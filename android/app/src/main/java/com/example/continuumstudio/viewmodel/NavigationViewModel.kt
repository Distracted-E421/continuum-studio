package com.example.continuumstudio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.navigation.ManeuverType
import com.example.continuumstudio.navigation.NavigationRoute
import com.example.continuumstudio.navigation.NavigationState
import com.example.continuumstudio.navigation.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing turn-by-turn navigation.
 * 
 * Currently a stub - will integrate with Mapbox Navigation SDK when token is configured.
 * 
 * To enable Mapbox:
 * 1. Create account at https://account.mapbox.com/
 * 2. Get MAPBOX_DOWNLOADS_TOKEN (secret) and MAPBOX_ACCESS_TOKEN (public)
 * 3. Add to gradle.properties:
 *    MAPBOX_DOWNLOADS_TOKEN=sk.xxx
 *    MAPBOX_ACCESS_TOKEN=pk.xxx
 * 4. Uncomment Mapbox dependencies in libs.versions.toml
 * 5. Uncomment integration code below
 */
class NavigationViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()
    
    private val _currentRoute = MutableStateFlow<NavigationRoute?>(null)
    val currentRoute: StateFlow<NavigationRoute?> = _currentRoute.asStateFlow()
    
    private val _isMapboxConfigured = MutableStateFlow(false)
    val isMapboxConfigured: StateFlow<Boolean> = _isMapboxConfigured.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    init {
        checkMapboxConfiguration()
    }
    
    private fun checkMapboxConfiguration() {
        // TODO: Check if Mapbox is properly configured
        // For now, always returns false until SDK is integrated
        _isMapboxConfigured.value = false
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
        if (!_isMapboxConfigured.value) {
            _errorMessage.value = "Mapbox not configured. Add token to gradle.properties."
            
            // Demo mode - show sample navigation state
            viewModelScope.launch {
                _currentRoute.value = NavigationRoute(
                    waypoints = waypoints,
                    totalDistance = "12.3 mi",
                    totalTime = "25 min"
                )
                
                // Show demo state
                _navigationState.value = NavigationState(
                    isNavigating = true,
                    currentInstruction = "Turn left",
                    distanceToNextTurn = "500 ft",
                    distanceRemaining = "12.3 mi",
                    timeRemaining = "25 min",
                    currentStreetName = "Main Street",
                    nextStreetName = "Oak Avenue",
                    maneuverType = ManeuverType.TURN_LEFT,
                    arrivalTime = "5:30 PM"
                )
            }
            return
        }
        
        // TODO: Integrate with Mapbox Navigation SDK
        // 1. Initialize MapboxNavigation if not done
        // 2. Build route request with waypoints
        // 3. Fetch routes from Directions API
        // 4. Set routes and start navigation
        // 5. Register RouteProgressObserver to update _navigationState
    }
    
    /**
     * Stop current navigation.
     */
    fun stopNavigation() {
        _navigationState.value = NavigationState(isNavigating = false)
        _currentRoute.value = null
        
        // TODO: Stop Mapbox navigation
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
        
        // TODO: Recalculate route from current location to remaining waypoints
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
        // TODO: Mapbox camera recenter
    }
    
    /**
     * Toggle voice guidance.
     */
    fun toggleVoiceGuidance(enabled: Boolean) {
        // TODO: Enable/disable Mapbox voice instructions
    }
    
    fun clearError() {
        _errorMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        // TODO: Clean up Mapbox resources
    }
    
    // ============================================================================
    // Mapbox Integration (Uncomment when SDK is added)
    // ============================================================================
    
    /*
    private lateinit var mapboxNavigation: MapboxNavigation
    
    private fun initializeMapbox() {
        if (::mapboxNavigation.isInitialized) return
        
        val navigationOptions = NavigationOptions.Builder(getApplication())
            .accessToken(getMapboxAccessToken())
            .build()
        
        mapboxNavigation = MapboxNavigationProvider.create(navigationOptions)
        
        // Register observers
        mapboxNavigation.registerRouteProgressObserver(routeProgressObserver)
        mapboxNavigation.registerVoiceInstructionsObserver(voiceInstructionsObserver)
        
        _isMapboxConfigured.value = true
    }
    
    private val routeProgressObserver = RouteProgressObserver { routeProgress ->
        val currentLeg = routeProgress.currentLegProgress
        val currentStep = currentLeg?.currentStepProgress
        val upcomingStep = currentLeg?.upcomingStep
        
        _navigationState.value = NavigationState(
            isNavigating = true,
            currentInstruction = currentStep?.step?.maneuver()?.instruction() ?: "",
            distanceToNextTurn = formatDistance(currentStep?.distanceRemaining ?: 0.0),
            distanceRemaining = formatDistance(routeProgress.distanceRemaining),
            timeRemaining = formatDuration(routeProgress.durationRemaining),
            currentStreetName = currentStep?.step?.name() ?: "",
            nextStreetName = upcomingStep?.name() ?: "",
            maneuverType = mapManeuverType(currentStep?.step?.maneuver()?.type()),
            arrivalTime = calculateArrivalTime(routeProgress.durationRemaining),
            isRerouting = routeProgress.currentState == RouteProgressState.ROUTE_INVALID
        )
    }
    
    private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
        // Use AudioFocusHelper to speak instructions
        // audioFocusHelper.speakOverlay(voiceInstructions.announcement())
    }
    
    private fun mapManeuverType(type: String?): ManeuverType {
        return when (type) {
            "turn", "turn left" -> ManeuverType.TURN_LEFT
            "turn right" -> ManeuverType.TURN_RIGHT
            "straight", "continue" -> ManeuverType.STRAIGHT
            "arrive" -> ManeuverType.ARRIVE
            "roundabout" -> ManeuverType.ROUNDABOUT
            // ... map other types
            else -> ManeuverType.NONE
        }
    }
    
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
    
    private fun getMapboxAccessToken(): String {
        // Read from BuildConfig or gradle.properties
        return ""  // BuildConfig.MAPBOX_ACCESS_TOKEN
    }
    */
}
