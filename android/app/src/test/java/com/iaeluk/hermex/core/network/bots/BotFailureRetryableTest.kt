package com.iaeluk.hermex.core.network.bots

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BotFailureRetryableTest {

    @Test
    fun refusalsTheUserMustActOnAreNotRetryable() {
        val permanent = listOf(
            BotFailure.Unsupported,
            BotFailure.WrongIdentity,
            BotFailure.DifferentHost,
            BotFailure.InvalidAddress,
            BotFailure.NotDashboard,
            BotFailure.Blocked,
            BotFailure.BrowserSignIn,
            BotFailure.UpgradeRefused(426),
            BotFailure.Outdated("0.19.0"),
            BotFailure.Rejected(400),
            BotFailure.Rejected(401),
            BotFailure.Rejected(403),
            BotFailure.Rejected(404),
            BotFailure.Rejected(-32601),
            BotFailure.Rejected(4090),
            BotFailure.Rejected(4130),
        )
        for (error in permanent) {
            assertFalse("Expected $error to be permanent", BotConnectionAdvice.isRetryable(error))
        }
    }

    @Test
    fun serverErrorsRateLimitsAndTransportAreRetryable() {
        val transient = listOf<Throwable>(
            BotFailure.Rejected(408),
            BotFailure.Rejected(429),
            BotFailure.Rejected(500),
            BotFailure.Rejected(502),
            BotFailure.Rejected(503),
            BotFailure.Stale,
            BotFailure.Transport,
            BotFailure.MissingChat,
            IOException("boom"),
        )
        for (error in transient) {
            assertTrue("Expected $error to be retryable", BotConnectionAdvice.isRetryable(error))
        }
    }
}
