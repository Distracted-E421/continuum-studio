package com.example.continuumstudio.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NowPlayingService"

/**
 * Listens to media notifications and active media sessions to provide
 * Now Playing information for any music app (Spotify, YouTube Music, etc.)
 * 
 * Requires NotificationListener permission (user grants in Settings)
 */
class NowPlayingService : NotificationListenerService() {
    
    private var mediaSessionManager: MediaSessionManager? = null
    private var activeController: MediaController? = null
    
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
        
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(metadata)
        }
    }
    
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateActiveSession(controllers)
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "NowPlayingService created")
        
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        
        try {
            val componentName = ComponentName(this, NowPlayingService::class.java)
            mediaSessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            
            // Get initial active sessions
            val sessions = mediaSessionManager?.getActiveSessions(componentName)
            updateActiveSession(sessions)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission not granted for notification listener", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        activeController?.unregisterCallback(mediaCallback)
    }
    
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // We primarily use MediaSession, but can fallback to notification parsing
    }
    
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Handle notification removal if needed
    }
    
    private fun updateActiveSession(controllers: List<MediaController>?) {
        // Prefer Spotify, then any active session
        val preferredApps = listOf("com.spotify.music", "com.google.android.youtube", "com.google.android.apps.youtube.music")
        
        val controller = controllers?.firstOrNull { ctrl ->
            preferredApps.any { pkg -> ctrl.packageName == pkg }
        } ?: controllers?.firstOrNull()
        
        if (controller != activeController) {
            activeController?.unregisterCallback(mediaCallback)
            activeController = controller
            activeController?.registerCallback(mediaCallback)
            
            // Update with current state
            updatePlaybackState(controller?.playbackState)
            updateMetadata(controller?.metadata)
            
            Log.d(TAG, "Active session: ${controller?.packageName}")
            
            // Update source package
            _nowPlayingState.value = _nowPlayingState.value.copy(
                sourcePackage = controller?.packageName ?: ""
            )
        }
    }
    
    private fun updatePlaybackState(state: PlaybackState?) {
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position ?: 0L
        
        _nowPlayingState.value = _nowPlayingState.value.copy(
            isPlaying = isPlaying,
            positionMs = position
        )
        
        Log.d(TAG, "Playback: playing=$isPlaying, position=$position")
    }
    
    private fun updateMetadata(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val artUri = metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        
        _nowPlayingState.value = _nowPlayingState.value.copy(
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            artworkUri = artUri
        )
        
        Log.d(TAG, "Metadata: $title - $artist")
    }
    
    // ============================================================================
    // Playback Control
    // ============================================================================
    
    fun play() {
        activeController?.transportControls?.play()
    }
    
    fun pause() {
        activeController?.transportControls?.pause()
    }
    
    fun next() {
        activeController?.transportControls?.skipToNext()
    }
    
    fun previous() {
        activeController?.transportControls?.skipToPrevious()
    }
    
    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs)
    }
    
    companion object {
        private val _nowPlayingState = MutableStateFlow(NowPlayingState())
        val nowPlayingState: StateFlow<NowPlayingState> = _nowPlayingState.asStateFlow()
        
        // Singleton reference for control (set when service binds)
        var instance: NowPlayingService? = null
            private set
    }
}

/**
 * State holder for Now Playing information
 */
data class NowPlayingState(
    val sourcePackage: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val artworkUri: String? = null
) {
    val hasTrack: Boolean get() = title.isNotEmpty()
    
    val isYouTube: Boolean get() = sourcePackage == "com.google.android.youtube" || 
        sourcePackage == "com.google.android.apps.youtube.music" ||
        sourcePackage == "com.vanced.android.youtube"
    
    val isSpotify: Boolean get() = sourcePackage == "com.spotify.music"
    
    val sourceDisplayName: String get() = when {
        isYouTube -> "YouTube"
        isSpotify -> "Spotify"
        sourcePackage.isNotEmpty() -> sourcePackage.substringAfterLast(".")
        else -> "Unknown"
    }
    
    val progressFraction: Float get() = 
        if (durationMs > 0) (positionMs.toFloat() / durationMs) else 0f
    
    val formattedPosition: String get() = formatDuration(positionMs)
    val formattedDuration: String get() = formatDuration(durationMs)
    
    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000 / 60) % 60
        val hours = ms / 1000 / 60 / 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }
}
