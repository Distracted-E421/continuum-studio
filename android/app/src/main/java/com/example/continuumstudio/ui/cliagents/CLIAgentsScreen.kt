package com.example.continuumstudio.ui.cliagents

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.data.*
import com.example.continuumstudio.network.OrchestratorModeResponse
import com.example.continuumstudio.ui.offline.OfflineStatusBar
import com.example.continuumstudio.viewmodel.CLIAgentsViewModel
import com.example.continuumstudio.viewmodel.DecisionEngineViewModel
import com.example.continuumstudio.viewmodel.OfflineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CLIAgentsScreen(
    viewModel: CLIAgentsViewModel = viewModel(),
    offlineViewModel: OfflineViewModel = viewModel(),
    decisionEngineViewModel: DecisionEngineViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val error by viewModel.error.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val orchestratorMode by viewModel.orchestratorMode.collectAsState()
    
    // Offline mode state
    val offlineState by offlineViewModel.uiState.collectAsState()
    
    // Decision engine state
    val decisionState by decisionEngineViewModel.uiState.collectAsState()
    
    // Wire up decision engine callback
    LaunchedEffect(Unit) {
        decisionEngineViewModel.onAutoDecision = { dialogId, response ->
            viewModel.respondToDialog(dialogId, response)
        }
    }
    
    LaunchedEffect(Unit) {
        viewModel.startPolling()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPolling()
        }
    }
    
    // Toast handling
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            // In a real app, show a Snackbar
            kotlinx.coroutines.delay(2000)
            viewModel.dismissToast()
        }
    }
    
    // Sync mode changes to decision engine
    LaunchedEffect(orchestratorMode) {
        orchestratorMode?.let { mode ->
            val presenceMode = OrchestratorPresenceMode.fromApiValue(mode.mode)
            decisionEngineViewModel.setMode(presenceMode)
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CLI Agents") },
                actions = {
                    ConnectionIndicator(isConnected = isConnected)
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
            // Offline status bar (only show if not online)
            if (offlineState.state != OfflineState.ONLINE || offlineState.pendingCount > 0) {
                OfflineStatusBar(
                    state = offlineState,
                    onSyncClick = { offlineViewModel.sync() }
                )
            }
            
            // Error banner
            error?.let { errorMessage ->
                ErrorBanner(
                    message = errorMessage,
                    onDismiss = { viewModel.clearError() }
                )
            }
            
            // Orchestrator mode bar
            OrchestratorModeBar(
                mode = orchestratorMode,
                onModeChange = { viewModel.setOrchestratorMode(it) }
            )
            
            // Tabs
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                CLIAgentsTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }
            
            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            // Tab content
            when (uiState.selectedTab) {
                CLIAgentsTab.AGENTS -> AgentsTab(
                    agents = uiState.agents,
                    selectedAgent = uiState.selectedAgent,
                    onViewDetails = { viewModel.viewAgentDetails(it) },
                    onStopAgent = { viewModel.stopAgent(it) },
                    onClearSelection = { viewModel.clearSelectedAgent() }
                )
                CLIAgentsTab.LAUNCH -> LaunchTab(
                    presets = uiState.presets,
                    selectedPresetId = uiState.selectedPresetId,
                    prompt = uiState.launchPrompt,
                    workspace = uiState.launchWorkspace,
                    mode = uiState.launchMode,
                    isLoading = uiState.isLoading,
                    onPromptChange = { viewModel.updateLaunchPrompt(it) },
                    onWorkspaceChange = { viewModel.updateLaunchWorkspace(it) },
                    onModeChange = { viewModel.updateLaunchMode(it) },
                    onPresetSelect = { viewModel.selectPreset(it) },
                    onSpawn = { viewModel.spawnAgent() }
                )
                CLIAgentsTab.DIALOGS -> DialogsTab(
                    dialogs = uiState.pendingDialogs,
                    onRespond = { id, selection -> viewModel.respondToDialog(id, selection) },
                    onEscalate = { viewModel.escalateDialog(it) }
                )
            }
        }
    }
}

// === Orchestrator Mode Bar ===

