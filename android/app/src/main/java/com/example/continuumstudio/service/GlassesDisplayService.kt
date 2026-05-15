package com.example.continuumstudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.example.continuumstudio.MainActivity
import com.example.continuumstudio.R
import com.example.continuumstudio.data.GlassesTheme
import com.example.continuumstudio.ui.glasses.GlassesDialogState
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.ui.glasses.GlassesPresentation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.osmdroid.util.GeoPoint

/**
 * Foreground Service for persistent glasses display management.
 * 
 * This service keeps the GlassesPresentation alive even when the main
 * Activity is backgrounded, allowing seamless XR experience when switching
 * between apps.
 * 
 * ## Display Mode Mapping (Samsung S23 Ultra)
 * 
 * | Frontend Mode | Detection API | Display Behavior |
 * |--------------|---------------|------------------|
 * | Mirror | No FLAG_PRESENTATION | Phone duplicated to glasses |
 * | Extended | DISPLAY_CATEGORY_PRESENTATION | Presentation controls glasses |
 * | Samsung DeX | SEM_DESKTOP_MODE_ENABLED | Desktop on external, phone separate |
 */
class GlassesDisplayService : Service(), DisplayManager.DisplayListener {
    
    companion object {
        private const val TAG = "GlassesDisplayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "glasses_display_channel"
        
        // Actions
        const val ACTION_START = "com.example.continuumstudio.GLASSES_START"
        const val ACTION_STOP = "com.example.continuumstudio.GLASSES_STOP"
        const val ACTION_UPDATE_THEME = "com.example.continuumstudio.GLASSES_UPDATE_THEME"
        
        /**
         * Start the glasses display service
         */
        fun start(context: Context) {
            val intent = Intent(context, GlassesDisplayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Stop the glasses display service
         */
        fun stop(context: Context) {
            val intent = Intent(context, GlassesDisplayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
    
    // Binder for local binding
    private val binder = LocalBinder()
    
    inner class LocalBinder : Binder() {
        fun getService(): GlassesDisplayService = this@GlassesDisplayService
    }
    
    // Display management
    private lateinit var displayManager: DisplayManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var glassesPresentation: GlassesPresentation? = null
    private var currentTheme: GlassesTheme = GlassesTheme()
    
    // State flows for UI observation
    private val _isGlassesConnected = MutableStateFlow(false)
    val isGlassesConnected: StateFlow<Boolean> = _isGlassesConnected.asStateFlow()
    
    private val _glassesDisplayState = MutableStateFlow(GlassesDisplayState())
    val glassesDisplayState: StateFlow<GlassesDisplayState> = _glassesDisplayState.asStateFlow()
    
    private val _displayMode = MutableStateFlow(DisplayMode.UNKNOWN)
    val displayMode: StateFlow<DisplayMode> = _displayMode.asStateFlow()
    
    /**
     * Display modes detectable from Android APIs
     */
    enum class DisplayMode {
        UNKNOWN,        // Not yet determined
        PHONE_ONLY,     // No external display
        MIRROR,         // External display, but duplicating phone (no FLAG_PRESENTATION)
        EXTENDED,       // Presentation display available (can show independent content)
        SAMSUNG_DEX     // Samsung DeX desktop mode active
    }
    
    data class GlassesDisplayState(
        val displayId: Int? = null,
        val displayName: String? = null,
        val width: Int = 0,
        val height: Int = 0,
        val refreshRate: Float = 0f,
        val connectedDisplayName: String? = null,
        val hasPresentationFlag: Boolean = false,
        val isDeXMode: Boolean = false
    )
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startDisplayListening()
            }
            ACTION_STOP -> {
                stopDisplayListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE_THEME -> {
                // Theme updates are now handled via updateTheme() method when bound
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        stopDisplayListening()
        super.onDestroy()
    }
    
    // ============================================================================
    // Display Listener Implementation
    // ============================================================================
    
    override fun onDisplayAdded(displayId: Int) {
        Log.d(TAG, "Display added: $displayId")
        checkForExternalDisplays()
        updateNotification()
    }
    
    override fun onDisplayRemoved(displayId: Int) {
        Log.d(TAG, "Display removed: $displayId")
        
        glassesPresentation?.let { presentation ->
            if (presentation.display.displayId == displayId) {
                dismissPresentation()
            }
        }
        
        checkForExternalDisplays()
        updateNotification()
    }
    
    override fun onDisplayChanged(displayId: Int) {
        Log.d(TAG, "Display changed: $displayId")
        detectDisplayMode()
    }
    
    // ============================================================================
    // Display Management
    // ============================================================================
    
    private fun startDisplayListening() {
        displayManager.registerDisplayListener(this, mainHandler)
        checkForExternalDisplays()
    }
    
    private fun stopDisplayListening() {
        displayManager.unregisterDisplayListener(this)
        dismissPresentation()
    }
    
    private fun checkForExternalDisplays() {
        val displays = displayManager.displays
        val externalDisplays = displays.filter { 
            it.displayId != Display.DEFAULT_DISPLAY && it.isValid 
        }
        
        Log.d(TAG, "Found ${externalDisplays.size} external display(s)")
        
        val presentationDisplay = findBestPresentationDisplay(externalDisplays)
        
        if (presentationDisplay != null) {
            if (glassesPresentation?.display?.displayId != presentationDisplay.displayId) {
                showPresentation(presentationDisplay)
            }
        } else {
            if (glassesPresentation != null) {
                dismissPresentation()
            }
        }
        
        _isGlassesConnected.value = glassesPresentation != null
        detectDisplayMode()
    }
    
    private fun findBestPresentationDisplay(displays: List<Display>): Display? {
        // Priority 1: DISPLAY_CATEGORY_PRESENTATION
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        presentationDisplays.firstOrNull { it.isValid }?.let { return it }
        
        // Priority 2: FLAG_PRESENTATION
        displays.firstOrNull { 
            it.flags and Display.FLAG_PRESENTATION != 0 && it.isValid 
        }?.let { return it }
        
        // Priority 3: Any valid external display
        return displays.firstOrNull { it.isValid }
    }
    
    private fun showPresentation(display: Display) {
        Log.d(TAG, "Showing presentation on display ${display.displayId}: ${display.name}")
        
        dismissPresentation()
        
        try {
            glassesPresentation = GlassesPresentation(this, display).apply {
                updateTheme(currentTheme)
                show()
            }
            
            val hasPresentationFlag = display.flags and Display.FLAG_PRESENTATION != 0
            _glassesDisplayState.value = GlassesDisplayState(
                displayId = display.displayId,
                displayName = display.name,
                width = display.mode.physicalWidth,
                height = display.mode.physicalHeight,
                refreshRate = display.mode.refreshRate,
                connectedDisplayName = display.name,
                hasPresentationFlag = hasPresentationFlag,
                isDeXMode = isDeXMode()
            )
            
            Log.d(TAG, "Presentation shown successfully. FLAG_PRESENTATION=$hasPresentationFlag")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show presentation", e)
        }
    }
    
    private fun dismissPresentation() {
        glassesPresentation?.let { presentation ->
            try {
                presentation.dismiss()
            } catch (e: Exception) {
                Log.e(TAG, "Error dismissing presentation", e)
            }
        }
        glassesPresentation = null
        _glassesDisplayState.value = GlassesDisplayState()
    }
    
    /**
     * Detect the current display mode based on available APIs
     */
    private fun detectDisplayMode() {
        val displays = displayManager.displays
        val hasExternal = displays.any { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
        
        if (!hasExternal) {
            _displayMode.value = DisplayMode.PHONE_ONLY
            return
        }
        
        // Check for Samsung DeX
        if (isDeXMode()) {
            _displayMode.value = DisplayMode.SAMSUNG_DEX
            return
        }
        
        // Check for presentation-capable display
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        val hasPresentationDisplay = presentationDisplays.isNotEmpty() || 
            displays.any { it.flags and Display.FLAG_PRESENTATION != 0 }
        
        _displayMode.value = if (hasPresentationDisplay) {
            DisplayMode.EXTENDED
        } else {
            DisplayMode.MIRROR
        }
        
        Log.d(TAG, "Display mode detected: ${_displayMode.value}")
    }
    
    /**
     * Check if Samsung DeX mode is active (uses reflection for Samsung-specific APIs)
     */
    private fun isDeXMode(): Boolean {
        val config = resources.configuration
        
        // Method 1: Check SEM_DESKTOP_MODE_ENABLED field
        try {
            val dexModeField = android.content.res.Configuration::class.java.getField("SEM_DESKTOP_MODE_ENABLED")
            val semDesktopModeEnabled = dexModeField.getInt(null)
            
            val semDesktopModeEnabledField = config.javaClass.getField("semDesktopModeEnabled")
            val currentValue = semDesktopModeEnabledField.getInt(config)
            
            if ((currentValue and semDesktopModeEnabled) != 0) {
                return true
            }
        } catch (e: Exception) {
            Log.v(TAG, "SEM_DESKTOP_MODE_ENABLED not available: ${e.message}")
        }
        
        // Method 2: Check semDesktopModeEnabled method
        try {
            val method = config.javaClass.getMethod("semDesktopModeEnabled")
            val result = method.invoke(config) as? Int
            if (result != null && result != 0) {
                return true
            }
        } catch (e: Exception) {
            Log.v(TAG, "semDesktopModeEnabled method not available: ${e.message}")
        }
        
        return false
    }
    
    // ============================================================================
    // Public API for updating glasses content
    // ============================================================================
    
    /**
     * Update the dialog shown on glasses
     */
    fun updateDialog(state: GlassesDialogState?) {
        glassesPresentation?.updateDialog(state)
    }
    
    /**
     * Update navigation state shown on glasses
     */
    fun updateNavigation(state: NavigationState) {
        glassesPresentation?.updateNavigation(state)
    }
    
    /**
     * Update OSM map overlay on glasses
     */
    fun updateOsmMap(location: GeoPoint?, destination: GeoPoint? = null, routeGeometry: List<GeoPoint> = emptyList()) {
        glassesPresentation?.updateOsmMap(location, destination, routeGeometry)
    }
    
    /**
     * Update theme on glasses
     */
    fun updateTheme(theme: GlassesTheme) {
        currentTheme = theme
        glassesPresentation?.updateTheme(theme)
    }
    
    /**
     * Scroll glasses content
     */
    fun scrollGlassesContent(direction: Int) {
        glassesPresentation?.scrollContent(direction)
    }
    
    // ============================================================================
    // Notification Management
    // ============================================================================
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "XR Glasses Display",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps glasses display active in background"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val contentText = when (_displayMode.value) {
            DisplayMode.EXTENDED -> "Extended display active"
            DisplayMode.SAMSUNG_DEX -> "Samsung DeX mode"
            DisplayMode.MIRROR -> "Mirror mode (tap to configure)"
            DisplayMode.PHONE_ONLY -> "Waiting for glasses..."
            DisplayMode.UNKNOWN -> "Checking display..."
        }
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Continuum Studio")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
}
