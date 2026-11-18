package com.example.continuumstudio.ui.settings

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.audio.MultiSoundController
import com.example.continuumstudio.audio.ShizukuBridge
import com.example.continuumstudio.service.tts.MediaSessionBridge
import kotlinx.coroutines.launch

/**
 * Send a report somewhere the user can pick.
 *
 * Deliberately a share sheet rather than an upload: it needs no server, no
 * token and no new endpoint, and it works with whatever the user already has
 * (email to self, a chat, a notes app). A diagnostics feature that depends on
 * being online is useless in exactly the situation it is needed for.
 *
 * **Must be called on the main thread** - `startActivity` needs it - but it does
 * no work beyond handing over an intent, so it cannot freeze the UI. The first
 * version of this was fine as an intent but the blocking probe that ran before it
 * was not (see AudioReportCollector.probePlayback).
 */
private fun shareReport(context: Context, text: String): Boolean {
    return try {
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Continuum audio report")
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share audio report")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        android.util.Log.w("AudioDiagnostics", "could not share report: ${e.message}")
        false
    }
}

/**
 * Write the report to a file the user can retrieve, and return its path.
 *
 * Second delivery path on purpose. A share sheet needs some app to receive the
 * intent; if the user has nothing suitable installed, the report went nowhere and
 * there was no way to tell. The file always works, and `adb pull` can fetch it
 * later without the app being open.
 */
