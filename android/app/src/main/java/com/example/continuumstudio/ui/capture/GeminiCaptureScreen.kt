package com.example.continuumstudio.ui.capture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.service.GeminiInteraction
import com.example.continuumstudio.viewmodel.GeminiCaptureViewModel
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiCaptureScreen(
    viewModel: GeminiCaptureViewModel = viewModel(),
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val isEnabled by viewModel.isServiceEnabled.collectAsState()
    val interactions by viewModel.interactions.collectAsState()
    val stats by viewModel.captureStats.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkServiceStatus(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemini Capture") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (interactions.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(Icons.Default.Delete, "Clear")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            ServiceStatusCard(
                isEnabled = isEnabled,
                stats = stats,
                onEnableClick = { viewModel.openAccessibilitySettings(context) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (interactions.isEmpty()) {
                EmptyStateCard()
            } else {
                InteractionsList(interactions)
            }
        }
    }
}

@Composable
private fun ServiceStatusCard(
    isEnabled: Boolean,
    stats: GeminiCaptureViewModel.CaptureStats,
    onEnableClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isEnabled) "Service Active" else "Service Disabled",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isEnabled) {
                        Text(
                            text = "Captures: ${stats.totalCaptures}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        stats.lastCaptureTime?.let { time ->
                            val ago = Duration.between(time, Instant.now()).seconds
                            Text(
                                text = "Last: ${ago}s ago",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                if (!isEnabled) {
                    Button(onClick = onEnableClick) {
                        Text("Enable")
                    }
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (!isEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Enable the accessibility service to capture Gemini interactions.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun EmptyStateCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No captures yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Open Gemini and start a conversation",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun InteractionsList(interactions: List<GeminiInteraction>) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(interactions) { interaction ->
            InteractionCard(interaction)
        }
    }
}

@Composable
private fun InteractionCard(interaction: GeminiInteraction) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Query",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = interaction.timestamp.take(19).replace("T", " "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Text(
                text = interaction.query,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Response",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )

            Text(
                text = interaction.response,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
