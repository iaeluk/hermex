package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.text
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * The end-to-end smoke against a real host: the local `local-hermes` stub on the dev VPS
 * (or any dashboard reachable from this machine). Skipped unless
 * `/tmp/hermex-smoke.properties` names one, so CI never runs it:
 *
 *     url=http://127.0.0.1:9199
 *     user=hermex
 *     password=<the stub's password>
 */
class HermesConnectionSmokeTest {

    @Test
    fun connectsSignsInMintsATicketAndRetires() = runBlocking {
        val propertiesFile = File("/tmp/hermex-smoke.properties")
        assumeTrue("no /tmp/hermex-smoke.properties", propertiesFile.isFile)
        val properties = Properties().apply { propertiesFile.inputStream().use { load(it) } }
        val url = properties.getProperty("url")
        val user = properties.getProperty("user")
        val password = properties.getProperty("password")
        assumeTrue(url != null && user != null && password != null)

        val connection = BotConnection(
            id = "smoke-1",
            name = "Smoke",
            address = BotConnection.address(url!!),
            username = user!!,
            password = password!!,
        )
        val hermes = HermesConnection(connection)

        val status = hermes.status()
        assertNotNull("the host must report a version", status["version"].text)
        assertEquals("the stub host keeps its auth gate on", true, status["auth_required"].flag)

        hermes.signIn()
        assertTrue("sign-in must stick", hermes.isSignedIn)
        assertEquals("the live install id must match the probe's", status["install_id"].text, hermes.serverInstallID)
        assertEquals("trust on first use stores the install id", status["install_id"].text, connection.installID)

        val upgrade = hermes.gatewayUpgrade()
        assertEquals("/api/ws", upgrade.url.encodedPath)
        assertTrue(
            "the upgrade must offer the ticket",
            upgrade.header("Sec-WebSocket-Protocol")?.startsWith("hermes-gateway-v1, hermes-gateway-ticket.") == true,
        )

        hermes.retire()
        val failure = runCatching { hermes.signIn() }.exceptionOrNull()
        assertTrue("a retired connection must answer stale", failure is BotFailure.Stale)
    }
}
