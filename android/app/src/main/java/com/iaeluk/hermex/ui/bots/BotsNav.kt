package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iaeluk.hermex.AppContainer
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.data.repository.AuthState
import com.iaeluk.hermex.data.secure.ServerKind

/**
 * Navigation helpers that build the Bots screens' ViewModels from the container.
 * Webui screens never touch these; Hermes screens never touch webui clients.
 */

/** The saved Hermes record + a live client for [serverId], or null when not saved yet. */
fun AppContainer.botsClient(serverId: String): BotClient? {
    val saved = botConnectionStore.load(serverId) ?: return null
    return BotClient(hermesConnections.connectionFor(serverId, saved))
}

@Composable
fun BotsSetupScreen(
    container: AppContainer,
    onDone: (String) -> Unit,
    onBack: () -> Unit,
) {
    val viewModel = remember(container) {
        BotSetupViewModel(container.registry, container.botConnectionStore)
    }
    BotSetupRoute(viewModel = viewModel, onDone = onDone, onBack = onBack)
}

@Composable
fun BotsHomeList(
    authState: AuthState,
    container: AppContainer,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCreate: () -> Unit = {},
    onOpenRooms: () -> Unit = {},
    onBack: () -> Unit,
) {
    val loggedIn = authState as? AuthState.LoggedIn ?: return
    val serverId = loggedIn.account.id
    val client = remember(serverId) { container.botsClient(serverId) }
    if (client == null) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This Hermes server has no saved connection.")
            Button(onClick = onOpenSetup) { Text("Connect") }
        }
        return
    }
    val viewModel = remember(serverId) { BotInboxViewModel(clientProvider = { client }) }
    BotsInboxRoute(
        viewModel = viewModel,
        onOpenChat = onOpenChat,
        onOpenSettings = onOpenSettings,
        onOpenCreate = onOpenCreate,
        onOpenRooms = onOpenRooms,
        onBack = onBack,
    )
}

@Composable
fun BotsCreateScreen(
    container: AppContainer,
    serverId: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val client = container.botsClient(serverId) ?: return
    val viewModel = remember(serverId) { BotProfileViewModel(clientProvider = { client }) }
    BotProfileRoute(viewModel = viewModel, title = "New bot", onSaved = onSaved, onBack = onBack)
}

@Composable
fun BotsEditScreen(
    container: AppContainer,
    serverId: String,
    profile: String,
    onSaved: () -> Unit,
    onBack: () -> Unit,
) {
    val client = container.botsClient(serverId) ?: return
    val viewModel = remember(serverId, profile) {
        BotProfileViewModel(clientProvider = { client }, existing = profile)
    }
    BotProfileRoute(viewModel = viewModel, title = "Edit bot", onSaved = onSaved, onBack = onBack)
}

@Composable
fun BotsRoomsScreen(
    container: AppContainer,
    serverId: String,
    onOpenRoom: (String) -> Unit,
    onOpenCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val client = container.botsClient(serverId) ?: return
    val viewModel = remember(serverId) { BotRoomsViewModel(clientProvider = { client }) }
    BotRoomsRoute(viewModel = viewModel, onOpenRoom = onOpenRoom, onOpenCreate = onOpenCreate, onBack = onBack)
}

@Composable
fun BotsRoomScreen(
    container: AppContainer,
    serverId: String,
    roomId: String,
    onDisbanded: () -> Unit,
    onBack: () -> Unit,
) {
    val client = container.botsClient(serverId) ?: return
    val viewModel = remember(serverId, roomId) { BotRoomViewModel(clientProvider = { client }, roomId = roomId) }
    BotRoomRoute(viewModel = viewModel, onDisbanded = onDisbanded, onBack = onBack)
}

@Composable
fun BotsChatScreen(
    container: AppContainer,
    serverId: String,
    profile: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val client = remember(serverId) { container.botsClient(serverId) }
    if (client == null) {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("This Hermes server has no saved connection.")
            Button(onClick = onBack) { Text("Back") }
        }
        return
    }
    val drafts = remember(context, serverId, profile) { StoreBotDrafts(context, serverId, profile) }
    val viewModel = remember(serverId, profile) {
        BotChatViewModel(profile = profile, clientProvider = { client }, drafts = drafts)
    }
    BotChatRoute(viewModel = viewModel, serverId = serverId, onBack = onBack)
}

private class StoreBotDrafts(
    context: android.content.Context,
    private val serverId: String,
    private val profile: String,
) : BotChatViewModel.BotDrafts {
    private val store = BotDraftStore(context)
    override suspend fun load(): String = store.current(serverId, profile)
    override suspend fun save(text: String) = store.save(serverId, profile, text)
}
