package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * One screen's use of its connection's shared gateway socket ([HermesGateway]). Screens
 * hold their own client; a chat's controls and delegated work share the chat's.
 * [connect] attaches to the socket, opening it when no other screen has; [close] ends
 * only this screen's calls, never the socket another screen is using.
 *
 * Callbacks arrive off the main thread, from the gateway's reader scope.
 */
class BotClient(
    val http: HermesConnection,
) : HermesGatewayConsumer {
    private val gateway: HermesGateway get() = http.gateway
    override val consumerID: Int = gateway.makeConsumerID()

    /** The connection this client signs in through, shared with its screens. */
    val connection get() = http.connection

    /** Bumped by [close] and a lost socket, so this screen's late results are dropped. */
    private val attemptLock = Any()
    private var attempt = 0

    /** `version` from the connection's last `/api/status` read; null when omitted. */
    val serverVersion: String? get() = http.serverVersion

    /** `install_id` from the same read; null when omitted. */
    val serverInstallID: String? get() = http.serverInstallID

    val replayEpoch: String? get() = gateway.replayEpoch

    val unavailableMethods: Set<String> get() = http.unavailableMethods

    val isAttached: Boolean get() = gateway.isAttached(consumerID)

    var onEvent: ((BotJson) -> Unit)? = null
    var onDisconnect: ((BotFailure) -> Unit)? = null

    /**
     * Attaches to the gateway socket once its handshake is done, so no RPC can precede
     * it. A socket another screen has open is joined as it is; otherwise this opens one,
     * or waits for the one already opening.
     */
    suspend fun connect() {
        close()
        val attempt = currentAttempt()
        try {
            gateway.join(this)
        } catch (error: Throwable) {
            if (attempt != currentAttempt()) throw BotFailure.Stale else throw error
        }
        if (attempt != currentAttempt()) throw BotFailure.Stale
        try {
            currentCoroutineContext().ensureActive()
        } catch (_: CancellationException) {
            close()
            throw BotFailure.Stale
        }
    }

    /** Admits and sends one typed request. `validateDispatch` runs at the actual socket write. */
    suspend fun call(call: HermesCall, validateDispatch: (() -> Unit)? = null): BotJson {
        val attempt = currentAttempt()
        val reply = gateway.send(call, consumerID, validateDispatch)
        checkOwner(attempt)
        return reply
    }

    /**
     * Throws [BotFailure.Stale] once [close], a reconnect or a lost socket has ended
     * `attempt`. The HTTP calls check it on their result and, through `validateDispatch`,
     * before each send.
     */
    fun checkOwner(attempt: Int) {
        if (attempt != currentAttempt()) throw BotFailure.Stale
    }

    fun currentAttempt(): Int = synchronized(attemptLock) { attempt }

    /**
     * Ends this screen's part: its calls fail with [BotFailure.Transport] and nothing
     * more reaches [onEvent]. Not a disconnect: [onDisconnect] stays quiet.
     */
    override fun close() {
        endLocalWork()
        gateway.leave(this)
    }

    /** The gateway's report that this client's connection is gone. */
    override fun socketEnded(error: BotFailure) {
        endLocalWork()
        onDisconnect?.invoke(error)
    }

    override fun onEvent(event: BotJson) {
        onEvent?.invoke(event)
    }

    private fun endLocalWork() {
        synchronized(attemptLock) { attempt += 1 }
    }
}
