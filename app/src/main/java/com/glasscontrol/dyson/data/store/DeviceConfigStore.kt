package com.glasscontrol.dyson.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.glasscontrol.dyson.domain.model.DysonDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists which machine is configured and where it lives on the network.
 *
 * The stored record deliberately carries an empty credential: the real one is
 * fetched from [SecureCredentialStore] and merged in by the repository.
 */
class DeviceConfigStore(context: Context) {

    private val dataStore = context.applicationContext.appDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val device: Flow<DysonDevice?> = dataStore.data.map { preferences ->
        preferences[DEVICE_KEY]?.let { stored ->
            runCatching { json.decodeFromString<DysonDevice>(stored) }.getOrNull()
        }
    }

    suspend fun save(device: DysonDevice) {
        dataStore.edit { preferences ->
            preferences[DEVICE_KEY] = json.encodeToString(device.withoutCredential())
        }
    }

    /** Records the address discovery or a manual entry resolved. */
    suspend fun updateHost(host: String) {
        val current = device.first() ?: return
        if (current.host == host) return
        save(current.copy(host = host))
    }

    suspend fun clear() {
        dataStore.edit { it.remove(DEVICE_KEY) }
    }

    private companion object {
        val DEVICE_KEY = stringPreferencesKey("configured_device")
    }
}
