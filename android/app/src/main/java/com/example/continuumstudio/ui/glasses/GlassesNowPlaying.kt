package com.example.continuumstudio.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.GlassesColors
import com.example.continuumstudio.service.NowPlayingState

/**
 * Now Playing widget optimized for AR glasses display.
 * 
 * Features:
 * - Minimal, high-contrast design (green on black)
 * - Track info (title, artist)
 * - Source-aware styling (YouTube red, Spotify green, etc.)
 * - Playback controls
 * - Progress indicator
 */
@Composable
fun GlassesNowPlaying(
    state: NowPlayingState,
    onPlay: () -> Unit = {},
    onPause: () -> Unit = {},
    onNext: () -> Unit = {},
    onPrevious: () -> Unit = {},
    showControls: Boolean = true,
    modifier: Modifier = Modifier
) {
    val primaryColor = GlassesColors.GreenPrimary
    val dimColor = GlassesColors.GreenDim
    
    // Source-specific accent color
    val accentColor = when {
        state.isYouTube -> Color(0xFFFF0000) // YouTube red
        state.isSpotify -> Color(0xFF1DB954) // Spotify green
        else -> primaryColor
    }
    
    if (!state.hasTrack) {
        // No track playing - show minimal indicator
        Box(
            modifier = modifier
                .border(1.dp, dimColor, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = dimColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "No music playing",
                    color = dimColor,
                    fontSize = 12.sp
                )
            }
        }
        return
    }
    
    Column(
        modifier = modifier
            .border(2.dp, accentColor, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .widthIn(min = 200.dp, max = 300.dp)
    ) {
        // Source badge (when not Spotify/default)
        if (state.isYouTube) {
            Text(
                text = "▶ YouTube",
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        // Track info
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Source-aware icon
            Icon(
                imageVector = if (state.isYouTube) Icons.Outlined.PlayCircle else Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(32.dp)
            )
            
            Column(modifier = Modifier.weight(1f)) {
                // Title
                Text(
                    text = state.title,
                    color = primaryColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                // Artist/Channel
                Text(
                    text = if (state.isYouTube) state.artist.ifEmpty { "YouTube" } else state.artist,
                    color = dimColor,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Progress bar
        LinearProgressIndicator(
            progress = { state.progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = accentColor,
            trackColor = Color(0x44000000)
        )
        
        // Time display
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                state.formattedPosition,
                color = dimColor,
                fontSize = 10.sp
            )
            Text(
                state.formattedDuration,
                color = dimColor,
                fontSize = 10.sp
            )
        }
        
        // Playback controls
        if (showControls) {
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = dimColor
                    )
                }
                
                IconButton(
                    onClick = if (state.isPlaying) onPause else onPlay,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = accentColor,
                        modifier = Modifier.size(32.dp)
                    )
                }
                
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = dimColor
                    )
                }
            }
        }
    }
}

/**
 * Minimal Now Playing indicator for status bar mode
 */
@Composable
fun GlassesNowPlayingMini(
    state: NowPlayingState,
    modifier: Modifier = Modifier
) {
    if (!state.hasTrack && !state.isPlaying) return
    
    val accentColor = when {
        state.isYouTube -> Color(0xFFFF0000)
        state.isSpotify -> Color(0xFF1DB954)
        else -> GlassesColors.GreenPrimary
    }
    
    Row(
        modifier = modifier
            .background(GlassesColors.Transparent)
            .border(1.dp, accentColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Play/Pause indicator with source awareness
        Icon(
            imageVector = if (state.isPlaying) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(14.dp)
        )
        
        // Source label for YouTube
        if (state.isYouTube) {
            Text(
                text = "YT:",
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        // Title
        Text(
            text = state.title.take(25) + if (state.title.length > 25) "..." else "",
            color = GlassesColors.GreenPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
        
        // Progress
        Text(
            text = state.formattedPosition,
            color = GlassesColors.GreenDim,
            fontSize = 10.sp
        )
    }
}
