package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import com.example.continuumstudio.network.DialogEvent
import com.example.continuumstudio.network.DialogWebSocketClient
import com.example.continuumstudio.network.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// DataStore extension
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class DialogViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = application.dataStore
    private val wsClient = DialogWebSocketClient(viewModelScope)
    private val networkMonitor = NetworkMonitor(application)

    // Exposed state
    val connectionState = wsClient.connectionState
    val dialogState = wsClient.dialogState
    val events = wsClient.events
    
    // History and latency
    val history = wsClient.history
    val historyLoading = wsClient.historyLoading
    val latency = wsClient.latency
    
    // Network connectivity status
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    
    // Network type for display
    val networkType: NetworkMonitor.NetworkType
        get() = networkMonitor.getNetworkType()
    
    // Snackbar/Toast message state
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()
    
    fun showToast(message: String) {
        _snackbarMessage.value = message
    }
    
    fun dismissToast() {
        _snackbarMessage.value = null
    }

    // Settings keys
    private object PrefsKeys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val CF_ACCESS_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
        val CF_ACCESS_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
        val ENDPOINTS_JSON = stringPreferencesKey("endpoints_json")
        val ACTIVE_ENDPOINT_INDEX = stringPreferencesKey("active_endpoint_index")
        val ENDPOINT_FALLBACK_ENABLED = booleanPreferencesKey("endpoint_fallback_enabled")
    }

    // Default endpoints - ordered by preference for failover
    // zen1 is primary since Framework is offline (March 2026)
    private val defaultEndpoints = listOf(
        ServerEndpoint(
            name = "zen1 (Primary)",
            url = "100.102.101.72:8082",
            type = EndpointType.TAILSCALE
        ),
        ServerEndpoint(
            name = "Obsidian (Direct)",
            url = "100.109.236.61:8080",
            type = EndpointType.TAILSCALE
        ),
        ServerEndpoint(
            name = "zen1 Proxy",
            url = "100.102.101.72:8081",
            type = EndpointType.TAILSCALE
        ),
        ServerEndpoint(
            name = "Cloudflare",
            url = "dialog.datapunk.dev",
            type = EndpointType.CLOUDFLARE
        )
    )

    // Multi-endpoint support
    private val _endpoints = MutableStateFlow<List<ServerEndpoint>>(defaultEndpoints)
    val endpoints: StateFlow<List<ServerEndpoint>> = _endpoints.asStateFlow()
    
    private val _activeEndpointIndex = MutableStateFlow(0)
    val activeEndpointIndex: StateFlow<Int> = _activeEndpointIndex.asStateFlow()
    
    private val _endpointFallbackEnabled = MutableStateFlow(true)
    val endpointFallbackEnabled: StateFlow<Boolean> = _endpointFallbackEnabled.asStateFlow()
    
    private val _endpointStatus = MutableStateFlow<Map<String, EndpointStatus>>(emptyMap())
    val endpointStatus: StateFlow<Map<String, EndpointStatus>> = _endpointStatus.asStateFlow()
    
    // Orchestrator mode state
    private val _orchestratorMode = MutableStateFlow(OrchestratorPresenceMode.USER_ACTIVE)
    val orchestratorMode: StateFlow<OrchestratorPresenceMode> = _orchestratorMode.asStateFlow()
    
    // Track failed endpoints for fallback
    private val failedEndpoints = mutableSetOf<Int>()

    // Saved server URL - defaults to public Cloudflare tunnel for mobile access
    val savedServerUrl: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.SERVER_URL] ?: "dialog.datapunk.dev"
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "dialog.datapunk.dev")

    // Auto-reconnect setting
    val autoReconnect: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.AUTO_RECONNECT] ?: true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Notifications enabled setting
    val notificationsEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.NOTIFICATIONS_ENABLED] ?: true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Vibration (haptic feedback) enabled setting
    val vibrationEnabled: StateFlow<Boolean> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.VIBRATION_ENABLED] ?: true
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Cloudflare Access Service Token credentials
    val cfAccessClientId: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.CF_ACCESS_CLIENT_ID] ?: ""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val cfAccessClientSecret: StateFlow<String> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.CF_ACCESS_CLIENT_SECRET] ?: ""
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        // Load saved endpoints
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                prefs[PrefsKeys.ENDPOINTS_JSON]?.let { json ->
                    try {
                        val saved = kotlinx.serialization.json.Json.decodeFromString<List<ServerEndpoint>>(json)
                        if (saved.isNotEmpty()) {
                            _endpoints.value = saved
                        }
                    } catch (e: Exception) {
                        // Keep defaults
                    }
                }
                prefs[PrefsKeys.ACTIVE_ENDPOINT_INDEX]?.let {
                    _activeEndpointIndex.value = it.toIntOrNull() ?: 0
                }
                prefs[PrefsKeys.ENDPOINT_FALLBACK_ENABLED]?.let {
                    _endpointFallbackEnabled.value = it
                }
            }
        }
        
        // Auto-connect on startup to active endpoint
        // Delay gives time for:
        // 1. Endpoints to load from DataStore
        // 2. Network stack to initialize
        // 3. Tailscale routes to establish (can take 1-3s on mobile)
        viewModelScope.launch {
            kotlinx.coroutines.delay(1500) // 1.5s startup delay for network stability
            connectToActiveEndpoint()
        }

        // Watch for network recovery and auto-reconnect
        // Only reconnects if we were previously connected and lost connection
        viewModelScope.launch {
            var wasOffline = false
            isOnline.collect { online ->
                if (online && wasOffline) {
                    // Network recovered - check if auto-reconnect is enabled
                    // Also check isReconnecting to avoid duplicate attempts
                    val state = connectionState.value
                    if (autoReconnect.value && 
                        !state.isConnected && 
                        !state.isConnecting && 
                        !state.isReconnecting) {
                        showToast("Network restored, reconnecting...")
                        connectToActiveEndpoint()
                    }
                }
                wasOffline = !online
            }
        }
    }
    
    /**
     * Get the currently active endpoint
     */
    fun getActiveEndpoint(): ServerEndpoint? {
        val index = _activeEndpointIndex.value
        val eps = _endpoints.value
        if (index < eps.size && eps[index].enabled) {
            return eps[index]
        }
        return eps.firstOrNull { it.enabled }
    }
    
    /**
     * Get the base URL of the active endpoint (for use by other ViewModels)
     * Returns the URL in HTTP format (not WebSocket)
     */
    fun getActiveEndpointBaseUrl(): String {
        val endpoint = getActiveEndpoint()
        return if (endpoint != null) {
            wsClient.buildHttpUrlForTest(endpoint.url)
        } else {
            "http://100.102.101.72:8082" // zen1 fallback
        }
    }
    
    /**
     * Get the WebSocket URL of the active endpoint (for use by other ViewModels)
     */
    fun getActiveEndpointWsUrl(): String {
        val endpoint = getActiveEndpoint()
        if (endpoint == null) return "ws://100.102.101.72:8082"
        val url = endpoint.url
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        val secure = url.contains("datapunk.dev") || url.contains("cloudflare")
        return if (secure) "wss://$url" else "ws://$url"
    }
    
    /**
     * Connect to the active endpoint
     */
    fun connectToActiveEndpoint() {
        val endpoint = getActiveEndpoint()
        if (endpoint != null) {
            connect(endpoint.url, endpoint.cfAccessClientId, endpoint.cfAccessClientSecret)
        }
    }
    
    /**
     * Add a new endpoint
     */
    fun addEndpoint(name: String, url: String, type: EndpointType = EndpointType.REMOTE) {
        val newEndpoint = ServerEndpoint(name = name, url = url, type = type)
        _endpoints.value = _endpoints.value + newEndpoint
        saveEndpoints()
    }
    
    /**
     * Remove an endpoint by index
     */
    fun removeEndpoint(index: Int) {
        if (index > 0 && index < _endpoints.value.size) {
            _endpoints.value = _endpoints.value.toMutableList().apply { removeAt(index) }
            if (_activeEndpointIndex.value >= _endpoints.value.size) {
                _activeEndpointIndex.value = 0
            }
            saveEndpoints()
        }
    }
    
    /**
     * Toggle an endpoint's enabled state
     */
    fun toggleEndpoint(index: Int) {
        _endpoints.value = _endpoints.value.toMutableList().apply {
            this[index] = this[index].copy(enabled = !this[index].enabled)
        }
        saveEndpoints()
    }
    
    /**
     * Set the active endpoint and connect to it
     */
    fun setActiveEndpoint(index: Int) {
        if (index < _endpoints.value.size && _endpoints.value[index].enabled) {
            _activeEndpointIndex.value = index
            saveEndpoints()
            failedEndpoints.clear()
            wsClient.disconnect()
            connectToActiveEndpoint()
        }
    }
    
    /**
     * Toggle endpoint fallback
     */
    fun setEndpointFallbackEnabled(enabled: Boolean) {
        _endpointFallbackEnabled.value = enabled
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.ENDPOINT_FALLBACK_ENABLED] = enabled
            }
        }
    }
    
    /**
     * Set orchestrator presence mode
     */
    fun setOrchestratorMode(mode: OrchestratorPresenceMode) {
        _orchestratorMode.value = mode
        viewModelScope.launch {
            try {
                val endpoint = getActiveEndpoint() ?: return@launch
                val httpUrl = wsClient.buildHttpUrlForTest(endpoint.url)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val modeValue = when (mode) {
                    OrchestratorPresenceMode.USER_ACTIVE -> "user_active"
                    OrchestratorPresenceMode.USER_DELEGATE -> "user_delegate"
                    OrchestratorPresenceMode.SPECTATOR -> "spectator"
                    OrchestratorPresenceMode.AUTONOMOUS -> "autonomous"
                }
                
                val body = """{"mode":"$modeValue"}"""
                    .toRequestBody("application/json".toMediaType())
                
                val request = okhttp3.Request.Builder()
                    .url("$httpUrl/api/orchestrator/mode")
                    .post(body)
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().close()
                }
            } catch (e: Exception) {
                // Mode set locally even if API fails
            }
        }
    }
    
    /**
     * Quick respond to a dialog by ID and option value
     */
    fun quickRespond(dialogId: String, optionValue: String) {
        viewModelScope.launch {
            try {
                val endpoint = getActiveEndpoint() ?: return@launch
                val httpUrl = wsClient.buildHttpUrlForTest(endpoint.url)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val body = """{"selection":"$optionValue"}"""
                    .toRequestBody("application/json".toMediaType())
                
                val request = okhttp3.Request.Builder()
                    .url("$httpUrl/api/dialogs/$dialogId/respond")
                    .post(body)
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                if (response.isSuccessful) {
                    showToast("Response sent")
                } else {
                    showToast("Failed to respond: ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                showToast("Error: ${e.message}")
            }
        }
    }
    
    /**
     * Test an endpoint's connectivity
     */
    fun testEndpoint(index: Int) {
        val endpoint = _endpoints.value.getOrNull(index) ?: return
        val url = endpoint.url
        
        _endpointStatus.value = _endpointStatus.value + (url to EndpointStatus(testing = true))
        
        viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            try {
                val httpUrl = wsClient.buildHttpUrlForTest(endpoint.url)
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val request = okhttp3.Request.Builder()
                    .url("$httpUrl/api/status")
                    .apply {
                        if (endpoint.cfAccessClientId.isNotBlank()) {
                            header("CF-Access-Client-Id", endpoint.cfAccessClientId)
                            header("CF-Access-Client-Secret", endpoint.cfAccessClientSecret)
                        }
                    }
                    .build()
                
                val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                val latency = System.currentTimeMillis() - startTime
                
                if (response.isSuccessful) {
                    _endpointStatus.value = _endpointStatus.value + (url to EndpointStatus(
                        connected = true,
                        latency = latency,
                        lastTest = System.currentTimeMillis()
                    ))
                } else {
                    _endpointStatus.value = _endpointStatus.value + (url to EndpointStatus(
                        connected = false,
                        error = "HTTP ${response.code}",
                        lastTest = System.currentTimeMillis()
                    ))
                }
            } catch (e: Exception) {
                _endpointStatus.value = _endpointStatus.value + (url to EndpointStatus(
                    connected = false,
                    error = e.message ?: "Connection failed",
                    lastTest = System.currentTimeMillis()
                ))
            }
        }
    }
    
    /**
     * Test all enabled endpoints
     */
    fun testAllEndpoints() {
        _endpoints.value.forEachIndexed { index, endpoint ->
            if (endpoint.enabled) {
                testEndpoint(index)
            }
        }
    }
    
    /**
     * Try fallback to another endpoint on connection failure
     */
    fun tryFallbackEndpoint(): Boolean {
        if (!_endpointFallbackEnabled.value) return false
        
        failedEndpoints.add(_activeEndpointIndex.value)
        
        for (i in _endpoints.value.indices) {
            if (!failedEndpoints.contains(i) && _endpoints.value[i].enabled) {
                _activeEndpointIndex.value = i
                showToast("Falling back to ${_endpoints.value[i].name}")
                connectToActiveEndpoint()
                return true
            }
        }
        
        // All endpoints failed - reset for next attempt
        failedEndpoints.clear()
        return false
    }
    
    private fun saveEndpoints() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.ENDPOINTS_JSON] = kotlinx.serialization.json.Json.encodeToString(_endpoints.value)
                prefs[PrefsKeys.ACTIVE_ENDPOINT_INDEX] = _activeEndpointIndex.value.toString()
            }
        }
    }

    /**
     * Connect to the dialog daemon
     */
    fun connect(serverUrl: String, clientId: String = "", clientSecret: String = "") {
        viewModelScope.launch {
            // Save the URL
            dataStore.edit { prefs ->
                prefs[PrefsKeys.SERVER_URL] = serverUrl
            }
            // Use provided credentials or fall back to stored ones
            val effectiveClientId = clientId.ifBlank { cfAccessClientId.value }
            val effectiveClientSecret = clientSecret.ifBlank { cfAccessClientSecret.value }
            wsClient.connect(serverUrl, effectiveClientId, effectiveClientSecret)
        }
    }

    /**
     * Disconnect from the daemon
     */
    fun disconnect() {
        wsClient.disconnect()
    }

    /**
     * Update auto-reconnect setting
     */
    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.AUTO_RECONNECT] = enabled
            }
        }
    }

    /**
     * Update notifications enabled setting
     */
    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.NOTIFICATIONS_ENABLED] = enabled
            }
        }
    }

    /**
     * Update vibration (haptic feedback) enabled setting
     */
    fun setVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.VIBRATION_ENABLED] = enabled
            }
        }
    }

    /**
     * Update Cloudflare Access Service Token Client ID
     */
    fun setCfAccessClientId(clientId: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.CF_ACCESS_CLIENT_ID] = clientId
            }
        }
    }

    /**
     * Update Cloudflare Access Service Token Client Secret
     */
    fun setCfAccessClientSecret(clientSecret: String) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[PrefsKeys.CF_ACCESS_CLIENT_SECRET] = clientSecret
            }
        }
    }

    /**
     * Refresh current dialog
     */
    fun refreshDialog() {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let {
                wsClient.fetchCurrentDialog(it)
            }
        }
    }

    /**
     * Submit the current dialog answer
     */
    fun submitAnswer() {
        viewModelScope.launch {
            val state = dialogState.value
            val dialog = state.activeDialog ?: return@launch
            val serverUrl = connectionState.value.serverUrl

            val selection: Any = when (dialog.dialogType.type) {
                "choice" -> {
                    if (dialog.dialogType.allowMultiple == true) {
                        state.selectedOptions.toList()
                    } else {
                        state.selectedValue
                    }
                }
                "text" -> state.selectedValue
                "confirm" -> state.selectedValue == "true"
                "slider" -> state.sliderValue
                else -> state.selectedValue
            }

            wsClient.answerDialog(
                serverUrl = serverUrl,
                dialogId = dialog.id,
                selection = selection,
                comment = state.comment.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * Select a single option (for single-choice dialogs)
     */
    fun selectOption(value: String) {
        wsClient.updateSelectedValue(value)
    }

    /**
     * Toggle an option (for multi-select dialogs)
     */
    fun toggleOption(value: String) {
        wsClient.toggleOption(value)
    }

    /**
     * Update text input value
     */
    fun updateTextInput(value: String) {
        wsClient.updateSelectedValue(value)
    }

    /**
     * Update comment
     */
    fun updateComment(comment: String) {
        wsClient.updateComment(comment)
    }

    /**
     * Update slider value
     */
    fun updateSliderValue(value: Float) {
        wsClient.updateSliderValue(value)
    }

    /**
     * Set confirmation answer
     */
    fun setConfirmation(confirmed: Boolean) {
        wsClient.updateSelectedValue(confirmed.toString())
    }

    /**
     * Toggle hold mode on the server
     */
    fun toggleHoldMode() {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let {
                wsClient.toggleHoldMode(it)
            }
        }
    }
    
    /**
     * Fetch dialog history
     */
    fun fetchHistory(limit: Int = 50) {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let {
                wsClient.fetchHistory(it, limit)
            }
        }
    }
    
    /**
     * Reinvoke a historical dialog
     */
    fun reinvokeDialog(historyItem: com.example.continuumstudio.data.HistoryItem) {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let {
                wsClient.reinvokeDialog(it, historyItem)
            }
        }
    }
    
    /**
     * Manually trigger a ping to measure latency
     */
    fun pingServer() {
        wsClient.sendPing()
    }

    /**
     * Send a test notification to verify notifications work
     */
    fun testNotification() {
        showToast("Test notification sent!")
        // This will be implemented in MainActivity to call the notification service
    }

    /**
     * Execute a quick action on the Synapsix daemon
     * @param action The action name (e.g., "start_cursor", "start_android", "start_godot")
     * @param args Optional arguments for the action
     * @param onResult Callback with the result
     */
    fun executeAction(
        action: String,
        args: List<String> = emptyList(),
        onResult: (com.example.continuumstudio.network.ActionResult) -> Unit = {}
    ) {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let { serverUrl ->
                val result = wsClient.executeAction(serverUrl, action, args)
                onResult(result)
                if (result.success) {
                    showToast("Action '$action' executed")
                } else {
                    showToast("Action failed: ${result.error ?: "Unknown error"}")
                }
            } ?: run {
                showToast("Not connected to server")
                onResult(com.example.continuumstudio.network.ActionResult(
                    success = false,
                    error = "Not connected to server"
                ))
            }
        }
    }

    /**
     * Render a diagram (Mermaid or D2) to SVG
     * @param diagramType "mermaid" or "d2"
     * @param content The diagram source code
     * @param theme "dark" or "light"
     * @param onResult Callback with the SVG result or error
     */
    fun renderDiagram(
        diagramType: String,
        content: String,
        theme: String = "dark",
        onResult: (com.example.continuumstudio.network.DiagramRenderResult) -> Unit
    ) {
        viewModelScope.launch {
            connectionState.value.serverUrl.takeIf { it.isNotBlank() }?.let { serverUrl ->
                val result = wsClient.renderDiagram(serverUrl, diagramType, content, theme)
                onResult(result)
            } ?: run {
                onResult(com.example.continuumstudio.network.DiagramRenderResult(
                    success = false,
                    error = "Not connected to server"
                ))
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}

