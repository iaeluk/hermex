package com.iaeluk.hermex.core.network.hermes

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

    val method: String
        get() = when (this) {
            is ClientCapabilities -> "client.capabilities"
        }

    /**
     * The JSON-RPC `params` in the shape the host's release takes. The iOS port refuses a
     * value the host must never receive with `BotFailure.unsupported`; no case needs that
     * yet.
     */
    fun params(): JsonObject = when (this) {
        is ClientCapabilities -> buildJsonObject { put("server_requests", true) }
    }

    /**
     * Reads and uploads that never control agent execution: cancelling one discards its
     * late reply; cancelling any other call ends its screen's part, because its outcome
     * is unknown.
     */
    val isCancellationSafe: Boolean
        get() = false

    /**
     * Optional reads and slash commands, whose timeout fails only that request; any other
     * call that times out ends its screen's part, the socket stays for the others.
     */
    val timesOutLocally: Boolean
        get() = false

    /** How long a reply may take, given the connection's standard deadline. */
    fun deadlineMs(standardMs: Long): Long = standardMs
}
