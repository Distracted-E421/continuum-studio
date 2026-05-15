package com.example.continuumstudio.ui.widgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*
import com.example.continuumstudio.data.db.*
import com.example.continuumstudio.viewmodel.*

/**
 * Widget-specific data types for clean abstraction
 */
data class WidgetAgentInfo(
    val id: String,
    val workspace: String?,
    val status: String
)

data class WidgetDialogInfo(
    val id: String,
    val title: String,
    val prompt: String,
    val options: List<WidgetDialogOption>
)

data class WidgetDialogOption(
    val value: String,
    val label: String
)

data class WidgetActivityItem(
    val type: String,
    val description: String
)

/**
 * Combined state holder for all widget data
 */
data class WidgetBayState(
    val dialogConnected: Boolean = false,
    val cliConnected: Boolean = false,
    val agents: List<WidgetAgentInfo> = emptyList(),
    val activeDialog: WidgetDialogInfo? = null,
    val pendingDialogCount: Int = 0,
    val activityEvents: List<WidgetActivityItem> = emptyList(),
    val currentMode: OrchestratorPresenceMode = OrchestratorPresenceMode.USER_ACTIVE,
    val networkState: CurrentNetworkState = CurrentNetworkState(),
    val networkMeasurements: List<NetworkMeasurement> = emptyList(),
    val networkStats: NetworkStats? = null,
    val selectedTimeRange: TimeRange = TimeRange.FIFTEEN_MINUTES,
    val queuedOperations: Int = 0,
    val parkedAgents: Int = 0
) {
    companion object {
        fun fromViewModels(
            dialogConnected: Boolean,
            cliConnected: Boolean,
            cliAgents: List<AgentSummary>,
            activeDialog: DialogDetails?,
            queueCount: Int,
            activityEvents: List<ActivityEvent>,
            mode: OrchestratorPresenceMode,
            networkState: CurrentNetworkState,
            networkMeasurements: List<NetworkMeasurement>,
            networkStats: NetworkStats?,
            selectedTimeRange: TimeRange,
            pendingOps: Int,
            parkedCount: Int
        ): WidgetBayState {
            return WidgetBayState(
                dialogConnected = dialogConnected,
                cliConnected = cliConnected,
                agents = cliAgents.map { agent ->
                    WidgetAgentInfo(
                        id = agent.id,
                        workspace = agent.workspace,
                        status = agent.status
                    )
                },
                activeDialog = activeDialog?.let { dialog ->
                    WidgetDialogInfo(
                        id = dialog.id,
                        title = dialog.title,
                        prompt = dialog.prompt,
                        options = dialog.dialogType.options?.map { opt ->
                            WidgetDialogOption(opt.value, opt.label)
                        } ?: emptyList()
                    )
                },
                pendingDialogCount = queueCount,
                activityEvents = activityEvents.take(5).map { event ->
                    WidgetActivityItem(
                        type = when (event) {
                            is ActivityEvent.Command -> "command"
                            is ActivityEvent.DialogSent -> "dialog_sent"
                            is ActivityEvent.DialogResponse -> "dialog_response"
                            is ActivityEvent.ToolCall -> "tool_call"
                            is ActivityEvent.FileEdit -> "file_edit"
                        },
                        description = when (event) {
                            is ActivityEvent.Command -> event.command.take(50)
                            is ActivityEvent.DialogSent -> event.title
                            is ActivityEvent.DialogResponse -> "Response: ${event.selection}"
                            is ActivityEvent.ToolCall -> "${event.toolName} (${event.status})"
                            is ActivityEvent.FileEdit -> "${event.action}: ${event.filePath.substringAfterLast("/")}"
                        }
                    )
                },
                currentMode = mode,
                networkState = networkState,
                networkMeasurements = networkMeasurements,
                networkStats = networkStats,
                selectedTimeRange = selectedTimeRange,
                queuedOperations = pendingOps,
                parkedAgents = parkedCount
            )
        }
    }
}

