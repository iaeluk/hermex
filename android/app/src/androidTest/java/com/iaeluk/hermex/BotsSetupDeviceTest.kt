package com.iaeluk.hermex

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.iaeluk.hermex.data.secure.BotConnectionStore
import com.iaeluk.hermex.data.secure.SecretStore
import com.iaeluk.hermex.data.secure.ServerRegistry
import com.iaeluk.hermex.ui.bots.BotSetupRoute
import com.iaeluk.hermex.ui.bots.BotSetupViewModel
import com.iaeluk.hermex.ui.theme.HermexTheme
import org.junit.Rule
import org.junit.Test

class BotsSetupDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class MemorySecretStore : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clearPrefix(prefix: String) { values.keys.removeAll { it.startsWith(prefix) } }
        override fun update(transform: (Map<String, String>) -> Map<String, String>) {
            val updated = transform(values.toMap())
            values.clear()
            values.putAll(updated)
        }
    }

    @Test
    fun setupFormShowsAddressAndAdvancesOnProbeError() {
        val secrets = MemorySecretStore()
        val viewModel = BotSetupViewModel(ServerRegistry(secrets), BotConnectionStore(secrets))

        composeRule.setContent {
            HermexTheme {
                BotSetupRoute(viewModel = viewModel, onDone = {}, onBack = {})
            }
        }

        composeRule.onNodeWithTag("bots_setup").assertIsDisplayed()
        composeRule.onNodeWithTag("bots_setup_address").assertIsDisplayed()
        composeRule.onNodeWithTag("bots_setup_address").performTextInput("not a url with spaces")
        composeRule.onNodeWithTag("bots_setup_probe").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Connect a Hermes host", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // A bad address keeps the form on the address step with an error.
        composeRule.onNodeWithTag("bots_setup_address").assertIsDisplayed()
    }
}
