package com.example.continuumstudio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.data.DecisionEngineConfig
import com.example.continuumstudio.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
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
                    placeholder = "http://100.109.236.61:8080"
                )
            }
            
            item {
                SettingsTextField(
                    label = "CLI Agents Server URL",
                    value = uiState.cliAgentsServerUrl,
                    onValueChange = { viewModel.updateCliAgentsServerUrl(it) },
                    placeholder = "http://100.109.236.61:4001"
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
            
            // About Section
            item {
                Spacer(modifier = Modifier.height(16.dp))
                SettingsSection(title = "About", icon = Icons.Default.Info)
            }
            
            item {
                SettingsInfo(
                    label = "Version",
                    value = "1.0.0"
                )
            }
            
            item {
                SettingsInfo(
                    label = "Build",
                    value = "Debug"
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
    valueLabel: String
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
            modifier = Modifier.fillMaxWidth()
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
