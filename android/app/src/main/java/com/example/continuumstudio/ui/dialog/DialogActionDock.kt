package com.example.continuumstudio.ui.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.ChoiceOption
import com.example.continuumstudio.service.tts.PlaybackState

/**
 * The always-there answer strip.
 *
 * ## The problem this solves
 *
 * The old dialog was one long scrolling column: timer, title, prompt, context,
 * *then* options, then the comment field, then Submit. On a phone that meant the
 * choices were often below the fold, and every scroll moved the Submit button
 * away from the thumb. Answering while driving effectively required stopping.
 *
 * ## The shape now
 *
 *  - [PinnedOptions] sits at the top, above the scrolling prompt, so the answers
 *    are reachable without reading or scrolling at all.
 *  - [ActionDock] is pinned at the bottom, in the thumb zone, and holds submit
 *    plus the audio and comment controls.
 *  - The prompt in between scrolls freely and can never push the controls off
 *    screen.
 *
 * Both docks stay put; only the reading pane moves.
 */

/** Colour of the primary action, shared so options and submit agree. */
private val ActionColor = Color(0xFF2E7D32)
private val SelectedColor = Color(0xFF1B5E20)
private val CardColor = Color(0xFF1E1E1E)
private val MutedText = Color(0xFF9E9E9E)

/**
 * Options, numbered to match what is spoken aloud.
 *
 * Numbers matter beyond aesthetics: the spoken script says "Option 1", so the
 * on-screen numbering is the link between what was heard and what is tapped.
 */
