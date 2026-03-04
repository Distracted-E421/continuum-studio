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
    }

    // Saved server URL - defaults to public Cloudflare tunnel for mobile access
    val savedServerUrl: Flow<String> = dataStore.data.map { prefs ->
        prefs[PrefsKeys.SERVER_URL] ?: "dialog.datapunk.dev"
    }

    init {
        // Auto-connect on startup if we have a saved URL
        viewModelScope.launch {
            savedServerUrl.first().let { url ->
                if (url.isNotBlank()) {
                    connect(url)
                }
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
            wsClient.connect(serverUrl)
        }
    }

    /**
     * Disconnect from the daemon
     */
    fun disconnect() {
        wsClient.disconnect()
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

    override fun onCleared() {
        super.onCleared()
        wsClient.disconnect()
    }
}

