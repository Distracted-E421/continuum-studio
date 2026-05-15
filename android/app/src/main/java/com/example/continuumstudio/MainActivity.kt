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
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Map
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
import com.example.continuumstudio.ui.settings.UpdateScreen
import com.example.continuumstudio.ui.theme.ContinuumStudioTheme
import com.example.continuumstudio.viewmodel.UpdateViewModel
import com.example.continuumstudio.ui.widgets.WidgetBayScreen
import com.example.continuumstudio.ui.widgets.WidgetBayState
import com.example.continuumstudio.ui.xr.XRConfigScreen
import com.example.continuumstudio.ui.xrcontrol.ControlSurfaceScreen
import com.example.continuumstudio.viewmodel.ControlSurfaceViewModel
import com.example.continuumstudio.service.NowPlayingService
import com.example.continuumstudio.viewmodel.ActivityFeedViewModel
import com.example.continuumstudio.viewmodel.CLIAgentsViewModel
import com.example.continuumstudio.viewmodel.CoordinationViewModel
import com.example.continuumstudio.viewmodel.DialogViewModel
import com.example.continuumstudio.viewmodel.NetworkMonitorViewModel
import com.example.continuumstudio.viewmodel.OfflineViewModel
import com.example.continuumstudio.viewmodel.ParkedAgentsViewModel
import com.example.continuumstudio.viewmodel.WidgetBayViewModel
import com.example.continuumstudio.viewmodel.XRViewModel
import com.example.continuumstudio.viewmodel.YouTubeViewModel
import com.example.continuumstudio.viewmodel.NavigationViewModel
import com.example.continuumstudio.ui.navigation.NavigationScreen
import com.example.continuumstudio.util.rememberDisplayInfo
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.continuumstudio.ui.components.DisplayModeIndicator
import com.example.continuumstudio.ui.components.AdaptiveLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

