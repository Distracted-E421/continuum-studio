package com.example.continuumstudio.ui.dialog

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.DialogDetails
import com.example.continuumstudio.data.ChoiceOption
import kotlinx.serialization.Serializable

@Serializable
data class DialogControlConfig(
    val buttonColumns: Int = 2,
    val showShortcutNumbers: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.STRONG,
    val showKeyboardByDefault: Boolean = false,
    val keyboardPosition: KeyboardPosition = KeyboardPosition.BOTTOM,
    val buttonSize: ButtonSize = ButtonSize.LARGE,
    val animateSelection: Boolean = true
)

enum class HapticStrength { NONE, LIGHT, MEDIUM, STRONG }
enum class KeyboardPosition { BOTTOM, TOP, OVERLAY }
enum class ButtonSize { SMALL, MEDIUM, LARGE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogControlPanel(
    dialog: DialogDetails?,
    selectedOptionIndex: Int,
    commentText: String,
    isSubmitting: Boolean,
    config: DialogControlConfig = DialogControlConfig(),
    onOptionSelect: (Int) -> Unit,
    onCommentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onTypingStateChange: (Boolean) -> Unit,
    onScrollGlasses: (Int) -> Unit = {}, // Scroll amount in pixels (positive = down)
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    var showKeyboard by remember { mutableStateOf(config.showKeyboardByDefault) }
    val focusRequester = remember { FocusRequester() }
    
    val options = dialog?.dialogType?.options ?: emptyList()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (dialog == null) {
            // No active dialog
            NoDialogPlaceholder(
                onRefresh = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onRefresh()
                }
            )
        } else {
            // Dialog info header
            DialogInfoHeader(
                title = dialog.title,
                timeRemaining = dialog.timeoutMs?.let { it / 1000 },
                optionCount = options.size
            )

            Spacer(Modifier.height(16.dp))

            // Options grid
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val chunkedOptions = options.chunked(config.buttonColumns)
                chunkedOptions.forEachIndexed { rowIndex, rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowOptions.forEachIndexed { colIndex, option ->
                            val optionIndex = rowIndex * config.buttonColumns + colIndex
                            val isSelected = selectedOptionIndex == optionIndex

                            DialogOptionButton(
                                index = optionIndex + 1,
                                label = option.label,
                                description = option.description,
                                isSelected = isSelected,
                                buttonSize = config.buttonSize,
                                showShortcut = config.showShortcutNumbers,
                                animate = config.animateSelection,
                                onClick = {
                                    performHaptic(hapticFeedback, view, config.hapticStrength)
                                    onOptionSelect(optionIndex)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // Fill empty slots
                        repeat(config.buttonColumns - rowOptions.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Comment input section
            AnimatedVisibility(
                visible = showKeyboard,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                CommentInputSection(
                    commentText = commentText,
                    focusRequester = focusRequester,
                    onCommentChange = { 
                        onCommentChange(it)
                        onTypingStateChange(it.isNotEmpty())
                    },
                    onSubmit = {
                        performHaptic(hapticFeedback, view, config.hapticStrength)
                        onSubmit()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            // Bottom action bar
            ActionBar(
                showKeyboard = showKeyboard,
                isSubmitting = isSubmitting,
                hasSelection = selectedOptionIndex >= 0,
                onToggleKeyboard = {
                    showKeyboard = !showKeyboard
                    if (showKeyboard) {
                        performHaptic(hapticFeedback, view, config.hapticStrength)
                    }
                },
                onSubmit = {
                    performHaptic(hapticFeedback, view, HapticStrength.STRONG)
                    onSubmit()
                },
                onRefresh = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onRefresh()
                },
                onScrollUp = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onScrollGlasses(-200) // Scroll up by 200px
                },
                onScrollDown = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onScrollGlasses(200) // Scroll down by 200px
                }
            )
        }
    }

    // Request focus when keyboard is shown
    LaunchedEffect(showKeyboard) {
        if (showKeyboard) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun NoDialogPlaceholder(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Inbox,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No Active Dialog",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Waiting for dialog from agent...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh")
        }
    }
}

@Composable
private fun DialogInfoHeader(
    title: String,
    timeRemaining: Int?,
    optionCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$optionCount options",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        timeRemaining?.let { seconds ->
            val isLow = seconds < 30
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (isLow) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isLow) Icons.Default.Warning else Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (isLow) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${seconds}s",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isLow) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogOptionButton(
    index: Int,
    label: String,
    description: String?,
    isSelected: Boolean,
    buttonSize: ButtonSize,
    showShortcut: Boolean,
    animate: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            isSelected && animate -> 1.02f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            isPressed -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
        animationSpec = tween(150),
        label = "buttonColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        },
        animationSpec = tween(150),
        label = "borderColor"
    )

    val minHeight = when (buttonSize) {
        ButtonSize.SMALL -> 56.dp
        ButtonSize.MEDIUM -> 72.dp
        ButtonSize.LARGE -> 88.dp
    }

    Surface(
        modifier = modifier
            .scale(scale)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shortcut number badge
            if (showShortcut) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$index",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
            }

            // Label and description
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                description?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Selection indicator
            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun CommentInputSection(
    commentText: String,
    focusRequester: FocusRequester,
    onCommentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))

            BasicTextField(
                value = commentText,
                onValueChange = onCommentChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = { onSubmit() }
                ),
                decorationBox = { innerTextField ->
                    Box {
                        if (commentText.isEmpty()) {
                            Text(
                                text = "Add a comment...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                        }
                        innerTextField()
                    }
                }
            )

            if (commentText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { onCommentChange("") },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionBar(
    showKeyboard: Boolean,
    isSubmitting: Boolean,
    hasSelection: Boolean,
    onToggleKeyboard: () -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Keyboard toggle
        OutlinedIconButton(
            onClick = onToggleKeyboard,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (showKeyboard) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                contentDescription = if (showKeyboard) "Hide keyboard" else "Show keyboard"
            )
        }

        // Refresh button
        OutlinedIconButton(
            onClick = onRefresh,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh"
            )
        }

        // Scroll controls (for glasses display)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.height(48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                IconButton(
                    onClick = onScrollUp,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Scroll up",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onScrollDown,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll down",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Submit button
        Button(
            onClick = onSubmit,
            enabled = hasSelection && !isSubmitting,
            modifier = Modifier.height(48.dp)
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isSubmitting) "Sending..." else "Submit",
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun performHaptic(
    hapticFeedback: androidx.compose.ui.hapticfeedback.HapticFeedback,
    view: android.view.View,
    strength: HapticStrength
) {
    when (strength) {
        HapticStrength.NONE -> { }
        HapticStrength.LIGHT -> hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        HapticStrength.MEDIUM -> hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        HapticStrength.STRONG -> {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}
