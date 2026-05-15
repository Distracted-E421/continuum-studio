package com.example.continuumstudio.ui.xrcontrol

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.viewmodel.ControlSurfaceViewModel
import com.example.continuumstudio.viewmodel.DPadDirection
import com.example.continuumstudio.xrcontrol.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlSurfaceScreen(
    viewModel: ControlSurfaceViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    
    val snackbarHostState = remember { SnackbarHostState() }
    
    var showAddWidgetDialog by remember { mutableStateOf(false) }
    var showProfileManager by remember { mutableStateOf(false) }
    
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissToast()
        }
    }
    
    Scaffold(
        topBar = {
            ControlSurfaceTopBar(
                profileName = state.activeProfile?.name ?: "No Profile",
                isEditMode = state.isEditMode,
                onEditToggle = { viewModel.setEditMode(!state.isEditMode) },
                onProfileCycle = { viewModel.cycleProfile() },
                onProfileManage = { showProfileManager = true }
            )
        },
        floatingActionButton = {
            if (state.isEditMode) {
                FloatingActionButton(
                    onClick = { showAddWidgetDialog = true },
                    containerColor = Color(0xFF00FF88),
                    contentColor = Color.Black
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Widget")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF121212),
        modifier = modifier
    ) { padding ->
        state.activeProfile?.let { profile ->
            ControlGrid(
                profile = profile,
                widgetStates = state.widgetStates,
                isEditMode = state.isEditMode,
                selectedWidgetId = state.selectedWidgetId,
                draggingWidgetId = state.draggingWidgetId,
                onWidgetTap = viewModel::onWidgetTapped,
                onWidgetLongPress = { viewModel.selectWidget(it) },
                onWidgetMove = viewModel::moveWidget,
                onWidgetResize = viewModel::resizeWidget,
                onWidgetDelete = viewModel::removeWidget,
                onWidgetDuplicate = viewModel::duplicateWidget,
                onDragStart = { viewModel.setDraggingWidget(it) },
                onDragEnd = { viewModel.setDraggingWidget(null) },
                onSliderChange = viewModel::onSliderValueChanged,
                onTrackpadGesture = viewModel::onTrackpadGesture,
                onDPadDirection = viewModel::onDPadDirection,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(8.dp)
            )
        } ?: EmptyProfilePlaceholder(
            onCreateProfile = { viewModel.createProfile("New Profile") },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
    
    // Add Widget Dialog
    if (showAddWidgetDialog) {
        WidgetTypePicker(
            onWidgetSelected = { type ->
                viewModel.addWidget(type)
                showAddWidgetDialog = false
            },
            onDismiss = { showAddWidgetDialog = false }
        )
    }
    
    // Profile Manager Dialog
    if (showProfileManager) {
        ProfileManagerDialog(
            profiles = state.allProfiles,
            activeProfileId = state.activeProfile?.id,
            onProfileSelect = { id ->
                viewModel.switchToProfile(id)
                showProfileManager = false
            },
            onProfileCreate = { name ->
                viewModel.createProfile(name)
            },
            onProfileDelete = { id ->
                viewModel.deleteProfile(id)
            },
            onProfileRename = { id, name ->
                viewModel.renameProfile(id, name)
            },
            onDismiss = { showProfileManager = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ControlSurfaceTopBar(
    profileName: String,
    isEditMode: Boolean,
    onEditToggle: () -> Unit,
    onProfileCycle: () -> Unit,
    onProfileManage: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Dashboard,
                    contentDescription = null,
                    tint = Color(0xFF00FF88)
                )
                Text(
                    profileName,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                if (isEditMode) {
                    Badge { Text("EDIT") }
                }
            }
        },
        actions = {
            IconButton(onClick = onProfileManage) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = "Manage Profiles",
                    tint = Color.White
                )
            }
            IconButton(onClick = onProfileCycle) {
                Icon(
                    Icons.Default.SwapHoriz,
                    contentDescription = "Switch Profile",
                    tint = Color.White
                )
            }
            IconButton(onClick = onEditToggle) {
                Icon(
                    if (isEditMode) Icons.Default.Done else Icons.Default.Edit,
                    contentDescription = if (isEditMode) "Done" else "Edit",
                    tint = if (isEditMode) Color(0xFF00FF88) else Color.White
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF1A1A1A)
        )
    )
}

@Composable
private fun ControlGrid(
    profile: ControlProfile,
    widgetStates: Map<String, WidgetState>,
    isEditMode: Boolean,
    selectedWidgetId: String?,
    draggingWidgetId: String?,
    onWidgetTap: (String) -> Unit,
    onWidgetLongPress: (String) -> Unit,
    onWidgetMove: (String, Int, Int) -> Unit,
    onWidgetResize: (String, Int, Int) -> Unit,
    onWidgetDelete: (String) -> Unit,
    onWidgetDuplicate: (String) -> Unit,
    onDragStart: (String) -> Unit,
    onDragEnd: () -> Unit,
    onSliderChange: (String, Float) -> Unit,
    onTrackpadGesture: (String, GestureType, Float, Float) -> Unit,
    onDPadDirection: (String, DPadDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val cellWidthPx = with(density) { (maxWidth / profile.gridColumns).toPx() }
        val cellHeightPx = with(density) { (maxHeight / profile.gridRows).toPx() }
        val cellPaddingPx = with(density) { 4.dp.toPx() }
        
        val cellWidth = maxWidth / profile.gridColumns
        val cellHeight = maxHeight / profile.gridRows
        val cellPadding = 4.dp
        
        // Grid background
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
        ) {
            // Draw grid lines in edit mode
            if (isEditMode) {
                GridLines(
                    columns = profile.gridColumns,
                    rows = profile.gridRows,
                    cellWidth = cellWidth,
                    cellHeight = cellHeight
                )
            }
            
            // Render each widget
            profile.widgets.forEach { widget ->
                val widgetState = widgetStates[widget.id]
                val isSelected = selectedWidgetId == widget.id
                val isDragging = draggingWidgetId == widget.id
                
                DraggableWidget(
                    widget = widget,
                    cellWidthPx = cellWidthPx,
                    cellHeightPx = cellHeightPx,
                    cellPaddingPx = cellPaddingPx,
                    isEditMode = isEditMode,
                    isSelected = isSelected,
                    isDragging = isDragging,
                    maxGridX = profile.gridColumns,
                    maxGridY = profile.gridRows,
                    onSelect = { onWidgetLongPress(widget.id) },
                    onMove = { newX, newY -> onWidgetMove(widget.id, newX, newY) },
                    onResize = { newSpanX, newSpanY -> onWidgetResize(widget.id, newSpanX, newSpanY) },
                    onDelete = { onWidgetDelete(widget.id) },
                    onDuplicate = { onWidgetDuplicate(widget.id) },
                    onDragStart = { onDragStart(widget.id) },
                    onDragEnd = onDragEnd
                ) {
                    WidgetContent(
                        widget = widget,
                        widgetState = widgetState,
                        isEditMode = isEditMode,
                        onTap = { onWidgetTap(widget.id) },
                        onSliderChange = { onSliderChange(widget.id, it) },
                        onTrackpadGesture = { gesture, dx, dy -> onTrackpadGesture(widget.id, gesture, dx, dy) },
                        onDPadDirection = { onDPadDirection(widget.id, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GridLines(
    columns: Int,
    rows: Int,
    cellWidth: Dp,
    cellHeight: Dp
) {
    // Vertical lines
    for (i in 1 until columns) {
        Box(
            modifier = Modifier
                .offset(x = cellWidth * i - 0.5.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(Color(0xFF333333))
        )
    }
    // Horizontal lines
    for (i in 1 until rows) {
        Box(
            modifier = Modifier
                .offset(y = cellHeight * i - 0.5.dp)
                .height(1.dp)
                .fillMaxWidth()
                .background(Color(0xFF333333))
        )
    }
}

@Composable
private fun BoxScope.WidgetContent(
    widget: ControlWidget,
    widgetState: WidgetState?,
    isEditMode: Boolean,
    onTap: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onTrackpadGesture: (GestureType, Float, Float) -> Unit,
    onDPadDirection: (DPadDirection) -> Unit
) {
    val accentColor = Color(widget.config.accentColor.toInt())
    val haptic = LocalHapticFeedback.current
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!isEditMode) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { 
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTap() 
                            }
                        )
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        when (widget.type) {
            ControlWidgetType.ACTION_BUTTON, ControlWidgetType.MACRO_BUTTON -> {
                ActionButtonContent(widget, accentColor)
            }
            ControlWidgetType.TOGGLE -> {
                ToggleContent(widget, widgetState?.toggleState ?: false, accentColor)
            }
            ControlWidgetType.SLIDER -> {
                SliderContent(widget, widgetState?.currentValue ?: widget.config.sliderMin, onSliderChange)
            }
            ControlWidgetType.TRACKPAD -> {
                TrackpadContent(widget, onTrackpadGesture)
            }
            ControlWidgetType.DPAD -> {
                DPadContent(widget, onDPadDirection)
            }
            ControlWidgetType.STATUS_DISPLAY -> {
                StatusDisplayContent(widget)
            }
            ControlWidgetType.KEYBOARD -> {
                KeyboardTriggerContent(widget)
            }
            ControlWidgetType.DIALOG_OPTIONS -> {
                DialogOptionsContent(widget, onTap)
            }
            ControlWidgetType.DIAL -> {
                DialContent(widget, widgetState?.currentValue ?: 0f, onSliderChange)
            }
            ControlWidgetType.FOLDER -> {
                FolderContent(widget, widgetState?.folderExpanded ?: false)
            }
            ControlWidgetType.SPACER -> {
                // Empty spacer
            }
        }
    }
}

@Composable
private fun ActionButtonContent(widget: ControlWidget, accentColor: Color) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            widget.config.icon?.let { iconName ->
                val icon = getIconForName(iconName)
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            if (widget.config.showLabel && widget.config.label.isNotBlank()) {
                Text(
                    widget.config.label,
                    color = Color.White,
                    fontSize = widget.config.fontSize.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ToggleContent(widget: ControlWidget, isOn: Boolean, accentColor: Color) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isOn) accentColor else Color(0xFF444444))
                    .border(2.dp, accentColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isOn) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = null,
                    tint = if (isOn) Color.Black else Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            if (widget.config.showLabel && widget.config.label.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    widget.config.label,
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SliderContent(
    widget: ControlWidget,
    currentValue: Float,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        if (widget.config.label.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    widget.config.label,
                    color = Color.White,
                    fontSize = 12.sp
                )
                Text(
                    "${currentValue.toInt()}",
                    color = Color(widget.config.accentColor.toInt()),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        Slider(
            value = currentValue,
            onValueChange = onValueChange,
            valueRange = widget.config.sliderMin..widget.config.sliderMax,
            steps = ((widget.config.sliderMax - widget.config.sliderMin) / widget.config.sliderStep).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = Color(widget.config.accentColor.toInt()),
                activeTrackColor = Color(widget.config.accentColor.toInt()),
                inactiveTrackColor = Color(0xFF444444)
            )
        )
    }
}

@Composable
private fun TrackpadContent(
    widget: ControlWidget,
    onGesture: (GestureType, Float, Float) -> Unit
) {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF252525), RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        lastX = offset.x
                        lastY = offset.y
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onGesture(GestureType.SCROLL, dragAmount.x, dragAmount.y)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                tint = Color(0xFF555555),
                modifier = Modifier.size(32.dp)
            )
            if (widget.config.label.isNotBlank()) {
                Text(
                    widget.config.label,
                    color = Color(0xFF555555),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun DPadContent(
    widget: ControlWidget,
    onDirection: (DPadDirection) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = Color(widget.config.accentColor.toInt())
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // D-pad layout
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Up
            DPadButton(Icons.Default.KeyboardArrowUp, accentColor) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDirection(DPadDirection.UP)
            }
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left
                DPadButton(Icons.Default.KeyboardArrowLeft, accentColor) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDirection(DPadDirection.LEFT)
                }
                
                // Center
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDirection(DPadDirection.CENTER)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                }
                
                // Right
                DPadButton(Icons.Default.KeyboardArrowRight, accentColor) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDirection(DPadDirection.RIGHT)
                }
            }
            
            // Down
            DPadButton(Icons.Default.KeyboardArrowDown, accentColor) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDirection(DPadDirection.DOWN)
            }
        }
    }
}

@Composable
private fun DPadButton(icon: ImageVector, accentColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun StatusDisplayContent(widget: ControlWidget) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00FF88))
            )
            Text(
                widget.config.label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun KeyboardTriggerContent(widget: ControlWidget) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Keyboard,
                contentDescription = null,
                tint = Color(widget.config.accentColor.toInt()),
                modifier = Modifier.size(24.dp)
            )
            Text(
                widget.config.label.ifBlank { "Keyboard" },
                color = Color.White,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun DialogOptionsContent(widget: ControlWidget, onTap: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            widget.config.label,
            color = Color(widget.config.accentColor.toInt()),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun DialContent(
    widget: ControlWidget,
    currentValue: Float,
    onValueChange: (Float) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Simplified dial representation
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color(0xFF333333))
                .border(3.dp, Color(widget.config.accentColor.toInt()), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${currentValue.toInt()}",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun FolderContent(widget: ControlWidget, isExpanded: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                contentDescription = null,
                tint = Color(widget.config.accentColor.toInt()),
                modifier = Modifier.size(28.dp)
            )
            if (widget.config.label.isNotBlank()) {
                Text(
                    widget.config.label,
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyProfilePlaceholder(
    onCreateProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Dashboard,
                contentDescription = null,
                tint = Color(0xFF444444),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "No Profile Selected",
                color = Color.White,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onCreateProfile) {
                Text("Create Profile")
            }
        }
    }
}

private fun getIconForName(name: String): ImageVector {
    return when (name.lowercase()) {
        "play" -> Icons.Default.PlayArrow
        "pause" -> Icons.Default.Pause
        "play_pause" -> Icons.Default.PlayArrow
        "stop" -> Icons.Default.Stop
        "skip_previous" -> Icons.Default.SkipPrevious
        "skip_next" -> Icons.Default.SkipNext
        "volume_up" -> Icons.Default.VolumeUp
        "volume_down" -> Icons.Default.VolumeDown
        "volume_off" -> Icons.Default.VolumeOff
        "home" -> Icons.Default.Home
        "back" -> Icons.AutoMirrored.Filled.ArrowBack
        "settings" -> Icons.Default.Settings
        "refresh" -> Icons.Default.Refresh
        "chat" -> Icons.Default.Chat
        "send" -> Icons.Default.Send
        "view_module" -> Icons.Default.ViewModule
        "dashboard" -> Icons.Default.Dashboard
        "keyboard" -> Icons.Default.Keyboard
        "touch_app" -> Icons.Default.TouchApp
        "folder" -> Icons.Default.Folder
        "folder_open" -> Icons.Default.FolderOpen
        else -> Icons.Default.Circle
    }
}
