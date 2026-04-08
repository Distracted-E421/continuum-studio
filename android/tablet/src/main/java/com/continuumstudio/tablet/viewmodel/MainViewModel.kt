package com.continuumstudio.tablet.viewmodel

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
import java.util.UUID

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
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenDialogId: String? = null
    
    private val _ttsState = MutableStateFlow(TtsState())
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()
    
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
    
    private val _dialogHistory = MutableStateFlow<List<Dialog>>(emptyList())
    val dialogHistory: StateFlow<List<Dialog>> = _dialogHistory.asStateFlow()
    
    private val _orchestratorMode = MutableStateFlow(OrchestratorMode.UserActive)
    val orchestratorMode: StateFlow<OrchestratorMode> = _orchestratorMode.asStateFlow()
    
    init {
        initTts()
        connect()
    }
    
    private fun initTts() {
        tts = TextToSpeech(getApplication()) { status ->
            android.util.Log.d("ContinuumTTS", "TTS init status: $status")
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.US)
                android.util.Log.d("ContinuumTTS", "Language set result: $langResult")
                tts?.setSpeechRate(_settings.value.speechRate)
                ttsReady = true
                _ttsState.value = _ttsState.value.copy(isReady = true)
                android.util.Log.d("ContinuumTTS", "TTS initialized successfully")
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d("ContinuumTTS", "TTS started: $utteranceId")
                        viewModelScope.launch { _ttsState.value = _ttsState.value.copy(isSpeaking = true) }
                    }
                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d("ContinuumTTS", "TTS done: $utteranceId")
                        viewModelScope.launch { _ttsState.value = _ttsState.value.copy(isSpeaking = false) }
                    }
                    override fun onError(utteranceId: String?) {
                        android.util.Log.e("ContinuumTTS", "TTS error: $utteranceId")
                        viewModelScope.launch { _ttsState.value = _ttsState.value.copy(isSpeaking = false) }
                    }
                })
            } else {
                android.util.Log.e("ContinuumTTS", "TTS init failed with status: $status")
            }
        }
    }
    
    fun speak(text: String, queue: Boolean = false) {
        android.util.Log.d("ContinuumTTS", "speak called: ttsReady=$ttsReady, enabled=${_settings.value.ttsEnabled}")
        if (!ttsReady) {
            android.util.Log.w("ContinuumTTS", "TTS not ready")
            return
        }
        if (!_settings.value.ttsEnabled) {
            android.util.Log.w("ContinuumTTS", "TTS disabled in settings")
            return
        }
        _ttsState.value = _ttsState.value.copy(isSpeaking = true)
        val queueMode = if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        val result = tts?.speak(text, queueMode, null, UUID.randomUUID().toString())
        android.util.Log.d("ContinuumTTS", "speak result: $result")
    }
    
    fun stopSpeaking() {
        tts?.stop()
        _ttsState.value = _ttsState.value.copy(isSpeaking = false)
    }
    
    // Clean text for TTS - remove emojis, hashes, improve readability
    private fun cleanTextForTts(text: String): String {
        return text
            // Remove emojis (common emoji ranges)
            .replace(Regex("[\\p{So}\\p{Cs}]"), "")
            // Replace arrows with words
            .replace("→", " to ")
            .replace("->", " to ")
            .replace("←", " from ")
            .replace("<-", " from ")
            .replace("↑", " up ")
            .replace("↓", " down ")
            // Remove backtick-quoted code/identifiers
            .replace(Regex("`[^`]+`"), "code")
            // Remove hex hashes (like git commits: 7+ hex chars)
            .replace(Regex("\\b[a-fA-F0-9]{7,}\\b"), "identifier")
            // Remove UUIDs
            .replace(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"), "identifier")
            // Remove URLs
            .replace(Regex("https?://[^\\s]+"), "link")
            // Remove file paths
            .replace(Regex("/[a-zA-Z0-9_/.-]+"), "path")
            // Add pause after newlines by adding period
            .replace(Regex("\n+"), ". ")
            // Add period before bullet points for pause
            .replace(Regex("\\s*[-*•]\\s+"), ". ")
            // Clean up multiple periods
            .replace(Regex("\\.\\s*\\."), ".")
            // Clean up multiple spaces
            .replace(Regex("\\s+"), " ")
            .trim()
    }
    
    // Manual speak - always speaks (no duplicate check)
    fun speakDialog(dialog: Dialog, force: Boolean = true) {
        android.util.Log.d("ContinuumTTS", "speakDialog: ${dialog.title}, force=$force")
        if (!_settings.value.ttsEnabled) {
            android.util.Log.w("ContinuumTTS", "TTS disabled, not speaking")
            return
        }
        if (!force && dialog.id == lastSpokenDialogId) {
            android.util.Log.d("ContinuumTTS", "Already spoken, skipping")
            return
        }
        lastSpokenDialogId = dialog.id
        
        val text = buildString {
            append("${cleanTextForTts(dialog.title)}. ")
            append(cleanTextForTts(dialog.prompt))
            if (!dialog.options.isNullOrEmpty()) {
                append(". Options are: ")
                dialog.options.forEachIndexed { idx, opt ->
                    append("${idx + 1}: ${cleanTextForTts(opt.label)}. ")
                }
            }
        }
        speak(text)
    }
    
    fun autoSpeakNewDialogs() {
        if (_settings.value.ttsEnabled && _settings.value.ttsAutoRead) {
            _dialogs.value.firstOrNull { !it.isAnswered }?.let { dialog ->
                if (dialog.id != lastSpokenDialogId) {
                    speakDialog(dialog, force = false)
                }
            }
        }
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
                // Stop TTS if we're speaking the dialog being answered
                if (lastSpokenDialogId == dialogId && _ttsState.value.isSpeaking) {
                    stopSpeaking()
                }
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
                client.getDialogs().onSuccess { dialogs -> 
                    _dialogs.value = dialogs
                    autoSpeakNewDialogs()
                }
                client.getDialogHistory(100).onSuccess { history -> _dialogHistory.value = history }
                client.getAgents().onSuccess { agents -> _agents.value = agents }
                client.getTasks().onSuccess { tasks -> _tasks.value = tasks }
                client.getParkedAgents().onSuccess { agents -> _parkedAgents.value = agents }
            }
        }
    }
    
    fun refreshDialogHistory() {
        viewModelScope.launch {
            apiClient?.getDialogHistory(100)?.onSuccess { history -> _dialogHistory.value = history }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
        tts = null
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

data class TtsState(
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
)
