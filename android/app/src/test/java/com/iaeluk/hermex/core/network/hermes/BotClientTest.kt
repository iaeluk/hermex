package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.integer
import com.iaeluk.hermex.core.network.bots.parseBotJson
import com.iaeluk.hermex.core.network.bots.text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class BotClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private class ScriptedSocket : BotSocket {
        val outgoing = Channel<String>(Channel.UNLIMITED)
        private val incoming = Channel<String>(Channel.UNLIMITED)

        override suspend fun receive(): String = incoming.receive()

        override suspend fun send(text: String) {
            outgoing.trySend(text)
        }

        override fun cancel() {
            incoming.close(BotFailure.Transport)
        }

        fun push(frame: String) {
            incoming.trySend(frame)
        }
    }

    private class Harness(
        val client: BotClient,
        val sockets: MutableList<ScriptedSocket>,
        val events: MutableList<BotJson>,
        val endings: MutableList<BotFailure>,
    ) {
        val socket: ScriptedSocket get() = sockets.last()
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    /** Answers the handshake's own `client.capabilities` on `socket`. */
    private suspend fun answerCapabilities(socket: ScriptedSocket) {
        val capabilities = withTimeout(5_000) { socket.outgoing.receive() }
        val id = parseBotJson(capabilities)?.let { it["id"].integer } ?: error("no capabilities id")
        socket.push("""{"jsonrpc":"2.0","id":$id,"result":{}}""")
    }

    private suspend fun CoroutineScope.connected(): Harness {
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))
        server.enqueue(json("""{"ticket":"tok-1"}"""))
        server.enqueue(json("""{"ticket":"tok-2"}"""))
        val sockets = Collections.synchronizedList(mutableListOf<ScriptedSocket>())
        val http = HermesConnection(
            BotConnection(
                id = "id-1",
                name = "Test",
                address = server.url("/").toString().trimEnd('/'),
                username = "user",
                password = "pass",
            ),
            HermesGateway.Options(socketFactory = { ScriptedSocket().also { sockets += it } }),
        )
        val events = Collections.synchronizedList(mutableListOf<BotJson>())
        val endings = Collections.synchronizedList(mutableListOf<BotFailure>())
        val client = BotClient(http)
        client.onEvent = { events += it }
        client.onDisconnect = { endings += it }
        // connect() only returns once gateway.ready arrives, so it runs while the test drives the socket.
        val connecting = async { client.connect() }
        withTimeout(5_000) {
            while (sockets.isEmpty()) delay(10)
        }
        val socket = sockets.last()
        socket.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1"}}}""")
        answerCapabilities(socket)
        connecting.await()
        return Harness(client, sockets, events, endings)
    }

    @Test
    fun connectAttachesAndSeesTheHandshake() = runBlocking {
        val harness = connected()

        assertTrue(harness.client.isAttached)
        assertEquals("epoch-1", harness.client.replayEpoch)
        assertEquals("0.21.5", harness.client.serverVersion)
        assertEquals("install-1", harness.client.serverInstallID)
    }

    @Test
    fun callSendsAndReturnsTheReply() = runBlocking {
        val harness = connected()

        val call = async { harness.client.call(HermesCall.ClientCapabilities) }
        val frame = withTimeout(5_000) { harness.socket.outgoing.receive() }
        val id = parseBotJson(frame)?.let { it["id"].integer } ?: error("no call id")
        harness.socket.push("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")

        assertEquals(true, call.await()["ok"].flag)
    }

    @Test
    fun eventsReachOnEvent() = runBlocking {
        val harness = connected()

        harness.socket.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed"}}""")
        withTimeout(5_000) {
            while (harness.events.isEmpty()) delay(10)
        }

        assertEquals("sessions.changed", harness.events.first()["type"].text)
    }

    @Test
    fun closeEndsOnlyItsCalls() = runBlocking {
        supervisorScope {
            val harness = connected()
            val call = async { harness.client.call(HermesCall.ClientCapabilities) }
            withTimeout(5_000) { harness.socket.outgoing.receive() }

            harness.client.close()

            val failure = runCatching { call.await() }.exceptionOrNull()
            assertEquals(BotFailure.Transport, failure)
            assertTrue("a close is not a disconnect", harness.endings.isEmpty())
        }
    }

    @Test
    fun reconnectAfterCloseAttachesAgain() = runBlocking {
        val harness = connected()
        harness.client.close()

        // The close ended the socket, so the reconnect opens a fresh one and shakes hands again.
        val reconnecting = async { harness.client.connect() }
        withTimeout(5_000) {
            while (harness.sockets.size < 2) delay(10)
        }
        val socket = harness.sockets.last()
        socket.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-2"}}}""")
        answerCapabilities(socket)
        reconnecting.await()

        assertTrue(harness.client.isAttached)
        assertEquals("epoch-2", harness.client.replayEpoch)
    }
}
