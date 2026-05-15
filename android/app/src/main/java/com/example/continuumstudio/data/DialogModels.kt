package com.example.continuumstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Dialog data models matching the desktop daemon's WebSocket API
 */

// === Incoming Messages from Server ===

@Serializable
sealed class ServerMessage {
    @Serializable
    @SerialName("initial")
    data class Initial(
        @SerialName("has_active") val hasActive: Boolean,
        @SerialName("active_id") val activeId: String? = null,
        @SerialName("queue_count") val queueCount: Int,
        @SerialName("hold_mode") val holdMode: Boolean,
    ) : ServerMessage()

    @Serializable
    @SerialName("NewDialog")
    data class NewDialog(
        val id: String,
        val title: String,
        val prompt: String,
        @SerialName("dialog_type") val dialogType: String,
        @SerialName("timeout_ms") val timeoutMs: Int? = null,
    ) : ServerMessage()

    @Serializable
    @SerialName("DialogCompleted")
    data class DialogCompleted(
        val id: String,
        val selection: JsonElement,
        val cancelled: Boolean,
    ) : ServerMessage()

    @Serializable
    @SerialName("QueueUpdate")
    data class QueueUpdate(
        @SerialName("active_id") val activeId: String?,
        @SerialName("queue_count") val queueCount: Int,
    ) : ServerMessage()

    @Serializable
    @SerialName("HoldModeChanged")
    data class HoldModeChanged(
        val enabled: Boolean,
    ) : ServerMessage()
}

// === API Responses ===

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
)

@Serializable
data class StatusResponse(
    @SerialName("has_active") val hasActive: Boolean,
    @SerialName("queue_length") val queueLength: Int,
    @SerialName("hold_mode") val holdMode: Boolean,
    @SerialName("toast_count") val toastCount: Int,
)

@Serializable
data class DialogDetails(
    val id: String,
    val title: String,
    val prompt: String,
    @SerialName("dialog_type") val dialogType: DialogTypeInfo,
    @SerialName("timeout_ms") val timeoutMs: Int? = null,
    @SerialName("time_remaining_ratio") val timeRemainingRatio: Float = 1.0f,
    @SerialName("is_paused") val isPaused: Boolean = false,
    val context: JsonElement? = null,
)

@Serializable
data class DialogTypeInfo(
    val type: String,
    val options: List<ChoiceOption>? = null,
    @SerialName("allow_multiple") val allowMultiple: Boolean? = null,
    val placeholder: String? = null,
    val multiline: Boolean? = null,
    @SerialName("yes_label") val yesLabel: String? = null,
    @SerialName("no_label") val noLabel: String? = null,
    val min: Float? = null,
    val max: Float? = null,
    val step: Float? = null,
    val unit: String? = null,
)

@Serializable
data class ChoiceOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

@Serializable
data class QueueItem(
    val index: Int,
    val id: String,
    val title: String,
    val type: String,
)

/**
 * History item from /api/history endpoint
 */
@Serializable
data class HistoryItem(
    val id: String,
    val title: String,
    val prompt: String,
    @SerialName("dialog_type") val dialogType: String,
    val selection: JsonElement? = null,
    val comment: String? = null,
    val cancelled: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val timeout: Int? = null,
    val options: List<ChoiceOption>? = null,
)

// === Outgoing Messages to Server ===

@Serializable
data class DialogAnswerRequest(
    val id: String,
    val selection: JsonElement,
    val comment: String? = null,
)

// === UI State ===

/**
 * Represents a server endpoint configuration
 */
@Serializable
data class ServerEndpoint(
    val name: String,
    val url: String,
    val type: EndpointType = EndpointType.REMOTE,
    val enabled: Boolean = true,
    val cfAccessClientId: String = "",
    val cfAccessClientSecret: String = "",
)

@Serializable
enum class EndpointType {
    @SerialName("local") LOCAL,
    @SerialName("tailscale") TAILSCALE,
    @SerialName("cloudflare") CLOUDFLARE,
    @SerialName("remote") REMOTE
}

/**
 * Status of an endpoint test
 */
data class EndpointStatus(
    val connected: Boolean = false,
    val latency: Long? = null,
    val error: String? = null,
    val testing: Boolean = false,
    val lastTest: Long = 0,
)

data class ConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempts: Int = 0,
    val serverUrl: String = "",
    val connectedEndpoint: String? = null, // Name of connected endpoint
    val errorMessage: String? = null,
)

data class DialogUiState(
    val activeDialog: DialogDetails? = null,
    val queueItems: List<QueueItem> = emptyList(),
    val queueCount: Int = 0,
    val holdMode: Boolean = false,
    val selectedValue: String = "",
    val comment: String = "",
    val selectedOptions: Set<String> = emptySet(),
    val sliderValue: Float = 0f,
    val isQueueDrawerOpen: Boolean = false,
)

