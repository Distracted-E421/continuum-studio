package com.example.continuumstudio.xrcontrol

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable

/**
 * XR Control Surface - Virtual Stream Deck for power users
 * 
 * A fully customizable, drag-and-drop control surface that transforms
 * the phone into a programmable input device for XR glasses interaction.
 */

// ============================================================================
// Widget Types
// ============================================================================

@Serializable
enum class ControlWidgetType {
    /** Single tap action button */
    ACTION_BUTTON,
    
    /** Button that executes a sequence of actions */
    MACRO_BUTTON,
    
    /** Touch gesture area for mouse/scroll control */
    TRACKPAD,
    
    /** Pop-up or fixed keyboard */
    KEYBOARD,
    
    /** 4-way or 8-way directional pad */
    DPAD,
    
    /** Continuous value slider (volume, brightness, scroll speed) */
    SLIDER,
    
    /** Rotary encoder simulation */
    DIAL,
    
    /** Quick response buttons for dialog options */
    DIALOG_OPTIONS,
    
    /** Read-only status display (connection, time, battery) */
    STATUS_DISPLAY,
    
    /** Toggle switch for on/off states */
    TOGGLE,
    
    /** Folder that reveals more buttons when tapped */
    FOLDER,
    
    /** Empty spacer for layout */
    SPACER
}

// ============================================================================
// Widget Configuration
// ============================================================================

@Serializable
data class ControlWidget(
    val id: String,
    val type: ControlWidgetType,
    val gridX: Int,
    val gridY: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val config: WidgetConfig = WidgetConfig(),
    val actions: List<WidgetAction> = emptyList()
)

@Serializable
data class WidgetConfig(
    val label: String = "",
    val icon: String? = null,
    val iconColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0xFF2D2D2D,
    val accentColor: Long = 0xFF00FF88,
    val fontSize: Float = 14f,
    val showLabel: Boolean = true,
    val hapticFeedback: Boolean = true,
    
    // Type-specific configs
    val trackpadSensitivity: Float = 1.0f,
    val trackpadScrollMultiplier: Float = 2.0f,
    val sliderMin: Float = 0f,
    val sliderMax: Float = 100f,
    val sliderStep: Float = 1f,
    val dpadMode: DPadMode = DPadMode.FOUR_WAY,
    val keyboardType: KeyboardType = KeyboardType.FULL,
    val toggleState: Boolean = false
)

@Serializable
enum class DPadMode { FOUR_WAY, EIGHT_WAY, ANALOG }

@Serializable
enum class KeyboardType { FULL, NUMPAD, SYMBOLS, CUSTOM }

// Gesture types for trackpad interactions
enum class GestureType {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SCROLL,
    SWIPE,
    PINCH
}

// ============================================================================
// Actions & Macros
// ============================================================================

@Serializable
sealed class WidgetAction {
    /** Execute a shell command via Synapsix */
    @Serializable
    data class ShellCommand(val command: String, val description: String = "") : WidgetAction()
    
    /** Send a dialog response */
    @Serializable
    data class DialogResponse(val optionIndex: Int? = null, val optionValue: String? = null) : WidgetAction()
    
    /** Navigate within the app */
    @Serializable
    data class AppNavigation(val route: String) : WidgetAction()
    
    /** Send a key event to glasses display */
    @Serializable
    data class KeyEvent(val keyCode: Int, val modifiers: List<String> = emptyList()) : WidgetAction()
    
    /** Simulate mouse/touch input */
    @Serializable
    data class PointerEvent(
        val type: PointerEventType,
        val x: Float = 0f,
        val y: Float = 0f,
        val deltaX: Float = 0f,
        val deltaY: Float = 0f
    ) : WidgetAction()
    
    /** Scroll in a direction */
    @Serializable
    data class Scroll(val direction: ScrollDirection, val amount: Float = 1f) : WidgetAction()
    
    /** Change glasses layout mode */
    @Serializable
    data class SetLayoutMode(val mode: String) : WidgetAction()
    
    /** Trigger a Synapsix dialog */
    @Serializable
    data class ShowDialog(val dialogType: String, val prompt: String, val options: List<String> = emptyList()) : WidgetAction()
    
    /** Switch control surface profile */
    @Serializable
    data class SwitchProfile(val profileId: String) : WidgetAction()
    
    /** Adjust a system setting */
    @Serializable
    data class SystemSetting(val setting: String, val value: String) : WidgetAction()
    
    /** Open an external app/URL */
    @Serializable
    data class Launch(val target: String, val isUrl: Boolean = false) : WidgetAction()
    
    /** Delay between macro steps */
    @Serializable
    data class Delay(val milliseconds: Long) : WidgetAction()
    
    /** HTTP request to Synapsix or other APIs */
    @Serializable
    data class HttpRequest(
        val method: String = "POST",
        val url: String,
        val body: String? = null,
        val headers: Map<String, String> = emptyMap()
    ) : WidgetAction()
    
