package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.BotPendingApproval
import com.iaeluk.hermex.core.network.bots.BotPendingCredential
import com.iaeluk.hermex.core.network.bots.BotPendingDesktop
import com.iaeluk.hermex.core.network.bots.BotComplete
import com.iaeluk.hermex.core.network.bots.BotPendingQuestion
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
    val pendingApprovals: List<BotPendingApproval> = emptyList(),
    val pendingQuestions: List<BotPendingQuestion> = emptyList(),
    val pendingCredentials: List<BotPendingCredential> = emptyList(),
    val pendingDesktop: List<BotPendingDesktop> = emptyList(),
    val withdrawnNotice: String? = null,
    val slashCandidates: List<BotComplete.Candidate> = emptyList(),
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
        if (!HermesCall.isSlashLine(text) || !text.contains(" ")) {
            if (_state.value.slashCandidates.isNotEmpty()) {
                _state.value = _state.value.copy(slashCandidates = emptyList())
            }
        }
    }

    /** Refreshes `/name ` completions; failures clear the list silently. */
    suspend fun refreshSlash() {
        val client = client ?: return
        val runtime = _state.value.runtime ?: return
        val text = _state.value.input
        if (!HermesCall.isSlashLine(text) || !text.contains(" ")) return
        try {
            val reply = client.call(HermesCall.CompleteSlash(text, runtime))
            _state.value = _state.value.copy(slashCandidates = BotComplete.candidates(reply))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.value = _state.value.copy(slashCandidates = emptyList())
        }
    }

    /** Runs one slash line and echoes it like a send. */
    suspend fun execSlash(command: String) {
        val runtime = _state.value.runtime ?: return
        sendCall(HermesCall.SlashExec(runtime, command), echo = command, clearInput = true)
        _state.value = _state.value.copy(slashCandidates = emptyList())
    }

    /** Attaches one small text file to the session. */
    suspend fun attachTextFile(name: String, text: String) {
        val client = client ?: return
        val runtime = _state.value.runtime ?: return
        if (name.isBlank() || text.isEmpty()) return
        _state.value = _state.value.copy(error = null)
        try {
            val dataUrl = "data:text/plain;base64," + java.util.Base64.getEncoder()
                .encodeToString(text.toByteArray(Charsets.UTF_8))
            client.call(HermesCall.FileAttach(runtime, name, dataUrl))
            appendMessage(BotChatMessage(role = "user", text = "Attached " + name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
        }
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
        // Server requests arrive in the original envelope (string id + method).
        if (event["id"].text != null && event["method"].text != null) {
            onServerRequest(event)
            return
        }
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
            "request.cancel" -> onRequestCancel(event["payload"])
        }
    }

    /** One server request: approval, clarify, sudo/secret, or a Desktop task. */
    private fun onServerRequest(envelope: BotJson) {
        val method = envelope["method"].text
        when (method) {
            "approval" -> {
                val approval = BotPendingApproval.fromEnvelope(envelope) ?: return
                val current = _state.value.pendingApprovals
                if (current.any { it.requestId == approval.requestId }) return
                _state.value = _state.value.copy(
                    pendingApprovals = current + approval,
                    isRunning = true,
                    withdrawnNotice = null,
                )
            }
            "clarify" -> {
                val question = BotPendingQuestion.fromEnvelope(envelope) ?: return
                val current = _state.value.pendingQuestions
                if (current.any { it.requestId == question.requestId }) return
                _state.value = _state.value.copy(
                    pendingQuestions = current + question,
                    isRunning = true,
                    withdrawnNotice = null,
                )
            }
            "sudo", "secret" -> {
                val credential = BotPendingCredential.fromEnvelope(method, envelope) ?: return
                val current = _state.value.pendingCredentials
                if (current.any { it.requestId == credential.requestId }) return
                _state.value = _state.value.copy(
                    pendingCredentials = current + credential,
                    isRunning = true,
                    withdrawnNotice = null,
                )
            }
            "tour", "terminal.read", "window.read", "preview.read", "preview.act" -> {
                val desktop = BotPendingDesktop.fromEnvelope(method, envelope) ?: return
                val current = _state.value.pendingDesktop
                if (current.any { it.requestId == desktop.requestId }) return
                _state.value = _state.value.copy(
                    pendingDesktop = current + desktop,
                    isRunning = true,
                    withdrawnNotice = null,
                )
            }
            else -> _state.value = _state.value.copy(isRunning = true)
        }
    }

    /** `request.cancel` withdraws only the matching envelope, with a note. */
    private fun onRequestCancel(payload: BotJson?) {
        val id = payload["id"].text?.takeIf { it.isNotEmpty() } ?: return
        val method = payload["method"].text?.takeIf { it.isNotEmpty() } ?: return
        val reason = payload["reason"].text
        val state = _state.value
        val removed = state.pendingApprovals.any { it.envelopeId == id } ||
            state.pendingQuestions.any { it.envelopeId == id } ||
            state.pendingCredentials.any { it.envelopeId == id } ||
            state.pendingDesktop.any { it.envelopeId == id }
        _state.value = state.copy(
            pendingApprovals = state.pendingApprovals.filterNot { it.envelopeId == id },
            pendingQuestions = state.pendingQuestions.filterNot { it.envelopeId == id },
            pendingCredentials = state.pendingCredentials.filterNot { it.envelopeId == id },
            pendingDesktop = state.pendingDesktop.filterNot { it.envelopeId == id },
            withdrawnNotice = if (removed) withdrawMessage(method, reason) else state.withdrawnNotice,
        )
    }

    private fun withdrawMessage(method: String, reason: String?): String? {
        if (reason.isNullOrBlank() || reason == "resolved") return null
        val what = when (method) {
            "approval" -> "Approval"
            "clarify" -> "Question"
            else -> "Request"
        }
        return when (reason.trim()) {
            "timeout" -> if (method == "approval") "Approval timed out, so it didn't run."
                else "$what timed out. The bot carried on without an answer."
            "interrupted", "session_closed" -> "$what withdrawn because the work stopped."
            "shutdown" -> "$what withdrawn because Hermes shut down."
            else -> "$what withdrawn."
        }
    }

    /** Answers one pending question: a choice locks, free text submits. */
    suspend fun answerQuestion(envelopeId: String, questionId: String?, answer: String) {
        val client = client ?: return
        val pending = _state.value.pendingQuestions.firstOrNull { it.envelopeId == envelopeId } ?: return
        _state.value = _state.value.copy(error = null)
        try {
            if (pending.isBatch && questionId != null) {
                client.call(HermesCall.ClarifyLock(pending.requestId, questionId, answer))
            } else {
                client.call(HermesCall.ClarifyAnswer(pending.requestId, answer))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
            return
        }
        if (pending.isBatch && questionId != null) {
            val updated = pending.questions.map {
                if (it.wireId == questionId) it.copy(lockedAnswer = answer) else it
            }
            val remaining = _state.value.pendingQuestions.map {
                if (it.envelopeId == envelopeId) it.copy(questions = updated) else it
            }.filterNot { it.questions.all { q -> q.isAnswered } }
            _state.value = _state.value.copy(pendingQuestions = remaining)
        } else {
            _state.value = _state.value.copy(
                pendingQuestions = _state.value.pendingQuestions.filterNot { it.envelopeId == envelopeId },
            )
        }
    }

    /** Answers one credential request; empty skips without retaining a secret. */
    suspend fun answerCredential(envelopeId: String, value: String) {
        val client = client ?: return
        val pending = _state.value.pendingCredentials.firstOrNull { it.envelopeId == envelopeId } ?: return
        _state.value = _state.value.copy(error = null)
        try {
            client.call(HermesCall.RequestAnswer(pending.requestId, value))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
            return
        }
        _state.value = _state.value.copy(
            pendingCredentials = _state.value.pendingCredentials.filterNot { it.envelopeId == envelopeId },
        )
    }

    /** Skips one credential request explicitly. */
    suspend fun skipCredential(envelopeId: String) = answerCredential(envelopeId, "")

    /** Dismisses one Desktop wait card; nothing is sent. */
    fun dismissDesktop(envelopeId: String) {
        _state.value = _state.value.copy(
            pendingDesktop = _state.value.pendingDesktop.filterNot { it.envelopeId == envelopeId },
        )
    }

    /** Answers one pending approval and drops its card. */
    suspend fun answerApproval(requestId: String, choice: String) {
        val client = client ?: return
        val runtime = _state.value.runtime ?: return
        _state.value = _state.value.copy(error = null)
        try {
            client.call(HermesCall.ApprovalRespond(runtime, requestId, choice))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
            return
        }
        _state.value = _state.value.copy(
            pendingApprovals = _state.value.pendingApprovals.filterNot { it.requestId == requestId },
        )
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
