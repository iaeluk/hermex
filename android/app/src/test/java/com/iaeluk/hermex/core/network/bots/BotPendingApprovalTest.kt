package com.iaeluk.hermex.core.network.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotPendingApprovalTest {

    @Test
    fun parsesAnApprovalEnvelope() {
        val envelope = parseBotJson(
            """{"id":"env-1","method":"approval","params":{""" +
                """"request_id":"q-1","command":"rm -rf /tmp/x","description":"Deletes files",""" +
                """"choices":["once","session","deny"],"pattern_keys":["rm"],"tool":"exec"}}"""
        )

        val approval = BotPendingApproval.fromEnvelope(envelope)

        assertEquals("q-1", approval?.requestId)
        assertEquals("env-1", approval?.envelopeId)
        assertEquals("rm -rf /tmp/x", approval?.command)
        assertEquals(listOf("once", "session", "deny"), approval?.choices)
        assertEquals("Allow session", approval?.choiceLabel("session"))
    }

    @Test
    fun unknownChoicesAreDroppedAndEmptyDefaults() {
        val envelope = parseBotJson(
            """{"id":"env-1","method":"approval","params":{"request_id":"q-1","choices":["sometimes"]}}"""
        )

        val approval = BotPendingApproval.fromEnvelope(envelope)

        assertEquals(listOf("once", "deny"), approval?.choices)
    }

    @Test
    fun missingRequestIdIsUnanswerable() {
        val envelope = parseBotJson("""{"id":"env-1","method":"approval","params":{}}""")

        assertNull(BotPendingApproval.fromEnvelope(envelope))
        assertNull(BotPendingApproval.fromEnvelope(null))
    }
}
