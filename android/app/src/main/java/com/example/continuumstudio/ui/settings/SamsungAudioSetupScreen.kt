package com.example.continuumstudio.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.audio.MultiSoundController
import com.example.continuumstudio.audio.ShizukuBridge
import kotlinx.coroutines.launch

/**
 * Multi-app audio setup and control.
 *
 * This is a working control panel, not a list of instructions. It drives
 * MultiSound through Shizuku (ADB shell, UID 2000) - see MultiSoundController
 * for what was verified to be possible without root, and what is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungAudioSetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { MultiSoundController(context) }

    val isSamsung = remember { controller.isSupportedDevice() }
    val hasSoundAssistant = remember { controller.isSoundAssistantInstalled() }

    var shizukuReady by remember { mutableStateOf(false) }
    var multiAudio by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var suppressed by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        shizukuReady = controller.isReady()
        if (shizukuReady) {
            multiAudio = controller.isMultiAudioFocusEnabled()
            val off = MultiSoundController.COMMON_MEDIA_APPS.mapNotNull { (pkg, _) ->
                if (controller.isFocusStealingAllowed(pkg) == false) pkg else null
            }.toSet()
            suppressed = off
        }
    }

    // Shizuku permission prompt. The grant arrives asynchronously through
    // Shizuku's listener, so refresh state when it lands.
    DisposableEffect(Unit) {
        ShizukuBridge.ensureListener()
        ShizukuBridge.onPermissionResult = { granted ->
            status = if (granted) "Shizuku permission granted" else "Shizuku permission denied"
            scope.launch { refresh() }
        }
        onDispose { ShizukuBridge.onPermissionResult = null }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Multi-App Audio") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!isSamsung) {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFF9800))
                        Text("MultiSound is a Samsung One UI feature. This device does not have it.")
                    }
                }
                return@Column
            }

            // --- Shizuku gate -------------------------------------------------
            Card(
                colors = CardDefaults.cardColors(
                    if (shizukuReady) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (shizukuReady) Icons.Default.CheckCircle else Icons.Default.Warning,
                            null,
                            tint = if (shizukuReady) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        )
                        Text(
                            if (shizukuReady) "Shizuku connected" else "Shizuku permission required",
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        if (shizukuReady)
                            "Controlling audio settings with ADB shell privileges (no root)."
                        else
                            "This screen changes system settings through Shizuku. " +
                                "Open Shizuku and grant Continuum permission, then tap Recheck.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!shizukuReady) {
                            Button(onClick = {
                                ShizukuBridge.requestPermission()
                            }) { Text("Request permission") }
                        }
                        OutlinedButton(
                            onClick = { scope.launch { busy = true; refresh(); busy = false } },
                            enabled = !busy
                        ) { Text("Recheck") }
                    }
                }
            }

            // --- MultiSound toggle -------------------------------------------
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.GraphicEq, null)
                        Text("Multi-app audio", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Lets several apps hold audio focus at once, so apps that do not " +
                            "decline focus do not pause each other.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    // Important distinction. Dialog speech deliberately requests no
                    // focus at all, so it mixes with music without this setting - the
                    // mixing happens below the focus system. Claiming otherwise sent
                    // the user to change the wrong thing when audio misbehaved.
                    Text(
                        "Dialog reading does not need this. It asks for no audio focus, " +
                            "so it already plays over music. Multi-app audio is for apps " +
                            "that would otherwise interrupt each other.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = multiAudio,
                        enabled = shizukuReady && !busy,
                        onCheckedChange = { want ->
                            scope.launch {
                                busy = true
                                controller.setMultiAudioFocus(want)
                                    .onSuccess {
                                        multiAudio = want
                                        status = if (want) "Multi-app audio on" else "Multi-app audio off"
                                    }
                                    .onFailure { status = it.message }
                                busy = false
                            }
                        }
                    )
                }
            }

            // --- Focus stealing ----------------------------------------------
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Stop apps taking over audio", fontWeight = FontWeight.Bold)
                    Text(
                        "Suppressing an app here records its current setting first, so " +
                            "Clear restores exactly what was there rather than guessing. " +
                            "Browsers are left alone: making your music keep playing is " +
                            "handled by the dialog audio setting, not by changing Vivaldi.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    MultiSoundController.COMMON_MEDIA_APPS.forEach { (pkg, label) ->
                        val isSuppressed = pkg in suppressed
                        val isBrowser = pkg == "com.vivaldi.browser"
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                                if (isBrowser) {
                                    Text(
                                        "Left as-is: dialog audio already avoids interrupting it",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Switch(
                                checked = isSuppressed,
                                enabled = shizukuReady && !busy,
                                onCheckedChange = { wantSuppress ->
                                    scope.launch {
                                        busy = true
                                        controller.setFocusStealingAllowed(pkg, allowed = !wantSuppress)
                                            .onSuccess {
                                                suppressed = if (wantSuppress) suppressed + pkg
                                                else suppressed - pkg
                                            }
                                            .onFailure { status = it.message }
                                        busy = false
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        // Only the apps that actually compete with a dialog, not
                        // every media app on the phone.
                        controller.applyCarProfile(MultiSoundController.DIALOG_COMPETITORS.map { it.first })
                            .onSuccess {
                                status = "Car profile applied ($it settings)"
                                refresh()
                            }
                            .onFailure { status = it.message }
                        busy = false
                    }
                },
                enabled = shizukuReady && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Apply car profile") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        busy = true
                        controller.clearFocusOverrides(
                            MultiSoundController.COMMON_MEDIA_APPS.map { it.first }
                        )
                            .onSuccess { restored ->
                                status = if (restored == 0) {
                                    "Nothing to restore - no app was changed by this screen"
                                } else {
                                    "Restored $restored app(s) to their previous setting"
                                }
                            }
                            .onFailure { status = it.message }
                        refresh()
                        busy = false
                    }
                },
                enabled = shizukuReady && !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Clear focus overrides") }

            status?.let {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }

            // --- Honest limitation note --------------------------------------
            Text(
                "Per-app volume levels are held in memory by the system and cannot be " +
                    "changed without root, so they still need Sound Assistant itself. " +
                    "Everything above works without root.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (hasSoundAssistant) {
                OutlinedButton(
                    onClick = { openSoundAssistant(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Sound Assistant") }
            }
            OutlinedButton(
                onClick = { openSoundSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Open system sound settings") }
        }
    }
}


private fun openSoundAssistant(context: android.content.Context) {
    try {
        context.packageManager
            .getLaunchIntentForPackage(MultiSoundController.SOUND_ASSISTANT_PKG)
            ?.let { context.startActivity(it) }
            ?: openSoundSettings(context)
    } catch (e: Exception) {
        openSoundSettings(context)
    }
}

private fun openSoundSettings(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
