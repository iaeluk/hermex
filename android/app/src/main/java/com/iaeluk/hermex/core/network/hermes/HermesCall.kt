package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotApprovalChoice
import com.iaeluk.hermex.core.network.bots.BotFailure
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
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

    /** Answers a credential/secret request with `{value}`. Empty skips. */
    data class RequestAnswer(val id: String, val value: String) : HermesCall()

    /** Locks one batch clarify answer per question id. */
    data class ClarifyLock(val requestId: String, val questionId: String, val answer: String) : HermesCall()

    /** Answers a single clarify question with free text or a choice label. */
    data class ClarifyAnswer(val id: String, val answer: String) : HermesCall()

    /** Slash-command completions for the argument stage (`/name `). */
    data class CompleteSlash(val text: String, val sessionId: String) : HermesCall()

    /** Runs one slash command line (`/name args`). */
    data class SlashExec(val sessionId: String, val command: String) : HermesCall()

    /** Path completions for `@path` mentions. */
    data class CompletePath(val word: String, val sessionId: String, val profile: String) : HermesCall()

    /** Attaches one file to the session as a data URL. */
    data class FileAttach(val sessionId: String, val name: String, val dataUrl: String) : HermesCall()

    /** Describes one profile (canonical chat, soul, model, face). */
    data class ProfilesDescribe(val name: String) : HermesCall()

    /** Creates one bot; cloneFrom copies its source's skills. */
    data class ProfilesCreate(
        val name: String,
        val description: String? = null,
        val cloneFrom: String? = null,
        val soul: String? = null,
    ) : HermesCall()

    /** Partial profile save: only non-null fields are sent. */
    data class ProfilesConfigure(
        val name: String,
        val description: String? = null,
        val soul: String? = null,
    ) : HermesCall()

    /** Rooms page. */
    data class GroupsList(val offset: Int = 0) : HermesCall()
    /** One room's state. */
    data class GroupsState(val roomId: String) : HermesCall()
    /** Room history window. */
    data class GroupsLog(val roomId: String, val sinceSeq: Int = 0, val limit: Int = 50) : HermesCall()
    /** Sends one room message; eventId dedupes retries. */
    data class GroupsSend(val roomId: String, val eventId: String, val text: String) : HermesCall()
    /** Stops room work. */
    data class GroupsStop(val roomId: String, val cancelId: String) : HermesCall()
    /** Creates one room with 2-6 members. */
    data class GroupsCreate(val roomId: String, val name: String, val members: List<String>) : HermesCall()
    /** Renames one room. */
    data class GroupsRename(val roomId: String, val eventId: String, val name: String) : HermesCall()
    /** Disbands one room. */
    data class GroupsDisband(val roomId: String) : HermesCall()

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
            is RequestAnswer -> "request.answer"
            is ClarifyLock -> "clarify.lock"
            is ClarifyAnswer -> "request.answer"
            is CompleteSlash -> "complete.slash"
            is SlashExec -> "slash.exec"
            is CompletePath -> "complete.path"
            is FileAttach -> "file.attach"
            is ProfilesDescribe -> "profiles.describe"
            is ProfilesCreate -> "profiles.create"
            is ProfilesConfigure -> "profiles.configure"
            is GroupsList -> "groups.list"
            is GroupsState -> "groups.state"
            is GroupsLog -> "groups.log"
            is GroupsSend -> "groups.send"
            is GroupsStop -> "groups.stop"
            is GroupsCreate -> "groups.create"
            is GroupsRename -> "groups.rename"
            is GroupsDisband -> "groups.disband"
        }

    companion object {
        /** The canonical title every bot's hidden chat carries. */
        const val BOT_CHAT_TITLE = "Bot Chat"

        /** A slash line: `/name` with a non-space first char after the slash. */
        fun isSlashLine(text: String): Boolean {
            if (!text.startsWith("/")) return false
            val first = text.drop(1).firstOrNull() ?: return false
            return !first.isWhitespace()
        }
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
        is RequestAnswer -> {
            if (id.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("id", id)
                put("result", buildJsonObject { put("value", value) })
            }
        }
        is ClarifyLock -> {
            if (requestId.isBlank() || questionId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("request_id", requestId)
                put("question_id", questionId)
                put("answer", answer)
            }
        }
        is ClarifyAnswer -> {
            if (id.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("id", id)
                put("result", buildJsonObject { put("answer", answer) })
            }
        }
        is CompleteSlash -> {
            if (sessionId.isBlank() || !isSlashLine(text) || !text.contains(" ")) throw BotFailure.Unsupported
            buildJsonObject {
                put("text", text)
                put("session_id", sessionId)
            }
        }
        is SlashExec -> {
            if (sessionId.isBlank() || !isSlashLine(command)) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("command", command)
            }
        }
        is CompletePath -> {
            if (word.isBlank() || word.any { it.isWhitespace() } || sessionId.isBlank() || profile.isBlank()) {
                throw BotFailure.Unsupported
            }
            buildJsonObject {
                put("word", word)
                put("session_id", sessionId)
                put("profile", profile)
            }
        }
        is FileAttach -> {
            if (sessionId.isBlank() || name.isBlank() || dataUrl.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("session_id", sessionId)
                put("name", name)
                put("data_url", dataUrl)
            }
        }
        is ProfilesDescribe -> {
            if (name.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("name", name) }
        }
        is ProfilesCreate -> {
            if (name.trim().isEmpty()) throw BotFailure.Unsupported
            buildJsonObject {
                put("name", name.trim())
                description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                cloneFrom?.trim()?.takeIf { it.isNotEmpty() }?.let { put("clone_from", it) }
                soul?.trim()?.takeIf { it.isNotEmpty() }?.let { put("soul", it) }
            }
        }
        is ProfilesConfigure -> {
            if (name.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                soul?.let { put("soul", it) }
            }
        }
        is GroupsList -> {
            if (offset < 0) throw BotFailure.Unsupported
            buildJsonObject {
                put("limit", 50)
                put("offset", offset)
            }
        }
        is GroupsState -> {
            if (roomId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("room_id", roomId) }
        }
        is GroupsLog -> {
            if (roomId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("room_id", roomId)
                put("since_seq", sinceSeq)
                put("limit", limit)
            }
        }
        is GroupsSend -> {
            if (roomId.isBlank() || eventId.isBlank() || text.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("room_id", roomId)
                put("event_id", eventId)
                put("payload", buildJsonObject {
                    put("text", text)
                    put("thread_id", "")
                })
            }
        }
        is GroupsStop -> {
            if (roomId.isBlank() || cancelId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject {
                put("room_id", roomId)
                put("cancel_id", cancelId)
            }
        }
        is GroupsCreate -> {
            if (roomId.isBlank() || name.trim().isEmpty()) throw BotFailure.Unsupported
            val clean = members.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (clean.size < 2 || clean.size > 6) throw BotFailure.Unsupported
            buildJsonObject {
                put("room_id", roomId)
                put("name", name.trim())
                put("members", buildJsonArray {
                    clean.forEach { member ->
                        addJsonObject {
                            put("member_id", member)
                            put("profile", member)
                            put("handle", member)
                        }
                    }
                })
            }
        }
        is GroupsRename -> {
            if (roomId.isBlank() || eventId.isBlank() || name.trim().isEmpty()) throw BotFailure.Unsupported
            buildJsonObject {
                put("room_id", roomId)
                put("event_id", eventId)
                put("name", name.trim())
            }
        }
        is GroupsDisband -> {
            if (roomId.isBlank()) throw BotFailure.Unsupported
            buildJsonObject { put("room_id", roomId) }
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
