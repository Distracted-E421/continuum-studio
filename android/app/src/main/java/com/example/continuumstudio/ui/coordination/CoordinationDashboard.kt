package com.example.continuumstudio.ui.coordination

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.data.*
import com.example.continuumstudio.viewmodel.CoordinationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinationDashboard(
    viewModel: CoordinationViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val isConnected by viewModel.isConnected.collectAsState()
    val error by viewModel.error.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.startPolling()
    }
    
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopPolling()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Coordination") },
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
            error?.let { errorMessage ->
                ErrorBanner(
                    message = errorMessage,
                    onDismiss = { viewModel.clearError() }
                )
            }
            
            CountsSummary(counts = uiState.counts)
            
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                CoordinationTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = null) }
                    )
                }
            }
            
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            
            when (uiState.selectedTab) {
                CoordinationTab.OVERVIEW -> OverviewTab(
                    overview = uiState.overview,
                    health = uiState.health
                )
                CoordinationTab.LOCKS -> LocksTab(
                    locks = uiState.locks,
                    onReleaseLock = { viewModel.releaseLock(it) }
                )
                CoordinationTab.CONFLICTS -> ConflictsTab(
                    conflicts = uiState.conflicts,
                    stats = uiState.conflictStats,
                    onResolveConflict = { id, resolution -> viewModel.resolveConflict(id, resolution) }
                )
                CoordinationTab.HANDOFFS -> HandoffsTab(
                    handoffs = uiState.handoffs,
                    history = uiState.handoffHistory,
                    onAcceptHandoff = { id, agent -> viewModel.acceptHandoff(id, agent) },
                    onCancelHandoff = { viewModel.cancelHandoff(it) }
                )
                CoordinationTab.STATE -> StateTab(
                    namespaces = uiState.stateNamespaces
                )
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
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (isConnected) "Connected" else "Disconnected",
            style = MaterialTheme.typography.labelSmall
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
private fun CountsSummary(counts: CoordinationCounts) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        CountBadge(
            label = "Locks",
            count = counts.activeLocks,
            icon = Icons.Default.Lock,
            color = Color(0xFF2196F3)
        )
        CountBadge(
            label = "Conflicts",
            count = counts.activeConflicts,
            icon = Icons.Default.Warning,
            color = if (counts.activeConflicts > 0) Color(0xFFF44336) else Color(0xFF4CAF50)
        )
        CountBadge(
            label = "Handoffs",
            count = counts.pendingHandoffs,
            icon = Icons.Default.SwapHoriz,
            color = Color(0xFFFF9800)
        )
    }
}

@Composable
private fun CountBadge(
    label: String,
    count: Int,
    icon: ImageVector,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = color.copy(alpha = 0.1f),
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = color
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

private val CoordinationTab.title: String
    get() = when (this) {
        CoordinationTab.OVERVIEW -> "Overview"
        CoordinationTab.LOCKS -> "Locks"
        CoordinationTab.CONFLICTS -> "Conflicts"
        CoordinationTab.HANDOFFS -> "Handoffs"
        CoordinationTab.STATE -> "State"
    }

private val CoordinationTab.icon: ImageVector
    get() = when (this) {
        CoordinationTab.OVERVIEW -> Icons.Default.Dashboard
        CoordinationTab.LOCKS -> Icons.Default.Lock
        CoordinationTab.CONFLICTS -> Icons.Default.Warning
        CoordinationTab.HANDOFFS -> Icons.Default.SwapHoriz
        CoordinationTab.STATE -> Icons.Default.Storage
    }

// === Tab Content ===

@Composable
private fun OverviewTab(overview: CoordinationOverview?, health: HealthStatus?) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Service Information",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    overview?.let {
                        InfoRow("Service", it.service)
                        InfoRow("Version", it.version)
                    } ?: Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Health Status",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    health?.let { h ->
                        StatusChip(
                            status = h.status,
                            isHealthy = h.status == "healthy"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        h.services.forEach { (name, serviceHealth) ->
                            ServiceHealthRow(name = name, health = serviceHealth)
                        }
                    } ?: Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Available Endpoints",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    overview?.endpoints?.forEach { (method, path) ->
                        Text(
                            text = "$method $path",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    } ?: Text("Loading...", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun LocksTab(locks: List<Lock>, onReleaseLock: (String) -> Unit) {
    if (locks.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Lock,
            message = "No active locks"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(locks, key = { it.id }) { lock ->
                LockCard(lock = lock, onRelease = { onReleaseLock(lock.id) })
            }
        }
    }
}

@Composable
private fun ConflictsTab(
    conflicts: List<Conflict>,
    stats: ConflictStats?,
    onResolveConflict: (String, String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        stats?.let { s ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Conflict Statistics",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("Total", s.totalDetected)
                            StatItem("Resolved", s.resolved)
                            StatItem("Active", s.active)
                        }
                    }
                }
            }
        }
        
        if (conflicts.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.CheckCircle,
                    message = "No active conflicts"
                )
            }
        } else {
            items(conflicts, key = { it.id }) { conflict ->
                ConflictCard(
                    conflict = conflict,
                    onResolve = { resolution -> onResolveConflict(conflict.id, resolution) }
                )
            }
        }
    }
}

