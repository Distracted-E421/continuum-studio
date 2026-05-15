package com.example.continuumstudio.ui.widgets

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/**
 * Represents an option in the radial menu
 */
data class RadialOption(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color = Color.White,
    val enabled: Boolean = true,
    val isDestructive: Boolean = false, // Red highlight for cancel/delete/decline options
)

/**
 * State holder for radial menu
 */
@Stable
class RadialMenuState(
    val options: List<RadialOption>,
    val centerRadius: Dp = 40.dp,
    val menuRadius: Dp = 120.dp,
) {
    var isVisible by mutableStateOf(false)
    var selectedIndex by mutableIntStateOf(-1)
    var dragOffset by mutableStateOf(Offset.Zero)
}

/**
 * Creates and remembers a RadialMenuState
 */
@Composable
fun rememberRadialMenuState(
    options: List<RadialOption>,
    centerRadius: Dp = 40.dp,
    menuRadius: Dp = 120.dp,
): RadialMenuState {
    return remember(options) {
        RadialMenuState(options, centerRadius, menuRadius)
    }
}

/**
 * Radial (Pie) Menu Composable - Hybrid Design
 *
 * A circular menu with split display/interaction zones for optimal phone ergonomics.
 * 
 * HYBRID DESIGN:
 * - Visual display: Large and CENTERED on screen for easy viewing
 * - Interaction zone: Anchored to BOTTOM-RIGHT corner (thumb position)
 * - Result: See options clearly while keeping thumb comfortable
 *
 * Key UX features:
 * - Large centered pie menu for visibility
 * - Thumb zone indicator in corner shows drag origin
 * - Selection based on drag from thumb zone, not screen center
 * - Release without dragging to dismiss
 *
 * @param state The menu state containing options and visibility
 * @param anchorOffset Position for thumb zone (offset from bottom-right corner)
 * @param onOptionSelected Callback when an option is selected
 * @param onDismiss Callback when the menu is dismissed without selection
 * @param modifier Modifier for the container
 */
