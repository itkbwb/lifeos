package com.lifeos.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lifeos_settings")

class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_base_url")

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[serverUrlKey] ?: DEFAULT_URL
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[serverUrlKey] = url }
    }

    companion object {
        // Raspberry Pi LAN address at hand-off time; editable from the Settings screen.
        const val DEFAULT_URL = "http://192.168.219.107:8000"
    }
}
