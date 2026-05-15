package com.example.continuumstudio.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Display mode categories for adaptive UI
 */
enum class DisplayMode {
    /** Normal phone display (portrait or landscape) */
    PHONE,
    /** Tablet or large screen device */
    TABLET,
    /** Samsung DeX mode - desktop-like experience */
    DEX,
    /** External display (glasses, monitor, TV) via USB-C/wireless */
    EXTERNAL_DISPLAY,
    /** Foldable in expanded mode */
    FOLDABLE_EXPANDED
}

/**
 * Display characteristics for UI adaptation
 */
data class DisplayInfo(
    val mode: DisplayMode,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val isPortrait: Boolean,
    val isMultiWindow: Boolean,
    val hasExternalDisplay: Boolean,
    val externalDisplayCount: Int,
    val screenCategory: ScreenCategory
)

enum class ScreenCategory {
    COMPACT,   // < 600dp width
    MEDIUM,    // 600-840dp width
    EXPANDED   // > 840dp width
}

/**
 * Helper for detecting display modes and adapting UI
 */
object DisplayModeHelper {
    
    /**
     * Detect current display mode
     */
    fun getDisplayMode(context: Context): DisplayMode {
        // Check for Samsung DeX mode
        if (isDeXMode(context)) {
            return DisplayMode.DEX
        }
        
        // Check for external display
        if (hasExternalDisplay(context)) {
            return DisplayMode.EXTERNAL_DISPLAY
        }
        
        // Check screen size for tablet
        val config = context.resources.configuration
        val screenWidthDp = config.screenWidthDp
        
        return when {
            screenWidthDp >= 840 -> DisplayMode.TABLET
            screenWidthDp >= 600 -> DisplayMode.TABLET
            else -> DisplayMode.PHONE
        }
    }
    
    /**
     * Check if Samsung DeX mode is active
     */
    fun isDeXMode(context: Context): Boolean {
        val config = context.resources.configuration
        
        // Method 1: Check Samsung DEX_MODE flag
        try {
            val dexModeField = Configuration::class.java.getField("SEM_DESKTOP_MODE_ENABLED")
            val semDesktopModeEnabled = dexModeField.getInt(null)
            if ((config.semDesktopModeEnabled and semDesktopModeEnabled) != 0) {
                return true
            }
        } catch (e: Exception) {
            // Not a Samsung device or field not available
        }
        
        // Method 2: Check configuration flags (works on many Samsung devices)
        try {
            val method = config.javaClass.getMethod("semDesktopModeEnabled")
            val result = method.invoke(config) as? Int
            if (result != null && result != 0) {
                return true
            }
        } catch (e: Exception) {
            // Method not available
        }
        
        // Method 3: Heuristic - check for desktop-like characteristics
        // External display + keyboard mode often indicates DeX
        if (hasExternalDisplay(context) && config.keyboard == Configuration.KEYBOARD_QWERTY) {
            return true
        }
        
        return false
    }
    
    /**
     * Check if any external display is connected (glasses, monitor, TV)
     */
    fun hasExternalDisplay(context: Context): Boolean {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        
        // More than one display means external is connected
        return displays.size > 1
    }
    
    /**
     * Get number of external displays
     */
    fun getExternalDisplayCount(context: Context): Int {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return displayManager.displays.size - 1 // Subtract built-in display
    }
    
    /**
     * Get comprehensive display info
     */
    fun getDisplayInfo(context: Context): DisplayInfo {
        val config = context.resources.configuration
        val displayMetrics = context.resources.displayMetrics
        
        val widthDp = config.screenWidthDp
        val heightDp = config.screenHeightDp
        
        val screenCategory = when {
            widthDp >= 840 -> ScreenCategory.EXPANDED
            widthDp >= 600 -> ScreenCategory.MEDIUM
            else -> ScreenCategory.COMPACT
        }
        
        val isMultiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && context is Activity) {
            context.isInMultiWindowMode
        } else {
            false
        }
        
        return DisplayInfo(
            mode = getDisplayMode(context),
            widthDp = widthDp,
            heightDp = heightDp,
            density = displayMetrics.density,
            isPortrait = config.orientation == Configuration.ORIENTATION_PORTRAIT,
            isMultiWindow = isMultiWindow,
            hasExternalDisplay = hasExternalDisplay(context),
            externalDisplayCount = getExternalDisplayCount(context),
            screenCategory = screenCategory
        )
    }
    
    /**
     * Flow that emits display changes (useful for reactive UI)
     */
    fun displayChangesFlow(context: Context): Flow<DisplayInfo> = callbackFlow {
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        
        // Initial emission
        trySend(getDisplayInfo(context))
        
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                trySend(getDisplayInfo(context))
            }
            
            override fun onDisplayRemoved(displayId: Int) {
                trySend(getDisplayInfo(context))
            }
            
            override fun onDisplayChanged(displayId: Int) {
                trySend(getDisplayInfo(context))
            }
        }
        
        displayManager.registerDisplayListener(listener, null)
        
        awaitClose {
            displayManager.unregisterDisplayListener(listener)
        }
    }
}

/**
 * Composable to observe display info reactively
 */
@Composable
fun rememberDisplayInfo(): State<DisplayInfo> {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    
    return produceState(
        initialValue = DisplayModeHelper.getDisplayInfo(context),
        configuration // Re-compute on configuration change
    ) {
        value = DisplayModeHelper.getDisplayInfo(context)
    }
}

/**
 * Extension for Configuration to safely access Samsung's semDesktopModeEnabled
 */
private val Configuration.semDesktopModeEnabled: Int
    get() = try {
        val field = Configuration::class.java.getField("semDesktopModeEnabled")
        field.getInt(this)
    } catch (e: Exception) {
        0
    }
