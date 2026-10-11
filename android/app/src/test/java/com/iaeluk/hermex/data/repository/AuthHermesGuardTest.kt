package com.iaeluk.hermex.data.repository

import com.iaeluk.hermex.core.network.PersistentCookieJar
import com.iaeluk.hermex.data.secure.SecretStore
import com.iaeluk.hermex.data.secure.ServerKind
import com.iaeluk.hermex.data.secure.ServerRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class GuardMemoryStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun clearPrefix(prefix: String) {
        values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
    }
    override fun update(transform: (Map<String, String>) -> Map<String, String>) {
        val updated = transform(values.toMap())
        values.clear()
        values.putAll(updated)
    }
}

class AuthHermesGuardTest {
    private fun repository(
        registry: ServerRegistry,
        secretStore: SecretStore,
        clientFactory: (okhttp3.HttpUrl) -> com.iaeluk.hermex.core.network.HermesApiClient,
    ): AuthRepository {
        val cookieJar = PersistentCookieJar(secretStore)
        val okHttpClient = OkHttpClient.Builder().cookieJar(cookieJar).build()
        return AuthRepository(
            registry = registry,
            clientFactory = clientFactory,
            cookieJar = cookieJar,
            clearCachedServer = {},
        )
    }

    private fun explodingClient(): (okhttp3.HttpUrl) -> com.iaeluk.hermex.core.network.HermesApiClient =
        { throw AssertionError("webui client must not run against a Hermes host") }

    @Test
    fun webuiRejectionNeverLogsOutAHermesServer() = runBlocking {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        val url = "http://100.123.21.106:9119/".toHttpUrl()
        registry.activate(url, displayName = "hermes", kind = ServerKind.HERMES)
        val repository = repository(registry, secretStore, explodingClient())

        assertTrue(repository.state.value is AuthState.LoggedIn)
        repository.handleUnauthorized(url, repository.currentAuthGeneration(url))

        assertTrue(repository.state.value is AuthState.LoggedIn)
        assertEquals(false, registry.isLoggedOut(ServerRegistry.normalizedId(url)))
    }

    @Test
    fun webuiRejectionStillLogsOutAWebuiServer() = runBlocking {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        val url = "http://100.123.21.106:8787/".toHttpUrl()
        registry.activate(url, displayName = "webui")
        val repository = repository(registry, secretStore, explodingClient())

        assertTrue(repository.state.value is AuthState.LoggedIn)
        repository.handleUnauthorized(url, repository.currentAuthGeneration(url))

        assertTrue(repository.state.value is AuthState.LoggedOut)
    }

    @Test
    fun staleLoggedOutFlagDoesNotStrandHermesOnSwitch() = runBlocking {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        val url = "http://100.123.21.106:9119/".toHttpUrl()
        val account = registry.activate(url, displayName = "hermes", kind = ServerKind.HERMES)
        // The old logout loop wrote this before webui rejections were guarded.
        registry.setLoggedOut(account.id, true)
        val repository = repository(registry, secretStore, explodingClient())

        repository.activate(account.id)

        assertTrue(repository.state.value is AuthState.LoggedIn)
        assertEquals(false, registry.isLoggedOut(account.id))
    }

    @Test
    fun freshStartRestoresFlaggedHermesAsLoggedIn() {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        val account = registry.activate(
            "http://100.123.21.106:9119/".toHttpUrl(),
            displayName = "hermes",
            kind = ServerKind.HERMES,
        )
        registry.setLoggedOut(account.id, true)

        val repository = repository(registry, secretStore, explodingClient())

        assertTrue(repository.state.value is AuthState.LoggedIn)
    }

    @Test
    fun logoutLeavesHermesAlone() = runBlocking {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        registry.activate(
            "http://100.123.21.106:9119/".toHttpUrl(),
            displayName = "hermes",
            kind = ServerKind.HERMES,
        )
        val repository = repository(registry, secretStore, explodingClient())

        repository.logout()

        assertTrue(repository.state.value is AuthState.LoggedIn)
    }

    @Test
    fun restoredValidationSkipsWebuiProbeForHermes() = runBlocking {
        val secretStore = GuardMemoryStore()
        val registry = ServerRegistry(secretStore)
        registry.activate(
            "http://100.123.21.106:9119/".toHttpUrl(),
            displayName = "hermes",
            kind = ServerKind.HERMES,
        )
        val repository = repository(registry, secretStore, explodingClient())

        assertTrue(repository.validateRestoredSession())
        assertTrue(repository.state.value is AuthState.LoggedIn)
    }
}
