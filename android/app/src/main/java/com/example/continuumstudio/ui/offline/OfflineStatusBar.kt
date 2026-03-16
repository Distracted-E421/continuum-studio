package com.example.continuumstudio.ui.offline

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.continuumstudio.data.*

/**
 * Compact status bar showing offline state and pending operations.
 */
@Composable
fun OfflineStatusBar(
    state: OfflineUiState,
    onSyncClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (state.state) {
        OfflineState.ONLINE -> MaterialTheme.colorScheme.surfaceVariant
        OfflineState.PARTIALLY_OFFLINE -> Color(0xFFFFF3E0) // Light orange
        OfflineState.FULLY_OFFLINE -> Color(0xFFFFEBEE) // Light red
    }
    
    val statusColor = when (state.state) {
        OfflineState.ONLINE -> Color(0xFF4CAF50) // Green
        OfflineState.PARTIALLY_OFFLINE -> Color(0xFFFF9800) // Orange
        OfflineState.FULLY_OFFLINE -> Color(0xFFF44336) // Red
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, RoundedCornerShape(4.dp))
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Status text
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.pendingCount > 0) {
                    Text(
                        text = "${state.pendingCount} pending operations",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Sync button
            if (state.pendingCount > 0 && state.state == OfflineState.ONLINE) {
                if (state.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onSyncClick) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Sync",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Decision engine triage panel.
 */
@Composable
fun TriagePanel(
    state: DecisionEngineUiState,
    onApprove: (String) -> Unit,
    onDecline: (String) -> Unit,
    onClaim: (String) -> Unit,
    onModeChange: (OrchestratorPresenceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Mode selector
        ModeSelector(
            currentMode = state.mode,
            onModeChange = onModeChange
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Triage queue
        if (state.triageQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No dialogs in triage queue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.triageQueue, key = { it.dialog.id }) { item ->
                    TriageItemCard(
                        item = item,
                        onApprove = { onApprove(item.dialog.id) },
                        onDecline = { onDecline(item.dialog.id) },
                        onClaim = { onClaim(item.dialog.id) }
                    )
                }
            }
        }
    }
}

/**
 * Orchestrator mode selector.
 */
@Composable
fun ModeSelector(
    currentMode: OrchestratorPresenceMode,
    onModeChange: (OrchestratorPresenceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ModeButton(
            mode = OrchestratorPresenceMode.USER_ACTIVE,
            emoji = "🟢",
            label = "Active",
            isSelected = currentMode == OrchestratorPresenceMode.USER_ACTIVE,
            onClick = { onModeChange(OrchestratorPresenceMode.USER_ACTIVE) }
        )
        ModeButton(
            mode = OrchestratorPresenceMode.USER_DELEGATE,
            emoji = "🟡",
            label = "Delegate",
            isSelected = currentMode == OrchestratorPresenceMode.USER_DELEGATE,
            onClick = { onModeChange(OrchestratorPresenceMode.USER_DELEGATE) }
        )
        ModeButton(
            mode = OrchestratorPresenceMode.SPECTATOR,
            emoji = "🟠",
            label = "Spectator",
            isSelected = currentMode == OrchestratorPresenceMode.SPECTATOR,
            onClick = { onModeChange(OrchestratorPresenceMode.SPECTATOR) }
        )
        ModeButton(
            mode = OrchestratorPresenceMode.AUTONOMOUS,
            emoji = "🔴",
            label = "Auto",
            isSelected = currentMode == OrchestratorPresenceMode.AUTONOMOUS,
            onClick = { onModeChange(OrchestratorPresenceMode.AUTONOMOUS) }
        )
    }
}

@Composable
private fun ModeButton(
    mode: OrchestratorPresenceMode,
    emoji: String,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surface,
        tonalElevation = if (isSelected) 4.dp else 1.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleMedium)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

@Composable
fun TriageItemCard(
    item: TriageItem,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onClaim: () -> Unit,
    modifier: Modifier = Modifier
) {
    val remainingSecs = item.remainingMs / 1000
    val stateColor = when (item.state) {
        TriageState.AUTO_APPROVE -> Color(0xFF4CAF50)
        TriageState.AUTO_DECLINE -> Color(0xFFF44336)
        TriageState.MANUAL -> Color(0xFF2196F3)
        TriageState.PAUSED -> Color(0xFF9E9E9E)
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Priority badge
                    val priority = parseDialogPriority(item.dialog.priority)
                    if (priority != DialogPriority.NORMAL) {
                        Text(
                            text = priority.emoji(),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    // Source badge
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = parseDialogSource(item.dialog.source).displayName(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                
                // Countdown
                if (item.state == TriageState.AUTO_APPROVE || item.state == TriageState.AUTO_DECLINE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = stateColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${remainingSecs}s",
                            style = MaterialTheme.typography.labelSmall,
                            color = stateColor
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Title
            Text(
                text = item.dialog.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            
            // Prompt preview
            Text(
                text = item.dialog.prompt.take(100) + if (item.dialog.prompt.length > 100) "..." else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Reasoning
            Text(
                text = item.reasoning,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClaim) {
                    Text("Claim")
                }
                if (item.state == TriageState.AUTO_APPROVE && item.suggestedResponse != null) {
                    TextButton(onClick = onDecline) {
                        Text("Override")
                    }
                    Button(onClick = onApprove) {
                        Text("Approve: ${item.suggestedResponse}")
                    }
                }
            }
        }
    }
}

private fun parseDialogPriority(priority: String?): DialogPriority {
    return when (priority?.lowercase()) {
        "low" -> DialogPriority.LOW
        "high" -> DialogPriority.HIGH
        "critical" -> DialogPriority.CRITICAL
        else -> DialogPriority.NORMAL
    }
}

private fun parseDialogSource(source: String?): DialogSource {
    return when (source?.lowercase()) {
        "orchestrator" -> DialogSource.ORCHESTRATOR
        "session_agent" -> DialogSource.SESSION_AGENT
        "sub_agent" -> DialogSource.SUB_AGENT
        else -> DialogSource.EXTERNAL
    }
}
