package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotFailure
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** The socket boundary permits scripted frames without a host, for tests. */
interface BotSocket {
    /** The next text frame, or a failure once the socket is gone. */
    suspend fun receive(): String

    suspend fun send(text: String)

    fun cancel()
}

/**
 * The production socket, on the connection's OkHttp client: the connection's cookies and
 * headers ride the upgrade. A refused upgrade fails the first receive with its mapped
 * failure; a close by the other end fails later receives with [BotFailure.Transport].
 */
class OkHttpBotSocket private constructor(
    private val webSocket: WebSocket,
    private val incoming: Channel<String>,
) : BotSocket {

    override suspend fun receive(): String = incoming.receive()

    override suspend fun send(text: String) {
        if (!webSocket.send(text)) throw BotFailure.Transport
    }

    override fun cancel() {
        webSocket.close(NORMAL_CLOSURE, null)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000

        /** Opens one socket for `request` on `client`. */
        fun open(client: OkHttpClient, request: Request): OkHttpBotSocket {
            val incoming = Channel<String>(Channel.BUFFERED)
            val socket = client.newWebSocket(request, FeedingListener(incoming))
            return OkHttpBotSocket(socket, incoming)
        }

        private class FeedingListener(private val incoming: Channel<String>) : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                incoming.trySend(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                incoming.trySend(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                incoming.close(BotFailure.Transport)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = response?.let { botUpgradeFailure(it.code) } ?: BotFailure.Transport
                incoming.close(failure)
            }
        }
    }
}

/**
 * The failure a gateway upgrade answered with [status] stands for, or null for 101, an
 * accepted upgrade whose later errors are the socket's own. 408, 429 and 5xx keep
 * [BotFailure.Rejected] and its quiet retry; any other status is [BotFailure.UpgradeRefused],
 * which does not heal on its own.
 */
fun botUpgradeFailure(status: Int): BotFailure? = when {
    status == 101 -> null
    status == 408 || status == 429 || status in 500..599 -> BotFailure.Rejected(status)
    else -> BotFailure.UpgradeRefused(status)
}
