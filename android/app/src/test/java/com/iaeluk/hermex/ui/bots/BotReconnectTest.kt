package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class BotReconnectTest {
    @Test
    fun backoffIsQuickOnBlipsAndCalmAfter() {
        assertEquals(1_000L, BotChatViewModel.reconnectDelayMs(0))
        assertEquals(2_000L, BotChatViewModel.reconnectDelayMs(1))
        assertEquals(4_000L, BotChatViewModel.reconnectDelayMs(2))
        assertEquals(8_000L, BotChatViewModel.reconnectDelayMs(3))
        assertEquals(8_000L, BotChatViewModel.reconnectDelayMs(99))
    }

    @Test
    fun dropsGiveUpAfterFiveTries() {
        assertEquals(5, BotChatViewModel.MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun transportErrorNoLongerDemandsReopen() {
        assertEquals("Connection lost.", BotChatViewModel.describe(BotFailure.Transport))
    }
}
