package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.CustomHeader
import com.iaeluk.hermex.core.network.bots.BotAddressError
import com.iaeluk.hermex.core.network.bots.BotConnection
import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotHostStatus
import com.iaeluk.hermex.core.network.bots.BotJson
import com.iaeluk.hermex.core.network.bots.flag
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.list
import com.iaeluk.hermex.core.network.bots.text
import com.iaeluk.hermex.core.network.hermes.HermesCompatibility
import com.iaeluk.hermex.core.network.hermes.HermesConnection
import com.iaeluk.hermex.core.network.parseCustomHeaderLines
import com.iaeluk.hermex.data.secure.BotConnectionStore
import com.iaeluk.hermex.data.secure.ServerKind
import com.iaeluk.hermex.data.secure.ServerRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/** One step of the Hermes connection setup. */
enum class BotSetupStep {
    ADDRESS,
    CREDENTIALS,
    SAVING,
    DONE,
}

/** What the setup form shows, and what blocks Connect. */
data class BotSetupUiState(
    val step: BotSetupStep = BotSetupStep.ADDRESS,
    val addressText: String = "",
    val address: String? = null,
    val addressError: String? = null,
    val hostStatus: BotHostStatus? = null,
    val hostVersion: String? = null,
    val username: String = "",
    val password: String = "",
    val headersText: String = "",
    val name: String = "",
    val isWorking: Boolean = false,
    val error: String? = null,
    val canConnect: Boolean = false,
) {
    val canProbe: Boolean get() = addressText.isNotBlank() && !isWorking
}

/**
 * The Hermes connection setup: probe the dashboard root, sign in, and save. Testable
 * without Android: the [connectionFactory] makes each probe's [HermesConnection].
 */
