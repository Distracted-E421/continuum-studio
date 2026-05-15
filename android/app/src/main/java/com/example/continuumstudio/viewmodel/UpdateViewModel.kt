package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.update.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "UpdateViewModel"

private val Context.updateSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_settings")

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        val KEY_SETTINGS_JSON = stringPreferencesKey("settings_json")
        val KEY_LAST_CHECK_TIME = longPreferencesKey("last_check_time")
        val KEY_AVAILABLE_UPDATE_JSON = stringPreferencesKey("available_update_json")
    }
    
    private val dataStore = application.updateSettingsDataStore
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val updateManager = UpdateManager(application)
    private val updateDownloader = UpdateDownloader(application)
    private val updateInstaller = UpdateInstaller(application)
    
    private val _uiState = MutableStateFlow(UpdateUiState(
        currentVersion = updateManager.getCurrentVersionName(),
        currentVersionCode = updateManager.getCurrentVersionCode()
    ))
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    init {
        loadSettings()
        observeInstallResult()
    }
    
    private fun loadSettings() {
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                val settingsJson = prefs[KEY_SETTINGS_JSON]
                val settings = if (settingsJson != null) {
                    try {
                        json.decodeFromString<UpdateSettings>(settingsJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse settings", e)
                        UpdateSettings()
                    }
                } else {
                    UpdateSettings()
                }
                
                val lastCheckTime = prefs[KEY_LAST_CHECK_TIME]
                val availableUpdateJson = prefs[KEY_AVAILABLE_UPDATE_JSON]
                val availableUpdate = if (availableUpdateJson != null) {
                    try {
                        json.decodeFromString<UpdateInfo>(availableUpdateJson)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
                
                _uiState.update { current ->
                    current.copy(
                        settings = settings,
                        lastCheckTime = lastCheckTime,
                        availableUpdate = availableUpdate
                    )
                }
            }
        }
    }
    
    private fun saveSettings() {
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[KEY_SETTINGS_JSON] = json.encodeToString(_uiState.value.settings)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save settings", e)
            }
        }
    }
    
    private fun observeInstallResult() {
        viewModelScope.launch {
            updateInstaller.installResult.collect { result ->
                when (result) {
                    is InstallResult.Success -> {
                        showToast("Update installed successfully!")
                        _uiState.update { it.copy(downloadState = DownloadState.Idle, availableUpdate = null) }
                        clearAvailableUpdate()
                    }
                    is InstallResult.Failure -> {
                        showToast("Installation failed: ${result.message}")
                        _uiState.update { it.copy(downloadState = DownloadState.Error(result.message)) }
                    }
                    is InstallResult.UserCancelled -> {
                        showToast("Installation cancelled")
                        _uiState.update { it.copy(downloadState = DownloadState.Idle) }
                    }
                    is InstallResult.Pending -> {
                        _uiState.update { it.copy(downloadState = DownloadState.Installing) }
                    }
                }
            }
        }
    }
    
    /**
     * Check for updates
     */
    fun checkForUpdates(forceCheck: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, checkErrors = emptyList()) }
            
            val (result, errors) = updateManager.checkForUpdates(_uiState.value.settings, forceCheck)
            val currentTime = System.currentTimeMillis()
            
            when (result) {
                is UpdateCheckResult.Available -> {
                    _uiState.update { current ->
                        current.copy(
                            isChecking = false,
                            lastCheckTime = currentTime,
                            availableUpdate = result.info,
                            checkErrors = errors
                        )
                    }
                    persistAvailableUpdate(result.info)
                    showToast("Update available: ${result.info.versionName}")
                }
                is UpdateCheckResult.UpToDate -> {
                    _uiState.update { current ->
                        current.copy(
                            isChecking = false,
                            lastCheckTime = currentTime,
                            availableUpdate = null,
                            checkErrors = errors
                        )
                    }
                    clearAvailableUpdate()
                    showToast("You're up to date!")
                }
                is UpdateCheckResult.Error -> {
                    _uiState.update { current ->
                        current.copy(
                            isChecking = false,
                            checkErrors = errors + (result.source to result.message)
                        )
                    }
                    showToast("Update check failed: ${result.message}")
                }
            }
            
            // Persist last check time
            dataStore.edit { prefs ->
                prefs[KEY_LAST_CHECK_TIME] = currentTime
            }
        }
    }
    
    /**
     * Download available update
     */
    fun downloadUpdate() {
        val updateInfo = _uiState.value.availableUpdate ?: return
        
        viewModelScope.launch {
            updateDownloader.downloadUpdate(updateInfo).collect { state ->
                _uiState.update { it.copy(downloadState = state) }
                
                if (state is DownloadState.Downloaded) {
                    showToast("Download complete!")
                } else if (state is DownloadState.Error) {
                    showToast("Download failed: ${state.message}")
                }
            }
        }
    }
    
    /**
     * Install downloaded update
     */
    fun installUpdate() {
        val downloadState = _uiState.value.downloadState
        if (downloadState !is DownloadState.Downloaded) {
            showToast("No downloaded update to install")
            return
        }
        
        if (!updateInstaller.canRequestInstall()) {
            showToast("Please grant install permission")
            updateInstaller.openInstallPermissionSettings()
            return
        }
        
        _uiState.update { it.copy(downloadState = DownloadState.Installing) }
        updateInstaller.installApk(downloadState.filePath)
    }
    
    /**
     * Cancel download
     */
    fun cancelDownload() {
        viewModelScope.launch {
            updateDownloader.cleanupDownloads()
            _uiState.update { it.copy(downloadState = DownloadState.Idle) }
        }
    }
    
    /**
     * Dismiss available update (skip this version)
     */
    fun dismissUpdate() {
        _uiState.update { it.copy(availableUpdate = null, downloadState = DownloadState.Idle) }
        viewModelScope.launch {
            updateDownloader.cleanupDownloads()
            clearAvailableUpdate()
        }
    }
    
    // Settings updates
    fun updateAutoCheckEnabled(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(autoCheckEnabled = enabled)) }
        saveSettings()
    }
    
    fun updateCheckOnCellular(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(checkOnCellular = enabled)) }
        saveSettings()
    }
    
    fun updateAutoDownload(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(autoDownload = enabled)) }
        saveSettings()
    }
    
    fun updateCheckInterval(hours: Int) {
        _uiState.update { it.copy(settings = it.settings.copy(checkIntervalHours = hours)) }
        saveSettings()
    }
    
    fun updateIncludePreReleases(enabled: Boolean) {
        _uiState.update { it.copy(settings = it.settings.copy(includePreReleases = enabled)) }
        saveSettings()
    }
    
    fun updateSynapsixEndpoint(endpoint: String) {
        _uiState.update { it.copy(settings = it.settings.copy(synapsixEndpoint = endpoint)) }
        saveSettings()
    }
    
    fun updateGiteaEndpoint(endpoint: String) {
        _uiState.update { it.copy(settings = it.settings.copy(giteaEndpoint = endpoint)) }
        saveSettings()
    }
    
    fun updateGithubRepo(repo: String) {
        _uiState.update { it.copy(settings = it.settings.copy(githubRepo = repo)) }
        saveSettings()
    }
    
    fun toggleSource(source: UpdateSource, enabled: Boolean) {
        val currentSources = _uiState.value.settings.enabledSources.toMutableSet()
        if (enabled) {
            currentSources.add(source.name)
        } else {
            currentSources.remove(source.name)
        }
        _uiState.update { it.copy(settings = it.settings.copy(enabledSources = currentSources)) }
        saveSettings()
    }
    
    private suspend fun persistAvailableUpdate(info: UpdateInfo) {
        dataStore.edit { prefs ->
            prefs[KEY_AVAILABLE_UPDATE_JSON] = json.encodeToString(info)
        }
    }
    
    private suspend fun clearAvailableUpdate() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_AVAILABLE_UPDATE_JSON)
        }
    }
    
    fun showToast(message: String) {
        _toastMessage.value = message
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        updateInstaller.cleanup()
    }
}
