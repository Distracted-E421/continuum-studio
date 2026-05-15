package com.example.continuumstudio.ui.xrcontrol

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.xrcontrol.ControlWidget
import com.example.continuumstudio.xrcontrol.ControlWidgetType
import kotlin.math.roundToInt

/**
 * Draggable widget container with resize handles for edit mode
 */
@Composable
fun DraggableWidget(
    widget: ControlWidget,
    cellWidthPx: Float,
    cellHeightPx: Float,
    cellPaddingPx: Float,
    isEditMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    maxGridX: Int,
    maxGridY: Int,
    onSelect: () -> Unit,
    onMove: (newX: Int, newY: Int) -> Unit,
    onResize: (newSpanX: Int, newSpanY: Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    
    // Animation states
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = tween(100),
        label = "drag_scale"
    )
    
    val elevation by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 0.dp,
        animationSpec = tween(100),
        label = "drag_elevation"
    )
    
    val borderColor by animateColorAsState(
        targetValue = when {
            isDragging -> Color(0xFF00FF88)
            isSelected -> Color(0xFF00AAFF)
            isEditMode -> Color(0xFF444444)
            else -> Color.Transparent
        },
        animationSpec = tween(150),
        label = "border_color"
    )
    
    // Calculate position in pixels
    val baseX = cellWidthPx * widget.gridX + cellPaddingPx
    val baseY = cellHeightPx * widget.gridY + cellPaddingPx
    
    // Drag offset tracking
    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    
    // Preview position during drag
    var previewGridX by remember { mutableIntStateOf(widget.gridX) }
    var previewGridY by remember { mutableIntStateOf(widget.gridY) }
    
    // Calculate actual position (base + drag offset or snap to grid)
    val actualX = if (isDragging) baseX + dragOffsetX else baseX
    val actualY = if (isDragging) baseY + dragOffsetY else baseY
    
    Box(
        modifier = modifier
            .offset { IntOffset(actualX.roundToInt(), actualY.roundToInt()) }
            .size(
                width = with(density) { (cellWidthPx * widget.spanX - cellPaddingPx * 2).toDp() },
                height = with(density) { (cellHeightPx * widget.spanY - cellPaddingPx * 2).toDp() }
            )
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(widget.config.backgroundColor.toInt()))
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .then(
                if (isEditMode) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelect()
                                onDragStart()
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            },
                            onDragEnd = {
                                // Snap to grid
                                val newX = ((baseX + dragOffsetX) / cellWidthPx)
                                    .roundToInt()
                                    .coerceIn(0, maxGridX - widget.spanX)
                                val newY = ((baseY + dragOffsetY) / cellHeightPx)
                                    .roundToInt()
                                    .coerceIn(0, maxGridY - widget.spanY)
                                
                                onMove(newX, newY)
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                onDragEnd()
                            },
                            onDragCancel = {
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                                onDragEnd()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                                
                                // Calculate preview grid position
                                previewGridX = ((baseX + dragOffsetX) / cellWidthPx)
                                    .roundToInt()
                                    .coerceIn(0, maxGridX - widget.spanX)
                                previewGridY = ((baseY + dragOffsetY) / cellHeightPx)
                                    .roundToInt()
                                    .coerceIn(0, maxGridY - widget.spanY)
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        // Widget content
        content()
        
        // Edit mode overlay with resize handles and actions
        if (isEditMode && isSelected) {
            EditOverlay(
                widget = widget,
                cellWidthPx = cellWidthPx,
                cellHeightPx = cellHeightPx,
                maxGridX = maxGridX,
                maxGridY = maxGridY,
                onResize = onResize,
                onDelete = onDelete,
                onDuplicate = onDuplicate
            )
        }
    }
}

@Composable
private fun BoxScope.EditOverlay(
    widget: ControlWidget,
    cellWidthPx: Float,
    cellHeightPx: Float,
    maxGridX: Int,
    maxGridY: Int,
    onResize: (newSpanX: Int, newSpanY: Int) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    
    // Semi-transparent overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
    )
    
    // Move indicator in center
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF333333).copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.OpenWith,
            contentDescription = "Drag to move",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
    
    // Action buttons at top
    Row(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Delete button
        IconButton(
            onClick = { 
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDelete() 
            },
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF4444))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Delete",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        
        // Duplicate button
        IconButton(
            onClick = { 
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDuplicate() 
            },
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFF00AAFF))
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Duplicate",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
    
    // Resize handles at corners
    // Bottom-right resize handle (primary)
    if (widget.gridX + widget.spanX < maxGridX || widget.gridY + widget.spanY < maxGridY) {
        ResizeHandle(
            modifier = Modifier.align(Alignment.BottomEnd),
            canExpandX = widget.gridX + widget.spanX < maxGridX,
            canExpandY = widget.gridY + widget.spanY < maxGridY,
            canShrinkX = widget.spanX > 1,
            canShrinkY = widget.spanY > 1,
            cellWidthPx = cellWidthPx,
            cellHeightPx = cellHeightPx,
            onResize = { dx, dy ->
                val newSpanX = (widget.spanX + dx).coerceIn(1, maxGridX - widget.gridX)
                val newSpanY = (widget.spanY + dy).coerceIn(1, maxGridY - widget.gridY)
                onResize(newSpanX, newSpanY)
            }
        )
    }
}

