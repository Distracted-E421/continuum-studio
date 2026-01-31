package com.example.continuumstudio.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*

/**
 * Main Widget Bay composable - a customizable grid of widgets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetBayScreen(
    bayConfig: BayConfig,
    connectionState: ConnectionState,
    dialogState: DialogUiState,
    harnesses: List<HarnessInfo>,
    services: List<ServiceInfo>,
    isLoadingHarnesses: Boolean,
    isLoadingServices: Boolean,
    onAddWidget: (WidgetType) -> Unit,
    onRemoveWidget: (String) -> Unit,
    onRefresh: () -> Unit,
    onNavigateToDialog: () -> Unit,
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
                            text = if (connectionState.isConnected) "Connected to ${connectionState.serverUrl}" else "Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionState.isConnected) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                },
                actions = {
                    // Edit mode toggle
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = "Edit Layout",
                            tint = if (editMode) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    // Add widget
                    IconButton(onClick = { showAddWidgetDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Widget")
                    }
                    // Refresh
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    // Dialog screen
                    IconButton(onClick = onNavigateToDialog) {
                        BadgedBox(
                            badge = {
                                if (dialogState.queueCount > 0) {
                                    Badge { Text(dialogState.queueCount.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.List, contentDescription = "Dialogs")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(bayConfig.columns),
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
                    connectionState = connectionState,
                    dialogState = dialogState,
                    harnesses = harnesses,
                    services = services,
                    isLoadingHarnesses = isLoadingHarnesses,
                    isLoadingServices = isLoadingServices,
                    editMode = editMode,
                    onRemove = { onRemoveWidget(widget.id) },
                    onQuickAction = onQuickAction,
                    onNavigateToDialog = onNavigateToDialog,
                )
            }
        }
    }
    
    // Add widget dialog
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
    connectionState: ConnectionState,
    dialogState: DialogUiState,
    harnesses: List<HarnessInfo>,
    services: List<ServiceInfo>,
    isLoadingHarnesses: Boolean,
    isLoadingServices: Boolean,
    editMode: Boolean,
    onRemove: () -> Unit,
    onQuickAction: (String) -> Unit,
    onNavigateToDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            // Widget header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        widget.type.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
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
            
            // Widget content based on type
            when (widget.type) {
                WidgetType.CONNECTION_STATUS -> ConnectionStatusWidget(connectionState)
                WidgetType.DIALOG_QUEUE -> DialogQueueWidget(dialogState, onNavigateToDialog)
                WidgetType.HARNESS_STATUS -> HarnessStatusWidget(harnesses, isLoadingHarnesses)
                WidgetType.SERVICE_DISCOVERY -> ServiceDiscoveryWidget(services, isLoadingServices)
                WidgetType.NODE_HEALTH -> NodeHealthWidget(connectionState)
                WidgetType.QUICK_ACTIONS -> QuickActionsWidget(onQuickAction)
                WidgetType.AGENT_STREAM -> AgentStreamWidget()
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
                        Icon(
                            type.icon(),
                            contentDescription = null,
                            tint = if (alreadyAdded) Color.Gray else MaterialTheme.colorScheme.primary
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

// Extension to get icon for widget type
fun WidgetType.icon(): ImageVector = when (this) {
    WidgetType.DIALOG_QUEUE -> Icons.Default.List
    WidgetType.HARNESS_STATUS -> Icons.Default.Settings
    WidgetType.SERVICE_DISCOVERY -> Icons.Default.Send
    WidgetType.NODE_HEALTH -> Icons.Default.Check
    WidgetType.QUICK_ACTIONS -> Icons.Default.Send
    WidgetType.CONNECTION_STATUS -> Icons.Default.Check
    WidgetType.AGENT_STREAM -> Icons.Default.List
}
