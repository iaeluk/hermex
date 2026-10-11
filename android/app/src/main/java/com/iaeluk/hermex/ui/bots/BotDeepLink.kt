package com.iaeluk.hermex.ui.bots

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * `hermes-agent://bot?server=…&connection=…&profile=…[&conversation=…]`, the one
 * bot route. Identity only, never a display name. A link is dropped rather than
 * opening the wrong bot: profile is required, server/connection narrow it.
 *
 * Push decision (Fase 4): local completion notifications now (the chat and room
 * screens post through StreamStatusNotifier when enabled); remote FCM comes
 * later with the hermex-push host plugin + Firebase, reusing these routes for
 * tap routing.
 */
data class BotDestination(
    val server: String? = null,
    val connection: String? = null,
    val profile: String,
    val conversation: String? = null,
) {
    companion object {
        const val SCHEME = "hermes-agent"
        const val HOST = "bot"

        fun build(profile: String, server: String? = null, connection: String? = null): String? {
            val clean = profile.trim().takeIf { it.isNotEmpty() } ?: return null
            val out = StringBuilder("$SCHEME://$HOST?profile=${encode(clean)}")
            server?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("&server=${encode(it)}") }
            connection?.trim()?.takeIf { it.isNotEmpty() }?.let { out.append("&connection=${encode(it)}") }
            return out.toString()
        }

        fun parse(url: String?): BotDestination? {
            if (url.isNullOrBlank()) return null
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            if (!uri.host.equals(HOST, ignoreCase = true)) return null
            val query = uri.rawQuery ?: return null
            val items = query.split("&").mapNotNull { part ->
                val eq = part.indexOf("=")
                if (eq < 0) return@mapNotNull null
                val name = decode(part.substring(0, eq))
                val value = decode(part.substring(eq + 1))
                name to value
            }.toMap()
            val profile = items["profile"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return BotDestination(
                server = items["server"]?.trim()?.takeIf { it.isNotEmpty() },
                connection = items["connection"]?.trim()?.takeIf { it.isNotEmpty() },
                profile = profile,
                conversation = items["conversation"]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")

        private fun decode(v: String): String =
            runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
    }
}
