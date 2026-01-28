package com.example.continuumstudio.ui.dialog

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogScreen(
    connectionState: ConnectionState,
    dialogState: DialogUiState,
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
) {
    var serverUrlInput by remember { mutableStateOf(connectionState.serverUrl.ifBlank { "obsidian:8080" }) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Continuum Studio")
                        Text(
                            text = if (connectionState.isConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connectionState.isConnected) Color(0xFF4CAF50) else Color.Gray
                        )
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Connection settings (collapsible)
            if (showSettings || !connectionState.isConnected) {
                ConnectionCard(
                    serverUrl = serverUrlInput,
                    onServerUrlChange = { serverUrlInput = it },
                    isConnected = connectionState.isConnected,
                    isConnecting = connectionState.isConnecting,
                    error = connectionState.errorMessage,
                    onConnect = { onConnect(serverUrlInput) },
                    onDisconnect = onDisconnect,
                )
            }

            // Main content
            if (connectionState.isConnected) {
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
                    )
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

@Composable
fun ConnectionCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    isConnected: Boolean,
    isConnecting: Boolean,
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
                placeholder = { Text("obsidian:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected && !isConnecting
            )

            Spacer(modifier = Modifier.height(12.dp))

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
                        enabled = !isConnecting && serverUrl.isNotBlank()
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

        // Prompt
        Text(
            text = dialog.prompt,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

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

