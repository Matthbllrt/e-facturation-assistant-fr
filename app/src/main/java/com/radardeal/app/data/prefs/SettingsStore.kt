package com.radardeal.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.radardeal.app.core.RdLog
import com.radardeal.app.domain.model.ScanFrequency
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Where a listing opens when the user taps "Voir sur Vinted". */
enum class OpenTarget { IN_APP, EXTERNAL }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val defaultIntervalSeconds: Int = ScanFrequency.FAST.seconds,
    val openTarget: OpenTarget = OpenTarget.IN_APP,
    /** Vinted marketplace host the user shops on, e.g. "www.vinted.fr". */
    val vintedHost: String = DEFAULT_HOST,
    /** True once a Vinted session cookie has been observed at least once. */
    val sessionEverEstablished: Boolean = false,
    /** Set while the user asked monitoring to run. Survives process death. */
    val monitoringRequested: Boolean = false,
) {
    companion object {
        const val DEFAULT_HOST = "www.vinted.fr"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "radardeal_settings")

/**
 * Every user preference of the app, stored locally with DataStore.
 *
 * Reads are fail-safe: a corrupted preferences file yields defaults rather than an exception,
 * because this flow is collected during the very first composition of the app.
 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val DEFAULT_INTERVAL = intPreferencesKey("default_interval_seconds")
        val OPEN_TARGET = stringPreferencesKey("open_target")
        val VINTED_HOST = stringPreferencesKey("vinted_host")
        val SESSION_ESTABLISHED = booleanPreferencesKey("session_established")
        val MONITORING_REQUESTED = booleanPreferencesKey("monitoring_requested")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { throwable ->
            if (throwable is IOException) {
                RdLog.w("Settings", "preferences unreadable, falling back to defaults")
                emit(emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            AppSettings(
                onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
                notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
                soundEnabled = prefs[Keys.SOUND] ?: true,
                vibrationEnabled = prefs[Keys.VIBRATION] ?: true,
                defaultIntervalSeconds = prefs[Keys.DEFAULT_INTERVAL] ?: ScanFrequency.FAST.seconds,
                openTarget = runCatching { OpenTarget.valueOf(prefs[Keys.OPEN_TARGET] ?: "") }
                    .getOrDefault(OpenTarget.IN_APP),
                vintedHost = prefs[Keys.VINTED_HOST]?.takeIf { it.isNotBlank() }
                    ?: AppSettings.DEFAULT_HOST,
                sessionEverEstablished = prefs[Keys.SESSION_ESTABLISHED] ?: false,
                monitoringRequested = prefs[Keys.MONITORING_REQUESTED] ?: false,
            )
        }

    private suspend fun write(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runCatching { store.edit(block) }
            .onFailure { RdLog.w("Settings", "write failed", it) }
    }

    suspend fun setOnboardingDone(done: Boolean) = write { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setNotificationsEnabled(v: Boolean) = write { it[Keys.NOTIFICATIONS] = v }
    suspend fun setSoundEnabled(v: Boolean) = write { it[Keys.SOUND] = v }
    suspend fun setVibrationEnabled(v: Boolean) = write { it[Keys.VIBRATION] = v }
    suspend fun setDefaultInterval(seconds: Int) = write { it[Keys.DEFAULT_INTERVAL] = seconds }
    suspend fun setOpenTarget(target: OpenTarget) = write { it[Keys.OPEN_TARGET] = target.name }
    suspend fun setVintedHost(host: String) = write { it[Keys.VINTED_HOST] = host }
    suspend fun setSessionEstablished(v: Boolean) = write { it[Keys.SESSION_ESTABLISHED] = v }
    suspend fun setMonitoringRequested(v: Boolean) = write { it[Keys.MONITORING_REQUESTED] = v }
}
