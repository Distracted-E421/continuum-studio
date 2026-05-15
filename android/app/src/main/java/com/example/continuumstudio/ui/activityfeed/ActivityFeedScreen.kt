package com.example.continuumstudio.ui.activityfeed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.data.*
import com.example.continuumstudio.viewmodel.ActivityFeedViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityFeedScreen(
    viewModel: ActivityFeedViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.connect()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity Feed") },
                actions = {
                    ConnectionIndicator(isConnected = uiState.isConnected)
                    IconButton(onClick = { viewModel.clearEvents() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                    IconButton(onClick = {
                        if (uiState.isConnected) viewModel.disconnect() else viewModel.connect()
                    }) {
                        Icon(
                            if (uiState.isConnected) Icons.Default.CloudOff else Icons.Default.Cloud,
                            contentDescription = if (uiState.isConnected) "Disconnect" else "Connect"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter chips
            FilterBar(
                filters = uiState.filters,
                onToggleFilter = { viewModel.toggleFilter(it) }
            )
            
            // Events list
            if (uiState.events.isEmpty()) {
                EmptyState()
            } else {
                val filteredEvents = uiState.events.filter { event ->
                    when (event) {
                        is ActivityEvent.Command -> ActivityFilter.COMMAND in uiState.filters
                        is ActivityEvent.DialogSent, is ActivityEvent.DialogResponse -> ActivityFilter.DIALOG in uiState.filters
                        is ActivityEvent.ToolCall -> ActivityFilter.TOOL_CALL in uiState.filters
                        is ActivityEvent.FileEdit -> ActivityFilter.FILE_EDIT in uiState.filters
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredEvents, key = { "${it.timestamp}-${it.hashCode()}" }) { event ->
                        EventCard(
                            event = event,
                            isExpanded = uiState.expandedEventId == "${event.timestamp}-${event.hashCode()}",
                            onToggleExpand = {
                                val id = "${event.timestamp}-${event.hashCode()}"
                                viewModel.expandEvent(if (uiState.expandedEventId == id) null else id)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    filters: Set<ActivityFilter>,
    onToggleFilter: (ActivityFilter) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ActivityFilter.entries) { filter ->
            FilterChip(
                selected = filter in filters,
                onClick = { onToggleFilter(filter) },
                label = { Text(filter.displayName) },
                leadingIcon = if (filter in filters) {
                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null
            )
        }
    }
}

@Composable
private fun EventCard(
    event: ActivityEvent,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val (icon, iconColor, title, subtitle) = when (event) {
        is ActivityEvent.Command -> {
            val color = when {
                event.exitCode == 0 -> Color(0xFF4CAF50)
                event.exitCode != null -> Color(0xFFF44336)
                else -> Color(0xFF2196F3)
            }
            Quadruple(
                Icons.Default.Terminal,
                color,
                "Command",
                event.command.take(50)
            )
        }
        is ActivityEvent.DialogSent -> Quadruple(
            Icons.Default.QuestionAnswer,
            Color(0xFFFF9800),
            "Dialog: ${event.title}",
            "Agent: ${event.agentId.take(8)}"
        )
        is ActivityEvent.DialogResponse -> Quadruple(
            Icons.Default.Reply,
            Color(0xFF9C27B0),
            "Response",
            "Selection: ${event.selection}"
        )
        is ActivityEvent.ToolCall -> {
            val color = if (event.status == "completed") Color(0xFF4CAF50) else Color(0xFF2196F3)
            Quadruple(
                Icons.Default.Build,
                color,
                "Tool: ${event.toolName}",
                "Status: ${event.status}"
            )
        }
        is ActivityEvent.FileEdit -> {
            val color = when (event.action) {
                "write" -> Color(0xFF4CAF50)
                "delete" -> Color(0xFFF44336)
                else -> Color(0xFF2196F3)
            }
            Quadruple(
                Icons.Default.Description,
                color,
                "File: ${event.action}",
                event.filePath.substringAfterLast("/")
            )
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = formatTimestamp(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Expanded details
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                
                when (event) {
                    is ActivityEvent.Command -> {
                        Text(
                            text = event.command,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(8.dp)
                        )
                        event.durationMs?.let {
                            Text(
                                text = "Duration: ${it}ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        event.exitCode?.let {
                            Text(
                                text = "Exit code: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (it == 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                        }
                    }
                    is ActivityEvent.DialogSent -> {
                        Text("Agent ID: ${event.agentId}", style = MaterialTheme.typography.bodySmall)
                        Text("Dialog ID: ${event.dialogId}", style = MaterialTheme.typography.bodySmall)
                    }
                    is ActivityEvent.DialogResponse -> {
                        Text("Dialog ID: ${event.dialogId}", style = MaterialTheme.typography.bodySmall)
                        Text("Selection: ${event.selection}", style = MaterialTheme.typography.bodySmall)
                    }
                    is ActivityEvent.ToolCall -> {
                        Text("Agent ID: ${event.agentId}", style = MaterialTheme.typography.bodySmall)
                        Text("Tool: ${event.toolName}", style = MaterialTheme.typography.bodySmall)
                        Text("Status: ${event.status}", style = MaterialTheme.typography.bodySmall)
                    }
                    is ActivityEvent.FileEdit -> {
                        Text("Agent ID: ${event.agentId}", style = MaterialTheme.typography.bodySmall)
                        Text("File: ${event.filePath}", style = MaterialTheme.typography.bodySmall)
                        Text("Action: ${event.action}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionIndicator(isConnected: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 8.dp)
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = if (isConnected) "Connected" else "Disconnected",
            tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFFF44336),
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Timeline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No activity yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = "Events will appear here in real-time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatTimestamp(timestamp: String): String {
    return try {
        val instant = java.time.Instant.parse(timestamp)
        val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        "${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}:${local.second.toString().padStart(2, '0')}"
    } catch (e: Exception) {
        timestamp.takeLast(8)
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