@Composable
fun RadialMenu(
    state: RadialMenuState,
    anchorOffset: Offset = Offset(55f, 110f), // x: right from center, y: up from bottom (matches visual)
    onOptionSelected: (RadialOption) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    
    val centerRadiusPx = with(density) { state.centerRadius.toPx() }
    val menuRadiusPx = with(density) { state.menuRadius.toPx() }
    // Thumb zone - larger for easier interaction
    val thumbZoneRadius = with(density) { 80.dp.toPx() }
    
    val options = state.options
    val segmentAngle = if (options.isNotEmpty()) 360f / options.size else 0f
    
    // Animation for menu appearance - smoother spring
    val scale by animateFloatAsState(
        targetValue = if (state.isVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "radial_scale"
    )
    
    // Animated arrow angle for smooth selection transitions
    val targetArrowAngle = if (state.selectedIndex >= 0) {
        -90f + (state.selectedIndex + 0.5f) * segmentAngle
    } else {
        0f
    }
    val animatedArrowAngle by animateFloatAsState(
        targetValue = targetArrowAngle,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "arrow_angle"
    )
    
    // Animated selection scale for smooth grow effect
    val selectionScale by animateFloatAsState(
        targetValue = if (state.selectedIndex >= 0) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "selection_scale"
    )
    
    // Track previous selection for haptic feedback
    var prevSelectedIndex by remember { mutableIntStateOf(-1) }
    
    // Track if we've moved enough to be "selecting" vs just tapping
    var hasDragged by remember { mutableStateOf(false) }
    
    if (state.isVisible || scale > 0.01f) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f * scale))
                .pointerInput(state.isVisible) {
                    if (!state.isVisible) return@pointerInput
                    
                    detectDragGestures(
                        onDragStart = {
                            hasDragged = false
                        },
                        onDragEnd = {
                            if (state.selectedIndex >= 0 && state.selectedIndex < options.size && hasDragged) {
                                val selected = options[state.selectedIndex]
                                if (selected.enabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onOptionSelected(selected)
                                }
                            } else {
                                onDismiss()
                            }
                            state.isVisible = false
                            state.selectedIndex = -1
                            state.dragOffset = Offset.Zero
                            hasDragged = false
                        },
                        onDragCancel = {
                            onDismiss()
                            state.isVisible = false
                            state.selectedIndex = -1
                            state.dragOffset = Offset.Zero
                            hasDragged = false
                        },
                        onDrag = { change, _ ->
                            // Calculate position relative to THUMB ZONE (bottom-center, shifted right)
                            // This matches the visual placement: BottomCenter + offset(x=55dp, y=-20dp)
                            val thumbZoneCenterX = size.width / 2 + anchorOffset.x
                            val thumbZoneCenterY = size.height - anchorOffset.y
                            
                            state.dragOffset = change.position - Offset(thumbZoneCenterX, thumbZoneCenterY)
                            
                            val distance = state.dragOffset.getDistance()
                            
                            // Mark as dragged if moved beyond minimal threshold (very sensitive)
                            if (distance > 10f) {
                                hasDragged = true
                            }
                            
                            // Start detecting selection at low threshold for immediate recognition
                            if (distance > 20f && options.isNotEmpty()) {
                                // Calculate angle and determine segment
                                val angle = atan2(state.dragOffset.y, state.dragOffset.x)
                                var degrees = Math.toDegrees(angle.toDouble()).toFloat()
                                // Normalize to 0-360, starting from top (12 o'clock)
                                degrees = (degrees + 90 + 360) % 360
                                
                                val newIndex = (degrees / segmentAngle).toInt().coerceIn(0, options.size - 1)
                                
                                if (newIndex != state.selectedIndex) {
                                    state.selectedIndex = newIndex
                                    if (newIndex != prevSelectedIndex) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        prevSelectedIndex = newIndex
                                    }
                                }
                            } else {
                                state.selectedIndex = -1
                            }
                        }
                    )
                }
        ) {
            // ===== CENTERED VISUAL MENU =====
            // This is the large pie chart display in the CENTER of screen
            val screenCenterX = maxWidth / 2
            val screenCenterY = maxHeight / 2 - 40.dp // Slightly above true center to avoid bottom nav
            
            Canvas(
                modifier = Modifier
                    .size(state.menuRadius * 3f) // Larger visual display
                    .align(Alignment.Center)
                    .offset(y = -40.dp) // Shift up slightly
            ) {
                val centerX = size.width / 2
                val centerY = size.height / 2
                val scaledMenuRadius = menuRadiusPx * 1.2f * scale // 20% larger for visibility
                val scaledCenterRadius = centerRadiusPx * 1.2f * scale
                
                // Draw segments
                options.forEachIndexed { index, option ->
                    val startAngle = -90f + index * segmentAngle
                    val isSelected = index == state.selectedIndex
                    
                    // Apply selection scale to selected segment for smooth grow effect
                    val segmentRadius = if (isSelected) scaledMenuRadius * selectionScale else scaledMenuRadius
                    
                    // Red color for destructive options (cancel, delete, decline, etc.)
                    val destructiveColor = Color(0xFFF44336) // Material Red 500
                    val segmentColor = when {
                        !option.enabled -> Color.Gray.copy(alpha = 0.3f)
                        option.isDestructive && isSelected -> destructiveColor.copy(alpha = 0.7f)
                        option.isDestructive -> destructiveColor.copy(alpha = 0.35f) // Subtle red when not selected
                        isSelected -> accentColor.copy(alpha = 0.6f)
                        else -> Color(0xFF2D2D2D).copy(alpha = 0.9f)
                    }
                    
                    drawArc(
                        color = segmentColor,
                        startAngle = startAngle,
                        sweepAngle = segmentAngle - 3f, // Gap between segments
                        useCenter = true,
                        topLeft = Offset(centerX - segmentRadius, centerY - segmentRadius),
                        size = androidx.compose.ui.geometry.Size(segmentRadius * 2, segmentRadius * 2)
                    )
                    
                    // Draw border for selected segment
                    if (isSelected && option.enabled) {
                        val borderColor = if (option.isDestructive) destructiveColor else accentColor
                        drawArc(
                            color = borderColor,
                            startAngle = startAngle,
                            sweepAngle = segmentAngle - 3f,
                            useCenter = true,
                            topLeft = Offset(centerX - segmentRadius, centerY - segmentRadius),
                            size = androidx.compose.ui.geometry.Size(segmentRadius * 2, segmentRadius * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx())
                        )
                    }
                    
                    // Calculate label position (middle of segment, at 65% radius)
                    // Labels move with segment when selected (smooth animation)
                    val labelAngle = Math.toRadians((startAngle + segmentAngle / 2).toDouble())
                    val labelRadius = segmentRadius * 0.65f
                    val labelX = centerX + cos(labelAngle).toFloat() * labelRadius
                    val labelY = centerY + sin(labelAngle).toFloat() * labelRadius
                    
                    // Draw label - larger when selected, red for destructive
                    val labelColor = when {
                        !option.enabled -> Color.Gray
                        option.isDestructive && isSelected -> Color.White
                        option.isDestructive -> Color(0xFFFFCDD2) // Light red tint
                        isSelected -> Color.White
                        else -> Color.White.copy(alpha = 0.9f)
                    }
                    val textLayout = textMeasurer.measure(
                        text = option.label,
                        style = TextStyle(
                            color = labelColor,
                            fontSize = if (isSelected) 16.sp else 14.sp
                        )
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(
                            labelX - textLayout.size.width / 2,
                            labelY - textLayout.size.height / 2
                        )
                    )
                }
                
                // Draw center with current selection label
                drawCircle(
                    color = Color(0xFF1A1A1A),
                    radius = scaledCenterRadius,
                    center = Offset(centerX, centerY)
                )
                
                // Center indicator color based on selection
                drawCircle(
                    color = if (state.selectedIndex >= 0) accentColor else Color(0xFF444444),
                    radius = scaledCenterRadius * 0.4f,
                    center = Offset(centerX, centerY)
                )
            }
            
            // ===== THUMB ZONE INDICATOR =====
            // Larger, shaded indicator positioned more toward center-bottom
            Canvas(
                modifier = Modifier
                    .size(180.dp)
                    .align(Alignment.BottomCenter)
                    .offset(x = 55.dp, y = (-20).dp) // Shift right from center, up from bottom
            ) {
                val thumbCenterX = size.width / 2
                val thumbCenterY = size.height / 2
                val scaledThumbZone = thumbZoneRadius * scale
                
                // Filled background - shaded drag zone
                drawCircle(
                    color = Color(0xFF1A1A1A).copy(alpha = 0.8f),
                    radius = scaledThumbZone,
                    center = Offset(thumbCenterX, thumbCenterY)
                )
                
                // Outer circle - drag zone boundary (thicker stroke)
                drawCircle(
                    color = accentColor.copy(alpha = 0.6f),
                    radius = scaledThumbZone,
                    center = Offset(thumbCenterX, thumbCenterY),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                )
                
                // Direction indicator arrow from center - using animated angle for smooth movement
                if (state.selectedIndex >= 0 || animatedArrowAngle != 0f) {
                    val arrowAngleRad = Math.toRadians(animatedArrowAngle.toDouble())
                    val arrowLength = scaledThumbZone * 0.8f * (if (state.selectedIndex >= 0) 1f else 0.5f)
                    val arrowEndX = thumbCenterX + cos(arrowAngleRad).toFloat() * arrowLength
                    val arrowEndY = thumbCenterY + sin(arrowAngleRad).toFloat() * arrowLength
                    
                    val arrowAlpha = if (state.selectedIndex >= 0) 1f else 0.3f
                    
                    drawLine(
                        color = accentColor.copy(alpha = arrowAlpha),
                        start = Offset(thumbCenterX, thumbCenterY),
                        end = Offset(arrowEndX, arrowEndY),
                        strokeWidth = 4.dp.toPx()
                    )
                    
                    // Arrowhead (only when selected)
                    if (state.selectedIndex >= 0) {
                        val arrowHeadSize = 12.dp.toPx()
                        val arrowHeadAngle1 = arrowAngleRad + Math.PI * 0.8
                        val arrowHeadAngle2 = arrowAngleRad - Math.PI * 0.8
                        val head1X = arrowEndX + cos(arrowHeadAngle1).toFloat() * arrowHeadSize
                        val head1Y = arrowEndY + sin(arrowHeadAngle1).toFloat() * arrowHeadSize
                        val head2X = arrowEndX + cos(arrowHeadAngle2).toFloat() * arrowHeadSize
                        val head2Y = arrowEndY + sin(arrowHeadAngle2).toFloat() * arrowHeadSize
                        
                        drawLine(color = accentColor, start = Offset(arrowEndX, arrowEndY), end = Offset(head1X, head1Y), strokeWidth = 3.dp.toPx())
                        drawLine(color = accentColor, start = Offset(arrowEndX, arrowEndY), end = Offset(head2X, head2Y), strokeWidth = 3.dp.toPx())
                    }
                }
                
                // Inner circle - drag origin (larger)
                drawCircle(
                    color = if (state.selectedIndex >= 0) accentColor else Color(0xFF888888),
                    radius = 18.dp.toPx() * scale,
                    center = Offset(thumbCenterX, thumbCenterY)
                )
                
                // "Drag here" text when nothing selected
                if (state.selectedIndex < 0 && scale > 0.5f) {
                    val textLayout = textMeasurer.measure(
                        text = "DRAG",
                        style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
                    )
                    drawText(
                        textLayoutResult = textLayout,
                        topLeft = Offset(thumbCenterX - textLayout.size.width / 2, thumbCenterY + 28.dp.toPx())
                    )
                }
            }
            
            // Instruction text at top
            if (scale > 0.5f) {
                Text(
                    text = if (state.selectedIndex >= 0) {
                        "Release to select: ${options.getOrNull(state.selectedIndex)?.label ?: ""}"
                    } else {
                        "Drag in circle below • Release to cancel"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 60.dp)
                )
            }
        }
    }
}