/**
 * Main Widget Bay composable - a customizable grid of widgets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetBayScreen(
    bayConfig: BayConfig,
    state: WidgetBayState,
    onAddWidget: (WidgetType) -> Unit,
    onRemoveWidget: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToAgents: () -> Unit,
    onNavigateToDialog: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToParked: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onModeChange: (OrchestratorPresenceMode) -> Unit,
    onTimeRangeChange: (TimeRange) -> Unit,
    onNetworkRefresh: () -> Unit,
    onQuickRespond: (String, String) -> Unit,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddWidgetDialog by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Dashboard")
                        Text(
                            text = when {
                                state.dialogConnected && state.cliConnected -> "All systems connected"
                                state.dialogConnected -> "Dialog connected"
                                state.cliConnected -> "CLI connected"
                                else -> "Not connected"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                state.dialogConnected && state.cliConnected -> Color(0xFF4CAF50)
                                state.dialogConnected || state.cliConnected -> Color(0xFFFF9800)
                                else -> Color.Gray
                            }
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = "Edit Layout",
                            tint = if (editMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showAddWidgetDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Widget")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 280.dp),
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val sortedWidgets = bayConfig.widgets.sortedBy { it.order }
            
            items(
                items = sortedWidgets,
                key = { it.id },
                span = { widget -> GridItemSpan(widget.span.coerceAtMost(bayConfig.columns)) }
            ) { widget ->
                WidgetCard(
                    widget = widget,
                    state = state,
                    editMode = editMode,
                    onRemove = { onRemoveWidget(widget.id) },
                    onNavigateToAgents = onNavigateToAgents,
                    onNavigateToDialog = onNavigateToDialog,
                    onNavigateToActivity = onNavigateToActivity,
                    onNavigateToParked = onNavigateToParked,
                    onNavigateToSettings = onNavigateToSettings,
                    onModeChange = onModeChange,
                    onTimeRangeChange = onTimeRangeChange,
                    onNetworkRefresh = onNetworkRefresh,
                    onQuickRespond = onQuickRespond,
                    onQuickAction = onQuickAction
                )
            }
        }
    }
    
    if (showAddWidgetDialog) {
        AddWidgetDialog(
            existingTypes = bayConfig.widgets.map { it.type }.toSet(),
            onDismiss = { showAddWidgetDialog = false },
            onAdd = { type ->
                onAddWidget(type)
                showAddWidgetDialog = false
            }
        )
    }
}

/**
 * Individual widget card wrapper
 */
@Composable
fun WidgetCard(
    widget: WidgetConfig,
    state: WidgetBayState,
    editMode: Boolean,
    onRemove: () -> Unit,
    onNavigateToAgents: () -> Unit,
    onNavigateToDialog: () -> Unit,
    onNavigateToActivity: () -> Unit,
    onNavigateToParked: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onModeChange: (OrchestratorPresenceMode) -> Unit,
    onTimeRangeChange: (TimeRange) -> Unit,
    onNetworkRefresh: () -> Unit,
    onQuickRespond: (String, String) -> Unit,
    onQuickAction: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (widget.span > 1) 140.dp else 120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        widget.type.emoji,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = widget.type.title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (editMode) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            when (widget.type) {
                WidgetType.SERVER_STATUS -> ServerStatusWidget(
                    dialogConnected = state.dialogConnected,
                    cliConnected = state.cliConnected,
                    latencyMs = state.networkState.lastLatencyMs,
                    onRefresh = onNetworkRefresh
                )
                WidgetType.RUNNING_AGENTS -> RunningAgentsWidget(
                    agents = state.agents,
                    onNavigateToAgents = onNavigateToAgents
                )
                WidgetType.DIALOG_BADGE -> DialogBadgeWidget(
                    activeDialog = state.activeDialog,
                    pendingCount = state.pendingDialogCount,
                    onNavigateToDialog = onNavigateToDialog,
                    onQuickRespond = onQuickRespond
                )
                WidgetType.ACTIVITY_PREVIEW -> ActivityPreviewWidget(
                    events = state.activityEvents,
                    onNavigateToFeed = onNavigateToActivity
                )
                WidgetType.MODE_SELECTOR -> ModeSelectorWidget(
                    currentMode = state.currentMode,
                    onModeChange = onModeChange
                )
                WidgetType.NETWORK_STATS -> NetworkStatsWidget(
                    currentState = state.networkState,
                    onRefresh = onNetworkRefresh
                )
                WidgetType.NETWORK_HISTORY -> NetworkHistoryWidget(
                    measurements = state.networkMeasurements,
                    stats = state.networkStats,
                    selectedRange = state.selectedTimeRange,
                    onRangeChange = onTimeRangeChange
                )
                WidgetType.QUEUE_STATUS -> QueueStatusWidget(
                    queuedOperations = state.queuedOperations,
                    parkedAgents = state.parkedAgents,
                    onNavigateToQueue = onNavigateToParked
                )
                WidgetType.QUICK_ACTIONS -> QuickActionsWidget(
                    onAction = { action ->
                        when (action) {
                            "settings" -> onNavigateToSettings()
                            "spawn_agent" -> onNavigateToAgents()
                            else -> onQuickAction(action)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Dialog for adding new widgets
 */
@Composable
fun AddWidgetDialog(
    existingTypes: Set<WidgetType>,
    onDismiss: () -> Unit,
    onAdd: (WidgetType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Widget") },
        text = {
            Column {
                WidgetType.entries.forEach { type ->
                    val alreadyAdded = type in existingTypes
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !alreadyAdded) { onAdd(type) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            type.emoji,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                type.title,
                                fontWeight = FontWeight.Medium,
                                color = if (alreadyAdded) Color.Gray else LocalContentColor.current
                            )
                            Text(
                                type.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (alreadyAdded) Color.Gray else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (alreadyAdded) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Added",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
