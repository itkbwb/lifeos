package com.lifeos.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lifeos_settings")

class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_base_url")

    // Legacy plaintext keys - only ever read once, to be wiped, never trusted again.
    private val legacyAccessClientIdKey = stringPreferencesKey("cf_access_client_id")
    private val legacyAccessClientSecretKey = stringPreferencesKey("cf_access_client_secret")

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs = EncryptedSharedPreferences.create(
        context,
        "lifeos_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _accessClientId = MutableStateFlow(securePrefs.getString(KEY_CLIENT_ID, "") ?: "")
    val accessClientId: StateFlow<String> = _accessClientId

    private val _accessClientSecret = MutableStateFlow(securePrefs.getString(KEY_CLIENT_SECRET, "") ?: "")
    val accessClientSecret: StateFlow<String> = _accessClientSecret

    val serverUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[serverUrlKey] ?: DEFAULT_URL
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { prefs -> prefs[serverUrlKey] = url }
    }

    fun setAccessCredentials(clientId: String, clientSecret: String) {
        // Synchronous commit (not apply()): callers - including the adb
        // provisioning path - refresh immediately afterward, so the write
        // must be durable before we return, not merely queued.
        securePrefs.edit()
            .putString(KEY_CLIENT_ID, clientId)
            .putString(KEY_CLIENT_SECRET, clientSecret)
            .commit()
        _accessClientId.value = clientId
        _accessClientSecret.value = clientSecret
    }

    fun hasAccessCredentials(): Boolean =
        _accessClientId.value.isNotBlank() && _accessClientSecret.value.isNotBlank()

    /** Never returns the raw secret - only a trailing-4-char mask for display. */
    fun accessClientSecretMasked(): String {
        val secret = _accessClientSecret.value
        if (secret.length < 4) return if (secret.isEmpty()) "" else "••••••••"
        return "••••••••" + secret.takeLast(4)
    }

    /**
     * Any secret ever stored in the old plaintext DataStore is treated as
     * already compromised (it sat unencrypted on disk) and is discarded,
     * never copied forward into the encrypted store.
     */
    suspend fun discardLegacyPlaintextCredentials() {
        context.dataStore.edit { prefs ->
            prefs.remove(legacyAccessClientIdKey)
            prefs.remove(legacyAccessClientSecretKey)
        }
    }

    companion object {
        private const val KEY_CLIENT_ID = "cf_access_client_id"
        private const val KEY_CLIENT_SECRET = "cf_access_client_secret"

        // Cloudflare Tunnel address; editable from the Settings screen.
        const val DEFAULT_URL = "https://life-os.vip"
    }
}
