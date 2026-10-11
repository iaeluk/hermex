package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.CustomHeader
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHeadersTest {

    private val origin = "https://example.com:9119".toHttpUrl()

    @Test
    fun reservedNamesAreRefused() {
        for (name in listOf(
            "Host",
            "Cookie",
            "Origin",
            "X-Hermes-Session-Token",
            "Sec-WebSocket-Protocol",
            "X-Forwarded-Prefix",
        )) {
            assertThrows("Expected refusal for “$name”", HermesHeaders.Rejection.Reserved::class.java) {
                HermesHeaders.of(listOf(CustomHeader(name, "value")))
            }
        }
    }

    @Test
    fun bearerAuthorizationIsRefused() {
        assertThrows(HermesHeaders.Rejection.Bearer::class.java) {
            HermesHeaders.of(listOf(CustomHeader("Authorization", "Bearer abc")))
        }
        assertThrows(HermesHeaders.Rejection.Bearer::class.java) {
            HermesHeaders.of(listOf(CustomHeader("authorization", "bearer   abc")))
        }
    }

    @Test
    fun otherAuthorizationValuesPass() {
        val headers = HermesHeaders.of(listOf(CustomHeader("Authorization", "CF-Access token")))
        assertEquals(1, headers.values.size)
    }

    @Test
    fun malformedHeadersAreRefused() {
        assertThrows(HermesHeaders.Rejection.Malformed::class.java) {
            HermesHeaders.of(listOf(CustomHeader("Bad Name", "value")))
        }
        assertThrows(HermesHeaders.Rejection.Malformed::class.java) {
            HermesHeaders.of(listOf(CustomHeader("X-Test", "line\nbreak")))
        }
        assertThrows(HermesHeaders.Rejection.Malformed::class.java) {
            HermesHeaders.of(listOf(CustomHeader("X-Test", "   ")))
        }
    }

    @Test
    fun savedFallsBackToNoneWhenThePolicyNowRejects() {
        val saved = HermesHeaders.saved(listOf(CustomHeader("Origin", "https://evil.example")))
        assertTrue(saved.values.isEmpty())
    }

    @Test
    fun appliedAddsHeadersOnlyForTheOrigin() {
        val request = Request.Builder().url("https://example.com:9119/api/status").build()
        val applied = HermesHeaders.of(listOf(CustomHeader("CF-Access-Client-Id", "id")))
            .applied(request, origin)
        assertEquals("id", applied.header("CF-Access-Client-Id"))

        val other = Request.Builder().url("https://other.example.com/api/status").build()
        val untouched = HermesHeaders.of(listOf(CustomHeader("CF-Access-Client-Id", "id")))
            .applied(other, origin)
        assertNull(untouched.header("CF-Access-Client-Id"))
    }

    @Test
    fun appliedKeepsAHeaderTheRequestAlreadyCarries() {
        val request = Request.Builder()
            .url("https://example.com:9119/api/status")
            .header("X-Test", "built-in")
            .build()
        val applied = HermesHeaders.of(listOf(CustomHeader("X-Test", "ours"))).applied(request, origin)
        assertEquals("built-in", applied.header("X-Test"))
    }

    @Test
    fun sameOriginComparesSchemeHostAndPort() {
        val sameHost = "https://example.com:9119/api/ws".toHttpUrl()
        assertTrue(HermesHeaders.isSameOrigin(sameHost, origin))

        val otherPort = "https://example.com:8443/api/ws".toHttpUrl()
        assertFalse(HermesHeaders.isSameOrigin(otherPort, origin))

        val otherScheme = "http://example.com:9119/api/ws".toHttpUrl()
        assertFalse(HermesHeaders.isSameOrigin(otherScheme, origin))
    }
}
