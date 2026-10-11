package com.iaeluk.hermex.core.network.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BotConnectionAddressTest {

    private fun parsesTo(expected: String, input: String) {
        assertEquals("for “$input”", expected, BotConnection.address(input))
    }

    @Test
    fun plainHostnameDefaultsToHttps() {
        parsesTo("https://example.com", "example.com")
    }

    @Test
    fun hostnameWithPortDefaultsToHttps() {
        parsesTo("https://example.com:9119", "example.com:9119")
    }

    @Test
    fun privateAndLocalHostsDefaultToHttp() {
        parsesTo("http://192.168.1.5", "192.168.1.5")
        parsesTo("http://10.0.0.7", "10.0.0.7")
        parsesTo("http://172.16.5.5", "172.16.5.5")
        parsesTo("http://100.64.1.1", "100.64.1.1")
        parsesTo("http://169.254.1.1", "169.254.1.1")
        parsesTo("http://127.0.0.1", "127.0.0.1")
        parsesTo("http://localhost", "localhost")
        parsesTo("http://localhost:9119", "localhost:9119")
        parsesTo("http://nas.local", "nas.local")
        parsesTo("http://myhost", "myhost")
    }

    @Test
    fun publicHostsDefaultToHttps() {
        parsesTo("https://8.8.8.8", "8.8.8.8")
        parsesTo("https://172.32.0.1", "172.32.0.1")
        parsesTo("https://100.128.0.1", "100.128.0.1")
    }

    @Test
    fun ipv6LiteralsAreBracketedAndLocalOnesDefaultToHttp() {
        parsesTo("http://[::1]", "::1")
        parsesTo("http://[::1]", "[::1]")
        parsesTo("http://[fd00::5]:9119", "[fd00::5]:9119")
        parsesTo("https://[2001:db8::1]", "[2001:db8::1]")
    }

    @Test
    fun wsSchemesBecomeHttpSchemes() {
        parsesTo("http://example.com:9119", "ws://example.com:9119")
        parsesTo("https://example.com:9119", "wss://example.com:9119")
    }

    @Test
    fun dashboardPathsAreCleared() {
        parsesTo("https://example.com", "https://example.com/")
        parsesTo("https://example.com", "https://example.com/api/status")
        parsesTo("https://example.com", "https://example.com/login")
        parsesTo("https://example.com:9119", "https://example.com:9119/sessions/123")
    }

    @Test
    fun otherPathsAreRefused() {
        val error = assertThrows(BotAddressError.Path::class.java) {
            BotConnection.address("https://example.com/foo")
        }
        assertEquals("/foo", error.path)

        val nested = assertThrows(BotAddressError.Path::class.java) {
            BotConnection.address("https://example.com/proxy/hermes")
        }
        assertEquals("/proxy/hermes", nested.path)
    }

    @Test
    fun queryAndFragmentAreDropped() {
        parsesTo("https://example.com:9119", "https://example.com:9119?token=x#frag")
    }

    @Test
    fun hostIsLowercased() {
        parsesTo("https://example.com:9119", "https://EXAMPLE.com:9119")
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        parsesTo("https://example.com", "  example.com  ")
    }

    @Test
    fun invalidAddressesAreRefused() {
        for (input in listOf(
            "",
            "   ",
            "exa mple.com",
            "https://",
            "https://user:pass@example.com",
            "ftp://example.com",
            "999.1.1.1",
            "1.2.3",
            "https://example.com:0",
            "https://example.com:99999",
        )) {
            assertThrows("Expected refusal for “$input”", BotAddressError.Invalid::class.java) {
                BotConnection.address(input)
            }
        }
    }
}
