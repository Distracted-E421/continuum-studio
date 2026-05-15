package com.example.continuumstudio.viewmodel

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.navigation.ManeuverType
import com.example.continuumstudio.navigation.NavigationRoute
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.navigation.Waypoint
import com.example.continuumstudio.navigation.OsrmRoutingService
import com.example.continuumstudio.navigation.RouteResult
import com.example.continuumstudio.navigation.RouteStep
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.util.GeoPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ViewModel for managing turn-by-turn navigation.
 * 
 * Uses OSMDroid for maps and OSRM for routing (open source stack).
 * 
 * Features:
 * - Turn-by-turn directions from OSRM
 * - Real-time location tracking
 * - Multi-stop (errands) navigation
 * - Wireframe overlay for AR glasses
 */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()
    
    private val _currentRoute = MutableStateFlow<NavigationRoute?>(null)
    val currentRoute: StateFlow<NavigationRoute?> = _currentRoute.asStateFlow()
    
    // Route geometry for map display
    private val _routeGeometry = MutableStateFlow<List<GeoPoint>>(emptyList())
    val routeGeometry: StateFlow<List<GeoPoint>> = _routeGeometry.asStateFlow()
    
    // Current location
    private val _currentLocation = MutableStateFlow<GeoPoint?>(null)
    val currentLocation: StateFlow<GeoPoint?> = _currentLocation.asStateFlow()
    
    // Destination
    private val _destination = MutableStateFlow<GeoPoint?>(null)
    val destination: StateFlow<GeoPoint?> = _destination.asStateFlow()
    
    // Route steps for turn-by-turn
    private val _routeSteps = MutableStateFlow<List<RouteStep>>(emptyList())
    val routeSteps: StateFlow<List<RouteStep>> = _routeSteps.asStateFlow()
    
    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()
    
    // Routing service and location
    private val routingService = OsrmRoutingService()
    private val locationClient: FusedLocationProviderClient = 
        LocationServices.getFusedLocationProviderClient(application)
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        // Try to get initial location
        updateCurrentLocation()
    }
    
    /**
     * Update current location from GPS.
     */
    @Suppress("MissingPermission")
    fun updateCurrentLocation() {
        try {
            val cancellationToken = CancellationTokenSource()
            locationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancellationToken.token
            ).addOnSuccessListener { location ->
                location?.let {
                    _currentLocation.value = GeoPoint(it.latitude, it.longitude)
                }
            }.addOnFailureListener { e ->
                android.util.Log.w("NavigationViewModel", "Failed to get location: ${e.message}")
            }
        } catch (e: SecurityException) {
            android.util.Log.w("NavigationViewModel", "Location permission not granted: ${e.message}")
        }
    }
    
    /**
     * Start navigation to a single destination.
     */
    fun startNavigation(
        destinationName: String,
        latitude: Double,
        longitude: Double
    ) {
        startMultiStopNavigation(
            listOf(Waypoint(destinationName, latitude, longitude))
        )
    }
    
    /**
     * Start navigation with multiple stops (errands mode).
     */
    fun startMultiStopNavigation(waypoints: List<Waypoint>) {
        viewModelScope.launch {
            val origin = _currentLocation.value
            if (origin == null) {
                _errorMessage.value = "Current location not available. Enable GPS."
                return@launch
            }
            
            _currentRoute.value = NavigationRoute(
                waypoints = waypoints,
                totalDistance = "Calculating...",
                totalTime = "..."
            )
            
            // Build list of GeoPoints including current location
            val allPoints = mutableListOf(origin)
            allPoints.addAll(waypoints.map { GeoPoint(it.latitude, it.longitude) })
            
            // Set destination
            _destination.value = allPoints.last()
            
            // Fetch route from OSRM
            val result = if (allPoints.size == 2) {
                routingService.getRoute(allPoints[0], allPoints[1])
            } else {
                routingService.getMultiStopRoute(allPoints)
            }
            
            when (result) {
                is RouteResult.Success -> {
                    _routeGeometry.value = result.routeGeometry
                    _routeSteps.value = result.steps
                    _currentStepIndex.value = 0
                    
                    val totalDistanceStr = formatDistance(result.totalDistanceMeters)
                    val totalTimeStr = formatDuration(result.totalDurationSeconds)
                    val arrivalTime = calculateArrivalTime(result.totalDurationSeconds)
                    
                    _currentRoute.value = NavigationRoute(
                        waypoints = waypoints,
                        totalDistance = totalDistanceStr,
                        totalTime = totalTimeStr
                    )
                    
                    // Update navigation state with first step
                    updateNavigationStateFromStep(0, result.totalDistanceMeters, result.totalDurationSeconds)
                }
                is RouteResult.Error -> {
                    _errorMessage.value = result.message
                    _navigationState.value = NavigationState(isNavigating = false)
                }
            }
        }
    }
    
    /**
     * Update navigation state based on current step.
     */
    private fun updateNavigationStateFromStep(
        stepIndex: Int, 
        totalDistanceMeters: Double,
        totalDurationSeconds: Double
    ) {
        val steps = _routeSteps.value
        if (stepIndex >= steps.size) {
            stopNavigation()
            return
        }
        
        val currentStep = steps[stepIndex]
        val nextStep = steps.getOrNull(stepIndex + 1)
        
        // Calculate remaining distance and time from current step onwards
        val remainingSteps = steps.drop(stepIndex)
        val remainingDistance = remainingSteps.sumOf { it.distanceMeters }
        val remainingDuration = remainingSteps.sumOf { it.durationSeconds }
        
        _navigationState.value = NavigationState(
            isNavigating = true,
            currentInstruction = currentStep.instruction,
            distanceToNextTurn = formatDistance(currentStep.distanceMeters),
            distanceRemaining = formatDistance(remainingDistance),
            timeRemaining = formatDuration(remainingDuration),
            currentStreetName = currentStep.streetName,
            nextStreetName = nextStep?.streetName ?: "",
            maneuverType = currentStep.maneuverType,
            arrivalTime = calculateArrivalTime(remainingDuration)
        )
    }
    
    /**
     * Advance to the next navigation step.
     */
    fun advanceToNextStep() {
        val nextIndex = _currentStepIndex.value + 1
        val steps = _routeSteps.value
        
        if (nextIndex >= steps.size) {
            // Arrived at destination
            _navigationState.value = NavigationState(
                isNavigating = true,
                currentInstruction = "You have arrived!",
                maneuverType = ManeuverType.ARRIVE,
                distanceToNextTurn = "0 ft",
                distanceRemaining = "0 ft",
                timeRemaining = "0 min",
                arrivalTime = SimpleDateFormat("h:mm a", Locale.US).format(Date())
            )
            return
        }
        
        _currentStepIndex.value = nextIndex
        
        // Recalculate remaining totals
        val remainingSteps = steps.drop(nextIndex)
        val remainingDistance = remainingSteps.sumOf { it.distanceMeters }
        val remainingDuration = remainingSteps.sumOf { it.durationSeconds }
        
        updateNavigationStateFromStep(nextIndex, remainingDistance, remainingDuration)
    }
    
    /**
     * Stop current navigation.
     */
    fun stopNavigation() {
        _navigationState.value = NavigationState(isNavigating = false)
        _currentRoute.value = null
        _routeGeometry.value = emptyList()
        _routeSteps.value = emptyList()
        _currentStepIndex.value = 0
        _destination.value = null
    }
    
    /**
     * Skip to the next waypoint in multi-stop navigation.
     */
    fun skipToNextWaypoint() {
        val route = _currentRoute.value ?: return
        val nextIndex = route.currentWaypointIndex + 1
        
        if (nextIndex >= route.waypoints.size) {
            stopNavigation()
            return
        }
        
        _currentRoute.value = route.copy(currentWaypointIndex = nextIndex)
        
        // Recalculate route from current location to remaining waypoints
        val remainingWaypoints = route.waypoints.drop(nextIndex)
        startMultiStopNavigation(remainingWaypoints)
    }
    
    /**
     * Called when user arrives at a waypoint.
     */
    fun onWaypointArrival(waypointIndex: Int) {
        val route = _currentRoute.value ?: return
        val updatedWaypoints = route.waypoints.mapIndexed { index, waypoint ->
            if (index == waypointIndex) waypoint.copy(isVisited = true) else waypoint
        }
        _currentRoute.value = route.copy(waypoints = updatedWaypoints)
    }
    
    /**
     * Recenter map on current location.
     */
    fun recenter() {
        updateCurrentLocation()
    }
    
    /**
     * Toggle voice guidance (requires TTS integration).
     */
    fun toggleVoiceGuidance(enabled: Boolean) {
        // TODO: Integrate with DialogViewModel TTS
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
    }
    
    // ============================================================================
    // Utility Functions
    // ============================================================================
    
    private fun formatDistance(meters: Double): String {
        return if (meters >= 1609) {
            String.format("%.1f mi", meters / 1609.34)
        } else {
            String.format("%d ft", (meters * 3.28084).toInt())
        }
    }
    
    private fun formatDuration(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        return if (mins >= 60) {
            String.format("%d hr %d min", mins / 60, mins % 60)
        } else {
            "$mins min"
        }
    }
    
    private fun calculateArrivalTime(durationSeconds: Double): String {
        val arrivalMillis = System.currentTimeMillis() + (durationSeconds * 1000).toLong()
        return SimpleDateFormat("h:mm a", Locale.US).format(Date(arrivalMillis))
    }
}
