package com.example.continuumstudio.ui.xr

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.data.*
import com.example.continuumstudio.viewmodel.XRViewModel
import com.example.continuumstudio.viewmodel.YouTubeViewModel
import com.example.continuumstudio.ui.youtube.YouTubeControlPanel
import com.example.continuumstudio.youtube.YouTubeState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XRConfigScreen(
    youtubeViewModel: YouTubeViewModel,
    xrViewModel: XRViewModel,
    onNavigateBack: () -> Unit = {},
    onNavigateToNavigation: () -> Unit = {}
) {
    val xrConfig by xrViewModel.xrConfig.collectAsState()
    val activePreset by xrViewModel.activePreset.collectAsState()
    val glassesTheme by xrViewModel.glassesTheme.collectAsState()
    val isGlassesConnected by xrViewModel.isGlassesConnected.collectAsState()
    val glassesDisplayState by xrViewModel.glassesDisplayState.collectAsState()
    val youtubeState by youtubeViewModel.youtubeState.collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Widgets", "Theme", "Presets", "Context", "Media", "Nav")
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("XR Configuration") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Connection status indicator
                    Surface(
                        color = if (isGlassesConnected) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isGlassesConnected) Color(0xFF4CAF50) else Color.Gray
                                    )
                            )
                            Text(
                                text = if (isGlassesConnected) 
                                    glassesDisplayState.connectedDisplayName ?: "Glasses" 
                                else 
                                    "No Glasses",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Tab row
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Tab content
            when (selectedTab) {
                0 -> WidgetConfigTab(xrConfig, xrViewModel)
                1 -> ThemeConfigTab(glassesTheme, xrViewModel)
                2 -> PresetsTab(activePreset, xrViewModel)
                3 -> ContextRulesTab(xrConfig.contextRules)
                4 -> MediaTab(youtubeState, youtubeViewModel)
                5 -> NavigationTab(onNavigateToNavigation)
            }
        }
    }
}

@Composable
private fun WidgetConfigTab(
    xrConfig: XRBayConfig,
    xrViewModel: XRViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Phone Widgets",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Widgets displayed on phone screen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        items(xrConfig.phoneWidgets) { widget ->
            WidgetConfigCard(widget = widget, location = "Phone")
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Glasses Widgets",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Widgets displayed on AR glasses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        items(xrConfig.glassesWidgets) { widget ->
            WidgetConfigCard(widget = widget, location = "Glasses")
        }
        
        if (xrConfig.phoneWidgets.isEmpty() && xrConfig.glassesWidgets.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Widgets,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No widgets configured",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Apply a preset to get started",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetConfigCard(
    widget: XRWidgetConfig,
    location: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = widget.type.toIcon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        widget.type.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "$location • ${widget.displayTarget.name.lowercase().replace("_", " ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Text(
                "Priority: ${widget.priority}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeConfigTab(
    glassesTheme: GlassesTheme,
    xrViewModel: XRViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Glasses Display Theme",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Optimize for AR visibility and power efficiency",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Color palette selection
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Color Palette", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    GlassesColorPalette.entries.forEach { palette ->
                        val isSelected = glassesTheme.palette == palette
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { xrViewModel.setGlassesColorPalette(palette) }
                                .padding(vertical = 4.dp),
                            color = if (isSelected) 
                                MaterialTheme.colorScheme.primaryContainer 
                            else 
                                Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Color preview
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black)
                                        .border(2.dp, palette.toPreviewColor(), CircleShape)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        palette.toDisplayName(),
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        palette.toDescription(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Additional theme options
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Display Options", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Use outlines instead of fills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Use Outlines")
                            Text(
                                "Better transparency, less power",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = glassesTheme.useOutlines,
                            onCheckedChange = { xrViewModel.setGlassesOutlinesOnly(it) }
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Reduce animations
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Reduce Animations")
                            Text(
                                "Minimize motion for comfort",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = glassesTheme.reduceAnimations,
                            onCheckedChange = { xrViewModel.setGlassesReduceAnimations(it) }
                        )
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Font scale
                    Text("Font Scale: ${String.format("%.1f", glassesTheme.fontScale)}x")
                    Slider(
                        value = glassesTheme.fontScale,
                        onValueChange = { xrViewModel.setGlassesTextScale(it) },
                        valueRange = 1.0f..2.0f,
                        steps = 4
                    )
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    
                    // Opacity
                    Text("Opacity: ${(glassesTheme.opacity * 100).toInt()}%")
                    Slider(
                        value = glassesTheme.opacity,
                        onValueChange = { xrViewModel.setGlassesOpacity(it) },
                        valueRange = 0.5f..1.0f
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetsTab(
    activePreset: String?,
    xrViewModel: XRViewModel
) {
    val presets = listOf(
        "driving" to XRLayoutPresets.DrivingMode,
        "desk" to XRLayoutPresets.DeskMode
    )
    
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Quick Presets",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Apply pre-configured layouts",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        items(presets) { (name, config) ->
            PresetCard(
                name = name,
                config = config,
                isActive = activePreset == name,
                onApply = { xrViewModel.applyPreset(name) }
            )
        }
    }
}

@Composable
private fun PresetCard(
    name: String,
    config: XRBayConfig,
    isActive: Boolean,
    onApply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${config.phoneWidgets.size} phone + ${config.glassesWidgets.size} glasses widgets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (isActive) {
                Icon(Icons.Default.Check, "Active", tint = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onApply) {
                    Text("Apply")
                }
            }
        }
    }
}

@Composable
private fun ContextRulesTab(rules: List<ContextRule>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                "Context-Aware Rules",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Automatically adjust based on situation",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        if (rules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No context rules configured",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "Rules adjust layout based on driving, walking, etc.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        items(rules) { rule ->
            ContextRuleCard(rule = rule)
        }
        
        item {
            OutlinedButton(
                onClick = { /* TODO: Add rule dialog */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add Rule")
            }
        }
    }
}

