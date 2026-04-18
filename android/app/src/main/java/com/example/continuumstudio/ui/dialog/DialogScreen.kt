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
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VpnKey
import com.example.continuumstudio.data.*
import com.example.continuumstudio.ui.widgets.*

/**
 * Tab options for main navigation
 */
private enum class MainTab {
    DIALOG, HISTORY, SETTINGS
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
    savedServerUrl: String = "dialog.datapunk.dev",
    autoReconnect: Boolean = true,
    notificationsEnabled: Boolean = true,
    vibrationEnabled: Boolean = true,
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
    onAutoReconnectChange: (Boolean) -> Unit = {},
    onNotificationsEnabledChange: (Boolean) -> Unit = {},
    onVibrationEnabledChange: (Boolean) -> Unit = {},
    cfAccessClientId: String = "",
    cfAccessClientSecret: String = "",
    onCfAccessClientIdChange: (String) -> Unit = {},
    onCfAccessClientSecretChange: (String) -> Unit = {},
    onTestNotification: () -> Unit = {},
    onCopyToClipboard: (String) -> Unit = {},
    // Endpoint configuration
    endpoints: List<ServerEndpoint> = emptyList(),
    activeEndpointIndex: Int = 0,
    endpointFallbackEnabled: Boolean = true,
    endpointStatus: Map<String, EndpointStatus> = emptyMap(),
    onAddEndpoint: (String, String) -> Unit = { _, _ -> },
    onRemoveEndpoint: (Int) -> Unit = {},
    onToggleEndpoint: (Int) -> Unit = {},
    onSetActiveEndpoint: (Int) -> Unit = {},
    onSetEndpointFallbackEnabled: (Boolean) -> Unit = {},
    onTestEndpoint: (Int) -> Unit = {},
    onTestAllEndpoints: () -> Unit = {},
    // Queue functionality
    queueItems: List<com.example.continuumstudio.data.QueueItem> = emptyList(),
    onSwitchToQueuedDialog: (Int) -> Unit = {},
    onToggleQueueDrawer: () -> Unit = {},
) {
    // Initialize serverUrlInput from savedServerUrl (persisted in DataStore)
    var serverUrlInput by remember { mutableStateOf(savedServerUrl) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(MainTab.DIALOG) }
    
    // FAB and radial menu removed - using inline buttons
    
    // Connection status for FAB
    val connectionStatus = when {
        connectionState.isConnected -> ConnectionStatus.CONNECTED
        connectionState.isConnecting -> ConnectionStatus.CONNECTING
        connectionState.errorMessage != null -> ConnectionStatus.ERROR
        else -> ConnectionStatus.DISCONNECTED
    }
    
    // Update serverUrlInput when savedServerUrl changes (e.g., on first load from DataStore)
    LaunchedEffect(savedServerUrl) {
        serverUrlInput = savedServerUrl
    }
    
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
                        IconButton(onClick = { 
                            // Toggle to Settings tab
                            selectedTab = if (selectedTab == MainTab.SETTINGS) MainTab.DIALOG else MainTab.SETTINGS
                        }) {
                            Icon(
                                Icons.Default.Settings, 
                                contentDescription = "Settings",
                                tint = if (selectedTab == MainTab.SETTINGS) 
                                    MaterialTheme.colorScheme.primary 
                                else 
                                    MaterialTheme.colorScheme.onSurface
                            )
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
                
                // Tab bar - always visible, but only Dialog/History require connection
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
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == MainTab.SETTINGS,
                            onClick = { selectedTab = MainTab.SETTINGS },
                            text = { Text("Settings") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        )
                    }
            }
        }
        // FAB removed - using inline buttons instead
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

            // Main content - Settings tab is always accessible
            when (selectedTab) {
                MainTab.SETTINGS -> {
                    SettingsView(
                        serverUrl = serverUrlInput,
                        onServerUrlChange = { serverUrlInput = it },
                        onConnect = { onConnect(serverUrlInput) },
                        onDisconnect = onDisconnect,
                        isConnected = connectionState.isConnected,
                        autoReconnect = autoReconnect,
                        notificationsEnabled = notificationsEnabled,
                        vibrationEnabled = vibrationEnabled,
                        onAutoReconnectChange = onAutoReconnectChange,
                        onNotificationsEnabledChange = onNotificationsEnabledChange,
                        onVibrationEnabledChange = onVibrationEnabledChange,
                        cfAccessClientId = cfAccessClientId,
                        cfAccessClientSecret = cfAccessClientSecret,
                        onCfAccessClientIdChange = onCfAccessClientIdChange,
                        onCfAccessClientSecretChange = onCfAccessClientSecretChange,
                        onTestNotification = onTestNotification,
                        endpoints = endpoints,
                        activeEndpointIndex = activeEndpointIndex,
                        endpointFallbackEnabled = endpointFallbackEnabled,
                        endpointStatus = endpointStatus,
                        onAddEndpoint = onAddEndpoint,
                        onRemoveEndpoint = onRemoveEndpoint,
                        onToggleEndpoint = onToggleEndpoint,
                        onSetActiveEndpoint = onSetActiveEndpoint,
                        onSetEndpointFallbackEnabled = onSetEndpointFallbackEnabled,
                        onTestEndpoint = onTestEndpoint,
                        onTestAllEndpoints = onTestAllEndpoints,
                    )
                }
                else -> {
                    if (connectionState.isConnected) {
                        when (selectedTab) {
                            MainTab.DIALOG -> {
                                // Wrap content with QueueDrawer on left
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Row(modifier = Modifier.fillMaxSize()) {
                                        // Queue drawer (left side)
                                        QueueDrawer(
                                            queueItems = queueItems,
                                            activeDialogId = dialogState.activeDialog?.id,
                                            isOpen = dialogState.isQueueDrawerOpen,
                                            onToggle = onToggleQueueDrawer,
                                            onSelectDialog = onSwitchToQueuedDialog,
                                        )
                                        
                                        // Main content (right side)
                                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                                                // No active dialog - show dashboard with queue info
                                                DashboardCard(
                                                    queueCount = queueItems.size,
                                                    holdMode = dialogState.holdMode,
                                                    latency = latency,
                                                    isOnline = isOnline,
                                                    onTestNotification = onTestNotification,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            MainTab.HISTORY -> {
                                HistoryView(
                                    history = history,
                                    isLoading = historyLoading,
                                    onRefresh = onFetchHistory,
                                    onReinvoke = onReinvokeDialog,
                                    onCopy = onCopyToClipboard,
                                )
                            }
                            MainTab.SETTINGS -> { /* Handled above */ }
                        }
                    } else if (!connectionState.isConnecting) {
                        // Not connected prompt
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.WifiOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    "Not Connected",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.Gray
                                )
                                Text(
                                    "Go to Settings tab to configure and connect",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Radial menu overlay removed - using inline buttons
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
 * Supports pull-to-refresh gesture and copy to clipboard
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryView(
    history: List<HistoryItem>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onReinvoke: (HistoryItem) -> Unit,
    onCopy: (String) -> Unit,
) {
    val pullToRefreshState = rememberPullToRefreshState()
    
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = Modifier.fillMaxSize()
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
                            onReinvoke = { onReinvoke(item) },
                            onCopy = onCopy
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings view with connection configuration, notification preferences,
 * and app information
 */
@Composable
fun SettingsView(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    isConnected: Boolean,
    autoReconnect: Boolean,
    notificationsEnabled: Boolean,
    vibrationEnabled: Boolean,
    onAutoReconnectChange: (Boolean) -> Unit,
    onNotificationsEnabledChange: (Boolean) -> Unit,
    onVibrationEnabledChange: (Boolean) -> Unit,
    cfAccessClientId: String = "",
    cfAccessClientSecret: String = "",
    onCfAccessClientIdChange: (String) -> Unit = {},
    onCfAccessClientSecretChange: (String) -> Unit = {},
    onTestNotification: () -> Unit,
    // Endpoint configuration
    endpoints: List<ServerEndpoint> = emptyList(),
    activeEndpointIndex: Int = 0,
    endpointFallbackEnabled: Boolean = true,
    endpointStatus: Map<String, EndpointStatus> = emptyMap(),
    onAddEndpoint: (String, String) -> Unit = { _, _ -> },
    onRemoveEndpoint: (Int) -> Unit = {},
    onToggleEndpoint: (Int) -> Unit = {},
    onSetActiveEndpoint: (Int) -> Unit = {},
    onSetEndpointFallbackEnabled: (Boolean) -> Unit = {},
    onTestEndpoint: (Int) -> Unit = {},
    onTestAllEndpoints: () -> Unit = {},
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showAddEndpointDialog by remember { mutableStateOf(false) }
    var newEndpointName by remember { mutableStateOf("") }
    var newEndpointUrl by remember { mutableStateOf("") }
    
    // Add Endpoint Dialog
    if (showAddEndpointDialog) {
        AlertDialog(
            onDismissRequest = { 
                showAddEndpointDialog = false
                newEndpointName = ""
                newEndpointUrl = ""
            },
            title = { Text("Add Endpoint") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = newEndpointName,
                        onValueChange = { newEndpointName = it },
                        label = { Text("Name") },
                        placeholder = { Text("e.g., Home Server") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newEndpointUrl,
                        onValueChange = { newEndpointUrl = it },
                        label = { Text("URL") },
                        placeholder = { Text("e.g., 192.168.1.100:8080") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newEndpointName.isNotBlank() && newEndpointUrl.isNotBlank()) {
                            onAddEndpoint(newEndpointName, newEndpointUrl)
                            showAddEndpointDialog = false
                            newEndpointName = ""
                            newEndpointUrl = ""
                        }
                    },
                    enabled = newEndpointName.isNotBlank() && newEndpointUrl.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddEndpointDialog = false
                    newEndpointName = ""
                    newEndpointUrl = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server Endpoints Section (NEW)
        item {
            SettingsSectionHeader(title = "Server Endpoints")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Multiple Endpoints",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Configure multiple server endpoints for automatic failover. Active endpoint is used first, others are fallbacks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Fallback toggle
                    SettingsToggleRow(
                        title = "Auto Fallback",
                        subtitle = "Try other enabled endpoints if active fails",
                        checked = endpointFallbackEnabled,
                        onCheckedChange = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSetEndpointFallbackEnabled(it)
                        }
                    )
                    
                    HorizontalDivider()
                    
                    // Endpoint list
                    if (endpoints.isNotEmpty()) {
                        endpoints.forEachIndexed { index, endpoint ->
                            val status = endpointStatus[endpoint.url]
                            EndpointRow(
                                endpoint = endpoint,
                                status = status,
                                isActive = index == activeEndpointIndex,
                                onToggle = { onToggleEndpoint(index) },
                                onSetActive = { onSetActiveEndpoint(index) },
                                onTest = { onTestEndpoint(index) },
                                onRemove = { onRemoveEndpoint(index) },
                                vibrationEnabled = vibrationEnabled,
                            )
                            if (index < endpoints.size - 1) {
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            }
                        }
                    } else {
                        Text(
                            "No endpoints configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    HorizontalDivider()
                    
                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { 
                                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                showAddEndpointDialog = true 
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add")
                        }
                        Button(
                            onClick = { 
                                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTestAllEndpoints() 
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test All")
                        }
                    }
                    
                    // Quick add Tailscale - zen1 (primary)
                    TextButton(
                        onClick = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddEndpoint("Tailscale (zen1)", "100.102.101.72:8082")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick Add: Tailscale (zen1)")
                    }
                    
                    // Quick add Tailscale - Obsidian (backup)
                    TextButton(
                        onClick = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAddEndpoint("Tailscale (Obsidian)", "100.109.236.61:8080")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Quick Add: Tailscale (Obsidian)")
                    }
                }
            }
        }
        
        // Connection Settings Section
        item {
            SettingsSectionHeader(title = "Connection")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Server URL input
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = onServerUrlChange,
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { 
                            Icon(Icons.Default.Link, contentDescription = null) 
                        },
                        trailingIcon = {
                            if (isConnected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Connected",
                                    tint = Color(0xFF4CAF50)
                                )
                            }
                        }
                    )
                    
                    // Connection status
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val statusColor = if (isConnected) Color(0xFF4CAF50) else Color(0xFFFF9800)
                        val statusText = if (isConnected) "Connected" else "Disconnected"
                        
                        Surface(
                            color = statusColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                statusText,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                        
                        Spacer(modifier = Modifier.weight(1f))
                        
                        if (isConnected) {
                            OutlinedButton(
                                onClick = {
                                    if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onDisconnect()
                                }
                            ) {
                                Text("Disconnect")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onConnect()
                                }
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                    
                    HorizontalDivider()
                    
                    // Auto-reconnect toggle
                    SettingsToggleRow(
                        title = "Auto-reconnect",
                        subtitle = "Automatically reconnect when network is restored",
                        checked = autoReconnect,
                        onCheckedChange = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAutoReconnectChange(it)
                        }
                    )
                }
            }
        }
        
        // Authentication Section (Cloudflare Access)
        item {
            SettingsSectionHeader(title = "Authentication")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Cloudflare Access",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Required for protected endpoints. Get service token credentials from Cloudflare Zero Trust dashboard.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // CF Access Client ID
                    OutlinedTextField(
                        value = cfAccessClientId,
                        onValueChange = onCfAccessClientIdChange,
                        label = { Text("Client ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { 
                            Icon(Icons.Default.Key, contentDescription = null) 
                        },
                        placeholder = { Text("CF-Access-Client-Id") }
                    )
                    
                    // CF Access Client Secret
                    OutlinedTextField(
                        value = cfAccessClientSecret,
                        onValueChange = onCfAccessClientSecretChange,
                        label = { Text("Client Secret") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { 
                            Icon(Icons.Default.Lock, contentDescription = null) 
                        },
                        placeholder = { Text("CF-Access-Client-Secret") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
                    )
                    
                    if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) {
                        Surface(
                            color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "✓ Service token configured",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            }
        }
        
        // Behavior Section
        item {
            SettingsSectionHeader(title = "Behavior")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Vibration/haptic feedback toggle
                    SettingsToggleRow(
                        title = "Haptic Feedback",
                        subtitle = "Vibrate on button presses and interactions",
                        checked = vibrationEnabled,
                        onCheckedChange = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onVibrationEnabledChange(it)
                        }
                    )
                }
            }
        }
        
        // Notifications Section
        item {
            SettingsSectionHeader(title = "Notifications")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Enable notifications toggle
                    SettingsToggleRow(
                        title = "Enable Notifications",
                        subtitle = "Show notifications for new dialogs and events",
                        checked = notificationsEnabled,
                        onCheckedChange = {
                            if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onNotificationsEnabledChange(it)
                        }
                    )
                    
                    HorizontalDivider()
                    
                    // Test notification button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Test Notifications",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "Send a test notification to verify they're working",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTestNotification()
                            },
                            enabled = notificationsEnabled
                        ) {
                            Icon(
                                Icons.Default.Notifications,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Test")
                        }
                    }
                    
                    HorizontalDivider()
                    
                    // Info about notification settings
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Notification permissions are managed in system settings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // About Section
        item {
            SettingsSectionHeader(title = "About")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AboutRow(label = "App Name", value = "Continuum Studio")
                    HorizontalDivider()
                    AboutRow(label = "Version", value = "1.0.0")
                    HorizontalDivider()
                    AboutRow(label = "Server Protocol", value = "WebSocket + HTTP")
                    HorizontalDivider()
                    AboutRow(
                        label = "Description",
                        value = "Android companion app for Synapsix Dialog daemon"
                    )
                }
            }
        }
        
        // Quick Links Section
        item {
            SettingsSectionHeader(title = "Quick Links")
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp)
                ) {
                    QuickLinkRow(
                        icon = Icons.Default.Web,
                        title = "Web Interface",
                        subtitle = "Open dialog.datapunk.dev in browser",
                        url = "https://dialog.datapunk.dev"
                    )
                    HorizontalDivider()
                    QuickLinkRow(
                        icon = Icons.Default.Code,
                        title = "Source Code",
                        subtitle = "View on Codeberg",
                        url = "https://codeberg.org/Distracted/continuum-studio"
                    )
                    HorizontalDivider()
                    QuickLinkRow(
                        icon = Icons.AutoMirrored.Filled.Help,
                        title = "Documentation",
                        subtitle = "Usage guide and FAQ",
                        url = "https://codeberg.org/Distracted/continuum-studio/wiki"
                    )
                }
            }
        }
        
        // Footer spacing
        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
            )
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

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun QuickLinkRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    url: String
) {
    val uriHandler = LocalUriHandler.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        "choice" -> Icons.AutoMirrored.Filled.List
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
 * Card for a single history item with haptic feedback
 */
@Composable
fun HistoryItemCard(
    item: HistoryItem,
    onReinvoke: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
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
                
                // Status + Copy + Reinvoke
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
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Copy button - copies selection/answer
                    item.selection?.let { selection ->
                        val selectionText = selection.toString()
                            .let { if (it.startsWith("\"") && it.endsWith("\"")) it.drop(1).dropLast(1) else it }
                        IconButton(
                            onClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                onCopy(selectionText)
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy answer",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    IconButton(
                        onClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReinvoke()
                        },
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
                icon = Icons.AutoMirrored.Filled.List,
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
    // Radial menu removed - using regular button interaction
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Main card content with dark background
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A), // High contrast dark
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                // Timer bar (if not paused)
                if (!dialog.isPaused && !holdMode && dialog.timeoutMs != null) {
                    LinearProgressIndicator(
                        progress = { dialog.timeRemainingRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when {
                            dialog.timeRemainingRatio > 0.5f -> Color(0xFF4CAF50)
                            dialog.timeRemainingRatio > 0.2f -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        },
                        trackColor = Color(0xFF333333),
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

                // Title - 24sp, bold, high contrast
                Text(
                    text = dialog.title,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 30.sp,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Prompt with markdown rendering - 18sp
                MarkdownText(
                    text = dialog.prompt,
                    modifier = Modifier.fillMaxWidth(),
                    baseFontSize = 18.sp,
                    textColor = Color(0xFFE0E0E0),
                )

                // Context data section (collapsible)
                if (dialog.context != null) {
                    var showContext by remember { mutableStateOf(false) }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF252525))
                            .clickable { showContext = !showContext }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (showContext) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (showContext) "Hide context" else "Show context",
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF9E9E9E)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Context Data",
                            fontSize = 14.sp,
                            color = Color(0xFF9E9E9E)
                        )
                    }
                    
                    if (showContext) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val contextText = dialog.context.toString()
                            .let { if (it.startsWith("\"") && it.endsWith("\"")) it.drop(1).dropLast(1) else it }
                        
                        CodeBlock(
                            code = contextText,
                            language = "json"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Dialog type specific content (choice/text/confirm/slider)
                when (dialog.dialogType.type) {
                    "choice" -> ChoiceContentRedesigned(
                        options = dialog.dialogType.options ?: emptyList(),
                        allowMultiple = dialog.dialogType.allowMultiple ?: false,
                        selectedValue = selectedValue,
                        selectedOptions = selectedOptions,
                        onSelectOption = onSelectOption,
                        onToggleOption = onToggleOption,
                    )
                    "text" -> TextInputContentRedesigned(
                        placeholder = dialog.dialogType.placeholder,
                        multiline = dialog.dialogType.multiline ?: false,
                        value = selectedValue,
                        onValueChange = onTextChange,
                    )
                    "confirm" -> {
                        // Show hint instead of buttons
                        Text(
                            text = "Hold card to respond",
                            fontSize = 14.sp,
                            color = Color(0xFF757575),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                    "slider" -> SliderContentRedesigned(
                        min = dialog.dialogType.min ?: 0f,
                        max = dialog.dialogType.max ?: 100f,
                        step = dialog.dialogType.step ?: 1f,
                        unit = dialog.dialogType.unit ?: "",
                        value = sliderValue,
                        onValueChange = onSliderChange,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Comment field - redesigned
                OutlinedTextField(
                    value = comment,
                    onValueChange = onCommentChange,
                    label = { Text("Comment (optional)", color = Color(0xFF757575)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color(0xFFBDBDBD),
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color(0xFF424242),
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color(0xFF757575),
                    ),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Explicit Submit button for choice/text/slider dialogs
                if (dialog.dialogType.type in listOf("choice", "text", "slider")) {
                    val hasSelection = when (dialog.dialogType.type) {
                        "choice" -> {
                            if (dialog.dialogType.allowMultiple == true) {
                                selectedOptions.isNotEmpty()
                            } else {
                                selectedValue.isNotEmpty()
                            }
                        }
                        "text" -> selectedValue.isNotEmpty()
                        "slider" -> true // Sliders always have a value
                        else -> false
                    }
                    
                    Button(
                        onClick = onSubmit,
                        enabled = hasSelection,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            disabledContainerColor = Color(0xFF2A2A2A),
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (hasSelection) "Submit Response" else "Select an option first",
                            fontSize = 16.sp,
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Interaction hint
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF252525))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color(0xFF757575),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tap an option, add a comment, then submit",
                        fontSize = 14.sp,
                        color = Color(0xFF757575),
                    )
                }
            }
        }
        
        // Radial menu overlay removed - using inline buttons instead
    }
}

@Composable
fun ChoiceContentRedesigned(
    options: List<ChoiceOption>,
    allowMultiple: Boolean,
    selectedValue: String,
    selectedOptions: Set<String>,
    onSelectOption: (String) -> Unit,
    onToggleOption: (String) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Header with selection hint
        Text(
            text = if (allowMultiple) "Tap to select multiple options" else "Tap to select an option",
            fontSize = 14.sp,
            color = Color(0xFF757575),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        options.forEach { option ->
            val isSelected = if (allowMultiple) {
                selectedOptions.contains(option.value)
            } else {
                selectedValue == option.value
            }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else Color(0xFF252525)
                    )
                    .clickable {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (allowMultiple) {
                            onToggleOption(option.value)
                        } else {
                            onSelectOption(option.value)
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (allowMultiple) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleOption(option.value) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = Color(0xFF757575),
                            checkmarkColor = Color.White,
                        )
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else if (isSelected) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.label ?: option.value,
                        fontSize = 18.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) Color.White else Color(0xFFBDBDBD),
                    )
                    if (!option.description.isNullOrBlank()) {
                        Text(
                            text = option.description,
                            fontSize = 14.sp,
                            color = Color(0xFF757575),
                        )
                    }
                }
            }
        }
        
        if (allowMultiple && selectedOptions.isNotEmpty()) {
            Text(
                text = "${selectedOptions.size} selected • Hold to submit",
                fontSize = 14.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun TextInputContentRedesigned(
    placeholder: String?,
    multiline: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { 
            Text(
                placeholder ?: "Enter your response...",
                color = Color(0xFF616161)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (multiline) 120.dp else 56.dp),
        minLines = if (multiline) 4 else 1,
        maxLines = if (multiline) 10 else 1,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color(0xFFBDBDBD),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color(0xFF424242),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
    
    if (value.isNotBlank()) {
        Text(
            text = "Hold card to submit",
            fontSize = 14.sp,
            color = Color(0xFF757575),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun SliderContentRedesigned(
    min: Float,
    max: Float,
    step: Float,
    unit: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column {
        // Value display - large and prominent
        Text(
            text = "${value.toInt()}$unit",
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Range labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${min.toInt()}$unit", fontSize = 14.sp, color = Color(0xFF757575))
            Text("${max.toInt()}$unit", fontSize = 14.sp, color = Color(0xFF757575))
        }
        
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            steps = ((max - min) / step).toInt() - 1,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF424242),
            )
        )
        
        Text(
            text = "Hold card to submit",
            fontSize = 14.sp,
            color = Color(0xFF757575),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
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
    val hapticFeedback = LocalHapticFeedback.current
    
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
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
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
    val hapticFeedback = LocalHapticFeedback.current
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirm(false)
            },
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
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onConfirm(true)
            },
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
    baseFontSize: TextUnit = 16.sp,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                MarkdownHeader(level = level, text = content, baseSize = baseFontSize, textColor = textColor)
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
                MarkdownBlockQuote(text = quoteLines.joinToString("\n"), textColor = textColor)
                continue
            }
            
            // Unordered lists
            if (line.trim().matches(Regex("^[-*+]\\s+.+"))) {
                val listItems = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^[-*+]\\s+.+"))) {
                    listItems.add(lines[i].trim().replaceFirst(Regex("^[-*+]\\s+"), ""))
                    i++
                }
                MarkdownUnorderedList(items = listItems, textColor = textColor, fontSize = baseFontSize)
                continue
            }
            
            // Ordered lists
            if (line.trim().matches(Regex("^\\d+\\.\\s+.+"))) {
                val listItems = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().matches(Regex("^\\d+\\.\\s+.+"))) {
                    listItems.add(lines[i].trim().replaceFirst(Regex("^\\d+\\.\\s+"), ""))
                    i++
                }
                MarkdownOrderedList(items = listItems, textColor = textColor, fontSize = baseFontSize)
                continue
            }
            
            // Regular text (skip empty lines but add some spacing)
            if (line.isBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
            } else {
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(line),
                        fontSize = baseFontSize,
                        lineHeight = baseFontSize * 1.4f,
                        color = textColor
                    )
                }
            }
            i++
        }
    }
}

