package com.glasscontrol.dyson.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.glasscontrol.dyson.domain.model.DysonState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Last known machine state, shared between the app and the widget.
 *
 * The widget renders from this cache so it can paint instantly on a home-screen
 * refresh, then updates again once a live read completes.
 */
class StateCache(context: Context) {

    private val dataStore = context.applicationContext.appDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val state: Flow<DysonState> = dataStore.data.map { preferences ->
        preferences[STATE_KEY]?.let { stored ->
            runCatching { json.decodeFromString<DysonState>(stored) }.getOrNull()
        } ?: DysonState()
    }

    suspend fun read(): DysonState = state.first()

    suspend fun write(state: DysonState) {
        dataStore.edit { preferences ->
            preferences[STATE_KEY] = json.encodeToString(state)
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(STATE_KEY) }
    }

    private companion object {
        val STATE_KEY = stringPreferencesKey("cached_state")
    }
}
