package com.iaeluk.hermex.data.secure

import com.iaeluk.hermex.core.network.HermesJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerKindTest {

    @Test
    fun savedServersWithoutAKindReadAsWebui() {
        val legacy = """{"servers":[{"id":"https://example.com/","urlString":"https://example.com/","displayName":"Example","initials":"EX","headerLogoColorHex":"#FFD700"}],"activeServerId":"https://example.com/"}"""

        val snapshot = HermesJson.decodeFromString<ServerRegistrySnapshot>(legacy)

        assertEquals(ServerKind.WEBUI, snapshot.servers.single().kind)
        assertEquals(ServerKind.WEBUI, ServerRegistry(MutableSecretStore()).activate("https://example.com".toHttpUrl()).kind)
    }

    @Test
    fun hermesServersRoundTripThroughTheRegistry() {
        val store = MutableSecretStore()
        val registry = ServerRegistry(store)

        val account = registry.activate("https://example.com".toHttpUrl())
        registry.update(account.copy(kind = ServerKind.HERMES))

        val restored = ServerRegistry(store)
        assertEquals(ServerKind.HERMES, restored.snapshot.value.servers.single().kind)
        assertEquals(ServerKind.HERMES, restored.activeServer()?.kind)
    }

    @Test
    fun hermesAccountsEncodeTheirKind() {
        val encoded = HermesJson.encodeToString(
            ServerAccount(id = "h", urlString = "h", displayName = "H", initials = "H", kind = ServerKind.HERMES)
        )

        assertTrue(encoded.contains("\"kind\":\"HERMES\""))
    }
}
