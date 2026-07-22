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
    private val accessClientIdKey = stringPreferencesKey("cf_access_client_id")
    private val accessClientSecretKey = stringPreferencesKey("cf_access_client_secret")

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[serverUrlKey] ?: DEFAULT_URL
    }

    val accessClientId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[accessClientIdKey] ?: ""
    }

    val accessClientSecret: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[accessClientSecretKey] ?: ""
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[serverUrlKey] = url }
    }

    suspend fun setAccessCredentials(clientId: String, clientSecret: String) {
        context.dataStore.edit { prefs ->
            prefs[accessClientIdKey] = clientId
            prefs[accessClientSecretKey] = clientSecret
        }
    }

    companion object {
        // Cloudflare Tunnel address; editable from the Settings screen.
        const val DEFAULT_URL = "https://life-os.vip"
    }
}
