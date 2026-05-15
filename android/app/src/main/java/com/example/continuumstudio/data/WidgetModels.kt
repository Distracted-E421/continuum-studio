package com.example.continuumstudio.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Widget Bay Configuration - defines the dashboard layout
 */
@Serializable
data class BayConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default Dashboard",
    val columns: Int = 2,
    val widgets: List<WidgetConfig> = emptyList()
)

/**
 * Individual widget configuration
 */
@Serializable
data class WidgetConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val span: Int = 1,  // How many columns to span (1 or 2)
    val order: Int = 0,
    val settings: Map<String, String> = emptyMap()
)

/**
 * Available widget types
 */
@Serializable
enum class WidgetType(val title: String, val description: String, val defaultSpan: Int, val emoji: String) {
    SERVER_STATUS("Server Status", "Connection status with ping times", 1, "🟢"),
    RUNNING_AGENTS("Running Agents", "Active CLI agents", 1, "🤖"),
    DIALOG_BADGE("Active Dialog", "Current dialog with quick response", 2, "💬"),
    ACTIVITY_PREVIEW("Activity Stream", "Recent activity events", 2, "📰"),
    MODE_SELECTOR("Orchestrator Mode", "Mode selector & status", 1, "🎯"),
    NETWORK_STATS("Network Stats", "Real-time network status", 1, "📡"),
    NETWORK_HISTORY("Network History", "Historical latency graph", 2, "📈"),
    QUEUE_STATUS("Queue Status", "Offline queue & parked agents", 1, "⏳"),
    QUICK_ACTIONS("Quick Actions", "Shortcut buttons", 1, "⚡");
    
    companion object {
        fun defaults(): List<WidgetConfig> = listOf(
            WidgetConfig(type = SERVER_STATUS, span = 1, order = 0),
            WidgetConfig(type = RUNNING_AGENTS, span = 1, order = 1),
            WidgetConfig(type = MODE_SELECTOR, span = 1, order = 2),
            WidgetConfig(type = NETWORK_STATS, span = 1, order = 3),
            WidgetConfig(type = DIALOG_BADGE, span = 2, order = 4),
            WidgetConfig(type = ACTIVITY_PREVIEW, span = 2, order = 5),
            WidgetConfig(type = NETWORK_HISTORY, span = 2, order = 6),
        )
    }
}

/**
 * Harness information from Synapsix
 */
data class HarnessInfo(
    val id: String,
    val name: String,
    val type: String,
    val status: String,
    val pid: Int?,
    val windowId: String?,
    val startedAt: Long?
)

/**
 * Service information from DNS-SD registry
 */
data class ServiceInfo(
    val id: String,
    val displayName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
    val health: String,
    val metadata: Map<String, String>
)

/**
 * Node health information
 */
data class NodeInfo(
    val name: String,
    val isConnected: Boolean,
    val services: Int,
    val uptime: Long?
)

