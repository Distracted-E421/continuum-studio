package com.example.continuumstudio.navigation

/**
 * Represents the current state of turn-by-turn navigation.
 * This is the data structure that will be displayed on the glasses.
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    val currentInstruction: String = "",
    val distanceToNextTurn: String = "",
    val distanceRemaining: String = "",
    val timeRemaining: String = "",
    val currentStreetName: String = "",
    val nextStreetName: String = "",
    val maneuverType: ManeuverType = ManeuverType.NONE,
    val laneGuidance: List<LaneInfo> = emptyList(),
    val arrivalTime: String = "",
    val isRerouting: Boolean = false,
    val speedLimit: Int? = null,
    val currentSpeed: Int? = null
)

enum class ManeuverType {
    NONE,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    SLIGHT_LEFT,
    SLIGHT_RIGHT,
    SHARP_LEFT,
    SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT,
    ROUNDABOUT,
    EXIT,
    ARRIVE,
    DEPART
}

data class LaneInfo(
    val direction: LaneDirection,
    val isActive: Boolean
)

enum class LaneDirection {
    LEFT,
    SLIGHT_LEFT,
    STRAIGHT,
    SLIGHT_RIGHT,
    RIGHT,
    U_TURN
}

/**
 * Represents a navigation waypoint (stop on the route).
 */
data class Waypoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isVisited: Boolean = false
)

/**
 * Navigation route with multiple stops.
 */
data class NavigationRoute(
    val waypoints: List<Waypoint>,
    val totalDistance: String,
    val totalTime: String,
    val currentWaypointIndex: Int = 0
)
