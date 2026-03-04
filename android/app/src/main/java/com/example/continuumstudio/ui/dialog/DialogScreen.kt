package com.example.continuumstudio.ui.dialog

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.*

/**
 * Tab options for main navigation
 */
private enum class MainTab {
    DIALOG, HISTORY
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogScreen(
    connectionState: ConnectionState,
    dialogState: DialogUiState,
    history: List<HistoryItem> = emptyList(),
    historyLoading: Boolean = false,
    latency: Long? = null,
    snackbarMessage: String? = null,
    isOnline: Boolean = true,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
    onSelectOption: (String) -> Unit,
    onToggleOption: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onSliderChange: (Float) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onToggleHoldMode: () -> Unit,
    onFetchHistory: () -> Unit = {},
    onReinvokeDialog: (HistoryItem) -> Unit = {},
    onSnackbarDismiss: () -> Unit = {},
    onTestNotification: () -> Unit = {},
) {
    var serverUrlInput by remember { mutableStateOf(connectionState.serverUrl.ifBlank { "dialog.datapunk.dev" }) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MainTab.DIALOG) }
    
    // Snackbar state for toast notifications
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(
                message = snackbarMessage,
                duration = SnackbarDuration.Short
            )
            onSnackbarDismiss()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { 
                        Column {
                            Text("Continuum Studio")
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Connection status
                                Text(
                                    text = if (connectionState.isConnected) "Connected" else "Disconnected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (connectionState.isConnected) Color(0xFF4CAF50) else Color.Gray
                                )
                                // Latency indicator
                                if (connectionState.isConnected && latency != null) {
                                    LatencyBadge(latency)
                                }
                            }
                        }
                    },
                    actions = {
                        // Hold mode toggle
                        if (connectionState.isConnected) {
                            IconButton(onClick = onToggleHoldMode) {
                                Icon(
                                    if (dialogState.holdMode) Icons.Default.Lock else Icons.Default.CheckCircle,
                                    contentDescription = "Hold Mode",
                                    tint = if (dialogState.holdMode) Color(0xFFFF9800) else Color.Gray
                                )
                            }
                        }
                        // Refresh
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        // Settings
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                
                // Offline banner
                if (!isOnline) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFE53935), // Red warning color
                        tonalElevation = 2.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "No internet connection",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }
                
                // Tab bar (only when connected)
                if (connectionState.isConnected) {
                    TabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Tab(
                            selected = selectedTab == MainTab.DIALOG,
                            onClick = { selectedTab = MainTab.DIALOG },
                            text = { Text("Dialog") },
                            icon = { Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == MainTab.HISTORY,
                            onClick = { 
                                selectedTab = MainTab.HISTORY
                                onFetchHistory()
                            },
                            text = { Text("History") },
                            icon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection settings (collapsible)
            if (showSettings || !connectionState.isConnected || connectionState.isReconnecting) {
                ConnectionCard(
                    serverUrl = serverUrlInput,
                    onServerUrlChange = { serverUrlInput = it },
                    isConnected = connectionState.isConnected,
                    isConnecting = connectionState.isConnecting,
                    isReconnecting = connectionState.isReconnecting,
                    reconnectAttempts = connectionState.reconnectAttempts,
                    error = connectionState.errorMessage,
                    onConnect = { onConnect(serverUrlInput) },
                    onDisconnect = onDisconnect,
                )
            }

            // Main content
            if (connectionState.isConnected) {
                when (selectedTab) {
                    MainTab.DIALOG -> {
                        if (dialogState.activeDialog != null) {
                            ActiveDialogCard(
                                dialog = dialogState.activeDialog,
                                selectedValue = dialogState.selectedValue,
                                selectedOptions = dialogState.selectedOptions,
                                sliderValue = dialogState.sliderValue,
                                comment = dialogState.comment,
                                holdMode = dialogState.holdMode,
                                onSelectOption = onSelectOption,
                                onToggleOption = onToggleOption,
                                onTextChange = onTextChange,
                                onCommentChange = onCommentChange,
                                onSliderChange = onSliderChange,
                                onConfirm = onConfirm,
                                onSubmit = onSubmit,
                            )
                        } else {
                            // No active dialog - show dashboard
                            DashboardCard(
                                queueCount = dialogState.queueCount,
                                holdMode = dialogState.holdMode,
                                latency = latency,
                                isOnline = isOnline,
                                onTestNotification = onTestNotification,
                            )
                        }
                    }
                    MainTab.HISTORY -> {
                        HistoryView(
                            history = history,
                            isLoading = historyLoading,
                            onRefresh = onFetchHistory,
                            onReinvoke = onReinvokeDialog,
                        )
                    }
                }
            } else if (!connectionState.isConnecting) {
                // Not connected prompt
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Enter server URL and tap Connect",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

/**
 * Latency indicator badge with color coding
 */
@Composable
fun LatencyBadge(latency: Long) {
    val color = when {
        latency < 100 -> Color(0xFF4CAF50) // Green
        latency < 300 -> Color(0xFFFF9800) // Orange
        else -> Color(0xFFF44336) // Red
    }
    
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Speed,
            contentDescription = "Latency",
            modifier = Modifier.size(12.dp),
            tint = color
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            "${latency}ms",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/**
 * History view showing past dialogs with reinvoke option
 * Supports pull-to-refresh gesture
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryView(
    history: List<HistoryItem>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onReinvoke: (HistoryItem) -> Unit,
) {
    val pullToRefreshState = rememberPullToRefreshState()
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullToRefresh(
                state = pullToRefreshState,
                isRefreshing = isLoading,
                onRefresh = onRefresh
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header with refresh button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Dialog History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Show history count
                    if (history.isNotEmpty()) {
                        Text(
                            "${history.size} items",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            }
            
            if (history.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No history yet",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Pull down to refresh",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history, key = { it.id }) { item ->
                        HistoryItemCard(
                            item = item,
                            onReinvoke = { onReinvoke(item) }
                        )
                    }
                }
            }
        }
        
        // Pull-to-refresh indicator
        PullToRefreshDefaults.Indicator(
            state = pullToRefreshState,
            isRefreshing = isLoading,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * Format ISO timestamp to human-readable format
 */
private fun formatTimestamp(isoTimestamp: String?): String {
    if (isoTimestamp == null) return ""
    return try {
        // Parse ISO 8601 format (e.g., "2026-03-04T10:30:00Z" or "2026-03-04T10:30:00.123456789Z")
        val parts = isoTimestamp.replace("Z", "").split("T")
        if (parts.size == 2) {
            val datePart = parts[0]
            val timePart = parts[1].split(".")[0] // Remove nanoseconds
            val timeComponents = timePart.split(":")
            if (timeComponents.size >= 2) {
                "${timeComponents[0]}:${timeComponents[1]}"
            } else {
                timePart
            }
        } else {
            isoTimestamp.take(16)
        }
    } catch (_: Exception) {
        isoTimestamp.take(16)
    }
}

/**
 * Get dialog type icon
 */
@Composable
private fun DialogTypeIcon(dialogType: String) {
    val icon = when (dialogType.lowercase()) {
        "choice" -> Icons.Default.List
        "text" -> Icons.Default.Edit
        "confirm" -> Icons.Default.Check
        "slider" -> Icons.Default.LinearScale
        else -> Icons.Default.QuestionAnswer
    }
    Icon(
        icon,
        contentDescription = dialogType,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Card for a single history item
 */
@Composable
fun HistoryItemCard(
    item: HistoryItem,
    onReinvoke: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = if (item.cancelled) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = if (expanded) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DialogTypeIcon(item.dialogType)
                        Text(
                            item.dialogType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        item.completedAt?.let { timestamp ->
                            Text(
                                " • ${formatTimestamp(timestamp)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                
                // Status + Reinvoke
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.cancelled) {
                        Text(
                            "Cancelled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        Icon(
                            Icons.Default.Done,
                            contentDescription = "Completed",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onReinvoke,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Reinvoke",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Prompt - expandable
            Text(
                item.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Show selection if present
            item.selection?.let { selection ->
                Spacer(modifier = Modifier.height(4.dp))
                val selectionText = selection.toString()
                    .let { if (it.startsWith("\"") && it.endsWith("\"")) it.drop(1).dropLast(1) else it }
                Text(
                    "Answer: ${if (expanded) selectionText else selectionText.take(50) + if (selectionText.length > 50) "..." else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontStyle = FontStyle.Italic,
                    maxLines = if (expanded) Int.MAX_VALUE else 1
                )
            }
            
            // Show comment if present and expanded
            if (expanded && !item.comment.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Comment: ${item.comment}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontStyle = FontStyle.Italic
                )
            }
            
            // Expand/collapse indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Show less" else "Show more",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun ConnectionCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
    isReconnecting: Boolean = false,
    reconnectAttempts: Int = 0,
    error: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                placeholder = { Text("dialog.datapunk.dev") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected && !isConnecting && !isReconnecting
            )

            Spacer(modifier = Modifier.height(12.dp))
            
            // Show reconnection status
            if (isReconnecting && reconnectAttempts > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Reconnecting (attempt $reconnectAttempts)...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onConnect,
                        enabled = !isConnecting && !isReconnecting && serverUrl.isNotBlank()
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isConnecting) "Connecting..." else "Connect")
                    }
                }
            }

            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun DashboardCard(
    queueCount: Int,
    holdMode: Boolean,
    latency: Long? = null,
    isOnline: Boolean = true,
    onTestNotification: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = Color(0xFF4CAF50)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            "Waiting for dialogs...",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusChip(
                icon = Icons.Default.List,
                label = "Queue: $queueCount",
            )
            StatusChip(
                icon = if (holdMode) Icons.Default.Lock else Icons.Default.CheckCircle,
                label = if (holdMode) "Hold ON" else "Hold OFF",
                color = if (holdMode) Color(0xFFFF9800) else Color.Gray
            )
        }
        
        // Network and Latency indicators
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Network status
            StatusChip(
                icon = if (isOnline) Icons.Default.Wifi else Icons.Default.WifiOff,
                label = if (isOnline) "Online" else "Offline",
                color = if (isOnline) Color(0xFF4CAF50) else Color(0xFFF44336)
            )
            
            // Latency indicator
            if (latency != null) {
                val latencyColor = when {
                    latency < 100 -> Color(0xFF4CAF50)
                    latency < 300 -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
                }
                StatusChip(
                    icon = Icons.Default.Speed,
                    label = "${latency}ms",
                    color = latencyColor
                )
            }
        }
        
        // Test notification button
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(
            onClick = onTestNotification,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(
                Icons.Default.Notifications,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Test Notification")
        }
        
        Text(
            "Send a test notification to verify notifications work",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.1f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = color)
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
fun ActiveDialogCard(
    dialog: DialogDetails,
    selectedValue: String,
    selectedOptions: Set<String>,
    sliderValue: Float,
    comment: String,
    holdMode: Boolean,
    onSelectOption: (String) -> Unit,
    onToggleOption: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onSliderChange: (Float) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Timer bar (if not paused)
        if (!dialog.isPaused && !holdMode && dialog.timeoutMs != null) {
            LinearProgressIndicator(
                progress = { dialog.timeRemainingRatio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = when {
                    dialog.timeRemainingRatio > 0.5f -> Color(0xFF4CAF50)
                    dialog.timeRemainingRatio > 0.2f -> Color(0xFFFF9800)
                    else -> Color(0xFFF44336)
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Hold mode indicator
        if (holdMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFFF9800).copy(alpha = 0.2f))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFFFF9800))
                Spacer(modifier = Modifier.width(8.dp))
                Text("HOLD MODE ACTIVE", fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Title
        Text(
            text = dialog.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Prompt with markdown rendering
        MarkdownText(
            text = dialog.prompt,
            modifier = Modifier.fillMaxWidth()
        )

        // Context data section (collapsible)
        if (dialog.context != null) {
            var showContext by remember { mutableStateOf(false) }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showContext = !showContext }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (showContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (showContext) "Hide context" else "Show context",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "Context Data",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (showContext) {
                Spacer(modifier = Modifier.height(4.dp))
                val contextText = dialog.context.toString()
                    .let { if (it.startsWith("\"") && it.endsWith("\"")) it.drop(1).dropLast(1) else it }
                
                CodeBlock(
                    code = contextText,
                    language = "json"
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Dialog type specific content
        when (dialog.dialogType.type) {
            "choice" -> ChoiceContent(
                options = dialog.dialogType.options ?: emptyList(),
                allowMultiple = dialog.dialogType.allowMultiple ?: false,
                selectedValue = selectedValue,
                selectedOptions = selectedOptions,
                onSelectOption = onSelectOption,
                onToggleOption = onToggleOption,
            )
            "text" -> TextInputContent(
                placeholder = dialog.dialogType.placeholder,
                multiline = dialog.dialogType.multiline ?: false,
                value = selectedValue,
                onValueChange = onTextChange,
            )
            "confirm" -> ConfirmationContent(
                yesLabel = dialog.dialogType.yesLabel ?: "Yes",
                noLabel = dialog.dialogType.noLabel ?: "No",
                selectedValue = selectedValue,
                onConfirm = onConfirm,
            )
            "slider" -> SliderContent(
                min = dialog.dialogType.min ?: 0f,
                max = dialog.dialogType.max ?: 100f,
                step = dialog.dialogType.step ?: 1f,
                unit = dialog.dialogType.unit ?: "",
                value = sliderValue,
                onValueChange = onSliderChange,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Comment field
        OutlinedTextField(
            value = comment,
            onValueChange = onCommentChange,
            label = { Text("Comment (optional)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Submit button
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = when (dialog.dialogType.type) {
                "choice" -> if (dialog.dialogType.allowMultiple == true) 
                    selectedOptions.isNotEmpty() else selectedValue.isNotBlank()
                "text" -> selectedValue.isNotBlank()
                "confirm" -> selectedValue.isNotBlank()
                "slider" -> true
                else -> true
            }
        ) {
            Icon(Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Submit")
        }
    }
}

@Composable
fun ChoiceContent(
    options: List<ChoiceOption>,
    allowMultiple: Boolean,
    selectedValue: String,
    selectedOptions: Set<String>,
    onSelectOption: (String) -> Unit,
    onToggleOption: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = if (allowMultiple) {
                selectedOptions.contains(option.value)
            } else {
                selectedValue == option.value
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (allowMultiple) onToggleOption(option.value)
                        else onSelectOption(option.value)
                    }
                    .then(
                        if (isSelected) Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(12.dp)
                        ) else Modifier
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (allowMultiple) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleOption(option.value) }
                        )
                    } else {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectOption(option.value) }
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        if (!option.description.isNullOrBlank()) {
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TextInputContent(
    placeholder: String?,
    multiline: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder?.let { { Text(it) } },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (multiline) 4 else 1,
        maxLines = if (multiline) 10 else 1,
    )
}

@Composable
fun ConfirmationContent(
    yesLabel: String,
    noLabel: String,
    selectedValue: String,
    onConfirm: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = { onConfirm(false) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedValue == "false")
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selectedValue == "false")
                    MaterialTheme.colorScheme.onError
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(noLabel)
        }
        
        Button(
            onClick = { onConfirm(true) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedValue == "true")
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (selectedValue == "true")
                    MaterialTheme.colorScheme.onPrimary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            Text(yesLabel)
        }
    }
}

@Composable
fun SliderContent(
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(
            text = "${value.toInt()}$unit",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = ((max - min) / step).toInt() - 1,
            modifier = Modifier.fillMaxWidth()
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${min.toInt()}$unit", style = MaterialTheme.typography.bodySmall)
            Text("${max.toInt()}$unit", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Enhanced markdown text renderer that handles:
 * - **bold** and __bold__
 * - *italic* and _italic_
 * - `inline code`
 * - ```code blocks```
 * - # ## ### Headers
 * - - Lists and numbered lists (1.)
 * - > Block quotes
 * - [links](url) (displayed but not clickable)
 * - Horizontal rules (---)
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val lines = text.lines()
    
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            
            // Check for code blocks
            if (line.trim().startsWith("```")) {
                // Collect code block
                val codeLines = mutableListOf<String>()
                val language = line.trim().removePrefix("```").trim()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                if (codeLines.isNotEmpty()) {
                    CodeBlock(code = codeLines.joinToString("\n"), language = language)
                }
                i++ // Skip closing ```
                continue
            }
            
            // Horizontal rule
            if (line.trim().matches(Regex("^[-*_]{3,}$"))) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                i++
                continue
            }
            
            // Headers
            val headerMatch = Regex("^(#{1,6})\\s+(.+)$").find(line.trim())
            if (headerMatch != null) {
                val level = headerMatch.groupValues[1].length
                val content = headerMatch.groupValues[2]
                MarkdownHeader(level = level, text = content)
                i++
                continue
            }
            
            // Block quotes (can be multi-line)
            if (line.trim().startsWith(">")) {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                MarkdownBlockQuote(text = quoteLines.joinToString("\n"))
                continue
            }
            
            // Unordered lists
            if (line.trim().matches(Regex("^[-*+]\\s+.+"))) {
                val listItems = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^[-*+]\\s+.+"))) {
                    listItems.add(lines[i].trim().replaceFirst(Regex("^[-*+]\\s+"), ""))
                    i++
                }
                MarkdownUnorderedList(items = listItems)
                continue
            }
            
            // Ordered lists
            if (line.trim().matches(Regex("^\\d+\\.\\s+.+"))) {
                val listItems = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^\\d+\\.\\s+.+"))) {
                    listItems.add(lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                    i++
                }
                MarkdownOrderedList(items = listItems)
                continue
            }
            
            // Regular text (skip empty lines but add some spacing)
            if (line.isBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            } else {
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(line),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            i++
        }
    }
}

@Composable
fun MarkdownHeader(level: Int, text: String) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineLarge
        2 -> MaterialTheme.typography.headlineMedium
        3 -> MaterialTheme.typography.headlineSmall
        4 -> MaterialTheme.typography.titleLarge
        5 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Text(
        text = parseInlineMarkdown(text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = if (level <= 2) 8.dp else 4.dp, bottom = 4.dp)
    )
}

@Composable
fun MarkdownBlockQuote(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // Vertical bar indicator
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.width(12.dp))
        SelectionContainer {
            Text(
                text = parseInlineMarkdown(text),
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun MarkdownUnorderedList(items: List<String>) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp)
                )
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(item),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownOrderedList(items: List<String>) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "${index + 1}.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp)
                )
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(item),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/**
 * Styled code block
 */
@Composable
fun CodeBlock(
    code: String,
    language: String = "",
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
    ) {
        // Language label if present
        if (language.isNotBlank()) {
            Text(
                text = language,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF808080),
                modifier = Modifier.padding(start = 12.dp, top = 8.dp)
            )
        }
        
        // Code content with horizontal scroll
        SelectionContainer {
            Box(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ),
                    color = Color(0xFFD4D4D4)
                )
            }
        }
    }
}

/**
 * Parse inline markdown formatting (bold, italic, code, links)
 */
private fun parseInlineMarkdown(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        val input = text
        
        // Simple regex patterns for inline formatting
        val patterns = listOf(
            Triple("\\*\\*(.+?)\\*\\*".toRegex(), SpanStyle(fontWeight = FontWeight.Bold), "bold"),
            Triple("__(.+?)__".toRegex(), SpanStyle(fontWeight = FontWeight.Bold), "bold2"),
            Triple("\\*(.+?)\\*".toRegex(), SpanStyle(fontStyle = FontStyle.Italic), "italic"),
            Triple("_(.+?)_".toRegex(), SpanStyle(fontStyle = FontStyle.Italic), "italic2"),
            Triple("`(.+?)`".toRegex(), SpanStyle(
                fontFamily = FontFamily.Monospace,
                background = Color(0x20808080)
            ), "code"),
            Triple("\\[(.+?)\\]\\((.+?)\\)".toRegex(), SpanStyle(
                color = Color(0xFF2196F3),
                textDecoration = TextDecoration.Underline
            ), "link"),
        )
        
        // Find all matches
        data class MatchInfo(val range: IntRange, val style: SpanStyle, val text: String, val type: String)
        val matches = mutableListOf<MatchInfo>()
        
        for ((regex, style, type) in patterns) {
            regex.findAll(input).forEach { match ->
                val capturedText = if (type == "link") {
                    match.groupValues[1] // Just the link text, not URL
                } else {
                    match.groupValues[1]
                }
                matches.add(MatchInfo(match.range, style, capturedText, type))
            }
        }
        
        // Sort by start position and filter overlapping
        val sortedMatches = matches.sortedBy { it.range.first }
        val nonOverlapping = mutableListOf<MatchInfo>()
        var lastEnd = -1
        for (match in sortedMatches) {
            if (match.range.first > lastEnd) {
                nonOverlapping.add(match)
                lastEnd = match.range.last
            }
        }
        
        // Build the annotated string
        for (match in nonOverlapping) {
            // Add text before this match
            if (currentIndex < match.range.first) {
                append(input.substring(currentIndex, match.range.first))
            }
            // Add styled text
            withStyle(match.style) {
                append(match.text)
            }
            currentIndex = match.range.last + 1
        }
        
        // Add remaining text
        if (currentIndex < input.length) {
            append(input.substring(currentIndex))
        }
    }
}

