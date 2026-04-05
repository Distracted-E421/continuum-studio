package com.example.continuumstudio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.continuumstudio.network.DialogEvent
import com.example.continuumstudio.service.DialogNotificationService
import com.example.continuumstudio.ui.activityfeed.ActivityFeedScreen
import com.example.continuumstudio.ui.cliagents.CLIAgentsScreen
import com.example.continuumstudio.ui.coordination.CoordinationDashboard
import com.example.continuumstudio.ui.dialog.DialogScreen
import com.example.continuumstudio.ui.parkedagents.ParkedAgentsScreen
import com.example.continuumstudio.ui.settings.SettingsScreen
import com.example.continuumstudio.ui.theme.ContinuumStudioTheme
import com.example.continuumstudio.ui.widgets.WidgetBayScreen
import com.example.continuumstudio.ui.widgets.WidgetBayState
import com.example.continuumstudio.viewmodel.ActivityFeedViewModel
import com.example.continuumstudio.viewmodel.CLIAgentsViewModel
import com.example.continuumstudio.viewmodel.CoordinationViewModel
import com.example.continuumstudio.viewmodel.DialogViewModel
import com.example.continuumstudio.viewmodel.NetworkMonitorViewModel
import com.example.continuumstudio.viewmodel.OfflineViewModel
import com.example.continuumstudio.viewmodel.ParkedAgentsViewModel
import com.example.continuumstudio.viewmodel.WidgetBayViewModel

