package com.example.continuumstudio.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.audio.AudioReportCollector
import com.example.continuumstudio.audio.ShizukuBridge
import com.example.continuumstudio.probe.ProbeCommand
import com.example.continuumstudio.probe.ProbeJson
import com.example.continuumstudio.probe.ProbeRoute
import com.example.continuumstudio.probe.ProbeServer
import com.example.continuumstudio.probe.ProbeTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.SecureRandom

/**
 * Probe bridge control surface.
 *
 * Deliberately explicit about every safety property, because this screen is the only
 * thing standing between a phone and a listening socket: the token is shown so it can
 * be copied, the bind address is stated in words, and stopping is one tap.
 *
 * See `android/docs/PROBE_BRIDGE.md`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeBridgeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Token is generated once per enable and kept in memory only. A token that
    // survives a restart would be a credential sitting on the device for a feature
    // that is off most of the time.
    var token by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<ProbeServer?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var allowTailnet by remember { mutableStateOf(false) }
    var tailnetHint by remember { mutableStateOf<String?>(null) }
    var lastTranscript by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun newToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        // URL-safe base64 without padding: survives being pasted into a header.
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Build the handler that answers probe routes from live app state. */
    fun makeServer(currentToken: () -> String?): ProbeServer = ProbeServer(
        tokenProvider = currentToken,
        log = { msg -> android.util.Log.d("ProbeBridge", msg) },
        readHandler = { req: ProbeServer.ProbeRequest -> handleProbe(context, req) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Probe bridge") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "A small read-only HTTP server so a desktop can query this phone's " +
                    "audio state directly instead of relying on a description of what " +
                    "happened. Off unless you start it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- Loopback (default) -------------------------------------
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Loopback only", fontWeight = FontWeight.Bold)
                    Text(
                        "Listens on 127.0.0.1. Nothing on any network can reach it. " +
                            "From a computer with wireless debugging paired, use:\n\n" +
                            "adb forward tcp:8765 tcp:8765\n" +
                            "curl -H \"X-Probe-Token: <token>\" http://127.0.0.1:8765/probe/audio",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = running == null && !busy,
                            onClick = {
                                busy = true
                                val t = newToken()
                                token = t
                                val server = makeServer { t }
                                val ok = server.start(InetAddress.getLoopbackAddress(), ProbeServer.DEFAULT_PORT)
                                running = if (ok) server else null
                                status = if (ok) {
                                    "Listening on 127.0.0.1:${server.boundPort}"
                                } else {
                                    "Could not bind port ${ProbeServer.DEFAULT_PORT}"
                                }
                                busy = false
                            },
                        ) { Text("Start (loopback)") }

                        OutlinedButton(
                            enabled = running != null,
                            onClick = {
                                running?.stop()
                                running = null
                                token = null
                                status = "Stopped"
                            },
                        ) { Text("Stop") }
                    }
                }
            }

            // --- Tailnet (opt-in, warned) --------------------------------
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Also listen on the tailnet", fontWeight = FontWeight.Bold)
                            Text(
                                "Makes it reachable from another machine on your tailnet, " +
                                    "including while you are out. That means a socket is " +
                                    "exposed on whatever network the phone is on, protected " +
                                    "only by the token.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = allowTailnet, onCheckedChange = { allowTailnet = it })
                    }
                    Text(
                        "Not implemented yet: address selection, a refusal to bind anything " +
                            "that is not a tailnet address, and an on-screen kill switch. " +
                            "Loopback works today.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // --- Token --------------------------------------------------
            token?.let { t ->
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Token", fontWeight = FontWeight.Bold)
                        SelectionContainer {
                            Text(t, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Probe token", t))
                            status = "Token copied"
                        }) {
                            Icon(Icons.Default.ContentCopy, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Copy token")
                        }
                        Text(
                            "Generated fresh each time you start. Only in memory, so " +
                                "closing the app or stopping the server invalidates it.",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            HorizontalDivider()

            // --- dumpsys -------------------------------------------------
            Text("Ask the phone directly", fontWeight = FontWeight.Bold)
            Text(
                "Runs a read-only dumpsys through Shizuku. This is what answers \"who " +
                    "holds the car's audio channel\" - the focus stack in dumpsys audio " +
                    "is authoritative, unlike AudioManager.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val shizukuReady = runCatching { ShizukuBridge.isAvailable() }.getOrDefault(false)
            if (!shizukuReady) {
                Text(
                    "Shizuku is not available, so these cannot run. Start Shizuku and " +
                        "grant permission in Multi-app audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ProbeCommand.entries.forEach { cmd ->
                OutlinedButton(
                    enabled = shizukuReady && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            lastTranscript = null
                            val result = withContext(Dispatchers.IO) {
                                ShizukuBridge.exec(cmd.command).map { ProbeTranscript.trim(it) }
                            }
                            lastTranscript = result.fold(
                                onSuccess = { "=== ${cmd.command} ===\n$it" },
                                onFailure = { "FAILED: ${cmd.command}\n${it.message}" },
                            )
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(cmd.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text(cmd.answers, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            lastTranscript?.let { text ->
                Button(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("Dumpsys", text))
                    status = "Transcript copied"
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.ContentCopy, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Copy transcript")
                }
                SelectionContainer {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }

    // Stop the listener when the screen goes away. Leaving a socket bound after the
    // user navigated off would be exactly the kind of silent background behaviour
    // this feature is meant to avoid.
    DisposableEffect(Unit) {
        onDispose {
            running?.stop()
        }
    }
}

/**
 * Answer one probe request from live state.
 *
 * Everything here is read-only. The `dumpsys` route resolves the query to an
 * allowlisted [ProbeCommand] and refuses anything else, so a caller can never get a
 * command of their choosing executed.
 */
private fun handleProbe(context: Context, req: ProbeServer.ProbeRequest): ProbeServer.ProbeResponse =
    when (req.route) {
        ProbeRoute.PING -> {
            val info = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }.getOrNull()
            ProbeServer.ProbeResponse(
                200,
                ProbeJson.ping(
                    versionName = info?.versionName ?: "unknown",
                    versionCode = info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else 0 } ?: 0,
                    tokensRequired = true,
                ),
            )
        }

        ProbeRoute.AUDIO -> {
            val report = AudioReportCollector.collect(context, "probe")
            ProbeServer.ProbeResponse(
                200,
                ProbeJson.audio(report, com.example.continuumstudio.audio.AudioFindings.inspect(report)),
            )
        }

        ProbeRoute.STATE -> {
            val player = com.example.continuumstudio.service.tts.AudioHub.existing()
            val pb = player?.currentPlayback()
            ProbeServer.ProbeResponse(
                200,
                ProbeJson.fields(
                    mapOf(
                        "sessionConnected" to
                            com.example.continuumstudio.service.tts.MediaSessionBridge.isConnected().toString(),
                        "focusPolicy" to (player?.focusPolicy?.storedValue ?: "none"),
                        "playbackPhase" to (pb?.phase?.name?.lowercase() ?: "idle"),
                        "segment" to pb?.let { "${it.segmentIndex + 1}/${it.segmentCount}" },
                        "projection" to
                            com.example.continuumstudio.service.tts.CarProjection.describe(context),
                    )
                ),
            )
        }

        ProbeRoute.PROBE_AUDIO -> {
            // Synchronous here because the server runs the handler off the main
            // thread; the collector's suspend variant is for UI callers.
            val result = kotlinx.coroutines.runBlocking {
                AudioReportCollector.probePlayback(context, audible = true)
            }
            ProbeServer.ProbeResponse(200, ProbeJson.fields(result.toMap()))
        }

        ProbeRoute.DUMPSYS -> {
            val cmd = ProbeCommand.byKey(req.queryValue("what"))
            if (cmd == null) {
                ProbeServer.ProbeResponse(
                    400,
                    ProbeJson.error(
                        "unknown_command",
                        "what= must be one of: ${ProbeCommand.entries.joinToString { it.key }}",
                    ),
                )
            } else {
                // ShizukuBridge.exec already returns Result, so flatten rather than
                // ending up with Result<Result<String>>.
                val out: Result<String> = runCatching { ShizukuBridge.exec(cmd.command) }
                    .getOrElse { Result.failure(it) }
                out.fold(
                    onSuccess = { text: String ->
                        ProbeServer.ProbeResponse(
                            200,
                            ProbeJson.fields(
                                mapOf(
                                    "command" to cmd.command,
                                    "answers" to cmd.answers,
                                    "transcript" to ProbeTranscript.trim(text),
                                )
                            ),
                        )
                    },
                    onFailure = { e ->
                        ProbeServer.ProbeResponse(
                            503,
                            ProbeJson.error(
                                "command_failed",
                                "${cmd.command}: ${e.message}. Is Shizuku running and granted?",
                            ),
                        )
                    },
                )
            }
        }
    }