private fun writeReportFile(context: Context, text: String): String = try {
    val dir = java.io.File(context.getExternalFilesDir(null) ?: context.filesDir, "reports")
        .apply { mkdirs() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    val file = java.io.File(dir, "audio-$stamp.txt")
    file.writeText(text)
    file.absolutePath
} catch (e: Exception) {
    "could not write report file: ${e.message}"
}

/**
 * On-device audio diagnostics.
 *
 * The Android Auto audio-route problem is invisible from the phone, so this
 * screen surfaces the facts that determine whether the car keeps our audio:
 * whether the MediaSession is connected, what the audio manager thinks the
 * active output is, and whether MultiSound is engaged.
 *
 * Read-only by design - it changes nothing, so it is safe to open while driving
 * (passenger) or debugging. It reports the live MediaSession state rather than
 * "see logcat" because a session left connected while idle is precisely the
 * failure that silences a head unit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { MultiSoundController(context) }

    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // The most recent report text, shown in-app as well as shared. This exists
    // because the share sheet is not a reliable delivery path: with no suitable
    // target the intent goes nowhere, and the first version gave no feedback at
    // all, so a report could be "sent" into nothing.
    var reportText by remember { mutableStateOf<String?>(null) }
    var reportStatus by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    /** Everything that touches disk or the audio service, off the main thread. */
    suspend fun buildReport(append: String = ""): String = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val car = com.example.continuumstudio.audio.AudioReportCollector
            .collect(context, "car / Android Auto")
        val phone = com.example.continuumstudio.audio.AudioReportCollector
            .collect(context, "phone / Bluetooth")
        buildString {
            if (append.isNotBlank()) {
                appendLine(append)
                appendLine()
            }
            appendLine(
                com.example.continuumstudio.audio.AudioReport.describeDifferences(car, phone)
            )
            appendLine()
            // Findings for the CURRENT state, printed before the raw dump so the
            // reader does not have to know what a healthy value looks like.
            val findings = com.example.continuumstudio.audio.AudioFindings.inspect(car)
            if (findings.isEmpty()) {
                appendLine("FINDINGS: nothing suspicious in the current audio state.")
            } else {
                appendLine("FINDINGS:")
                findings.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("=== CAR ===")
            appendLine(car.render())
            appendLine("=== PHONE ===")
            appendLine(phone.render())
        }
    }

    /**
     * Build the report, persist it, and share it.
     *
     * The disk write is inside the IO block: writing to external storage on the
     * main thread is what made the screen stutter after sharing. Only
     * `startActivity` runs on the caller's (main) dispatcher.
     */
    suspend fun produceAndShare(append: String = ""): Pair<String, String> {
        val text = buildReport(append)
        val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            writeReportFile(context, text)
        }
        val shared = shareReport(context, text)
        return text to if (shared) path else "not shared: $path"
    }
    // Live speech-pipeline log. Collected as state so it updates while a dialog
    // is being read, which is when the interesting events happen.
    val trace by com.example.continuumstudio.speech.SpeechTrace.events.collectAsState()
    // Read-only: use existing() so merely opening this screen never creates a
    // player as a side effect of drawing (see AudioHub). The remembered flow is
    // a stable stand-in when no player exists yet.
    val emptyPlayback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(
            null as com.example.continuumstudio.service.tts.PlaybackState?
        )
    }
    val playbackFlow = com.example.continuumstudio.service.tts.AudioHub.existing()?.playback
        ?: emptyPlayback
    val playback by playbackFlow.collectAsState()

    // Media volume, tracked so the repair button can show what it changed.
    var mediaVolume by remember { mutableStateOf(-1) }
    var mediaVolumeMax by remember { mutableStateOf(-1) }
    var showTrace by remember { mutableStateOf(true) }

    suspend fun collect() {
        val out = mutableListOf<Pair<String, String>>()
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        out += "MediaSession" to if (MediaSessionBridge.isConnected()) {
            "CONNECTED - holding the car's media channel"
        } else {
            "released (nothing to play)"
        }

        out += "Shizuku available" to runCatching { ShizukuBridge.isAvailable() }.getOrDefault(false).toString()

        out += "MultiSound" to runCatching {
            if (controller.isReady()) controller.isMultiAudioFocusEnabled().toString() else "unknown (Shizuku)"
        }.getOrDefault("unknown")

        out += "Media volume" to "${am.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"

        // Cached clips. Worth surfacing: the cache is what makes a repeated
        // dialog instant, and it is also the only thing this app accumulates on
        // disk besides logs.
        out += "Audio cache" to runCatching {
            val client = com.example.continuumstudio.service.tts.EasyVoiceClient(context)
            val bytes = client.cacheSizeBytes()
            val files = java.io.File(context.cacheDir, "easyvoice").listFiles()?.count { it.isFile } ?: 0
            "$files clips, ${bytes / 1024} KB"
        }.getOrDefault("unavailable")

        out += "Mode" to when (am.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            else -> am.mode.toString()
        }

        out += "Music active" to am.isMusicActive.toString()

        out += "Speaker on" to am.isSpeakerphoneOn.toString()

        val devices = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(", ") { d ->
                    when (d.type) {
                        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
                        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
                        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
                        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
                        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-dev"
                        else -> "t${d.type}"
                    }
                }
        }.getOrDefault("none")
        out += "Outputs" to devices

        out += "AA connected" to (
            context.getSystemService(android.app.UiModeManager::class.java)
                ?.currentModeType
                ?.toString() ?: "?"
            )

        // A real AudioFocusRequest is used deliberately. The old code called the
        // deprecated requestAudioFocus(null, stream, gain) overload, which on
        // modern Android returns FAILED because a null listener cannot receive
        // callbacks - so this probe reported "not grantable" on a perfectly
        // healthy device. It also abandoned focus straight away so the probe
        // itself does not hold anything.
        val focusRequest = android.media.AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        val focusGranted = runCatching {
            val granted = am.requestAudioFocus(focusRequest) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) am.abandonAudioFocusRequest(focusRequest)
            granted
        }.getOrDefault(false)
        out += "Focus grantable" to focusGranted.toString()

        // The active policy matters more than grantability now: under overlap we
        // never ask, which is the point.
        val activePolicy = com.example.continuumstudio.service.tts.AudioHub.existing()
            ?.focusPolicy?.storedValue
            ?: "not created yet"
        out += ("Dialog audio policy" to activePolicy)

        lines = out
    }

    LaunchedEffect(Unit) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        mediaVolumeMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        collect()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Share a full audio report. This is the mechanism for
                    // comparing the car's behaviour with the phone's: collect one
                    // in each situation and the difference is the answer.
                    IconButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                reportStatus = null
                                try {
                                    val (text, where) = produceAndShare()
                                    reportText = text
                                    reportStatus = "Shown below. $where"
                                } catch (e: Exception) {
                                    reportStatus = "Report failed: ${e.javaClass.simpleName}: ${e.message}"
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, "Share audio report")
                    }
                    IconButton(onClick = {
                        com.example.continuumstudio.speech.SpeechTrace.clear()
                    }) {
                        Icon(Icons.Default.Delete, "Clear trace")
                    }
                    IconButton(onClick = { scope.launch { collect() } }) {
                        Icon(Icons.Default.Refresh, "Refresh")
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Use this when audio misbehaves in the car. It reports what the phone " +
                    "believes about audio routing and the media session.",
                style = MaterialTheme.typography.bodySmall
            )

            lines.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1.4f)
                    )
                }
                HorizontalDivider()
            }

            // --- Media volume ---------------------------------------------
            //
            // A real car report showed music at 2/15 and notifications and system
            // at 0/15, which is inaudible over road noise while looking like
            // healthy numbers. Rather than asking the user to hunt for the right
            // stream at the wheel, offer the fix directly.
            HorizontalDivider()
            Text(
                "Media volume",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (mediaVolume >= 0) {
                    "$mediaVolume of $mediaVolumeMax" +
                        when {
                            mediaVolume == 0 -> " - silent. Nothing can be heard."
                            mediaVolume <= 3 -> " - very low. Inaudible over road noise."
                            else -> ""
                        }
                } else {
                    "unknown"
                },
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    mediaVolume in 0..3 -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(
                onClick = {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    // Two thirds of max: loud enough in a car, short of the level
                    // that would be startling, and reversible by the volume keys.
                    val target = (max * 2) / 3
                    runCatching {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                    }
                    mediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    mediaVolumeMax = max
                    reportStatus =
                        "Media volume set to $mediaVolume of $mediaVolumeMax. Turn it " +
                            "back down if that was too loud."
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mediaVolumeMax > 0,
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Raise media volume")
            }
            Text(
                "This changes the phone's media stream only - not notifications or " +
                    "alarms - so nothing else is altered.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Report ---------------------------------------------------
            //
            // The report is displayed here as well as shared. Relying on the share
            // sheet alone meant a report could be "sent" into nothing if no app
            // accepted the intent, with no feedback.
            Text(
                "Tap the share icon to send a full audio report. It collects the same " +
                    "fields twice and diffs them, so the useful part is which values " +
                    "moved between car and phone. The text also appears below, and is " +
                    "saved to a file, so it can always be recovered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        reportStatus = null
                        try {
                            val (text, where) = produceAndShare()
                            reportText = text
                            reportStatus = "Shown below. $where"
                        } catch (e: Exception) {
                            reportStatus = "Report failed: ${e.javaClass.simpleName}: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Collecting..." else "Share audio report")
            }

            // Audible probe, opt-in. A silent probe cannot tell "routed but quiet"
            // from "not routed at all", and that distinction is the whole
            // question in a car. Kept behind a button because it makes noise.
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        reportStatus = "Playing a 1s beep..."
                        try {
                            // Suspend and IO-bound: this used to block the main
                            // thread for up to 6s and made the whole screen lag.
                            val probe = com.example.continuumstudio.audio.AudioReportCollector
                                .probePlayback(context, audible = true)
                            val probeText = buildString {
                                appendLine("Audible probe (a 1s beep should have played)")
                                probe.forEach { (k, v) -> appendLine("$k: $v") }
                            }
                            val (text, where) = produceAndShare(append = probeText)
                            reportText = text
                            reportStatus = "Shown below. $where"
                        } catch (e: Exception) {
                            reportStatus = "Probe failed: ${e.javaClass.simpleName}: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Play a test beep and share the result") }

            reportStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            reportText?.let { text ->
                // Copy-to-clipboard is the primary action, not text selection.
                // Selecting text one-handed in a car is genuinely hard, and the
                // user has to paste it into a dialog comment next - so the report
                // is put on the clipboard directly and the selection container is
                // only a fallback.
                Button(
                    onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("Continuum audio report", text)
                        )
                        reportStatus = "Copied to clipboard. Paste it into a dialog comment."
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy report to clipboard")
                }
                SelectionContainer {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            // --- Live speech pipeline ------------------------------------
            //
            // This is the section that makes a remote debugging session
            // possible: it shows the compiled script, per-segment generation
            // timings, and the reason a read was skipped.
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showTrace = !showTrace },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Speech pipeline",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text("${trace.size} events", style = MaterialTheme.typography.labelSmall)
                Icon(
                    if (showTrace) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
            }

            playback?.let { pb ->
                Text(
                    "Now: ${pb.phase.name.lowercase()} " +
                        "segment ${pb.segmentIndex + 1}/${pb.segmentCount} " +
                        "${pb.positionLabel()}/${pb.durationLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (showTrace) {
                // Newest last reads naturally while watching, but the visible
                // window is capped so a long read does not bury the top of the
                // screen under hundreds of segment lines.
                trace.takeLast(40).forEach { event ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            event.timeLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "${event.tag}: ${event.message}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (event.level) {
                                com.example.continuumstudio.speech.SpeechTrace.Event.Level.ERROR ->
                                    MaterialTheme.colorScheme.error
                                com.example.continuumstudio.speech.SpeechTrace.Event.Level.WARN ->
                                    MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (trace.isEmpty()) {
                    Text(
                        "No speech events yet. Trigger a dialog to populate this.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                "Full session detail is in logcat under tags MediaSessionBridge and " +
                    "DialogMediaService.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
