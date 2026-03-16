package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

data class SettingsUiState(
    val dialogServerUrl: String = "http://100.109.236.61:8080",
    val cliAgentsServerUrl: String = "http://100.109.236.61:4001",
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
    val notificationsEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val highPriorityAlerts: Boolean = true,
    val showAutoHandledNotifications: Boolean = false,
    val autoReconnect: Boolean = true,
    val autoSync: Boolean = true,
    val triageTimeoutSecs: Long = 30,
    val undoWindowSecs: Long = 10,
    val isLoading: Boolean = false,
    val isDirty: Boolean = false
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        // Server settings
        val KEY_DIALOG_SERVER_URL = stringPreferencesKey("dialog_server_url")
        val KEY_CLI_AGENTS_SERVER_URL = stringPreferencesKey("cli_agents_server_url")
        val KEY_CF_ACCESS_CLIENT_ID = stringPreferencesKey("cf_access_client_id")
        val KEY_CF_ACCESS_CLIENT_SECRET = stringPreferencesKey("cf_access_client_secret")
        
        // Notification settings
        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val KEY_HIGH_PRIORITY_ALERTS = booleanPreferencesKey("high_priority_alerts")
        val KEY_SHOW_AUTO_HANDLED = booleanPreferencesKey("show_auto_handled_notifications")
        
        // Connection settings
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_AUTO_SYNC = booleanPreferencesKey("auto_sync")
        
        // Decision engine settings
        val KEY_TRIAGE_TIMEOUT_SECS = longPreferencesKey("triage_timeout_secs")
        val KEY_UNDO_WINDOW_SECS = longPreferencesKey("undo_window_secs")
        
        // Defaults - Use Obsidian's Tailscale IP for Android connectivity
        const val DEFAULT_DIALOG_SERVER_URL = "http://100.109.236.61:8080"
        const val DEFAULT_CLI_AGENTS_SERVER_URL = "http://100.109.236.61:4001"
        const val DEFAULT_TRIAGE_TIMEOUT_SECS = 30L
        const val DEFAULT_UNDO_WINDOW_SECS = 10L
    }
    
    private val dataStore = application.appSettingsDataStore
    
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    fun loadSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            dataStore.data.first().let { prefs ->
                _uiState.update { current ->
                    current.copy(
                        dialogServerUrl = prefs[KEY_DIALOG_SERVER_URL] ?: DEFAULT_DIALOG_SERVER_URL,
                        cliAgentsServerUrl = prefs[KEY_CLI_AGENTS_SERVER_URL] ?: DEFAULT_CLI_AGENTS_SERVER_URL,
                        cfAccessClientId = prefs[KEY_CF_ACCESS_CLIENT_ID] ?: "",
                        cfAccessClientSecret = prefs[KEY_CF_ACCESS_CLIENT_SECRET] ?: "",
                        notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
                        vibrationEnabled = prefs[KEY_VIBRATION_ENABLED] ?: true,
                        highPriorityAlerts = prefs[KEY_HIGH_PRIORITY_ALERTS] ?: true,
                        showAutoHandledNotifications = prefs[KEY_SHOW_AUTO_HANDLED] ?: false,
                        autoReconnect = prefs[KEY_AUTO_RECONNECT] ?: true,
                        autoSync = prefs[KEY_AUTO_SYNC] ?: true,
                        triageTimeoutSecs = prefs[KEY_TRIAGE_TIMEOUT_SECS] ?: DEFAULT_TRIAGE_TIMEOUT_SECS,
                        undoWindowSecs = prefs[KEY_UNDO_WINDOW_SECS] ?: DEFAULT_UNDO_WINDOW_SECS,
                        isLoading = false,
                        isDirty = false
                    )
                }
            }
        }
    }
    
    fun saveSettings() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                dataStore.edit { prefs ->
                    val state = _uiState.value
                    prefs[KEY_DIALOG_SERVER_URL] = state.dialogServerUrl
                    prefs[KEY_CLI_AGENTS_SERVER_URL] = state.cliAgentsServerUrl
                    prefs[KEY_CF_ACCESS_CLIENT_ID] = state.cfAccessClientId
                    prefs[KEY_CF_ACCESS_CLIENT_SECRET] = state.cfAccessClientSecret
                    prefs[KEY_NOTIFICATIONS_ENABLED] = state.notificationsEnabled
                    prefs[KEY_VIBRATION_ENABLED] = state.vibrationEnabled
                    prefs[KEY_HIGH_PRIORITY_ALERTS] = state.highPriorityAlerts
                    prefs[KEY_SHOW_AUTO_HANDLED] = state.showAutoHandledNotifications
                    prefs[KEY_AUTO_RECONNECT] = state.autoReconnect
                    prefs[KEY_AUTO_SYNC] = state.autoSync
                    prefs[KEY_TRIAGE_TIMEOUT_SECS] = state.triageTimeoutSecs
                    prefs[KEY_UNDO_WINDOW_SECS] = state.undoWindowSecs
                }
                
                _uiState.update { it.copy(isLoading = false, isDirty = false) }
                showToast("Settings saved")
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
                showToast("Failed to save settings: ${e.message}")
            }
        }
    }
    
    fun resetToDefaults() {
        _uiState.update {
            SettingsUiState(isDirty = true)
        }
        showToast("Settings reset to defaults (tap Save to apply)")
    }
    
    // Update methods
    fun updateDialogServerUrl(url: String) {
        _uiState.update { it.copy(dialogServerUrl = url, isDirty = true) }
    }
    
    fun updateCliAgentsServerUrl(url: String) {
        _uiState.update { it.copy(cliAgentsServerUrl = url, isDirty = true) }
    }
    
    fun updateCfAccessClientId(id: String) {
        _uiState.update { it.copy(cfAccessClientId = id, isDirty = true) }
    }
    
    fun updateCfAccessClientSecret(secret: String) {
        _uiState.update { it.copy(cfAccessClientSecret = secret, isDirty = true) }
    }
    
    fun updateNotificationsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(notificationsEnabled = enabled, isDirty = true) }
    }
    
    fun updateVibrationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(vibrationEnabled = enabled, isDirty = true) }
    }
    
    fun updateHighPriorityAlerts(enabled: Boolean) {
        _uiState.update { it.copy(highPriorityAlerts = enabled, isDirty = true) }
    }
    
    fun updateShowAutoHandledNotifications(enabled: Boolean) {
        _uiState.update { it.copy(showAutoHandledNotifications = enabled, isDirty = true) }
    }
    
    fun updateAutoReconnect(enabled: Boolean) {
        _uiState.update { it.copy(autoReconnect = enabled, isDirty = true) }
    }
    
    fun updateAutoSync(enabled: Boolean) {
        _uiState.update { it.copy(autoSync = enabled, isDirty = true) }
    }
    
    fun updateTriageTimeout(secs: Long) {
        _uiState.update { it.copy(triageTimeoutSecs = secs, isDirty = true) }
    }
    
    fun updateUndoWindow(secs: Long) {
        _uiState.update { it.copy(undoWindowSecs = secs, isDirty = true) }
    }
    
    fun showToast(message: String) {
        _toastMessage.value = message
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
}
