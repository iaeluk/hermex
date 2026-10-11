package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.BotLiveStatus
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.core.network.hermes.HermesCall
import com.iaeluk.hermex.core.network.bots.parseBotJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotInboxViewModelTest {

    private class FakeClient(
        val roster: BotJson?,
        val active: BotJson?,
        val failActiveWith: Throwable? = null,
    ) {
        val calls = mutableListOf<String>()
        var connected = false
        var closed = false
        var onEvent: ((BotJson) -> Unit)? = null
        var onDisconnect: ((BotFailure) -> Unit)? = null

        val client: BotClient get() = throw UnsupportedOperationException("wired via calls")
    }

    @Test
    fun statusMapping() {
        assertEquals(BotLiveStatus.WAITING, BotInboxViewModel.statusOf("waiting"))
        assertEquals(BotLiveStatus.WORKING, BotInboxViewModel.statusOf("working"))
        assertEquals(BotLiveStatus.WORKING, BotInboxViewModel.statusOf("starting"))
        assertEquals(BotLiveStatus.WORKING, BotInboxViewModel.statusOf("streaming"))
        assertEquals(BotLiveStatus.IDLE, BotInboxViewModel.statusOf("resuming"))
        assertEquals(BotLiveStatus.IDLE, BotInboxViewModel.statusOf(null))
    }

    @Test
    fun urgencyOrder() {
        assertEquals(
            BotLiveStatus.WAITING,
            BotInboxViewModel.moreUrgent(BotLiveStatus.WORKING, BotLiveStatus.WAITING),
        )
        assertEquals(
            BotLiveStatus.WORKING,
            BotInboxViewModel.moreUrgent(BotLiveStatus.IDLE, BotLiveStatus.WORKING),
        )
    }

    @Test
    fun startFailsClosedWhenConnectRefuses() = runBlocking {
        val vm = BotInboxViewModel(clientProvider = {
            throw BotFailure.Transport
        })
        vm.start()
        assertTrue(vm.state.value.error != null)
        assertTrue(!vm.state.value.isConnected)
    }
}
