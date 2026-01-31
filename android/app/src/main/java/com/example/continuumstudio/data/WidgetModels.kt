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
enum class WidgetType(val title: String, val description: String, val defaultSpan: Int) {
    DIALOG_QUEUE("Dialog Queue", "Pending AI dialogs", 1),
    HARNESS_STATUS("Harnesses", "Active Synapsix harnesses", 1),
    SERVICE_DISCOVERY("Services", "DNS-SD discovered services", 2),
    NODE_HEALTH("Node Health", "BEAM cluster status", 1),
    QUICK_ACTIONS("Quick Actions", "Shortcut buttons", 1),
    CONNECTION_STATUS("Connection", "Server connection status", 1),
    AGENT_STREAM("Agent Stream", "Live AI conversation", 2);
    
    companion object {
        fun defaults(): List<WidgetConfig> = listOf(
            WidgetConfig(type = CONNECTION_STATUS, span = 1, order = 0),
            WidgetConfig(type = DIALOG_QUEUE, span = 1, order = 1),
            WidgetConfig(type = HARNESS_STATUS, span = 2, order = 2),
            WidgetConfig(type = SERVICE_DISCOVERY, span = 2, order = 3),
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

