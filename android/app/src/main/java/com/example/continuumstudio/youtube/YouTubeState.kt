package com.example.continuumstudio.youtube

/**
 * State for YouTube playback on glasses.
 */
data class YouTubeState(
    val isActive: Boolean = false,
    val videoId: String? = null,
    val videoUrl: String? = null,
    val title: String = "",
    val channelName: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val error: String? = null
) {
    val progress: Float
        get() = if (duration > 0) (currentPosition.toFloat() / duration) else 0f
        
    val hasVideo: Boolean
        get() = videoId != null || videoUrl != null
}
