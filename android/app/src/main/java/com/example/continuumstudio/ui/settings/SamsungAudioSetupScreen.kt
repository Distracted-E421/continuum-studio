package com.example.continuumstudio.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.audio.AudioFocusHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungAudioSetupScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val isSamsung = AudioFocusHelper.isSamsungDevice()
    val hasSoundAssistant = AudioFocusHelper.isSoundAssistantInstalled(context)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Samsung Audio Setup") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Device Detection Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSamsung) 
                        MaterialTheme.colorScheme.primaryContainer 
                    else 
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (isSamsung) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isSamsung) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                    Column {
                        Text(
                            if (isSamsung) "Samsung Galaxy Detected" else "Non-Samsung Device",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (isSamsung) 
                                "You can use Samsung's audio features" 
                            else 
                                "Some features may not be available",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            if (isSamsung) {
                // Sound Assistant Card
                SetupStepCard(
                    stepNumber = 1,
                    title = "Sound Assistant",
                    description = "Enable Multisound to allow Continuum to speak while music plays",
                    isAvailable = hasSoundAssistant,
                    availableText = "Installed",
                    unavailableText = "Not installed - get from Galaxy Store",
                    icon = Icons.Default.GraphicEq,
                    onAction = if (hasSoundAssistant) {
                        { openSoundAssistant(context) }
                    } else {
                        { openGalaxyStore(context, "com.samsung.android.soundassistant") }
                    },
                    actionText = if (hasSoundAssistant) "Open Sound Assistant" else "Get from Galaxy Store"
                )
                
                // Instructions for Sound Assistant
                if (hasSoundAssistant) {
                    InstructionCard(
                        title = "Enable Multisound",
                        steps = listOf(
                            "Open Sound Assistant",
                            "Find 'Multisound' or 'Multi sound' toggle",
                            "Enable it to allow multiple apps to play audio",
                            "This lets Continuum TTS overlay your music"
                        )
                    )
                }
                
                // Separate App Sound
                SetupStepCard(
                    stepNumber = 2,
                    title = "Separate App Sound",
                    description = "Route specific apps to different audio outputs (glasses, phone, Bluetooth)",
                    isAvailable = true,
                    availableText = "System feature",
                    unavailableText = "",
                    icon = Icons.Default.SettingsInputComponent,
                    onAction = { openSoundSettings(context) },
                    actionText = "Open Sound Settings"
                )
                
                InstructionCard(
                    title = "Configure Separate App Sound",
                    steps = listOf(
                        "Go to Settings → Sounds and vibration",
                        "Tap 'Separate app sound'",
                        "Turn it ON",
                        "Add the apps you want to route:",
                        "  • Continuum → Phone speaker (for TTS)",
                        "  • Spotify → Bluetooth/Glasses",
                        "  • YouTube → Bluetooth/Glasses"
                    )
                )
                
                // Notification Access
                SetupStepCard(
                    stepNumber = 3,
                    title = "Notification Access",
                    description = "Required to see Now Playing info from Spotify/YouTube",
                    isAvailable = true,
                    availableText = "Required permission",
                    unavailableText = "",
                    icon = Icons.Default.Notifications,
                    onAction = { openNotificationAccessSettings(context) },
                    actionText = "Grant Notification Access"
                )
            }
            
            // Tips Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lightbulb, contentDescription = null)
                        Text("Tips", fontWeight = FontWeight.Bold)
                    }
                    Text("• TTS will automatically duck (lower volume) other audio")
                    Text("• Use Separate App Sound for precise routing to glasses")
                    Text("• RayNeo 3 appears as a Bluetooth audio device")
                    Text("• Android Auto integration routes audio automatically")
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    isAvailable: Boolean,
    availableText: String,
    unavailableText: String,
    icon: ImageVector,
    onAction: () -> Unit,
    actionText: String
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(16.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$stepNumber",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(icon, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(
                        if (isAvailable) availableText else unavailableText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isAvailable) Color(0xFF4CAF50) else Color(0xFFFF9800)
                    )
                }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(actionText)
            }
        }
    }
}

@Composable
private fun InstructionCard(
    title: String,
    steps: List<String>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            steps.forEachIndexed { index, step ->
                if (step.startsWith("  •")) {
                    Text(step, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun openSoundAssistant(context: Context) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage("com.samsung.android.soundassistant")
        if (intent != null) {
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        // Fallback to sound settings
        openSoundSettings(context)
    }
}

private fun openGalaxyStore(context: Context, packageName: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse("samsungapps://ProductDetail/$packageName")
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to web
    }
}

private fun openSoundSettings(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun openNotificationAccessSettings(context: Context) {
    try {
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}
