package com.continuumstudio.tablet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.continuumstudio.tablet.ui.navigation.AppNavigation
import com.continuumstudio.tablet.ui.theme.ContinuumTabletTheme
import com.continuumstudio.tablet.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val viewModel: MainViewModel = viewModel()
            val uiMode by viewModel.uiMode.collectAsState()
            val settings by viewModel.settings.collectAsState()
            
            ContinuumTabletTheme(
                isFiveFootMode = uiMode == UiMode.FiveFoot,
                fontScale = settings.fontScale
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        viewModel = viewModel,
                        onModeToggle = { viewModel.toggleMode() }
                    )
                }
            }
        }
    }
}

enum class UiMode {
    InfoDense,
    FiveFoot
}
