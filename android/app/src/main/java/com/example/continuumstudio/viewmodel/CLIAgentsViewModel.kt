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
import com.example.continuumstudio.network.CLIAgentsApiClient
import com.example.continuumstudio.network.OrchestratorModeResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.cliAgentsDataStore: DataStore<Preferences> by preferencesDataStore(name = "cli_agents_settings")

/**
 * ViewModel for CLI Agents management.
 * Handles agent spawning, presets, and dialog orchestration.
 */
class CLIAgentsViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private val API_HOST_KEY = stringPreferencesKey("cli_agents_api_host")
        private val DIALOG_HOST_KEY = stringPreferencesKey("dialog_host")
        private val CF_CLIENT_ID_KEY = stringPreferencesKey("cf_access_client_id")
        private val CF_CLIENT_SECRET_KEY = stringPreferencesKey("cf_access_client_secret")
        
        private const val DEFAULT_API_HOST = "http://100.109.236.61:4001"
        private const val DEFAULT_DIALOG_HOST = "http://100.109.236.61:8080"
        private const val POLL_INTERVAL_MS = 5000L
    }
    
    private val dataStore = application.cliAgentsDataStore
    private var apiClient = CLIAgentsApiClient()
    private var pollingJob: Job? = null
    
    // === State Flows ===
    
    private val _uiState = MutableStateFlow(CLIAgentsUiState())
    val uiState: StateFlow<CLIAgentsUiState> = _uiState.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    private val _orchestratorMode = MutableStateFlow<OrchestratorModeResponse?>(null)
    val orchestratorMode: StateFlow<OrchestratorModeResponse?> = _orchestratorMode.asStateFlow()
    
    init {
        loadSettings()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            dataStore.data.collect { preferences ->
                val apiHost = preferences[API_HOST_KEY] ?: DEFAULT_API_HOST
                val dialogHost = preferences[DIALOG_HOST_KEY] ?: DEFAULT_DIALOG_HOST
                val cfClientId = preferences[CF_CLIENT_ID_KEY]
                val cfClientSecret = preferences[CF_CLIENT_SECRET_KEY]
                
                apiClient = CLIAgentsApiClient(
                    baseUrl = apiHost,
                    dialogBaseUrl = dialogHost,
                    cfAccessClientId = cfClientId,
                    cfAccessClientSecret = cfClientSecret
                )
            }
        }
    }
    
    // === Tab Selection ===
    
    fun selectTab(tab: CLIAgentsTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }
    
    // === Polling ===
    
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
    
    // === Refresh ===
    
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _error.value = null
            
            // Refresh based on current tab
            when (_uiState.value.selectedTab) {
                CLIAgentsTab.AGENTS -> refreshAgents()
                CLIAgentsTab.LAUNCH -> refreshPresets()
                CLIAgentsTab.DIALOGS -> refreshDialogs()
            }
            
            // Also fetch orchestrator mode
            refreshOrchestratorMode()
            
            _uiState.update { it.copy(isLoading = false) }
        }
    }
    
    private suspend fun refreshAgents() {
        apiClient.listAgents()
            .onSuccess { agents ->
                _isConnected.value = true
                _uiState.update { it.copy(agents = agents) }
            }
            .onFailure { e ->
                _isConnected.value = false
                _error.value = "Failed to fetch agents: ${e.message}"
            }
    }
    
    private suspend fun refreshPresets() {
        apiClient.listPresets()
            .onSuccess { presets ->
                _uiState.update { it.copy(presets = presets) }
            }
            .onFailure { e ->
                _error.value = "Failed to fetch presets: ${e.message}"
            }
        
        apiClient.listSnippets()
            .onSuccess { snippets ->
                _uiState.update { it.copy(snippets = snippets) }
            }
    }
    
    private suspend fun refreshDialogs() {
        apiClient.fetchPendingDialogs()
            .onSuccess { dialogs ->
                _uiState.update { it.copy(pendingDialogs = dialogs) }
            }
            .onFailure { e ->
                _error.value = "Failed to fetch dialogs: ${e.message}"
            }
    }
    
    private suspend fun refreshOrchestratorMode() {
        apiClient.getOrchestratorMode()
            .onSuccess { mode ->
                _orchestratorMode.value = mode
            }
    }
    
    // === Launch Form ===
    
    fun updateLaunchPrompt(prompt: String) {
        _uiState.update { it.copy(launchPrompt = prompt) }
    }
    
    fun updateLaunchWorkspace(workspace: String) {
        _uiState.update { it.copy(launchWorkspace = workspace) }
    }
    
    fun updateLaunchMode(mode: AgentMode) {
        _uiState.update { it.copy(launchMode = mode) }
    }
    
    fun selectPreset(presetId: String?) {
        _uiState.update { it.copy(selectedPresetId = presetId) }
    }
    
    // === Actions ===
    
    fun spawnAgent() {
        val state = _uiState.value
        if (state.launchPrompt.isBlank() || state.launchWorkspace.isBlank()) {
            showToast("Please fill in prompt and workspace")
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            val preset = state.presets.find { it.id == state.selectedPresetId }
            
            val request = SpawnAgentRequest(
                prompt = state.launchPrompt,
                workspace = state.launchWorkspace,
                mode = state.launchMode.label,
                prefix = preset?.prefix,
                suffix = preset?.suffix
            )
            
            apiClient.spawnAgent(request)
                .onSuccess { response ->
                    showToast("Agent spawned: ${response.agentId.take(8)}...")
                    // Clear form
                    _uiState.update { 
                        it.copy(
                            launchPrompt = "",
                            isLoading = false
                        ) 
                    }
                    // Switch to agents tab
                    selectTab(CLIAgentsTab.AGENTS)
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to spawn agent: ${e.message}"
                    _uiState.update { it.copy(isLoading = false) }
                }
        }
    }
    
    fun stopAgent(agentId: String) {
        viewModelScope.launch {
            apiClient.stopAgent(agentId)
                .onSuccess {
                    showToast("Agent stopped")
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to stop agent: ${e.message}"
                }
        }
    }
    
    fun viewAgentDetails(agentId: String) {
        viewModelScope.launch {
            apiClient.getAgent(agentId)
                .onSuccess { agent ->
                    _uiState.update { it.copy(selectedAgent = agent) }
                }
                .onFailure { e ->
                    _error.value = "Failed to fetch agent: ${e.message}"
                }
        }
    }
    
    fun clearSelectedAgent() {
        _uiState.update { it.copy(selectedAgent = null) }
    }
    
    // === Dialog Response (Orchestration) ===
    
    fun respondToDialog(dialogId: String, selection: String, comment: String? = null) {
        viewModelScope.launch {
            apiClient.respondToDialog(dialogId, selection, comment)
                .onSuccess {
                    showToast("Response sent")
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to respond: ${e.message}"
                }
        }
    }
    
    fun escalateDialog(dialogId: String) {
        viewModelScope.launch {
            apiClient.escalateDialog(dialogId)
                .onSuccess {
                    showToast("Dialog escalated")
                    refresh()
                }
                .onFailure { e ->
                    _error.value = "Failed to escalate: ${e.message}"
                }
        }
    }
    
    // === Orchestrator Mode ===
    
    fun setOrchestratorMode(mode: String) {
        viewModelScope.launch {
            apiClient.setOrchestratorMode(mode)
                .onSuccess {
                    showToast("Mode set to $mode")
                    refreshOrchestratorMode()
                }
                .onFailure { e ->
                    _error.value = "Failed to set mode: ${e.message}"
                }
        }
    }
    
    // === Utilities ===
    
    fun clearError() {
        _error.value = null
    }
    
    fun showToast(message: String) {
        _toastMessage.value = message
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
