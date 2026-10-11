package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotRoom
import com.iaeluk.hermex.core.network.bots.BotRoomMessage
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.core.network.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class BotRoomsUiState(
    val isLoading: Boolean = true,
    val rooms: List<BotRoom> = emptyList(),
    val error: String? = null,
)

data class BotRoomUiState(
    val isLoading: Boolean = true,
    val messages: List<BotRoomMessage> = emptyList(),
    val input: String = "",
    val error: String? = null,
    val isWorking: Boolean = false,
)

class BotRoomsViewModel(private val clientProvider: () -> BotClient) {
    private val _state = MutableStateFlow(BotRoomsUiState())
    val state: StateFlow<BotRoomsUiState> = _state

    suspend fun start() {
        _state.value = BotRoomsUiState(isLoading = true)
        try {
            val reply = clientProvider().call(HermesCall.GroupsList(0))
            _state.value = BotRoomsUiState(isLoading = false, rooms = BotRoom.listIn(reply))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = BotRoomsUiState(isLoading = false, error = describe(error))
        }
    }

    companion object {
        fun describe(error: Throwable): String = when (error) {
            is BotFailure.Stale -> "This connection was replaced."
            is BotFailure.Transport -> "Connection lost."
            is BotFailure.Rejected -> "The host refused the request (${error.code})."
            else -> error.message ?: "Something went wrong."
        }
    }
}

class BotRoomViewModel(
    private val clientProvider: () -> BotClient,
    val roomId: String,
) {
    private val _state = MutableStateFlow(BotRoomUiState())
    val state: StateFlow<BotRoomUiState> = _state

    fun updateInput(v: String) { _state.value = _state.value.copy(input = v) }

    suspend fun start() {
        _state.value = BotRoomUiState(isLoading = true)
        try {
            val reply = clientProvider().call(HermesCall.GroupsLog(roomId))
            _state.value = BotRoomUiState(isLoading = false, messages = BotRoomMessage.listIn(reply))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = BotRoomUiState(isLoading = false, error = BotRoomsViewModel.describe(error))
        }
    }

    suspend fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        _state.value = _state.value.copy(input = "", error = null, isWorking = true)
        try {
            clientProvider().call(HermesCall.GroupsSend(roomId, UUID.randomUUID().toString(), text))
            start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = BotRoomsViewModel.describe(error), isWorking = false)
        }
    }

    suspend fun stop() {
        try {
            clientProvider().call(HermesCall.GroupsStop(roomId, UUID.randomUUID().toString()))
        } catch (_: Throwable) { }
        _state.value = _state.value.copy(isWorking = false)
    }

    suspend fun disband(): Boolean {
        return try {
            clientProvider().call(HermesCall.GroupsDisband(roomId))
            true
        } catch (_: Throwable) {
            false
        }
    }
}
