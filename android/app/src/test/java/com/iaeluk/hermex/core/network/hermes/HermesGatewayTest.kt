package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.integer
import com.iaeluk.hermex.core.network.bots.parseBotJson
import com.iaeluk.hermex.core.network.bots.text
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

class HermesGatewayTest {

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

    /** A scripted socket: frames the gateway sends land in [outgoing]; tests push replies. */
    private class ScriptedSocket : BotSocket {
        val outgoing = Channel<String>(Channel.UNLIMITED)
        private val incoming = Channel<String>(Channel.UNLIMITED)
        var cancelled = false

        override suspend fun receive(): String = incoming.receive()

        override suspend fun send(text: String) {
            outgoing.trySend(text)
        }

        override fun cancel() {
            cancelled = true
            incoming.close(BotFailure.Transport)
        }

        fun push(frame: String) {
            incoming.trySend(frame)
        }
    }

    private class FakeConsumer(override val consumerID: Int) : HermesGatewayConsumer {
        val events = mutableListOf<BotJson>()
        val endings = mutableListOf<BotFailure>()
        var closed = false

        override fun onEvent(event: BotJson) {
            events += event
        }

        override fun socketEnded(error: BotFailure) {
            endings += error
        }

        override fun close() {
            closed = true
        }
    }

    private class Harness(
        val gateway: HermesGateway,
        val consumer: FakeConsumer,
        val socket: ScriptedSocket,
        val connection: HermesConnection,
        val capabilitiesFrame: String,
    )

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun connection(): BotConnection = BotConnection(
        id = "id-1",
        name = "Test",
        address = server.url("/").toString().trimEnd('/'),
        username = "user",
        password = "pass",
    )

