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
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.continuumstudio.network.DialogEvent
import com.example.continuumstudio.service.DialogNotificationService
import com.example.continuumstudio.ui.dialog.DialogScreen
import com.example.continuumstudio.ui.theme.ContinuumStudioTheme
import com.example.continuumstudio.ui.widgets.WidgetBayScreen
import com.example.continuumstudio.viewmodel.DialogViewModel
import com.example.continuumstudio.viewmodel.WidgetBayViewModel

// Navigation routes
sealed class Screen(val route: String, val title: String) {
    object Dashboard : Screen("dashboard", "Dashboard")
    object Dialog : Screen("dialog", "Dialog")
}

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
                val navController = rememberNavController()
                val dialogViewModel: DialogViewModel = viewModel()
                val widgetBayViewModel: WidgetBayViewModel = viewModel()
                
                val connectionState by dialogViewModel.connectionState.collectAsState()
                val dialogState by dialogViewModel.dialogState.collectAsState()
                val bayConfig by widgetBayViewModel.bayConfig.collectAsState()
                val harnesses by widgetBayViewModel.harnesses.collectAsState()
                val services by widgetBayViewModel.services.collectAsState()
                val isLoadingHarnesses by widgetBayViewModel.isLoadingHarnesses.collectAsState()
                val isLoadingServices by widgetBayViewModel.isLoadingServices.collectAsState()
                
                // Handle events
                LaunchedEffect(Unit) {
                    dialogViewModel.events.collect { event ->
                        when (event) {
                            is DialogEvent.Connected -> {
                                // Fetch widget data on connection
                                widgetBayViewModel.refreshAll(connectionState.serverUrl)
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
                                // Auto-navigate to dialog screen when new dialog arrives
                                navController.navigate(Screen.Dialog.route) {
                                    launchSingleTop = true
                                }
                            }
                            is DialogEvent.DialogCompleted -> {
                                // Dialog was completed (by us or desktop)
                            }
                        }
                    }
                }
                
                // Bottom navigation bar state
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Dashboard") },
                                label = { Text("Dashboard") },
                                selected = currentRoute == Screen.Dashboard.route,
                                onClick = {
                                    navController.navigate(Screen.Dashboard.route) {
                                        popUpTo(navController.graph.startDestinationId)
                                        launchSingleTop = true
                                    }
                                }
                            )
                            NavigationBarItem(
                                icon = { 
                                    BadgedBox(
                                        badge = {
                                            if (dialogState.queueCount > 0 || dialogState.activeDialog != null) {
                                                Badge { 
                                                    Text(
                                                        if (dialogState.activeDialog != null) "!" 
                                                        else dialogState.queueCount.toString()
                                                    )
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.List, contentDescription = "Dialog")
                                    }
                                },
                                label = { Text("Dialog") },
                                selected = currentRoute == Screen.Dialog.route,
                                onClick = {
                                    navController.navigate(Screen.Dialog.route) {
                                        popUpTo(navController.graph.startDestinationId)
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable(
                            Screen.Dashboard.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) }
                        ) {
                            WidgetBayScreen(
                                bayConfig = bayConfig,
                                connectionState = connectionState,
                                dialogState = dialogState,
                                harnesses = harnesses,
                                services = services,
                                isLoadingHarnesses = isLoadingHarnesses,
                                isLoadingServices = isLoadingServices,
                                onAddWidget = widgetBayViewModel::addWidget,
                                onRemoveWidget = widgetBayViewModel::removeWidget,
                                onRefresh = { 
                                    if (connectionState.isConnected) {
                                        widgetBayViewModel.refreshAll(connectionState.serverUrl)
                                    }
                                },
                                onNavigateToDialog = {
                                    navController.navigate(Screen.Dialog.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onQuickAction = { action ->
                                    handleQuickAction(action, connectionState.serverUrl)
                                }
                            )
                        }
                        
                        composable(
                            Screen.Dialog.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            DialogScreen(
                                connectionState = connectionState,
                                dialogState = dialogState,
                                onConnect = dialogViewModel::connect,
                                onDisconnect = dialogViewModel::disconnect,
                                onRefresh = dialogViewModel::refreshDialog,
                                onSelectOption = dialogViewModel::selectOption,
                                onToggleOption = dialogViewModel::toggleOption,
                                onTextChange = dialogViewModel::updateTextInput,
                                onCommentChange = dialogViewModel::updateComment,
                                onSliderChange = dialogViewModel::updateSliderValue,
                                onConfirm = dialogViewModel::setConfirmation,
                                onSubmit = dialogViewModel::submitAnswer,
                                onToggleHoldMode = dialogViewModel::toggleHoldMode,
                            )
                        }
                    }
                }
            }
        }
    }
    
    private fun handleQuickAction(action: String, serverUrl: String) {
        // TODO: Implement quick action RPC calls to Synapsix
        Toast.makeText(
            this,
            "Quick action: $action (coming soon)",
            Toast.LENGTH_SHORT
        ).show()
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
