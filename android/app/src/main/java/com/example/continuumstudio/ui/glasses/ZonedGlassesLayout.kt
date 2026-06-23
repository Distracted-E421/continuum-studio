package com.example.continuumstudio.ui.glasses

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.GlassesColors
import kotlin.math.roundToInt
import com.example.continuumstudio.data.GlassesTheme
import kotlinx.serialization.Serializable

@Serializable
data class ZoneConfig(
    val leftWidthPercent: Float = 0.35f,
    val centerWidthPercent: Float = 0.30f,
    val rightWidthPercent: Float = 0.35f,
    val topPadding: Float = 16f,
    val bottomPadding: Float = 16f,
    val sidePadding: Float = 16f,
    val zoneSpacing: Float = 8f,
    val showZoneBorders: Boolean = false,
    val centerOpacity: Float = 0.0f
)

enum class GlassesZone {
    LEFT, CENTER, RIGHT, TOP_BAR, BOTTOM_BAR
}

@Composable
fun ZonedGlassesLayout(
    dialogState: GlassesDialogState?,
    selectedOptionIndex: Int?,
    typingText: String?,
    connectionStatus: GlassesConnectionStatus,
    theme: GlassesTheme,
    zoneConfig: ZoneConfig = ZoneConfig(),
    primaryColor: Color,
    dimColor: Color,
    contentScrollOffset: Float = 0f,
    onOptionHighlight: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Track dialog changes for entrance animation
    var previousDialogId by remember { mutableStateOf<String?>(null) }
    var animationKey by remember { mutableStateOf(0) }
    
    // Trigger animation when dialog changes
    LaunchedEffect(dialogState?.id) {
        if (dialogState?.id != previousDialogId && dialogState != null) {
            animationKey++
            previousDialogId = dialogState.id
        }
    }
    
    // Entrance animations - skip if theme.reduceAnimations is true
    val useAnimations = !theme.reduceAnimations
    
    // Left zone slide-in from left
    val leftZoneOffsetX by animateIntAsState(
        targetValue = if (dialogState != null) 0 else -200,
        animationSpec = if (useAnimations) tween(400, easing = FastOutSlowInEasing) else snap(),
        label = "leftZoneOffset"
    )
    
    // Right zone slide-in from right
    val rightZoneOffsetX by animateIntAsState(
        targetValue = if (dialogState != null) 0 else 200,
        animationSpec = if (useAnimations) tween(400, delayMillis = 100, easing = FastOutSlowInEasing) else snap(),
        label = "rightZoneOffset"
    )
    
    // Overall fade in
    val contentAlpha by animateFloatAsState(
        targetValue = if (dialogState != null) 1f else 0f,
        animationSpec = if (useAnimations) tween(300) else snap(),
        label = "contentAlpha"
    )
    
    // Top bar slide down
    val topBarOffsetY by animateIntAsState(
        targetValue = if (dialogState != null) 0 else -50,
        animationSpec = if (useAnimations) tween(350) else snap(),
        label = "topBarOffset"
    )
    
    // Bottom bar slide up
    val bottomBarOffsetY by animateIntAsState(
        targetValue = if (dialogState != null || typingText != null) 0 else 50,
        animationSpec = if (useAnimations) tween(350) else snap(),
        label = "bottomBarOffset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassesColors.Transparent)
            .padding(
                top = zoneConfig.topPadding.dp,
                bottom = zoneConfig.bottomPadding.dp,
                start = zoneConfig.sidePadding.dp,
                end = zoneConfig.sidePadding.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(zoneConfig.zoneSpacing.dp)
        ) {
            // LEFT ZONE - Dialog content/prompt (slides in from left)
            Box(
                modifier = Modifier
                    .weight(zoneConfig.leftWidthPercent)
                    .fillMaxHeight()
                    .offset { IntOffset(leftZoneOffsetX, 0) }
                    .alpha(contentAlpha)
                    .then(
                        if (zoneConfig.showZoneBorders)
                            Modifier.border(1.dp, dimColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        else Modifier
                    )
            ) {
                if (dialogState != null) {
                    LeftZoneContent(
                        dialog = dialogState,
                        typingText = typingText,
                        primaryColor = primaryColor,
                        dimColor = dimColor,
                        theme = theme,
                        scrollOffset = contentScrollOffset
                    )
                } else {
                    // Idle state - show subtle hint
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Waiting for dialog...",
                            color = dimColor.copy(alpha = 0.3f),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // CENTER ZONE - Clear see-through area
            Box(
                modifier = Modifier
                    .weight(zoneConfig.centerWidthPercent)
                    .fillMaxHeight()
                    .alpha(zoneConfig.centerOpacity)
                    .then(
                        if (zoneConfig.showZoneBorders)
                            Modifier.border(1.dp, dimColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        else Modifier
                    )
            ) {
                // Intentionally empty - see-through zone
                // Only show minimal status if needed
                val disconnectedAlpha by animateFloatAsState(
                    targetValue = if (!connectionStatus.isConnected) 0.5f else 0f,
                    animationSpec = tween(300),
                    label = "disconnectedAlpha"
                )
                if (disconnectedAlpha > 0f) {
                    Text(
                        text = "⊘",
                        color = GlassesColors.AlertRed.copy(alpha = disconnectedAlpha),
                        fontSize = 24.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            // RIGHT ZONE - Dialog options (slides in from right)
            Box(
                modifier = Modifier
                    .weight(zoneConfig.rightWidthPercent)
                    .fillMaxHeight()
                    .offset { IntOffset(rightZoneOffsetX, 0) }
                    .alpha(contentAlpha)
                    .then(
                        if (zoneConfig.showZoneBorders)
                            Modifier.border(1.dp, dimColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        else Modifier
                    )
            ) {
                if (dialogState != null) {
                    RightZoneContent(
                        dialog = dialogState,
                        selectedIndex = selectedOptionIndex,
                        primaryColor = primaryColor,
                        dimColor = dimColor,
                        theme = theme,
                        onOptionHighlight = onOptionHighlight
                    )
                } else {
                    // Idle state
                    Column(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "No options",
                            color = dimColor.copy(alpha = 0.2f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.End
                        )
                    }
                }
            }
        }

        // Top bar - connection status and timer (slides down)
        TopStatusBar(
            connectionStatus = connectionStatus,
            timeRemaining = dialogState?.timeRemaining,
            isUrgent = dialogState?.isUrgent == true,
            primaryColor = primaryColor,
            dimColor = dimColor,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, topBarOffsetY) }
                .alpha(if (dialogState != null) 1f else 0.5f)
        )

        // Bottom bar - typing indicator and current action (slides up)
        val showBottomBar = typingText != null || dialogState != null
        val bottomBarAlpha by animateFloatAsState(
            targetValue = if (showBottomBar) 1f else 0f,
            animationSpec = if (useAnimations) tween(300) else snap(),
            label = "bottomBarAlpha"
        )
        if (bottomBarAlpha > 0f) {
            BottomStatusBar(
                typingText = typingText,
                dialogTitle = dialogState?.title,
                primaryColor = primaryColor,
                dimColor = dimColor,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset { IntOffset(0, bottomBarOffsetY) }
                    .alpha(bottomBarAlpha)
            )
        }
    }
}

@Composable
private fun LeftZoneContent(
    dialog: GlassesDialogState,
    typingText: String?,
    primaryColor: Color,
    dimColor: Color,
    theme: GlassesTheme,
    scrollOffset: Float = 0f,
    modifier: Modifier = Modifier
) {
    val textScale = theme.fontScale
    
    // Create scroll state that we can control programmatically
    val scrollState = rememberScrollState()
    
    // Sync the scroll position with the external scrollOffset
    LaunchedEffect(scrollOffset) {
        scrollState.scrollTo(scrollOffset.roundToInt().coerceAtLeast(0))
    }

    // Outer box with clipToBounds to hide overflow
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(12.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // Title (keep maxLines for title as it should be compact)
            Text(
                text = dialog.title,
                color = primaryColor,
                fontSize = (22 * textScale).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))

            // Prompt content - NO maxLines to allow full scrolling
            Text(
                text = stripMarkdown(dialog.prompt),
                color = dimColor,
                fontSize = (14 * textScale).sp,
                lineHeight = (20 * textScale).sp
                // No maxLines - content can be any length
            )

            // Typing indicator
            if (typingText != null) {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, primaryColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BlinkingCursor(primaryColor)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = typingText.ifEmpty { "..." },
                            color = primaryColor,
                            fontSize = (14 * textScale).sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            
            // Add some bottom padding so last content isn't cut off
            Spacer(Modifier.height(24.dp))
        }
        
        // Scroll indicators
        if (scrollState.value > 0) {
            // Show "more above" indicator
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                GlassesColors.Transparent.copy(alpha = 0.3f),
                                GlassesColors.Transparent
                            )
                        )
                    )
            ) {
                Text(
                    text = "▲",
                    color = dimColor.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
        
        if (scrollState.value < scrollState.maxValue) {
            // Show "more below" indicator
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(
                                GlassesColors.Transparent,
                                GlassesColors.Transparent.copy(alpha = 0.3f)
                            )
                        )
                    )
            ) {
                Text(
                    text = "▼",
                    color = dimColor.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

@Composable
private fun RightZoneContent(
    dialog: GlassesDialogState,
    selectedIndex: Int?,
    primaryColor: Color,
    dimColor: Color,
    theme: GlassesTheme,
    onOptionHighlight: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val textScale = theme.fontScale

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "OPTIONS",
            color = dimColor,
            fontSize = (10 * textScale).sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp
        )

        Spacer(Modifier.height(12.dp))

        dialog.options.forEachIndexed { index, option ->
            val isSelected = selectedIndex == index
            val animatedAlpha by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.8f,
                animationSpec = tween(150),
                label = "optionAlpha"
            )
            val animatedBorderWidth by animateDpAsState(
                targetValue = if (isSelected) 2.dp else 1.dp,
                animationSpec = tween(150),
                label = "optionBorder"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .border(
                        width = animatedBorderWidth,
                        color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .background(
                        if (isSelected) primaryColor.copy(alpha = 0.1f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .alpha(animatedAlpha),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Shortcut number
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            if (isSelected) primaryColor else primaryColor.copy(alpha = 0.2f),
                            RoundedCornerShape(6.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${index + 1}",
                        color = if (isSelected) Color.Black else primaryColor,
                        fontSize = (14 * textScale).sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.width(10.dp))

                // Option label
                Text(
                    text = option.label,
                    color = primaryColor,
                    fontSize = (14 * textScale).sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Timer warning
        dialog.timeRemaining?.let { seconds ->
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (seconds < 30) "⚠" else "⏱",
                    color = if (seconds < 30) GlassesColors.AlertYellow else dimColor,
                    fontSize = (12 * textScale).sp
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${seconds}s",
                    color = if (seconds < 30) GlassesColors.AlertYellow else dimColor,
                    fontSize = (12 * textScale).sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TopStatusBar(
    connectionStatus: GlassesConnectionStatus,
    timeRemaining: Int?,
    isUrgent: Boolean,
    primaryColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(top = 4.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Connection indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (connectionStatus.isConnected) GlassesColors.GreenBright else GlassesColors.AlertRed,
                        RoundedCornerShape(50)
                    )
            )
            connectionStatus.latencyMs?.let {
                Text(
                    text = "${it}ms",
                    color = dimColor,
                    fontSize = 10.sp
                )
            }
        }

        // Urgent indicator
        if (isUrgent) {
            Text(
                text = "⚠ URGENT",
                color = GlassesColors.AlertYellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Agent count
        if (connectionStatus.agentCount > 0) {
            Text(
                text = "${connectionStatus.agentCount}🤖",
                color = dimColor,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun BottomStatusBar(
    typingText: String?,
    dialogTitle: String?,
    primaryColor: Color,
    dimColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Current dialog indicator
        dialogTitle?.let {
            Text(
                text = "📋 $it",
                color = dimColor,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }

        // Typing status
        if (typingText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BlinkingCursor(primaryColor, size = 8.dp)
                Text(
                    text = "typing...",
                    color = primaryColor.copy(alpha = 0.7f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun BlinkingCursor(
    color: Color,
    size: Dp = 12.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Box(
        modifier = Modifier
            .width(2.dp)
            .height(size)
            .background(color.copy(alpha = alpha))
    )
}

private fun stripMarkdown(text: String): String {
    return text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("\\*(.+?)\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("_(.+?)_"), "$1")
        .replace(Regex("`(.+?)`"), "$1")
        .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
        .replace(Regex("^[-*]\\s+", RegexOption.MULTILINE), "• ")
        .trim()
}
