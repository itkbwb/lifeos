package com.lifeos.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lifeos_settings")

class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_base_url")
    private val notificationsEnabledKey = booleanPreferencesKey("notifications_enabled")
    private val lastStartNotifiedIdKey = intPreferencesKey("last_start_notified_plan_entry_id")
    private val lastStopNotifiedIdKey = intPreferencesKey("last_stop_notified_event_id")
    private val dayDAnchorDateKey = stringPreferencesKey("day_d_anchor_date")

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

    /** Whether the start/stop suggestion worker is allowed to post notifications
     * (chapter: notifications) - default on; the worker itself checks this every
     * run rather than the periodic WorkManager registration being toggled. */
    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[notificationsEnabledKey] ?: true
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[notificationsEnabledKey] = enabled }
    }

    /** Dedup state so the same Dynamic Plan entry / active session doesn't
     * re-notify on every 15-minute worker tick. -1 means "nothing notified yet". */
    val lastStartNotifiedPlanEntryId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[lastStartNotifiedIdKey] ?: -1
    }

    suspend fun setLastStartNotifiedPlanEntryId(id: Int) {
        context.dataStore.edit { prefs -> prefs[lastStartNotifiedIdKey] = id }
    }

    val lastStopNotifiedEventId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[lastStopNotifiedIdKey] ?: -1
    }

    suspend fun setLastStopNotifiedEventId(id: Int) {
        context.dataStore.edit { prefs -> prefs[lastStopNotifiedIdKey] = id }
    }

    /** The "Day D" anchor date shown on the Dashboard as "День Д" (on the anchor
     * itself) or "День Д+N"/"День Д-N" (days after/before it) - local-only,
     * device-specific, unrelated to any project/plan data on the server. */
    val dayDAnchorDate: Flow<LocalDate?> = context.dataStore.data.map { prefs ->
        prefs[dayDAnchorDateKey]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    suspend fun setDayDAnchorDate(date: LocalDate?) {
        context.dataStore.edit { prefs ->
            if (date == null) prefs.remove(dayDAnchorDateKey) else prefs[dayDAnchorDateKey] = date.toString()
        }
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
