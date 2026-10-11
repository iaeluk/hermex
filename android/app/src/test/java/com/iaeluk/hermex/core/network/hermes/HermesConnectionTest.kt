package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.CustomHeader
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HermesConnectionTest {

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

    private fun connection(installID: String? = null, headers: List<CustomHeader>? = null): BotConnection =
        BotConnection(
            id = "id-1",
            name = "Test",
            address = server.url("/").toString().trimEnd('/'),
            username = "user",
            password = "pass",
            installID = installID,
            headers = headers,
        )

    private fun json(body: String, code: Int = 200, setCookie: String? = null): MockResponse =
        MockResponse.Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .apply { setCookie?.let { addHeader("Set-Cookie", it) } }
            .body(body)
            .build()

    private fun statusBody(version: String = "0.21.5", install: String = "install-1"): String =
        """{"version":"$version","install_id":"$install","auth_required":true,"auth_providers":["basic"]}"""

    @Test
    fun signInReadsStatusLogsInAndVerifiesIdentity() = runBlocking {
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"user":"user"}""", setCookie = "session=abc; Path=/"))
        server.enqueue(json("""{"provider":"basic"}"""))

        val connection = HermesConnection(connection())
        connection.signIn()

        assertTrue(connection.isSignedIn)
        assertEquals("0.21.5", connection.serverVersion)
        assertEquals("install-1", connection.serverInstallID)
        assertEquals("install-1", connection.connection.installID)
        assertTrue(connection.state is HermesConnection.State.Ready)

        val status = server.takeRequest()
        assertEquals("/api/status", status.url.encodedPath)
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/auth/password-login", login.url.encodedPath)
        assertTrue(login.body?.utf8()?.contains("\"provider\":\"basic\"") == true)
        val identity = server.takeRequest()
        assertEquals("/api/auth/me", identity.url.encodedPath)
        assertNotNull("the session cookie from login must ride on the identity read", identity.headers["Cookie"])
    }

    @Test
    fun signInRefusesAnOutdatedHostBeforeAnyPasswordGoesOut() = runBlocking {
        server.enqueue(json(statusBody(version = "0.19.0")))

        val connection = HermesConnection(connection())
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.Outdated)
        assertEquals("0.19.0", (failure as BotFailure.Outdated).version)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun signInRefusesAnAddressThatNowReachesADifferentInstall() = runBlocking {
        server.enqueue(json(statusBody(install = "install-2")))

        val connection = HermesConnection(connection(installID = "install-1"))
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.DifferentHost)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun signInRefusesBrowserOnlySignIn() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"i","auth_required":true,"auth_providers":["oauth"]}"""))

        val connection = HermesConnection(connection())
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.BrowserSignIn)
    }

    @Test
    fun signInRefusesAHostWithoutItsAuthGate() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"i","auth_required":false}"""))

        val connection = HermesConnection(connection())
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.Unsupported)
    }

    @Test
    fun aRejectedLoginIsReportedAndFailsTheSignIn() = runBlocking {
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"detail":"bad credentials"}""", code = 401))

        var rejected = false
        val connection = HermesConnection(connection())
        connection.onLoginRejected = { rejected = true }
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertEquals(BotFailure.Rejected(401), failure)
        assertTrue(rejected)
    }

    @Test
    fun aWrongIdentityProviderFailsTheSignIn() = runBlocking {
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"oauth"}"""))

        val connection = HermesConnection(connection())
        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.WrongIdentity)
    }

    @Test
    fun concurrentSignInsShareOneAttempt() = runBlocking {
        server.enqueue(json(statusBody()).newBuilder().bodyDelay(300, TimeUnit.MILLISECONDS).build())
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))

        val connection = HermesConnection(connection())
        val callers = listOf(
            async { connection.signIn() },
            async { connection.signIn() },
        )
        callers.awaitAll()

        assertTrue(connection.isSignedIn)
        assertEquals("one status, one login, one identity", 3, server.requestCount)
    }

    @Test
    fun retireMakesEveryCallStale() = runBlocking {
        val connection = HermesConnection(connection())
        connection.retire()

        val failure = runCatching { connection.signIn() }.exceptionOrNull()

        assertTrue(failure is BotFailure.Stale)
        assertTrue(connection.state is HermesConnection.State.Stale)
        assertTrue(!connection.answersStatus())
    }

    @Test
    fun statusProbeClassifiesTheHostsAnswers() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        assertTrue(
            runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
                is BotFailure.NotDashboard
        )

        server.enqueue(json("""{"detail":"Sign in to continue"}""", code = 401))
        assertTrue(
            runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
                is BotFailure.NotDashboard
        )

        server.enqueue(MockResponse.Builder().code(401).body("<html>access proxy</html>").build())
        assertTrue(
            runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
                is BotFailure.Blocked
        )

        server.enqueue(MockResponse.Builder().code(200).body("not json").build())
        assertTrue(
            runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
                is BotFailure.NotDashboard
        )

        server.enqueue(json("""{"detail":"Invalid Host header. Use 127.0.0.1 or localhost."}""", code = 400))
        val refusal = runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
        assertEquals(BotFailure.Rejected(400), refusal)

        server.enqueue(json("""{"detail":"Bad request"}""", code = 400))
        assertTrue(
            runCatching { HermesConnection(connection()).status() }.exceptionOrNull()
                is BotFailure.NotDashboard
        )
    }

    @Test
    fun aSignedRequestSignsInAgainOnceAfter401AndResends() = runBlocking {
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))
        server.enqueue(json("""{"detail":"expired"}""", code = 401))
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))
        server.enqueue(json("""{"ticket":"tok-1"}"""))

        val connection = HermesConnection(connection())
        val upgrade = connection.gatewayUpgrade()

        assertEquals("/api/ws", upgrade.url.encodedPath)
        assertEquals("hermes-gateway-v1, hermes-gateway-ticket.tok-1", upgrade.header("Sec-WebSocket-Protocol"))
        assertEquals("3 + 1 + 3 + 1", 8, server.requestCount)
    }

    @Test
    fun savedConnectionHeadersRideOnEveryRequest() = runBlocking {
        server.enqueue(json(statusBody()))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))

        val connection = HermesConnection(connection(headers = listOf(CustomHeader("CF-Access-Client-Id", "id-1"))))
        connection.signIn()

        assertEquals("id-1", server.takeRequest().headers["CF-Access-Client-Id"])
    }
}
