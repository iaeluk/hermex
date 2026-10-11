package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotApprovalChoice
import com.iaeluk.hermex.core.network.bots.BotFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The gateway RPC surface, ported from the iOS port's `HermesCall`; grows with the Bot
 * screens. Each case carries its method name, the params the host expects, and how the
 * socket treats it: whether cancelling discards its late reply, whether its timeout fails
 * only the request, and how long its reply may take.
 */
sealed class HermesCall {
    /** The socket handshake's opt-in to server→client requests. */
    object ClientCapabilities : HermesCall()

    /** The roster read: every profile, with its canonical chat when it has one. */
    data class ProfilesList(val includeSessions: Boolean) : HermesCall()

    /** The sessions of one profile, including its hidden Bot Chat. */
    data class SessionList(val profile: String) : HermesCall()

    /** Mints the profile's hidden Bot Chat under its configuration. */
    data class SessionCreate(val profile: String) : HermesCall()

    /** Persists a freshly minted Bot Chat under its canonical title. */
    data class SessionTitle(val sessionId: String) : HermesCall()

    /**
     * Attaches to a session without closing it when this socket drops. `omitMessages`
     * skips the transcript when the screen already has it.
     */
    data class SessionResume(val profile: String, val sessionId: String, val omitMessages: Boolean = false) :
        HermesCall()

    /** The inbox's live-status read. */
    object SessionActiveList : HermesCall()

    /** The one profile-scoped read after connecting, so the host watches its store. */
    data class SessionMostRecent(val profile: String) : HermesCall()

    /** Sends a prompt, queued behind any running turn. */
    data class PromptSubmit(val sessionId: String, val text: String) : HermesCall()

    /** Steers the running turn with new text, keeping what the agent already did. */
    data class SessionSteer(val sessionId: String, val text: String) : HermesCall()

    /** Redirects the run with new text, discarding the current turn's work. */
    data class SessionRedirect(val sessionId: String, val text: String) : HermesCall()

    /** Stops the running turn. */
    data class SessionInterrupt(val sessionId: String) : HermesCall()

    /** Closes the runtime the screen held. */
    data class SessionClose(val runtime: String) : HermesCall()

    /** Answers a pending dangerous-command approval. Choice is the host's vocabulary. */
    data class ApprovalRespond(val sessionId: String, val requestId: String, val choice: String) : HermesCall()

    val method: String
        get() = when (this) {
            is ClientCapabilities -> "client.capabilities"
            is ProfilesList -> "profiles.list"
            is SessionList -> "session.list"
            is SessionCreate -> "session.create"
            is SessionTitle -> "session.title"
            is SessionResume -> "session.resume"
            is SessionActiveList -> "session.active_list"
            is SessionMostRecent -> "session.most_recent"
            is PromptSubmit -> "prompt.submit"
            is SessionSteer -> "session.steer"
            is SessionRedirect -> "session.redirect"
            is SessionInterrupt -> "session.interrupt"
            is SessionClose -> "session.close"
            is ApprovalRespond -> "approval.respond"
        }

    companion object {
        /** The canonical title every bot's hidden chat carries. */
        const val BOT_CHAT_TITLE = "Bot Chat"
    }

    /**
     * The JSON-RPC `params` in the shape the host's release takes. Refuses a value the
     * host must never receive with [BotFailure.Unsupported], like the iOS port.
     */
    fun params(): JsonObject = when (this) {
        is ClientCapabilities -> buildJsonObject { put("server_requests", true) }
        is ProfilesList -> buildJsonObject { put("include_sessions", includeSessions) }
        is SessionList -> buildJsonObject {
            put("profile", profile)
            put("title", BOT_CHAT_TITLE)
            put("include_hidden", true)
        }
        is SessionCreate -> buildJsonObject {
            put("profile", profile)
            put("title", BOT_CHAT_TITLE)
            put("hidden", true)
            put("follow_profile_config", true)
        }
        is SessionTitle -> buildJsonObject {
            put("session_id", sessionId)
            put("title", BOT_CHAT_TITLE)
        }
        is SessionResume -> {
            if (profile.isBlank() || sessionId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("profile", profile)
                put("session_id", sessionId)
                put("close_on_disconnect", false)
                if (omitMessages) put("omit_messages", true)
            }
        }
        is SessionActiveList -> buildJsonObject { }
        is SessionMostRecent -> {
            if (profile.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("profile", profile) }
        }
        is PromptSubmit -> {
            if (sessionId.isBlank() || text.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("text", text)
                put("queued", true)
            }
        }
        is SessionSteer -> {
            if (sessionId.isBlank() || text.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("text", text)
            }
        }
        is SessionRedirect -> {
            if (sessionId.isBlank() || text.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("text", text)
            }
        }
        is SessionInterrupt -> {
            if (sessionId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("session_id", sessionId) }
        }
        is SessionClose -> {
            if (runtime.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("session_id", runtime) }
        }
        is ApprovalRespond -> {
            if (sessionId.isBlank() || requestId.isBlank()) throw BotFailure.Unsupported
            if (choice !in BotApprovalChoice.values) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("request_id", requestId)
                put("choice", choice)
            }
        }
    }

    /**
     * Reads that never control agent execution: cancelling one discards its late reply;
     * cancelling any other call ends its screen's part, because its outcome is unknown.
     */
    val isCancellationSafe: Boolean
        get() = when (this) {
            is ProfilesList, is SessionList, is SessionActiveList, is SessionMostRecent -> true
            else -> false
        }

    /**
     * Optional reads whose timeout fails only that request; any other call that times out
     * ends its screen's part, the socket stays for the others.
     */
    val timesOutLocally: Boolean
        get() = when (this) {
            is SessionActiveList, is SessionMostRecent -> true
            else -> false
        }

    /** How long a reply may take, given the connection's standard deadline. */
    fun deadlineMs(standardMs: Long): Long = standardMs
}