    private fun enqueueSignIn() {
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))
        server.enqueue(json("""{"ticket":"tok-1"}"""))
    }

    private fun readyFrame(epoch: String = "epoch-1"): String =
        """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"$epoch"}}}"""

    private fun idOf(frame: String): Int =
        parseBotJson(frame)?.let { it["id"].integer } ?: error("no integer id in $frame")

    /** A handshaken gateway: signs in over the mock host, opens the scripted socket, replies to capabilities. */
    private suspend fun handshaken(
        rpcDeadlineMs: Long = 30_000,
        heartbeatMs: Long = 15_000,
        silenceMs: Long = 45_000,
    ): Harness = coroutineScope {
        enqueueSignIn()
        val socket = ScriptedSocket()
        val connection = HermesConnection(connection())
        val gateway = HermesGateway(
            connection,
            HermesGateway.Options(rpcDeadlineMs, heartbeatMs, silenceMs) { socket },
        )
        val consumer = FakeConsumer(gateway.makeConsumerID())
        val joining = async { gateway.join(consumer) }
        socket.push(readyFrame())
        val capabilities = withTimeout(5_000) { socket.outgoing.receive() }
        socket.push("""{"jsonrpc":"2.0","id":${idOf(capabilities)},"result":{}}""")
        joining.await()
        Harness(gateway, consumer, socket, connection, capabilities)
    }

    @Test
    fun handshakeRecordsTheEpochAndSendsCapabilities() = runBlocking {
        val harness = handshaken()

        assertEquals("epoch-1", harness.gateway.replayEpoch)
        assertTrue(harness.gateway.isAttached(harness.consumer.consumerID))
        assertTrue(harness.capabilitiesFrame.contains("\"client.capabilities\""))
        assertTrue(harness.capabilitiesFrame.contains("\"server_requests\":true"))
    }

    @Test
    fun replySettlesOnlyTheCallThatSentIt() = runBlocking {
        val harness = handshaken()
        val call = async { harness.gateway.send(HermesCall.ClientCapabilities, harness.consumer.consumerID) }
        val frame = withTimeout(5_000) { harness.socket.outgoing.receive() }
        harness.socket.push("""{"jsonrpc":"2.0","id":${idOf(frame)},"result":{"ok":true}}""")

        val reply = call.await()

        assertEquals(true, reply["ok"].flag)
    }

    // A call whose failure is the assertion runs in a supervisor: runBlocking's plain
    // Job would rethrow an expected Rejected/Transport when the test ends, even though
    // await() caught it.
    @Test
    fun errorReplyRejectsAndNotesTheMissingMethod() = runBlocking {
        supervisorScope {
            val harness = handshaken()
            val call = async { harness.gateway.send(HermesCall.ClientCapabilities, harness.consumer.consumerID) }
            val frame = withTimeout(5_000) { harness.socket.outgoing.receive() }
            harness.socket.push("""{"jsonrpc":"2.0","id":${idOf(frame)},"error":{"code":-32601,"message":"method not found"}}""")

            val failure = runCatching { call.await() }.exceptionOrNull()

            assertEquals(BotFailure.Rejected(-32601), failure)
            assertTrue(harness.connection.unavailableMethods.contains("client.capabilities"))
        }
    }

    @Test
    fun handshakeToleratesAnOlderHostWithoutCapabilities() = runBlocking {
        enqueueSignIn()
        val socket = ScriptedSocket()
        val connection = HermesConnection(connection())
        val gateway = HermesGateway(connection, HermesGateway.Options(socketFactory = { socket }))
        val consumer = FakeConsumer(gateway.makeConsumerID())
        val joining = async { gateway.join(consumer) }
        socket.push(readyFrame())
        val capabilities = withTimeout(5_000) { socket.outgoing.receive() }
        socket.push("""{"jsonrpc":"2.0","id":${idOf(capabilities)},"error":{"code":-32601}}""")

        joining.await()

        assertTrue(gateway.isAttached(consumer.consumerID))
        assertTrue("the handshake's own call is exempt from the missing-methods record", connection.unavailableMethods.isEmpty())
    }

    @Test
    fun aSecondConsumerSharesTheOneSocket() = runBlocking {
        val harness = handshaken()
        val second = FakeConsumer(harness.gateway.makeConsumerID())

        harness.gateway.join(second)

        assertTrue(harness.gateway.isAttached(second.consumerID))
        harness.socket.push("""{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed"}}""")
        withTimeout(5_000) {
            while (second.events.isEmpty()) delay(10)
        }
        assertEquals("sessions.changed", second.events.first()["type"].text)
    }

    @Test
    fun heartbeatPingsOnItsCadence() = runBlocking {
        val harness = handshaken(heartbeatMs = 60)

        val ping = withTimeout(5_000) {
            var frame = harness.socket.outgoing.receive()
            while (!frame.contains("gateway.ping")) frame = harness.socket.outgoing.receive()
            frame
        }

        assertTrue(ping.contains("\"method\":\"gateway.ping\""))
    }

    @Test
    fun aSilentSocketIsDroppedAndConsumersHearItOnce() = runBlocking {
        val harness = handshaken(silenceMs = 300)

        withTimeout(5_000) {
            while (harness.consumer.endings.isEmpty()) delay(10)
        }

        assertEquals(BotFailure.Transport, harness.consumer.endings.first())
        assertTrue(harness.socket.cancelled)
        assertTrue(!harness.gateway.isAttached(harness.consumer.consumerID))
    }

    @Test
    fun retireEndsConsumersWithStaleAndRefusesReconnects() = runBlocking {
        val harness = handshaken()

        harness.gateway.retire()

        assertEquals(listOf<BotFailure>(BotFailure.Stale), harness.consumer.endings)
        val failure = runCatching { harness.gateway.join(FakeConsumer(harness.gateway.makeConsumerID())) }.exceptionOrNull()
        assertTrue(failure is BotFailure.Stale)
    }

    @Test
    fun leaveFailsItsCallsAndTheLastScreenClosesTheSocket() = runBlocking {
        supervisorScope {
            val harness = handshaken()
            val call = async { harness.gateway.send(HermesCall.ClientCapabilities, harness.consumer.consumerID) }
            withTimeout(5_000) { harness.socket.outgoing.receive() }

            harness.gateway.leave(harness.consumer)

            val failure = runCatching { call.await() }.exceptionOrNull()
            assertEquals(BotFailure.Transport, failure)
            assertTrue(harness.socket.cancelled)
        }
    }

    @Test
    fun serverRequestsAreDeliveredInTheOriginalEnvelope() = runBlocking {
        val harness = handshaken()

        harness.socket.push("""{"jsonrpc":"2.0","id":"req-1","method":"approval.request","params":{"type":"approval"}}""")
        withTimeout(5_000) {
            while (harness.consumer.events.isEmpty()) delay(10)
        }

        assertEquals("approval.request", harness.consumer.events.first()["method"].text)
    }

    @Test
    fun timeoutOfAPlainCallEndsTheConsumerButNotTheSocket() = runBlocking {
        supervisorScope {
            val harness = handshaken(rpcDeadlineMs = 200)
            val other = FakeConsumer(harness.gateway.makeConsumerID())
            harness.gateway.join(other)

            val call = async { harness.gateway.send(HermesCall.ClientCapabilities, harness.consumer.consumerID) }
            withTimeout(5_000) { harness.socket.outgoing.receive() }

            withTimeout(5_000) {
                while (harness.consumer.endings.isEmpty()) delay(10)
            }

            assertEquals(BotFailure.Transport, harness.consumer.endings.first())
            val failure = runCatching { call.await() }.exceptionOrNull()
            assertEquals(BotFailure.Transport, failure)
            assertTrue(!harness.gateway.isAttached(harness.consumer.consumerID))
            assertTrue("another consumer keeps its connection", harness.gateway.isAttached(other.consumerID))
            assertTrue("another consumer hears nothing", other.endings.isEmpty())
            assertTrue("the socket stays for the other screens", !harness.socket.cancelled)

            // The surviving consumer still sends on the same socket.
            val followUp = async { harness.gateway.send(HermesCall.ClientCapabilities, other.consumerID) }
            val frame = withTimeout(5_000) { harness.socket.outgoing.receive() }
            harness.socket.push("""{"jsonrpc":"2.0","id":${idOf(frame)},"result":{"ok":true}}""")
            assertEquals(true, followUp.await()["ok"].flag)
        }
    }

    @Test
    fun closeForBackgroundEndsSilently() = runBlocking {
        val harness = handshaken()

        harness.gateway.closeForBackground()

        assertTrue(harness.consumer.endings.isEmpty())
        assertTrue(harness.socket.cancelled)
        assertTrue(!harness.gateway.isAttached(harness.consumer.consumerID))
    }
}
