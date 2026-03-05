package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import com.example.continuumstudio.network.CoordinationApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.coordinationDataStore: DataStore<Preferences> by preferencesDataStore(name = "coordination_settings")

/**
 * ViewModel for the Coordination Dashboard.
 * Manages state for locks, conflicts, handoffs, and shared state across multiple agents.
 */
class CoordinationViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private val HOST_KEY = stringPreferencesKey("coordination_host")
        private val CF_CLIENT_ID_KEY = stringPreferencesKey("cf_access_client_id")
        private val CF_CLIENT_SECRET_KEY = stringPreferencesKey("cf_access_client_secret")
        
        private const val DEFAULT_HOST = "http://obsidian:4040"
        private const val POLL_INTERVAL_MS = 5000L
    }
    
    private val dataStore = application.coordinationDataStore
    
    private var apiClient = CoordinationApiClient()
    private var pollingJob: Job? = null
    
    private val _uiState = MutableStateFlow(CoordinationUiState())
    val uiState: StateFlow<CoordinationUiState> = _uiState.asStateFlow()
    
    private val _host = MutableStateFlow(DEFAULT_HOST)
    val host: StateFlow<String> = _host.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            dataStore.data.collect { preferences ->
                val savedHost = preferences[HOST_KEY] ?: DEFAULT_HOST
                val cfClientId = preferences[CF_CLIENT_ID_KEY]
                val cfClientSecret = preferences[CF_CLIENT_SECRET_KEY]
                
                _host.value = savedHost
                apiClient = CoordinationApiClient(
                    baseUrl = savedHost,
                    cfAccessClientId = cfClientId,
                    cfAccessClientSecret = cfClientSecret
                )
            }
        }
    }
    
    fun updateHost(newHost: String) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                preferences[HOST_KEY] = newHost
            }
            _host.value = newHost
            apiClient = apiClient.updateConfig(newBaseUrl = newHost)
            refresh()
        }
    }
    
    fun updateCloudflareAccess(clientId: String?, clientSecret: String?) {
        viewModelScope.launch {
            dataStore.edit { preferences ->
                if (clientId != null) preferences[CF_CLIENT_ID_KEY] = clientId
                if (clientSecret != null) preferences[CF_CLIENT_SECRET_KEY] = clientSecret
            }
            apiClient = apiClient.updateConfig(
                newCfAccessClientId = clientId,
                newCfAccessClientSecret = clientSecret
            )
            refresh()
        }
    }
    
    fun selectTab(tab: CoordinationTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }
    
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _error.value = null
            
            val healthResult = apiClient.getHealth()
            healthResult.onSuccess { health ->
                _isConnected.value = health.status == "healthy"
                _uiState.update { it.copy(health = health) }
            }.onFailure { e ->
                _isConnected.value = false
                _error.value = "Connection failed: ${e.message}"
            }
            
            when (_uiState.value.selectedTab) {
                CoordinationTab.OVERVIEW -> refreshOverview()
                CoordinationTab.LOCKS -> refreshLocks()
                CoordinationTab.CONFLICTS -> refreshConflicts()
                CoordinationTab.HANDOFFS -> refreshHandoffs()
                CoordinationTab.STATE -> refreshState()
            }
            
            refreshCounts()
            
            _uiState.update { it.copy(isLoading = false) }
        }
    }
    
    private suspend fun refreshOverview() {
        apiClient.getOverview().onSuccess { overview ->
            _uiState.update { it.copy(overview = overview) }
        }
    }
    
    private suspend fun refreshLocks() {
        apiClient.getLocks().onSuccess { response ->
            _uiState.update { it.copy(locks = response.locks) }
        }.onFailure { e ->
            _error.value = "Failed to fetch locks: ${e.message}"
        }
    }
    
    private suspend fun refreshConflicts() {
        apiClient.getConflicts().onSuccess { response ->
            _uiState.update { it.copy(conflicts = response.conflicts) }
        }.onFailure { e ->
            _error.value = "Failed to fetch conflicts: ${e.message}"
        }
        
        apiClient.getConflictStats().onSuccess { stats ->
            _uiState.update { it.copy(conflictStats = stats) }
        }
    }
    
    private suspend fun refreshHandoffs() {
        apiClient.getHandoffs().onSuccess { response ->
            _uiState.update { it.copy(handoffs = response.handoffs) }
        }.onFailure { e ->
            _error.value = "Failed to fetch handoffs: ${e.message}"
        }
        
        apiClient.getHandoffHistory().onSuccess { response ->
            _uiState.update { it.copy(handoffHistory = response.history) }
        }
    }
    
    private suspend fun refreshState() {
        apiClient.getNamespaces().onSuccess { response ->
            _uiState.update { it.copy(stateNamespaces = response.namespaces) }
        }.onFailure { e ->
            _error.value = "Failed to fetch state: ${e.message}"
        }
    }
    
    private suspend fun refreshCounts() {
        var lockCount = 0
        var conflictCount = 0
        var handoffCount = 0
        
        apiClient.getLocks().onSuccess { lockCount = it.count }
        apiClient.getConflicts().onSuccess { conflictCount = it.conflicts.size }
        apiClient.getHandoffs().onSuccess { handoffCount = it.handoffs.size }
        
        _uiState.update {
            it.copy(
                counts = CoordinationCounts(
                    activeLocks = lockCount,
                    activeConflicts = conflictCount,
                    pendingHandoffs = handoffCount
                )
            )
        }
    }
    
    // === Actions ===
    
    fun acquireLock(
        resourceType: String,
        resourceId: String,
        agentId: String,
        mode: String = "exclusive",
        timeout: Int? = null
    ) {
        viewModelScope.launch {
            val request = LockRequest(
                resourceType = resourceType,
                resourceId = resourceId,
                agentId = agentId,
                mode = mode,
                timeout = timeout
            )
            apiClient.acquireLock(request).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to acquire lock: ${e.message}"
            }
        }
    }
    
    fun releaseLock(lockId: String) {
        viewModelScope.launch {
            apiClient.releaseLock(lockId).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to release lock: ${e.message}"
            }
        }
    }
    
    fun resolveConflict(conflictId: String, resolution: String? = null) {
        viewModelScope.launch {
            apiClient.resolveConflict(conflictId, resolution).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to resolve conflict: ${e.message}"
            }
        }
    }
    
    fun initiateHandoff(
        fromAgent: String,
        toAgent: String,
        taskId: String,
        context: Map<String, String> = emptyMap(),
        reason: String? = null
    ) {
        viewModelScope.launch {
            val request = InitiateHandoffRequest(
                fromAgent = fromAgent,
                toAgent = toAgent,
                taskId = taskId,
                context = context,
                reason = reason
            )
            apiClient.initiateHandoff(request).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to initiate handoff: ${e.message}"
            }
        }
    }
    
    fun acceptHandoff(handoffId: String, acceptingAgent: String) {
        viewModelScope.launch {
            val request = AcceptHandoffRequest(acceptingAgent = acceptingAgent)
            apiClient.acceptHandoff(handoffId, request).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to accept handoff: ${e.message}"
            }
        }
    }
    
    fun cancelHandoff(handoffId: String) {
        viewModelScope.launch {
            apiClient.cancelHandoff(handoffId).onSuccess {
                refresh()
            }.onFailure { e ->
                _error.value = "Failed to cancel handoff: ${e.message}"
            }
        }
    }
    
    fun clearError() {
        _error.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
