package com.iaeluk.hermex.core.network.hermes

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The direct-Hermes REST surface the connection layer needs, built the way the host
 * expects: the dashboard root as base, JSON bodies with `Content-Type: application/json`.
 * Grows with the Bot screens (sessions, cron, skills, …), like `HermesREST` in the iOS
 * port.
 */
sealed class HermesRest {
    abstract fun request(base: HttpUrl): Request

    /** The public dashboard status; the sign-in reads it before any credential goes out. */
    object Status : HermesRest() {
        override fun request(base: HttpUrl): Request = get(base.route("api/status"))
    }

    /** The public health route, answered before the host's auth gate. */
    object Health : HermesRest() {
        override fun request(base: HttpUrl): Request = get(base.route("api/health"))
    }

    /** The basic sign-in the host's auth gate takes. */
    data class Login(val username: String, val password: String) : HermesRest() {
        override fun request(base: HttpUrl): Request = send(
            "POST",
            base.route("auth/password-login"),
            buildJsonObject {
                put("provider", "basic")
                put("username", username)
                put("password", password)
            }.toString(),
        )
    }

    /** The signed-in identity; its `provider` must read back as `basic`. */
    object Identity : HermesRest() {
        override fun request(base: HttpUrl): Request = get(base.route("api/auth/me"))
    }

    /** Mints one single-use ticket for the gateway upgrade. */
    object Ticket : HermesRest() {
        override fun request(base: HttpUrl): Request = send("POST", base.route("api/auth/ws-ticket"), "{}")
    }

    companion object {
        private val jsonMediaType = "application/json".toMediaType()

        /**
         * The gateway socket's upgrade for one ticket: `api/ws` on the address, offering
         * `hermes-gateway-v1` and the ticket as subprotocols. OkHttp carries the upgrade
         * over the plain `http`/`https` URL (there is no separate `ws`/`wss` form in this
         * stack, unlike the iOS port), and the handshake reads the subprotocols from this
         * request header.
         */
        fun gatewayUpgrade(base: HttpUrl, ticket: String): Request = Request.Builder()
            .url(base.route("api/ws"))
            .header("Sec-WebSocket-Protocol", "hermes-gateway-v1, hermes-gateway-ticket.$ticket")
            .build()

        private fun HttpUrl.route(path: String): HttpUrl =
            newBuilder().addPathSegments(path).build()

        private fun get(url: HttpUrl): Request = Request.Builder().url(url).get().build()

        private fun send(method: String, url: HttpUrl, body: String): Request = Request.Builder()
            .url(url)
            .method(method, body.toRequestBody(jsonMediaType))
            .build()
    }
}
