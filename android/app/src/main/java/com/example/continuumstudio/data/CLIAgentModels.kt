package com.example.continuumstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * CLI Agent data models for Synapsix CLI agent management.
 * These models match the Synapsix CLIBackend API at /api/cli-agents/
 */

// === Agent Types ===

@Serializable
data class CLIAgent(
    val id: String,
    val workspace: String,
    val prompt: String,
    val status: String, // "pending" | "running" | "completed" | "failed" | "timeout"
    val mode: String? = null, // "agent" | "plan" | "ask"
    val model: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val events: List<CLIAgentEvent> = emptyList(),
    val result: String? = null,
    val error: String? = null
)

@Serializable
data class CLIAgentEvent(
    val type: String,
    val timestamp: String,
    val content: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_args") val toolArgs: JsonElement? = null
)

@Serializable
data class AgentSummary(
    val id: String,
    val workspace: String,
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("event_count") val eventCount: Int = 0
)

// === Presets ===

@Serializable
data class Preset(
    val id: String,
    val name: String,
    val description: String? = null,
    val category: String,
    @SerialName("is_builtin") val isBuiltin: Boolean = false,
    val prefix: String? = null,
    val suffix: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun displayName(): String = if (isBuiltin) "📦 $name" else "✏️ $name"
}

@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val description: String? = null,
    val content: String,
    val position: String, // "prefix" | "suffix"
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

// === Pending Dialogs from Agents ===

@Serializable
data class PendingAgentDialog(
    val id: String,
    val title: String,
    val prompt: String,
    @SerialName("dialog_type") val dialogType: String,
    val options: List<DialogOptionItem>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    val source: String? = null, // "session_agent" | "sub_agent" | "orchestrator" | "external"
    val priority: String? = null, // "low" | "normal" | "high" | "critical"
    val workspace: String? = null,
    @SerialName("orchestrator_id") val orchestratorId: String? = null
) {
    val sourceDisplay: String
        get() = when (source?.lowercase()) {
            "session_agent", "sessionagent" -> "Session"
            "sub_agent", "subagent" -> "Sub"
            "external" -> "External"
            else -> "Orch"
        }
    
    val priorityEmoji: String
        get() = when (priority?.lowercase()) {
            "critical" -> "🚨"
            "high" -> "🔴"
            "low" -> "⬇️"
            else -> ""
        }
}

@Serializable
data class DialogOptionItem(
    val value: String,
    val label: String,
    val description: String? = null
)

// === API Requests ===

@Serializable
data class SpawnAgentRequest(
    val prompt: String,
    val workspace: String,
    val mode: String? = null,
    val force: Boolean? = null,
    @SerialName("approve_mcps") val approveMcps: Boolean? = null,
    val prefix: String? = null,
    val suffix: String? = null
)

@Serializable
data class SpawnBatchRequest(
    val prompt: String,
    val workspaces: List<String>,
    @SerialName("max_concurrent") val maxConcurrent: Int? = null,
    @SerialName("stop_on_failure") val stopOnFailure: Boolean? = null
)

@Serializable
data class CreatePresetRequest(
    val name: String,
    val description: String? = null,
    val category: String,
    val prefix: String? = null,
    val suffix: String? = null
)

@Serializable
data class DialogRespondRequest(
    val selection: String,
    val comment: String? = null
)

// === API Responses ===

@Serializable
data class SpawnResponse(
    @SerialName("agent_id") val agentId: String,
    @SerialName("batch_id") val batchId: String? = null
)

@Serializable
data class ListAgentsResponse(
    val agents: List<AgentSummary>
)

@Serializable
data class PresetsResponse(
    val presets: List<Preset>
)

@Serializable
data class SnippetsResponse(
    val snippets: List<Snippet>
)

@Serializable
data class AgentDialogsResponse(
    val success: Boolean,
    val data: List<PendingAgentDialog>,
    val error: String? = null
)

@Serializable
data class PresetResponse(
    val preset: Preset
)

@Serializable
data class SnippetResponse(
    val snippet: Snippet
)

// === UI State ===

data class CLIAgentsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    
    // Agents
    val agents: List<AgentSummary> = emptyList(),
    val selectedAgent: CLIAgent? = null,
    
    // Presets
    val presets: List<Preset> = emptyList(),
    val selectedPresetId: String? = null,
    val snippets: List<Snippet> = emptyList(),
    
    // Pending dialogs from worker agents
    val pendingDialogs: List<PendingAgentDialog> = emptyList(),
    
    // Launch form
    val launchPrompt: String = "",
    val launchWorkspace: String = "",
    val launchMode: AgentMode = AgentMode.AGENT,
    
    // Selected tab
    val selectedTab: CLIAgentsTab = CLIAgentsTab.AGENTS
)

enum class CLIAgentsTab {
    AGENTS, LAUNCH, DIALOGS
}

enum class AgentMode(val label: String) {
    AGENT("agent"),
    PLAN("plan"),
    ASK("ask")
}

enum class AgentStatus(val displayName: String, val isTerminal: Boolean) {
    PENDING("Pending", false),
    RUNNING("Running", false),
    COMPLETED("Completed", true),
    FAILED("Failed", true),
    TIMEOUT("Timeout", true);
    
    companion object {
        fun fromString(s: String): AgentStatus = when (s.lowercase()) {
            "pending" -> PENDING
            "running" -> RUNNING
            "completed" -> COMPLETED
            "failed" -> FAILED
            "timeout" -> TIMEOUT
            else -> PENDING
        }
    }
}
