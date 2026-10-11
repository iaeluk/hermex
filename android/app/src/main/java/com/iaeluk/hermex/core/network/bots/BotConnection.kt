package com.iaeluk.hermex.core.network.bots

import com.iaeluk.hermex.core.network.CustomHeader
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import kotlinx.serialization.Serializable

/**
 * One direct-Hermes connection record: the dashboard root, credentials and the optional
 * Connection Headers a proxy in front of the host needs. The install id is the host's
 * public identity, trusted on first use and then a guard against an address that now
 * reaches a different host.
 *
 * Serializable so the per-server store keeps it in the encrypted secret storage, like
 * the iOS port's keychain record. Never the webui's custom headers.
 */
@Serializable
data class BotConnection(
    val id: String,
    var name: String,
    val address: String,
    val username: String,
    var password: String,
    var hermesVersion: String? = null,
    var installID: String? = null,
    var headers: List<CustomHeader>? = null,
) {
    /**
     * Called with the live `install_id` before any password is sent. Throws
     * [BotFailure.DifferentHost] only when both ids are known and differ; a record
     * without one, or a host that omits it this time, connects as before (trust on
     * first use). The id is public, so this catches an address that now reaches another
     * host, not an impostor.
     */
    fun requireSameInstall(live: String?) {
        val stored = installID ?: return
        if (live != null && stored != live) throw BotFailure.DifferentHost
    }

    companion object {
        /** The `install_id` a `/api/status` reply reports, or null when omitted or empty. */
        fun installIdIn(status: BotJson?): String? =
            status["install_id"].text?.takeIf { it.isNotEmpty() }

        /** First path segments a browser, config file or curl command adds to the dashboard's root. */
        private val dashboardPaths = setOf("login", "auth", "api", "chat", "sessions")

        /**
         * Parses the connection form's text into the dashboard root every request is
         * built from, so a saved address never has a path, query or fragment. A pasted
         * link is reduced to its root: `ws`/`wss` become `http`/`https`, the query and
         * fragment are dropped, and a path starting with a dashboard segment is cleared.
         * Any other path throws [BotAddressError.Path]; credentials, other schemes and
         * bad hosts or ports throw [BotAddressError.Invalid]. A missing scheme becomes
         * HTTPS, or HTTP for private and local hosts.
         */
        fun address(text: String): String {
            var value = text.trim()
            if (value.isEmpty() || value.any { it.isWhitespace() }) throw BotAddressError.Invalid
            val hasScheme = value.contains("://")
            if (!hasScheme) {
                // A bare IPv6 literal needs brackets; bracketed literals may include a port.
                if (isIpv6Literal(value)) value = "[$value]"
                value = "https://$value"
            }
            val uri = try {
                URI(value)
            } catch (_: Exception) {
                throw BotAddressError.Invalid
            }
            val scheme = when (uri.scheme?.lowercase()) {
                "ws" -> "http"
                "wss" -> "https"
                "http" -> "http"
                "https" -> "https"
                else -> throw BotAddressError.Invalid
            }
            val rawHost = uri.host?.takeIf { it.isNotEmpty() } ?: throw BotAddressError.Invalid
            if (uri.userInfo != null) throw BotAddressError.Invalid
            val port = uri.port
            if (port == 0 || port > 65535) throw BotAddressError.Invalid
            val plainHost = rawHost.removePrefix("[").removeSuffix("]")
            if (looksLikeIpv4(plainHost) && !isIpv4Literal(plainHost)) throw BotAddressError.Invalid
            var path = uri.path ?: ""
            while (path.endsWith("/")) path = path.dropLast(1)
            val firstSegment = path.split('/').firstOrNull { it.isNotEmpty() }
            if (firstSegment != null && firstSegment.lowercase() !in dashboardPaths) {
                throw BotAddressError.Path(path)
            }
            val lowerHost = plainHost.lowercase()
            val finalScheme = if (!hasScheme && defaultsToHttp(lowerHost)) "http" else scheme
            val hostForUrl = if (rawHost.startsWith("[")) "[$lowerHost]" else lowerHost
            return buildString {
                append(finalScheme).append("://").append(hostForUrl)
                if (port > 0) append(':').append(port)
            }
        }

        private fun looksLikeIpv4(value: String): Boolean =
            value.isNotEmpty() && value.all { it.isDigit() || it == '.' }

        private fun isIpv4Literal(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 &&
                    part.all { it.isDigit() } && part.toInt() in 0..255
            }
        }

        /**
         * Infer a scheme only for an omitted one, never as a retry after TLS fails:
         * private and link-local IPs, loopback and local hostnames get HTTP.
         */
        private fun defaultsToHttp(host: String): Boolean {
            if (isIpv4Literal(host)) return privateIpv4(host.split('.').map { it.toInt() })
            if (host.contains(':')) {
                val bytes = ipv6Bytes(host) ?: return false
                if (bytes.size == 4) return privateIpv4(bytes.map { it.toInt() and 0xFF })
                val b = bytes.map { it.toInt() and 0xFF }
                if (b.size != 16) return false
                val v4Mapped = b.take(10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF
                if (v4Mapped) return privateIpv4(b.takeLast(4))
                val loopback = b.take(15).all { it == 0 } && b[15] == 1
                return loopback ||
                    ((b[0] and 0xFE) == 0xFC) ||
                    (b[0] == 0xFE && (b[1] and 0xC0) == 0x80)
            }
            return host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                (!host.contains(".") && !host.contains(":"))
        }

        private fun privateIpv4(octets: List<Int>): Boolean =
            octets[0] == 10 || octets[0] == 127 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 169 && octets[1] == 254) ||
                (octets[0] == 100 && octets[1] in 64..127)

        private fun isIpv6Literal(value: String): Boolean {
            if (!value.contains(':')) return false
            if (!value.all { it in "0123456789abcdefABCDEF:." }) return false
            if (!value.contains("::") && value.count { it == ':' } < 7) return false
            return try {
                val parsed = InetAddress.getByName(value)
                parsed is Inet6Address || (parsed is Inet4Address && value.contains(':'))
            } catch (_: Exception) {
                false
            }
        }

        private fun ipv6Bytes(host: String): ByteArray? {
            if (!isIpv6Literal(host)) return null
            return try {
                InetAddress.getByName(host).address
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Why the connection form's address can't be used. Thrown only by [BotConnection.address],
 * so typing mistakes stay apart from [BotFailure] connection failures.
 */
sealed class BotAddressError(message: String) : Exception(message) {
    /** A path that isn't a dashboard page, as typed without trailing slashes. */
    data class Path(val path: String) : BotAddressError(
        "Remove “$path” from the address. Hermex needs the dashboard's main address, " +
            "like https://example.com:9119, and can't use a dashboard served under a path yet."
    )

    /** No usable HTTP or HTTPS host, or the address carries a username or password. */
    object Invalid : BotAddressError(
        "Enter the dashboard's HTTP or HTTPS address, like https://example.com:9119, " +
            "without a username or password."
    )
}
