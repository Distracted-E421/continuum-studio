package com.continuumstudio.tablet.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.continuumstudio.tablet.ui.theme.LocalIsFiveFootMode
import com.continuumstudio.tablet.viewmodel.MainViewModel
import com.continuumstudio.tablet.viewmodel.OrchestratorMode

@Composable
fun AgentsScreen(viewModel: MainViewModel) {
    val agents by viewModel.agents.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "CLI Agents",
            style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                   else MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        
        if (agents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No active agents")
            }
        } else {
            LazyColumn {
                items(agents) { agent ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(agent.id, style = MaterialTheme.typography.titleMedium)
                            Text("Status: ${agent.status}", style = MaterialTheme.typography.bodyMedium)
                            agent.workspace?.let { Text("Workspace: $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrchestratorScreen(viewModel: MainViewModel) {
    val mode by viewModel.orchestratorMode.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "Orchestrator Mode",
            style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                   else MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(24.dp))
        
        OrchestratorMode.entries.forEach { orchMode ->
            val selected = mode == orchMode
            Card(
                onClick = { viewModel.setOrchestratorMode(orchMode) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(if (isFiveFootMode) 24.dp else 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            orchMode.name,
                            style = if (isFiveFootMode) MaterialTheme.typography.titleLarge 
                                   else MaterialTheme.typography.titleMedium
                        )
                        Text(
                            getModeDescription(orchMode),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun getModeDescription(mode: OrchestratorMode): String = when (mode) {
    OrchestratorMode.UserActive -> "You handle all dialogs"
    OrchestratorMode.UserDelegate -> "Auto-handle routine, escalate important"
    OrchestratorMode.Spectator -> "Auto-handle all, you can claim"
    OrchestratorMode.Autonomous -> "Full auto, critical queued for review"
}

@Composable
fun ActivityFeedScreen(viewModel: MainViewModel) {
    val events by viewModel.activityFeed.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Activity Feed",
            style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                   else MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No recent activity")
            }
        } else {
            LazyColumn {
                items(events) { event ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(event.title, style = MaterialTheme.typography.titleMedium)
                            event.body?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ParkedAgentsScreen(viewModel: MainViewModel) {
    val parkedAgents by viewModel.parkedAgents.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Parked Agents",
            style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                   else MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        
        if (parkedAgents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No parked agents")
            }
        } else {
            LazyColumn {
                items(parkedAgents) { agent ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(agent.id, style = MaterialTheme.typography.titleMedium)
                                agent.workspace?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            Button(onClick = { /* Unpark agent */ }) {
                                Text("Unpark")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TaskQueueScreen(viewModel: MainViewModel) {
    val tasks by viewModel.tasks.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Task Queue",
            style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                   else MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(16.dp))
        
        if (tasks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("No pending tasks")
            }
        } else {
            LazyColumn {
                items(tasks) { task ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    getPriorityEmoji(task.priority),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(task.content, style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                "Status: ${task.status}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getPriorityEmoji(priority: com.continuumstudio.tablet.data.models.TaskPriority): String = when (priority) {
    com.continuumstudio.tablet.data.models.TaskPriority.Critical -> "🚨"
    com.continuumstudio.tablet.data.models.TaskPriority.High -> "🔴"
    com.continuumstudio.tablet.data.models.TaskPriority.Medium -> "🟡"
    com.continuumstudio.tablet.data.models.TaskPriority.Low -> "🟢"
    com.continuumstudio.tablet.data.models.TaskPriority.Backlog -> "⚪"
}

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val isFiveFootMode = LocalIsFiveFootMode.current
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Settings",
                style = if (isFiveFootMode) MaterialTheme.typography.headlineLarge 
                       else MaterialTheme.typography.headlineMedium
            )
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Display", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    
                    Text("Font Scale: ${(settings.fontScale * 100).toInt()}%")
                    Slider(
                        value = settings.fontScale,
                        onValueChange = { newValue -> viewModel.updateSettings { s -> s.copy(fontScale = newValue) } },
                        valueRange = 0.75f..2f,
                        steps = 4
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("High Contrast")
                        Switch(
                            checked = settings.highContrast,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(highContrast = it) } }
                        )
                    }
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Text-to-Speech", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Enable TTS")
                        Switch(
                            checked = settings.ttsEnabled,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(ttsEnabled = it) } }
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Auto-read Dialogs")
                        Switch(
                            checked = settings.ttsAutoRead,
                            onCheckedChange = { viewModel.updateSettings { s -> s.copy(ttsAutoRead = it) } },
                            enabled = settings.ttsEnabled
                        )
                    }
                    
                    Text("Speech Rate: ${settings.speechRate}x")
                    Slider(
                        value = settings.speechRate,
                        onValueChange = { viewModel.updateSettings { s -> s.copy(speechRate = it) } },
                        valueRange = 0.5f..2f,
                        enabled = settings.ttsEnabled
                    )
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = settings.phoenixUrl,
                        onValueChange = { newUrl -> viewModel.updateSettings { s -> s.copy(phoenixUrl = newUrl) } },
                        label = { Text("Phoenix Server URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = settings.dialogUrl,
                        onValueChange = { newUrl -> viewModel.updateSettings { s -> s.copy(dialogUrl = newUrl) } },
                        label = { Text("Dialog Daemon URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
