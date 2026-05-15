package com.example.continuumstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Offline operation types that can be queued for later sync.
 */
@Serializable
sealed class OperationType {
    @Serializable
    @SerialName("task_add")
    data class TaskAdd(
        val title: String,
        val description: String? = null,
        val priority: String = "normal"
    ) : OperationType()
    
    @Serializable
    @SerialName("task_update")
    data class TaskUpdate(
        val taskId: String,
        val status: String
    ) : OperationType()
    
    @Serializable
    @SerialName("dialog_response")
    data class DialogResponse(
        val dialogId: String,
        val selection: String,
        val comment: String? = null
    ) : OperationType()
    
    @Serializable
    @SerialName("settings_sync")
    data class SettingsSync(
        val settingsJson: String
    ) : OperationType()
    
    @Serializable
    @SerialName("agent_spawn")
    data class AgentSpawn(
        val prompt: String,
        val workspace: String,
        val presetId: String? = null
    ) : OperationType()
    
    @Serializable
    @SerialName("agent_stop")
    data class AgentStop(
        val agentId: String
    ) : OperationType()
    
    @Serializable
    @SerialName("custom")
    data class Custom(
        val name: String,
        val payload: JsonElement
    ) : OperationType()
}

/**
 * A queued offline operation.
 */
@Serializable
data class QueuedOperation(
    val id: String,
    val timestamp: Long,
    val operation: OperationType,
    val retryCount: Int = 0,
    val lastError: String? = null
)

/**
 * Overall offline state.
 */
enum class OfflineState {
    ONLINE,
    PARTIALLY_OFFLINE,
    FULLY_OFFLINE
}

/**
 * UI state for offline mode.
 */
data class OfflineUiState(
    val state: OfflineState = OfflineState.ONLINE,
    val pendingCount: Int = 0,
    val lastSync: Long? = null,
    val syncing: Boolean = false,
    val coreConnected: Boolean = false,
    val dialogConnected: Boolean = false,
    val cliAgentsConnected: Boolean = false,
    val error: String? = null
) {
    val statusText: String
        get() = when (state) {
            OfflineState.ONLINE -> "All services connected"
            OfflineState.FULLY_OFFLINE -> "Offline - operations will be queued"
            OfflineState.PARTIALLY_OFFLINE -> {
                val disconnected = mutableListOf<String>()
                if (!coreConnected) disconnected.add("Core")
                if (!dialogConnected) disconnected.add("Dialog")
                if (!cliAgentsConnected) disconnected.add("CLI Agents")
                "Partial - ${disconnected.joinToString(", ")} disconnected"
            }
        }
}

/**
 * Decision Engine types.
 */

enum class DialogPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL;
    
    fun emoji(): String = when (this) {
        LOW -> "⬇️"
        NORMAL -> ""
        HIGH -> "🔴"
        CRITICAL -> "🚨"
    }
    
    fun asString(): String = name.lowercase()
}

enum class DialogSource {
    ORCHESTRATOR,
    SESSION_AGENT,
    SUB_AGENT,
    EXTERNAL;
    
    fun displayName(): String = when (this) {
        ORCHESTRATOR -> "Orchestrator"
        SESSION_AGENT -> "Session"
        SUB_AGENT -> "Sub-Agent"
        EXTERNAL -> "External"
    }
}

enum class TriageState {
    MANUAL,
    AUTO_APPROVE,
    AUTO_DECLINE,
    PAUSED
}

/**
 * Result of decision engine evaluation.
 */
sealed class DecisionResult {
    data class AutoHandle(
        val response: String,
        val reasoning: String
    ) : DecisionResult()
    
    data class RequireUser(
        val reasoning: String
    ) : DecisionResult()
    
    data class Triage(
        val state: TriageState,
        val suggestedResponse: String?,
        val reasoning: String,
        val timeoutMs: Long
    ) : DecisionResult()
}

/**
 * A triage queue item.
 */
data class TriageItem(
    val dialog: PendingAgentDialog,
    val state: TriageState,
    val addedAt: Long,
    val suggestedResponse: String?,
    val reasoning: String,
    val timeoutMs: Long
) {
    val remainingMs: Long
        get() = maxOf(0, (addedAt + timeoutMs) - System.currentTimeMillis())
    
    val isExpired: Boolean
        get() = System.currentTimeMillis() >= addedAt + timeoutMs
}

/**
 * A recorded decision.
 */
@Serializable
data class DecisionRecord(
    val dialogId: String,
    val agentId: String?,
    val workspace: String?,
    val source: String,
    val priority: String,
    val response: String,
    val reasoning: String,
    val autoHandled: Boolean,
    val timestamp: Long,
    val mode: String,
    val undoable: Boolean = false
)

/**
 * Decision engine configuration.
 */
data class DecisionEngineConfig(
    val defaultTriageTimeoutSecs: Long = 30,
    val undoWindowSecs: Long = 10,
    val maxHistorySize: Int = 1000,
    val criticalKeywords: List<String> = listOf(
        "delete", "remove", "destroy", "drop", "irreversible",
        "production", "sudo", "root", "format", "wipe",
        "database", "migration"
    ),
    val agentTimeouts: Map<String, Long> = emptyMap()
)

/**
 * Orchestrator presence modes for decision engine.
 * Separate from AgentMode (AGENT/PLAN/ASK) which controls Cursor CLI mode.
 */
enum class OrchestratorPresenceMode {
    USER_ACTIVE,    // User handles all dialogs
    USER_DELEGATE,  // Routine dialogs auto-handled, high/critical escalated
    SPECTATOR,      // Auto-handle all, user can claim within timeout
    AUTONOMOUS;     // Full auto, critical queued for later

    fun displayName(): String = when (this) {
        USER_ACTIVE -> "Active"
        USER_DELEGATE -> "Delegate"
        SPECTATOR -> "Spectator"
        AUTONOMOUS -> "Autonomous"
    }
    
    fun emoji(): String = when (this) {
        USER_ACTIVE -> "🟢"
        USER_DELEGATE -> "🟡"
        SPECTATOR -> "🟠"
        AUTONOMOUS -> "🔴"
    }
    
    fun apiValue(): String = name.lowercase()
    
    companion object {
        fun fromApiValue(value: String): OrchestratorPresenceMode = when (value.lowercase()) {
            "user_active" -> USER_ACTIVE
            "user_delegate" -> USER_DELEGATE
            "spectator" -> SPECTATOR
            "autonomous" -> AUTONOMOUS
            else -> USER_ACTIVE
        }
    }
}

/**
 * Decision engine UI state.
 */
data class DecisionEngineUiState(
    val mode: OrchestratorPresenceMode = OrchestratorPresenceMode.USER_ACTIVE,
    val triageQueue: List<TriageItem> = emptyList(),
    val recentDecisions: List<DecisionRecord> = emptyList(),
    val undoableDecisions: List<DecisionRecord> = emptyList()
)
