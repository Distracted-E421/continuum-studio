package com.example.continuumstudio.ui.dialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.QueueItem

/**
 * Queue drawer component with collapsible tab on the left edge.
 * Shows queued dialogs and allows switching between them.
 */
@Composable
fun QueueDrawer(
    queueItems: List<QueueItem>,
    activeDialogId: String?,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onSelectDialog: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val drawerWidth = 280.dp
    
    // Track drag state for swipe gesture
    var dragOffset by remember { mutableFloatStateOf(0f) }
    
    Box(
        modifier = modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragOffset > 50 && !isOpen) {
                            onToggle()
                        } else if (dragOffset < -50 && isOpen) {
                            onToggle()
                        }
                        dragOffset = 0f
                    },
                    onHorizontalDrag = { _, delta ->
                        dragOffset += delta
                    }
                )
            }
    ) {
        // Collapsed tab (always visible when drawer is closed)
        AnimatedVisibility(
            visible = !isOpen,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
        ) {
            QueueCollapsedTab(
                queueCount = queueItems.size,
                onClick = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
            )
        }
        
        // Expanded drawer
        AnimatedVisibility(
            visible = isOpen,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(200)
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(200)
            ),
        ) {
            QueueDrawerContent(
                queueItems = queueItems,
                activeDialogId = activeDialogId,
                drawerWidth = drawerWidth,
                onClose = onToggle,
                onSelectDialog = { index ->
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelectDialog(index)
                },
            )
        }
    }
}

/**
 * Collapsed tab showing queue icon and count badge
 */
@Composable
private fun QueueCollapsedTab(
    queueCount: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(start = 0.dp, top = 120.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        color = if (queueCount > 0) MaterialTheme.colorScheme.primary else Color(0xFF333333),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Default.Inbox,
                contentDescription = "Queue",
                tint = Color.White,
                modifier = Modifier.size(24.dp),
            )
            if (queueCount > 0) {
                Badge(
                    containerColor = Color.White,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        text = queueCount.toString(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * Expanded drawer content showing all queued dialogs
 */
@Composable
private fun QueueDrawerContent(
    queueItems: List<QueueItem>,
    activeDialogId: String?,
    drawerWidth: androidx.compose.ui.unit.Dp,
    onClose: () -> Unit,
    onSelectDialog: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(drawerWidth)
            .fillMaxHeight(),
        color = Color(0xFF1E1E1E),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Default.Inbox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Queue",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    if (queueItems.isNotEmpty()) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(queueItems.size.toString())
                        }
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Close",
                        tint = Color.White,
                    )
                }
            }
            
            HorizontalDivider(color = Color(0xFF333333))
            
            // Queue items list
            if (queueItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(48.dp),
                        )
                        Text(
                            text = "Queue is empty",
                            color = Color(0xFF9E9E9E),
                            fontSize = 16.sp,
                        )
                        Text(
                            text = "No pending dialogs",
                            color = Color(0xFF757575),
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(queueItems) { index, item ->
                        QueueItemCard(
                            item = item,
                            isActive = item.id == activeDialogId,
                            onClick = { onSelectDialog(index) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Individual queue item card
 */
@Composable
private fun QueueItemCard(
    item: QueueItem,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val typeColor = when (item.type.lowercase()) {
        "choice" -> Color(0xFF2196F3)
        "confirm" -> Color(0xFFFF9800)
        "text" -> Color(0xFF4CAF50)
        "slider" -> Color(0xFF9C27B0)
        else -> Color(0xFF757575)
    }
    
    val typeIcon = when (item.type.lowercase()) {
        "choice" -> Icons.AutoMirrored.Filled.List
        "confirm" -> Icons.AutoMirrored.Filled.Help
        "text" -> Icons.Default.Edit
        "slider" -> Icons.Default.Tune
        else -> Icons.Default.QuestionMark
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) 
            else 
                Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Type indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(typeColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    typeIcon,
                    contentDescription = item.type,
                    tint = typeColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            
            // Content
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.title,
                    fontSize = 16.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.type.replaceFirstChar { it.uppercase() },
                    fontSize = 12.sp,
                    color = typeColor,
                )
            }
            
            // Index badge
            Surface(
                shape = CircleShape,
                color = Color(0xFF424242),
            ) {
                Text(
                    text = "#${item.index + 1}",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 12.sp,
                    color = Color(0xFFBDBDBD),
                )
            }
        }
    }
}
