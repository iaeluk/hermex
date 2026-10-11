package com.iaeluk.hermex.ui.bots

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.iaeluk.hermex.core.network.bots.BotInboxRow
import com.iaeluk.hermex.core.network.bots.BotLiveStatus
import com.iaeluk.hermex.core.network.hermes.BotClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Bots inbox: roster in display order with live status. Opens [onOpenChat]
 * with the tapped profile id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BotsInboxRoute(
    viewModel: BotInboxViewModel,
    onOpenChat: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.onRosterChanged = { scope.launch { viewModel.refresh() } }
        try {
            viewModel.start()
        } finally {
            viewModel.onRosterChanged = null
        }
    }
    LaunchedEffect(state.isConnected, state.rows) {
        while (viewModel.shouldPoll()) {
            delay(5_000)
            viewModel.refresh()
        }
    }

    Column(Modifier.fillMaxSize().testTag("bots_inbox")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Bots", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenSettings) { Text("Settings") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null && state.rows.isEmpty() -> Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.error ?: "Could not load bots.")
                Button(onClick = { scope.launch { viewModel.start() } }) { Text("Retry") }
            }
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try { viewModel.refresh() } finally { refreshing = false }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.profile.id }) { row ->
                        BotInboxRowItem(row = row, onClick = { onOpenChat(row.profile.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BotInboxRowItem(row: BotInboxRow, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("bots_inbox_row_${row.profile.id}"),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.profile.rowTitle(), style = MaterialTheme.typography.titleMedium)
            BotLiveBadge(status = row.status, unread = row.unread)
        }
        row.profile.preview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun BotLiveBadge(status: BotLiveStatus, unread: Boolean) {
    val text = when (status) {
        BotLiveStatus.WAITING -> "Waiting for you"
        BotLiveStatus.WORKING -> "Working"
        BotLiveStatus.IDLE -> if (unread) "Unread" else null
    } ?: return
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = when (status) {
            BotLiveStatus.WAITING -> MaterialTheme.colorScheme.error
            BotLiveStatus.WORKING -> MaterialTheme.colorScheme.primary
            BotLiveStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
