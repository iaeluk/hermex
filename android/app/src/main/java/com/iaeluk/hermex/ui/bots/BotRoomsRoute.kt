package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iaeluk.hermex.ui.chat.StreamStatusNotifier
import kotlinx.coroutines.launch

@Composable
fun BotRoomsRoute(
    viewModel: BotRoomsViewModel,
    onOpenRoom: (String) -> Unit,
    onOpenCreate: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(viewModel) { viewModel.start() }
    Column(Modifier.fillMaxSize().testTag("bots_rooms")) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Rooms", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenCreate) { Text("New") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        when {
            state.isLoading -> CircularProgressIndicator(Modifier.padding(16.dp))
            state.error != null -> {
                Text(state.error ?: "", Modifier.padding(16.dp))
                Button(onClick = { scope.launch { viewModel.start() } }) { Text("Retry") }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(state.rooms, key = { it.id }) { room ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenRoom(room.id) }
                            .padding(16.dp).testTag("bots_room_${room.id}"),
                    ) {
                        Text(room.name, style = MaterialTheme.typography.titleMedium)
                        if (room.members.isNotEmpty()) {
                            Text(
                                room.members.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BotRoomRoute(
    viewModel: BotRoomViewModel,
    serverId: String = "",
    onDisbanded: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notifier = remember(context) { StreamStatusNotifier(context) }
    var lastNotified by remember { androidx.compose.runtime.mutableStateOf(0) }
    LaunchedEffect(viewModel) { viewModel.start() }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastNotified) {
            lastNotified = state.messages.size
            if (serverId.isNotBlank() && notifier.canPostCompletionNotifications()) {
                notifier.showResponseComplete(serverId, "room:" + viewModel.roomId)
            }
        }
    }
    Column(Modifier.fillMaxSize().testTag("bots_room_detail")) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            OutlinedButton(
                onClick = { scope.launch { if (viewModel.disband()) onDisbanded() } },
                modifier = Modifier.testTag("bots_room_disband"),
            ) { Text("Disband") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            items(state.messages) { message ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (message.author.isNotBlank()) {
                        Text(message.author, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(message.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::updateInput,
                placeholder = { Text("Message the room") },
                modifier = Modifier.weight(1f).testTag("bots_room_input"),
            )
            if (state.isWorking) {
                OutlinedButton(onClick = { scope.launch { viewModel.stop() } }) { Text("Stop") }
            } else {
                Button(
                    onClick = { scope.launch { viewModel.send() } },
                    enabled = state.input.isNotBlank(),
                    modifier = Modifier.testTag("bots_room_send"),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
fun BotRoomCreateRoute(
    membersHint: String,
    onCreate: (String, List<String>) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var members by remember { mutableStateOf(membersHint) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().padding(16.dp).testTag("bots_room_create"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("New room", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        OutlinedTextField(
            value = name, onValueChange = { name = it; error = null },
            label = { Text("Room name") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("bots_room_create_name"),
        )
        OutlinedTextField(
            value = members, onValueChange = { members = it; error = null },
            label = { Text("Members (comma separated, 2-6)") }, minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("bots_room_create_members"),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val parsed = members.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val problem = com.iaeluk.hermex.core.network.bots.BotRoom.validateMembers(parsed)
                    ?: if (name.trim().isEmpty()) "Give the room a name." else null
                if (problem != null) error = problem
                else onCreate(name.trim(), parsed)
            },
            modifier = Modifier.testTag("bots_room_create_save"),
        ) { Text("Create") }
    }
}
