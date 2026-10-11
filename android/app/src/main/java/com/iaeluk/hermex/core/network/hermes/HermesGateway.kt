package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.integer
import com.iaeluk.hermex.core.network.bots.parseBotJson
import com.iaeluk.hermex.core.network.bots.text
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request

/** One screen's use of its connection's shared gateway socket. */
interface HermesGatewayConsumer {
    val consumerID: Int

    /** One event or server request, in the original envelope. */
    fun onEvent(event: BotJson)

    /** The gateway's report that this client's connection is gone. */
    fun socketEnded(error: BotFailure)

    /** Ends this screen's part; the socket stays while another screen holds it. */
    fun close()
}

/**
 * The one gateway WebSocket a [HermesConnection] shares among its Bot screens. The socket
 * opens when the first consumer joins and closes when the last one leaves.
 *
 * Every socket mints a fresh ticket and runs one handshake before anything else:
 * `gateway.ready` (recording `replay_epoch`), then `client.capabilities` as the first
 * outbound frame, then the keepalive. Consumers that join while it opens wait for that
 * one attempt, so several screens reconnecting after the same drop make one replacement.
 *
 * The receive loop multiplexes, so a quiet tool never blocks a Stop request. A reply
 * settles only the call that sent it. Every event and server request goes to every
 * attached screen; the gateway never answers a server request itself.
 *
 * A consumer's `close()` ends only its own calls, and their late replies reach no one. A
 * required call past its deadline ends only its screen, which hears it as a lost
 * connection. A lost socket (a read or send failure, silence) ends every attached
 * screen's part, and each hears it once; anything later from that socket is dropped by
 * its generation. [retire] does the same with [BotFailure.Stale] when the connection's
 * server or configuration is replaced, and refuses reconnects.
 */