@Composable
private fun ContextRuleCard(rule: ContextRule) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.name,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    "When: ${rule.trigger.name.lowercase().replace("_", " ")} → ${rule.action.name.lowercase().replace("_", " ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = { /* TODO */ }
            )
        }
    }
}

// Extension functions
private fun WidgetType.toIcon(): ImageVector = when (this) {
    WidgetType.SERVER_STATUS -> Icons.Outlined.Cloud
    WidgetType.RUNNING_AGENTS -> Icons.Outlined.SmartToy
    WidgetType.DIALOG_BADGE -> Icons.Outlined.ChatBubble
    WidgetType.ACTIVITY_PREVIEW -> Icons.Outlined.Timeline
    WidgetType.MODE_SELECTOR -> Icons.Outlined.Tune
    WidgetType.NETWORK_STATS -> Icons.Outlined.NetworkCheck
    WidgetType.NETWORK_HISTORY -> Icons.Outlined.ShowChart
    WidgetType.QUEUE_STATUS -> Icons.Outlined.Queue
    WidgetType.QUICK_ACTIONS -> Icons.Outlined.TouchApp
}

private fun GlassesColorPalette.toDisplayName(): String = when (this) {
    GlassesColorPalette.MONOCHROME_GREEN -> "Monochrome Green"
    GlassesColorPalette.MONOCHROME_CYAN -> "Monochrome Cyan"
    GlassesColorPalette.MONOCHROME_AMBER -> "Monochrome Amber"
    GlassesColorPalette.HIGH_CONTRAST -> "High Contrast"
    GlassesColorPalette.MUTED -> "Muted"
}

private fun GlassesColorPalette.toDescription(): String = when (this) {
    GlassesColorPalette.MONOCHROME_GREEN -> "Best power efficiency, classic look"
    GlassesColorPalette.MONOCHROME_CYAN -> "High visibility, cool tone"
    GlassesColorPalette.MONOCHROME_AMBER -> "Warm, easy on eyes"
    GlassesColorPalette.HIGH_CONTRAST -> "Maximum readability, white on black"
    GlassesColorPalette.MUTED -> "Subtle, low-distraction display"
}

private fun GlassesColorPalette.toPreviewColor(): Color = when (this) {
    GlassesColorPalette.MONOCHROME_GREEN -> Color(0xFF00FF00)
    GlassesColorPalette.MONOCHROME_CYAN -> Color(0xFF00FFFF)
    GlassesColorPalette.MONOCHROME_AMBER -> Color(0xFFFFBF00)
    GlassesColorPalette.HIGH_CONTRAST -> Color.White
    GlassesColorPalette.MUTED -> Color(0xFF888888)
}

// Media tab for YouTube and other media controls
@Composable
private fun MediaTab(
    youtubeState: YouTubeState,
    youtubeViewModel: YouTubeViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Media on Glasses",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Send media content to your AR glasses",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // YouTube control panel
        item {
            YouTubeControlPanel(
                youtubeState = youtubeState,
                onPlay = { url -> youtubeViewModel.playVideo(url) },
                onStop = { youtubeViewModel.stopVideo() }
            )
        }
        
        // Future media options placeholder
        item {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Coming Soon",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Local video playback\n• Screen mirroring controls\n• Picture-in-Picture mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun NavigationTab(onNavigateToNavigation: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                "Turn-by-Turn Navigation",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Open-source maps powered by OpenStreetMap + OSRM",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToNavigation() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Open Navigation",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Enter destination, get directions on your glasses",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Go",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Features",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    
                    FeatureRow("Real-time turn-by-turn directions", true)
                    FeatureRow("Wireframe map overlay on glasses", true)
                    FeatureRow("Dark tiles optimized for XR", true)
                    FeatureRow("Voice guidance", false)
                    FeatureRow("Offline maps", false)
                }
            }
        }
        
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Open Source Stack",
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Maps: OSMDroid + OpenStreetMap\n" +
                        "Routing: OSRM (Open Source Routing Machine)\n" +
                        "No API keys required. Free forever.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(text: String, implemented: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (implemented) Icons.Default.Check else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = if (implemented) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (implemented) 
                MaterialTheme.colorScheme.onSurface 
            else 
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
