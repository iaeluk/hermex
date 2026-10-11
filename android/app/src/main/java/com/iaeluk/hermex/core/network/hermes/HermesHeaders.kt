package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.CustomHeader
import okhttp3.HttpUrl
import okhttp3.Request

/**
 * Request headers for one direct-Hermes origin, admitted by the policy the host needs:
 * no name the transport owns (the HTTP stack and cookie jar set those), no name Hermes
 * reads for its own checks (`Origin` for the gateway upgrade, `X-Forwarded-Prefix` for
 * the session cookie's path, `X-Hermes-Session-Token`), and no `Bearer` authorization,
 * which Hermes also reads as its session token. Any other `Authorization` value passes,
 * such as Cloudflare Access's single-header JSON service token. The only source is the
 * Hermes connection's own record, never the webui's custom headers.
 */
class HermesHeaders private constructor(val values: List<CustomHeader>) {

    sealed class Rejection(message: String) : Exception(message) {
        data class Malformed(val name: String) : Rejection(
            "“$name” isn't a valid header: names can't contain spaces or colons, and " +
                "values must fit on one line. Fix or remove it to connect."
        )

        data class Reserved(val name: String) : Rejection(
            "“$name” is reserved for Hermex and Hermes, so it can't be a connection " +
                "header. Remove this header to connect."
        )

        object Bearer : Rejection(
            "Hermes reads Authorization: Bearer as its own sign-in and refuses it. " +
                "Remove this header to connect."
        )
    }

    companion object {
        val none = HermesHeaders(emptyList())

        private val reserved = setOf(
            "host", "connection", "upgrade", "content-length", "transfer-encoding", "te", "trailer",
            "keep-alive", "proxy-connection", "proxy-authorization", "cookie",
            "origin", "x-forwarded-prefix", "x-hermes-session-token",
        )

        /** Validates a saved or typed header list; a policy refusal throws [Rejection]. */
        fun of(headers: List<CustomHeader>): HermesHeaders {
            for (header in headers) {
                val name = header.name.trim()
                val normalized = name.lowercase()
                if (normalized.isEmpty() || header.value.isBlank() || !header.hasValidHttpSyntax) {
                    throw Rejection.Malformed(name)
                }
                if (normalized in reserved || normalized.startsWith("sec-websocket-")) {
                    throw Rejection.Reserved(name)
                }
                val scheme = header.value.trim().split(WHITESPACE).firstOrNull()?.lowercase()
                if (normalized == "authorization" && scheme == "bearer") throw Rejection.Bearer
            }
            return HermesHeaders(headers)
        }

        /**
         * The headers a saved record carries. A saved list the policy now refuses sends
         * none: the connection form shows why and keeps Connect off until it is fixed.
         */
        fun saved(saved: List<CustomHeader>?): HermesHeaders =
            try {
                of(saved ?: emptyList())
            } catch (_: Rejection) {
                none
            }

        /**
         * Same scheme, host and port as `origin`. The gateway socket is an `http`/`https`
         * URL in this stack: OkHttp carries the WebSocket upgrade over it, so there is no
         * separate `ws`/`wss` form to normalize.
         */
        fun isSameOrigin(url: HttpUrl, origin: HttpUrl): Boolean =
            url.scheme == origin.scheme &&
                url.host.equals(origin.host, ignoreCase = true) &&
                url.port == origin.port

        private val WHITESPACE = Regex("\\s+")
    }

    /**
     * `request` with these headers added when it is for `origin`. A header the request
     * already carries, such as the JSON content type or the gateway subprotocols, keeps
     * its built-in value.
     */
    fun applied(request: Request, origin: HttpUrl): Request {
        if (values.isEmpty() || !isSameOrigin(request.url, origin)) return request
        val builder = request.newBuilder()
        for (header in values) {
            val name = header.name.trim()
            if (request.header(name) == null) builder.header(name, header.value)
        }
        return builder.build()
    }
}