@Composable
fun MarkdownHeader(
    level: Int, 
    text: String,
    baseSize: TextUnit = 16.sp,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Scale header sizes relative to base size
    val fontSize = when (level) {
        1 -> baseSize * 1.5f
        2 -> baseSize * 1.35f
        3 -> baseSize * 1.2f
        4 -> baseSize * 1.1f
        5 -> baseSize * 1.05f
        else -> baseSize
    }
    Text(
        text = parseInlineMarkdown(text),
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = textColor,
        modifier = Modifier.padding(top = if (level <= 2) 8.dp else 4.dp, bottom = 4.dp)
    )
}

@Composable
fun MarkdownBlockQuote(
    text: String,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
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
                color = textColor.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
fun MarkdownUnorderedList(
    items: List<String>,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 16.sp,
) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        items.forEach { item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "•",
                    fontSize = fontSize,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(16.dp)
                )
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(item),
                        fontSize = fontSize,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
fun MarkdownOrderedList(
    items: List<String>,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 16.sp,
) {
    Column(modifier = Modifier.padding(start = 8.dp)) {
        items.forEachIndexed { index, item ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text = "${index + 1}.",
                    fontSize = fontSize,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(24.dp)
                )
                SelectionContainer {
                    Text(
                        text = parseInlineMarkdown(item),
                        fontSize = fontSize,
                        color = textColor
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
/**
 * Individual endpoint row in the settings
 */
@Composable
fun EndpointRow(
    endpoint: ServerEndpoint,
    status: EndpointStatus?,
    isActive: Boolean,
    onToggle: () -> Unit,
    onSetActive: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    vibrationEnabled: Boolean,
) {
    val hapticFeedback = LocalHapticFeedback.current
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onSetActive()
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Enable/disable checkbox
        Checkbox(
            checked = endpoint.enabled,
            onCheckedChange = { 
                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle()
            }
        )
        
        // Endpoint type icon
        val typeIcon = when (endpoint.type) {
            EndpointType.LOCAL -> Icons.Default.Home
            EndpointType.TAILSCALE -> Icons.Default.VpnKey
            EndpointType.CLOUDFLARE -> Icons.Default.Public
            EndpointType.REMOTE -> Icons.Default.Dns
        }
        Icon(
            typeIcon,
            contentDescription = endpoint.type.name,
            modifier = Modifier.size(20.dp),
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // Endpoint info
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    endpoint.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "ACTIVE",
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                endpoint.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        // Status indicator
        if (status != null) {
            when {
                status.testing -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                status.connected -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Connected",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        status.latency?.let { latency ->
                            Spacer(modifier = Modifier.width(2.dp))
                            val latencyColor = when {
                                latency < 100 -> Color(0xFF4CAF50)
                                latency < 300 -> Color(0xFFFF9800)
                                else -> Color(0xFFF44336)
                            }
                            Text(
                                "${latency}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = latencyColor
                            )
                        }
                    }
                }
                status.error != null -> {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Error",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFF44336)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(4.dp))
        
        // Test button
        IconButton(
            onClick = { 
                if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onTest()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.NetworkCheck,
                contentDescription = "Test",
                modifier = Modifier.size(18.dp)
            )
        }
        
        // Delete button (only for non-default endpoints)
        if (endpoint.type != EndpointType.LOCAL) {
            IconButton(
                onClick = { 
                    if (vibrationEnabled) hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onRemove()
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

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

