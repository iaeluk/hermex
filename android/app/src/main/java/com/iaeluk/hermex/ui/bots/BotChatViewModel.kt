package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.BotSessionRow
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.list
import com.iaeluk.hermex.core.network.bots.text
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.core.network.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BotChatMessage(
    val role: String,
    val text: String,
    val isStreaming: Boolean = false,
)

data class BotChatUiState(
    val isLoading: Boolean = true,
    val isConnected: Boolean = false,
    val isRunning: Boolean = false,
    val messages: List<BotChatMessage> = emptyList(),
    val input: String = "",
    val error: String? = null,
    val runtime: String? = null,
    val completedTurns: Int = 0,
)

/**
 * One bot's chat: attach/resume, streaming send/steer/queue/interrupt, drafts.
 * Testable without Android: [clientProvider] makes the screen's [BotClient],
 * [drafts] persists the composer text per bot.
 */
class BotChatViewModel(
    val profile: String,
    private val clientProvider: () -> BotClient,
    private val drafts: BotDrafts = NoBotDrafts,
) {
    interface BotDrafts {
        suspend fun load(): String
        suspend fun save(text: String)
    }

    private val _state = MutableStateFlow(BotChatUiState())
    val state: StateFlow<BotChatUiState> = _state

    private var client: BotClient? = null
    private var streamingAssistant: StringBuilder? = null

    suspend fun start() {
        _state.value = BotsChatLoading()
        try {
            val client = clientProvider().also { this.client = it }
            client.onEvent = { onEvent(it) }
            client.onDisconnect = { onDisconnect(it) }
            client.connect()
            _state.value = _state.value.copy(isConnected = true)
            val draft = try { drafts.load() } catch (_: Throwable) { "" }
            _state.value = _state.value.copy(input = draft)
            val sessionKey = ensureChat(client)
            val resume = client.call(HermesCall.SessionResume(profile, sessionKey))
            applyResume(resume)
            _state.value = _state.value.copy(isLoading = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isLoading = false, error = describe(error))
        }
    }

    fun updateInput(text: String) {
        _state.value = _state.value.copy(input = text)
    }

    suspend fun persistDraft() {
        try { drafts.save(_state.value.input) } catch (_: Throwable) { }
    }

    /** Sends: idle submits, running queues behind the turn (the host queues). */
    suspend fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        sendCall(HermesCall.PromptSubmit(requireRuntime(), text), echo = text, clearInput = true)
    }

    /** Steers the running turn, keeping what the agent already did. */
    suspend fun steer() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        sendCall(HermesCall.SessionSteer(requireRuntime(), text), echo = text, clearInput = true)
    }

    /** Redirects the run, discarding the current turn's work. */
    suspend fun redirect() {
        val text = _state.value.input.trim()
        if (text.isEmpty()) return
        sendCall(HermesCall.SessionRedirect(requireRuntime(), text), echo = text, clearInput = true)
    }

    /** Stops the running turn. */
    suspend fun interrupt() {
        val client = client ?: return
        _state.value = _state.value.copy(error = null)
        try {
            client.call(HermesCall.SessionInterrupt(requireRuntime()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
        }
    }

    fun stop() {
        client?.close()
        client = null
    }

    private suspend fun sendCall(call: HermesCall, echo: String, clearInput: Boolean) {
        val client = client ?: return
        appendMessage(BotChatMessage(role = "user", text = echo))
        if (clearInput) {
            _state.value = _state.value.copy(input = "")
            try { drafts.save("") } catch (_: Throwable) { }
        }
        _state.value = _state.value.copy(error = null, isRunning = true)
        ensureAssistantTurn()
        try {
            client.call(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error), isRunning = false)
        }
    }

    private suspend fun ensureChat(client: BotClient): String {
        val list = client.call(HermesCall.SessionList(profile))
        val rows = BotSessionRow.listIn(list)
        rows.firstOrNull { it.isBotChat }?.let { return it.id }
        val created = client.call(HermesCall.SessionCreate(profile))
        val sessionId = created["session_id"].text ?: created["id"].text
            ?: throw BotFailure.Unsupported
        try {
            client.call(HermesCall.SessionTitle(sessionId))
        } catch (_: BotFailure.Rejected) {
            // The title is cosmetic; a refusal must not block the chat.
        }
        return sessionId
    }

    private fun applyResume(resume: BotJson) {
        val runtime = resume["session_id"].text ?: resume["runtime"].text
        val messages = resume["messages"].list?.mapNotNull { item ->
            val role = item["role"].text ?: return@mapNotNull null
            val text = item["content"].text ?: item["text"].text ?: return@mapNotNull null
            BotChatMessage(role = role, text = text)
        } ?: emptyList()
        _state.value = _state.value.copy(
            runtime = runtime,
            messages = messages,
            isRunning = resume["running"].flag == true || resume["status"].text == "working",
        )
    }

    private fun onEvent(event: BotJson) {
        when (event["type"].text) {
            "message.start" -> ensureAssistantTurn()
            "message.delta" -> {
                val text = event["payload"]["text"].text ?: return
                if (text.isEmpty()) return
                ensureAssistantTurn()
                streamingAssistant?.append(text)
                publishStreaming()
                _state.value = _state.value.copy(isRunning = true)
            }
            "message.complete" -> {
                val text = event["payload"]["text"].text
                if (streamingAssistant == null && text != null) {
                    appendMessage(BotChatMessage(role = "assistant", text = text))
                }
                finishTurn()
            }
            "session.info" -> {
                val running = event["payload"]["running"].flag
                if (running != null) _state.value = _state.value.copy(isRunning = running)
            }
            "error" -> {
                val message = event["payload"]["message"].text
                if (!message.isNullOrEmpty()) _state.value = _state.value.copy(error = message)
            }
        }
    }

    private fun onDisconnect(error: BotFailure) {
        finishTurn()
        _state.value = _state.value.copy(isConnected = false, error = describe(error))
    }

    private fun ensureAssistantTurn() {
        if (streamingAssistant == null) {
            streamingAssistant = StringBuilder()
            appendMessage(BotChatMessage(role = "assistant", text = "", isStreaming = true))
        }
    }

    private fun publishStreaming() {
        val text = streamingAssistant?.toString() ?: return
        val messages = _state.value.messages
        val index = messages.indexOfLast { it.role == "assistant" }
        if (index < 0) return
        val updated = messages.toMutableList()
        updated[index] = updated[index].copy(text = text, isStreaming = true)
        _state.value = _state.value.copy(messages = updated)
    }

    private fun finishTurn() {
        val text = streamingAssistant?.toString()
        streamingAssistant = null
        val messages = _state.value.messages
        if (text != null) {
            val index = messages.indexOfLast { it.role == "assistant" }
            if (index >= 0) {
                val updated = messages.toMutableList()
                updated[index] = updated[index].copy(isStreaming = false)
                _state.value = _state.value.copy(
                    messages = updated,
                    isRunning = false,
                    completedTurns = _state.value.completedTurns + 1,
                )
                return
            }
        }
        _state.value = _state.value.copy(
            isRunning = false,
            completedTurns = _state.value.completedTurns + 1,
        )
    }

    private fun appendMessage(message: BotChatMessage) {
        _state.value = _state.value.copy(messages = _state.value.messages + message)
    }

    private fun requireRuntime(): String =
        _state.value.runtime ?: throw BotFailure.Unsupported

    companion object {
        private fun BotsChatLoading() = BotChatUiState(isLoading = true)

        fun describe(error: Throwable): String = when (error) {
            is BotFailure.Stale -> "This connection was replaced."
            is BotFailure.Transport -> "Connection lost. Reopen to reconnect."
            is BotFailure.Rejected -> "The host refused the request (${error.code})."
            else -> error.message ?: "Something went wrong."
        }
    }
}

object NoBotDrafts : BotChatViewModel.BotDrafts {
    override suspend fun load(): String = ""
    override suspend fun save(text: String) { }
}
