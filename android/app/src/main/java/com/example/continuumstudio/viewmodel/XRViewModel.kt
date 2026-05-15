package com.example.continuumstudio.viewmodel

import android.app.Application
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
    
    // Glasses display manager
    val glassesManager = GlassesDisplayManager(application)
    
    // Persistence keys
    private object PrefsKeys {
        val XR_CONFIG = stringPreferencesKey("xr_bay_config")
        val ACTIVE_PRESET = stringPreferencesKey("xr_active_preset")
        val GLASSES_THEME = stringPreferencesKey("glasses_theme")
    }
    
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
        
        // Update manager when theme changes
        viewModelScope.launch {
            _glassesTheme.collect { theme ->
                glassesManager.updateTheme(theme)
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save XR config: ${e.message}")
            }
        }
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



}

// Extension to convert timestamp string to Long
private fun ActivityEvent.timestampAsLong(): Long {
    return try {
        java.time.Instant.parse(timestamp).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
