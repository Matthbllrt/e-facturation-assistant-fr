package com.glasscontrol.dyson.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Single DataStore for everything that is not a secret.
 *
 * Credentials live in [SecureCredentialStore] instead; what is kept here is
 * device identity, cached sensor state and widget preferences — all of it
 * recoverable, none of it sensitive.
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dyson_glass")
