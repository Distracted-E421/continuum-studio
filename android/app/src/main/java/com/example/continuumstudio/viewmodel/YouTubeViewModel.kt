package com.example.continuumstudio.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.youtube.YouTubeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing YouTube playback on glasses.
 * 
 * Handles video URL/ID parsing and state management for the WebView player.
 */
class YouTubeViewModel : ViewModel() {
    
    private val _youtubeState = MutableStateFlow(YouTubeState())
    val youtubeState: StateFlow<YouTubeState> = _youtubeState.asStateFlow()
    
    /**
     * Start playing a YouTube video on glasses.
     * Accepts various URL formats or direct video ID.
     */
    fun playVideo(input: String) {
        val videoId = extractVideoId(input)
        
        if (videoId == null) {
            _youtubeState.value = _youtubeState.value.copy(
                error = "Invalid YouTube URL or video ID"
            )
            return
        }
        
        _youtubeState.value = YouTubeState(
            isActive = true,
            videoId = videoId,
            videoUrl = input,
            isPlaying = true,
            error = null
        )
    }
    
    /**
     * Play by video ID directly
     */
    fun playVideoById(videoId: String, title: String = "", channel: String = "") {
        _youtubeState.value = YouTubeState(
            isActive = true,
            videoId = videoId,
            title = title,
            channelName = channel,
            isPlaying = true,
            error = null
        )
    }
    
    /**
     * Stop YouTube playback
     */
    fun stopVideo() {
        _youtubeState.value = YouTubeState()
    }
    
    /**
     * Update playback state (from WebView JS bridge if implemented)
     */
    fun updatePlaybackState(
        isPlaying: Boolean = _youtubeState.value.isPlaying,
        position: Long = _youtubeState.value.currentPosition,
        duration: Long = _youtubeState.value.duration
    ) {
        _youtubeState.value = _youtubeState.value.copy(
            isPlaying = isPlaying,
            currentPosition = position,
            duration = duration
        )
    }
    
    /**
     * Extract video ID from various YouTube URL formats
     */
    private fun extractVideoId(url: String): String? {
        return when {
            // Standard watch URL: youtube.com/watch?v=VIDEO_ID
            url.contains("youtube.com/watch") -> {
                url.substringAfter("v=").substringBefore("&").takeIf { it.length == 11 }
            }
            // Short URL: youtu.be/VIDEO_ID
            url.contains("youtu.be/") -> {
                url.substringAfter("youtu.be/").substringBefore("?").takeIf { it.length == 11 }
            }
            // Embed URL: youtube.com/embed/VIDEO_ID
            url.contains("youtube.com/embed/") -> {
                url.substringAfter("embed/").substringBefore("?").takeIf { it.length == 11 }
            }
            // Mobile URL: m.youtube.com/watch?v=VIDEO_ID
            url.contains("m.youtube.com/watch") -> {
                url.substringAfter("v=").substringBefore("&").takeIf { it.length == 11 }
            }
            // Already a video ID (11 chars, alphanumeric with _ and -)
            url.matches(Regex("^[a-zA-Z0-9_-]{11}$")) -> url
            else -> null
        }
    }
}
