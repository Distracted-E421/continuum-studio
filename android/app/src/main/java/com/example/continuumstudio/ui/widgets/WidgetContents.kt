package com.example.continuumstudio.ui.widgets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*
import com.example.continuumstudio.data.db.*
import com.example.continuumstudio.viewmodel.*

/**
 * Server Status Widget - Shows connection status with ping times
 */
@Composable
fun ServerStatusWidget(
    dialogConnected: Boolean,
    cliConnected: Boolean,
    latencyMs: Int? = null,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val bothConnected = dialogConnected && cliConnected
        
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    when {
                        bothConnected -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                        dialogConnected || cliConnected -> Color(0xFFFF9800).copy(alpha = 0.15f)
                        else -> Color.Gray.copy(alpha = 0.15f)
                    }
                )
                .clickable { onRefresh() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                when {
                    bothConnected -> Icons.Default.CheckCircle
                    dialogConnected || cliConnected -> Icons.Default.Warning
                    else -> Icons.Default.Close
                },
                contentDescription = null,
                tint = when {
                    bothConnected -> Color(0xFF4CAF50)
                    dialogConnected || cliConnected -> Color(0xFFFF9800)
                    else -> Color.Gray
                },
                modifier = Modifier.size(28.dp)
            )
        }
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            when {
                bothConnected -> "All Connected"
                dialogConnected -> "Dialog Only"
                cliConnected -> "CLI Only"
                else -> "Disconnected"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        if (latencyMs != null) {
            Text(
                "${latencyMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    latencyMs < 50 -> Color(0xFF4CAF50)
                    latencyMs < 150 -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
                }
            )
        }
        
        Spacer(Modifier.height(4.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusDot("Dialog", dialogConnected)
            StatusDot("CLI", cliConnected)
        }
    }
}

@Composable
private fun StatusDot(label: String, connected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF4CAF50) else Color.Gray)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Running Agents Widget - Shows active CLI agents
 */
