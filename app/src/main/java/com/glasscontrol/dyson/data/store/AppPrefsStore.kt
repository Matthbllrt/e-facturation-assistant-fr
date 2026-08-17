package com.glasscontrol.dyson.data.store

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.glasscontrol.dyson.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether onboarding has been completed, plus the user's display choices. */
data class AppPrefs(
    val theme: AppTheme = AppTheme.SYSTEM,
    val backgroundRefresh: Boolean = true,
)

class AppPrefsStore(context: Context) {

    private val dataStore = context.applicationContext.appDataStore

    val prefs: Flow<AppPrefs> = dataStore.data.map { preferences ->
        AppPrefs(
            theme = preferences[THEME_KEY]
                ?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() }
                ?: AppTheme.SYSTEM,
            backgroundRefresh = preferences[REFRESH_KEY] ?: true,
        )
    }

    suspend fun setTheme(theme: AppTheme) {
        dataStore.edit { it[THEME_KEY] = theme.name }
    }

    suspend fun setBackgroundRefresh(enabled: Boolean) {
        dataStore.edit { it[REFRESH_KEY] = enabled }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("app_theme")
        val REFRESH_KEY = booleanPreferencesKey("background_refresh")
    }
}
