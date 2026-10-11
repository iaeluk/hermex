package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iaeluk.hermex.ui.chat.StreamStatusNotifier
import kotlinx.coroutines.launch

/**
 * One bot's chat: transcript, composer with Send/Steer/Interrupt, drafts.
 * Notifies on turn completion when the app allows it.
 */
@Composable
fun BotChatRoute(
    viewModel: BotChatViewModel,
    serverId: String,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notifier = remember(context) { StreamStatusNotifier(context) }
    val listState = rememberLazyListState()
    var lastNotifiedTurns by remember { mutableStateOf(0) }

    LaunchedEffect(viewModel) { viewModel.start() }
    DisposableEffect(viewModel) {
        onDispose {
            scope.launch { viewModel.persistDraft() }
            viewModel.stop()
        }
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }
    LaunchedEffect(state.completedTurns) {
        if (state.completedTurns > lastNotifiedTurns) {
            lastNotifiedTurns = state.completedTurns
            val sessionKey = state.runtime ?: viewModel.profile
            if (notifier.canPostCompletionNotifications()) {
                notifier.showResponseComplete(serverId, sessionKey)
            }
        }
    }

    Column(Modifier.fillMaxSize().testTag("bots_chat")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(viewModel.profile, style = MaterialTheme.typography.titleMedium)
            if (state.isRunning) Text("Working", color = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier)
        }

        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.messages.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "Could not open chat.")
                Button(onClick = { scope.launch { viewModel.start() } }) { Text("Retry") }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages) { message ->
                        BotChatBubble(message)
                    }
                }
                state.pendingApprovals.forEach { approval ->
                    BotApprovalCard(
                        approval = approval,
                        onAnswer = { choice -> scope.launch { viewModel.answerApproval(approval.requestId, choice) } },
                    )
                }
                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = viewModel::updateInput,
                        placeholder = { Text("Message") },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        modifier = Modifier.weight(1f).testTag("bots_chat_input"),
                    )
                    if (state.isRunning) {
                        OutlinedButton(
                            onClick = { scope.launch { viewModel.interrupt() } },
                            modifier = Modifier.testTag("bots_chat_stop"),
                        ) { Text("Stop") }
                        OutlinedButton(
                            onClick = { scope.launch { viewModel.steer() } },
                            enabled = state.input.isNotBlank(),
                        ) { Text("Steer") }
                    } else {
                        Button(
                            onClick = { scope.launch { viewModel.send() } },
                            enabled = state.input.isNotBlank(),
                            modifier = Modifier.testTag("bots_chat_send"),
                        ) { Text("Send") }
                    }
                }
                if (state.isRunning) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { viewModel.redirect() } },
                            enabled = state.input.isNotBlank(),
                        ) { Text("Redirect") }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}


@Composable
private fun BotApprovalCard(
    approval: com.iaeluk.hermex.core.network.bots.BotPendingApproval,
    onAnswer: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("bots_approval_${approval.requestId}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Needs your approval", style = MaterialTheme.typography.labelMedium)
        approval.command?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        approval.consequence?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            approval.choices.forEach { choice ->
                OutlinedButton(onClick = { onAnswer(choice) }) {
                    Text(approval.choiceLabel(choice))
                }
            }
        }
    }
}

@Composable
private fun BotChatBubble(message: BotChatMessage) {
    Column(
        Modifier.fillMaxWidth().padding(
            start = if (message.role == "user") 48.dp else 0.dp,
            end = if (message.role == "user") 0.dp else 48.dp,
        ),
    ) {
        Text(
            if (message.role == "user") "You" else "Assistant",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(message.text.ifEmpty { "..." }, style = MaterialTheme.typography.bodyMedium)
    }
}
