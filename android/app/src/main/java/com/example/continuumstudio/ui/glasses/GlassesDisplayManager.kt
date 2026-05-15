package com.example.continuumstudio.ui.glasses

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.continuumstudio.data.GlassesTheme
import com.example.continuumstudio.service.NowPlayingState
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.youtube.YouTubeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GlassesDisplayManager"

/**
 * Manages the lifecycle of the glasses Presentation.
 * 
 * Features:
 * - Automatic detection of external displays (glasses)
 * - Lifecycle-aware: creates/destroys Presentation with Activity
 * - Handles display connect/disconnect events
 * - Provides state flow for display status
 */
class GlassesDisplayManager(
    private val context: Context
) : DisplayManager.DisplayListener {
    
    private val displayManager: DisplayManager = 
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Current presentation (if any)
    private var glassesPresentation: GlassesPresentation? = null
    
    // State flows
    private val _displayState = MutableStateFlow(GlassesDisplayState())
    val displayState: StateFlow<GlassesDisplayState> = _displayState.asStateFlow()
    
    private val _isGlassesConnected = MutableStateFlow(false)
    val isGlassesConnected: StateFlow<Boolean> = _isGlassesConnected.asStateFlow()
    
    // Theme to apply
    private var currentTheme: GlassesTheme = GlassesTheme()
    
    /**
     * Start listening for display changes.
     * Call this in Activity.onStart()
     */
    fun startListening(initialTheme: GlassesTheme? = null) {
        initialTheme?.let { currentTheme = it }
        displayManager.registerDisplayListener(this, mainHandler)
        checkForExternalDisplays()
    }
    
    /**
     * Stop listening for display changes.
     * Call this in Activity.onStop()
     */
    fun stopListening() {
        displayManager.unregisterDisplayListener(this)
        dismissPresentation()
    }
    
    /**
     * Create a lifecycle observer for automatic management
     */
    /**
     * Create a lifecycle observer for automatic management
     * @param themeProvider Function to get current theme when starting
     */
    fun createLifecycleObserver(themeProvider: (() -> GlassesTheme)? = null): DefaultLifecycleObserver {
        return object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startListening(themeProvider?.invoke())
            }
            
            override fun onStop(owner: LifecycleOwner) {
                stopListening()
            }
        }
    }
    
    // ============================================================================
    // Display Listener Callbacks
    // ============================================================================
    
    override fun onDisplayAdded(displayId: Int) {
        Log.d(TAG, "Display added: $displayId")
        checkForExternalDisplays()
    }
    
    override fun onDisplayRemoved(displayId: Int) {
        Log.d(TAG, "Display removed: $displayId")
        
        // If our glasses display was removed, dismiss the presentation
        glassesPresentation?.let { presentation ->
            if (presentation.display.displayId == displayId) {
                dismissPresentation()
            }
        }
        
        // Re-check for other external displays
        checkForExternalDisplays()
    }
    
    override fun onDisplayChanged(displayId: Int) {
        Log.d(TAG, "Display changed: $displayId")
        // Handle display changes if needed (e.g., resolution change)
    }
    
    // ============================================================================
    // Display Management
    // ============================================================================
    
    /**
     * Check for external displays and create presentation if found
     */
    private fun checkForExternalDisplays() {
        val displays = displayManager.displays
        val externalDisplays = displays.filter { 
            it.displayId != Display.DEFAULT_DISPLAY && it.isValid 
        }
        
        val presentationDisplay = findBestPresentationDisplay(externalDisplays)
        
        _displayState.value = GlassesDisplayState(
            hasExternalDisplay = externalDisplays.isNotEmpty(),
            externalDisplayCount = externalDisplays.size,
            connectedDisplayName = presentationDisplay?.name,
            displayWidth = presentationDisplay?.mode?.physicalWidth ?: 0,
            displayHeight = presentationDisplay?.mode?.physicalHeight ?: 0
        )
        
        _isGlassesConnected.value = externalDisplays.isNotEmpty()
        
        // Create or update presentation
        if (presentationDisplay != null && glassesPresentation?.display?.displayId != presentationDisplay.displayId) {
            // New display - create new presentation
            dismissPresentation()
            createPresentation(presentationDisplay)
        } else if (presentationDisplay == null && glassesPresentation != null) {
            // No display - dismiss presentation
            dismissPresentation()
        }
    }
    
    /**
     * Find the best display for presentation.
     * Prefers displays flagged as PRESENTATION, falls back to any external.
     */
    private fun findBestPresentationDisplay(displays: List<Display>): Display? {
        // First, try to find a display categorized as presentation
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        presentationDisplays.firstOrNull { it.isValid }?.let { return it }
        
        // Fall back to any external display with PRESENTATION flag
        displays.firstOrNull { 
            it.flags and Display.FLAG_PRESENTATION != 0 && it.isValid 
        }?.let { return it }
        
        // Last resort: any valid external display
        return displays.firstOrNull { it.isValid }
    }
    
    /**
     * Create a new presentation on the specified display
     */
    private fun createPresentation(display: Display) {
        try {
            Log.d(TAG, "Creating presentation on display: ${display.name} (${display.displayId})")
            
            glassesPresentation = GlassesPresentation(context, display).apply {
                updateTheme(currentTheme)
                show()
            }
            
            Log.d(TAG, "Presentation created and shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create presentation", e)
        }
    }
    
    /**
     * Dismiss the current presentation
     */
    private fun dismissPresentation() {
        glassesPresentation?.let { presentation ->
            try {
                if (presentation.isShowing) {
                    presentation.dismiss()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing presentation", e)
            }
        }
        glassesPresentation = null
    }
    
    // ============================================================================
    // Public API for updating glasses display
    // ============================================================================
    
    /**
     * Update dialog state on glasses
     */
    fun updateDialog(state: GlassesDialogState?) {
        glassesPresentation?.updateDialog(state)
    }
    
    /**
     * Update activity events on glasses
     */
    fun updateActivity(events: List<GlassesActivityItem>) {
        glassesPresentation?.updateActivity(events)
    }
    
    /**
     * Update connection status on glasses
     */
    fun updateConnectionStatus(status: GlassesConnectionStatus) {
        glassesPresentation?.updateConnectionStatus(status)
    }
    
    /**
     * Update theme for glasses display
     */
    fun updateTheme(theme: GlassesTheme) {
        currentTheme = theme
        glassesPresentation?.updateTheme(theme)
    }
    
    /**
     * Update layout mode
     */
    fun updateLayout(mode: GlassesLayoutMode) {
        glassesPresentation?.updateLayout(mode)
    }
    
    /**
     * Update now playing state
     */
    fun updateNowPlaying(state: NowPlayingState) {
        glassesPresentation?.updateNowPlaying(state)
    }
    
    /**
     * Update navigation state
     */
    fun updateNavigation(state: NavigationState) {
        glassesPresentation?.updateNavigation(state)
    }
    
    fun updateYoutube(state: YouTubeState) {
        glassesPresentation?.updateYoutube(state)
    }
    
    /**
     * Update control surface state on glasses
     */
    fun updateControlSurface(state: GlassesControlSurfaceState) {
        glassesPresentation?.updateControlSurface(state)
    }
    
    /**
     * Show brief action feedback on glasses
     */
    fun showActionFeedback(description: String, source: String) {
        glassesPresentation?.showActionFeedback(description, source)
    }
    
    /**
     * Update dialog option selection (for glasses to highlight selected option)
     */
    fun updateDialogSelection(index: Int?) {
        glassesPresentation?.updateDialogSelection(index)
    }
    
    /**
     * Update typing text (for glasses to show what user is typing)
     */
    fun updateTypingText(text: String?) {
        glassesPresentation?.updateTypingText(text)
    }
    
    /**
     * Scroll content on glasses display (for dialog content)
     */
    fun scrollContent(scrollAmount: Int) {
        glassesPresentation?.scrollContent(scrollAmount)
    }
    
    /**
     * Check if presentation is currently showing
     */
    val isShowingPresentation: Boolean
        get() = glassesPresentation?.isShowing == true
}

/**
 * State holder for glasses display information
 */
data class GlassesDisplayState(
    val hasExternalDisplay: Boolean = false,
    val externalDisplayCount: Int = 0,
    val connectedDisplayName: String? = null,
    val displayWidth: Int = 0,
    val displayHeight: Int = 0
)

/**
 * Control surface state for glasses display
 */
data class GlassesControlSurfaceState(
    val profileName: String? = null,
    val profileIcon: String? = null,
    val widgetCount: Int = 0,
    val lastAction: String? = null,
    val isEditMode: Boolean = false,
    val timestamp: Long = 0
)
