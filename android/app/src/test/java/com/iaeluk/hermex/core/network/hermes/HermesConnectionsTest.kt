package com.iaeluk.hermex.core.network.hermes

import com.iaeluk.hermex.core.network.CustomHeader
import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesConnectionsTest {

    private fun record(password: String = "pass", headers: List<CustomHeader>? = null) = BotConnection(
        id = "conn-1",
        name = "Test",
        address = "https://example.com:9119",
        username = "user",
        password = password,
        installID = "install-1",
        headers = headers,
    )

    @Test
    fun theSameSavedRecordSharesOneConnection() {
        val holder = HermesConnections()

        val first = holder.connectionFor("server-1", record())
        val second = holder.connectionFor("server-1", record())

        assertSame(first, second)
    }

    @Test
    fun newCredentialsRetireTheOldConnection() {
        val holder = HermesConnections()
        val first = holder.connectionFor("server-1", record())

        val second = holder.connectionFor("server-1", record(password = "new-pass"))

        assertNotSame(first, second)
        assertTrue(first.isRetired)
    }

    @Test
    fun aBackfilledInstallIdIsAdopted() {
        val holder = HermesConnections()
        val withoutInstall = record().copy(installID = null)
        val first = holder.connectionFor("server-1", withoutInstall)

        val second = holder.connectionFor("server-1", record())

        assertSame(first, second)
        assertTrue(!first.isRetired)
    }

    @Test
    fun anotherServerGetsItsOwnConnection() {
        val holder = HermesConnections()
        val first = holder.connectionFor("server-1", record())

        val second = holder.connectionFor("server-2", record())

        assertNotSame(first, second)
        assertTrue(first.isRetired)
    }

    @Test
    fun loginRejectionReportsTheServer() {
        val holder = HermesConnections()
        var rejected: String? = null
        holder.onSignInRejected = { rejected = it }
        val connection = holder.connectionFor("server-1", record())

        connection.onLoginRejected?.invoke()

        assertTrue(rejected == "server-1")
    }

    @Test
    fun retireUnlessStillKeepsAnUnchangedConnection() {
        val holder = HermesConnections()
        val first = holder.connectionFor("server-1", record())

        holder.retire("server-1", unlessStill = record())

        assertTrue(!first.isRetired)
        assertSame(first, holder.connectionFor("server-1", record()))
    }

    @Test
    fun retireEndsTheConnection() {
        val holder = HermesConnections()
        val first = holder.connectionFor("server-1", record())

        holder.retire("server-1")

        assertTrue(first.isRetired)
        val failure = runCatching {
            kotlinx.coroutines.runBlocking { first.signIn() }
        }.exceptionOrNull()
        assertTrue(failure is BotFailure.Stale)
    }
}