@Composable
private fun OrchestratorModeBar(
    mode: OrchestratorModeResponse?,
    onModeChange: (String) -> Unit
) {
    val modes = listOf(
        "user_active" to "🟢",
        "user_delegate" to "🟡",
        "spectator" to "🟠",
        "autonomous" to "🔴"
    )
    
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mode:",
                style = MaterialTheme.typography.labelMedium
            )
            modes.forEach { (modeValue, emoji) ->
                val isSelected = mode?.mode == modeValue
                FilterChip(
                    selected = isSelected,
                    onClick = { onModeChange(modeValue) },
                    label = { Text(emoji) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
    }
}

// === Agents Tab ===

@Composable
private fun AgentsTab(
    agents: List<AgentSummary>,
    selectedAgent: CLIAgent?,
    onViewDetails: (String) -> Unit,
    onStopAgent: (String) -> Unit,
    onClearSelection: () -> Unit
) {
    // Show agent details modal if selected
    selectedAgent?.let { agent ->
        AgentDetailsModal(
            agent = agent,
            onDismiss = onClearSelection,
            onStop = { onStopAgent(agent.id) }
        )
    }
    
    if (agents.isEmpty()) {
        EmptyState(
            icon = Icons.Default.SmartToy,
            message = "No agents running"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(agents, key = { it.id }) { agent ->
                AgentCard(
                    agent = agent,
                    onViewDetails = { onViewDetails(agent.id) },
                    onStop = { onStopAgent(agent.id) }
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    agent: AgentSummary,
    onViewDetails: () -> Unit,
    onStop: () -> Unit
) {
    val status = AgentStatus.fromString(agent.status)
    val statusColor = when (status) {
        AgentStatus.RUNNING -> Color(0xFF2196F3)
        AgentStatus.COMPLETED -> Color(0xFF4CAF50)
        AgentStatus.FAILED -> Color(0xFFF44336)
        AgentStatus.TIMEOUT -> Color(0xFFFF9800)
        AgentStatus.PENDING -> Color(0xFF9E9E9E)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onViewDetails
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.id.take(12) + "...",
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = agent.workspace.substringAfterLast("/"),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                StatusChip(status = status.displayName, color = statusColor)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${agent.eventCount} events",
                    style = MaterialTheme.typography.bodySmall
                )
                if (!status.isTerminal) {
                    TextButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentDetailsModal(
    agent: CLIAgent,
    onDismiss: () -> Unit,
    onStop: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Agent Details",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            InfoRow("ID", agent.id)
            InfoRow("Workspace", agent.workspace)
            InfoRow("Status", agent.status)
            agent.mode?.let { InfoRow("Mode", it) }
            agent.model?.let { InfoRow("Model", it) }
            agent.startedAt?.let { InfoRow("Started", it.take(19)) }
            agent.completedAt?.let { InfoRow("Completed", it.take(19)) }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "Prompt:",
                style = MaterialTheme.typography.labelMedium
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = agent.prompt.take(500),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
            
            agent.result?.let { result ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("Result:", style = MaterialTheme.typography.labelMedium)
                Text(result.take(200), style = MaterialTheme.typography.bodySmall)
            }
            
            agent.error?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("Error:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
                if (agent.status == "running" || agent.status == "pending") {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onStop()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop Agent")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// === Launch Tab ===

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchTab(
    presets: List<Preset>,
    selectedPresetId: String?,
    prompt: String,
    workspace: String,
    mode: AgentMode,
    isLoading: Boolean,
    onPromptChange: (String) -> Unit,
    onWorkspaceChange: (String) -> Unit,
    onModeChange: (AgentMode) -> Unit,
    onPresetSelect: (String?) -> Unit,
    onSpawn: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Spawn New Agent",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        // Workspace
        item {
            OutlinedTextField(
                value = workspace,
                onValueChange = onWorkspaceChange,
                label = { Text("Workspace Path") },
                placeholder = { Text("/home/e421/myproject") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }
        
        // Prompt
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                label = { Text("Prompt") },
                placeholder = { Text("Describe the task for the agent...") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6
            )
        }
        
        // Mode selection
        item {
            Text("Mode", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AgentMode.entries.forEach { agentMode ->
                    FilterChip(
                        selected = mode == agentMode,
                        onClick = { onModeChange(agentMode) },
                        label = { Text(agentMode.label.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
        }
        
        // Preset selection
        item {
            var expanded by remember { mutableStateOf(false) }
            val selectedPreset = presets.find { it.id == selectedPresetId }
            
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = selectedPreset?.displayName() ?: "No Preset",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Preset") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("No Preset") },
                        onClick = {
                            onPresetSelect(null)
                            expanded = false
                        }
                    )
                    presets.forEach { preset ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(preset.displayName())
                                    preset.description?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = {
                                onPresetSelect(preset.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
        
        // Spawn button
        item {
            Button(
                onClick = onSpawn,
                enabled = !isLoading && prompt.isNotBlank() && workspace.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(Icons.Default.RocketLaunch, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Spawn Agent")
            }
        }
    }
}

// === Dialogs Tab ===

@Composable
private fun DialogsTab(
    dialogs: List<PendingAgentDialog>,
    onRespond: (String, String) -> Unit,
    onEscalate: (String) -> Unit
) {
    if (dialogs.isEmpty()) {
        EmptyState(
            icon = Icons.Default.QuestionAnswer,
            message = "No pending dialogs"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(dialogs, key = { it.id }) { dialog ->
                PendingDialogCard(
                    dialog = dialog,
                    onRespond = { selection -> onRespond(dialog.id, selection) },
                    onEscalate = { onEscalate(dialog.id) }
                )
            }
        }
    }
}

@Composable
private fun PendingDialogCard(
    dialog: PendingAgentDialog,
    onRespond: (String) -> Unit,
    onEscalate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dialog.priorityEmoji,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dialog.title,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = dialog.sourceDisplay,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = dialog.prompt.take(200),
                style = MaterialTheme.typography.bodyMedium
            )
            
            dialog.workspace?.let { ws ->
                Text(
                    text = "📁 ${ws.substringAfterLast("/")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Options
            dialog.options?.let { options ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        OutlinedButton(
                            onClick = { onRespond(option.value) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(option.label)
                                option.description?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            
            // For confirmation dialogs
            if (dialog.dialogType == "confirmation" && dialog.options.isNullOrEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = { onRespond("false") }) {
                        Text("No")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onRespond("true") }) {
                        Text("Yes")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onEscalate) {
                    Icon(Icons.Default.PriorityHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Escalate")
                }
            }
        }
    }
}

// === Helper Components ===

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
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}

// === Tab Extensions ===

private val CLIAgentsTab.title: String
    get() = when (this) {
        CLIAgentsTab.AGENTS -> "Agents"
        CLIAgentsTab.LAUNCH -> "Launch"
        CLIAgentsTab.DIALOGS -> "Dialogs"
    }

private val CLIAgentsTab.icon: ImageVector
    get() = when (this) {
        CLIAgentsTab.AGENTS -> Icons.Default.SmartToy
        CLIAgentsTab.LAUNCH -> Icons.Default.RocketLaunch
        CLIAgentsTab.DIALOGS -> Icons.Default.QuestionAnswer
    }
