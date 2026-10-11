package com.iaeluk.hermex.core.network.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPendingRequestsTest {

    @Test
    fun parsesASingleQuestion() {
        val envelope = parseBotJson(
            """{"id":"env-q","method":"clarify","params":{"request_id":"q-9","question":"Which branch?","choices":["main","dev (Recommended)"]}}"""
        )
        val pending = BotPendingQuestion.fromEnvelope(envelope)
        assertEquals("q-9", pending?.requestId)
        assertEquals(1, pending?.questions?.size)
        assertEquals("dev", pending?.questions?.first()?.choices?.get(1)?.label)
        assertTrue(pending?.questions?.first()?.choices?.get(1)?.isRecommended == true)
    }

    @Test
    fun parsesABatchWithLockedAnswers() {
        val envelope = parseBotJson(
            """{"id":"env-b","method":"clarify","params":{"request_id":"q-10","questions":[{"qid":"a","question":"First?"},{"qid":"b","question":"Second?"}],"answers":{"a":"done"}}}"""
        )
        val pending = BotPendingQuestion.fromEnvelope(envelope)
        assertEquals(true, pending?.isBatch)
        assertEquals(1, pending?.unansweredCount)
    }

    @Test
    fun credentialAndDesktopParse() {
        val cred = BotPendingCredential.fromEnvelope(
            "secret",
            parseBotJson("""{"id":"env-s","method":"secret","params":{"request_id":"s-1","env_var":"TOKEN","prompt":"Paste it"}}"""),
        )
        assertEquals("TOKEN", cred?.envVar)
        assertEquals("Secret needed", cred?.title)

        val desktop = BotPendingDesktop.fromEnvelope(
            "terminal.read",
            parseBotJson("""{"id":"env-t","method":"terminal.read","params":{}}"""),
        )
        assertEquals("This bot is reading a terminal on the Mac.", desktop?.title)
        assertNull(BotPendingDesktop.fromEnvelope("sudo", parseBotJson("""{"id":"x","method":"sudo","params":{}}""")))
    }
}