/**
 * Quick Actions radial menu preset
 * 
 * Uses hybrid design: Large centered visual menu + thumb zone in corner
 */
@Composable
fun QuickActionsRadialMenu(
    isVisible: Boolean,
    onRefresh: () -> Unit,
    onSwitchEndpoint: () -> Unit,
    onSettings: () -> Unit,
    onTestAll: () -> Unit,
    onViewLogs: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    // Anchor offset: x = right from center, y = up from bottom (matches visual)
    anchorOffset: Offset = Offset(55f, 110f),
) {
    val options = remember {
        listOf(
            RadialOption("refresh", "Refresh", Icons.Default.Refresh),
            RadialOption("endpoint", "Endpoint", Icons.Default.Dns),
            RadialOption("test_all", "Test All", Icons.Default.NetworkCheck),
            RadialOption("logs", "Logs", Icons.Default.History),
            RadialOption("settings", "Settings", Icons.Default.Settings),
        )
    }
    
    // Larger radius for visual display
    val state = rememberRadialMenuState(
        options = options,
        centerRadius = 50.dp,
        menuRadius = 140.dp, // Large for visibility
    )
    
    LaunchedEffect(isVisible) {
        state.isVisible = isVisible
    }
    
    RadialMenu(
        state = state,
        anchorOffset = anchorOffset,
        onOptionSelected = { option ->
            when (option.id) {
                "refresh" -> onRefresh()
                "endpoint" -> onSwitchEndpoint()
                "settings" -> onSettings()
                "test_all" -> onTestAll()
                "logs" -> onViewLogs()
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
        accentColor = accentColor,
    )
}

/**
 * Dialog Response radial menu - dynamically built from dialog options
 * 
 * Uses hybrid design: Large centered visual menu + thumb zone in corner
 */
@Composable
fun DialogResponseRadialMenu(
    isVisible: Boolean,
    options: List<Pair<String, String>>, // id to label pairs
    onSelectOption: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    anchorOffset: Offset = Offset(55f, 110f),
) {
    val radialOptions = remember(options) {
        val positiveIcons = listOf(
            Icons.Default.Check,
            Icons.Default.Star,
            Icons.Default.Favorite,
            Icons.Default.ThumbUp,
            Icons.Default.Done,
            Icons.Default.PlayArrow,
        )
        
        // Patterns for negative/destructive options
        val negativePatterns = listOf("no", "cancel", "decline", "reject", "deny", "false", "never")
        
        options.mapIndexed { index, (id, label) ->
            val lowerLabel = label.lowercase()
            val lowerId = id.lowercase()
            val isNegative = negativePatterns.any { pattern ->
                lowerLabel == pattern || lowerId == pattern || 
                lowerLabel.startsWith("$pattern ") || lowerLabel.endsWith(" $pattern")
            }
            
            RadialOption(
                id = id,
                label = if (label.length > 12) label.take(10) + "..." else label,
                icon = if (isNegative) Icons.Default.Close else positiveIcons.getOrElse(index) { Icons.Default.Check },
                isDestructive = isNegative
            )
        } + RadialOption("__cancel__", "Cancel", Icons.Default.Close, isDestructive = true)
    }
    
    // Larger radius for visual display
    val state = rememberRadialMenuState(
        options = radialOptions,
        centerRadius = 50.dp,
        menuRadius = 140.dp, // Large for visibility
    )
    
    LaunchedEffect(isVisible) {
        state.isVisible = isVisible
    }
    
    RadialMenu(
        state = state,
        anchorOffset = anchorOffset,
        onOptionSelected = { option ->
            if (option.id == "__cancel__") {
                onCancel()
            } else {
                onSelectOption(option.id)
            }
        },
        onDismiss = onDismiss,
        modifier = modifier,
        accentColor = accentColor,
    )
}
