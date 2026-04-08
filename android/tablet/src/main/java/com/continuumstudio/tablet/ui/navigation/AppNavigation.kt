package com.continuumstudio.tablet.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.continuumstudio.tablet.UiMode
import com.continuumstudio.tablet.ui.screens.*
import com.continuumstudio.tablet.ui.theme.LocalIsFiveFootMode
import com.continuumstudio.tablet.viewmodel.MainViewModel
import kotlinx.coroutines.launch

enum class Screen(
    val title: String,
    val icon: ImageVector,
    val route: String,
) {
    Dialogs("Dialog Inbox", Icons.Default.Email, "dialogs"),
    History("Dialog History", Icons.Default.History, "history"),
    Activity("Activity Log", Icons.Default.List, "activity"),
    Agents("CLI Agents", Icons.Default.Android, "agents"),
    Orchestrator("Orchestrator", Icons.Default.Settings, "orchestrator"),
    Parked("Parked Agents", Icons.Default.DirectionsCar, "parked"),
    Tasks("Task Queue", Icons.Default.CheckCircle, "tasks"),
    Settings("Settings", Icons.Default.Settings, "settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    viewModel: MainViewModel,
    onModeToggle: () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.Dialogs) }
    val isFiveFootMode = LocalIsFiveFootMode.current
    val uiMode by viewModel.uiMode.collectAsState()
    
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(if (isFiveFootMode) 320.dp else 280.dp)
            ) {
                Spacer(Modifier.height(16.dp))
                
                Text(
                    text = "Continuum Tablet",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                Screen.entries.forEach { screen ->
                    NavigationDrawerItem(
                        icon = { 
                            Icon(
                                screen.icon, 
                                contentDescription = screen.title,
                                modifier = if (isFiveFootMode) Modifier.size(32.dp) else Modifier
                            )
                        },
                        label = { 
                            Text(
                                screen.title,
                                style = if (isFiveFootMode) MaterialTheme.typography.titleMedium 
                                       else MaterialTheme.typography.bodyLarge
                            )
                        },
                        selected = currentScreen == screen,
                        onClick = {
                            currentScreen = screen
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = if (isFiveFootMode) 4.dp else 2.dp)
                            .height(if (isFiveFootMode) 72.dp else 56.dp)
                    )
                }
                
                Spacer(Modifier.weight(1f))
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                NavigationDrawerItem(
                    icon = { 
                        Icon(
                            if (uiMode == UiMode.FiveFoot) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "Toggle Mode"
                        )
                    },
                    label = { 
                        Text(
                            if (uiMode == UiMode.FiveFoot) "Info-Dense Mode" else "5-Foot Mode"
                        )
                    },
                    selected = false,
                    onClick = onModeToggle,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                
                Spacer(Modifier.height(16.dp))
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { 
                        Text(
                            currentScreen.title,
                            style = if (isFiveFootMode) MaterialTheme.typography.headlineMedium 
                                   else MaterialTheme.typography.titleLarge
                        )
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = if (isFiveFootMode) Modifier.size(64.dp) else Modifier
                        ) {
                            Icon(
                                Icons.Default.Menu, 
                                contentDescription = "Menu",
                                modifier = if (isFiveFootMode) Modifier.size(32.dp) else Modifier
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onModeToggle,
                            modifier = if (isFiveFootMode) Modifier.size(64.dp) else Modifier
                        ) {
                            Icon(
                                if (uiMode == UiMode.FiveFoot) Icons.Default.ZoomOut else Icons.Default.ZoomIn,
                                contentDescription = "Toggle Mode",
                                modifier = if (isFiveFootMode) Modifier.size(32.dp) else Modifier
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (currentScreen) {
                    Screen.Dialogs -> DialogInboxScreen(viewModel)
                    Screen.History -> DialogHistoryScreen(viewModel)
                    Screen.Activity -> ActivityFeedScreen(viewModel)
                    Screen.Agents -> AgentsScreen(viewModel)
                    Screen.Orchestrator -> OrchestratorScreen(viewModel)
                    Screen.Parked -> ParkedAgentsScreen(viewModel)
                    Screen.Tasks -> TaskQueueScreen(viewModel)
                    Screen.Settings -> SettingsScreen(viewModel)
                }
            }
        }
    }
}
