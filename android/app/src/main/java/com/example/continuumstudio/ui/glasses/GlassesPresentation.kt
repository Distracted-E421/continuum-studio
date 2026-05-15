package com.example.continuumstudio.ui.glasses

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.*
import com.example.continuumstudio.service.NowPlayingState
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.youtube.YouTubeState
import org.osmdroid.util.GeoPoint

/**
 * Android Presentation for rendering UI on glasses/external display.
 * 
 * Key design principles for AR glasses:
 * - Black background = transparent (additive display)
 * - Green monochrome for max transparency + efficiency
 * - Strokes/outlines instead of fills
 * - Large text for distance viewing
 * - Minimal, sparse UI
 */
class GlassesPresentation(
    context: Context,
    display: Display
) : Presentation(context, display), LifecycleOwner, SavedStateRegistryOwner {
    
    // Lifecycle support for Compose
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    
    private var _dialogState = mutableStateOf<GlassesDialogState?>(null)
    private var _activityEvents = mutableStateOf<List<GlassesActivityItem>>(emptyList())
    private var _connectionStatus = mutableStateOf(GlassesConnectionStatus())
    private var _theme = mutableStateOf(GlassesTheme())
    private var _layout = mutableStateOf(GlassesLayoutMode.DIALOG_FOCUSED)
    private var _nowPlaying = mutableStateOf(NowPlayingState())
    private var _navigationState = mutableStateOf(NavigationState())
    private var _youtubeState = mutableStateOf(YouTubeState())
    private var _controlSurfaceState = mutableStateOf<GlassesControlSurfaceState?>(null)
    private var _actionFeedback = mutableStateOf<Pair<String, String>?>(null) // (description, source)
    private var _selectedOptionIndex = mutableStateOf<Int?>(null)
    private var _typingText = mutableStateOf<String?>(null)
    private var _useZonedLayout = mutableStateOf(true)
    private var _contentScrollOffset = mutableStateOf(0f)
    
    // OSM Map state
    private var _osmLocation = mutableStateOf<GeoPoint?>(null)
    private var _osmDestination = mutableStateOf<GeoPoint?>(null)
    private var _osmRouteGeometry = mutableStateOf<List<GeoPoint>>(emptyList())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        savedStateRegistryController.performRestore(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@GlassesPresentation)
            setViewTreeSavedStateRegistryOwner(this@GlassesPresentation)
            setContent {
                GlassesRoot(
                    nowPlayingState = _nowPlaying.value,
                    navigationState = _navigationState.value,
                    dialogState = _dialogState.value,
                    activityEvents = _activityEvents.value,
                    connectionStatus = _connectionStatus.value,
                    theme = _theme.value,
                    layoutMode = _layout.value,
                    youtubeState = _youtubeState.value,
                    controlSurfaceState = _controlSurfaceState.value,
                    actionFeedback = _actionFeedback.value,
                    selectedOptionIndex = _selectedOptionIndex.value,
                    typingText = _typingText.value,
                    useZonedLayout = _useZonedLayout.value,
                    contentScrollOffset = _contentScrollOffset.value,
                    osmLocation = _osmLocation.value,
                    osmDestination = _osmDestination.value,
                    osmRouteGeometry = _osmRouteGeometry.value
                )
            }
        }
        window?.decorView?.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(composeView)
    }
    
    override fun onStart() {
        super.onStart()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }
    
    override fun onStop() {
        super.onStop()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }
    
    override fun dismiss() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.dismiss()
    }
    
    fun updateDialog(state: GlassesDialogState?) {
        // Reset scroll offset when dialog changes (new dialog or cleared)
        if (_dialogState.value?.id != state?.id) {
            _contentScrollOffset.value = 0f
        }
        _dialogState.value = state
    }
    fun updateActivity(events: List<GlassesActivityItem>) { _activityEvents.value = events }
    fun updateConnectionStatus(status: GlassesConnectionStatus) { _connectionStatus.value = status }
    fun updateTheme(theme: GlassesTheme) { _theme.value = theme }
    fun updateLayout(mode: GlassesLayoutMode) { _layout.value = mode }
    fun updateNowPlaying(state: NowPlayingState) { _nowPlaying.value = state }
    fun updateNavigation(state: NavigationState) { _navigationState.value = state }
    fun updateYoutube(state: YouTubeState) { _youtubeState.value = state }
    fun updateControlSurface(state: GlassesControlSurfaceState) { _controlSurfaceState.value = state }
    fun showActionFeedback(description: String, source: String) {
        _actionFeedback.value = description to source
        // Auto-clear after 2 seconds
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            _actionFeedback.value = null
        }, 2000)
    }
    fun updateDialogSelection(index: Int?) { _selectedOptionIndex.value = index }
    fun updateTypingText(text: String?) { _typingText.value = text }
    fun setUseZonedLayout(enabled: Boolean) { _useZonedLayout.value = enabled }
    fun scrollContent(amount: Int) { 
        // Add to scroll offset, coercing to non-negative values
        // The ZonedGlassesLayout will handle coercing to max bounds via scrollState
        _contentScrollOffset.value = (_contentScrollOffset.value + amount).coerceAtLeast(0f)
    }
    
    /**
     * Update OSM map state for glasses display.
     * Call this when navigation location/route changes.
     */
    fun updateOsmMap(
        location: GeoPoint?,
        destination: GeoPoint? = null,
        routeGeometry: List<GeoPoint> = emptyList()
    ) {
        _osmLocation.value = location
        _osmDestination.value = destination
        _osmRouteGeometry.value = routeGeometry
    }
}

