package com.continuumstudio.tablet.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.continuumstudio.tablet.UiMode
import com.continuumstudio.tablet.data.models.Dialog
import com.continuumstudio.tablet.data.models.Agent
import com.continuumstudio.tablet.data.models.ActivityEvent
import com.continuumstudio.tablet.data.models.Task
import com.continuumstudio.tablet.data.network.SynapsixClient
import com.continuumstudio.tablet.data.network.WebSocketEvent
import com.continuumstudio.tablet.data.network.WebSocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppSettings(
    // Phoenix server (agents, tasks, etc.)
    val phoenixUrl: String = "http://100.109.236.61:4001",
    val phoenixWsUrl: String = "ws://100.109.236.61:4001",
    // Dialog daemon (dialogs, history)
    val dialogUrl: String = "http://100.109.236.61:8080",
    val dialogWsUrl: String = "ws://100.109.236.61:8080",
    // UI settings
    val fontScale: Float = 1.0f,
    val ttsEnabled: Boolean = true,
    val ttsAutoRead: Boolean = true,
    val speechRate: Float = 1.0f,
    val highContrast: Boolean = false,
)

data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val lastError: String? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private var apiClient: SynapsixClient? = null
    private var webSocketManager: WebSocketManager? = null
    
    private val _uiMode = MutableStateFlow(UiMode.InfoDense)
    val uiMode: StateFlow<UiMode> = _uiMode.asStateFlow()
    
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _dialogs = MutableStateFlow<List<Dialog>>(emptyList())
    val dialogs: StateFlow<List<Dialog>> = _dialogs.asStateFlow()
    
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    
    private val _parkedAgents = MutableStateFlow<List<Agent>>(emptyList())
    val parkedAgents: StateFlow<List<Agent>> = _parkedAgents.asStateFlow()
    
    private val _activityFeed = MutableStateFlow<List<ActivityEvent>>(emptyList())
    val activityFeed: StateFlow<List<ActivityEvent>> = _activityFeed.asStateFlow()
    
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()
    
    private val _orchestratorMode = MutableStateFlow(OrchestratorMode.UserActive)
    val orchestratorMode: StateFlow<OrchestratorMode> = _orchestratorMode.asStateFlow()
    
    init {
        connect()
    }
    
    fun toggleMode() {
        _uiMode.value = when (_uiMode.value) {
            UiMode.InfoDense -> UiMode.FiveFoot
            UiMode.FiveFoot -> UiMode.InfoDense
        }
    }
    
    fun setMode(mode: UiMode) {
        _uiMode.value = mode
    }
    
    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val oldSettings = _settings.value
        val newSettings = update(oldSettings)
        _settings.value = newSettings
        
        // Reconnect if any server URL changed
        if (newSettings.phoenixUrl != oldSettings.phoenixUrl || 
            newSettings.dialogUrl != oldSettings.dialogUrl) {
            reconnect()
        }
    }
    
    fun setOrchestratorMode(mode: OrchestratorMode) {
        _orchestratorMode.value = mode
    }
    
    fun respondToDialog(dialogId: String, response: String, comment: String? = null) {
        viewModelScope.launch {
            apiClient?.respondToDialog(dialogId, response, comment)?.onSuccess {
                _dialogs.value = _dialogs.value.filter { it.id != dialogId }
            }
        }
    }
    
    fun spawnAgent(prompt: String, workspace: String? = null, preset: String? = null) {
        viewModelScope.launch {
            apiClient?.spawnAgent(prompt, workspace, preset)?.onSuccess { agent ->
                _agents.value = _agents.value + agent
            }
        }
    }
    
    fun unparkAgent(agentId: String) {
        viewModelScope.launch {
            apiClient?.unparkAgent(agentId)?.onSuccess {
                _parkedAgents.value = _parkedAgents.value.filter { it.id != agentId }
            }
        }
    }
    
    fun connect() {
        viewModelScope.launch {
            _connectionState.value = ConnectionState(isConnecting = true)
            val settings = _settings.value
            
            apiClient = SynapsixClient(settings.phoenixUrl, settings.dialogUrl)
            webSocketManager = WebSocketManager(settings.phoenixWsUrl)
            
            apiClient?.ping()?.onSuccess { isHealthy ->
                android.util.Log.d("ContinuumTablet", "Ping result: $isHealthy")
                if (isHealthy) {
                    _connectionState.value = ConnectionState(isConnected = true)
                    webSocketManager?.connect()
                    subscribeToWebSocketEvents()
                    refreshData()
                } else {
                    _connectionState.value = ConnectionState(lastError = "Server unhealthy")
                }
            }?.onFailure { error ->
                android.util.Log.e("ContinuumTablet", "Ping failed: ${error.message}", error)
                _connectionState.value = ConnectionState(lastError = error.message)
            }
        }
    }
    
    fun disconnect() {
        webSocketManager?.disconnect()
        webSocketManager = null
        apiClient = null
        _connectionState.value = ConnectionState()
    }
    
    fun reconnect() {
        disconnect()
        connect()
    }
    
    fun refreshData() {
        viewModelScope.launch {
            apiClient?.let { client ->
                client.getDialogs().onSuccess { dialogs -> _dialogs.value = dialogs }
                client.getAgents().onSuccess { agents -> _agents.value = agents }
                client.getTasks().onSuccess { tasks -> _tasks.value = tasks }
                client.getParkedAgents().onSuccess { agents -> _parkedAgents.value = agents }
            }
        }
    }
    
    private fun subscribeToWebSocketEvents() {
        viewModelScope.launch {
            webSocketManager?.events?.collect { event ->
                when (event) {
                    is WebSocketEvent.Connected -> {
                        _connectionState.value = _connectionState.value.copy(isConnected = true)
                    }
                    is WebSocketEvent.Disconnected -> {
                        _connectionState.value = _connectionState.value.copy(isConnected = false)
                    }
                    is WebSocketEvent.Error -> {
                        _connectionState.value = _connectionState.value.copy(lastError = event.message)
                    }
                    is WebSocketEvent.DialogCreated -> {
                        _dialogs.value = _dialogs.value + event.dialog
                    }
                    is WebSocketEvent.DialogAnswered -> {
                        _dialogs.value = _dialogs.value.filter { it.id != event.dialogId }
                    }
                    is WebSocketEvent.DialogEscalated -> {
                        // Mark dialog as escalated if needed
                    }
                    is WebSocketEvent.AgentStarted -> {
                        _agents.value = _agents.value + event.agent
                    }
                    is WebSocketEvent.AgentCompleted -> {
                        _agents.value = _agents.value.map { 
                            if (it.id == event.agentId) it.copy(status = com.continuumstudio.tablet.data.models.AgentStatus.Completed)
                            else it
                        }
                    }
                    is WebSocketEvent.AgentFailed -> {
                        _agents.value = _agents.value.map {
                            if (it.id == event.agentId) it.copy(status = com.continuumstudio.tablet.data.models.AgentStatus.Failed, error = event.error)
                            else it
                        }
                    }
                    is WebSocketEvent.ActivityEvent -> {
                        _activityFeed.value = listOf(event.event) + _activityFeed.value.take(99)
                    }
                }
            }
        }
    }
}

enum class OrchestratorMode {
    UserActive,
    UserDelegate,
    Spectator,
    Autonomous
}