@Composable
fun PinnedOptions(
    options: List<ChoiceOption>,
    allowMultiple: Boolean,
    selectedValue: String,
    selectedOptions: Set<String>,
    onSelectOption: (String) -> Unit,
    onToggleOption: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val haptics = LocalHapticFeedback.current

    // Cap the pinned area at a share of the screen rather than a fixed height.
    //
    // A fixed 260dp was still too much on a small phone: with eight options, the
    // audio strip visible and the app's own bars, the reading pane was left 46dp -
    // enough for one line. A fraction of available height adapts to the device.
    val maxPinnedHeight = with(LocalConfiguration.current) {
        // 30%: enough for four options in two columns, while leaving the reading
        // pane usable even at 640dp height with the audio strip showing.
        (screenHeightDp.dp * 0.30f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxPinnedHeight)
            .verticalScroll(rememberScrollState())
            .background(CardColor)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Two columns always. A single column was used beyond four options, but
        // that doubled the height exactly when space was tightest - 8 options is
        // 4 rows in two columns (264dp) versus 8 rows in one (528dp). Labels
        // truncate at two lines, which is acceptable for a choice; being off
        // screen is not.
        val columns = 2
        val rows = options.chunked(columns)

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { option ->
                    val index = options.indexOf(option)
                    val isSelected = if (allowMultiple) {
                        selectedOptions.contains(option.value)
                    } else {
                        selectedValue == option.value
                    }
                    OptionButton(
                        index = index,
                        label = option.label ?: option.value,
                        description = option.description,
                        selected = isSelected,
                        multiple = allowMultiple,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (allowMultiple) onToggleOption(option.value)
                            else onSelectOption(option.value)
                        },
                    )
                }
                // Keep the last odd option the same width as its neighbours.
                if (row.size < columns) {
                    repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun OptionButton(
    index: Int,
    label: String,
    description: String?,
    selected: Boolean,
    multiple: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = if (selected) SelectedColor else Color(0xFF2A2A2A),
        shape = RoundedCornerShape(12.dp),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, Color(0xFF66BB6A))
        } else null,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Spoken number, so heard "option 2" maps to this button.
            Surface(
                color = if (selected) Color(0xFF66BB6A) else Color(0xFF3D3D3D),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${index + 1}",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.Black else Color(0xFFE0E0E0),
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!description.isNullOrBlank() && !selected) {
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = MutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(
                    if (multiple) Icons.Default.CheckBox else Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Bottom action dock: always visible, thumb-reachable.
 *
 * Layout is deliberate: the primary action spans the full width, and the
 * secondary controls (audio, comment) sit in a compact row above it. That
 * ordering means the biggest target is the one used most.
 */
@Composable
fun ActionDock(
    canSubmit: Boolean,
    submitEnabled: Boolean,
    playback: PlaybackState?,
    isMuted: Boolean,
    commentOpen: Boolean,
    onToggleComment: () -> Unit,
    onSubmit: () -> Unit,
    onPlayPause: () -> Unit,
    onStopAudio: () -> Unit,
    onMuteToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipToOptions: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF171717),
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // No navigationBarsPadding here: the dialog screen lives inside the
                // app Scaffold, which already applies that inset via its bottomBar
                // and hands it to us as `padding`. Adding it again wasted 24-48dp
                // at exactly the place space is tightest - directly under Submit.
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Audio transport, only while there is audio to control.
            AnimatedVisibility(visible = playback != null) {
                playback?.let {
                    LiveAudioStrip(
                        playback = it,
                        isMuted = isMuted,
                        onPlayPause = onPlayPause,
                        onStop = onStopAudio,
                        onMuteToggle = onMuteToggle,
                        onSeek = onSeek,
                        onSkipToOptions = onSkipToOptions,
                    )
                }
            }

            // Secondary row: comment toggle and mute-when-idle.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onToggleComment,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFBDBDBD)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3A3A3A)),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        if (commentOpen) Icons.Default.Edit else Icons.Default.Comment,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (commentOpen) "Hide comment" else "Comment", fontSize = 14.sp)
                }

                // Mute for THIS dialog. When nothing is playing it reads as a
                // muted-state indicator so the reason for silence is visible.
                OutlinedButton(
                    onClick = onMuteToggle,
                    modifier = Modifier.height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isMuted) Color(0xFFFFB74D) else Color(0xFFBDBDBD)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isMuted) Color(0xFFFFB74D) else Color(0xFF3A3A3A)
                    ),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (isMuted) "Unmute this dialog" else "Mute this dialog",
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isMuted) "Muted" else "Mute", fontSize = 14.sp)
                }
            }

            // Primary action: full width, tallest target on screen.
            Button(
                onClick = onSubmit,
                enabled = submitEnabled,
                modifier = Modifier.fillMaxWidth().height(62.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ActionColor,
                    disabledContainerColor = Color(0xFF2A2A2A),
                    disabledContentColor = MutedText,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (canSubmit) "Submit" else "Pick an option",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Compact audio transport.
 *
 * Shows a generating state explicitly. Silent waiting was the worst part of the
 * old behaviour: a slow network looked identical to broken audio, which is why
 * the 8s fallback felt arbitrary from the driver's seat.
 */
@Composable
private fun LiveAudioStrip(
    playback: PlaybackState,
    isMuted: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onMuteToggle: () -> Unit,
    onSeek: (Float) -> Unit,
    onSkipToOptions: (() -> Unit)? = null,
) {
    Surface(
        color = Color(0xFF242424),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        playback.isGenerating -> Icons.Default.HourglassEmpty
                        playback.playing -> Icons.Default.GraphicEq
                        else -> Icons.Default.Pause
                    },
                    contentDescription = null,
                    tint = when {
                        playback.isGenerating -> Color(0xFFFFB74D)
                        playback.playing -> Color(0xFF66BB6A)
                        else -> MutedText
                    },
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))

                val status = when {
                    playback.isGenerating ->
                        "Generating audio ${playback.segmentIndex + 1}/${playback.segmentCount}..."
                    isMuted -> "Muted for this dialog"
                    else -> "${playback.positionLabel()} / ${playback.durationLabel()}"
                }
                Text(
                    text = status,
                    fontSize = 13.sp,
                    color = if (playback.isGenerating) Color(0xFFFFB74D) else Color(0xFFE0E0E0),
                    modifier = Modifier.weight(1f),
                )

                // Skip straight to the choices. Only offered when the dialog has
                // options and the read has not already reached them - in the
                // common case the body is ~17s of speech, so this is the
                // difference between waiting and deciding.
                if (onSkipToOptions != null && playback.segmentIndex < playback.segmentCount - 1) {
                    TextButton(
                        onClick = onSkipToOptions,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("Options", fontSize = 12.sp, color = Color(0xFF66BB6A))
                    }
                }

                // 44dp targets: the accessibility minimum is 48dp, and these are
                // the controls reached while driving, so being undersized here was
                // the worst possible place for it. 44 keeps three controls plus
                // the status text on one row at 412dp width; the tappable box is
                // the full IconButton bounds, not the 20dp icon inside it.
                IconButton(onClick = onPlayPause, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (playback.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playback.playing) "Pause" else "Play",
                        tint = Color(0xFFE0E0E0),
                    )
                }
                IconButton(onClick = onStop, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color(0xFFE0E0E0))
                }
                IconButton(onClick = onMuteToggle, modifier = Modifier.size(44.dp)) {
                    Icon(
                        if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = "Mute this dialog",
                        tint = if (isMuted) Color(0xFFFFB74D) else Color(0xFFE0E0E0),
                    )
                }
            }

            // Scrubbing is only meaningful once a segment is loaded. While
            // generating, the bar shows overall script progress instead so a
            // long dialog still looks like it is moving.
            if (playback.isGenerating) {
                LinearProgressIndicator(
                    progress = { playback.scriptProgress },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = Color(0xFFFFB74D),
                    trackColor = Color(0xFF333333),
                )
            } else {
                Slider(
                    value = playback.progress,
                    onValueChange = onSeek,
                    modifier = Modifier.fillMaxWidth().height(22.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF66BB6A),
                        activeTrackColor = Color(0xFF66BB6A),
                        inactiveTrackColor = Color(0xFF3A3A3A),
                    ),
                )
            }
        }
    }
}

/**
 * Horizontal strip of recent answers, for one-tap replies to repeats.
 *
 * Placed in the reading pane rather than the dock so it never competes with
 * Submit for space.
 */
@Composable
fun QuickReplyRow(
    replies: List<String>,
    onReply: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (replies.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        itemsIndexed(replies) { _, reply ->
            AssistChip(
                onClick = { onReply(reply) },
                label = { Text(reply, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