@Composable
private fun HandoffsTab(
    handoffs: List<Handoff>,
    history: List<Handoff>,
    onAcceptHandoff: (String, String) -> Unit,
    onCancelHandoff: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Pending Handoffs",
                style = MaterialTheme.typography.titleMedium
            )
        }
        
        if (handoffs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Default.SwapHoriz,
                    message = "No pending handoffs"
                )
            }
        } else {
            items(handoffs, key = { it.id }) { handoff ->
                HandoffCard(
                    handoff = handoff,
                    onAccept = { agent -> onAcceptHandoff(handoff.id, agent) },
                    onCancel = { onCancelHandoff(handoff.id) }
                )
            }
        }
        
        if (history.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Recent History",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            items(history.take(10), key = { "history-${it.id}" }) { handoff ->
                HandoffHistoryCard(handoff = handoff)
            }
        }
    }
}

@Composable
private fun StateTab(namespaces: List<SharedStateNamespace>) {
    if (namespaces.isEmpty()) {
        EmptyState(
            icon = Icons.Default.Storage,
            message = "No shared state namespaces"
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(namespaces, key = { it.name }) { namespace ->
                NamespaceCard(namespace = namespace)
            }
        }
    }
}

// === Card Components ===

@Composable
private fun LockCard(lock: Lock, onRelease: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${lock.resourceType}/${lock.resourceId}",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Mode: ${lock.mode}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                ModeChip(mode = lock.mode)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "Holders: ${lock.holders.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Acquired: ${lock.acquiredAt}",
                style = MaterialTheme.typography.bodySmall
            )
            lock.expiresAt?.let {
                Text(
                    text = "Expires: $it",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = onRelease,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.align(Alignment.End)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Release")
            }
        }
    }
}

@Composable
private fun ConflictCard(conflict: Conflict, onResolve: (String?) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conflict.type,
                    style = MaterialTheme.typography.titleSmall
                )
                SeverityChip(severity = conflict.severity)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = conflict.description,
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Agents: ${conflict.agents.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Resource: ${conflict.resourceType}/${conflict.resourceId}",
                style = MaterialTheme.typography.bodySmall
            )
            
            if (conflict.suggestedResolutions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Suggested resolutions:",
                    style = MaterialTheme.typography.labelMedium
                )
                conflict.suggestedResolutions.forEach { resolution ->
                    TextButton(onClick = { onResolve(resolution) }) {
                        Text("• $resolution")
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { onResolve(null) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Resolve")
            }
        }
    }
}

@Composable
private fun HandoffCard(
    handoff: Handoff,
    onAccept: (String) -> Unit,
    onCancel: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Task: ${handoff.taskId}",
                    style = MaterialTheme.typography.titleSmall
                )
                StatusChip(status = handoff.status, isHealthy = handoff.status == "completed")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = handoff.fromAgent, style = MaterialTheme.typography.bodyMedium)
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Text(text = handoff.toAgent, style = MaterialTheme.typography.bodyMedium)
            }
            
            handoff.reason?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Reason: $it",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Text(
                text = "Initiated: ${handoff.initiatedAt}",
                style = MaterialTheme.typography.bodySmall
            )
            
            if (handoff.status == "pending") {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onAccept(handoff.toAgent) }) {
                        Text("Accept")
                    }
                }
            }
        }
    }
}

@Composable
private fun HandoffHistoryCard(handoff: Handoff) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${handoff.fromAgent} → ${handoff.toAgent}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = handoff.taskId,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            StatusChip(
                status = handoff.status,
                isHealthy = handoff.status == "completed"
            )
        }
    }
}

@Composable
private fun NamespaceCard(namespace: SharedStateNamespace) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = namespace.name,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${namespace.keyCount} keys",
                style = MaterialTheme.typography.bodySmall
            )
            
            if (namespace.scopes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Scopes: ${namespace.scopes.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// === Helper Components ===

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ServiceHealthRow(name: String, health: ServiceHealth) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
        StatusChip(status = health.status, isHealthy = health.status == "up")
    }
}

@Composable
private fun StatusChip(status: String, isHealthy: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isHealthy) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFFF44336).copy(alpha = 0.2f)
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHealthy) Color(0xFF4CAF50) else Color(0xFFF44336),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ModeChip(mode: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (mode == "exclusive") Color(0xFFF44336).copy(alpha = 0.2f) else Color(0xFF2196F3).copy(alpha = 0.2f)
    ) {
        Text(
            text = mode,
            style = MaterialTheme.typography.labelSmall,
            color = if (mode == "exclusive") Color(0xFFF44336) else Color(0xFF2196F3),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SeverityChip(severity: String) {
    val color = when (severity) {
        "critical" -> Color(0xFFF44336)
        "high" -> Color(0xFFFF9800)
        "medium" -> Color(0xFFFFC107)
        else -> Color(0xFF4CAF50)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = severity,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun EmptyState(icon: ImageVector, message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
