package com.example.continuumstudio.data

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * XR Display Models - Extensions for glasses/dual-display support
 * 
 * This module extends the base widget system to support:
 * - Independent rendering on phone vs glasses displays
 * - Transparency-optimized themes for see-through glasses
 * - Context-aware automatic layout switching
 * - MediaProjection-based PnP capture
 */

// ============================================================================
// Display Target Configuration
// ============================================================================

/**
 * Where a widget should be displayed
 */
@Serializable
enum class DisplayTarget {
    /** Only visible on phone display */
    PHONE_ONLY,
    /** Only visible on glasses/external display */
    GLASSES_ONLY,
    /** Mirrored to both displays (same content) */
    BOTH,
    /** Phone is primary, glasses shows simplified version */
    PHONE_PRIMARY_GLASSES_MINIMAL,
    /** Glasses is primary, phone shows controls only */
    GLASSES_PRIMARY_PHONE_CONTROLS,
    /** Hidden on all displays */
    NONE
}

/**
 * Extended widget configuration for XR
 */
@Serializable
data class XRWidgetConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val span: Int = 1,
    val order: Int = 0,
    val settings: Map<String, String> = emptyMap(),
    // XR Extensions
    val displayTarget: DisplayTarget = DisplayTarget.BOTH,
    val phoneTheme: WidgetThemeMode = WidgetThemeMode.FULL,
    val glassesTheme: WidgetThemeMode = WidgetThemeMode.TRANSPARENT,
    val priority: Int = 0,
    val contextRules: List<String> = emptyList()
)

// ============================================================================
// Theme System
// ============================================================================

/**
 * Visual theme mode for widgets
 */
@Serializable
enum class WidgetThemeMode {
    /** Maximum see-through: monochrome green, minimal fills */
    TRANSPARENT,
    /** Reduced colors: dark backgrounds, white text, accent only for alerts */
    MINIMAL,
    /** Full Material You theme */
    FULL
}

/**
 * Color palette optimized for additive displays (AR glasses)
 */
@Serializable
enum class GlassesColorPalette {
    MONOCHROME_GREEN,
    MONOCHROME_CYAN,
    MONOCHROME_AMBER,
    HIGH_CONTRAST,
    MUTED
}

/**
 * Complete theme configuration for glasses display
 */
@Serializable
data class GlassesTheme(
    val mode: WidgetThemeMode = WidgetThemeMode.TRANSPARENT,
    val palette: GlassesColorPalette = GlassesColorPalette.MONOCHROME_GREEN,
    val opacity: Float = 1.0f,
    val useOutlines: Boolean = true,
    val maxBrightness: Float = 0.8f,
    val fontScale: Float = 1.2f,
    val reduceAnimations: Boolean = false
) {
    // Backwards compatibility aliases
    val colorPalette: GlassesColorPalette get() = palette
    val useOutlinesOnly: Boolean get() = useOutlines
    val textScale: Float get() = fontScale
}

/**
 * Color tokens for glasses-optimized rendering
 */
object GlassesColors {
    val GreenPrimary = Color(0xFF00FF00)
    val GreenDim = Color(0xFF00AA00)
    val GreenBright = Color(0xFF44FF44)
    val CyanPrimary = Color(0xFF00FFFF)
    val CyanDim = Color(0xFF00AAAA)
    val AmberPrimary = Color(0xFFFFAA00)
    val AmberDim = Color(0xFFAA7700)
    val White = Color(0xFFFFFFFF)
    val OffWhite = Color(0xFFE0E0E0)
    val AlertRed = Color(0xFFFF4444)
    val AlertYellow = Color(0xFFFFFF00)
    val Transparent = Color(0xFF000000) // Opaque black = transparent on AR glasses
    val SemiTransparent = Color(0x44000000)
    
    fun primaryFor(palette: GlassesColorPalette): Color = when (palette) {
        GlassesColorPalette.MONOCHROME_GREEN -> GreenPrimary
        GlassesColorPalette.MONOCHROME_CYAN -> CyanPrimary
        GlassesColorPalette.MONOCHROME_AMBER -> AmberPrimary
        GlassesColorPalette.HIGH_CONTRAST -> White
        GlassesColorPalette.MUTED -> OffWhite
    }
    
    fun dimFor(palette: GlassesColorPalette): Color = when (palette) {
        GlassesColorPalette.MONOCHROME_GREEN -> GreenDim
        GlassesColorPalette.MONOCHROME_CYAN -> CyanDim
        GlassesColorPalette.MONOCHROME_AMBER -> AmberDim
        GlassesColorPalette.HIGH_CONTRAST -> OffWhite
        GlassesColorPalette.MUTED -> Color(0xFFAAAAAA)
    }
}

// ============================================================================
// Context-Aware Rules
// ============================================================================

@Serializable
enum class ContextType {
    DRIVING, WALKING, STATIONARY,
    GLASSES_CONNECTED, GLASSES_DISCONNECTED,
    NIGHT_MODE, DAY_MODE, LOW_BATTERY, MANUAL
}

