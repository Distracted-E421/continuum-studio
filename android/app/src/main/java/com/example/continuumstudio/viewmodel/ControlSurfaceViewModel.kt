package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.xrcontrol.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "ControlSurfaceViewModel"

private val Context.controlSurfaceDataStore: DataStore<Preferences> by preferencesDataStore(name = "control_surface")

class ControlSurfaceViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        val KEY_PROFILES_JSON = stringPreferencesKey("profiles_json")
        val KEY_ACTIVE_PROFILE_ID = stringPreferencesKey("active_profile_id")
        val KEY_AUTO_SWITCH_ENABLED = booleanPreferencesKey("auto_switch_enabled")
    }
    
    private val dataStore = application.controlSurfaceDataStore
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        encodeDefaults = true
    }
    
    private val actionExecutor = ActionExecutor(application)
    
    private val _state = MutableStateFlow(ControlSurfaceState())
    val state: StateFlow<ControlSurfaceState> = _state.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    private val _autoSwitchEnabled = MutableStateFlow(true)
    val autoSwitchEnabled: StateFlow<Boolean> = _autoSwitchEnabled.asStateFlow()
    
    // Callback for navigation/dialog integration
    var onNavigate: ((String) -> Unit)? = null
    var onDialogSubmit: ((Int?, String?) -> Unit)? = null
    var onLayoutModeChange: ((String) -> Unit)? = null
    
    init {
        loadProfiles()
        setupActionCallbacks()
    }
    
    private fun loadProfiles() {
        viewModelScope.launch {
            dataStore.data.first().let { prefs ->
                val profilesJson = prefs[KEY_PROFILES_JSON]
                val activeProfileId = prefs[KEY_ACTIVE_PROFILE_ID]
                val autoSwitch = prefs[KEY_AUTO_SWITCH_ENABLED] ?: true
                
                _autoSwitchEnabled.value = autoSwitch
                
                val profiles = if (profilesJson != null) {
                    try {
                        json.decodeFromString<List<ControlProfile>>(profilesJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse profiles", e)
                        DefaultProfiles.allDefaults
                    }
                } else {
                    DefaultProfiles.allDefaults
                }
                
                val activeProfile = profiles.find { it.id == activeProfileId }
                    ?: profiles.find { it.isDefault }
                    ?: profiles.firstOrNull()
                
                _state.update { current ->
                    current.copy(
                        allProfiles = profiles,
                        activeProfile = activeProfile
                    )
                }
            }
        }
    }
    
    private fun saveProfiles() {
        viewModelScope.launch {
            try {
                dataStore.edit { prefs ->
                    prefs[KEY_PROFILES_JSON] = json.encodeToString(_state.value.allProfiles)
                    _state.value.activeProfile?.let { 
                        prefs[KEY_ACTIVE_PROFILE_ID] = it.id 
                    }
                    prefs[KEY_AUTO_SWITCH_ENABLED] = _autoSwitchEnabled.value
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save profiles", e)
            }
        }
    }
    
    private fun setupActionCallbacks() {
        actionExecutor.callback = object : ActionExecutor.ActionCallback {
            override fun onActionStarted(action: WidgetAction) {
                Log.d(TAG, "Action started: $action")
            }
            
            override fun onActionCompleted(action: WidgetAction, success: Boolean, result: String?) {
                Log.d(TAG, "Action completed: $success - $result")
                _state.update { current ->
                    current.copy(
                        lastAction = action,
                        actionHistory = (current.actionHistory + ExecutedAction(action, "", success = success, result = result)).takeLast(50)
                    )
                }
            }
            
            override fun onDialogSubmit(optionIndex: Int?, optionValue: String?) {
                onDialogSubmit?.invoke(optionIndex, optionValue)
            }
            
            override fun onNavigate(route: String) {
                onNavigate?.invoke(route)
            }
            
            override fun onLayoutModeChange(mode: String) {
                onLayoutModeChange?.invoke(mode)
            }
            
            override fun onProfileSwitch(profileId: String) {
                if (profileId == "cycle") {
                    cycleProfile()
                } else {
                    switchToProfile(profileId)
                }
            }
            
            override fun onPointerEvent(event: WidgetAction.PointerEvent) {
                // Handle pointer events - typically send to glasses presentation
                Log.d(TAG, "Pointer event: $event")
            }
            
            override fun onScrollEvent(direction: ScrollDirection, amount: Float) {
                // Handle scroll events
                Log.d(TAG, "Scroll: $direction x $amount")
            }
        }
    }
    
    // ========================================================================
    // Profile Management
    // ========================================================================
    
    fun switchToProfile(profileId: String) {
        val profile = _state.value.allProfiles.find { it.id == profileId }
        if (profile != null) {
            _state.update { it.copy(activeProfile = profile) }
            saveProfiles()
            showToast("Switched to ${profile.name}")
        }
    }
    
    fun cycleProfile() {
        val profiles = _state.value.allProfiles
        val currentIndex = profiles.indexOfFirst { it.id == _state.value.activeProfile?.id }
        val nextIndex = (currentIndex + 1) % profiles.size
        switchToProfile(profiles[nextIndex].id)
    }
    
    fun createProfile(name: String, basedOn: String? = null): ControlProfile {
        val baseProfile = basedOn?.let { id -> 
            _state.value.allProfiles.find { it.id == id } 
        } ?: DefaultProfiles.generalProfile
        
        val newProfile = baseProfile.copy(
            id = "profile-${System.currentTimeMillis()}",
            name = name,
            isDefault = false,
            createdAt = System.currentTimeMillis(),
            modifiedAt = System.currentTimeMillis()
        )
        
        _state.update { current ->
            current.copy(allProfiles = current.allProfiles + newProfile)
        }
        saveProfiles()
        
        return newProfile
    }
    
    fun deleteProfile(profileId: String) {
        val profile = _state.value.allProfiles.find { it.id == profileId }
        if (profile?.isDefault == true) {
            showToast("Cannot delete default profile")
            return
        }
        
        _state.update { current ->
            val newProfiles = current.allProfiles.filter { it.id != profileId }
            val newActive = if (current.activeProfile?.id == profileId) {
                newProfiles.find { it.isDefault } ?: newProfiles.firstOrNull()
            } else {
                current.activeProfile
            }
            current.copy(allProfiles = newProfiles, activeProfile = newActive)
        }
        saveProfiles()
    }
    
    fun duplicateProfile(profileId: String): ControlProfile? {
        val original = _state.value.allProfiles.find { it.id == profileId } ?: return null
        return createProfile("${original.name} (Copy)", profileId)
    }
    
    // ========================================================================
    // Widget Management
    // ========================================================================
    
    fun addWidget(widget: ControlWidget) {
        updateActiveProfile { profile ->
            profile.copy(
                widgets = profile.widgets + widget,
                modifiedAt = System.currentTimeMillis()
            )
        }
    }
    
    fun addWidget(type: ControlWidgetType) {
        val profile = _state.value.activeProfile ?: return
        
        // Find an empty spot in the grid
        val occupiedCells = mutableSetOf<Pair<Int, Int>>()
        profile.widgets.forEach { widget ->
            for (x in widget.gridX until widget.gridX + widget.spanX) {
                for (y in widget.gridY until widget.gridY + widget.spanY) {
                    occupiedCells.add(Pair(x, y))
                }
            }
        }
        
        // Find first empty cell
        var foundX = 0
        var foundY = 0
        outer@ for (y in 0 until profile.gridRows) {
            for (x in 0 until profile.gridColumns) {
                if (Pair(x, y) !in occupiedCells) {
                    foundX = x
                    foundY = y
                    break@outer
                }
            }
        }
        
        val newWidget = ControlWidget(
            id = "widget-${System.currentTimeMillis()}",
            type = type,
            gridX = foundX,
            gridY = foundY,
            spanX = 1,
            spanY = 1,
            config = WidgetConfig(
                label = type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
            )
        )
        
        addWidget(newWidget)
        selectWidget(newWidget.id)
        showToast("Widget added")
    }
    
    fun duplicateWidget(widgetId: String) {
        val profile = _state.value.activeProfile ?: return
        val widget = profile.widgets.find { it.id == widgetId } ?: return
        
        // Find next available position
        var newX = widget.gridX + 1
        var newY = widget.gridY
        if (newX + widget.spanX > profile.gridColumns) {
            newX = 0
            newY = widget.gridY + widget.spanY
        }
        if (newY + widget.spanY > profile.gridRows) {
            newY = 0
        }
        
        val newWidget = widget.copy(
            id = "widget-${System.currentTimeMillis()}",
            gridX = newX.coerceIn(0, profile.gridColumns - widget.spanX),
            gridY = newY.coerceIn(0, profile.gridRows - widget.spanY)
        )
        
        addWidget(newWidget)
        selectWidget(newWidget.id)
        showToast("Widget duplicated")
    }
    
    fun setDraggingWidget(widgetId: String?) {
        _state.update { it.copy(draggingWidgetId = widgetId) }
    }
    
    fun renameProfile(profileId: String, newName: String) {
        _state.update { current ->
            val updated = current.allProfiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(name = newName, modifiedAt = System.currentTimeMillis())
                } else profile
            }
            current.copy(
                allProfiles = updated,
                activeProfile = if (current.activeProfile?.id == profileId) {
                    current.activeProfile.copy(name = newName, modifiedAt = System.currentTimeMillis())
                } else current.activeProfile
            )
        }
        saveProfiles()
    }
    
    fun removeWidget(widgetId: String) {
        updateActiveProfile { profile ->
            profile.copy(
                widgets = profile.widgets.filter { it.id != widgetId },
                modifiedAt = System.currentTimeMillis()
            )
        }
    }
    
    fun updateWidget(widgetId: String, updater: (ControlWidget) -> ControlWidget) {
        updateActiveProfile { profile ->
            profile.copy(
                widgets = profile.widgets.map { 
                    if (it.id == widgetId) updater(it) else it 
                },
                modifiedAt = System.currentTimeMillis()
            )
        }
    }
    
    fun moveWidget(widgetId: String, newGridX: Int, newGridY: Int) {
        updateWidget(widgetId) { widget ->
            widget.copy(gridX = newGridX, gridY = newGridY)
        }
    }
    
    fun resizeWidget(widgetId: String, newSpanX: Int, newSpanY: Int) {
        updateWidget(widgetId) { widget ->
            widget.copy(
                spanX = newSpanX.coerceIn(1, 4),
                spanY = newSpanY.coerceIn(1, 4)
            )
        }
    }
    
    fun copyWidget(widgetId: String) {
        val widget = _state.value.activeProfile?.widgets?.find { it.id == widgetId }
        if (widget != null) {
            _state.update { it.copy(clipboardWidget = widget) }
            showToast("Widget copied")
        }
    }
    
    fun pasteWidget(gridX: Int, gridY: Int) {
        val clipboard = _state.value.clipboardWidget ?: return
        val newWidget = clipboard.copy(
            id = "widget-${System.currentTimeMillis()}",
            gridX = gridX,
            gridY = gridY
        )
        addWidget(newWidget)
        showToast("Widget pasted")
    }
    
    private fun updateActiveProfile(updater: (ControlProfile) -> ControlProfile) {
        _state.update { current ->
            val activeProfile = current.activeProfile ?: return@update current
            val updatedProfile = updater(activeProfile)
            current.copy(
                activeProfile = updatedProfile,
                allProfiles = current.allProfiles.map { 
                    if (it.id == updatedProfile.id) updatedProfile else it 
                }
            )
        }
        saveProfiles()
    }
    
    // ========================================================================
    // Widget Interaction
    // ========================================================================
    
    fun onWidgetPressed(widgetId: String) {
        _state.update { current ->
            current.copy(
                widgetStates = current.widgetStates + (widgetId to WidgetState(widgetId, isPressed = true))
            )
        }
    }
    
    fun onWidgetReleased(widgetId: String) {
        _state.update { current ->
            current.copy(
                widgetStates = current.widgetStates + (widgetId to 
                    (current.widgetStates[widgetId] ?: WidgetState(widgetId)).copy(isPressed = false)
                )
            )
        }
    }
    
    fun onWidgetTapped(widgetId: String) {
        val widget = _state.value.activeProfile?.widgets?.find { it.id == widgetId } ?: return
        
        viewModelScope.launch {
            when (widget.type) {
                ControlWidgetType.TOGGLE -> {
                    // Toggle state and execute actions
                    val currentState = _state.value.widgetStates[widgetId]?.toggleState ?: widget.config.toggleState
                    _state.update { current ->
                        current.copy(
                            widgetStates = current.widgetStates + (widgetId to 
                                WidgetState(widgetId, toggleState = !currentState)
                            )
                        )
                    }
                    if (widget.actions.isNotEmpty()) {
                        actionExecutor.executeMacro(widget.actions, widgetId)
                    }
                }
                
                ControlWidgetType.FOLDER -> {
                    // Toggle folder expansion
                    val currentExpanded = _state.value.widgetStates[widgetId]?.folderExpanded ?: false
                    _state.update { current ->
                        current.copy(
                            widgetStates = current.widgetStates + (widgetId to 
                                WidgetState(widgetId, folderExpanded = !currentExpanded)
                            )
                        )
                    }
                }
                
                else -> {
                    // Execute actions
                    if (widget.actions.isNotEmpty()) {
                        actionExecutor.executeMacro(widget.actions, widgetId)
                    }
                }
            }
        }
    }
    
    fun onSliderValueChanged(widgetId: String, value: Float) {
        _state.update { current ->
            current.copy(
                widgetStates = current.widgetStates + (widgetId to 
                    WidgetState(widgetId, currentValue = value)
                )
            )
        }
        
        // Execute slider actions with the new value
        val widget = _state.value.activeProfile?.widgets?.find { it.id == widgetId } ?: return
        viewModelScope.launch {
            widget.actions.forEach { action ->
                // Replace placeholders in actions with actual value
                actionExecutor.execute(action, widgetId)
            }
        }
    }
    
    fun onTrackpadGesture(widgetId: String, gesture: GestureType, deltaX: Float, deltaY: Float) {
        _state.update { current ->
            current.copy(
                trackpadState = current.trackpadState.copy(
                    isActive = true,
                    velocity = deltaX to deltaY,
                    gestureType = gesture
                )
            )
        }
        
        val widget = _state.value.activeProfile?.widgets?.find { it.id == widgetId } ?: return
        val sensitivity = widget.config.trackpadSensitivity
        
        viewModelScope.launch {
            when (gesture) {
                GestureType.SCROLL -> {
                    val direction = when {
                        deltaY > 0 -> ScrollDirection.UP
                        deltaY < 0 -> ScrollDirection.DOWN
                        deltaX > 0 -> ScrollDirection.LEFT
                        else -> ScrollDirection.RIGHT
                    }
                    actionExecutor.execute(
                        WidgetAction.Scroll(direction, kotlin.math.abs(deltaY.coerceAtLeast(deltaX)) * sensitivity),
                        widgetId
                    )
                }
                
                GestureType.TAP -> {
                    actionExecutor.execute(
                        WidgetAction.PointerEvent(PointerEventType.CLICK, deltaX, deltaY),
                        widgetId
                    )
                }
                
                GestureType.DOUBLE_TAP -> {
                    actionExecutor.execute(
                        WidgetAction.PointerEvent(PointerEventType.DOUBLE_CLICK, deltaX, deltaY),
                        widgetId
                    )
                }
                
                else -> {
                    // Handle swipes, pinches, etc.
                }
            }
        }
    }
    
    fun onDPadDirection(widgetId: String, direction: DPadDirection) {
        viewModelScope.launch {
            val keyCode = when (direction) {
                DPadDirection.UP -> android.view.KeyEvent.KEYCODE_DPAD_UP
                DPadDirection.DOWN -> android.view.KeyEvent.KEYCODE_DPAD_DOWN
                DPadDirection.LEFT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
                DPadDirection.RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                DPadDirection.CENTER -> android.view.KeyEvent.KEYCODE_DPAD_CENTER
                DPadDirection.UP_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_UP_LEFT
                DPadDirection.UP_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_UP_RIGHT
                DPadDirection.DOWN_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_DOWN_LEFT
                DPadDirection.DOWN_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_DOWN_RIGHT
            }
            actionExecutor.execute(WidgetAction.KeyEvent(keyCode), widgetId)
        }
    }
    
    // ========================================================================
    // Edit Mode
    // ========================================================================
    
    fun setEditMode(enabled: Boolean) {
        _state.update { it.copy(isEditMode = enabled, selectedWidgetId = null) }
    }
    
    fun selectWidget(widgetId: String?) {
        _state.update { it.copy(selectedWidgetId = widgetId) }
    }
    
    // ========================================================================
    // Auto-switching
    // ========================================================================
    
    fun setAutoSwitchEnabled(enabled: Boolean) {
        _autoSwitchEnabled.value = enabled
        saveProfiles()
    }
    
    fun evaluateAutoSwitch(
        hasActiveDialog: Boolean,
        dialogType: String?,
        isMediaPlaying: Boolean,
        isNavigating: Boolean,
        currentLayoutMode: String
    ) {
        if (!_autoSwitchEnabled.value) return
        
        val profiles = _state.value.allProfiles
            .filter { it.autoActivateConditions.isNotEmpty() }
            .sortedByDescending { it.priority }
        
        for (profile in profiles) {
            val shouldActivate = profile.autoActivateConditions.any { condition ->
                when (condition) {
                    is ProfileCondition.DialogActive -> hasActiveDialog && 
                        (condition.dialogType == null || condition.dialogType == dialogType)
                    is ProfileCondition.MediaPlaying -> isMediaPlaying
                    is ProfileCondition.NavigationActive -> isNavigating
                    is ProfileCondition.GlassesLayoutMode -> condition.mode == currentLayoutMode
                    else -> false
                }
            }
            
            if (shouldActivate && _state.value.activeProfile?.id != profile.id) {
                switchToProfile(profile.id)
                return
            }
        }
        
        // Fall back to default if no conditions match
        if (!hasActiveDialog && !isMediaPlaying && !isNavigating) {
            val defaultProfile = profiles.find { it.isDefault }
            if (defaultProfile != null && _state.value.activeProfile?.id != defaultProfile.id) {
                switchToProfile(defaultProfile.id)
            }
        }
    }
    
    // ========================================================================
    // Utils
    // ========================================================================
    
    fun showToast(message: String) {
        _toastMessage.value = message
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    fun resetToDefaults() {
        _state.update { current ->
            current.copy(
                allProfiles = DefaultProfiles.allDefaults,
                activeProfile = DefaultProfiles.generalProfile
            )
        }
        saveProfiles()
        showToast("Reset to default profiles")
    }
}

enum class DPadDirection {
    UP, DOWN, LEFT, RIGHT, CENTER,
    UP_LEFT, UP_RIGHT, DOWN_LEFT, DOWN_RIGHT
}
