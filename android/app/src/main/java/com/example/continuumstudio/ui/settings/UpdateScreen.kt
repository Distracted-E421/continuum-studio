package com.example.continuumstudio.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.update.*
import com.example.continuumstudio.viewmodel.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(
    viewModel: UpdateViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Updates") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.checkForUpdates(forceCheck = true) },
                        enabled = !uiState.isChecking
                    ) {
                        if (uiState.isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            // Current version info
            CurrentVersionCard(
                currentVersion = uiState.currentVersion,
                lastCheckTime = uiState.lastCheckTime
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Available update card
            AnimatedVisibility(visible = uiState.availableUpdate != null) {
                uiState.availableUpdate?.let { update ->
                    AvailableUpdateCard(
                        update = update,
                        downloadState = uiState.downloadState,
                        onDownload = { viewModel.downloadUpdate() },
                        onInstall = { viewModel.installUpdate() },
                        onCancel = { viewModel.cancelDownload() },
                        onDismiss = { viewModel.dismissUpdate() }
                    )
                }
            }
            
            // Check errors
            if (uiState.checkErrors.isNotEmpty()) {
                CheckErrorsCard(errors = uiState.checkErrors)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Settings section
            UpdateSettingsSection(
                settings = uiState.settings,
                onAutoCheckChanged = viewModel::updateAutoCheckEnabled,
                onCheckOnCellularChanged = viewModel::updateCheckOnCellular,
                onAutoDownloadChanged = viewModel::updateAutoDownload,
                onIncludePreReleasesChanged = viewModel::updateIncludePreReleases,
                onSynapsixEndpointChanged = viewModel::updateSynapsixEndpoint,
                onGiteaEndpointChanged = viewModel::updateGiteaEndpoint,
                onGithubRepoChanged = viewModel::updateGithubRepo,
                onSourceToggled = viewModel::toggleSource
            )
        }
    }
}

@Composable
private fun CurrentVersionCard(
    currentVersion: String,
    lastCheckTime: Long?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        "Continuum Studio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Version $currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            lastCheckTime?.let { time ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Last checked: ${formatTime(time)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AvailableUpdateCard(
    update: UpdateInfo,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Update Available",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Version ${update.versionName}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                if (update.isPreRelease) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Pre-release") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Source and size info
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Source: ${update.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                if (update.fileSize > 0) {
                    Text(
                        "Size: ${formatFileSize(update.fileSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            
            // Release notes
            if (update.releaseNotes.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    update.releaseNotes,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Download state / actions
            when (downloadState) {
                is DownloadState.Idle -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Download")
                        }
                        
                        OutlinedButton(onClick = onDismiss) {
                            Text("Skip")
                        }
                    }
                }
                
                is DownloadState.Downloading -> {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Downloading... ${downloadState.progress}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${formatFileSize(downloadState.downloadedBytes)} / ${formatFileSize(downloadState.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        LinearProgressIndicator(
                            progress = { downloadState.progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        TextButton(onClick = onCancel) {
                            Text("Cancel")
                        }
                    }
                }
                
                is DownloadState.Downloaded -> {
                    Button(
                        onClick = onInstall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.InstallMobile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Install Update")
                    }
                }
                
                is DownloadState.Installing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Installing...")
                    }
                }
                
                is DownloadState.Error -> {
                    Column {
                        Text(
                            "Error: ${downloadState.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onDownload) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckErrorsCard(
    errors: List<Pair<UpdateSource, String>>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Some sources failed",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            errors.forEach { (source, message) ->
                Text(
                    "${source.displayName}: $message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun UpdateSettingsSection(
    settings: UpdateSettings,
    onAutoCheckChanged: (Boolean) -> Unit,
    onCheckOnCellularChanged: (Boolean) -> Unit,
    onAutoDownloadChanged: (Boolean) -> Unit,
    onIncludePreReleasesChanged: (Boolean) -> Unit,
    onSynapsixEndpointChanged: (String) -> Unit,
    onGiteaEndpointChanged: (String) -> Unit,
    onGithubRepoChanged: (String) -> Unit,
    onSourceToggled: (UpdateSource, Boolean) -> Unit
) {
    var expandedSources by remember { mutableStateOf(false) }
    
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            "Update Settings",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        
        // Auto-check toggle
        SettingSwitch(
            title = "Check automatically",
            subtitle = "Check for updates periodically",
            checked = settings.autoCheckEnabled,
            onCheckedChange = onAutoCheckChanged
        )
        
        // Cellular toggle
        SettingSwitch(
            title = "Check on cellular",
            subtitle = "Allow update checks over mobile data",
            checked = settings.checkOnCellular,
            onCheckedChange = onCheckOnCellularChanged
        )
        
        // Auto-download toggle
        SettingSwitch(
            title = "Auto-download",
            subtitle = "Download updates automatically (Wi-Fi only)",
            checked = settings.autoDownload,
            onCheckedChange = onAutoDownloadChanged
        )
        
        // Pre-releases toggle
        SettingSwitch(
            title = "Include pre-releases",
            subtitle = "Show beta and pre-release versions",
            checked = settings.includePreReleases,
            onCheckedChange = onIncludePreReleasesChanged
        )
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
        
        // Update sources section
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Update Sources",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = { expandedSources = !expandedSources }) {
                Icon(
                    if (expandedSources) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expandedSources) "Collapse" else "Expand"
                )
            }
        }
        
        Text(
            "Priority: Synapsix → Gitea → GitHub",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Source toggles
        UpdateSource.entries.forEach { source ->
            val isEnabled = settings.enabledSources.contains(source.name)
            SettingSwitch(
                title = source.displayName,
                subtitle = when (source) {
                    UpdateSource.SYNAPSIX -> "Get updates via Synapsix dialog"
                    UpdateSource.GITEA -> "Self-hosted releases"
                    UpdateSource.GITHUB -> "Public GitHub releases"
                },
                checked = isEnabled,
                onCheckedChange = { onSourceToggled(source, it) }
            )
        }
        
        // Expanded source configuration
        AnimatedVisibility(visible = expandedSources) {
            Column {
                Spacer(modifier = Modifier.height(16.dp))
                
                // Synapsix endpoint
                OutlinedTextField(
                    value = settings.synapsixEndpoint,
                    onValueChange = onSynapsixEndpointChanged,
                    label = { Text("Synapsix Endpoint") },
                    placeholder = { Text("http://host:port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Gitea endpoint
                OutlinedTextField(
                    value = settings.giteaEndpoint,
                    onValueChange = onGiteaEndpointChanged,
                    label = { Text("Gitea Releases API") },
                    placeholder = { Text("http://host:port/api/v1/repos/owner/repo/releases") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // GitHub repo
                OutlinedTextField(
                    value = settings.githubRepo,
                    onValueChange = onGithubRepoChanged,
                    label = { Text("GitHub Repository") },
                    placeholder = { Text("owner/repo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
