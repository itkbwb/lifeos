package com.lifeos.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.data.Dashboard
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class LoadState {
    data object Loading : LoadState()
    data class Loaded(val dashboard: Dashboard) : LoadState()
    data class Error(val message: String) : LoadState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)

    private val _state = MutableStateFlow<LoadState>(LoadState.Loading)
    val state: StateFlow<LoadState> = _state

    private val _serverUrl = MutableStateFlow(SettingsStore.DEFAULT_URL)
    val serverUrl: StateFlow<String> = _serverUrl

    val accessClientId: StateFlow<String> = settingsStore.accessClientId
    val accessClientSecret: StateFlow<String> = settingsStore.accessClientSecret

    init {
        viewModelScope.launch {
            // Any secret from the old plaintext DataStore is already compromised
            // (it sat unencrypted on disk) - discard rather than migrate forward.
            settingsStore.discardLegacyPlaintextCredentials()
        }
        viewModelScope.launch {
            settingsStore.serverUrl.collect { url ->
                _serverUrl.value = url
                refresh()
            }
        }
    }

    private fun buildApi(): com.lifeos.app.data.LifeOsApi {
        return ApiFactory.create(_serverUrl.value, accessClientId.value, accessClientSecret.value)
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = LoadState.Loading
            try {
                val dashboard = withContext(Dispatchers.IO) { buildApi().getDashboard() }
                _state.value = LoadState.Loaded(dashboard)
            } catch (e: Exception) {
                _state.value = LoadState.Error(e.message ?: "Network error")
            }
        }
    }

    fun completeBlock(id: Int) = act(id, "completed")
    fun skipBlock(id: Int) = act(id, "skipped")

    private fun act(id: Int, action: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { buildApi().blockAction(id, action) }
                refresh()
            } catch (_: Exception) {
                // Next manual refresh will retry; nothing to reconcile locally.
            }
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setServerUrl(url)
        }
    }

    fun provisionAccessCredentials(clientId: String, clientSecret: String) {
        settingsStore.setAccessCredentials(clientId, clientSecret)
        refresh()
    }
}
