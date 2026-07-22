package com.lifeos.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lifeos.app.data.ApiFactory
import com.lifeos.app.data.Block
import com.lifeos.app.data.DayPlan
import com.lifeos.app.data.LifeOsApi
import com.lifeos.app.data.NowResponse
import com.lifeos.app.data.ProjectStat
import com.lifeos.app.data.RescheduleRequest
import com.lifeos.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.time.DayOfWeek
import java.time.LocalDate

private const val POLL_INTERVAL_MS = 20_000L

sealed class ConnectionState {
    data object Loading : ConnectionState()
    data class Loaded(val now: NowResponse, val fetchedAtMillis: Long) : ConnectionState()
    data object NoConnection : ConnectionState()
    data object ServerUnavailable : ConnectionState()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsStore = SettingsStore(application)

    private val _nowState = MutableStateFlow<ConnectionState>(ConnectionState.Loading)
    val nowState: StateFlow<ConnectionState> = _nowState

    private val _dayPlan = MutableStateFlow<DayPlan?>(null)
    val dayPlan: StateFlow<DayPlan?> = _dayPlan

    private val _weekPlans = MutableStateFlow<List<DayPlan>>(emptyList())
    val weekPlans: StateFlow<List<DayPlan>> = _weekPlans

    private val _projects = MutableStateFlow<List<ProjectStat>>(emptyList())
    val projects: StateFlow<List<ProjectStat>> = _projects

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
                refreshNow()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                refreshNow(showLoading = false)
            }
        }
    }

    private fun buildApi(): LifeOsApi =
        ApiFactory.create(_serverUrl.value, accessClientId.value, accessClientSecret.value)

    fun refreshNow(showLoading: Boolean = true) {
        viewModelScope.launch {
            if (showLoading) _nowState.value = ConnectionState.Loading
            try {
                val fetchedAt = System.currentTimeMillis()
                val now = withContext(Dispatchers.IO) { buildApi().getNow() }
                _nowState.value = ConnectionState.Loaded(now, fetchedAt)
            } catch (e: IOException) {
                android.util.Log.w("LifeOS", "getNow: no connection", e)
                _nowState.value = ConnectionState.NoConnection
            } catch (e: HttpException) {
                android.util.Log.w("LifeOS", "getNow: server error ${e.code()}", e)
                _nowState.value = ConnectionState.ServerUnavailable
            } catch (e: Exception) {
                android.util.Log.w("LifeOS", "getNow: unexpected error", e)
                _nowState.value = ConnectionState.ServerUnavailable
            }
        }
    }

    fun refreshDayPlan(date: String? = null) {
        viewModelScope.launch {
            try {
                _dayPlan.value = withContext(Dispatchers.IO) { buildApi().getDayPlan(date) }
            } catch (_: Exception) {
                // Day screen keeps showing its last known list; Now screen already
                // surfaces the connectivity state.
            }
        }
    }

    fun refreshWeekPlan(anchorDate: String? = null) {
        viewModelScope.launch {
            try {
                val anchor = anchorDate?.let { LocalDate.parse(it) } ?: LocalDate.now()
                val monday = anchor.with(DayOfWeek.MONDAY)
                val dates = (0 until 7).map { monday.plusDays(it.toLong()).toString() }
                val plans = withContext(Dispatchers.IO) {
                    val api = buildApi()
                    dates.map { date -> api.getDayPlan(date) }
                }
                _weekPlans.value = plans
            } catch (_: Exception) {
                // Week screen keeps showing its last known data.
            }
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            try {
                _projects.value = withContext(Dispatchers.IO) { buildApi().getProjects() }
            } catch (_: Exception) {
            }
        }
    }

    private fun act(id: Int, call: suspend LifeOsApi.(Int) -> Block) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { buildApi().call(id) }
            } catch (_: HttpException) {
                // 409 = already in that state, or another session is active;
                // resyncing below shows the real current state either way.
            } catch (_: Exception) {
            }
            refreshNow(showLoading = false)
            refreshDayPlan()
        }
    }

    fun startBlock(id: Int) = act(id) { startBlock(it) }
    fun restartBlock(id: Int) = act(id) { restartBlock(it) }
    fun pauseBlock(id: Int) = act(id) { pauseBlock(it) }
    fun resumeBlock(id: Int) = act(id) { resumeBlock(it) }
    fun completeBlock(id: Int) = act(id) { completeBlock(it) }
    fun skipBlock(id: Int) = act(id) { skipBlock(it) }
    fun cancelBlock(id: Int) = act(id) { cancelBlock(it) }

    fun rescheduleBlock(id: Int, plannedStart: String, plannedEnd: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    buildApi().rescheduleBlock(id, RescheduleRequest(plannedStart, plannedEnd))
                }
            } catch (_: Exception) {
            }
            refreshNow(showLoading = false)
            refreshDayPlan()
        }
    }

    fun updateServerUrl(url: String) {
        viewModelScope.launch { settingsStore.setServerUrl(url) }
    }

    fun provisionAccessCredentials(clientId: String, clientSecret: String) {
        settingsStore.setAccessCredentials(clientId, clientSecret)
        refreshNow()
    }
}
