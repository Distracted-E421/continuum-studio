package com.example.continuumstudio.ui.glasses

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import com.example.continuumstudio.data.GlassesColors

/**
 * AR-optimized map overlay for glasses.
 * 
 * Design principles:
 * - Dark map style (black = transparent on AR)
 * - Green monochrome markers and routes
 * - Minimal UI - focus on navigation info
 * - High contrast text for visibility
 */
@Composable
fun GlassesMapOverlay(
    currentLocation: LatLng?,
    destination: LatLng? = null,
    routePoints: List<LatLng> = emptyList(),
    nextDirection: String? = null,
    distanceToNext: String? = null,
    eta: String? = null,
    modifier: Modifier = Modifier
) {
    val primaryColor = GlassesColors.GreenPrimary
    val dimColor = GlassesColors.GreenDim
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassesColors.Transparent)
    ) {
        // Map view (positioned in corner or as overlay)
        if (currentLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(200.dp)
                    .padding(8.dp)
                    .border(2.dp, primaryColor, RoundedCornerShape(8.dp))
            ) {
                val cameraPositionState = rememberCameraPositionState {
                    position = CameraPosition.fromLatLngZoom(currentLocation, 16f)
                }
                
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(
                        mapStyleOptions = MapStyleOptions(AR_DARK_MAP_STYLE),
                        isMyLocationEnabled = false, // We'll show custom marker
                        isBuildingEnabled = false,
                        isIndoorEnabled = false
                    ),
                    uiSettings = MapUiSettings(
                        compassEnabled = false,
                        mapToolbarEnabled = false,
                        myLocationButtonEnabled = false,
                        rotationGesturesEnabled = false,
                        scrollGesturesEnabled = false,
                        tiltGesturesEnabled = false,
                        zoomControlsEnabled = false,
                        zoomGesturesEnabled = false
                    )
                ) {
                    // Current location marker
                    Marker(
                        state = MarkerState(position = currentLocation),
                        title = "You",
                        // Use green marker for visibility
                    )
                    
                    // Destination marker if set
                    destination?.let { dest ->
                        Marker(
                            state = MarkerState(position = dest),
                            title = "Destination"
                        )
                    }
                    
                    // Route polyline
                    if (routePoints.isNotEmpty()) {
                        Polyline(
                            points = routePoints,
                            color = primaryColor,
                            width = 8f
                        )
                    }
                }
            }
        }
        
        // Navigation HUD info (top area)
        if (nextDirection != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .border(2.dp, primaryColor, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Next turn direction
                Text(
                    text = nextDirection,
                    color = primaryColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                
                // Distance to next turn
                distanceToNext?.let { distance ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = distance,
                        color = dimColor,
                        fontSize = 18.sp
                    )
                }
                
                // ETA
                eta?.let { arrivalTime ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ETA: $arrivalTime",
                        color = dimColor,
                        fontSize = 14.sp
                    )
                }
            }
        }
        
        // Compass / heading indicator (future)
        // Speed indicator (future)
    }
}

/**
 * Simplified map overlay showing just current location
 * Used when no navigation is active
 */
@Composable
fun GlassesMapMiniOverlay(
    currentLocation: LatLng,
    modifier: Modifier = Modifier
) {
    val primaryColor = GlassesColors.GreenPrimary
    
    Box(
        modifier = modifier
            .size(120.dp)
            .border(1.dp, primaryColor, RoundedCornerShape(8.dp))
    ) {
        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(currentLocation, 15f)
        }
        
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                mapStyleOptions = MapStyleOptions(AR_DARK_MAP_STYLE),
                isMyLocationEnabled = false,
                isBuildingEnabled = false,
                isIndoorEnabled = false
            ),
            uiSettings = MapUiSettings(
                compassEnabled = false,
                mapToolbarEnabled = false,
                myLocationButtonEnabled = false,
                rotationGesturesEnabled = false,
                scrollGesturesEnabled = false,
                tiltGesturesEnabled = false,
                zoomControlsEnabled = false,
                zoomGesturesEnabled = false
            )
        ) {
            Marker(
                state = MarkerState(position = currentLocation),
                title = "You"
            )
        }
    }
}

/**
 * Dark map style JSON for AR visibility.
 * Black background = transparent on additive AR display.
 * Green/cyan highlights for roads and labels.
 */
private const val AR_DARK_MAP_STYLE = """
[
  {
    "elementType": "geometry",
    "stylers": [{"color": "#000000"}]
  },
  {
    "elementType": "labels.text.fill",
    "stylers": [{"color": "#00ff00"}]
  },
  {
    "elementType": "labels.text.stroke",
    "stylers": [{"color": "#000000"}, {"weight": 2}]
  },
  {
    "featureType": "road",
    "elementType": "geometry",
    "stylers": [{"color": "#003300"}]
  },
  {
    "featureType": "road",
    "elementType": "geometry.stroke",
    "stylers": [{"color": "#00aa00"}]
  },
  {
    "featureType": "road.highway",
    "elementType": "geometry",
    "stylers": [{"color": "#005500"}]
  },
  {
    "featureType": "road.highway",
    "elementType": "geometry.stroke",
    "stylers": [{"color": "#00ff00"}]
  },
  {
    "featureType": "water",
    "elementType": "geometry",
    "stylers": [{"color": "#001122"}]
  },
  {
    "featureType": "poi",
    "stylers": [{"visibility": "off"}]
  },
  {
    "featureType": "transit",
    "stylers": [{"visibility": "off"}]
  },
  {
    "featureType": "administrative",
    "elementType": "geometry",
    "stylers": [{"visibility": "off"}]
  }
]
"""