@Composable
private fun ResizeHandle(
    modifier: Modifier = Modifier,
    canExpandX: Boolean,
    canExpandY: Boolean,
    canShrinkX: Boolean,
    canShrinkY: Boolean,
    cellWidthPx: Float,
    cellHeightPx: Float,
    onResize: (deltaX: Int, deltaY: Int) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var isDragging by remember { mutableStateOf(false) }
    var accumulatedX by remember { mutableFloatStateOf(0f) }
    var accumulatedY by remember { mutableFloatStateOf(0f) }
    
    val handleColor by animateColorAsState(
        targetValue = if (isDragging) Color(0xFF00FF88) else Color.White,
        label = "resize_handle_color"
    )
    
    Box(
        modifier = modifier
            .padding(2.dp)
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333))
            .border(1.dp, handleColor, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        accumulatedX = 0f
                        accumulatedY = 0f
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onDragEnd = {
                        isDragging = false
                        // Apply resize
                        val deltaX = (accumulatedX / cellWidthPx).roundToInt()
                        val deltaY = (accumulatedY / cellHeightPx).roundToInt()
                        if (deltaX != 0 || deltaY != 0) {
                            onResize(deltaX, deltaY)
                        }
                        accumulatedX = 0f
                        accumulatedY = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        accumulatedX = 0f
                        accumulatedY = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (canExpandX || (dragAmount.x < 0 && canShrinkX)) {
                            accumulatedX += dragAmount.x
                        }
                        if (canExpandY || (dragAmount.y < 0 && canShrinkY)) {
                            accumulatedY += dragAmount.y
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.AspectRatio,
            contentDescription = "Resize",
            tint = handleColor,
            modifier = Modifier.size(16.dp)
        )
    }
}

/**
 * Widget type selector for adding new widgets
 */
@Composable
fun WidgetTypePicker(
    onWidgetSelected: (ControlWidgetType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val widgetTypes = listOf(
        WidgetTypeInfo(ControlWidgetType.ACTION_BUTTON, Icons.Default.TouchApp, "Button", "Single action button"),
        WidgetTypeInfo(ControlWidgetType.MACRO_BUTTON, Icons.Default.PlaylistPlay, "Macro", "Execute sequence of actions"),
        WidgetTypeInfo(ControlWidgetType.TRACKPAD, Icons.Default.TouchApp, "Trackpad", "Touch gesture area"),
        WidgetTypeInfo(ControlWidgetType.KEYBOARD, Icons.Default.Keyboard, "Keyboard", "Text input trigger"),
        WidgetTypeInfo(ControlWidgetType.DPAD, Icons.Default.Gamepad, "D-Pad", "Directional control"),
        WidgetTypeInfo(ControlWidgetType.SLIDER, Icons.Default.Tune, "Slider", "Continuous value"),
        WidgetTypeInfo(ControlWidgetType.DIAL, Icons.Default.RadioButtonChecked, "Dial", "Rotary control"),
        WidgetTypeInfo(ControlWidgetType.TOGGLE, Icons.Default.ToggleOn, "Toggle", "On/off switch"),
        WidgetTypeInfo(ControlWidgetType.STATUS_DISPLAY, Icons.Default.Info, "Status", "Read-only display"),
        WidgetTypeInfo(ControlWidgetType.FOLDER, Icons.Default.Folder, "Folder", "Group of widgets"),
        WidgetTypeInfo(ControlWidgetType.SPACER, Icons.Default.SpaceBar, "Spacer", "Empty space")
    )
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Widget", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                widgetTypes.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { info ->
                            Card(
                                onClick = { onWidgetSelected(info.type) },
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF2D2D2D)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        info.icon,
                                        contentDescription = null,
                                        tint = Color(0xFF00FF88),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        info.name,
                                        fontSize = 12.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                        // Fill remaining space if row is incomplete
                        repeat(3 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White
    )
}

private data class WidgetTypeInfo(
    val type: ControlWidgetType,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val name: String,
    val description: String
)

/**
 * Profile management dialog
 */
@Composable
fun ProfileManagerDialog(
    profiles: List<com.example.continuumstudio.xrcontrol.ControlProfile>,
    activeProfileId: String?,
    onProfileSelect: (String) -> Unit,
    onProfileCreate: (String) -> Unit,
    onProfileDelete: (String) -> Unit,
    onProfileRename: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Profiles", fontWeight = FontWeight.Bold)
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Profile", tint = Color(0xFF00FF88))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                profiles.forEach { profile ->
                    Card(
                        onClick = { onProfileSelect(profile.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (profile.id == activeProfileId) 
                                Color(0xFF00FF88).copy(alpha = 0.2f) 
                            else Color(0xFF2D2D2D)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    profile.name,
                                    color = Color.White,
                                    fontWeight = if (profile.id == activeProfileId) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    "${profile.widgets.size} widgets",
                                    fontSize = 12.sp,
                                    color = Color.Gray
                                )
                            }
                            
                            if (profile.id == activeProfileId) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = Color(0xFF00FF88)
                                )
                            } else if (!profile.isDefault) {
                                IconButton(onClick = { onProfileDelete(profile.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFFF4444)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        containerColor = Color(0xFF1A1A1A),
        titleContentColor = Color.White
    )
    
    // Create profile dialog
    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            onProfileCreate(newProfileName)
                            newProfileName = ""
                            showCreateDialog = false
                        }
                    },
                    enabled = newProfileName.isNotBlank()
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = Color(0xFF1A1A1A),
            titleContentColor = Color.White
        )
    }
}
