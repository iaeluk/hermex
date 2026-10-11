package com.iaeluk.hermex.data.secure

import com.iaeluk.hermex.core.network.CustomHeader
import com.iaeluk.hermex.core.network.HermesJson
import com.iaeluk.hermex.core.network.bots.BotConnection
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotConnectionStoreTest {

    private fun record() = BotConnection(
        id = "conn-1",
        name = "Home",
        address = "https://example.com:9119",
        username = "hermex",
        password = "secret",
        installID = "install-1",
        headers = listOf(CustomHeader("CF-Access-Client-Id", "id-1")),
    )

    @Test
    fun saveAndLoadRoundTrip() {
        val store = BotConnectionStore(MutableSecretStore())

        store.save("server-1", record())

        assertEquals(record(), store.load("server-1"))
    }

    @Test
    fun recordsAreScopedByServer() {
        val store = BotConnectionStore(MutableSecretStore())

        store.save("server-1", record())

        assertNull(store.load("server-2"))
    }

    @Test
    fun removeForgetsTheRecord() {
        val secrets = MutableSecretStore()
        val store = BotConnectionStore(secrets)
        store.save("server-1", record())

        store.remove("server-1")

        assertNull(store.load("server-1"))
        assertTrue(secrets.values.keys.none { it.startsWith("bot_connection::") })
    }

    @Test
    fun corruptRecordsReadAsAbsent() {
        val secrets = MutableSecretStore(mapOf("bot_connection::server-1" to "not-json").toMutableMap())
        val store = BotConnectionStore(secrets)

        assertNull(store.load("server-1"))
    }

    @Test
    fun thePasswordIsStoredEncryptedInTheSecretPayload() {
        val secrets = MutableSecretStore()
        BotConnectionStore(secrets).save("server-1", record())

        val stored = secrets.values.values.single()
        assertTrue(stored.contains("secret"))
        val decoded = HermesJson.decodeFromString<BotConnection>(stored)
        assertEquals("secret", decoded.password)
    }
}
