package com.continuumstudio.tablet.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import com.continuumstudio.tablet.data.models.Dialog
import com.continuumstudio.tablet.data.models.DialogPriority
import com.continuumstudio.tablet.ui.theme.LocalIsFiveFootMode
import com.continuumstudio.tablet.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogInboxScreen(viewModel: MainViewModel) {
    val dialogs by viewModel.dialogs.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Auto-select first dialog if only one exists
    var selectedDialogId by remember { mutableStateOf<String?>(null) }
    // Use derivedStateOf to avoid recomposition when unrelated state changes
    val selectedDialog by remember(dialogs, selectedDialogId) { 
        androidx.compose.runtime.derivedStateOf {
            dialogs.find { it.id == selectedDialogId } ?: dialogs.firstOrNull()
        }
    }
    
    // Auto-refresh every 7 seconds for non-WebSocket data (history, tasks, parked)
    // WebSocket handles real-time updates for dialogs and active agents
    LaunchedEffect(Unit) {
        while (true) {
            delay(7000)
            viewModel.refreshNonRealtimeData()
        }
    }
    
    val pullToRefreshState = rememberPullToRefreshState()
    
    val onRefresh: () -> Unit = {
        scope.launch {
            isRefreshing = true
            viewModel.refreshData()
            delay(800) // Longer delay for visible feedback
            isRefreshing = false
        }
    }
    
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        state = pullToRefreshState,
        modifier = Modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                modifier = Modifier.align(Alignment.TopCenter),
                isRefreshing = isRefreshing,
                state = pullToRefreshState,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                color = MaterialTheme.colorScheme.primary
            )
        }
    ) {
        if (dialogs.isEmpty()) {
            EmptyDialogState(isFiveFootMode, onRefresh = onRefresh)
        } else {
            if (isFiveFootMode) {
                FiveFootDialogList(dialogs, viewModel)
            } else {
                InfoDenseSplitView(
                    dialogs = dialogs,
                    selectedDialog = selectedDialog,
                    onSelectDialog = { selectedDialogId = it.id },
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
private fun EmptyDialogState(isFiveFootMode: Boolean, onRefresh: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "📬",
                style = if (isFiveFootMode) MaterialTheme.typography.displayLarge 
                       else MaterialTheme.typography.displayMedium
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No pending dialogs",
                style = if (isFiveFootMode) MaterialTheme.typography.headlineMedium 
                       else MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Agent questions will appear here",
                style = if (isFiveFootMode) MaterialTheme.typography.bodyLarge 
                       else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRefresh,
                modifier = if (isFiveFootMode) Modifier.height(64.dp).padding(horizontal = 32.dp) else Modifier
            ) {
                Text(
                    "↻ Refresh",
                    style = if (isFiveFootMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Pull down to refresh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Layout: Options (left ~1/3), Content (center ~2/3), Queue drawer (right edge)
@Composable
private fun InfoDenseSplitView(
    dialogs: List<Dialog>,
    selectedDialog: Dialog?,
    onSelectDialog: (Dialog) -> Unit,
    viewModel: MainViewModel
) {
    val ttsState by viewModel.ttsState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var isQueueDrawerOpen by remember { mutableStateOf(false) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left column: Response options with comment box (~1/3)
            Column(
                modifier = Modifier
                    .weight(0.33f)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedDialog != null) {
                    Text(
                        text = "Response",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    // Selected option state (not auto-submit)
                    var selectedOption by remember(selectedDialog.id) { mutableStateOf<String?>(null) }
                    var comment by remember(selectedDialog.id) { mutableStateOf("") }
                    
                    // Option buttons - select only, don't submit
                    selectedDialog.options?.forEach { option ->
                        val isSelected = selectedOption == option.value
                        OutlinedButton(
                            onClick = { selectedOption = option.value },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                            ),
                            border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                    
                    // Fallback for dialogs without options (like confirmation)
                    if (selectedDialog.options == null || selectedDialog.options.isEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { selectedOption = "true" },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selectedOption == "true") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                ),
                                border = if (selectedOption == "true") BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else ButtonDefaults.outlinedButtonBorder
                            ) {
                                Text("Confirm", style = MaterialTheme.typography.titleMedium)
                            }
                            OutlinedButton(
                                onClick = { selectedOption = "false" },
                                modifier = Modifier.weight(1f).height(56.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (selectedOption == "false") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
                                ),
                                border = if (selectedOption == "false") BorderStroke(2.dp, MaterialTheme.colorScheme.error) else ButtonDefaults.outlinedButtonBorder
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    
                    // Comment input box
                    OutlinedTextField(
                        value = comment,
                        onValueChange = { comment = it },
                        label = { Text("Add comment (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // DEDICATED SUBMIT BUTTON
                    Button(
                        onClick = { 
                            if (selectedOption != null) {
                                viewModel.respondToDialog(
                                    selectedDialog.id, 
                                    selectedOption!!, 
                                    if (comment.isNotBlank()) comment else null
                                )
                                selectedOption = null
                                comment = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        enabled = selectedOption != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Submit",
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Submit Response",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
            
            VerticalDivider()
            
            // Center-right column: Full dialog content (~2/3)
            Column(
                modifier = Modifier
                    .weight(0.67f)
                    .fillMaxHeight()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (selectedDialog != null) {
                    // Title with priority and TTS controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PriorityIndicator(selectedDialog.priority, large = true)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = selectedDialog.title,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        // TTS controls
                        if (settings.ttsEnabled) {
                            if (ttsState.isSpeaking) {
                                IconButton(onClick = { viewModel.stopSpeaking() }) {
                                    Icon(
                                        Icons.Default.Stop, 
                                        contentDescription = "Stop",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                IconButton(onClick = { viewModel.speakDialog(selectedDialog) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.VolumeUp, 
                                        contentDescription = "Read aloud",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    
                    // Full prompt content
                    Text(
                        text = selectedDialog.prompt,
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No dialog selected")
                    }
                }
            }
        }
        
        // Right edge: Queue drawer tab + drawer (ALWAYS visible)
        QueueDrawer(
            isOpen = isQueueDrawerOpen,
            dialogs = dialogs,
            selectedDialogId = selectedDialog?.id,
            onToggle = { isQueueDrawerOpen = !isQueueDrawerOpen },
            onSelectDialog = { dialog ->
                onSelectDialog(dialog)
                isQueueDrawerOpen = false
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

// Queue drawer with tab on right edge
@Composable
private fun QueueDrawer(
    isOpen: Boolean,
    dialogs: List<Dialog>,
    selectedDialogId: String?,
    onToggle: () -> Unit,
    onSelectDialog: (Dialog) -> Unit,
    modifier: Modifier = Modifier
) {
    // Use animateFloatAsState for smooth 0-1 progress animation
    val drawerProgress by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 200,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "drawerProgress"
    )
    
    val drawerWidth = 240f
    
    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Collapsed tab (always visible)
        Card(
            onClick = onToggle,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier
                .width(40.dp)
                .height(120.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Queue count badge
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${dialogs.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isOpen) "◀" else "▶",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        
        // Drawer - width animates from 0 to drawerWidth
        val currentWidth = (drawerWidth * drawerProgress).dp
        
        if (drawerProgress > 0.01f) {
            Surface(
                modifier = Modifier
                    .width(currentWidth)
                    .fillMaxHeight()
                    .clipToBounds(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                // Content always at full width, clipped by parent
                Box(
                    modifier = Modifier
                        .width(drawerWidth.dp)
                        .fillMaxHeight()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Queue (${dialogs.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(12.dp))
                        
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(dialogs, key = { it.id }) { dialog ->
                                val isSelected = dialog.id == selectedDialogId
                                Card(
                                    onClick = { onSelectDialog(dialog) },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) 
                                            MaterialTheme.colorScheme.primaryContainer 
                                        else 
                                            MaterialTheme.colorScheme.surface
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        PriorityIndicator(dialog.priority)
                                        Spacer(Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = dialog.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                color = if (isSelected)
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                else
                                                    MaterialTheme.colorScheme.onSurface
                                            )
                                            dialog.workspace?.let { ws ->
                                                Text(
                                                    text = ws,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FiveFootDialogList(dialogs: List<Dialog>, viewModel: MainViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(dialogs, key = { it.id }) { dialog ->
            FiveFootDialogCard(dialog, viewModel)
        }
    }
}

@Composable
private fun DialogListItem(dialog: Dialog, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityIndicator(dialog.priority)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = dialog.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = dialog.prompt,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FiveFootDialogCard(dialog: Dialog, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PriorityIndicator(dialog.priority, large = true)
                Spacer(Modifier.width(16.dp))
                Text(
                    text = dialog.title,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            Text(
                text = dialog.prompt,
                style = MaterialTheme.typography.bodyLarge
            )
            
            Spacer(Modifier.height(24.dp))
            
            dialog.options?.let { options ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { option ->
                        Button(
                            onClick = { viewModel.respondToDialog(dialog.id, option.value) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(72.dp)
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PriorityIndicator(priority: DialogPriority, large: Boolean = false) {
    val emoji = when (priority) {
        DialogPriority.Critical -> "🚨"
        DialogPriority.High -> "🔴"
        DialogPriority.Normal -> "🟡"
        DialogPriority.Low -> "🟢"
    }
    Text(
        text = emoji,
        style = if (large) MaterialTheme.typography.headlineMedium 
               else MaterialTheme.typography.titleMedium
    )
}
