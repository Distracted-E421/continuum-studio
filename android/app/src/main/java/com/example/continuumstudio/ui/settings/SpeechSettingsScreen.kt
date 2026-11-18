package com.example.continuumstudio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.speech.PronunciationDictionary
import com.example.continuumstudio.speech.SpeechScript
import com.example.continuumstudio.speech.SpeechScriptCompiler

/**
 * Speech settings: how a dialog becomes audio.
 *
 * Three things are configurable here, in order of how often they need
 * changing while away from the desk:
 *
 *  1. **Wait budget.** The old fixed 8 seconds was the single most annoying
 *     behaviour on a mobile link: a dialog that needed 9 seconds silently
 *     switched to the on-device voice. This is now a preference with a clear
 *     explanation of the trade.
 *  2. **The dictionary.** Words that are spelled right but said wrong, plus
 *     the expansions that make file names and symbols readable.
 *  3. **Script preview.** Paste a dialog's markdown and see exactly what would
 *     be spoken, segment by segment, before trusting it during a drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechSettingsScreen(
    onBack: () -> Unit,
    waitMs: Long,
    onWaitMsChange: (Long) -> Unit,
    speechRate: Float,
    onSpeechRateChange: (Float) -> Unit,
    autoRead: Boolean,
    onAutoReadChange: (Boolean) -> Unit,
    /** Master switch for reading dialogs aloud. Defaults to off. */
    enabled: Boolean = false,
    onEnabledChange: (Boolean) -> Unit = {},
    currentScript: SpeechScript? = null,
    onSpeakPreview: (SpeechScript) -> Unit = {},
    voice: String = com.example.continuumstudio.service.tts.EasyVoiceClient.DEFAULT_VOICE,
    onVoiceChange: (String) -> Unit = {},
    onPreviewVoice: (String) -> Unit = {},
    focusPolicy: com.example.continuumstudio.service.tts.FocusPolicy =
        com.example.continuumstudio.service.tts.FocusPolicy.DEFAULT,
    onFocusPolicyChange: (com.example.continuumstudio.service.tts.FocusPolicy) -> Unit = {},
) {
    var showDictionary by remember { mutableStateOf(false) }
    var dictionaryQuery by remember { mutableStateOf("") }
    var previewInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Speech") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {

            // --- Waiting ---------------------------------------------------

            Text("Waiting for audio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "How long one part of a dialog may take to generate before the " +
                    "app gives up and uses the on-device voice. A dialog is spoken " +
                    "in parts, so this is per part, not per dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Currently ${waitMs / 1000}s — ${waitVerdict(waitMs)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Slider(
                value = waitMs.toFloat(),
                onValueChange = { onWaitMsChange(it.toLong()) },
                valueRange = 8_000f..120_000f,
                steps = 21, // 5s increments between 8s and 120s
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15_000L, 25_000L, 45_000L, 90_000L).forEach { preset ->
                    FilterChip(
                        selected = waitMs == preset,
                        onClick = { onWaitMsChange(preset) },
                        label = { Text("${preset / 1000}s", fontSize = 12.sp) },
                    )
                }
            }

            HorizontalDivider()

            // --- Voice -----------------------------------------------------

            Text("Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Tap a voice to select it, or the sample button to hear it first. " +
                    "Changing voice re-reads the current dialog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.example.continuumstudio.service.tts.EasyVoiceClient.VOICES.forEach { option ->
                val selected = option.id == voice
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onVoiceChange(option.id) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onVoiceChange(option.id) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            "${option.accent} · ${option.note}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onPreviewVoice(option.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Hear ${option.name}")
                    }
                }
            }

            HorizontalDivider()

            // --- Reading ---------------------------------------------------

            // Master switch. This is the only place it can be turned on: the
            // setting defaults to off, and until now nothing in the UI called
            // setTtsEnabled, so dialog reading was permanently silent with no
            // way to enable it.
            Text("Reading", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read dialogs aloud", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (enabled) "New dialogs will be spoken."
                        else "Dialog reading is OFF - nothing will be spoken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Read new dialogs automatically", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Mute on the dialog page silences only that dialog.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoRead, onCheckedChange = onAutoReadChange)
            }
            Text(
                "Speed: %.2f×".format(speechRate),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = speechRate,
                onValueChange = onSpeechRateChange,
                valueRange = 0.6f..1.8f,
            )

            HorizontalDivider()

            // --- Other apps' audio ----------------------------------------

            //
            // This is the setting that decides whether Vivaldi keeps playing
            // while a dialog is read. Overlap is the default because it is the
            // only option that cannot leave another app paused or quietened.
            Text(
                "While a dialog is playing",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "What happens to music in another app. Overlap never asks for " +
                    "audio focus, so nothing else is interrupted and nothing can get " +
                    "stuck. Ducking and pausing ask the other app to react.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            com.example.continuumstudio.service.tts.FocusPolicy.entries.forEach { policy ->
                val selected = policy == focusPolicy
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onFocusPolicyChange(policy) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { onFocusPolicyChange(policy) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            focusPolicyLabel(policy),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            focusPolicyNote(policy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (focusPolicy != com.example.continuumstudio.service.tts.FocusPolicy.OVERLAP) {
                Text(
                    "Heads up: on this phone a paused app often does not resume by " +
                        "itself. Overlap is the setting that cannot interrupt it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider()

            // --- Dictionary ------------------------------------------------

            Row(
                modifier = Modifier.fillMaxWidth().clickable { showDictionary = !showDictionary },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pronunciation dictionary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${PronunciationDictionary.allEntries().size} entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (showDictionary) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }

            if (showDictionary) {
                OutlinedTextField(
                    value = dictionaryQuery,
                    onValueChange = { dictionaryQuery = it },
                    label = { Text("Filter") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val entries = PronunciationDictionary.allEntries()
                    .filter {
                        dictionaryQuery.isBlank() ||
                            it.first.contains(dictionaryQuery, ignoreCase = true) ||
                            it.second.contains(dictionaryQuery, ignoreCase = true)
                    }
                    .sortedBy { it.first }

                Text(
                    "${entries.size} shown — spelled on the left, spoken on the right",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Bounded list: this lives inside a scrollable column, so a
                // large dictionary must not create thousands of nodes.
                entries.take(80).forEach { (written, spoken, kind) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            written,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            spoken,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1.3f),
                        )
                        Text(
                            kind,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (entries.size > 80) {
                    Text(
                        "…${entries.size - 80} more. Use the filter to narrow.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // --- Script preview --------------------------------------------

            Text("Script preview", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Paste markdown to see exactly what would be spoken. Segments are " +
                    "synthesized in order, so the first one starts before the rest exists.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = previewInput,
                onValueChange = { previewInput = it },
                placeholder = { Text("**Done** — edited `DialogScreen.kt`\n\n- 12 tests pass\n- 300ms faster") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                maxLines = 6,
            )

            val preview = remember(previewInput) {
                if (previewInput.isBlank()) null
                else SpeechScriptCompiler.compile(
                    title = "Preview",
                    prompt = previewInput,
                    dialogType = "choice",
                    optionLabels = listOf("Ship it", "Hold"),
                )
            }

            if (preview != null) {
                Text(
                    "${preview.segments.size} segments, ${preview.characterCount} characters",
                    style = MaterialTheme.typography.labelMedium,
                )
                Button(
                    onClick = { onSpeakPreview(preview) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Speak this preview")
                }
                ScriptLines(preview)
            }

            // --- The dialog currently on screen ----------------------------

            currentScript?.let { script ->
                HorizontalDivider()
                Text(
                    "Current dialog script",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                ScriptLines(script)
            }
        }
    }
}

/** Segment list, newest information first: kind, text, and its pause. */
@Composable
private fun ScriptLines(script: SpeechScript) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            script.segments.forEachIndexed { index, segment ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            segment.spoken,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${segment.kind.name.lowercase()} · +${segment.pauseAfterMs}ms",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Plain-language consequence of the current wait setting. */
private fun waitVerdict(ms: Long): String = when {
    ms <= 12_000L -> "snappy, but may drop to the fallback voice on a weak link"
    ms <= 30_000L -> "balanced for mobile use"
    ms <= 60_000L -> "patient; good on Tailscale while driving"
    else -> "very patient; audio may feel late but keeps the good voice"
}

/** Short name for each focus policy. */
private fun focusPolicyLabel(policy: com.example.continuumstudio.service.tts.FocusPolicy): String =
    when (policy) {
        com.example.continuumstudio.service.tts.FocusPolicy.OVERLAP -> "Play over the top"
        com.example.continuumstudio.service.tts.FocusPolicy.DUCK -> "Lower the other app"
        com.example.continuumstudio.service.tts.FocusPolicy.PAUSE -> "Pause the other app"
    }

/** What each focus policy actually does to the other app. */
private fun focusPolicyNote(policy: com.example.continuumstudio.service.tts.FocusPolicy): String =
    when (policy) {
        com.example.continuumstudio.service.tts.FocusPolicy.OVERLAP ->
            "Music keeps its volume. Nothing else is told the dialog started."
        com.example.continuumstudio.service.tts.FocusPolicy.DUCK ->
            "The other app is asked to quieten down and is expected to come back up."
        com.example.continuumstudio.service.tts.FocusPolicy.PAUSE ->
            "The other app stops for the dialog and may need restarting by hand."
    }
