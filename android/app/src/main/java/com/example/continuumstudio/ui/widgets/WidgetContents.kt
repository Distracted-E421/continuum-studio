package com.example.continuumstudio.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*

/**
 * Connection status widget - shows server connection state
 */
@Composable
fun ConnectionStatusWidget(connectionState: ConnectionState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (connectionState.isConnected) Color(0xFF4CAF50).copy(alpha = 0.2f)
                    else Color.Gray.copy(alpha = 0.2f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (connectionState.isConnected) Icons.Default.Check else Icons.Default.Close,
                contentDescription = null,
                tint = if (connectionState.isConnected) Color(0xFF4CAF50) else Color.Gray
            )
        }
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            if (connectionState.isConnected) "Connected" else "Disconnected",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        if (connectionState.isConnected) {
            Text(
                connectionState.serverUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (connectionState.errorMessage != null) {
            Text(
                connectionState.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Dialog queue widget - shows pending dialogs
 */
@Composable
fun DialogQueueWidget(
    dialogState: DialogUiState,
    onNavigateToDialog: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDialog() }
    ) {
        if (dialogState.activeDialog != null) {
            // Active dialog preview
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        dialogState.activeDialog.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        dialogState.activeDialog.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // No active dialog
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "No pending dialogs",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(Modifier.height(4.dp))
        
        // Queue count
        Row {
            StatusChip(
                label = "Queue: ${dialogState.queueCount}",
                color = if (dialogState.queueCount > 0) MaterialTheme.colorScheme.secondary else Color.Gray
            )
            Spacer(Modifier.width(4.dp))
            StatusChip(
                label = if (dialogState.holdMode) "Hold ON" else "Hold OFF",
                color = if (dialogState.holdMode) Color(0xFFFF9800) else Color.Gray
            )
        }
    }
}

/**
 * Harness status widget - shows Synapsix harnesses
 */
@Composable
fun HarnessStatusWidget(
    harnesses: List<HarnessInfo>,
    isLoading: Boolean
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else if (harnesses.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No harnesses running",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            harnesses.take(4).forEach { harness ->
                HarnessRow(harness)
            }
            if (harnesses.size > 4) {
                Text(
                    "+${harnesses.size - 4} more",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun HarnessRow(harness: HarnessInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    when (harness.status) {
                        "healthy" -> Color(0xFF4CAF50)
                        "warning" -> Color(0xFFFF9800)
                        else -> Color.Red
                    }
                )
        )
        
        Spacer(Modifier.width(8.dp))
        
        Column(Modifier.weight(1f)) {
            Text(
                harness.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                harness.type,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Type badge
        HarnessTypeBadge(harness.type)
    }
}

@Composable
fun HarnessTypeBadge(type: String) {
    val color = when {
        type.contains("cursor", ignoreCase = true) -> Color(0xFF9C27B0)
        type.contains("android", ignoreCase = true) -> Color(0xFF4CAF50)
        type.contains("godot", ignoreCase = true) -> Color(0xFF2196F3)
        else -> Color.Gray
    }
    
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(4.dp)
    ) {
        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
    }
}

/**
 * Service discovery widget - shows DNS-SD services
 */
@Composable
fun ServiceDiscoveryWidget(
    services: List<ServiceInfo>,
    isLoading: Boolean
) {
    if (isLoading) {
        Box(
            modifier = Modifier.fillMaxWidth().height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    } else if (services.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No services discovered",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            services.take(5).forEach { service ->
                ServiceRow(service)
            }
            if (services.size > 5) {
                Text(
                    "+${services.size - 5} more services",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun ServiceRow(service: ServiceInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Health indicator
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when (service.health) {
                        "healthy" -> Color(0xFF4CAF50)
                        "warning" -> Color(0xFFFF9800)
                        else -> Color.Red
                    }
                )
        )
        
        Spacer(Modifier.width(8.dp))
        
        Column(Modifier.weight(1f)) {
            Text(
                service.displayName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${service.host}:${service.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Service type
        Text(
            service.serviceType.replace("_", " ").take(10),
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
    }
}

/**
 * Node health widget - shows BEAM cluster status
 */
@Composable
fun NodeHealthWidget(connectionState: ConnectionState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Settings,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = if (connectionState.isConnected) MaterialTheme.colorScheme.primary else Color.Gray
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            if (connectionState.isConnected) "Node Online" else "Node Offline",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        
        // TODO: Add actual cluster info when available
        Text(
            "Synapsix @ Obsidian",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Quick actions widget - shortcut buttons
 */
@Composable
fun QuickActionsWidget(onAction: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(
            icon = Icons.AutoMirrored.Filled.Send,
            label = "Cursor",
            onClick = { onAction("start_cursor") }
        )
        QuickActionButton(
            icon = Icons.Default.Settings,
            label = "Android",
            onClick = { onAction("start_android") }
        )
        QuickActionButton(
            icon = Icons.Default.Refresh,
            label = "Godot",
            onClick = { onAction("start_godot") }
        )
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/**
 * Agent stream widget - live AI conversation (placeholder)
 */
@Composable
fun AgentStreamWidget() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = Color.Gray
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Agent stream coming soon",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

/**
 * Reusable status chip
 */
@Composable
fun StatusChip(
    label: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