class BotSetupViewModel(
    private val registry: ServerRegistry,
    private val botStore: BotConnectionStore,
    private val connectionFactory: (BotConnection) -> HermesConnection = ::HermesConnection,
) {
    private val _state = MutableStateFlow(BotSetupUiState())
    val state: StateFlow<BotSetupUiState> = _state

    /** The registry id saved by the last successful connect, for navigation. */
    private var _savedServerId: String? = null
    val savedServerId: String? get() = _savedServerId

    fun updateAddress(text: String) {
        _state.value = _state.value.copy(addressText = text, address = null, addressError = null, error = null, hostStatus = null)
    }

    fun updateUsername(text: String) {
        _state.value = _state.value.copy(username = text, error = null)
    }

    fun updatePassword(text: String) {
        _state.value = _state.value.copy(password = text, error = null)
    }

    fun updateHeaders(text: String) {
        _state.value = _state.value.copy(headersText = text, error = null)
    }

    fun updateName(text: String) {
        _state.value = _state.value.copy(name = text)
    }

    /**
     * Probes the typed address: parses it to the dashboard root, reads the public
     * `/api/status`, and classifies the host. On success advances to credentials.
     */
    suspend fun probe(): Boolean {
        val snapshot = _state.value
        _state.value = snapshot.copy(isWorking = true, addressError = null, error = null)
        try {
            val address = try {
                BotConnection.address(snapshot.addressText)
            } catch (error: BotAddressError.Path) {
                _state.value = _state.value.copy(isWorking = false, addressError = error.message)
                return false
            } catch (_: BotAddressError.Invalid) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    addressError = "Enter the dashboard's HTTP or HTTPS address, like https://example.com:9119.",
                )
                return false
            }
            val probeConnection = BotConnection(
                id = "probe",
                name = "Probe",
                address = address,
                username = "",
                password = "",
            )
            val status: BotJson = try {
                connectionFactory(probeConnection).status()
            } catch (error: Throwable) {
                _state.value = _state.value.copy(isWorking = false, addressError = describeProbeFailure(error))
                return false
            }
            val version = status["version"].text
            if (version != null && !HermesCompatibility.isSupported(version)) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    addressError = "This host runs $version, older than the supported ${HermesCompatibility.minimumVersion}. Update Hermes first.",
                )
                return false
            }
            val parsed = BotHostStatus(status)
            _state.value = _state.value.copy(
                isWorking = false,
                step = BotSetupStep.CREDENTIALS,
                address = address,
                hostStatus = parsed,
                hostVersion = version,
                canConnect = snapshot.username.isNotBlank() || snapshot.password.isNotBlank(),
            )
            refreshCanConnect()
            return true
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isWorking = false, error = error.message)
            return false
        }
    }

    /**
     * Signs in with the form's credentials and saves the connection: the record goes to
     * the encrypted store, the server joins the registry as [ServerKind.HERMES].
     */
    suspend fun connectAndSave(): Boolean {
        val snapshot = _state.value
        val address = snapshot.address ?: return false
        _state.value = snapshot.copy(isWorking = true, error = null, step = BotSetupStep.SAVING)
        try {
            val headers = try {
                parseCustomHeaderLines(snapshot.headersText)
            } catch (_: IllegalArgumentException) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    step = BotSetupStep.CREDENTIALS,
                    error = "Check the Connection Headers: one Name: value per line.",
                )
                return false
            }
            if (snapshot.username.isBlank()) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    step = BotSetupStep.CREDENTIALS,
                    error = "Enter the dashboard username.",
                )
                return false
            }
            if (snapshot.password.isBlank()) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    step = BotSetupStep.CREDENTIALS,
                    error = "Enter the dashboard password.",
                )
                return false
            }
            val record = BotConnection(
                id = UUID.randomUUID().toString(),
                name = snapshot.name.takeIf { it.isNotBlank() } ?: address,
                address = address,
                username = snapshot.username.trim(),
                password = snapshot.password,
                headers = headers.takeIf { it.isNotEmpty() },
            )
            try {
                connectionFactory(record).signIn()
            } catch (error: Throwable) {
                _state.value = _state.value.copy(
                    isWorking = false,
                    step = BotSetupStep.CREDENTIALS,
                    error = describeSignInFailure(error),
                )
                return false
            }
            record.hermesVersion = connectionFactory(record).serverVersion
            val url = address.toHttpUrlOrNull()
                ?: run {
                    _state.value = _state.value.copy(
                        isWorking = false,
                        step = BotSetupStep.CREDENTIALS,
                        error = "This address is not usable.",
                    )
                    return false
                }
            val account = registry.activate(url, displayName = record.name, kind = ServerKind.HERMES, loggedOut = false)
            botStore.save(account.id, record)
            _savedServerId = account.id
            _state.value = _state.value.copy(isWorking = false, step = BotSetupStep.DONE)
            return true
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isWorking = false, step = BotSetupStep.CREDENTIALS, error = error.message)
            return false
        }
    }

    fun backToAddress() {
        _state.value = _state.value.copy(step = BotSetupStep.ADDRESS, error = null)
    }

    private fun refreshCanConnect() {
        val snapshot = _state.value
        _state.value = snapshot.copy(canConnect = snapshot.username.isNotBlank() && snapshot.password.isNotBlank())
    }

    private fun describeProbeFailure(error: Throwable): String = when (error) {
        is BotFailure.NotDashboard -> "This address is not a Hermes dashboard. Check for a webui or proxy page."
        is BotFailure.Blocked -> "Something in front of Hermes wants its own sign-in. Open the address in a browser first."
        is BotFailure.BrowserSignIn -> "This host only offers browser sign-in."
        is BotFailure.Outdated -> "This host runs ${error.version}, older than ${HermesCompatibility.minimumVersion}."
        is BotFailure.Rejected -> "The host refused the probe (${error.code})."
        is BotFailure.Transport -> "The host did not answer. Check the address and network."
        else -> error.message ?: "The host did not answer."
    }

    private fun describeSignInFailure(error: Throwable): String = when (error) {
        is BotFailure.Rejected -> if (error.code == 401) "Wrong username or password." else "The host refused the sign-in (${error.code})."
        is BotFailure.DifferentHost -> "This address now reaches a different host."
        is BotFailure.Outdated -> "This host runs ${error.version}, older than ${HermesCompatibility.minimumVersion}."
        is BotFailure.BrowserSignIn -> "This host only offers browser sign-in."
        is BotFailure.Transport -> "The host did not answer. Check the network and try again."
        else -> error.message ?: "Could not sign in."
    }

    /** Whether the probed status offers basic sign-in at all. */
    fun probedOffersBasic(status: BotJson?): Boolean =
        status?.get("auth_providers")?.list?.any { it.text == "basic" } == true
}
