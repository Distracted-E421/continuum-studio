package com.example.continuumstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Activity Feed data models for real-time agent activity monitoring.
 * Events come from Synapsix dialog daemon WebSocket at /ws/activity
 */

@Serializable
data class ActivityPayload(
    val event: String,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("dialog_id") val dialogId: String? = null,
    val title: String? = null,
    val selection: String? = null,
    val command: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    val timestamp: String? = null
)

sealed class ActivityEvent {
    abstract val timestamp: String
    abstract val agentId: String
    
    data class Command(
        override val agentId: String,
        val command: String,
        val exitCode: Int?,
        val durationMs: Long?,
        override val timestamp: String
    ) : ActivityEvent()
    
    data class DialogSent(
        override val agentId: String,
        val dialogId: String,
        val title: String,
        override val timestamp: String
    ) : ActivityEvent()
    
    data class DialogResponse(
        val dialogId: String,
        val selection: String,
        override val timestamp: String
    ) : ActivityEvent() {
        override val agentId: String = "user"
    }
    
    data class ToolCall(
        override val agentId: String,
        val toolName: String,
        val status: String, // "started" | "completed"
        override val timestamp: String
    ) : ActivityEvent()
    
    data class FileEdit(
        override val agentId: String,
        val filePath: String,
        val action: String, // "read" | "write" | "delete"
        override val timestamp: String
    ) : ActivityEvent()
}

// === UI State ===

data class ActivityFeedUiState(
    val isConnected: Boolean = false,
    val events: List<ActivityEvent> = emptyList(),
    val filters: Set<ActivityFilter> = ActivityFilter.entries.toSet(),
    val expandedEventId: String? = null
)

enum class ActivityFilter(val displayName: String) {
    COMMAND("Commands"),
    DIALOG("Dialogs"),
    TOOL_CALL("Tool Calls"),
    FILE_EDIT("File Edits")
}

// === Parked Agents ===

@Serializable
data class ParkedAgentJson(
    @SerialName("agent_id") val agentId: String,
    val workspace: String,
    @SerialName("parked_at") val parkedAt: Long, // Unix timestamp
    val capabilities: List<String> = emptyList(),
    @SerialName("last_heartbeat") val lastHeartbeat: Long? = null
)

data class ParkedAgent(
    val agentId: String,
    val workspace: String,
    val parkedAt: Long,
    val capabilities: List<String>,
    val lastHeartbeat: Long
) {
    val displayWorkspace: String
        get() = workspace.substringAfterLast("/")
    
    val capabilitiesDisplay: String
        get() = capabilities.joinToString(", ") { it.replaceFirstChar { c -> c.uppercase() } }
}

@Serializable
data class ParkedAgentsResponse(
    val agents: List<ParkedAgentJson> = emptyList()
)

data class ParkedAgentsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val agents: List<ParkedAgent> = emptyList(),
    val assigningAgentId: String? = null,
    val taskDescription: String = ""
)
