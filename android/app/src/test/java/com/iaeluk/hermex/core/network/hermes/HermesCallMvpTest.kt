package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesCallMvpTest {

    @Test
    fun methodsMatchTheHost() {
        assertEquals("profiles.list", HermesCall.ProfilesList(true).method)
        assertEquals("session.list", HermesCall.SessionList("default").method)
        assertEquals("session.create", HermesCall.SessionCreate("default").method)
        assertEquals("session.title", HermesCall.SessionTitle("s1").method)
        assertEquals("session.resume", HermesCall.SessionResume("default", "s1").method)
        assertEquals("session.active_list", HermesCall.SessionActiveList.method)
        assertEquals("session.most_recent", HermesCall.SessionMostRecent("default").method)
        assertEquals("prompt.submit", HermesCall.PromptSubmit("s1", "hi").method)
        assertEquals("session.steer", HermesCall.SessionSteer("s1", "hi").method)
        assertEquals("session.redirect", HermesCall.SessionRedirect("s1", "hi").method)
        assertEquals("session.interrupt", HermesCall.SessionInterrupt("s1").method)
        assertEquals("session.close", HermesCall.SessionClose("r1").method)
    }

    @Test
    fun paramsCarryTheCanonicalBotChatTitle() {
        val list = HermesCall.SessionList("default").params()
        assertEquals("Bot Chat", list["title"]?.toString()?.trim('"'))
        assertEquals(HermesCall.BOT_CHAT_TITLE, "Bot Chat")
    }

    @Test
    fun blankValuesAreRefused() {
        for (call in listOf(
            HermesCall.SessionResume("", "s1"),
            HermesCall.SessionResume("p", ""),
            HermesCall.PromptSubmit("", "hi"),
            HermesCall.PromptSubmit("s1", "  "),
            HermesCall.SessionSteer("s1", ""),
            HermesCall.SessionInterrupt(""),
            HermesCall.SessionClose("  "),
            HermesCall.SessionMostRecent(""),
        )) {
            val failure = runCatching { call.params() }.exceptionOrNull()
            assertTrue("expected refusal for $call", failure is BotFailure.Unsupported)
        }
    }

    @Test
    fun approvalRespondCarriesTheHostVocabulary() {
        val call = HermesCall.ApprovalRespond("run-1", "q-1", "session")
        assertEquals("approval.respond", call.method)
        val params = call.params()
        assertEquals("run-1", params["session_id"]?.toString()?.trim('"'))
        assertEquals("q-1", params["request_id"]?.toString()?.trim('"'))
        assertEquals("session", params["choice"]?.toString()?.trim('"'))
    }

    @Test
    fun approvalRespondRefusesUnknownChoices() {
        val failure = runCatching {
            HermesCall.ApprovalRespond("run-1", "q-1", "sometimes").params()
        }.exceptionOrNull()
        assertTrue(failure is BotFailure.Unsupported)
    }

    @Test
    fun readsAreCancellationSafeAndLiveReadsTimeOutLocally() {
        assertTrue(HermesCall.ProfilesList(true).isCancellationSafe)
        assertTrue(HermesCall.SessionList("p").isCancellationSafe)
        assertTrue(HermesCall.SessionActiveList.isCancellationSafe)
        assertTrue(HermesCall.SessionMostRecent("p").isCancellationSafe)
        assertTrue(!HermesCall.PromptSubmit("s", "hi").isCancellationSafe)

        assertTrue(HermesCall.SessionActiveList.timesOutLocally)
        assertTrue(HermesCall.SessionMostRecent("p").timesOutLocally)
        assertTrue(!HermesCall.PromptSubmit("s", "hi").timesOutLocally)
    }
}
