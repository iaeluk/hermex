package com.iaeluk.hermex.core.network.bots

import com.iaeluk.hermex.core.network.hermes.HermesCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotCompleteTest {
    @Test
    fun slashLineAdmission() {
        assertTrue(HermesCall.isSlashLine("/deploy prod"))
        assertTrue(!HermesCall.isSlashLine(" /deploy"))
        assertTrue(!HermesCall.isSlashLine("/ deploy"))
    }

    @Test
    fun candidatesTolerateShapes() {
        val reply = parseBotJson("""{"items":[{"name":"/deploy","label":"Deploy"},{"value":"/logs"}]}""")
        val got = BotComplete.candidates(reply)
        assertEquals(listOf("/deploy", "/logs"), got.map { it.insert })
        assertEquals("Deploy", got.first().display)
        assertEquals(0, BotComplete.candidates(parseBotJson("""{"items":[]}""")).size)
    }

    @Test
    fun fileAttachAndSlashMethods() {
        assertEquals("slash.exec", HermesCall.SlashExec("s", "/deploy prod").method)
        assertEquals("complete.slash", HermesCall.CompleteSlash("/deploy ", "s").method)
        assertEquals("complete.path", HermesCall.CompletePath("@src/M", "s", "p").method)
        assertEquals("file.attach", HermesCall.FileAttach("s", "a.txt", "data:text/plain;base64,QQ==").method)
    }
}
