package com.example.continuumstudio.ui.dialog

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
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
import java.util.Locale

@Serializable
data class DialogControlConfig(
    val buttonColumns: Int = 2,
    val showShortcutNumbers: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.STRONG,
    val showKeyboardByDefault: Boolean = false,
    val keyboardPosition: KeyboardPosition = KeyboardPosition.BOTTOM,
    val buttonSize: ButtonSize = ButtonSize.LARGE,
    val animateSelection: Boolean = true,
    val xrMode: Boolean = false,
    val autoShowKeyboardForText: Boolean = true
)

enum class HapticStrength { NONE, LIGHT, MEDIUM, STRONG }
enum class KeyboardPosition { BOTTOM, TOP, OVERLAY }
enum class ButtonSize { SMALL, MEDIUM, LARGE, XR_LARGE }

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
    onScrollGlasses: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hapticFeedback = LocalHapticFeedback.current
    val view = LocalView.current
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    
    val options = dialog?.dialogType?.options ?: emptyList()
    val isTextDialog = dialog?.dialogType?.type == "text"
    
    var showKeyboard by remember(dialog?.id) { 
        mutableStateOf(
            config.showKeyboardByDefault || 
            (config.autoShowKeyboardForText && isTextDialog)
        ) 
    }
    
    var isListening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    
    val effectiveButtonSize = if (config.xrMode) ButtonSize.XR_LARGE else config.buttonSize
    val effectiveColumns = if (config.xrMode && options.size <= 4) 1 else config.buttonColumns
    val effectivePadding = if (config.xrMode) 20.dp else 16.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(effectivePadding),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (dialog == null) {
            NoDialogPlaceholder(
                onRefresh = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onRefresh()
                },
                xrMode = config.xrMode
            )
        } else {
            DialogInfoHeader(
                title = dialog.title,
                timeRemaining = dialog.timeoutMs?.let { it / 1000 },
                optionCount = options.size,
                isTextDialog = isTextDialog,
                xrMode = config.xrMode
            )

            Spacer(Modifier.height(if (config.xrMode) 20.dp else 16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (config.xrMode) 16.dp else 12.dp)
            ) {
                val chunkedOptions = options.chunked(effectiveColumns)
                chunkedOptions.forEachIndexed { rowIndex, rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (config.xrMode) 16.dp else 12.dp)
                    ) {
                        rowOptions.forEachIndexed { colIndex, option ->
                            val optionIndex = rowIndex * effectiveColumns + colIndex
                            val isSelected = selectedOptionIndex == optionIndex

                            DialogOptionButton(
                                index = optionIndex + 1,
                                label = option.label,
                                description = option.description,
                                isSelected = isSelected,
                                buttonSize = effectiveButtonSize,
                                showShortcut = config.showShortcutNumbers,
                                animate = config.animateSelection,
                                xrMode = config.xrMode,
                                onClick = {
                                    performHaptic(hapticFeedback, view, config.hapticStrength)
                                    onOptionSelect(optionIndex)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(effectiveColumns - rowOptions.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(if (config.xrMode) 20.dp else 16.dp))

            AnimatedVisibility(
                visible = showKeyboard,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                XRCommentInputSection(
                    commentText = commentText,
                    focusRequester = focusRequester,
                    isListening = isListening,
                    voiceError = voiceError,
                    xrMode = config.xrMode,
                    isTextDialog = isTextDialog,
                    placeholder = dialog.dialogType.placeholder,
                    onCommentChange = { 
                        onCommentChange(it)
                        onTypingStateChange(it.isNotEmpty())
                    },
                    onVoiceInput = {
                        performHaptic(hapticFeedback, view, HapticStrength.STRONG)
                        voiceError = null
                        startVoiceRecognition(
                            context = context,
                            onResult = { text ->
                                val newText = if (commentText.isEmpty()) text else "$commentText $text"
                                onCommentChange(newText)
                                onTypingStateChange(true)
                                isListening = false
                            },
                            onError = { error ->
                                voiceError = error
                                isListening = false
                            },
                            onListening = { listening ->
                                isListening = listening
                            }
                        )
                    },
                    onSubmit = {
                        performHaptic(hapticFeedback, view, config.hapticStrength)
                        onSubmit()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(if (config.xrMode) 16.dp else 12.dp))

            XRActionBar(
                showKeyboard = showKeyboard,
                isSubmitting = isSubmitting,
                hasSelection = selectedOptionIndex >= 0 || (isTextDialog && commentText.isNotEmpty()),
                xrMode = config.xrMode,
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
                    onScrollGlasses(-200)
                },
                onScrollDown = {
                    performHaptic(hapticFeedback, view, config.hapticStrength)
                    onScrollGlasses(200)
                }
            )
        }
    }

    LaunchedEffect(showKeyboard) {
        if (showKeyboard) {
            focusRequester.requestFocus()
        }
    }
}

@Composable
private fun NoDialogPlaceholder(
    onRefresh: () -> Unit,
    xrMode: Boolean = false,
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
            modifier = Modifier.size(if (xrMode) 80.dp else 64.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(Modifier.height(if (xrMode) 20.dp else 16.dp))
        Text(
            text = "No Active Dialog",
            style = if (xrMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(if (xrMode) 12.dp else 8.dp))
        Text(
            text = "Waiting for dialog from agent...",
            style = if (xrMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(if (xrMode) 32.dp else 24.dp))
        if (xrMode) {
            Button(
                onClick = onRefresh,
                modifier = Modifier.height(56.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Refresh", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun DialogInfoHeader(
    title: String,
    timeRemaining: Int?,
    optionCount: Int,
    isTextDialog: Boolean = false,
    xrMode: Boolean = false,
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
                style = if (xrMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = if (isTextDialog) "Text input" else "$optionCount options",
                    style = if (xrMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                if (isTextDialog) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Type",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }
        }

        timeRemaining?.let { seconds ->
            val isLow = seconds < 30
            Surface(
                shape = RoundedCornerShape(if (xrMode) 12.dp else 8.dp),
                color = if (isLow) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (xrMode) 16.dp else 12.dp, 
                        vertical = if (xrMode) 12.dp else 8.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isLow) Icons.Default.Warning else Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(if (xrMode) 20.dp else 16.dp),
                        tint = if (isLow) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(if (xrMode) 6.dp else 4.dp))
                    Text(
                        text = "${seconds}s",
                        style = if (xrMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
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
    xrMode: Boolean = false,
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
        ButtonSize.XR_LARGE -> 100.dp
    }
    
    val cornerRadius = if (xrMode) 16.dp else 12.dp
    val padding = if (xrMode) 16.dp else 12.dp
    val badgeSize = if (xrMode) 44.dp else 36.dp

    Surface(
        modifier = modifier
            .scale(scale)
            .heightIn(min = minHeight)
            .clip(RoundedCornerShape(cornerRadius))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = if (isSelected) 3.dp else if (xrMode) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showShortcut) {
                Surface(
                    shape = RoundedCornerShape(if (xrMode) 12.dp else 8.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Box(
                        modifier = Modifier.size(badgeSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$index",
                            style = if (xrMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(if (xrMode) 16.dp else 12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = if (xrMode) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                description?.let {
                    Spacer(Modifier.height(if (xrMode) 6.dp else 4.dp))
                    Text(
                        text = it,
                        style = if (xrMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = isSelected,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (xrMode) 32.dp else 24.dp)
                )
            }
        }
    }
}

@Composable
private fun XRCommentInputSection(
    commentText: String,
    focusRequester: FocusRequester,
    isListening: Boolean,
    voiceError: String?,
    xrMode: Boolean,
    isTextDialog: Boolean,
    placeholder: String?,
    onCommentChange: (String) -> Unit,
    onVoiceInput: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cornerRadius = if (xrMode) 16.dp else 12.dp
    val padding = if (xrMode) 16.dp else 12.dp
    val iconSize = if (xrMode) 24.dp else 20.dp
    val fontSize = if (xrMode) 18.sp else 16.sp
    
    val defaultPlaceholder = if (isTextDialog) "Enter your response..." else "Add a comment..."
    val displayPlaceholder = placeholder ?: defaultPlaceholder
    
    val pulseAnimation = rememberInfiniteTransition(label = "voicePulse")
    val pulseAlpha by pulseAnimation.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(cornerRadius),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(
                if (xrMode) 2.dp else 1.dp,
                if (isListening) MaterialTheme.colorScheme.primary 
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isTextDialog) Icons.Default.Edit else Icons.Default.Comment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(iconSize)
                )
                Spacer(Modifier.width(if (xrMode) 16.dp else 12.dp))

                BasicTextField(
                    value = commentText,
                    onValueChange = onCommentChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = if (xrMode) 48.dp else 36.dp)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = fontSize
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
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (commentText.isEmpty()) {
                                Text(
                                    text = displayPlaceholder,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                Spacer(Modifier.width(8.dp))
                
                Surface(
                    shape = CircleShape,
                    color = if (isListening) 
                        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                        else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .size(if (xrMode) 48.dp else 40.dp)
                        .clickable(enabled = !isListening) { onVoiceInput() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = if (isListening) "Listening..." else "Voice input",
                            tint = if (isListening) 
                                MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (xrMode) 24.dp else 20.dp)
                        )
                    }
                }

                if (commentText.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = { onCommentChange("") },
                        modifier = Modifier.size(if (xrMode) 48.dp else 40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(if (xrMode) 24.dp else 20.dp)
                        )
                    }
                }
            }
        }
        
        voiceError?.let { error ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        
        if (isListening) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Listening...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun XRActionBar(
    showKeyboard: Boolean,
    isSubmitting: Boolean,
    hasSelection: Boolean,
    xrMode: Boolean = false,
    onToggleKeyboard: () -> Unit,
    onSubmit: () -> Unit,
    onRefresh: () -> Unit,
    onScrollUp: () -> Unit,
    onScrollDown: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonSize = if (xrMode) 56.dp else 48.dp
    val iconSize = if (xrMode) 28.dp else 24.dp
    val spacing = if (xrMode) 12.dp else 8.dp
    
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedIconButton(
            onClick = onToggleKeyboard,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = if (showKeyboard) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
                contentDescription = if (showKeyboard) "Hide keyboard" else "Show keyboard",
                modifier = Modifier.size(iconSize)
            )
        }

        OutlinedIconButton(
            onClick = onRefresh,
            modifier = Modifier.size(buttonSize)
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(iconSize)
            )
        }

        Surface(
            shape = RoundedCornerShape(if (xrMode) 16.dp else 12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.height(buttonSize)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                IconButton(
                    onClick = onScrollUp,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Scroll up",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                }
                IconButton(
                    onClick = onScrollDown,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll down",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize)
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onSubmit,
            enabled = hasSelection && !isSubmitting,
            modifier = Modifier.height(buttonSize),
            contentPadding = PaddingValues(
                horizontal = if (xrMode) 24.dp else 16.dp,
                vertical = if (xrMode) 16.dp else 12.dp
            )
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(if (xrMode) 24.dp else 20.dp),
                    strokeWidth = if (xrMode) 3.dp else 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(if (xrMode) 24.dp else 20.dp)
                )
            }
            Spacer(Modifier.width(if (xrMode) 12.dp else 8.dp))
            Text(
                text = if (isSubmitting) "Sending..." else "Submit",
                fontWeight = FontWeight.SemiBold,
                style = if (xrMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
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

private fun startVoiceRecognition(
    context: Context,
    onResult: (String) -> Unit,
    onError: (String) -> Unit,
    onListening: (Boolean) -> Unit
) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
        onError("Speech recognition not available")
        return
    }
    
    val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
    
    speechRecognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onListening(true)
        }
        
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        
        override fun onError(error: Int) {
            onListening(false)
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Recognition error ($error)"
            }
            onError(message)
            speechRecognizer.destroy()
        }
        
        override fun onResults(results: Bundle?) {
            onListening(false)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                onResult(matches[0])
            } else {
                onError("No results")
            }
            speechRecognizer.destroy()
        }
        
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    })
    
    speechRecognizer.startListening(intent)
}
