package com.example.continuumstudio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.BuildConfig
import com.example.continuumstudio.data.DecisionEngineConfig
import com.example.continuumstudio.viewmodel.SettingsViewModel
import com.example.continuumstudio.viewmodel.OtaUpdateViewModel
import com.example.continuumstudio.viewmodel.DialogViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    otaViewModel: OtaUpdateViewModel = viewModel(),
    dialogViewModel: DialogViewModel = viewModel(),
    onNavigateToUpdates: () -> Unit = {},
    onNavigateToGeminiCapture: () -> Unit = {},
    onNavigateToAgentCamera: () -> Unit = {},
    onSwitchToCognix: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
    // OTA Update state
    val otaUpdateState by otaViewModel.updateState.collectAsState()
    val availableOtaUpdate by otaViewModel.availableUpdate.collectAsState()
    
    // Dialog TTS state was removed with the legacy "Dialog Reading" and
    // "Activity Feed TTS" sections; the Voice section below supersedes them.
    
    var showSamsungAudioSetup by remember { mutableStateOf(false) }
    var showAudioDiagnostics by remember { mutableStateOf(false) }
    var showSpeechSettings by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.loadSettings()
    }
    
    // Toast handling
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissToast()
        }
    }
    
    if (showSamsungAudioSetup) {
        SamsungAudioSetupScreen(onBack = { showSamsungAudioSetup = false })
        return
    }
    if (showAudioDiagnostics) {
        AudioDiagnosticsScreen(onBack = { showAudioDiagnostics = false })
        return
    }
    if (showSpeechSettings) {
        // Reads the live audio preferences from the dialog view model, which
        // owns them - the dialog screen and this screen must never disagree
        // about the wait budget.
        val audioWaitMs by dialogViewModel.audioWaitMs.collectAsState()
        val speechRate by dialogViewModel.ttsSpeed.collectAsState()
        val autoRead by dialogViewModel.ttsAutoRead.collectAsState()
        val ttsEnabled by dialogViewModel.ttsEnabled.collectAsState()
        val currentScript by dialogViewModel.currentScript.collectAsState()
        val voice by dialogViewModel.voice.collectAsState()
        SpeechSettingsScreen(
            onBack = { showSpeechSettings = false },
            waitMs = audioWaitMs,
            onWaitMsChange = dialogViewModel::setAudioWaitMs,
            speechRate = speechRate,
            onSpeechRateChange = dialogViewModel::setTtsSpeed,
            autoRead = autoRead,
            onAutoReadChange = dialogViewModel::setTtsAutoRead,
            enabled = ttsEnabled,
            onEnabledChange = dialogViewModel::setTtsEnabled,
            currentScript = currentScript,
            onSpeakPreview = { script ->
                dialogViewModel.speakScriptPreview(script)
            },
            voice = voice,
            onVoiceChange = dialogViewModel::setVoice,
            onPreviewVoice = dialogViewModel::previewVoice,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Server Configuration Section
            item {
                SettingsSection(title = "Server Configuration", icon = Icons.Default.Cloud)
            }
            
            item {
                SettingsTextField(
                    label = "Dialog Server URL",
                    value = uiState.dialogServerUrl,
                    onValueChange = { viewModel.updateDialogServerUrl(it) },
                    placeholder = "http://100.102.101.72:8082"
                )
            }
            
            item {
                SettingsTextField(
                    label = "CLI Agents Server URL",
                    value = uiState.cliAgentsServerUrl,
                    onValueChange = { viewModel.updateCliAgentsServerUrl(it) },
                    placeholder = "http://100.102.101.72:4001"
                )
            }
            
            // Cloudflare Access Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Cloudflare Access", icon = Icons.Default.Security)
            }
            
            item {
                SettingsTextField(
                    label = "CF Access Client ID",
                    value = uiState.cfAccessClientId,
                    onValueChange = { viewModel.updateCfAccessClientId(it) },
                    placeholder = "Optional"
                )
            }
            
            item {
                SettingsTextField(
                    label = "CF Access Client Secret",
                    value = uiState.cfAccessClientSecret,
                    onValueChange = { viewModel.updateCfAccessClientSecret(it) },
                    placeholder = "Optional",
                    isPassword = true
                )
            }
            
            // Notification Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Notifications", icon = Icons.Default.Notifications)
            }
            
            item {
                SettingsSwitch(
                    label = "Enable Notifications",
                    description = "Show notifications for new dialogs",
                    checked = uiState.notificationsEnabled,
                    onCheckedChange = { viewModel.updateNotificationsEnabled(it) }
                )
            }
            
            item {
                SettingsSwitch(
                    label = "Vibration",
                    description = "Vibrate on new notifications",
                    checked = uiState.vibrationEnabled,
                    onCheckedChange = { viewModel.updateVibrationEnabled(it) },
                    enabled = uiState.notificationsEnabled
                )
            }
            
            item {
                SettingsSwitch(
                    label = "High Priority Alerts",
                    description = "Stronger alerts for critical dialogs",
                    checked = uiState.highPriorityAlerts,
                    onCheckedChange = { viewModel.updateHighPriorityAlerts(it) },
                    enabled = uiState.notificationsEnabled
                )
            }
            
            item {
                SettingsSwitch(
                    label = "Auto-handled Notifications",
                    description = "Show when dialogs are auto-handled",
                    checked = uiState.showAutoHandledNotifications,
                    onCheckedChange = { viewModel.updateShowAutoHandledNotifications(it) },
                    enabled = uiState.notificationsEnabled
                )
            }
            
            // Connection Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Connection", icon = Icons.Default.Wifi)
            }
            
            item {
                SettingsSwitch(
                    label = "Auto Reconnect",
                    description = "Automatically reconnect when disconnected",
                    checked = uiState.autoReconnect,
                    onCheckedChange = { viewModel.updateAutoReconnect(it) }
                )
            }
            
            item {
                SettingsSwitch(
                    label = "Auto Sync",
                    description = "Sync queued operations when back online",
                    checked = uiState.autoSync,
                    onCheckedChange = { viewModel.updateAutoSync(it) }
                )
            }
            
            // Decision Engine Settings Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Decision Engine", icon = Icons.Default.Settings)
            }

            // Voice / TTS engine status
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(
                    title = "Voice",
                    icon = Icons.AutoMirrored.Filled.VolumeUp
                )
            }

            item {
                val easyVoiceConfigured = remember { BuildConfig.EASYVOICE_API_KEY.isNotBlank() }
                SettingsInfo(
                    label = "Engine",
                    value = if (easyVoiceConfigured) "EasyVoice (Kokoro)" else "Android TTS"
                )
            }

            item {
                val easyVoiceConfigured = remember { BuildConfig.EASYVOICE_API_KEY.isNotBlank() }
                SettingsInfo(
                    label = "Fallback",
                    value = if (easyVoiceConfigured) "Android TTS if generation fails"
                           else "Android TTS only"
                )
            }

            item {
                SettingsNavigationItem(
                    label = "Speech settings",
                    description = "Wait budget, dictionary, script preview",
                    onClick = { showSpeechSettings = true }
                )
            }

            item {
                SettingsNavigationItem(
                    label = "Multi-app audio",
                    description = "Control MultiSound and per-app audio focus (needs Shizuku)",
                    onClick = { showSamsungAudioSetup = true }
                )
            }

            item {
                SettingsNavigationItem(
                    label = "Audio diagnostics",
                    description = "Inspect media session and routing when car audio misbehaves",
                    onClick = { showAudioDiagnostics = true }
                )
            }            
            item {
                SettingsSlider(
                    label = "Triage Timeout",
                    value = uiState.triageTimeoutSecs.toFloat(),
                    onValueChange = { viewModel.updateTriageTimeout(it.toLong()) },
                    valueRange = 10f..120f,
                    steps = 10,
                    valueLabel = "${uiState.triageTimeoutSecs}s"
                )
            }
            
            item {
                SettingsSlider(
                    label = "Undo Window",
                    value = uiState.undoWindowSecs.toFloat(),
                    onValueChange = { viewModel.updateUndoWindow(it.toLong()) },
                    valueRange = 5f..60f,
                    steps = 10,
                    valueLabel = "${uiState.undoWindowSecs}s"
                )
            }
            
            // App Updates Section (OTA)
            item {
                Spacer(modifier = Modifier.height(16.dp))
                OtaUpdateSection(
                    currentVersion = otaViewModel.currentVersion,
                    updateState = otaUpdateState,
                    availableUpdate = availableOtaUpdate,
                    onCheckForUpdate = { otaViewModel.checkForUpdate() },
                    onDownloadAndInstall = { otaViewModel.downloadAndInstall() },
                    onResetState = { otaViewModel.resetState() }
                )
            }
            
            // Agent Camera Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Agent Camera", icon = Icons.Default.CameraAlt)
            }
            
            item {
                SettingsNavigationItem(
                    label = "Agent Camera",
                    description = "Capture images and send to agents",
                    onClick = onNavigateToAgentCamera
                )
            }
            
            // Project Ouroboros Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "Project Ouroboros", icon = Icons.Default.SmartToy)
            }
            
            item {
                SettingsNavigationItem(
                    label = "Gemini Capture",
                    description = "Capture Gemini interactions for local AI training",
                    onClick = onNavigateToGeminiCapture
                )
            }
            
            // App Mode Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "App Mode", icon = Icons.Default.Psychology)
            }
            
            item {
                ListItem(
                    headlineContent = { Text("Switch to Cognix") },
                    supportingContent = { Text("ADHD support, nudges, patterns, ambient awareness") },
                    leadingContent = {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = onSwitchToCognix)
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "About", icon = Icons.Default.Info)
            }
            
            item {
                SettingsInfo(
                    label = "Version",
                    value = otaViewModel.currentVersion
                )
            }
            
            item {
                SettingsInfo(
                    label = "Build",
                    value = if (BuildConfig.DEBUG) "Debug" else "Release"
                )
            }
            
            // Actions
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.resetToDefaults() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Reset")
                    }
                    Button(
                        onClick = { viewModel.saveSettings() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save")
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
    HorizontalDivider()
}

@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    isPassword: Boolean = false
) {
    var passwordVisible by remember { mutableStateOf(false) }
    
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (isPassword && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (isPassword) {
            {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "Hide" else "Show"
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done
        )
    )
}

@Composable
private fun SettingsSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface 
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.5f
                    )
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
        }
    }
}

@Composable
private fun SettingsSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    enabled: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
    }
}

@Composable
private fun SettingsInfo(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsNavigationItem(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "Navigate",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
