package com.example.continuumstudio.ui.cognix

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.viewmodel.CognixViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CognixSettingsScreen(
    viewModel: CognixViewModel,
    onNavigateBack: () -> Unit,
    onResetAppMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var serverUrl by remember { mutableStateOf(connectionState.serverUrl) }
    var showResetDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cognix Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            // Connection section
            SettingsSection(title = "Connection") {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Cognix Server URL") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { viewModel.updateServerUrl(serverUrl) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Connect")
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (connectionState.isConnected) 
                            "Connected (${connectionState.latencyMs}ms)" 
                        else 
                            "Disconnected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connectionState.isConnected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    
                    TextButton(onClick = { viewModel.connect() }) {
                        Text("Test Connection")
                    }
                }
            }
            
            // Nudge settings section
            SettingsSection(title = "Nudges") {
                SettingsToggle(
                    title = "Enable Nudges",
                    subtitle = "Receive proactive reminders and check-ins",
                    checked = settings.enableNudges,
                    onCheckedChange = { viewModel.updateEnableNudges(it) }
                )
                
                SettingsToggle(
                    title = "Sound",
                    subtitle = "Play sound for nudges",
                    checked = settings.nudgeSound,
                    onCheckedChange = { viewModel.updateNudgeSound(it) }
                )
                
                SettingsToggle(
                    title = "Vibration",
                    subtitle = "Vibrate for nudges",
                    checked = settings.nudgeVibration,
                    onCheckedChange = { viewModel.updateNudgeVibration(it) }
                )
            }
            
            // Privacy section
            SettingsSection(title = "Privacy") {
                SettingsToggle(
                    title = "Local Processing Only",
                    subtitle = "Keep all data on-device (no cloud sync)",
                    checked = settings.localProcessingOnly,
                    onCheckedChange = { viewModel.updateLocalProcessingOnly(it) }
                )
                
                SettingsToggle(
                    title = "Screen Capture",
                    subtitle = "Allow periodic screen captures for context",
                    checked = settings.screenCapture,
                    onCheckedChange = { viewModel.updateScreenCapture(it) }
                )
            }
            
            // App section
            SettingsSection(title = "App") {
                ListItem(
                    headlineContent = { Text("Switch to Continuum Studio") },
                    supportingContent = { Text("Change app mode") },
                    leadingContent = {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                    },
                    modifier = Modifier.clickable(onClick = { showResetDialog = true })
                )
                
                ListItem(
                    headlineContent = { Text("Version") },
                    supportingContent = { Text("1.10.0 (Cognix integration)") },
                    leadingContent = {
                        Icon(Icons.Default.Info, contentDescription = null)
                    }
                )
            }
        }
    }
    
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Switch App Mode?") },
            text = { Text("This will restart the app in Continuum Studio mode. You can switch back anytime from settings.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    onResetAppMode()
                }) {
                    Text("Switch")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        content()
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

