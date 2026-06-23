package com.example.continuumstudio.ui.cognix

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.viewmodel.CognixViewModel
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CognixHomeScreen(
    viewModel: CognixViewModel,
    onNavigateToCapture: () -> Unit,
    onNavigateToPatterns: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onSwitchMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val nudges by viewModel.activeNudges.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cognix") },
                actions = {
                    // Connection indicator
                    Icon(
                        imageVector = if (connectionState.isConnected) 
                            Icons.Default.Cloud 
                        else 
                            Icons.Default.CloudOff,
                        contentDescription = if (connectionState.isConnected) "Connected" else "Disconnected",
                        tint = if (connectionState.isConnected)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    
                    IconButton(onClick = onSwitchMode) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = "Switch to Continuum"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCapture,
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "Capture")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Greeting card
            item {
                GreetingCard()
            }
            
            // Connection status
            if (!connectionState.isConnected) {
                item {
                    ConnectionWarningCard(
                        serverUrl = connectionState.serverUrl,
                        onRetry = { viewModel.connect() }
                    )
                }
            }
            
            // Quick actions
            item {
                QuickActionsRow(
                    onCapture = onNavigateToCapture,
                    onPatterns = onNavigateToPatterns,
                    onSettings = onNavigateToSettings
                )
            }
            
            // Active nudges section
            item {
                Text(
                    text = "Active Nudges",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            if (nudges.isEmpty()) {
                item {
                    EmptyNudgesCard()
                }
            } else {
                items(nudges) { nudge ->
                    NudgeCard(
                        nudge = nudge,
                        onDismiss = { viewModel.dismissNudge(nudge.id) },
                        onSnooze = { viewModel.snoozeNudge(nudge.id, 15) }
                    )
                }
            }
            
            // Recent patterns section
            item {
                Text(
                    text = "Recent Insights",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            
            item {
                PatternInsightsCard(
                    patterns = uiState.recentPatterns
                )
            }
        }
    }
}

@Composable
private fun GreetingCard() {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "How can I help you focus today?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun ConnectionWarningCard(
    serverUrl: String,
    onRetry: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Not connected",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = serverUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                )
            }
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onCapture: () -> Unit,
    onPatterns: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QuickActionButton(
            icon = Icons.Default.CameraAlt,
            label = "Capture",
            onClick = onCapture,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            icon = Icons.Default.Timeline,
            label = "Patterns",
            onClick = onPatterns,
            modifier = Modifier.weight(1f)
        )
        QuickActionButton(
            icon = Icons.Default.Settings,
            label = "Settings",
            onClick = onSettings,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedCard(
        onClick = onClick,
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun EmptyNudgesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "All clear!",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "No active nudges right now",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

data class Nudge(
    val id: String,
    val title: String,
    val message: String,
    val priority: NudgePriority,
    val timestamp: Long
)

enum class NudgePriority { LOW, MEDIUM, HIGH }

@Composable
private fun NudgeCard(
    nudge: Nudge,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    val priorityColor = when (nudge.priority) {
        NudgePriority.HIGH -> MaterialTheme.colorScheme.error
        NudgePriority.MEDIUM -> MaterialTheme.colorScheme.tertiary
        NudgePriority.LOW -> MaterialTheme.colorScheme.secondary
    }
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.NotificationsActive,
                    contentDescription = null,
                    tint = priorityColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = nudge.title,
                    style = MaterialTheme.typography.titleSmall
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = nudge.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onSnooze) {
                    Text("Snooze 15m")
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
        }
    }
}

@Composable
private fun PatternInsightsCard(
    patterns: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            if (patterns.isEmpty()) {
                Text(
                    text = "Keep using Cognix to discover your patterns",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                patterns.forEach { pattern ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Insights,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pattern,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
