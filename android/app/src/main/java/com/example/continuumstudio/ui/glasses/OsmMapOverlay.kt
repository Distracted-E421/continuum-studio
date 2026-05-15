package com.example.continuumstudio.ui.glasses

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.continuumstudio.data.GlassesColors
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * AR-optimized map overlay using OSMDroid (OpenStreetMap).
 * 
 * Advantages over Google Maps:
 * - Free, no API key required
 * - Offline map support
 * - Open source, can customize tile sources
 * - Lighter weight
 * 
 * Design principles:
 * - Dark map tiles for AR (black = transparent)
 * - Green monochrome route overlay
 * - Minimal UI - focus on navigation info
 */
@Composable
fun OsmMapOverlay(
    currentLocation: GeoPoint?,
    destination: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    nextDirection: String? = null,
    distanceToNext: String? = null,
    eta: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val primaryColor = GlassesColors.GreenPrimary
    val dimColor = GlassesColors.GreenDim
    
    // Initialize OSMDroid configuration
    LaunchedEffect(Unit) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            // Set tile cache location
            osmdroidBasePath = context.getExternalFilesDir(null)
            osmdroidTileCache = context.getExternalFilesDir("osm_tiles")
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassesColors.Transparent)
    ) {
        // Map view (positioned in corner)
        if (currentLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(200.dp)
                    .padding(8.dp)
                    .border(2.dp, primaryColor, RoundedCornerShape(8.dp))
            ) {
                OsmMapViewComposable(
                    currentLocation = currentLocation,
                    destination = destination,
                    routePoints = routePoints,
                    modifier = Modifier.fillMaxSize()
                )
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
                Text(
                    text = nextDirection,
                    color = primaryColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                
                distanceToNext?.let { distance ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = distance,
                        color = dimColor,
                        fontSize = 18.sp
                    )
                }
                
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
    }
}

/**
 * Mini map overlay showing just current location.
 */
@Composable
fun OsmMapMiniOverlay(
    currentLocation: GeoPoint,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val primaryColor = GlassesColors.GreenPrimary
    
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }
    
    Box(
        modifier = modifier
            .size(120.dp)
            .border(1.dp, primaryColor, RoundedCornerShape(8.dp))
    ) {
        OsmMapViewComposable(
            currentLocation = currentLocation,
            zoomLevel = 15.0,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Dark tile source for AR visibility.
 * Uses CartoDB Dark Matter tiles (free, no API key).
 */
private val DARK_TILE_SOURCE = object : OnlineTileSourceBase(
    "CartoDB-Dark",
    0, 19, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        return "${baseUrl}$zoom/$x/$y.png"
    }
}

/**
 * Composable wrapper for OSMDroid MapView.
 */
@Composable
private fun OsmMapViewComposable(
    currentLocation: GeoPoint,
    destination: GeoPoint? = null,
    routePoints: List<GeoPoint> = emptyList(),
    zoomLevel: Double = 16.0,
    modifier: Modifier = Modifier
) {
    val greenColor = AndroidColor.parseColor("#00FF00")
    val darkGreen = AndroidColor.parseColor("#003300")
    
    AndroidView(
        factory = { context ->
            MapView(context).apply {
                // Use dark tiles for AR visibility (black = transparent)
                setTileSource(DARK_TILE_SOURCE)
                
                // Disable all interactions (read-only display)
                setMultiTouchControls(false)
                setBuiltInZoomControls(false)
                
                // Set initial position
                controller.setZoom(zoomLevel)
                controller.setCenter(currentLocation)
                
                // Add current location marker
                val locationMarker = Marker(this).apply {
                    position = currentLocation
                    title = "You"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                overlays.add(locationMarker)
            }
        },
        update = { mapView ->
            // Update center position
            mapView.controller.setCenter(currentLocation)
            
            // Clear existing overlays
            mapView.overlays.clear()
            
            // Add current location marker
            val locationMarker = Marker(mapView).apply {
                position = currentLocation
                title = "You"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            }
            mapView.overlays.add(locationMarker)
            
            // Add destination marker if set
            destination?.let { dest ->
                val destMarker = Marker(mapView).apply {
                    position = dest
                    title = "Destination"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(destMarker)
            }
            
            // Add route polyline
            if (routePoints.isNotEmpty()) {
                val routeLine = Polyline().apply {
                    setPoints(routePoints)
                    outlinePaint.color = greenColor
                    outlinePaint.strokeWidth = 8f
                }
                mapView.overlays.add(routeLine)
            }
            
            mapView.invalidate()
        },
        modifier = modifier
    )
}
