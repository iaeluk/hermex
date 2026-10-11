package com.iaeluk.hermex.core.network.hermes

import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

class HermesRestTest {

    private val base = "https://example.com:9119".toHttpUrl()

    @Test
    fun publicRoutesSitOnTheDashboardRoot() {
        assertEquals("/api/status", HermesRest.Status.request(base).url.encodedPath)
        assertEquals("/api/health", HermesRest.Health.request(base).url.encodedPath)
        assertEquals("/api/auth/me", HermesRest.Identity.request(base).url.encodedPath)
    }

    @Test
    fun loginPostsTheBasicProviderBody() {
        val request = HermesRest.Login("user", "pa ss").request(base)

        assertEquals("POST", request.method)
        assertEquals("/auth/password-login", request.url.encodedPath)
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        assertEquals("""{"provider":"basic","username":"user","password":"pa ss"}""", buffer.readUtf8())
    }

    @Test
    fun ticketPostsAnEmptyJsonObject() {
        val request = HermesRest.Ticket.request(base)

        assertEquals("POST", request.method)
        assertEquals("/api/auth/ws-ticket", request.url.encodedPath)
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        assertEquals("{}", buffer.readUtf8())
    }

    @Test
    fun gatewayUpgradeOffersTheSubprotocolsWithTheTicket() {
        val request = HermesRest.gatewayUpgrade(base, "tok-1")

        assertEquals("https://example.com:9119/api/ws", request.url.toString())
        assertEquals("hermes-gateway-v1, hermes-gateway-ticket.tok-1", request.header("Sec-WebSocket-Protocol"))
    }
}
