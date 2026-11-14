package com.example.continuumstudio.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.service.tts.PlaybackState
import kotlinx.coroutines.delay

/**
 * Compact "now speaking" strip with a scrubber.
 *
 * Used on the dialog screen for normal phone use. Android Auto cannot render
 * this Compose widget - the car equivalent is a MediaSession / transport
 * control surface - but both read the same [PlaybackState] source.
 *
 * @param playback current position/duration, or null when nothing is loaded
 * @param onSeek scrub callback, fraction 0f..1f
 * @param onPlayPause toggles playback
 * @param onStop stops and dismisses
 */
@Composable
fun DialogAudioBar(
    playback: PlaybackState?,
    onSeek: (Float) -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Dialog audio",
) {
    if (playback == null) return

    // MediaPlayer's position is not observable, so tick while playing. 250ms is
    // enough for a smooth progress bar without busy-looping the main thread.
    var position by remember { mutableStateOf(playback.positionMs) }
    LaunchedEffect(playback.durationMs, playback.playing) {
        while (playback.playing) {
            delay(250)
            position += 250
            if (position > playback.durationMs) position = playback.durationMs
        }
    }
    LaunchedEffect(playback.positionMs) { position = playback.positionMs }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${fmt(position)} / ${fmt(playback.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Slider(
                value = if (playback.durationMs > 0)
                    (position.toFloat() / playback.durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { onSeek(it) },
                modifier = Modifier.fillMaxWidth().height(28.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onPlayPause) {
                    Icon(
                        if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playback.playing) "Pause" else "Play"
                    )
                }
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            }
        }
    }
}

private fun fmt(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
