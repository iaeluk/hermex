package com.iaeluk.hermex.ui.bots

import com.iaeluk.hermex.core.network.bots.BotFailure
import com.iaeluk.hermex.core.network.bots.BotProfileEdit
import com.iaeluk.hermex.core.network.bots.get
import com.iaeluk.hermex.core.network.bots.text
import com.iaeluk.hermex.core.network.hermes.BotClient
import com.iaeluk.hermex.core.network.hermes.HermesCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class BotProfileUiState(
    val name: String = "",
    val description: String = "",
    val soul: String = "",
    val cloneFrom: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val nameError: String? = null,
    val saved: Boolean = false,
)

/**
 * Create + edit for bot profiles. Create sends name/description/soul/clone;
 * edit loads describe then saves partial fields.
 */
class BotProfileViewModel(
    private val clientProvider: () -> BotClient,
    private val existing: String? = null,
) {
    private val _state = MutableStateFlow(BotProfileUiState(name = existing.orEmpty()))
    val state: StateFlow<BotProfileUiState> = _state

    fun updateName(v: String) { _state.value = _state.value.copy(name = v, nameError = null, saved = false) }
    fun updateDescription(v: String) { _state.value = _state.value.copy(description = v, saved = false) }
    fun updateSoul(v: String) { _state.value = _state.value.copy(soul = v, saved = false) }
    fun updateCloneFrom(v: String) { _state.value = _state.value.copy(cloneFrom = v, saved = false) }

    suspend fun load() {
        val name = existing ?: return
        try {
            val reply = clientProvider().call(HermesCall.ProfilesDescribe(name))
            _state.value = _state.value.copy(
                description = reply["description"].text.orEmpty(),
                soul = reply["soul"].text.orEmpty(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(error = describe(error))
        }
    }

    suspend fun save() {
        val s = _state.value
        val nameError = BotProfileEdit.validateName(if (existing != null) existing else s.name)
        if (nameError != null) {
            _state.value = s.copy(nameError = nameError)
            return
        }
        _state.value = s.copy(isSaving = true, error = null, nameError = null)
        try {
            if (existing != null) {
                clientProvider().call(
                    HermesCall.ProfilesConfigure(
                        name = existing,
                        description = BotProfileEdit.cleanDescription(s.description) ?: "",
                        soul = BotProfileEdit.cleanSoul(s.soul) ?: "",
                    )
                )
            } else {
                clientProvider().call(
                    HermesCall.ProfilesCreate(
                        name = s.name,
                        description = BotProfileEdit.cleanDescription(s.description),
                        cloneFrom = s.cloneFrom.trim().takeIf { it.isNotEmpty() },
                        soul = BotProfileEdit.cleanSoul(s.soul),
                    )
                )
            }
            _state.value = _state.value.copy(isSaving = false, saved = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = _state.value.copy(isSaving = false, error = describe(error))
        }
    }

    companion object {
        fun describe(error: Throwable): String = when (error) {
            is BotFailure.Stale -> "This connection was replaced."
            is BotFailure.Transport -> "Connection lost."
            is BotFailure.Rejected -> "The host refused the request (${error.code})."
            else -> error.message ?: "Something went wrong."
        }
    }
}
