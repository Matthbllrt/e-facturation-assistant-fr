package com.glasscontrol.dyson.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** How a widget instance should look and which controls it exposes. */
@Serializable
data class WidgetConfig(
    val serial: String? = null,
    val theme: WidgetTheme = WidgetTheme.AUTO,
    /** 0f = fully transparent glass, 1f = solid panel. */
    val glassOpacity: Float = 0.55f,
    val showSensors: Boolean = true,
    val quickControls: List<QuickControl> = DEFAULT_CONTROLS,
) {
    companion object {
        val DEFAULT_CONTROLS = listOf(
            QuickControl.POWER,
            QuickControl.AUTO,
            QuickControl.OSCILLATION,
        )
    }
}

@Serializable
enum class WidgetTheme { LIGHT, DARK, AUTO }

/** The controls a user can put on a widget. */
@Serializable
enum class QuickControl { POWER, AUTO, OSCILLATION, NIGHT, SPEED, HEAT }

/**
 * Per-instance widget preferences.
 *
 * Keyed by app widget id so two widgets on the same home screen can be
 * configured independently — one compact and opaque, one hero and translucent.
 */
class WidgetConfigStore(context: Context) {

    private val dataStore = context.applicationContext.appDataStore
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun config(appWidgetId: Int): Flow<WidgetConfig> = dataStore.data.map { preferences ->
        preferences[key(appWidgetId)]?.let { stored ->
            runCatching { json.decodeFromString<WidgetConfig>(stored) }.getOrNull()
        } ?: WidgetConfig()
    }

    suspend fun read(appWidgetId: Int): WidgetConfig = config(appWidgetId).first()

    suspend fun save(appWidgetId: Int, config: WidgetConfig) {
        dataStore.edit { preferences ->
            preferences[key(appWidgetId)] = json.encodeToString(config)
        }
    }

    suspend fun remove(appWidgetId: Int) {
        dataStore.edit { it.remove(key(appWidgetId)) }
    }

    private fun key(appWidgetId: Int) = stringPreferencesKey("widget_$appWidgetId")

    companion object {
        /**
         * Pseudo-id holding the defaults a newly added widget starts from.
         *
         * Real app widget ids are positive, so a negative id cannot collide.
         */
        const val DEFAULTS_ID = -1
    }
}
