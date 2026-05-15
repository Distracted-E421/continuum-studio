package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import com.example.continuumstudio.service.NowPlayingState
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.youtube.YouTubeState
import com.example.continuumstudio.ui.glasses.*
import kotlinx.coroutines.flow.*
import org.osmdroid.util.GeoPoint
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.continuumstudio.service.GlassesDisplayService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "XRViewModel"

/**
 * ViewModel for XR/Glasses functionality.
 * 
 * Bridges data between phone and glasses displays:
 * - Converts dialog state to glasses format
 * - Manages layout presets and context rules
 * - Handles XR-specific settings
 */
class XRViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = application.dataStore
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    
    // Service binding for persistent mode theme updates
    private var glassesService: GlassesDisplayService? = null
    private var isServiceBound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? GlassesDisplayService.LocalBinder
            glassesService = binder?.getService()
            isServiceBound = true
            // Push current theme to service immediately on connect
            glassesService?.updateTheme(_glassesTheme.value)
            Log.d(TAG, "GlassesDisplayService bound, pushed current theme")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            glassesService = null
            isServiceBound = false
            Log.d(TAG, "GlassesDisplayService unbound")
        }
    }
    
    // Glasses display manager
    val glassesManager = GlassesDisplayManager(application)
    
    // Persistence keys
    private object PrefsKeys {
        val XR_CONFIG = stringPreferencesKey("xr_bay_config")
        val ACTIVE_PRESET = stringPreferencesKey("xr_active_preset")
        val GLASSES_THEME = stringPreferencesKey("glasses_theme")
        val PERSISTENT_MODE = booleanPreferencesKey("glasses_persistent_mode")
    }
    
    // Persistent mode - keeps glasses display active when app is backgrounded
    private val _persistentModeEnabled = MutableStateFlow(false)
    val persistentModeEnabled: StateFlow<Boolean> = _persistentModeEnabled.asStateFlow()
    
    // Display mode detection
    private val _currentDisplayMode = MutableStateFlow(GlassesDisplayService.DisplayMode.UNKNOWN)
    val currentDisplayMode: StateFlow<GlassesDisplayService.DisplayMode> = _currentDisplayMode.asStateFlow()
    
    // XR Configuration
    private val _xrConfig = MutableStateFlow(XRBayConfig())
    val xrConfig: StateFlow<XRBayConfig> = _xrConfig.asStateFlow()
    
    // Active preset name
    private val _activePreset = MutableStateFlow<String?>(null)
    val activePreset: StateFlow<String?> = _activePreset.asStateFlow()
    
    // Glasses theme
    private val _glassesTheme = MutableStateFlow(GlassesTheme())
    val glassesTheme: StateFlow<GlassesTheme> = _glassesTheme.asStateFlow()
    
    // Display state from manager
    val glassesDisplayState = glassesManager.displayState
    val isGlassesConnected = glassesManager.isGlassesConnected
    
    // Layout mode
    private val _layoutMode = MutableStateFlow(GlassesLayoutMode.DIALOG_FOCUSED)
    val layoutMode: StateFlow<GlassesLayoutMode> = _layoutMode.asStateFlow()
    
    // Zoned layout mode
    private val _useZonedLayout = MutableStateFlow(true)
    val useZonedLayout: StateFlow<Boolean> = _useZonedLayout.asStateFlow()
    
    // Dialog control state for glasses sync
    private val _selectedOptionIndex = MutableStateFlow<Int?>(null)
    val selectedOptionIndex: StateFlow<Int?> = _selectedOptionIndex.asStateFlow()
    
    private val _typingText = MutableStateFlow<String?>(null)
    val typingText: StateFlow<String?> = _typingText.asStateFlow()
    
    private val _isDialogControlMode = MutableStateFlow(false)
    val isDialogControlMode: StateFlow<Boolean> = _isDialogControlMode.asStateFlow()
    
    init {
        loadConfig()
        
        // Update manager AND service when theme changes
        viewModelScope.launch {
            _glassesTheme.collect { theme ->
                glassesManager.updateTheme(theme)
                // Also push to service if bound (for persistent mode)
                glassesService?.updateTheme(theme)
            }
        }
        
        // Update manager when layout mode changes
        viewModelScope.launch {
            _layoutMode.collect { mode ->
                glassesManager.updateLayout(mode)
            }
        }
    }
    
    // ============================================================================
    // Configuration Management
    // ============================================================================
    
    private var hasRestoredServiceBinding = false
    
    private fun loadConfig() {
        viewModelScope.launch {
            dataStore.data.collect { prefs ->
                prefs[PrefsKeys.XR_CONFIG]?.let { configJson ->
                    try {
                        _xrConfig.value = json.decodeFromString(configJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse XR config: ${e.message}")
                    }
                }
                
                prefs[PrefsKeys.ACTIVE_PRESET]?.let { preset ->
                    _activePreset.value = preset
                }
                
                prefs[PrefsKeys.GLASSES_THEME]?.let { themeJson ->
                    try {
                        _glassesTheme.value = json.decodeFromString(themeJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse glasses theme: ${e.message}")
                    }
                }
                
                // Load persistent mode setting
                _persistentModeEnabled.value = prefs[PrefsKeys.PERSISTENT_MODE] ?: false
                
                // Restore service binding once after first load
                if (!hasRestoredServiceBinding) {
                    hasRestoredServiceBinding = true
                    restoreServiceBindingIfNeeded()
                }
            }
        }
    }
    
    private fun saveConfig() {
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[PrefsKeys.XR_CONFIG] = json.encodeToString(_xrConfig.value)
                    _activePreset.value?.let { prefs[PrefsKeys.ACTIVE_PRESET] = it }
                    prefs[PrefsKeys.GLASSES_THEME] = json.encodeToString(_glassesTheme.value)
                    prefs[PrefsKeys.PERSISTENT_MODE] = _persistentModeEnabled.value
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save XR config: ${e.message}")
            }
        }
    }
    
    // ============================================================================
    // Persistent Mode Management
    // ============================================================================
    
    /**
     * Enable or disable persistent glasses mode.
     * 
     * When enabled, the glasses display remains active even when the app
     * is backgrounded, using a foreground service.
     * 
     * ## Display Mode Mapping (Samsung S23 Ultra):
     * - PHONE_ONLY: No external display connected
     * - MIRROR: External display duplicating phone (no FLAG_PRESENTATION)
     * - EXTENDED: Presentation display available (independent content)
     * - SAMSUNG_DEX: Samsung DeX desktop mode active
     */
    fun setPersistentMode(enabled: Boolean) {
        _persistentModeEnabled.value = enabled
        val context = getApplication<Application>()
        
        if (enabled) {
            // Start the foreground service
            GlassesDisplayService.start(context)
            Log.i(TAG, "Persistent mode enabled - starting GlassesDisplayService")
            
            // Bind to service to push theme updates
            val intent = Intent(context, GlassesDisplayService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } else {
            // Unbind from service
            if (isServiceBound) {
                try {
                    context.unbindService(serviceConnection)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Service already unbound")
                }
                isServiceBound = false
                glassesService = null
            }
            // Stop the foreground service
            GlassesDisplayService.stop(context)
            Log.i(TAG, "Persistent mode disabled - stopping GlassesDisplayService")
        }
        
        saveConfig()
    }
    
    /**
     * Get the current display mode.
     * 
     * @return Human-readable description of the current mode
     */
    fun getDisplayModeDescription(): String {
        return when (_currentDisplayMode.value) {
            GlassesDisplayService.DisplayMode.UNKNOWN -> "Checking display..."
            GlassesDisplayService.DisplayMode.PHONE_ONLY -> "No external display"
            GlassesDisplayService.DisplayMode.MIRROR -> "Mirror mode (duplicate)"
            GlassesDisplayService.DisplayMode.EXTENDED -> "Extended mode (independent)"
            GlassesDisplayService.DisplayMode.SAMSUNG_DEX -> "Samsung DeX mode"
        }
    }
    
    /**
     * Check if the current mode supports independent glasses content.
     */
    fun canShowIndependentContent(): Boolean {
        return _currentDisplayMode.value == GlassesDisplayService.DisplayMode.EXTENDED ||
               _currentDisplayMode.value == GlassesDisplayService.DisplayMode.SAMSUNG_DEX
    }
    
    // ============================================================================
    // Dialog Bridge
    // ============================================================================
    
    /**
     * Update glasses display with dialog state from DialogViewModel
     */
    fun updateDialogForGlasses(dialog: DialogDetails?) {
        val glassesState = dialog?.let { d ->
            GlassesDialogState(
                id = d.id,
                title = d.title,
                prompt = d.prompt,
                options = d.dialogType.options?.mapIndexed { index, opt ->
                    GlassesDialogOption(
                        value = opt.value,
                        label = opt.label,
                        shortcut = "${index + 1}"
                    )
                } ?: emptyList(),
                timeRemaining = d.timeoutMs?.let { it / 1000 },
                isUrgent = d.title.contains("URGENT", ignoreCase = true) || 
                          d.title.contains("ERROR", ignoreCase = true)
            )
        }
        
        glassesManager.updateDialog(glassesState)
    }
    
    // ============================================================================
    // Activity Bridge
    // ============================================================================
    
    /**
     * Update glasses display with activity events
     */
    fun updateActivityForGlasses(events: List<ActivityEvent>) {
        val glassesEvents = events.take(5).map { event ->
            GlassesActivityItem(
                id = event.hashCode().toString(),
                type = when (event) {
                    is ActivityEvent.Command -> "command"
                    is ActivityEvent.DialogSent -> "dialog_sent"
                    is ActivityEvent.DialogResponse -> "dialog_response"
                    is ActivityEvent.ToolCall -> "tool_call"
                    is ActivityEvent.FileEdit -> "file_edit"
                },
                description = when (event) {
                    is ActivityEvent.Command -> event.command.take(50)
                    is ActivityEvent.DialogSent -> event.title
                    is ActivityEvent.DialogResponse -> "Response: ${event.selection}"
                    is ActivityEvent.ToolCall -> "${event.toolName} (${event.status})"
                    is ActivityEvent.FileEdit -> "${event.action}: ${event.filePath.substringAfterLast("/")}"
                },
                timestamp = event.timestampAsLong()
            )
        }
        
        glassesManager.updateActivity(glassesEvents)
    }
    
    // ============================================================================
    // Connection Status Bridge
    // ============================================================================
    
    /**
     * Update glasses display with connection status
     */
    fun updateConnectionForGlasses(
        isConnected: Boolean,
        serverUrl: String,
        latencyMs: Long?,
        agentCount: Int
    ) {
        glassesManager.updateConnectionStatus(
            GlassesConnectionStatus(
                isConnected = isConnected,
                serverName = serverUrl.substringBefore(":"),
                latencyMs = latencyMs,
                agentCount = agentCount
            )
        )
    }
    
    // ============================================================================
    
    // ============================================================================
    // Now Playing Bridge
    // ============================================================================
    
    /**
     * Update glasses display with now playing state
     */
    fun updateNowPlayingForGlasses(state: NowPlayingState) {
        glassesManager.updateNowPlaying(state)
    }
    
    /**
     * Update glasses display with navigation state
     */
    fun updateNavigationForGlasses(state: NavigationState) {
        glassesManager.updateNavigation(state)
    }
    
    /**
     * Update glasses display with OSM map data.
     * Call this alongside updateNavigationForGlasses when route data is available.
     */
    fun updateOsmMapForGlasses(
        location: GeoPoint?,
        destination: GeoPoint? = null,
        routeGeometry: List<GeoPoint> = emptyList()
    ) {
        glassesManager.updateOsmMap(location, destination, routeGeometry)
    }
    
    fun updateYoutubeForGlasses(state: YouTubeState) {
        glassesManager.updateYoutube(state)
    }
    
    // ============================================================================
    // Control Surface Bridge
    // ============================================================================
    
    /**
     * Update glasses display with control surface state
     * Shows active profile, recent action feedback, and mini-preview
     */
    fun updateControlSurfaceForGlasses(
        activeProfileName: String?,
        activeProfileIcon: String?,
        widgetCount: Int,
        lastAction: String?,
        isEditMode: Boolean
    ) {
        glassesManager.updateControlSurface(
            GlassesControlSurfaceState(
                profileName = activeProfileName,
                profileIcon = activeProfileIcon,
                widgetCount = widgetCount,
                lastAction = lastAction,
                isEditMode = isEditMode,
                timestamp = System.currentTimeMillis()
            )
        )
    }
    
    /**
     * Show action feedback briefly on glasses when a widget is activated
     */
    fun showControlSurfaceAction(actionDescription: String, widgetLabel: String?) {
        glassesManager.showActionFeedback(
            description = actionDescription,
            source = widgetLabel ?: "Control Surface"
        )
    }
    
    // ============================================================================
    // Preset Management
    // ============================================================================
    
    fun applyPreset(presetName: String) {
        val preset = when (presetName) {
            "driving" -> XRLayoutPresets.DrivingMode
            "desk" -> XRLayoutPresets.DeskMode
            else -> return
        }
        
        _xrConfig.value = preset
        _glassesTheme.value = preset.glassesTheme
        _activePreset.value = presetName
        
        // Apply appropriate layout mode
        _layoutMode.value = when (presetName) {
            "driving" -> GlassesLayoutMode.DIALOG_FOCUSED
            "desk" -> GlassesLayoutMode.SPLIT_VIEW
            else -> GlassesLayoutMode.DIALOG_FOCUSED
        }
        
        saveConfig()
    }
    
    // ============================================================================
    // Theme Management
    // ============================================================================
    
    fun setGlassesThemeMode(mode: WidgetThemeMode) {
        _glassesTheme.value = _glassesTheme.value.copy(mode = mode)
        saveConfig()
    }
    
    fun setGlassesColorPalette(palette: GlassesColorPalette) {
        _glassesTheme.value = _glassesTheme.value.copy(palette = palette)
        saveConfig()
    }
    
    fun setGlassesOutlinesOnly(outlines: Boolean) {
        _glassesTheme.value = _glassesTheme.value.copy(useOutlines = outlines)
        saveConfig()
    }
    
    fun setGlassesTextScale(scale: Float) {
        _glassesTheme.value = _glassesTheme.value.copy(fontScale = scale.coerceIn(0.8f, 2.0f))
        saveConfig()
    }
    
    // ============================================================================
    // Layout Mode
    // ============================================================================
    
    fun setLayoutMode(mode: GlassesLayoutMode) {
        _layoutMode.value = mode
    }
    
    // ============================================================================
    // Available Presets
    // ============================================================================
    
    fun getAvailablePresets(): List<String> = listOf("driving", "desk")
    
    fun getPresetDescription(name: String): String = when (name) {
        "driving" -> "Minimal UI, green monochrome, dialog-focused"
        "desk" -> "Split view, high contrast, dialog + activity"
        else -> ""
    }
    
    // ============================================================================
    // Dialog Control Mode (Phone as Input)
    // ============================================================================
    
    fun setDialogControlMode(enabled: Boolean) {
        _isDialogControlMode.value = enabled
        if (!enabled) {
            _selectedOptionIndex.value = null
            _typingText.value = null
        }
    }
    
    fun updateSelectedOption(index: Int?) {
        _selectedOptionIndex.value = index
        // Sync to glasses
        glassesManager.updateDialogSelection(index)
    }
    
    fun updateTypingText(text: String?) {
        _typingText.value = text
        // Sync to glasses
        glassesManager.updateTypingText(text)
    }
    
    fun setUseZonedLayout(enabled: Boolean) {
        _useZonedLayout.value = enabled
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("use_zoned_layout")] = enabled.toString()
            }
        }
    }
    
    fun scrollGlassesContent(scrollAmount: Int) {
        glassesManager.scrollContent(scrollAmount)
    }


    // Theme configuration - aliases for UI
    fun setGlassesPalette(palette: GlassesColorPalette) = setGlassesColorPalette(palette)
    fun setGlassesUseOutlines(useOutlines: Boolean) = setGlassesOutlinesOnly(useOutlines)
    
    fun setGlassesReduceAnimations(reduce: Boolean) {
        _glassesTheme.value = _glassesTheme.value.copy(reduceAnimations = reduce)
        saveConfig()
        glassesManager.updateTheme(_glassesTheme.value)
    }
    
    fun setGlassesFontScale(scale: Float) {
        _glassesTheme.value = _glassesTheme.value.copy(fontScale = scale)
        saveConfig()
        glassesManager.updateTheme(_glassesTheme.value)
    }
    
    fun setGlassesOpacity(opacity: Float) {
        _glassesTheme.value = _glassesTheme.value.copy(opacity = opacity)
        saveConfig()
        glassesManager.updateTheme(_glassesTheme.value)
    }
    
    /**
     * Bind to service if persistent mode is enabled.
     * Call this after loadConfig completes to restore service binding.
     */
    private fun restoreServiceBindingIfNeeded() {
        if (_persistentModeEnabled.value && !isServiceBound) {
            val context = getApplication<Application>()
            val intent = Intent(context, GlassesDisplayService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Restored service binding for persistent mode")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up service binding
        if (isServiceBound) {
            try {
                getApplication<Application>().unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Service already unbound in onCleared")
            }
            isServiceBound = false
            glassesService = null
        }
    }
}

// Extension to convert timestamp string to Long
private fun ActivityEvent.timestampAsLong(): Long {
    return try {
        java.time.Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
