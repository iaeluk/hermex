package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.hermes.HermesConnection
import com.iaeluk.hermex.data.secure.BotConnectionStore
import com.iaeluk.hermex.data.secure.SecretStore
import com.iaeluk.hermex.data.secure.ServerKind
import com.iaeluk.hermex.data.secure.ServerRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BotSetupViewModelTest {

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

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

    private fun memoryStore(): SecretStore = object : SecretStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clearPrefix(prefix: String) { values.keys.removeAll { it.startsWith(prefix) } }
        override fun update(transform: (Map<String, String>) -> Map<String, String>) {
            val updated = transform(values.toMap())
            values.clear()
            values.putAll(updated)
        }
    }

    private fun viewModel(): Triple<BotSetupViewModel, ServerRegistry, BotConnectionStore> {
        val secrets = memoryStore()
        val registry = ServerRegistry(secrets)
        val store = BotConnectionStore(secrets)
        return Triple(BotSetupViewModel(registry, store, ::HermesConnection), registry, store)
    }

    @Test
    fun probeParsesAddressAndAdvances() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"i1","auth_required":true,"auth_providers":["basic"]}"""))
        val (vm, _, _) = viewModel()

        vm.updateAddress(server.url("/api/status?x=1").toString())
        val ok = vm.probe()

        assertTrue(ok)
        assertEquals(BotSetupStep.CREDENTIALS, vm.state.value.step)
        assertEquals(server.url("/").toString().trimEnd('/'), vm.state.value.address)
        assertEquals("0.21.5", vm.state.value.hostVersion)
    }

    @Test
    fun probeRefusesANonDashboard() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        val (vm, _, _) = viewModel()

        vm.updateAddress(server.url("/").toString())
        val ok = vm.probe()

        assertTrue(!ok)
        assertTrue(vm.state.value.addressError?.contains("not a Hermes dashboard") == true)
    }

    @Test
    fun probeRefusesAnOutdatedHost() = runBlocking {
        server.enqueue(json("""{"version":"0.19.0","install_id":"i1","auth_required":true,"auth_providers":["basic"]}"""))
        val (vm, _, _) = viewModel()

        vm.updateAddress(server.url("/").toString())
        val ok = vm.probe()

        assertTrue(!ok)
        assertTrue(vm.state.value.addressError?.contains("0.19.0") == true)
    }

    @Test
    fun probeRefusesABadAddress() = runBlocking {
        val (vm, _, _) = viewModel()

        vm.updateAddress("not a url with spaces")
        val ok = vm.probe()

        assertTrue(!ok)
        assertTrue(vm.state.value.addressError != null)
    }

    @Test
    fun connectSignsInAndSavesAsHermes() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        // signIn inside connectAndSave: status + login + identity
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        server.enqueue(json("""{"user":"user"}"""))
        server.enqueue(json("""{"provider":"basic"}"""))
        val (vm, registry, store) = viewModel()

        vm.updateAddress(server.url("/").toString())
        assertTrue(vm.probe())
        vm.updateUsername("user")
        vm.updatePassword("pass")
        vm.updateName("Home")
        val ok = vm.connectAndSave()

        assertTrue(ok)
        assertEquals(BotSetupStep.DONE, vm.state.value.step)
        val account = registry.snapshot.value.servers.single()
        assertEquals(ServerKind.HERMES, account.kind)
        val saved = store.load(account.id)
        assertEquals("user", saved?.username)
        assertEquals("install-1", saved?.installID)
    }

    @Test
    fun connectRefusesWrongPassword() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        server.enqueue(json("""{"detail":"bad"}""", code = 401))
        val (vm, registry, _) = viewModel()

        vm.updateAddress(server.url("/").toString())
        assertTrue(vm.probe())
        vm.updateUsername("user")
        vm.updatePassword("wrong")
        val ok = vm.connectAndSave()

        assertTrue(!ok)
        assertEquals("Wrong username or password.", vm.state.value.error)
        assertTrue(registry.snapshot.value.servers.isEmpty())
    }

    @Test
    fun connectRequiresCredentials() = runBlocking {
        server.enqueue(json("""{"version":"0.21.5","install_id":"install-1","auth_required":true,"auth_providers":["basic"]}"""))
        val (vm, _, _) = viewModel()

        vm.updateAddress(server.url("/").toString())
        assertTrue(vm.probe())
        val ok = vm.connectAndSave()

        assertTrue(!ok)
        assertTrue(vm.state.value.error != null)
    }
}