// Navigation routes
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Home", Icons.Default.Home)
    object Dialog : Screen("dialog", "Dialog", Icons.AutoMirrored.Filled.List)
    object CLIAgents : Screen("cli_agents", "Agents", Icons.Default.SmartToy)
    object Activity : Screen("activity", "Feed", Icons.Default.Timeline)
    object Parked : Screen("parked", "Parked", Icons.Default.LocalParking)
    object Coordination : Screen("coordination", "Coord", Icons.Default.Groups)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
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
                val coordinationViewModel: CoordinationViewModel = viewModel()
                val activityFeedViewModel: ActivityFeedViewModel = viewModel()
                val cliAgentsViewModel: CLIAgentsViewModel = viewModel()
                val parkedAgentsViewModel: ParkedAgentsViewModel = viewModel()
                val networkMonitorViewModel: NetworkMonitorViewModel = viewModel()
                val offlineViewModel: OfflineViewModel = viewModel()
                
                // Start all background services at Activity level
                LaunchedEffect(Unit) {
                    activityFeedViewModel.connect()
                    cliAgentsViewModel.startPolling()
                    parkedAgentsViewModel.startPolling()
                    networkMonitorViewModel.startMonitoring()
                }
                
                val connectionState by dialogViewModel.connectionState.collectAsState()
                val dialogState by dialogViewModel.dialogState.collectAsState()
                val history by dialogViewModel.history.collectAsState()
                val historyLoading by dialogViewModel.historyLoading.collectAsState()
                val latency by dialogViewModel.latency.collectAsState()
                val snackbarMessage by dialogViewModel.snackbarMessage.collectAsState()
                val isOnline by dialogViewModel.isOnline.collectAsState()
                val bayConfig by widgetBayViewModel.bayConfig.collectAsState()
                
                // CLI Agents state
                val cliUiState by cliAgentsViewModel.uiState.collectAsState()
                val cliConnected by cliAgentsViewModel.isConnected.collectAsState()
                
                // Activity feed state
                val activityUiState by activityFeedViewModel.uiState.collectAsState()
                
                // Parked agents state
                val parkedUiState by parkedAgentsViewModel.uiState.collectAsState()
                
                // Network monitor state
                val networkState by networkMonitorViewModel.currentState.collectAsState()
                val networkMeasurements by networkMonitorViewModel.measurements.collectAsState(initial = emptyList())
                val networkStats by networkMonitorViewModel.stats.collectAsState()
                val selectedTimeRange by networkMonitorViewModel.selectedTimeRange.collectAsState()
                
                // Offline state
                val offlineState by offlineViewModel.uiState.collectAsState()
                
                // Orchestrator mode state (from dialog or settings)
                val orchestratorMode by dialogViewModel.orchestratorMode.collectAsState()
                
                // Settings states
                val savedServerUrl by dialogViewModel.savedServerUrl.collectAsState()
                val autoReconnect by dialogViewModel.autoReconnect.collectAsState()
                val notificationsEnabled by dialogViewModel.notificationsEnabled.collectAsState()
                val vibrationEnabled by dialogViewModel.vibrationEnabled.collectAsState()
                val cfAccessClientId by dialogViewModel.cfAccessClientId.collectAsState()
                val cfAccessClientSecret by dialogViewModel.cfAccessClientSecret.collectAsState()
                
                // Endpoint configuration states
                val endpoints by dialogViewModel.endpoints.collectAsState()
                val activeEndpointIndex by dialogViewModel.activeEndpointIndex.collectAsState()
                val endpointFallbackEnabled by dialogViewModel.endpointFallbackEnabled.collectAsState()
                val endpointStatus by dialogViewModel.endpointStatus.collectAsState()
                
                // Handle events - show toasts and notifications
                LaunchedEffect(Unit) {
                    dialogViewModel.events.collect { event ->
                        when (event) {
                            is DialogEvent.Connected -> {
                                // Sync endpoint to other ViewModels
                                val baseUrl = dialogViewModel.getActiveEndpointBaseUrl()
                                val wsUrl = dialogViewModel.getActiveEndpointWsUrl()
                                activityFeedViewModel.updateServerUrl("$wsUrl/ws/activity")
                                parkedAgentsViewModel.updateServerUrl(baseUrl)
                                cliAgentsViewModel.updateServerUrl(baseUrl)
                                
                                // Fetch widget data on connection
                                widgetBayViewModel.refreshAll(connectionState.serverUrl)
                                dialogViewModel.showToast("Connected to server")
                                // Clear any connection lost notification
                                DialogNotificationService.notifyConnectionRestored(this@MainActivity)
                            }
                            is DialogEvent.Disconnected -> {
                                if (event.reason.isNotBlank()) {
                                    dialogViewModel.showToast("Disconnected: ${event.reason}")
                                }
                                // Show connection lost notification if app is in background
                                if (!isInForeground) {
                                    DialogNotificationService.notifyConnectionLost(this@MainActivity)
                                }
                                // Try falling back to next endpoint
                                if (endpointFallbackEnabled) {
                                    dialogViewModel.tryFallbackEndpoint()
                                }
                            }
                            is DialogEvent.Reconnecting -> {
                                val delaySeconds = event.delayMs / 1000
                                dialogViewModel.showToast("Reconnecting (attempt ${event.attempt}) in ${delaySeconds}s...")
                            }
                            is DialogEvent.Error -> {
                                dialogViewModel.showToast("Error: ${event.message}")
                                // Try falling back to next endpoint on connection errors
                                if (endpointFallbackEnabled && event.message.contains("connect", ignoreCase = true)) {
                                    dialogViewModel.tryFallbackEndpoint()
                                }
                            }
                            is DialogEvent.NewDialog -> {
                                // Show toast
                                dialogViewModel.showToast("New dialog: ${event.title}")
                                
                                // Show notification if app is in background
                                if (!isInForeground) {
                                    DialogNotificationService.notifyNewDialog(
                                        this@MainActivity,
                                        event.id,
                                        event.title,
                                        event.prompt,
                                        event.dialogType
                                    )
                                }
                                // Auto-navigate to dialog screen when new dialog arrives
                                navController.navigate(Screen.Dialog.route) {
                                    launchSingleTop = true
                                }
                            }
                            is DialogEvent.DialogCompleted -> {
                                // Dialog was completed (by us or desktop)
                                dialogViewModel.showToast("Dialog completed")
                                // Dismiss any dialog notification
                                DialogNotificationService.dismissDialogNotification(this@MainActivity)
                            }
                        }
                    }
                }
                
                // Bottom navigation bar state
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                
                // List of screens for bottom nav
                val bottomNavScreens = listOf(
                    Screen.Dashboard,
                    Screen.Dialog,
                    Screen.CLIAgents,
                    Screen.Activity,
                    Screen.Settings
                )
                
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            bottomNavScreens.forEach { screen ->
                                val isSelected = currentRoute == screen.route
                                NavigationBarItem(
                                    icon = { 
                                        if (screen == Screen.Dialog) {
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
                                                Icon(screen.icon, contentDescription = screen.title)
                                            }
                                        } else {
                                            Icon(screen.icon, contentDescription = screen.title)
                                        }
                                    },
                                    label = { Text(screen.title) },
                                    selected = isSelected,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId)
                                            launchSingleTop = true
                                        }
                                    }
                                )
                            }
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
                            val widgetBayState = WidgetBayState.fromViewModels(
                                dialogConnected = connectionState.isConnected,
                                cliConnected = cliConnected,
                                cliAgents = cliUiState.agents,
                                activeDialog = dialogState.activeDialog,
                                queueCount = dialogState.queueCount,
                                activityEvents = activityUiState.events,
                                mode = orchestratorMode,
                                networkState = networkState,
                                networkMeasurements = networkMeasurements,
                                networkStats = networkStats,
                                selectedTimeRange = selectedTimeRange,
                                pendingOps = offlineState.pendingCount,
                                parkedCount = parkedUiState.agents.size
                            )
                            
                            WidgetBayScreen(
                                bayConfig = bayConfig,
                                state = widgetBayState,
                                onAddWidget = widgetBayViewModel::addWidget,
                                onRemoveWidget = widgetBayViewModel::removeWidget,
                                onRefresh = { 
                                    networkMonitorViewModel.performSinglePing()
                                    cliAgentsViewModel.refresh()
                                    parkedAgentsViewModel.refresh()
                                },
                                onNavigateToAgents = {
                                    navController.navigate(Screen.CLIAgents.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToDialog = {
                                    navController.navigate(Screen.Dialog.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToActivity = {
                                    navController.navigate(Screen.Activity.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToParked = {
                                    navController.navigate(Screen.Parked.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onNavigateToSettings = {
                                    navController.navigate(Screen.Settings.route) {
                                        launchSingleTop = true
                                    }
                                },
                                onModeChange = { mode ->
                                    dialogViewModel.setOrchestratorMode(mode)
                                },
                                onTimeRangeChange = { range ->
                                    networkMonitorViewModel.setTimeRange(range)
                                },
                                onNetworkRefresh = {
                                    networkMonitorViewModel.performSinglePing()
                                },
                                onQuickRespond = { dialogId, optionValue ->
                                    dialogViewModel.quickRespond(dialogId, optionValue)
                                },
                                onQuickAction = { action ->
                                    dialogViewModel.executeAction(action)
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
                                history = history,
                                historyLoading = historyLoading,
                                latency = latency,
                                snackbarMessage = snackbarMessage,
                                isOnline = isOnline,
                                savedServerUrl = savedServerUrl,
                                autoReconnect = autoReconnect,
                                notificationsEnabled = notificationsEnabled,
                                vibrationEnabled = vibrationEnabled,
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
                                onFetchHistory = dialogViewModel::fetchHistory,
                                onReinvokeDialog = dialogViewModel::reinvokeDialog,
                                onSnackbarDismiss = dialogViewModel::dismissToast,
                                onAutoReconnectChange = dialogViewModel::setAutoReconnect,
                                onNotificationsEnabledChange = dialogViewModel::setNotificationsEnabled,
                                onVibrationEnabledChange = dialogViewModel::setVibrationEnabled,
                                cfAccessClientId = cfAccessClientId,
                                cfAccessClientSecret = cfAccessClientSecret,
                                onCfAccessClientIdChange = dialogViewModel::setCfAccessClientId,
                                onCfAccessClientSecretChange = dialogViewModel::setCfAccessClientSecret,
                                onTestNotification = {
                                    DialogNotificationService.notifyNewDialog(
                                        this@MainActivity,
                                        "test-${System.currentTimeMillis()}",
                                        "Test Notification",
                                        "This is a test notification from Continuum Studio. If you see this, notifications are working correctly!"
                                    )
                                    dialogViewModel.showToast("Test notification sent!")
                                },
                                onCopyToClipboard = { text ->
                                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Dialog Response", text)
                                    clipboard.setPrimaryClip(clip)
                                    dialogViewModel.showToast("Copied to clipboard!")
                                },
                                // Endpoint configuration
                                endpoints = endpoints,
                                activeEndpointIndex = activeEndpointIndex,
                                endpointFallbackEnabled = endpointFallbackEnabled,
                                endpointStatus = endpointStatus,
                                onAddEndpoint = dialogViewModel::addEndpoint,
                                onRemoveEndpoint = dialogViewModel::removeEndpoint,
                                onToggleEndpoint = dialogViewModel::toggleEndpoint,
                                onSetActiveEndpoint = dialogViewModel::setActiveEndpoint,
                                onSetEndpointFallbackEnabled = dialogViewModel::setEndpointFallbackEnabled,
                                onTestEndpoint = dialogViewModel::testEndpoint,
                                onTestAllEndpoints = dialogViewModel::testAllEndpoints,
                            )
                        }
                        
                        composable(
                            Screen.Coordination.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            CoordinationDashboard(
                                viewModel = coordinationViewModel
                            )
                        }
                        
                        composable(
                            Screen.CLIAgents.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            CLIAgentsScreen(viewModel = cliAgentsViewModel)
                        }
                        
                        composable(
                            Screen.Activity.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            ActivityFeedScreen(viewModel = activityFeedViewModel)
                        }
                        
                        composable(
                            Screen.Parked.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            ParkedAgentsScreen(viewModel = parkedAgentsViewModel)
                        }
                        
                        composable(
                            Screen.Settings.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            SettingsScreen()
                        }
                    }
                }
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
