package com.iaeluk.hermex.ui.bots

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotChatViewModelTest {

    @Test
    fun initialStateIsLoading() {
        val vm = BotChatViewModel(profile = "helper", clientProvider = {
            throw UnsupportedOperationException("no client in unit test")
        })
        assertTrue(vm.state.value.isLoading)
        assertEquals("helper", vm.profile)
    }

    @Test
    fun failuresAreDescribed() {
        assertTrue(BotChatViewModel.describe(Exception("boom")).isNotEmpty())
    }
}