    /** Conditional action based on state */
    @Serializable
    data class Conditional(
        val condition: String,
        val thenActions: List<WidgetAction>,
        val elseActions: List<WidgetAction> = emptyList()
    ) : WidgetAction()
}

@Serializable
enum class PointerEventType { CLICK, DOUBLE_CLICK, RIGHT_CLICK, MOVE, DRAG_START, DRAG, DRAG_END }

@Serializable
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

// ============================================================================
// Profiles
// ============================================================================

@Serializable
data class ControlProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val icon: String? = null,
    val gridColumns: Int = 4,
    val gridRows: Int = 6,
    val widgets: List<ControlWidget> = emptyList(),
    val autoActivateConditions: List<ProfileCondition> = emptyList(),
    val priority: Int = 0,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
)

@Serializable
sealed class ProfileCondition {
    /** Activate when a dialog is active */
    @Serializable
    data class DialogActive(val dialogType: String? = null) : ProfileCondition()
    
    /** Activate when glasses layout mode matches */
    @Serializable
    data class GlassesLayoutMode(val mode: String) : ProfileCondition()
    
    /** Activate when specific app is in foreground (on glasses) */
    @Serializable
    data class AppInForeground(val packageName: String) : ProfileCondition()
    
    /** Activate when media is playing */
    @Serializable
    data class MediaPlaying(val appPackage: String? = null) : ProfileCondition()
    
    /** Activate when navigating */
    @Serializable
    data object NavigationActive : ProfileCondition()
    
    /** Activate based on time of day */
    @Serializable
    data class TimeRange(val startHour: Int, val endHour: Int) : ProfileCondition()
    
    /** Activate when connection state changes */
    @Serializable
    data class ConnectionState(val isConnected: Boolean) : ProfileCondition()
    
    /** Custom condition via expression */
    @Serializable
    data class Custom(val expression: String) : ProfileCondition()
}

// ============================================================================
// State Management
// ============================================================================

data class ControlSurfaceState(
    val activeProfile: ControlProfile? = null,
    val allProfiles: List<ControlProfile> = emptyList(),
    val isEditMode: Boolean = false,
    val selectedWidgetId: String? = null,
    val draggingWidgetId: String? = null,
    val clipboardWidget: ControlWidget? = null,
    val trackpadState: TrackpadState = TrackpadState(),
    val widgetStates: Map<String, WidgetState> = emptyMap(),
    val lastAction: WidgetAction? = null,
    val actionHistory: List<ExecutedAction> = emptyList()
)

data class TrackpadState(
    val isActive: Boolean = false,
    val lastX: Float = 0f,
    val lastY: Float = 0f,
    val velocity: Pair<Float, Float> = 0f to 0f,
    val gestureType: GestureType? = null
)

data class WidgetState(
    val widgetId: String,
    val isPressed: Boolean = false,
    val currentValue: Float? = null,
    val toggleState: Boolean? = null,
    val folderExpanded: Boolean = false
)

data class ExecutedAction(
    val action: WidgetAction,
    val widgetId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean = true,
    val result: String? = null
)

// ============================================================================
// Default Profiles
// ============================================================================

object DefaultProfiles {
    
