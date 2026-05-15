package com.example.continuumstudio.ui.youtube

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.youtube.YouTubeState

/**
 * Control panel for sending YouTube videos to glasses.
 * 
 * Features:
 * - Text field for URL/video ID input
 * - Paste from clipboard button
 * - Play/Stop buttons
 * - Current playback status
 */
@Composable
fun YouTubeControlPanel(
    youtubeState: YouTubeState,
    onPlay: (String) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputUrl by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Text(
                text = "YouTube on Glasses",
                style = MaterialTheme.typography.titleMedium
            )
            
            // URL input
            OutlinedTextField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                label = { Text("YouTube URL or Video ID") },
                placeholder = { Text("Paste URL or enter video ID...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        if (inputUrl.isNotBlank()) {
                            onPlay(inputUrl)
                        }
                    }
                ),
                trailingIcon = {
                    Row {
                        // Paste button
                        IconButton(onClick = {
                            clipboardManager.getText()?.let { clipText ->
                                inputUrl = clipText.text
                            }
                        }) {
                            Icon(
                                Icons.Default.ContentPaste,
                                contentDescription = "Paste from clipboard"
                            )
                        }
                    }
                }
            )
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play button
                Button(
                    onClick = { 
                        if (inputUrl.isNotBlank()) {
                            onPlay(inputUrl)
                        }
                    },
                    enabled = inputUrl.isNotBlank() && !youtubeState.isActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Play on Glasses")
                }
                
                // Stop button
                if (youtubeState.isActive) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Stop")
                    }
                }
            }
            
            // Current playback status
            if (youtubeState.isActive) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "▶ Playing on glasses",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.weight(1f))
                    youtubeState.title.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Error display
            youtubeState.error?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            
            // Help text
            Text(
                text = "Note: Only videos that allow embedding will work. Music videos and some premium content may be blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Compact version for dashboard widget
 */
@Composable
fun YouTubeControlWidget(
    youtubeState: YouTubeState,
    onOpenFullPanel: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        onClick = onOpenFullPanel
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = if (youtubeState.isActive) 
                    MaterialTheme.colorScheme.primary 
                else 
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "YouTube",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (youtubeState.isActive) "Playing on glasses" else "Tap to send video",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (youtubeState.isActive) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Close, contentDescription = "Stop")
                }
            }
        }
    }
}