@Serializable
enum class ContextAction {
    SHOW, HIDE, MOVE_TO_GLASSES, MOVE_TO_PHONE,
    SET_TRANSPARENT_THEME, SET_MINIMAL_THEME, SET_FULL_THEME
}

@Serializable
data class ContextRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trigger: ContextType,
    val action: ContextAction,
    val targetWidgetIds: List<String> = emptyList(),
    val priority: Int = 0,
    val enabled: Boolean = true
)

// ============================================================================
// PnP Capture Configuration
// ============================================================================

@Serializable
data class PnPCaptureConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val targetPackage: String? = null,
    val displayTarget: DisplayTarget = DisplayTarget.GLASSES_ONLY,
    val position: PnPPosition = PnPPosition.FLOATING,
    val size: PnPSize = PnPSize.SMALL,
    val opacity: Float = 0.9f,
    val audioCapture: Boolean = false,
    val enabled: Boolean = true
)

@Serializable
enum class PnPPosition {
    FLOATING, DOCKED_LEFT, DOCKED_RIGHT, DOCKED_TOP, DOCKED_BOTTOM, STRIP_TOP, STRIP_BOTTOM
}

@Serializable
enum class PnPSize { SMALL, MEDIUM, LARGE, ADAPTIVE }

object PnPPresets {
    val YouTubePnP = PnPCaptureConfig(
        name = "YouTube",
        targetPackage = "com.google.android.youtube",
        displayTarget = DisplayTarget.GLASSES_ONLY,
        position = PnPPosition.FLOATING,
        size = PnPSize.MEDIUM,
        audioCapture = true
    )
    
    val GoogleMapsPnP = PnPCaptureConfig(
        name = "Maps Navigation",
        targetPackage = "com.google.android.apps.maps",
        displayTarget = DisplayTarget.GLASSES_ONLY,
        position = PnPPosition.STRIP_TOP,
        size = PnPSize.ADAPTIVE,
        audioCapture = false
    )
}

// ============================================================================
// XR Widget Types
// ============================================================================

@Serializable
enum class XRWidgetType(val title: String, val description: String, val emoji: String) {
    PNP_CAPTURE("PnP Capture", "Capture and relay another app", "📺"),
    AUDIO_CONTROL("Audio Control", "TTS and music controls", "🔊"),
    MAPS_MINIMAL("Maps Minimal", "Stripped-down navigation view", "🗺️"),
    VOICE_COMMAND("Voice Command", "Voice activation status", "🎤"),
    TIMER("Timer", "Countdown or stopwatch", "⏱️"),
    QUICK_NOTE("Quick Note", "Custom persistent text", "📝")
}

// ============================================================================
// Extended Bay Configuration
// ============================================================================

@Serializable
data class XRBayConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default XR Layout",
    val phoneColumns: Int = 2,
    val phoneWidgets: List<XRWidgetConfig> = emptyList(),
    val glassesColumns: Int = 1,
    val glassesWidgets: List<XRWidgetConfig> = emptyList(),
    val glassesTheme: GlassesTheme = GlassesTheme(),
    val contextRules: List<ContextRule> = emptyList(),
    val pnpConfigs: List<PnPCaptureConfig> = emptyList()
)

object XRLayoutPresets {
    val DrivingMode = XRBayConfig(
        name = "Driving",
        phoneColumns = 2,
        phoneWidgets = listOf(
            XRWidgetConfig(
                type = WidgetType.QUICK_ACTIONS,
                displayTarget = DisplayTarget.PHONE_ONLY,
                phoneTheme = WidgetThemeMode.FULL
            )
        ),
        glassesColumns = 1,
        glassesWidgets = listOf(
            XRWidgetConfig(
                type = WidgetType.DIALOG_BADGE,
                displayTarget = DisplayTarget.GLASSES_ONLY,
                glassesTheme = WidgetThemeMode.TRANSPARENT
            )
        ),
        glassesTheme = GlassesTheme(
            mode = WidgetThemeMode.TRANSPARENT,
            palette = GlassesColorPalette.MONOCHROME_GREEN,
            useOutlines = true
        )
    )
    
    val DeskMode = XRBayConfig(
        name = "Desk Work",
        phoneColumns = 2,
        phoneWidgets = listOf(
            XRWidgetConfig(type = WidgetType.SERVER_STATUS),
            XRWidgetConfig(type = WidgetType.RUNNING_AGENTS),
            XRWidgetConfig(type = WidgetType.QUICK_ACTIONS)
        ),
        glassesColumns = 2,
        glassesWidgets = listOf(
            XRWidgetConfig(type = WidgetType.DIALOG_BADGE, span = 2, glassesTheme = WidgetThemeMode.MINIMAL),
            XRWidgetConfig(type = WidgetType.ACTIVITY_PREVIEW, span = 2, glassesTheme = WidgetThemeMode.MINIMAL)
        ),
        glassesTheme = GlassesTheme(mode = WidgetThemeMode.MINIMAL, palette = GlassesColorPalette.HIGH_CONTRAST)
    )
}
