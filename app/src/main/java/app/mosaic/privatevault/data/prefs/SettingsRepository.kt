package app.mosaic.privatevault.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mosaic.privatevault.core.log.SafeLog
import app.mosaic.privatevault.domain.alias.Alias
import app.mosaic.privatevault.domain.model.AutoLockDelay
import app.mosaic.privatevault.domain.model.MosaicSettings
import app.mosaic.privatevault.domain.model.NotificationPolicy
import app.mosaic.privatevault.domain.model.SyncMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "mosaic_settings")

/**
 * Non-sensitive preferences only.
 *
 * The alias lives here on purpose — it is the *fake* name, so leaking it leaks
 * nothing. The real handle, the token and the PIN live in the encrypted
 * [app.mosaic.privatevault.data.secure.SecureStore] instead.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<MosaicSettings> = context.settingsDataStore.data
        .catch { error ->
            if (error is IOException) {
                SafeLog.w("settings.read_failed", error)
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { prefs ->
            MosaicSettings(
                syncMode = prefs[Keys.SYNC_MODE]?.toEnum() ?: SyncMode.UNSET,
                alias = prefs[Keys.ALIAS] ?: MosaicSettings.DEFAULT_ALIAS,
                cooldownSeconds = prefs[Keys.COOLDOWN_SECONDS] ?: 0L,
                notificationPolicy = prefs[Keys.NOTIFICATION_POLICY]?.toNotificationPolicy()
                    ?: NotificationPolicy.NONE,
                autoLock = prefs[Keys.AUTO_LOCK]?.toAutoLock() ?: AutoLockDelay.MINUTE_1,
                screenshotProtection = prefs[Keys.SCREENSHOT_PROTECTION] ?: true,
                onboardingComplete = prefs[Keys.ONBOARDING_COMPLETE] ?: false,
                avatarSeed = prefs[Keys.AVATAR_SEED] ?: 0,
            )
        }

    suspend fun setSyncMode(mode: SyncMode) = edit { it[Keys.SYNC_MODE] = mode.name }

    suspend fun setAlias(alias: String) = edit {
        it[Keys.ALIAS] = Alias.sanitize(alias).ifEmpty { MosaicSettings.DEFAULT_ALIAS }
    }

    suspend fun setCooldownSeconds(seconds: Long) = edit {
        it[Keys.COOLDOWN_SECONDS] = seconds.coerceIn(0L, MAX_COOLDOWN_SECONDS)
    }

    suspend fun setNotificationPolicy(policy: NotificationPolicy) =
        edit { it[Keys.NOTIFICATION_POLICY] = policy.name }

    suspend fun setAutoLock(delay: AutoLockDelay) = edit { it[Keys.AUTO_LOCK] = delay.name }

    suspend fun setScreenshotProtection(enabled: Boolean) =
        edit { it[Keys.SCREENSHOT_PROTECTION] = enabled }

    suspend fun setOnboardingComplete(complete: Boolean) =
        edit { it[Keys.ONBOARDING_COMPLETE] = complete }

    suspend fun setAvatarSeed(seed: Int) = edit { it[Keys.AVATAR_SEED] = seed }

    /**
     * When the cooldown elapsed and the discreet "cleanup ready" affordance
     * should appear. Not part of [MosaicSettings] because it is transient state
     * rather than a user preference.
     */
    val cleanupReadyAt: Flow<Long?> = context.settingsDataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CLEANUP_READY_AT] }

    suspend fun setCleanupReadyAt(millis: Long) = edit { it[Keys.CLEANUP_READY_AT] = millis }

    suspend fun clearCleanupReady() = edit { it.remove(Keys.CLEANUP_READY_AT) }

    suspend fun clear() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private fun String.toEnum(): SyncMode? = SyncMode.entries.firstOrNull { it.name == this }
    private fun String.toNotificationPolicy(): NotificationPolicy? =
        NotificationPolicy.entries.firstOrNull { it.name == this }

    private fun String.toAutoLock(): AutoLockDelay? =
        AutoLockDelay.entries.firstOrNull { it.name == this }

    private object Keys {
        val SYNC_MODE = stringPreferencesKey("sync_mode")
        val ALIAS = stringPreferencesKey("alias")
        val COOLDOWN_SECONDS = longPreferencesKey("cooldown_seconds")
        val NOTIFICATION_POLICY = stringPreferencesKey("notification_policy")
        val AUTO_LOCK = stringPreferencesKey("auto_lock")
        val SCREENSHOT_PROTECTION = booleanPreferencesKey("screenshot_protection")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val AVATAR_SEED = intPreferencesKey("avatar_seed")
        val CLEANUP_READY_AT = longPreferencesKey("cleanup_ready_at")
    }

    companion object {
        /** 24 h. A longer "cooldown" is indistinguishable from off. */
        const val MAX_COOLDOWN_SECONDS = 86_400L
    }
}