class HermesGateway(
    private val http: HermesConnection,
    private val options: Options = Options(),
) {
    /** Tests shorten the deadlines and script the socket; production takes the defaults. */
    class Options(
        val rpcDeadlineMs: Long = 30_000,
        /** The `gateway.ping` cadence. */
        val heartbeatMs: Long = 15_000,
        /** The silence after which a quiet socket is dropped. */
        val silenceMs: Long = 45_000,
        /** Gets the finished gateway upgrade; null opens a real socket on the connection. */
        val socketFactory: ((Request) -> BotSocket)? = null,
    )

    /** `replay_epoch` from the last socket's `gateway.ready`. */
    var replayEpoch: String? = null
        private set

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: BotSocket? = null
    /** The open attempt every joining consumer waits on; null once the socket is open. */
    private var opening: Deferred<Unit>? = null
    private var reader: Job? = null
    private var heartbeat: Job? = null
    /** Numbers sockets. A frame, failure or deadline from an older one is ignored. */
    private var generation = 0
    private var isRetired = false
    private val consumers = mutableListOf<Consumer>()
    private var consumerCount = 0
    private var nextID = 0
    private val pending = mutableMapOf<Int, Pending>()

    private class Consumer(val id: Int, val client: HermesGatewayConsumer, var attached: Boolean)

    private class Pending(
        val continuation: CancellableContinuation<BotJson>,
        /** The screen that sent it; null for the handshake's own call. */
        val consumer: Int?,
        /** `HermesCall.method`, for the connection's missing methods. */
        val method: String,
        val deadline: Job,
    )

    private class Registration(val id: Int, val owner: Int, val socket: BotSocket)

    /** A new identity for one screen's client. */
    fun makeConsumerID(): Int = synchronized(lock) {
        consumerCount += 1
        consumerCount
    }

    fun isAttached(consumer: Int): Boolean =
        synchronized(lock) { consumers.any { it.id == consumer && it.attached } }

    /** Whether `consumer` may send: attached, or null for the handshake's own call. */
    private fun maySendLocked(consumer: Int?): Boolean =
        consumer == null || consumers.any { it.id == consumer && it.attached }

    /**
     * Attaches [client] to the open socket, or waits for the socket to open, starting that
     * when nobody has. Throws what opening threw, [BotFailure.Transport] for a client
     * whose socket dropped before it could attach, and [BotFailure.Stale] once retired.
     */
    suspend fun join(client: HermesGatewayConsumer) {
        val attempt = synchronized(lock) {
            if (isRetired) throw BotFailure.Stale
            val isOpen = socket != null && opening == null
            consumers.removeAll { it.id == client.consumerID }
            consumers.add(Consumer(client.consumerID, client, isOpen))
            if (isOpen) return
            opening ?: startOpeningLocked()
        }
        attempt.await()
        val attached = synchronized(lock) { consumers.any { it.id == client.consumerID && it.attached } }
        if (!attached) throw BotFailure.Transport
    }

    /**
     * Ends [client]'s part: its calls in flight fail with [BotFailure.Transport] and their
     * late replies reach no one. The last screen to leave closes the socket, silently.
     */
    fun leave(client: HermesGatewayConsumer) {
        val failed = mutableListOf<Pending>()
        var lastLeft = false
        synchronized(lock) {
            consumers.removeAll { it.id == client.consumerID }
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val (_, entry) = iterator.next()
                if (entry.consumer == client.consumerID) {
                    iterator.remove()
                    failed.add(entry)
                }
            }
            lastLeft = consumers.isEmpty()
        }
        failed.forEach {
            it.deadline.cancel()
            it.continuation.resumeWith(Result.failure(BotFailure.Transport))
        }
        if (lastLeft) end(null)
    }

    /**
     * Closes the socket because the app went to the background, so the host sees a clean
     * close rather than a half-open socket. Silent: no attached screen hears it.
     */
    fun closeForBackground() {
        val live = synchronized(lock) { socket != null || opening != null }
        if (live) end(null)
    }

    /**
     * Ends the socket as lost after the dashboard restarted, whose old socket can linger
     * half-open behind a tunnel: attached screens hear [BotFailure.Transport] once and
     * reconnect on their own. Nothing happens while no socket is open or opening.
     */
    fun dropSocket() {
        val live = synchronized(lock) { socket != null || opening != null }
        if (live) end(BotFailure.Transport)
    }

    /**
     * Ends the socket for good because the connection was replaced. Attached screens hear
     * [BotFailure.Stale] once, and every later [join] throws it.
     */
    fun retire() {
        synchronized(lock) { isRetired = true }
        end(BotFailure.Stale)
    }

    /**
     * Admits and sends one typed request for `consumer` (null: the handshake's own), then
     * waits for its reply. `validateDispatch` runs at the actual socket write.
     */
    suspend fun send(call: HermesCall, consumer: Int?, validateDispatch: (() -> Unit)? = null): BotJson {
        val params = call.params()
        val registration = synchronized(lock) {
            val liveSocket = socket
            if (liveSocket == null || !maySendLocked(consumer)) throw BotFailure.Stale
            nextID += 1
            Registration(nextID, generation, liveSocket)
        }
        val frame = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", registration.id)
            put("method", call.method)
            put("params", params)
        }.toString()
        return suspendCancellableCoroutine { continuation ->
            val deadline = scope.launch {
                try {
                    delay(call.deadlineMs(options.rpcDeadlineMs))
                } catch (cancelled: CancellationException) {
                    return@launch
                }
                val stillPending = synchronized(lock) {
                    registration.owner == generation && pending.containsKey(registration.id)
                }
                if (!stillPending) return@launch
                if (call.timesOutLocally) settle(registration.id, BotFailure.Transport) else expire(registration.id)
            }
            val registered = synchronized(lock) {
                if (registration.owner != generation) {
                    false
                } else {
                    pending[registration.id] = Pending(continuation, consumer, call.method, deadline)
                    true
                }
            }
            if (!registered) {
                deadline.cancel()
                continuation.resumeWith(Result.failure(BotFailure.Stale))
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                cancel(registration.id, registration.owner, call.isCancellationSafe)
            }
            scope.launch {
                val dispatchable = synchronized(lock) {
                    registration.owner == generation && pending.containsKey(registration.id)
                }
                if (!dispatchable) return@launch
                try {
                    validateDispatch?.invoke()
                } catch (error: Throwable) {
                    settle(registration.id, error)
                    return@launch
                }
                try {
                    registration.socket.send(frame)
                } catch (error: Throwable) {
                    fail(error, registration.owner)
                }
            }
        }
    }

    /**
     * A cancelled read discards its late reply. Any other cancelled call may have reached
     * the agent, so its screen ends as `close()` ends it; the socket stays for the others.
     */
    private fun cancel(id: Int, owner: Int, safely: Boolean) {
        val entry = synchronized(lock) { if (owner == generation) pending[id] else null } ?: return
        if (safely) {
            settle(id, CancellationException("Call cancelled"))
            return
        }
        val consumerId = entry.consumer ?: return
        val client = synchronized(lock) { consumers.firstOrNull { it.id == consumerId }?.client } ?: return
        client.close()
    }

    /**
     * A required call past its deadline. A screen's ends only that screen, which hears
     * [BotFailure.Transport] once, as it did when it had its own socket; the heartbeat and
     * the silence deadline decide whether the socket itself is gone. The handshake's own
     * call means the socket never became usable.
     */
    private fun expire(id: Int) {
        val entry = synchronized(lock) { pending[id] } ?: return
        val consumerId = entry.consumer
        if (consumerId == null) {
            end(BotFailure.Transport)
            return
        }
        val client = synchronized(lock) { consumers.firstOrNull { it.id == consumerId }?.client }
        if (client == null) {
            settle(id, BotFailure.Transport)
            return
        }
        leave(client)
        client.socketEnded(BotFailure.Transport)
    }

    private fun settle(id: Int, error: Throwable) {
        val entry = synchronized(lock) { pending.remove(id) } ?: return
        entry.deadline.cancel()
        entry.continuation.resumeWith(Result.failure(error))
    }

    /** Starts the one open attempt; the caller holds the lock. */
    private fun startOpeningLocked(): Deferred<Unit> {
        val owner = generation
        val attempt = scope.async {
            try {
                handshake(owner)
                synchronized(lock) {
                    opening = null
                    consumers.forEach { it.attached = true }
                }
            } catch (error: Throwable) {
                // An attempt a leave, fault or retirement already ended has nothing left to end.
                val current = synchronized(lock) { owner == generation }
                if (current) end(null)
                throw error
            }
        }
        opening = attempt
        return attempt
    }

    /**
     * Signs in unless the connection already is, mints a fresh ticket, opens the socket
     * and completes the handshake. No consumer sends anything until it returns.
     */
    private suspend fun handshake(owner: Int) {
        fun check() {
            if (synchronized(lock) { owner != generation }) throw BotFailure.Stale
        }
        val upgrade = http.gatewayUpgrade()
        check()
        val socket = options.socketFactory?.invoke(upgrade) ?: http.openGatewaySocket(upgrade)
        synchronized(lock) { this.socket = socket }
        val ready = receive(socket)
        check()
        val isReady = ready["method"].text == "event" && ready["params"]["type"].text == "gateway.ready"
        val epoch = ready["params"]["payload"]["replay_epoch"].text?.takeIf { it.isNotEmpty() }
        if (!isReady || epoch == null) throw BotFailure.Unsupported
        synchronized(lock) { replayEpoch = epoch }
        startReader(socket, owner)
        // Without this the host treats the socket as a build that predates server→client
        // requests: approvals are withdrawn unsent, clarify is answered empty, and
        // sudo/secret are skipped. A host older than the capability answers -32601 and
        // connects as before.
        try {
            send(HermesCall.ClientCapabilities, null)
        } catch (_: BotFailure.Rejected) {
        }
        check()
        startHeartbeat(socket, owner)
    }

    private fun startReader(socket: BotSocket, owner: Int) {
        val job = scope.launch {
            try {
                while (true) {
                    val frame = receive(socket)
                    val current = synchronized(lock) { owner == generation }
                    if (!current) return@launch
                    consume(frame, owner)
                }
            } catch (error: Throwable) {
                fail(error, owner)
            }
        }
        synchronized(lock) { reader = job }
    }

    /**
     * Sends `gateway.ping` on a fixed cadence, because the host sends no JSON heartbeat of
     * its own and an idle socket would otherwise hit the silence deadline in [receive].
     * The pong is just more inbound traffic: its string id matches no pending call, so
     * [consume] drops it. A failed send is a lost socket.
     */
    private fun startHeartbeat(socket: BotSocket, owner: Int) {
        val job = scope.launch {
            var beat = 0
            while (true) {
                try {
                    delay(options.heartbeatMs)
                } catch (cancelled: CancellationException) {
                    return@launch
                }
                val current = synchronized(lock) { owner == generation }
                if (!current) return@launch
                beat += 1
                val frame = """{"jsonrpc":"2.0","id":"heartbeat-$beat","method":"gateway.ping","params":{}}"""
                try {
                    socket.send(frame)
                } catch (error: Throwable) {
                    fail(error, owner)
                    return@launch
                }
            }
        }
        synchronized(lock) { heartbeat = job }
    }

    /** Reads one frame, or closes `socket` after the silence deadline. */
    private suspend fun receive(socket: BotSocket): BotJson {
        val frame = withTimeoutOrNull(options.silenceMs) { socket.receive() }
            ?: run {
                socket.cancel()
                throw BotFailure.Transport
            }
        return parseBotJson(frame) ?: throw BotFailure.Unsupported
    }

    private fun consume(frame: BotJson, owner: Int) {
        if (frame["method"].text == "event") {
            deliver(frame["params"] ?: JsonNull, owner)
            return
        }
        // Server requests have string ids and no replay sequence. Forward the original
        // envelope so screens cannot mistake them for sequenced events.
        if (frame["id"].text != null && frame["method"].text != null) {
            deliver(frame, owner)
            return
        }
        // The keepalive's pongs have string ids and are dropped here.
        val id = frame["id"].integer ?: return
        val entry = synchronized(lock) { pending.remove(id) } ?: return
        entry.deadline.cancel()
        val code = frame["error"]["code"].integer
        if (code != null) {
            // The host lacks this method, so every screen on the connection leaves it off.
            // The handshake's own `client.capabilities` is exempt: an older host connects as before.
            if (code == -32601 && entry.consumer != null) http.noteUnavailable(entry.method)
            entry.continuation.resumeWith(Result.failure(BotFailure.Rejected(code)))
        } else {
            val result = frame["result"]
            if (result != null && result !is JsonNull) {
                entry.continuation.resumeWith(Result.success(result))
            } else {
                entry.continuation.resumeWith(Result.failure(BotFailure.Unsupported))
            }
        }
    }

    private fun deliver(event: BotJson, owner: Int) {
        val targets = synchronized(lock) {
            if (owner == generation) consumers.filter { it.attached }.map { it.client } else emptyList()
        }
        for (client in targets) {
            // A callback may close a screen or end the socket; check before each one.
            if (synchronized(lock) { owner != generation }) return
            if (synchronized(lock) { !isAttached(client.consumerID) }) continue
            client.onEvent(event)
        }
    }

    private fun fail(error: Throwable, owner: Int) {
        val current = synchronized(lock) { owner == generation }
        if (current) end(error as? BotFailure ?: BotFailure.Transport)
    }

    /**
     * Closes the socket, fails every call on it with [BotFailure.Transport] and drops
     * every screen. The attached ones hear [error] once; nil ends it silently (the last
     * screen left, the app went to the background, or opening failed and its waiters get
     * the error from [join]).
     */
    private fun end(error: BotFailure?) {
        val interrupted: List<Pending>
        val lost: List<HermesGatewayConsumer>
        synchronized(lock) {
            generation += 1
            opening = null
            reader?.cancel()
            reader = null
            heartbeat?.cancel()
            heartbeat = null
            socket?.cancel()
            socket = null
            interrupted = pending.values.toList()
            pending.clear()
            lost = consumers.filter { it.attached }.map { it.client }
            consumers.clear()
        }
        interrupted.forEach {
            it.deadline.cancel()
            it.continuation.resumeWith(Result.failure(BotFailure.Transport))
        }
        if (error != null) lost.forEach { it.socketEnded(error) }
    }
}