// Navigation routes
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : Screen("dashboard", "Home", Icons.Default.Home)
    object Dialog : Screen("dialog", "Dialog", Icons.AutoMirrored.Filled.List)
    object CLIAgents : Screen("cli_agents", "Agents", Icons.Default.SmartToy)
    object Activity : Screen("activity", "Feed", Icons.Default.Timeline)
    object Parked : Screen("parked", "Parked", Icons.Default.LocalParking)
    object Coordination : Screen("coordination", "Coord", Icons.Default.Groups)
    object ControlSurface : Screen("control_surface", "Deck", Icons.Default.Dashboard)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object XRConfig : Screen("xr_config", "XR Config", Icons.Outlined.Tune)
    object Updates : Screen("updates", "Updates", Icons.Default.SystemUpdate)
    object Navigation : Screen("navigation", "Nav", Icons.Default.Map)
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
                val xrViewModel: XRViewModel = viewModel()
                val youtubeViewModel: YouTubeViewModel = viewModel()
                val updateViewModel: UpdateViewModel = viewModel()
                val controlSurfaceViewModel: ControlSurfaceViewModel = viewModel()
                val navigationViewModel: NavigationViewModel = viewModel()
                
                // Display mode detection for DeX/glasses support
                val displayInfo by rememberDisplayInfo()
                
                // Start all background services at Activity level
                LaunchedEffect(Unit) {
                    activityFeedViewModel.connect()
                    cliAgentsViewModel.startPolling()
                    parkedAgentsViewModel.startPolling()
                    networkMonitorViewModel.startMonitoring()
                }
                
                // XR Glasses lifecycle management
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = xrViewModel.glassesManager.createLifecycleObserver { xrViewModel.glassesTheme.value }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }
                
                val connectionState by dialogViewModel.connectionState.collectAsState()
                val dialogState by dialogViewModel.dialogState.collectAsState()
                val history by dialogViewModel.history.collectAsState()
                val historyLoading by dialogViewModel.historyLoading.collectAsState()
                val latency by dialogViewModel.latency.collectAsState()
                val snackbarMessage by dialogViewModel.snackbarMessage.collectAsState()
                val isOnline by dialogViewModel.isOnline.collectAsState()
                val queueItems by dialogViewModel.queueItems.collectAsState()
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
                
                // XR/Glasses state
                val isGlassesConnected by xrViewModel.isGlassesConnected.collectAsState()
                val glassesDisplayState by xrViewModel.glassesDisplayState.collectAsState()
                val xrLayoutMode by xrViewModel.layoutMode.collectAsState()
                
                // Bridge states to glasses display
                LaunchedEffect(dialogState.activeDialog) {
                    xrViewModel.updateDialogForGlasses(dialogState.activeDialog)
                }
                
                LaunchedEffect(activityUiState.events) {
                    xrViewModel.updateActivityForGlasses(activityUiState.events)
                }
                
                LaunchedEffect(connectionState.isConnected, cliUiState.agents.size, latency) {
                    xrViewModel.updateConnectionForGlasses(
                        isConnected = connectionState.isConnected,
                        serverUrl = connectionState.serverUrl,
                        latencyMs = latency,
                        agentCount = cliUiState.agents.size
                    )
                }
                
                // Bridge now playing state to glasses display
                val nowPlayingState by NowPlayingService.nowPlayingState.collectAsState()
                
                // YouTube state
                val youtubeState by youtubeViewModel.youtubeState.collectAsState()
                LaunchedEffect(nowPlayingState) {
                    xrViewModel.updateNowPlayingForGlasses(nowPlayingState)
                }
                
                // Bridge YouTube state to glasses
                LaunchedEffect(youtubeState) {
                    xrViewModel.updateYoutubeForGlasses(youtubeState)
                }
                
                // Bridge control surface state to glasses
                val controlSurfaceState by controlSurfaceViewModel.state.collectAsState()
                LaunchedEffect(
                    controlSurfaceState.activeProfile?.name,
                    controlSurfaceState.activeProfile?.widgets?.size,
                    controlSurfaceState.isEditMode
                ) {
                    xrViewModel.updateControlSurfaceForGlasses(
                        activeProfileName = controlSurfaceState.activeProfile?.name,
                        activeProfileIcon = controlSurfaceState.activeProfile?.icon,
                        widgetCount = controlSurfaceState.activeProfile?.widgets?.size ?: 0,
                        lastAction = null,
                        isEditMode = controlSurfaceState.isEditMode
                    )
                }
                
                // Show action feedback on glasses when widget is tapped
                LaunchedEffect(controlSurfaceState.lastAction, controlSurfaceState.actionHistory.size) {
                    controlSurfaceState.actionHistory.lastOrNull()?.let { executed ->
                        val widgetLabel = controlSurfaceState.activeProfile?.widgets
                            ?.find { it.id == executed.widgetId }?.config?.label
                        xrViewModel.showControlSurfaceAction(
                            actionDescription = executed.result ?: "Action executed",
                            widgetLabel = widgetLabel
                        )
                    }
                }
                
                // Bridge navigation state to glasses (OSM map + turn-by-turn)
                val navState by navigationViewModel.navigationState.collectAsState()
                val navLocation by navigationViewModel.currentLocation.collectAsState()
                val navDestination by navigationViewModel.destination.collectAsState()
                val navRouteGeometry by navigationViewModel.routeGeometry.collectAsState()
                
                LaunchedEffect(navState) {
                    xrViewModel.updateNavigationForGlasses(navState)
                }
                
                LaunchedEffect(navLocation, navDestination, navRouteGeometry) {
                    xrViewModel.updateOsmMapForGlasses(
                        location = navLocation,
                        destination = navDestination,
                        routeGeometry = navRouteGeometry
                    )
                }
                
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
                    Screen.ControlSurface,
                    Screen.CLIAgents,
                    Screen.Activity,
                    Screen.Settings
                )
                
                Scaffold(
                    topBar = {
                        // Show display mode indicator when in DeX or external display mode
                        if (displayInfo.hasExternalDisplay || isGlassesConnected) {
                            androidx.compose.material3.Surface(
                                color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                                modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                            ) {
                                androidx.compose.foundation.layout.Row(
                                    modifier = androidx.compose.ui.Modifier.padding(8.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                    ) {
                                        DisplayModeIndicator(displayInfo = displayInfo)
                                        IconButton(
                                            onClick = { navController.navigate(Screen.XRConfig.route) }
                                        ) {
                                            Icon(
                                                Icons.Outlined.Tune,
                                                contentDescription = "XR Settings"
                                            )
                                        }
                                    }
                                    androidx.compose.material3.Text(
                                        text = if (isGlassesConnected)
                                        "Glasses: " + (glassesDisplayState.connectedDisplayName ?: "Connected")
                                    else
                                        "External Display",
                                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    },
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
                    AdaptiveLayout(
                        displayInfo = displayInfo,
                        primaryContent = {
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Dashboard.route,
                        modifier = Modifier.fillMaxSize()
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
                                // Queue functionality
                                queueItems = queueItems,
                                onSwitchToQueuedDialog = dialogViewModel::switchToQueuedDialog,
                                onToggleQueueDrawer = dialogViewModel::toggleQueueDrawer,
                                // XR Mode (phone as input device when glasses connected)
                                isXRMode = isGlassesConnected,
                                onXROptionIndexChange = { index ->
                                    xrViewModel.updateSelectedOption(index)
                                },
                                onTypingStateChange = { isTyping ->
                                    xrViewModel.updateTypingText(if (isTyping) dialogState.comment else null)
                                },
                                onScrollGlasses = { scrollAmount ->
                                    xrViewModel.scrollGlassesContent(scrollAmount)
                                }
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
                            SettingsScreen(
                                onNavigateToUpdates = {
                                    navController.navigate(Screen.Updates.route) {
                                        launchSingleTop = true
                                    }
                                }
                            )
                        }
                        
                        composable(
                            Screen.Updates.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            UpdateScreen(
                                viewModel = updateViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        
                        composable(
                            Screen.ControlSurface.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            ControlSurfaceScreen(
                                viewModel = controlSurfaceViewModel
                            )
                        }
                        
                        composable(
                            Screen.XRConfig.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            XRConfigScreen(
                                youtubeViewModel = youtubeViewModel,
                                xrViewModel = xrViewModel,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToNavigation = { navController.navigate(Screen.Navigation.route) }
                            )
                        }
                        
                        composable(
                            Screen.Navigation.route,
                            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) },
                            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) }
                        ) {
                            NavigationScreen(
                                navigationViewModel = navigationViewModel,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
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
