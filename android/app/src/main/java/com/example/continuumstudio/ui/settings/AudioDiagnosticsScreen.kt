package com.example.continuumstudio.ui.settings

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
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
 * On-device audio diagnostics.
 *
 * The Android Auto audio-route problem is invisible from the phone, so this
 * screen surfaces the facts that determine whether the car keeps our audio:
 * whether the MediaSession is connected, what the audio manager thinks the
 * active output is, and whether MultiSound is engaged.
 *
 * Read-only by design - it changes nothing, so it is safe to open while driving
 * (passenger) or debugging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { MultiSoundController(context) }

    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
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

    LaunchedEffect(Unit) { collect() }

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
