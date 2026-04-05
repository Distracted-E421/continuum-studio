package com.example.continuumstudio.ui.widgets

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Connection state for UI representation
 */
enum class ConnectionStatus {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
    ERROR
}

/**
 * Context-aware Floating Action Button that changes based on app state.
 *
 * Features:
 * - Large tap target (72dp) for one-handed use
 * - Color indicates connection state
 * - Pulsing animation when dialog is pending
 * - Long-press triggers radial menu
 * - Single tap performs primary action (refresh when connected, reconnect when not)
 *
 * @param connectionStatus Current connection state
 * @param hasPendingDialog Whether there's a dialog awaiting response
 * @param onTap Primary action (single tap)
 * @param onLongPress Triggers radial menu
 * @param modifier Modifier for positioning
 */
@Composable
fun ContextAwareFAB(
    connectionStatus: ConnectionStatus,
    hasPendingDialog: Boolean = false,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    
    // Color based on connection state
    val targetColor = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) // Green
        ConnectionStatus.CONNECTING -> Color(0xFFFFC107) // Yellow
        ConnectionStatus.DISCONNECTED -> Color.Gray
        ConnectionStatus.ERROR -> Color(0xFFF44336) // Red
    }
    
    val backgroundColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(300),
        label = "fab_color"
    )
    
    // Icon based on state
    val icon: ImageVector = when {
        hasPendingDialog -> Icons.Default.QuestionAnswer
        connectionStatus == ConnectionStatus.CONNECTED -> Icons.Default.MoreVert
        connectionStatus == ConnectionStatus.CONNECTING -> Icons.Default.Sync
        else -> Icons.Default.Refresh
    }
    
    // Pulse animation for pending dialog
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (hasPendingDialog) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (hasPendingDialog) 0.7f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    
    // Press state for visual feedback
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "press_scale"
    )
    
    Box(
        modifier = modifier
            .size(72.dp) // Larger for one-handed use
            .scale(pulseScale * pressScale)
            .clip(CircleShape)
            .background(backgroundColor.copy(alpha = pulseAlpha))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = when {
                hasPendingDialog -> "Respond to dialog"
                connectionStatus == ConnectionStatus.CONNECTED -> "Actions menu"
                else -> "Reconnect"
            },
            tint = Color.White,
            modifier = Modifier.size(32.dp)
        )
    }
}

/**
 * Large status indicator showing connection state at a glance.
 * Tap to expand for more details.
 *
 * @param connectionStatus Current connection state
 * @param endpointName Name of current endpoint
 * @param latency Current latency in ms (null if not available)
 * @param onTap Callback when tapped (show details or switch endpoint)
 * @param onLongPress Callback for long press (quick switch endpoint)
 */
@Composable
fun StatusIndicator(
    connectionStatus: ConnectionStatus,
    endpointName: String,
    latency: Long?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    
    val color = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
        ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
        ConnectionStatus.DISCONNECTED -> Color.Gray
        ConnectionStatus.ERROR -> Color(0xFFF44336)
    }
    
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "status_press_scale"
    )
    
    Row(
        modifier = modifier
            .scale(pressScale)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onTap()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Large colored circle
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(color)
        )
        
        Column {
            Text(
                text = endpointName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (latency != null && connectionStatus == ConnectionStatus.CONNECTED) {
                Text(
                    text = "${latency}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        latency < 100 -> Color(0xFF4CAF50)
                        latency < 300 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
            } else {
                Text(
                    text = when (connectionStatus) {
                        ConnectionStatus.CONNECTED -> "Online"
                        ConnectionStatus.CONNECTING -> "Connecting..."
                        ConnectionStatus.DISCONNECTED -> "Offline"
                        ConnectionStatus.ERROR -> "Error"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Preview composable for testing
 */
@Composable
fun FABPreview() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Connection States:", color = Color.White)
        
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ContextAwareFAB(
                connectionStatus = ConnectionStatus.CONNECTED,
                onTap = {},
                onLongPress = {}
            )
            ContextAwareFAB(
                connectionStatus = ConnectionStatus.CONNECTING,
                onTap = {},
                onLongPress = {}
            )
            ContextAwareFAB(
                connectionStatus = ConnectionStatus.DISCONNECTED,
                onTap = {},
                onLongPress = {}
            )
            ContextAwareFAB(
                connectionStatus = ConnectionStatus.ERROR,
                onTap = {},
                onLongPress = {}
            )
        }
        
        Text("With Pending Dialog:", color = Color.White)
        ContextAwareFAB(
            connectionStatus = ConnectionStatus.CONNECTED,
            hasPendingDialog = true,
            onTap = {},
            onLongPress = {}
        )
        
        Text("Status Indicators:", color = Color.White)
        StatusIndicator(
            connectionStatus = ConnectionStatus.CONNECTED,
            endpointName = "zen1 (Primary)",
            latency = 45,
            onTap = {},
            onLongPress = {}
        )
        StatusIndicator(
            connectionStatus = ConnectionStatus.CONNECTING,
            endpointName = "Obsidian",
            latency = null,
            onTap = {},
            onLongPress = {}
        )
    }
}
