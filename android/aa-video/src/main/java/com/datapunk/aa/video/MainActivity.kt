package com.datapunk.aa.video

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phone-side screen.
 *
 * This app's job is to prove the car-surface path, so the phone UI explains what
 * to expect and what to check, rather than pretending to be a media app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            "synapsix-aa-video",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Experimental. Proves that we can draw our own frames onto an " +
                                "Android Auto head unit.",
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Divider()

                        Text("What should happen", fontWeight = FontWeight.Medium)
                        Text(
                            "1. Plug the phone into the car.\n" +
                                "2. Open this app from the car's app drawer.\n" +
                                "3. A blue pulsing circle with 'Surface OK' appears on the " +
                                "head unit, proving the surface is ours.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Text("If it does not appear", fontWeight = FontWeight.Medium)
                        Text(
                            "Templated car apps are refused on physical head units when " +
                                "sideloaded. The workaround is to declare the Play Store as " +
                                "the installer:\n\n" +
                                "adb shell pm install -i com.android.vending -r app.apk",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(Modifier.weight(1f))

                        Text(
                            "Debug builds only. The host validator is permissive in debug " +
                                "so the Desktop Head Unit works.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
