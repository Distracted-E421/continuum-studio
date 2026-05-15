package com.example.continuumstudio.navigation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * OSRM (Open Source Routing Machine) service for turn-by-turn directions.
 * 
 * Uses the public demo server by default.
 * For production, consider self-hosting OSRM or using a paid service.
 * 
 * Documentation: https://project-osrm.org/docs/v5.24.0/api/
 */
class OsrmRoutingService(
    private val baseUrl: String = DEMO_SERVER
) {
    companion object {
        const val DEMO_SERVER = "https://router.project-osrm.org"
        private val json = Json { 
            ignoreUnknownKeys = true 
            isLenient = true
        }
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Get route between two points with turn-by-turn directions.
     */
    suspend fun getRoute(
        origin: GeoPoint,
        destination: GeoPoint,
        profile: String = "driving"
    ): RouteResult = withContext(Dispatchers.IO) {
        val url = buildRouteUrl(origin, destination, profile)
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ContinuumStudio/1.0")
            .get()
            .build()
        
        try {
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext RouteResult.Error("HTTP ${response.code}: ${response.message}")
            }
            
            val body = response.body?.string() ?: return@withContext RouteResult.Error("Empty response")
            val osrmResponse = json.decodeFromString<OsrmResponse>(body)
            
            if (osrmResponse.code != "Ok" || osrmResponse.routes.isEmpty()) {
                return@withContext RouteResult.Error("No route found: ${osrmResponse.code}")
            }
            
            val route = osrmResponse.routes.first()
            
            RouteResult.Success(
                routeGeometry = decodePolyline(route.geometry),
                totalDistanceMeters = route.distance,
                totalDurationSeconds = route.duration,
                steps = route.legs.flatMap { leg ->
                    leg.steps.map { step ->
                        RouteStep(
                            instruction = step.maneuver.instruction ?: buildInstruction(step),
                            distanceMeters = step.distance,
                            durationSeconds = step.duration,
                            maneuverType = mapManeuverType(step.maneuver.type, step.maneuver.modifier),
                            streetName = step.name,
                            startLocation = GeoPoint(
                                step.maneuver.location[1],
                                step.maneuver.location[0]
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            RouteResult.Error("Routing failed: ${e.message}")
        }
    }
    
    /**
     * Get route with multiple waypoints (errands mode).
     */
    suspend fun getMultiStopRoute(
        waypoints: List<GeoPoint>,
        profile: String = "driving"
    ): RouteResult = withContext(Dispatchers.IO) {
        if (waypoints.size < 2) {
            return@withContext RouteResult.Error("Need at least 2 waypoints")
        }
        
        val coordinates = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "$baseUrl/route/v1/$profile/$coordinates" +
                "?overview=full&geometries=polyline&steps=true&annotations=false"
        
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ContinuumStudio/1.0")
            .get()
            .build()
        
        try {
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext RouteResult.Error("HTTP ${response.code}")
            }
            
            val body = response.body?.string() ?: return@withContext RouteResult.Error("Empty response")
            val osrmResponse = json.decodeFromString<OsrmResponse>(body)
            
            if (osrmResponse.code != "Ok" || osrmResponse.routes.isEmpty()) {
                return@withContext RouteResult.Error("No route found")
            }
            
            val route = osrmResponse.routes.first()
            
            RouteResult.Success(
                routeGeometry = decodePolyline(route.geometry),
                totalDistanceMeters = route.distance,
                totalDurationSeconds = route.duration,
                steps = route.legs.flatMap { leg ->
                    leg.steps.map { step ->
                        RouteStep(
                            instruction = step.maneuver.instruction ?: buildInstruction(step),
                            distanceMeters = step.distance,
                            durationSeconds = step.duration,
                            maneuverType = mapManeuverType(step.maneuver.type, step.maneuver.modifier),
                            streetName = step.name,
                            startLocation = GeoPoint(
                                step.maneuver.location[1],
                                step.maneuver.location[0]
                            )
                        )
                    }
                }
            )
        } catch (e: Exception) {
            RouteResult.Error("Routing failed: ${e.message}")
        }
    }
    
    private fun buildRouteUrl(origin: GeoPoint, destination: GeoPoint, profile: String): String {
        return "$baseUrl/route/v1/$profile/" +
                "${origin.longitude},${origin.latitude};" +
                "${destination.longitude},${destination.latitude}" +
                "?overview=full&geometries=polyline&steps=true&annotations=false"
    }
    
    private fun buildInstruction(step: OsrmStep): String {
        val maneuver = step.maneuver
        val streetPart = if (step.name.isNotBlank()) " onto ${step.name}" else ""
        
        return when (maneuver.type) {
            "turn" -> "Turn ${maneuver.modifier ?: ""}$streetPart"
            "new name" -> "Continue$streetPart"
            "depart" -> "Head ${maneuver.modifier ?: "north"}$streetPart"
            "arrive" -> "Arrive at destination"
            "merge" -> "Merge ${maneuver.modifier ?: ""}$streetPart"
            "on ramp" -> "Take the ramp$streetPart"
            "off ramp" -> "Take exit$streetPart"
            "fork" -> "Keep ${maneuver.modifier ?: "straight"}$streetPart"
            "end of road" -> "Turn ${maneuver.modifier ?: ""}$streetPart"
            "continue" -> "Continue$streetPart"
            "roundabout" -> "At roundabout, take exit$streetPart"
            else -> "Continue$streetPart"
        }.trim()
    }
    
    private fun mapManeuverType(type: String?, modifier: String?): ManeuverType {
        return when (type) {
            "turn" -> when (modifier) {
                "left" -> ManeuverType.TURN_LEFT
                "right" -> ManeuverType.TURN_RIGHT
                "slight left" -> ManeuverType.SLIGHT_LEFT
                "slight right" -> ManeuverType.SLIGHT_RIGHT
                "sharp left" -> ManeuverType.SHARP_LEFT
                "sharp right" -> ManeuverType.SHARP_RIGHT
                "uturn" -> ManeuverType.U_TURN_LEFT
                else -> ManeuverType.STRAIGHT
            }
            "new name", "continue" -> ManeuverType.STRAIGHT
            "depart" -> ManeuverType.DEPART
            "arrive" -> ManeuverType.ARRIVE
            "merge" -> when (modifier) {
                "left", "slight left" -> ManeuverType.MERGE_LEFT
                "right", "slight right" -> ManeuverType.MERGE_RIGHT
                else -> ManeuverType.STRAIGHT
            }
            "roundabout", "rotary" -> ManeuverType.ROUNDABOUT
            "exit roundabout", "exit rotary" -> ManeuverType.EXIT
            else -> ManeuverType.NONE
        }
    }
    
    /**
     * Decode Google-style polyline encoding to list of GeoPoints.
     * OSRM uses polyline encoding for route geometry.
     */
    private fun decodePolyline(encoded: String): List<GeoPoint> {
        val poly = mutableListOf<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat
            
            shift = 0
            result = 0
            
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng
            
            poly.add(GeoPoint(lat / 1E5, lng / 1E5))
        }
        
        return poly
    }
}

// OSRM API Response Models

@Serializable
data class OsrmResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList(),
    val waypoints: List<OsrmWaypoint> = emptyList()
)

@Serializable
data class OsrmRoute(
    val geometry: String,
    val distance: Double,
    val duration: Double,
    val legs: List<OsrmLeg>
)

@Serializable
data class OsrmLeg(
    val distance: Double,
    val duration: Double,
    val steps: List<OsrmStep>
)

@Serializable
data class OsrmStep(
    val geometry: String,
    val distance: Double,
    val duration: Double,
    val name: String,
    val maneuver: OsrmManeuver
)

@Serializable
data class OsrmManeuver(
    val type: String,
    val modifier: String? = null,
    val instruction: String? = null,
    val location: List<Double>,
    @SerialName("bearing_before") val bearingBefore: Int = 0,
    @SerialName("bearing_after") val bearingAfter: Int = 0
)

@Serializable
data class OsrmWaypoint(
    val name: String,
    val location: List<Double>,
    val distance: Double = 0.0
)

// Result wrapper

sealed class RouteResult {
    data class Success(
        val routeGeometry: List<GeoPoint>,
        val totalDistanceMeters: Double,
        val totalDurationSeconds: Double,
        val steps: List<RouteStep>
    ) : RouteResult()
    
    data class Error(val message: String) : RouteResult()
}

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: ManeuverType,
    val streetName: String,
    val startLocation: GeoPoint
)
