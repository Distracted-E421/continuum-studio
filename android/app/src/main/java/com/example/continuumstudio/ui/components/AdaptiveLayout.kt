package com.example.continuumstudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.util.DisplayInfo
import com.example.continuumstudio.util.DisplayMode
import com.example.continuumstudio.util.ScreenCategory
import com.example.continuumstudio.util.rememberDisplayInfo

/**
 * Adaptive layout that adjusts based on display mode
 * 
 * For DeX/External displays: Uses dual-pane layout with activity feed on glasses
 * For Phone: Standard single-pane layout
 * For Tablet: Responsive multi-pane when space permits
 */
@Composable
fun AdaptiveLayout(
    displayInfo: DisplayInfo = rememberDisplayInfo().value,
    // Primary content (left pane on DeX, full screen on phone)
    primaryContent: @Composable () -> Unit,
    // Secondary content (right pane on DeX, hidden on phone unless explicitly shown)
    secondaryContent: (@Composable () -> Unit)? = null,
    // Panel for glasses (activity feed, TTS controls) - shown on external display
    glassesContent: (@Composable () -> Unit)? = null,
    // Show secondary content even on phone (as bottom sheet or overlay)
    forceShowSecondary: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (displayInfo.mode) {
        DisplayMode.DEX -> {
            // Desktop/glasses mode: Use split layout
            DexLayout(
                primaryContent = primaryContent,
                secondaryContent = secondaryContent ?: glassesContent,
                displayInfo = displayInfo,
                modifier = modifier
            )
        }
        DisplayMode.TABLET -> {
            // Tablet: Adaptive based on width
            TabletLayout(
                primaryContent = primaryContent,
                secondaryContent = secondaryContent,
                displayInfo = displayInfo,
                modifier = modifier
            )
        }
        else -> {
            // Phone: Single pane
            PhoneLayout(
                primaryContent = primaryContent,
                secondaryContent = if (forceShowSecondary) secondaryContent else null,
                modifier = modifier
            )
        }
    }
}

@Composable
private fun DexLayout(
    primaryContent: @Composable () -> Unit,
    secondaryContent: (@Composable () -> Unit)?,
    displayInfo: DisplayInfo,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxSize()
    ) {
        // Main panel (phone as input device when glasses connected)
        Box(
            modifier = Modifier
                .weight(if (secondaryContent != null) 0.4f else 1f)
                .fillMaxHeight()
        ) {
            primaryContent()
        }
        
        // Secondary panel (optimized for glasses viewing)
        if (secondaryContent != null) {
            Surface(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxHeight(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                secondaryContent()
            }
        }
    }
}

@Composable
private fun TabletLayout(
    primaryContent: @Composable () -> Unit,
    secondaryContent: (@Composable () -> Unit)?,
    displayInfo: DisplayInfo,
    modifier: Modifier = Modifier
) {
    when (displayInfo.screenCategory) {
        ScreenCategory.EXPANDED -> {
            Row(
                modifier = modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                ) {
                    primaryContent()
                }
                
                if (secondaryContent != null) {
                    Surface(
                        modifier = Modifier
                            .weight(0.5f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        secondaryContent()
                    }
                }
            }
        }
        else -> {
            // Medium or compact: Stack or single pane
            Box(modifier = modifier.fillMaxSize()) {
                primaryContent()
            }
        }
    }
}

@Composable
private fun PhoneLayout(
    primaryContent: @Composable () -> Unit,
    secondaryContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        primaryContent()
        
        // Could add bottom sheet for secondary content if needed
    }
}

/**
 * Simplified mode indicator for debugging/user awareness
 */
@Composable
fun DisplayModeIndicator(
    displayInfo: DisplayInfo = rememberDisplayInfo().value,
    modifier: Modifier = Modifier
) {
    val modeText = when (displayInfo.mode) {
        DisplayMode.DEX -> "DeX Mode"
        DisplayMode.EXTERNAL_DISPLAY -> "External Display (${displayInfo.externalDisplayCount})"
        DisplayMode.TABLET -> "Tablet"
        DisplayMode.FOLDABLE_EXPANDED -> "Expanded"
        DisplayMode.PHONE -> "Phone"
    }
    
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = modeText,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
