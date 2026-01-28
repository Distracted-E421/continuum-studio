package com.example.continuumstudio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.continuumstudio.network.DialogEvent
import com.example.continuumstudio.service.DialogNotificationService
import com.example.continuumstudio.ui.dialog.DialogScreen
import com.example.continuumstudio.ui.theme.ContinuumStudioTheme
import com.example.continuumstudio.viewmodel.DialogViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private var isInForeground = false
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            // Notification permission granted - start service
            DialogNotificationService.startService(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Permission already granted - start service
                DialogNotificationService.startService(this)
            }
        } else {
            // Pre-Android 13 - no permission needed
            DialogNotificationService.startService(this)
        }

        setContent {
            ContinuumStudioTheme {
                val viewModel: DialogViewModel = viewModel()
                val connectionState by viewModel.connectionState.collectAsState()
                val dialogState by viewModel.dialogState.collectAsState()
                val savedUrl by viewModel.savedServerUrl.collectAsState(initial = "obsidian:8080")
                
                // Handle events
                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is DialogEvent.Connected -> {
                                // Could show a toast
                            }
                            is DialogEvent.Disconnected -> {
                                // Handle disconnection
                            }
                            is DialogEvent.Error -> {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Error: ${event.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            is DialogEvent.NewDialog -> {
                                // Show notification if app is in background
                                if (!isInForeground) {
                                    DialogNotificationService.notifyNewDialog(
                                        this@MainActivity,
                                        event.id,
                                        event.title,
                                        event.prompt
                                    )
                                }
                            }
                            is DialogEvent.DialogCompleted -> {
                                // Dialog was completed (by us or desktop)
                            }
                        }
                    }
                }

                DialogScreen(
                    connectionState = connectionState,
                    dialogState = dialogState,
                    onConnect = viewModel::connect,
                    onDisconnect = viewModel::disconnect,
                    onRefresh = viewModel::refreshDialog,
                    onSelectOption = viewModel::selectOption,
                    onToggleOption = viewModel::toggleOption,
                    onTextChange = viewModel::updateTextInput,
                    onCommentChange = viewModel::updateComment,
                    onSliderChange = viewModel::updateSliderValue,
                    onConfirm = viewModel::setConfirmation,
                    onSubmit = viewModel::submitAnswer,
                    onToggleHoldMode = viewModel::toggleHoldMode,
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        isInForeground = true
    }
    
    override fun onPause() {
        super.onPause()
        isInForeground = false
    }
}