@Composable
fun RunningAgentsWidget(
    agents: List<WidgetAgentInfo>,
    onNavigateToAgents: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToAgents() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "🤖",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${agents.size}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (agents.size == 1) "agent" else "agents",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (agents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            agents.take(3).forEach { agent ->
                AgentRow(agent)
                Spacer(Modifier.height(4.dp))
            }
            if (agents.size > 3) {
                Text(
                    "+${agents.size - 3} more",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Text(
                "No agents running",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun AgentRow(agent: WidgetAgentInfo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when (agent.status) {
                        "running" -> Color(0xFF4CAF50)
                        "completed" -> Color(0xFF2196F3)
                        "failed" -> Color(0xFFF44336)
                        else -> Color.Gray
                    }
                )
        )
        Spacer(Modifier.width(6.dp))
        Text(
            agent.workspace?.substringAfterLast("/") ?: agent.id.take(8),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Dialog Badge Widget - Shows current/pending dialogs
 */
@Composable
fun DialogBadgeWidget(
    activeDialog: WidgetDialogInfo?,
    pendingCount: Int,
    onNavigateToDialog: () -> Unit,
    onQuickRespond: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToDialog() }
    ) {
        if (activeDialog != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "💬",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "ACTIVE DIALOG",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                activeDialog.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    Text(
                        activeDialog.prompt,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (activeDialog.options.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            activeDialog.options.take(2).forEach { option ->
                                FilledTonalButton(
                                    onClick = { onQuickRespond(activeDialog.id, option.value) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(8.dp)
                                ) {
                                    Text(
                                        option.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
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
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "No Active Dialog",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (pendingCount > 0) {
                        Text(
                            "$pendingCount pending",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Activity Preview Widget - Shows recent activity events
 */
@Composable
fun ActivityPreviewWidget(
    events: List<WidgetActivityItem>,
    onNavigateToFeed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToFeed() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📰 Activity",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (events.isNotEmpty()) {
                Text(
                    "${events.size} recent",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No recent activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        } else {
            events.take(4).forEach { event ->
                ActivityEventRow(event)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun ActivityEventRow(event: WidgetActivityItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when (event.type) {
                "dialog_sent" -> "💬"
                "dialog_response" -> "✅"
                "command" -> "⚡"
                "tool_call" -> "🔧"
                "file_edit" -> "📝"
                else -> "•"
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.width(8.dp))
        Text(
            event.description,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Mode Selector Widget - Orchestrator mode toggle
 */
@Composable
fun ModeSelectorWidget(
    currentMode: OrchestratorPresenceMode,
    onModeChange: (OrchestratorPresenceMode) -> Unit
) {
    val modeDisplayName = when (currentMode) {
        OrchestratorPresenceMode.USER_ACTIVE -> "Active"
        OrchestratorPresenceMode.USER_DELEGATE -> "Delegate"
        OrchestratorPresenceMode.SPECTATOR -> "Spectator"
        OrchestratorPresenceMode.AUTONOMOUS -> "Autonomous"
    }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            when (currentMode) {
                OrchestratorPresenceMode.USER_ACTIVE -> "🟢"
                OrchestratorPresenceMode.USER_DELEGATE -> "🟡"
                OrchestratorPresenceMode.SPECTATOR -> "🟠"
                OrchestratorPresenceMode.AUTONOMOUS -> "🔴"
            },
            style = MaterialTheme.typography.headlineLarge
        )
        
        Spacer(Modifier.height(4.dp))
        
        Text(
            modeDisplayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            OrchestratorPresenceMode.entries.forEach { mode ->
                ModeButton(
                    emoji = when (mode) {
                        OrchestratorPresenceMode.USER_ACTIVE -> "🟢"
                        OrchestratorPresenceMode.USER_DELEGATE -> "🟡"
                        OrchestratorPresenceMode.SPECTATOR -> "🟠"
                        OrchestratorPresenceMode.AUTONOMOUS -> "🔴"
                    },
                    isSelected = mode == currentMode,
                    onClick = { onModeChange(mode) }
                )
            }
        }
    }
}

@Composable
private fun ModeButton(
    emoji: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            emoji,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Network Stats Widget - Real-time network status
 */
@Composable
fun NetworkStatsWidget(
    currentState: CurrentNetworkState,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRefresh() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                currentState.quality.emoji,
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    currentState.connectionType.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (currentState.networkName != null) {
                    Text(
                        currentState.networkName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Latency",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Text(
                    if (currentState.lastLatencyMs != null) "${currentState.lastLatencyMs}ms"
                    else "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = currentState.lastLatencyMs?.let {
                        when {
                            it < 50 -> Color(0xFF4CAF50)
                            it < 150 -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        }
                    } ?: Color.Gray
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "Quality",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                Text(
                    currentState.quality.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Network History Widget - Historical latency graph
 */
@Composable
fun NetworkHistoryWidget(
    measurements: List<NetworkMeasurement>,
    stats: NetworkStats?,
    selectedRange: TimeRange,
    onRangeChange: (TimeRange) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "📈 Latency History",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            
            Row {
                TimeRange.entries.take(4).forEach { range ->
                    TextButton(
                        onClick = { onRangeChange(range) },
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(
                            range.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (range == selectedRange) 
                                MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        if (measurements.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No data yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        } else {
            LatencyGraph(
                measurements = measurements,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
            )
        }
        
        if (stats != null) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Avg", "${stats.averageLatency.toInt()}ms")
                StatItem("Min", "${stats.minLatency}ms")
                StatItem("Max", "${stats.maxLatency}ms")
                StatItem("Success", "${stats.successRate.toInt()}%")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LatencyGraph(
    measurements: List<NetworkMeasurement>,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(surfaceVariant.copy(alpha = 0.3f))
    ) {
        if (measurements.isEmpty()) return@Canvas
        
        val successfulMeasurements = measurements.filter { it.success }
        if (successfulMeasurements.isEmpty()) return@Canvas
        
        val maxLatency = successfulMeasurements.maxOf { it.latencyMs }.coerceAtLeast(100)
        val minTime = measurements.minOf { it.timestamp }
        val maxTime = measurements.maxOf { it.timestamp }
        val timeRange = (maxTime - minTime).coerceAtLeast(1L)
        
        val padding = 8.dp.toPx()
        val graphWidth = size.width - padding * 2
        val graphHeight = size.height - padding * 2
        
        val path = Path()
        var isFirst = true
        
        successfulMeasurements.sortedBy { it.timestamp }.forEach { measurement ->
            val x = padding + ((measurement.timestamp - minTime).toFloat() / timeRange * graphWidth)
            val y = padding + graphHeight - (measurement.latencyMs.toFloat() / maxLatency * graphHeight)
            
            if (isFirst) {
                path.moveTo(x, y)
                isFirst = false
            } else {
                path.lineTo(x, y)
            }
        }
        
        drawPath(
            path = path,
            color = primaryColor,
            style = Stroke(width = 2.dp.toPx())
        )
        
        successfulMeasurements.sortedBy { it.timestamp }.forEach { measurement ->
            val x = padding + ((measurement.timestamp - minTime).toFloat() / timeRange * graphWidth)
            val y = padding + graphHeight - (measurement.latencyMs.toFloat() / maxLatency * graphHeight)
            
            drawCircle(
                color = primaryColor,
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
        }
        
        measurements.filter { !it.success }.forEach { measurement ->
            val x = padding + ((measurement.timestamp - minTime).toFloat() / timeRange * graphWidth)
            drawCircle(
                color = errorColor,
                radius = 4.dp.toPx(),
                center = Offset(x, size.height - padding - 4.dp.toPx())
            )
        }
    }
}

/**
 * Queue Status Widget - Shows offline queue and parked agents
 */
@Composable
fun QueueStatusWidget(
    queuedOperations: Int,
    parkedAgents: Int,
    onNavigateToQueue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToQueue() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "⏳",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(Modifier.height(4.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$queuedOperations",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (queuedOperations > 0) Color(0xFFFF9800) else Color.Gray
                )
                Text(
                    "Queued",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$parkedAgents",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (parkedAgents > 0) Color(0xFF2196F3) else Color.Gray
                )
                Text(
                    "Parked",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

/**
 * Quick Actions Widget - Shortcut buttons
 */
@Composable
fun QuickActionsWidget(onAction: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(
            emoji = "🔄",
            label = "Refresh",
            onClick = { onAction("refresh_all") }
        )
        QuickActionButton(
            emoji = "🤖",
            label = "Spawn",
            onClick = { onAction("spawn_agent") }
        )
        QuickActionButton(
            emoji = "⚙️",
            label = "Settings",
            onClick = { onAction("settings") }
        )
    }
}

@Composable
private fun QuickActionButton(
    emoji: String,
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
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                emoji,
                style = MaterialTheme.typography.titleMedium
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
