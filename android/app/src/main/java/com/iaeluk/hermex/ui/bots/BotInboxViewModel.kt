package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotInboxRow
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.BotLiveStatus
import com.iaeluk.hermex.core.network.bots.BotProfile
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.list
import com.iaeluk.hermex.core.network.bots.text
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.core.network.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BotsInboxUiState(
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val rows: List<BotInboxRow> = emptyList(),
    val error: String? = null,
)

/**
 * The Bots inbox: roster + live status, in display order. Testable without
 * Android: [clientProvider] makes the screen's [BotClient].
 *
 * Live status comes from `session.active_list`, matched by session key onto the
 * canonical chat each roster row carries. While any bot is busy the active list
 * is re-read every 5 s, like the iOS port; a stall fails only the read.
 */
class BotInboxViewModel(
    private val clientProvider: () -> BotClient,
) {
    private val _state = MutableStateFlow(BotsInboxUiState())
    val state: StateFlow<BotsInboxUiState> = _state

    private var client: BotClient? = null
    private var pollJob: Job? = null
    private var seenMarks = mutableMapOf<String, Double>()

    /** Fired on `sessions.changed`; the Route reloads (the ViewModel owns no scope). */
    var onRosterChanged: (() -> Unit)? = null

    suspend fun start() {
        _state.value = BotsInboxUiState(isLoading = true)
        try {
            val client = clientProvider().also { this.client = it }
            client.onEvent = { onEvent(it) }
            client.onDisconnect = { onDisconnect(it) }
            client.connect()
            _state.value = _state.value.copy(isConnected = true)
            refresh()
            startPolling()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = BotsInboxUiState(isLoading = false, error = describe(error))
        }
    }

    suspend fun refresh() {
        val client = client ?: return
        try {
            val roster = client.call(HermesCall.ProfilesList(includeSessions = true))
            val profiles = BotProfile.listIn(roster)
            val statuses = readLiveStatuses(client, profiles)
            val rows = profiles.map { profile ->
                BotInboxRow(
                    profile = profile,
                    status = statuses[profile.id] ?: BotLiveStatus.IDLE,
                    unread = profile.unread || statuses[profile.id] == BotLiveStatus.WAITING,
                    lastActiveEpochSeconds = profile.lastActiveEpochSeconds,
                )
            }
            _state.value = _state.value.copy(isLoading = false, rows = BotInboxRow.sorted(rows), error = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (_state.value.rows.isEmpty()) {
                _state.value = _state.value.copy(isLoading = false, error = describe(error))
            }
        }
    }

    fun markSeen(profileId: String, epochSeconds: Double) {
        seenMarks[profileId] = epochSeconds
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        client?.close()
        client = null
    }

    private fun onEvent(event: BotJson) {
        if (event["type"].text != "sessions.changed") return
        onRosterChanged?.invoke()
    }

    private fun onDisconnect(error: BotFailure) {
        _state.value = _state.value.copy(isConnected = false, error = describe(error))
    }

    private suspend fun readLiveStatuses(
        client: BotClient,
        profiles: List<BotProfile>,
    ): Map<String, BotLiveStatus> {
        val reply = try {
            client.call(HermesCall.SessionActiveList)
        } catch (_: BotFailure.Rejected) {
            return emptyMap()
        }
        val items = reply["sessions"].list ?: reply.list ?: emptyList()
        val byKey = mutableMapOf<String, BotLiveStatus>()
        for (item in items) {
            val key = item["session_key"].text ?: item["session_id"].text ?: continue
            byKey[key] = statusOf(item["status"].text)
        }
        val matched = mutableMapOf<String, BotLiveStatus>()
        val keyOwners = mutableMapOf<String, Int>()
        for (profile in profiles) {
            val keys = listOfNotNull(profile.canonicalID, profile.canonicalTipID).distinct()
            var best: BotLiveStatus? = null
            for (key in keys) {
                keyOwners[key] = (keyOwners[key] ?: 0) + 1
                val status = byKey[key] ?: continue
                best = moreUrgent(best, status)
            }
            if (best != null) matched[profile.id] = best
        }
        // A key two bots share marks neither.
        val shared = byKey.keys.filter { (keyOwners[it] ?: 0) > 1 }
        if (shared.isNotEmpty()) {
            val hitIds = profiles.filter { p ->
                listOfNotNull(p.canonicalID, p.canonicalTipID).any { it in shared }
            }.map { it.id }.toSet()
            hitIds.forEach { matched.remove(it) }
        }
        return matched
    }

    private fun startPolling() {
        // The Route drives periodic refresh while busy; the ViewModel exposes
        // shouldPoll so the policy is testable without a scope.
    }

    /** Whether the inbox should keep re-reading the active list right now. */
    fun shouldPoll(): Boolean =
        _state.value.isConnected && _state.value.rows.any { it.status != BotLiveStatus.IDLE }

    companion object {
        fun statusOf(raw: String?): BotLiveStatus = when (raw) {
            "waiting" -> BotLiveStatus.WAITING
            "working", "starting", "streaming" -> BotLiveStatus.WORKING
            else -> BotLiveStatus.IDLE
        }

        fun moreUrgent(a: BotLiveStatus?, b: BotLiveStatus): BotLiveStatus {
            if (a == null) return b
            if (a == BotLiveStatus.WAITING || b == BotLiveStatus.WAITING) return BotLiveStatus.WAITING
            if (a == BotLiveStatus.WORKING || b == BotLiveStatus.WORKING) return BotLiveStatus.WORKING
            return BotLiveStatus.IDLE
        }

        fun describe(error: Throwable): String = when (error) {
            is BotFailure.Stale -> "This connection was replaced."
            is BotFailure.Transport -> "Connection lost. Reopen to reconnect."
            is BotFailure.Rejected -> "The host refused the read (${error.code})."
            else -> error.message ?: "Could not load bots."
        }
    }
}
