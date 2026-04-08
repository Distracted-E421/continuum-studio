package com.continuumstudio.tablet.ui.screens

import androidx.compose.foundation.layout.*
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
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
    val selectedDialog = dialogs.find { it.id == selectedDialogId } ?: dialogs.firstOrNull()
    
    // Auto-refresh every 2 seconds for faster dialog detection
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            viewModel.refreshData()
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

// Per user request: Left 2/3 for full dialog content, Right 1/3 for options
@Composable
private fun InfoDenseSplitView(
    dialogs: List<Dialog>,
    selectedDialog: Dialog?,
    onSelectDialog: (Dialog) -> Unit,
    viewModel: MainViewModel
) {
    val ttsState by viewModel.ttsState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    
    Row(modifier = Modifier.fillMaxSize()) {
        // Left 2/3: Full dialog content
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
                                    Icons.Default.VolumeUp, 
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
                
                // Show if there are multiple dialogs (for queue awareness)
                if (dialogs.size > 1) {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "📋 ${dialogs.size} dialogs in queue",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // Small dialog list for queue
                    dialogs.forEach { dialog ->
                        if (dialog.id != selectedDialog.id) {
                            TextButton(onClick = { onSelectDialog(dialog) }) {
                                Text(
                                    text = dialog.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No dialog selected")
                }
            }
        }
        
        VerticalDivider()
        
        // Right 1/3: Response options with comment box
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
                
                // Comment input box
                var comment by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Add comment (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                
                Spacer(Modifier.height(8.dp))
                
                selectedDialog.options?.forEach { option ->
                    Button(
                        onClick = { 
                            viewModel.respondToDialog(
                                selectedDialog.id, 
                                option.value, 
                                if (comment.isNotBlank()) comment else null
                            )
                            comment = "" // Reset after response
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                
                // Fallback for dialogs without options (like confirmation)
                if (selectedDialog.options == null || selectedDialog.options.isEmpty()) {
                    Button(
                        onClick = { 
                            viewModel.respondToDialog(selectedDialog.id, "true", if (comment.isNotBlank()) comment else null)
                            comment = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Confirm", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedButton(
                        onClick = { 
                            viewModel.respondToDialog(selectedDialog.id, "false", if (comment.isNotBlank()) comment else null)
                            comment = ""
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
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
        items(dialogs) { dialog ->
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
