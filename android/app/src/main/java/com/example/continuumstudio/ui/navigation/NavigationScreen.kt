package com.example.continuumstudio.ui.navigation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.continuumstudio.navigation.ManeuverType
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.navigation.Waypoint
import com.example.continuumstudio.viewmodel.NavigationViewModel

/**
 * Navigation screen for turn-by-turn directions.
 * Uses OSMDroid maps and OSRM routing (open source stack).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    navigationViewModel: NavigationViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val navigationState by navigationViewModel.navigationState.collectAsState()
    val currentLocation by navigationViewModel.currentLocation.collectAsState()
    val currentRoute by navigationViewModel.currentRoute.collectAsState()
    val routeSteps by navigationViewModel.routeSteps.collectAsState()
    val currentStepIndex by navigationViewModel.currentStepIndex.collectAsState()
    val errorMessage by navigationViewModel.errorMessage.collectAsState()
    
    var destinationInput by remember { mutableStateOf("") }
    var showCoordinatesInput by remember { mutableStateOf(false) }
    var latInput by remember { mutableStateOf("") }
    var lonInput by remember { mutableStateOf("") }
    var showSavedPlaces by remember { mutableStateOf(false) }
    
    val focusManager = LocalFocusManager.current
    
    // Handle error messages
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            // Show snackbar or toast
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigation") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    // GPS status indicator
                    Surface(
                        color = if (currentLocation != null) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                if (currentLocation != null) Icons.Default.GpsFixed 
                                else Icons.Default.GpsOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (currentLocation != null) "GPS Ready" else "No GPS",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    
                    IconButton(onClick = { navigationViewModel.recenter() }) {
                        Icon(Icons.Default.MyLocation, "Recenter")
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
            // Navigation is active - show active navigation UI
            if (navigationState.isNavigating) {
                ActiveNavigationContent(
                    navigationState = navigationState,
                    currentRoute = currentRoute,
                    routeSteps = routeSteps,
                    currentStepIndex = currentStepIndex,
                    onStopNavigation = { navigationViewModel.stopNavigation() },
                    onAdvanceStep = { navigationViewModel.advanceToNextStep() },
                    onSkipWaypoint = { navigationViewModel.skipToNextWaypoint() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Navigation not active - show destination input
                DestinationInputContent(
                    destinationInput = destinationInput,
                    onDestinationInputChange = { destinationInput = it },
                    showCoordinatesInput = showCoordinatesInput,
                    onToggleCoordinatesInput = { showCoordinatesInput = !showCoordinatesInput },
                    latInput = latInput,
                    onLatInputChange = { latInput = it },
                    lonInput = lonInput,
                    onLonInputChange = { lonInput = it },
                    currentLocation = currentLocation,
                    errorMessage = errorMessage,
                    onClearError = { navigationViewModel.clearError() },
                    onStartNavigation = { name, lat, lon ->
                        navigationViewModel.startNavigation(name, lat, lon)
                        focusManager.clearFocus()
                    },
                    onRefreshLocation = { navigationViewModel.updateCurrentLocation() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ActiveNavigationContent(
    navigationState: NavigationState,
    currentRoute: com.example.continuumstudio.navigation.NavigationRoute?,
    routeSteps: List<com.example.continuumstudio.navigation.RouteStep>,
    currentStepIndex: Int,
    onStopNavigation: () -> Unit,
    onAdvanceStep: () -> Unit,
    onSkipWaypoint: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Current instruction card (large, prominent)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Maneuver icon
                        ManeuverIcon(
                            type = navigationState.maneuverType,
                            modifier = Modifier.size(64.dp)
                        )
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                navigationState.distanceToNextTurn,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                navigationState.currentInstruction,
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (navigationState.nextStreetName.isNotEmpty()) {
                                Text(
                                    navigationState.nextStreetName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
        
        // Summary card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(navigationState.timeRemaining, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Time", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(navigationState.distanceRemaining, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Distance", style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(navigationState.arrivalTime, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("ETA", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        
        // Upcoming steps
        if (routeSteps.isNotEmpty()) {
            item {
                Text(
                    "Upcoming Turns",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            
            items(routeSteps.drop(currentStepIndex).take(5)) { step ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ManeuverIcon(
                            type = step.maneuverType,
                            modifier = Modifier.size(32.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(step.instruction, style = MaterialTheme.typography.bodyMedium)
                            if (step.streetName.isNotEmpty()) {
                                Text(
                                    step.streetName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Text(
                            formatDistance(step.distanceMeters),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
        
        // Control buttons
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onAdvanceStep,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.SkipNext, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Next Step")
                }
                
                Button(
                    onClick = onStopNavigation,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Stop, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun DestinationInputContent(
    destinationInput: String,
    onDestinationInputChange: (String) -> Unit,
    showCoordinatesInput: Boolean,
    onToggleCoordinatesInput: () -> Unit,
    latInput: String,
    onLatInputChange: (String) -> Unit,
    lonInput: String,
    onLonInputChange: (String) -> Unit,
    currentLocation: org.osmdroid.util.GeoPoint?,
    errorMessage: String?,
    onClearError: () -> Unit,
    onStartNavigation: (String, Double, Double) -> Unit,
    onRefreshLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Error message
        errorMessage?.let { error ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
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
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        IconButton(onClick = onClearError) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
            }
        }
        
        // Current location info
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Current Location", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = onRefreshLocation) {
                            Icon(Icons.Default.Refresh, "Refresh location")
                        }
                    }
                    
                    if (currentLocation != null) {
                        Text(
                            "Lat: ${String.format("%.6f", currentLocation.latitude)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Lon: ${String.format("%.6f", currentLocation.longitude)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "Location not available. Grant location permission and enable GPS.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        
        // Input mode toggle
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !showCoordinatesInput,
                    onClick = { if (showCoordinatesInput) onToggleCoordinatesInput() },
                    label = { Text("Search") },
                    leadingIcon = if (!showCoordinatesInput) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null
                )
                FilterChip(
                    selected = showCoordinatesInput,
                    onClick = { if (!showCoordinatesInput) onToggleCoordinatesInput() },
                    label = { Text("Coordinates") },
                    leadingIcon = if (showCoordinatesInput) {
                        { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }
                    } else null
                )
            }
        }
        
        // Destination input
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Enter Destination", style = MaterialTheme.typography.titleMedium)
                    
                    if (showCoordinatesInput) {
                        // Coordinates input
                        OutlinedTextField(
                            value = destinationInput,
                            onValueChange = onDestinationInputChange,
                            label = { Text("Name (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = latInput,
                                onValueChange = onLatInputChange,
                                label = { Text("Latitude") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Next
                                ),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = lonInput,
                                onValueChange = onLonInputChange,
                                label = { Text("Longitude") },
                                modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                singleLine = true
                            )
                        }
                        
                        Button(
                            onClick = {
                                val lat = latInput.toDoubleOrNull()
                                val lon = lonInput.toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    onStartNavigation(
                                        destinationInput.ifEmpty { "Destination" },
                                        lat,
                                        lon
                                    )
                                }
                            },
                            enabled = currentLocation != null && 
                                     latInput.toDoubleOrNull() != null && 
                                     lonInput.toDoubleOrNull() != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start Navigation")
                        }
                    } else {
                        // Search input (placeholder - would need geocoding API)
                        OutlinedTextField(
                            value = destinationInput,
                            onValueChange = onDestinationInputChange,
                            label = { Text("Search address") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {
                                // TODO: Implement geocoding search
                            })
                        )
                        
                        Text(
                            "Address search requires a geocoding service. Use coordinates for now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        
        // Quick destinations
        item {
            Text("Quick Destinations", style = MaterialTheme.typography.titleMedium)
        }
        
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Example quick destinations - these would be customizable
                QuickDestinationChip(
                    label = "Home",
                    icon = Icons.Default.Home,
                    onClick = { /* TODO: Load saved home location */ }
                )
                QuickDestinationChip(
                    label = "Work",
                    icon = Icons.Default.Work,
                    onClick = { /* TODO: Load saved work location */ }
                )
            }
        }
        
        // Info card about open source stack
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
                            null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text("Open Source Navigation", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Powered by OpenStreetMap (OSMDroid) and OSRM routing. " +
                        "No API keys required. Works offline with cached tiles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickDestinationChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) }
    )
}

@Composable
private fun ManeuverIcon(
    type: ManeuverType,
    modifier: Modifier = Modifier
) {
    val icon = when (type) {
        ManeuverType.STRAIGHT -> Icons.Default.ArrowUpward
        ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> Icons.Default.KeyboardArrowLeft
        ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> Icons.Default.KeyboardArrowRight
        ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> Icons.Default.Refresh
        ManeuverType.MERGE_LEFT, ManeuverType.MERGE_RIGHT -> Icons.Default.CallMerge
        ManeuverType.ROUNDABOUT -> Icons.Default.RotateRight
        ManeuverType.EXIT -> Icons.Default.ExitToApp
        ManeuverType.ARRIVE -> Icons.Default.Flag
        ManeuverType.DEPART -> Icons.Default.Place
        ManeuverType.NONE -> Icons.Default.RadioButtonUnchecked
    }
    
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = CircleShape
    ) {
        Icon(
            icon,
            contentDescription = type.name,
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize(),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun formatDistance(meters: Double): String {
    return if (meters >= 1609) {
        String.format("%.1f mi", meters / 1609.34)
    } else {
        String.format("%d ft", (meters * 3.28084).toInt())
    }
}
