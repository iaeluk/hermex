package com.iaeluk.hermex.data.secure

import com.iaeluk.hermex.core.network.HermesJson
import com.iaeluk.hermex.core.network.bots.BotConnection
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * One saved direct-Hermes connection record per configured server, in the encrypted
 * secret storage (the iOS port keeps the same record in its keychain, scoped by server).
 * Passwords live only here, never in the server registry snapshot.
 */
class BotConnectionStore(
    private val secretStore: SecretStore,
) {
    /** The saved record for `serverId`, or null when none was saved or it no longer decodes. */
    fun load(serverId: String): BotConnection? =
        secretStore.getString(key(serverId))
            ?.let { encoded -> runCatching { HermesJson.decodeFromString<BotConnection>(encoded) }.getOrNull() }

    fun save(serverId: String, connection: BotConnection) {
        secretStore.putString(key(serverId), HermesJson.encodeToString(connection))
    }

    fun remove(serverId: String) {
        secretStore.remove(key(serverId))
    }

    private fun key(serverId: String) = "$KEY_PREFIX$serverId"

    private companion object {
        const val KEY_PREFIX = "bot_connection::"
    }
}
