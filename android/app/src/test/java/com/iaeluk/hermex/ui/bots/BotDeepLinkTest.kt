package com.iaeluk.hermex.ui.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotDeepLinkTest {
    @Test
    fun parsesABotLink() {
        val dest = BotDestination.parse("hermes-agent://bot?server=https%3A%2F%2Fh&connection=abc&profile=Ada%20Bot")
        assertEquals("Ada Bot", dest?.profile)
        assertEquals("abc", dest?.connection)
    }

    @Test
    fun dropsLinksWithoutProfile() {
        assertNull(BotDestination.parse("hermes-agent://bot?server=https://h"))
        assertNull(BotDestination.parse("hermes-agent://session?id=1"))
        assertNull(BotDestination.parse(null as String?))
    }

    @Test
    fun buildRoundTrips() {
        val url = BotDestination.build("Ada", server = "https://h")!!
        assertEquals("Ada", BotDestination.parse(url)?.profile)
    }
}