// Data classes
data class GlassesDialogState(
    val id: String,
    val title: String,
    val prompt: String,
    val options: List<GlassesDialogOption>,
    val timeRemaining: Int? = null,
    val isUrgent: Boolean = false
)

data class GlassesDialogOption(
    val value: String,
    val label: String,
    val shortcut: String? = null
)

data class GlassesActivityItem(
    val id: String,
    val type: String,
    val description: String,
    val timestamp: Long
)

data class GlassesConnectionStatus(
    val isConnected: Boolean = false,
    val serverName: String = "",
    val latencyMs: Long? = null,
    val agentCount: Int = 0
)

enum class GlassesLayoutMode {
    DIALOG_FOCUSED, ACTIVITY_FEED, SPLIT_VIEW, MINIMAL_STATUS, PNP_OVERLAY
}

// Main composable
@Composable
private fun GlassesRoot(
    nowPlayingState: NowPlayingState,
    navigationState: NavigationState,
    dialogState: GlassesDialogState?,
    activityEvents: List<GlassesActivityItem>,
    connectionStatus: GlassesConnectionStatus,
    theme: GlassesTheme,
    layoutMode: GlassesLayoutMode,
    youtubeState: YouTubeState,
    controlSurfaceState: GlassesControlSurfaceState?,
    actionFeedback: Pair<String, String>?,
    selectedOptionIndex: Int? = null,
    typingText: String? = null,
    useZonedLayout: Boolean = true,
    contentScrollOffset: Float = 0f,
    osmLocation: GeoPoint? = null,
    osmDestination: GeoPoint? = null,
    osmRouteGeometry: List<GeoPoint> = emptyList()
) {
    val primaryColor = GlassesColors.primaryFor(theme.colorPalette)
    val dimColor = GlassesColors.dimFor(theme.colorPalette)
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassesColors.Transparent)
    ) {
        // Use zoned layout for dialog-focused mode when enabled
        if (useZonedLayout && layoutMode == GlassesLayoutMode.DIALOG_FOCUSED) {
            ZonedGlassesLayout(
                dialogState = dialogState,
                selectedOptionIndex = selectedOptionIndex,
                typingText = typingText,
                connectionStatus = connectionStatus,
                theme = theme,
                primaryColor = primaryColor,
                dimColor = dimColor,
                contentScrollOffset = contentScrollOffset,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Legacy layout mode content
            when (layoutMode) {
                GlassesLayoutMode.DIALOG_FOCUSED -> {
                    if (dialogState != null) {
                        GlassesDialogView(dialogState, primaryColor, dimColor, theme, Modifier.align(Alignment.Center))
                    } else {
                        GlassesIdleView(connectionStatus, primaryColor, dimColor, Modifier.align(Alignment.Center))
                    }
                }
                GlassesLayoutMode.ACTIVITY_FEED -> {
                    GlassesActivityView(activityEvents, primaryColor, dimColor, Modifier.align(Alignment.TopCenter))
                }
                GlassesLayoutMode.SPLIT_VIEW -> {
                    Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(0.6f)) {
                            dialogState?.let { GlassesDialogView(it, primaryColor, dimColor, theme) }
                        }
                        Box(Modifier.weight(0.4f)) {
                            GlassesActivityView(activityEvents, primaryColor, dimColor)
                        }
                    }
                }
                GlassesLayoutMode.MINIMAL_STATUS, GlassesLayoutMode.PNP_OVERLAY -> {
                    GlassesStatusBar(connectionStatus, primaryColor, Modifier.align(Alignment.TopStart))
                }
            }
        }
        
        // Connection indicator
        if (layoutMode != GlassesLayoutMode.MINIMAL_STATUS && layoutMode != GlassesLayoutMode.PNP_OVERLAY) {
            GlassesConnectionIndicator(connectionStatus, primaryColor, Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        
        // Navigation overlay (text HUD + OSM map)
        if (navigationState.isNavigating) {
            if (layoutMode == GlassesLayoutMode.MINIMAL_STATUS || layoutMode == GlassesLayoutMode.PNP_OVERLAY) {
                GlassesNavigationMini(
                    state = navigationState,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                )
                // Mini OSM map in corner when minimal mode
                osmLocation?.let { location ->
                    OsmMapMiniOverlay(
                        currentLocation = location,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    )
                }
            } else {
                // Full navigation: use OSM overlay which includes HUD + map
                osmLocation?.let { location ->
                    OsmMapOverlay(
                        currentLocation = location,
                        destination = osmDestination,
                        routePoints = osmRouteGeometry,
                        nextDirection = navigationState.currentInstruction,
                        distanceToNext = navigationState.distanceToNextTurn,
                        eta = navigationState.arrivalTime,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: run {
                    // Fallback to text-only overlay if no location
                    GlassesNavigationOverlay(
                        state = navigationState,
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                    )
                }
            }
        }
        
        // Now Playing overlay
        if (nowPlayingState.hasTrack || nowPlayingState.isPlaying) {
            if (layoutMode == GlassesLayoutMode.MINIMAL_STATUS || layoutMode == GlassesLayoutMode.PNP_OVERLAY) {
                GlassesNowPlayingMini(
                    state = nowPlayingState,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            } else {
                GlassesNowPlaying(
                    state = nowPlayingState,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                )
            }
        }
        
        // YouTube player overlay
        if (youtubeState.isActive) {
            GlassesYouTubePlayer(
                videoId = youtubeState.videoId,
                videoUrl = youtubeState.videoUrl,
                autoplay = true,
                showControls = true,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.9f)
                    .aspectRatio(16f/9f)
            )
        }
        
        // Control Surface status (bottom right)
        controlSurfaceState?.let { state ->
            GlassesControlSurfaceWidget(
                state = state,
                primaryColor = primaryColor,
                dimColor = dimColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            )
        }
        
        // Action feedback toast (center, animates in/out)
        actionFeedback?.let { (description, source) ->
            GlassesActionFeedback(
                description = description,
                source = source,
                primaryColor = primaryColor,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 100.dp)
            )
        }
    }
}

// Control Surface widget for glasses
@Composable
private fun GlassesControlSurfaceWidget(
    state: GlassesControlSurfaceState,
    primaryColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .border(1.dp, dimColor, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Profile icon indicator
        Text(
            text = when (state.profileIcon) {
                "chat" -> "💬"
                "play_circle" -> "▶"
                "dashboard" -> "⊞"
                else -> "⊞"
            },
            color = primaryColor,
            fontSize = 16.sp
        )
        
        Column {
            Text(
                text = state.profileName ?: "No Profile",
                color = primaryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${state.widgetCount} widgets",
                    color = dimColor,
                    fontSize = 10.sp
                )
                if (state.isEditMode) {
                    Text(
                        text = "• EDIT",
                        color = GlassesColors.AlertYellow,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// Action feedback toast
@Composable
private fun GlassesActionFeedback(
    description: String,
    source: String,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .border(2.dp, primaryColor, RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "✓ $description",
            color = primaryColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = source,
            color = primaryColor.copy(alpha = 0.7f),
            fontSize = 12.sp
        )
    }
}

// Dialog view
@Composable
private fun GlassesDialogView(
    dialog: GlassesDialogState,
    primaryColor: Color,
    dimColor: Color,
    theme: GlassesTheme,
    modifier: Modifier = Modifier
) {
    val textScale = theme.textScale
    
    Column(
        modifier = modifier
            .padding(24.dp)
            .then(
                if (theme.useOutlinesOnly) {
                    Modifier.border(2.dp, primaryColor, RoundedCornerShape(12.dp)).padding(16.dp)
                } else {
                    Modifier.background(GlassesColors.SemiTransparent, RoundedCornerShape(12.dp)).padding(16.dp)
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (dialog.isUrgent) {
            Text("⚠ URGENT", color = GlassesColors.AlertYellow, fontSize = (14 * textScale).sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        
        Text(
            text = dialog.title,
            color = primaryColor,
            fontSize = (24 * textScale).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = stripMarkdown(dialog.prompt),
            color = dimColor,
            fontSize = (16 * textScale).sp,
            textAlign = TextAlign.Center,
            softWrap = true
        )
        
        Spacer(Modifier.height(24.dp))
        
        dialog.options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(1.dp, primaryColor, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                option.shortcut?.let {
                    Text("[$it]", color = primaryColor, fontSize = (14 * textScale).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                }
                Text(option.label, color = primaryColor, fontSize = (16 * textScale).sp)
            }
        }
        
        dialog.timeRemaining?.let { seconds ->
            Spacer(Modifier.height(12.dp))
            Text("${seconds}s remaining", color = if (seconds < 30) GlassesColors.AlertYellow else dimColor, fontSize = (12 * textScale).sp)
        }
    }
}

// Activity view
@Composable
private fun GlassesActivityView(
    events: List<GlassesActivityItem>,
    primaryColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(16.dp)) {
        Text("Activity", color = primaryColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        
        events.take(5).forEach { event ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (event.type) { "command" -> "▶"; "dialog_sent" -> "💬"; "dialog_response" -> "✓"; "tool_call" -> "🔧"; "file_edit" -> "📝"; else -> "•" },
                    color = primaryColor, fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(event.description, color = dimColor, fontSize = 14.sp, softWrap = true)
            }
        }
    }
}

// Idle view
@Composable
private fun GlassesIdleView(
    connectionStatus: GlassesConnectionStatus,
    primaryColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (connectionStatus.isConnected) "Ready" else "Not Connected",
            color = primaryColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Light
        )
        if (connectionStatus.isConnected) {
            Spacer(Modifier.height(8.dp))
            Text("${connectionStatus.agentCount} agents active", color = dimColor, fontSize = 16.sp)
        }
    }
}

// Status bar
@Composable
private fun GlassesStatusBar(
    connectionStatus: GlassesConnectionStatus,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .padding(8.dp)
            .border(1.dp, primaryColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (connectionStatus.isConnected) "●" else "○",
            color = if (connectionStatus.isConnected) GlassesColors.GreenBright else GlassesColors.AlertRed,
            fontSize = 12.sp
        )
        connectionStatus.latencyMs?.let { Text("${it}ms", color = primaryColor, fontSize = 12.sp) }
        if (connectionStatus.agentCount > 0) {
            Text("${connectionStatus.agentCount} 🤖", color = primaryColor, fontSize = 12.sp)
        }
    }
}

// Connection indicator
@Composable
private fun GlassesConnectionIndicator(
    status: GlassesConnectionStatus,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(12.dp)
            .background(
                if (status.isConnected) GlassesColors.GreenBright else GlassesColors.AlertRed,
                RoundedCornerShape(50)
            )
    )
}

// Helper function to strip markdown
private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("_(.+?)_"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "• ")
        .trim()
}
