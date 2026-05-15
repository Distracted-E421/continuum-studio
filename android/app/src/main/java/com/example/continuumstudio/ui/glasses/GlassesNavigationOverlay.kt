package com.example.continuumstudio.ui.glasses

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.continuumstudio.data.GlassesColors
import com.example.continuumstudio.navigation.ManeuverType
import com.example.continuumstudio.navigation.NavigationState

/**
 * Full navigation overlay for glasses display.
 * Shows turn instruction, distance, ETA, and maneuver arrow.
 * 
 * Design: Green monochrome on black (transparent on AR glasses)
 */
@Composable
fun GlassesNavigationOverlay(
    state: NavigationState,
    modifier: Modifier = Modifier
) {
    val primaryColor = GlassesColors.GreenPrimary
    val dimColor = GlassesColors.GreenDim
    
    if (!state.isNavigating) return
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, primaryColor, RoundedCornerShape(12.dp))
            .background(GlassesColors.Transparent, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Maneuver Icon
            ManeuverIcon(
                type = state.maneuverType,
                color = primaryColor,
                modifier = Modifier.size(64.dp)
            )
            
            // Turn Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Distance to turn
                Text(
                    text = state.distanceToNextTurn,
                    color = primaryColor,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                
                // Instruction
                Text(
                    text = state.currentInstruction,
                    color = primaryColor,
                    fontSize = 18.sp
                )
                
                // Next street
                if (state.nextStreetName.isNotEmpty()) {
                    Text(
                        text = state.nextStreetName,
                        color = dimColor,
                        fontSize = 14.sp
                    )
                }
            }
            
            // ETA Column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = state.timeRemaining,
                    color = primaryColor,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.arrivalTime,
                    color = dimColor,
                    fontSize = 14.sp
                )
                if (state.distanceRemaining.isNotEmpty()) {
                    Text(
                        text = state.distanceRemaining,
                        color = dimColor,
                        fontSize = 12.sp
                    )
                }
            }
        }
        
        // Rerouting indicator
        if (state.isRerouting) {
            Text(
                text = "Rerouting...",
                color = Color(0xFFFFD700), // Gold/yellow for attention
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(top = 8.dp)
            )
        }
    }
}

/**
 * Minimal navigation overlay for status bar.
 * Shows just the next turn and distance.
 */
@Composable
fun GlassesNavigationMini(
    state: NavigationState,
    modifier: Modifier = Modifier
) {
    val primaryColor = GlassesColors.GreenPrimary
    
    if (!state.isNavigating) return
    
    Row(
        modifier = modifier
            .border(1.dp, primaryColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ManeuverIcon(
            type = state.maneuverType,
            color = primaryColor,
            modifier = Modifier.size(24.dp)
        )
        
        Text(
            text = state.distanceToNextTurn,
            color = primaryColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = "\u2022",  // bullet
            color = primaryColor,
            fontSize = 12.sp
        )
        
        Text(
            text = state.timeRemaining,
            color = primaryColor,
            fontSize = 14.sp
        )
    }
}

/**
 * Draw the turn maneuver icon using Canvas.
 * Simple arrow-based icons for AR visibility.
 */
@Composable
private fun ManeuverIcon(
    type: ManeuverType,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension / 10f
        val stroke = Stroke(
            width = strokeWidth,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
        
        val centerX = size.width / 2
        val centerY = size.height / 2
        val arrowSize = size.minDimension * 0.35f
        
        when (type) {
            ManeuverType.STRAIGHT -> {
                // Arrow pointing up
                val path = Path().apply {
                    moveTo(centerX, size.height * 0.2f)
                    lineTo(centerX, size.height * 0.8f)
                }
                drawPath(path, color, style = stroke)
                // Arrowhead
                val headPath = Path().apply {
                    moveTo(centerX - arrowSize * 0.5f, size.height * 0.35f)
                    lineTo(centerX, size.height * 0.2f)
                    lineTo(centerX + arrowSize * 0.5f, size.height * 0.35f)
                }
                drawPath(headPath, color, style = stroke)
            }
            
            ManeuverType.TURN_LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT -> {
                // Left turn arrow
                val path = Path().apply {
                    moveTo(size.width * 0.7f, size.height * 0.8f)
                    lineTo(size.width * 0.7f, size.height * 0.4f)
                    lineTo(size.width * 0.3f, size.height * 0.4f)
                }
                drawPath(path, color, style = stroke)
                // Arrowhead
                val headPath = Path().apply {
                    moveTo(size.width * 0.45f, size.height * 0.25f)
                    lineTo(size.width * 0.3f, size.height * 0.4f)
                    lineTo(size.width * 0.45f, size.height * 0.55f)
                }
                drawPath(headPath, color, style = stroke)
            }
            
            ManeuverType.TURN_RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT -> {
                // Right turn arrow
                val path = Path().apply {
                    moveTo(size.width * 0.3f, size.height * 0.8f)
                    lineTo(size.width * 0.3f, size.height * 0.4f)
                    lineTo(size.width * 0.7f, size.height * 0.4f)
                }
                drawPath(path, color, style = stroke)
                // Arrowhead
                val headPath = Path().apply {
                    moveTo(size.width * 0.55f, size.height * 0.25f)
                    lineTo(size.width * 0.7f, size.height * 0.4f)
                    lineTo(size.width * 0.55f, size.height * 0.55f)
                }
                drawPath(headPath, color, style = stroke)
            }
            
            ManeuverType.U_TURN_LEFT, ManeuverType.U_TURN_RIGHT -> {
                // U-turn
                val path = Path().apply {
                    moveTo(size.width * 0.6f, size.height * 0.8f)
                    lineTo(size.width * 0.6f, size.height * 0.4f)
                    quadraticTo(
                        size.width * 0.6f, size.height * 0.2f,
                        size.width * 0.4f, size.height * 0.2f
                    )
                    quadraticTo(
                        size.width * 0.2f, size.height * 0.2f,
                        size.width * 0.2f, size.height * 0.4f
                    )
                    lineTo(size.width * 0.2f, size.height * 0.6f)
                }
                drawPath(path, color, style = stroke)
            }
            
            ManeuverType.ARRIVE -> {
                // Destination marker (circle with dot)
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.3f,
                    center = Offset(centerX, centerY),
                    style = stroke
                )
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.1f,
                    center = Offset(centerX, centerY)
                )
            }
            
            ManeuverType.ROUNDABOUT -> {
                // Roundabout (circle with arrow)
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.25f,
                    center = Offset(centerX, centerY),
                    style = stroke
                )
                val arrowPath = Path().apply {
                    moveTo(centerX + size.minDimension * 0.15f, size.height * 0.2f)
                    lineTo(centerX + size.minDimension * 0.3f, size.height * 0.35f)
                    lineTo(centerX + size.minDimension * 0.15f, size.height * 0.5f)
                }
                drawPath(arrowPath, color, style = stroke)
            }
            
            else -> {
                // Default: just draw a dot
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.15f,
                    center = Offset(centerX, centerY)
                )
            }
        }
    }
}
