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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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
    }

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
        // Auto-connect on startup if we have a saved URL
        viewModelScope.launch {
            savedServerUrl.first().let { url ->
                if (url.isNotBlank()) {
                    connect(url)
                }
            }
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
                        val url = savedServerUrl.value
                        if (url.isNotBlank()) {
                            showToast("Network restored, reconnecting...")
                            connect(url)
                        }
                    }
                }
                wasOffline = !online
            }
        }
    }

    /**
     * Connect to the dialog daemon
     */
    fun connect(serverUrl: String) {
        viewModelScope.launch {
            // Save the URL
            dataStore.edit { prefs ->
                prefs[PrefsKeys.SERVER_URL] = serverUrl
            }
            // Pass CF Access credentials if available
            val clientId = cfAccessClientId.value
            val clientSecret = cfAccessClientSecret.value
            wsClient.connect(serverUrl, clientId, clientSecret)
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

