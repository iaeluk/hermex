package com.iaeluk.hermex.ui.bots

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.botDraftsDataStore by preferencesDataStore(name = "hermex_bot_drafts")

/**
 * Per-bot composer drafts, keyed by server + profile. Memory of the inbox:
 * leaving a chat keeps its unsent text, removing the connection drops them.
 */
class BotDraftStore(context: Context) {
    private val dataStore = context.applicationContext.botDraftsDataStore

    fun draft(serverId: String, profile: String): Flow<String> =
        dataStore.data.map { it[key(serverId, profile)] ?: "" }

    suspend fun current(serverId: String, profile: String): String =
        draft(serverId, profile).first()

    suspend fun save(serverId: String, profile: String, text: String) {
        dataStore.edit { prefs ->
            if (text.isEmpty()) prefs.remove(key(serverId, profile))
            else prefs[key(serverId, profile)] = text.take(20_000)
        }
    }

    suspend fun clearServer(serverId: String) {
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix(serverId)) }
                .forEach { key -> prefs.remove(key) }
        }
    }

    private fun prefix(serverId: String) = "bot_draft::$serverId::"

    private fun key(serverId: String, profile: String) =
        stringPreferencesKey(prefix(serverId) + profile)
}