    val dialogProfile = ControlProfile(
        id = "default-dialog",
        name = "Dialog Mode",
        description = "Quick response buttons for active dialogs",
        icon = "chat",
        gridColumns = 4,
        gridRows = 5,
        widgets = listOf(
            // Top row - status
            ControlWidget(
                id = "dialog-status",
                type = ControlWidgetType.STATUS_DISPLAY,
                gridX = 0, gridY = 0, spanX = 4, spanY = 1,
                config = WidgetConfig(label = "Active Dialog")
            ),
            // Option buttons 1-4
            ControlWidget(
                id = "opt-1", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 0, gridY = 1, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "1", accentColor = 0xFF00FF88),
                actions = listOf(WidgetAction.DialogResponse(optionIndex = 0))
            ),
            ControlWidget(
                id = "opt-2", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 2, gridY = 1, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "2", accentColor = 0xFF00FF88),
                actions = listOf(WidgetAction.DialogResponse(optionIndex = 1))
            ),
            ControlWidget(
                id = "opt-3", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 0, gridY = 2, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "3", accentColor = 0xFF00FF88),
                actions = listOf(WidgetAction.DialogResponse(optionIndex = 2))
            ),
            ControlWidget(
                id = "opt-4", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 2, gridY = 2, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "4", accentColor = 0xFF00FF88),
                actions = listOf(WidgetAction.DialogResponse(optionIndex = 3))
            ),
            // Keyboard toggle
            ControlWidget(
                id = "keyboard-toggle", type = ControlWidgetType.KEYBOARD,
                gridX = 0, gridY = 3, spanX = 4, spanY = 1,
                config = WidgetConfig(label = "Comment/Text Input", keyboardType = KeyboardType.FULL)
            ),
            // Submit and navigation
            ControlWidget(
                id = "submit", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 0, gridY = 4, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "Submit", accentColor = 0xFF00AAFF),
                actions = listOf(WidgetAction.HttpRequest(url = "/api/dialog/submit"))
            ),
            ControlWidget(
                id = "refresh", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 2, gridY = 4, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "Refresh", accentColor = 0xFFFFAA00),
                actions = listOf(WidgetAction.HttpRequest(url = "/api/dialog/refresh"))
            )
        ),
        autoActivateConditions = listOf(ProfileCondition.DialogActive()),
        priority = 100,
        isDefault = false
    )
    
    val mediaProfile = ControlProfile(
        id = "default-media",
        name = "Media Control",
        description = "Controls for music/video playback",
        icon = "play_circle",
        gridColumns = 4,
        gridRows = 5,
        widgets = listOf(
            // Now playing display
            ControlWidget(
                id = "now-playing",
                type = ControlWidgetType.STATUS_DISPLAY,
                gridX = 0, gridY = 0, spanX = 4, spanY = 1,
                config = WidgetConfig(label = "Now Playing")
            ),
            // Playback controls
            ControlWidget(
                id = "prev", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 0, gridY = 1, spanX = 1, spanY = 1,
                config = WidgetConfig(icon = "skip_previous"),
                actions = listOf(WidgetAction.KeyEvent(keyCode = 88)) // KEYCODE_MEDIA_PREVIOUS
            ),
            ControlWidget(
                id = "play-pause", type = ControlWidgetType.TOGGLE,
                gridX = 1, gridY = 1, spanX = 2, spanY = 1,
                config = WidgetConfig(icon = "play_pause"),
                actions = listOf(WidgetAction.KeyEvent(keyCode = 85)) // KEYCODE_MEDIA_PLAY_PAUSE
            ),
            ControlWidget(
                id = "next", type = ControlWidgetType.ACTION_BUTTON,
                gridX = 3, gridY = 1, spanX = 1, spanY = 1,
                config = WidgetConfig(icon = "skip_next"),
                actions = listOf(WidgetAction.KeyEvent(keyCode = 87)) // KEYCODE_MEDIA_NEXT
            ),
            // Volume
            ControlWidget(
                id = "volume", type = ControlWidgetType.SLIDER,
                gridX = 0, gridY = 2, spanX = 4, spanY = 1,
                config = WidgetConfig(label = "Volume", sliderMin = 0f, sliderMax = 100f)
            ),
            // Seek trackpad
            ControlWidget(
                id = "seek-pad", type = ControlWidgetType.TRACKPAD,
                gridX = 0, gridY = 3, spanX = 4, spanY = 2,
                config = WidgetConfig(label = "Seek / Scroll", trackpadSensitivity = 0.5f)
            )
        ),
        autoActivateConditions = listOf(ProfileCondition.MediaPlaying()),
        priority = 80
    )
    
    val generalProfile = ControlProfile(
        id = "default-general",
        name = "General",
        description = "Default control surface",
        icon = "dashboard",
        gridColumns = 4,
        gridRows = 6,
        widgets = listOf(
            // Connection status
            ControlWidget(
                id = "conn-status",
                type = ControlWidgetType.STATUS_DISPLAY,
                gridX = 0, gridY = 0, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "Status")
            ),
            ControlWidget(
                id = "layout-toggle",
                type = ControlWidgetType.ACTION_BUTTON,
                gridX = 2, gridY = 0, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "Layout", icon = "view_module"),
                actions = listOf(WidgetAction.SwitchProfile("cycle"))
            ),
            // Main trackpad
            ControlWidget(
                id = "main-trackpad",
                type = ControlWidgetType.TRACKPAD,
                gridX = 0, gridY = 1, spanX = 4, spanY = 3,
                config = WidgetConfig(label = "Trackpad", trackpadSensitivity = 1.0f)
            ),
            // D-pad
            ControlWidget(
                id = "dpad",
                type = ControlWidgetType.DPAD,
                gridX = 0, gridY = 4, spanX = 2, spanY = 2,
                config = WidgetConfig(dpadMode = DPadMode.FOUR_WAY)
            ),
            // Quick actions
            ControlWidget(
                id = "action-1",
                type = ControlWidgetType.ACTION_BUTTON,
                gridX = 2, gridY = 4, spanX = 1, spanY = 1,
                config = WidgetConfig(label = "A", accentColor = 0xFF00FF88)
            ),
            ControlWidget(
                id = "action-2",
                type = ControlWidgetType.ACTION_BUTTON,
                gridX = 3, gridY = 4, spanX = 1, spanY = 1,
                config = WidgetConfig(label = "B", accentColor = 0xFFFF6600)
            ),
            ControlWidget(
                id = "keyboard",
                type = ControlWidgetType.KEYBOARD,
                gridX = 2, gridY = 5, spanX = 2, spanY = 1,
                config = WidgetConfig(label = "Keyboard")
            )
        ),
        isDefault = true,
        priority = 0
    )
    
    val allDefaults = listOf(generalProfile, dialogProfile, mediaProfile)
}
