package com.example.continuumstudio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Coordination API data models for multi-agent coordination dashboard.
 * These models match the Synapsix CoordinationRouter API at /api/coord/
 */

// === Overview & Health ===

@Serializable
data class CoordinationOverview(
    val service: String,
    val version: String,
    val endpoints: Map<String, String>
)

@Serializable
data class HealthStatus(
    val status: String,  // "healthy" | "degraded"
    val services: Map<String, ServiceHealth>
)

@Serializable
data class ServiceHealth(
    val status: String,  // "up" | "down" | "degraded"
    val details: Map<String, JsonElement>? = null
)

// === Locks ===

@Serializable
data class LocksResponse(
    val locks: List<Lock>,
    val count: Int
)

@Serializable
data class Lock(
    val id: String,
    @SerialName("resource_type") val resourceType: String,
    @SerialName("resource_id") val resourceId: String,
    val mode: String,  // "exclusive" | "shared"
    val holders: List<String>,
    @SerialName("acquired_at") val acquiredAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class LockRequest(
    @SerialName("resource_type") val resourceType: String,
    @SerialName("resource_id") val resourceId: String,
    val mode: String,
    @SerialName("agent_id") val agentId: String,
    val timeout: Int? = null
)

// === Conflicts ===

@Serializable
data class ConflictsResponse(
    val conflicts: List<Conflict>,
    val count: Int
)

@Serializable
data class Conflict(
    val id: String,
    val type: String,  // "file" | "resource" | "workspace" | "deploy"
    val severity: String,  // "high" | "medium" | "low"
    val agents: List<String>,
    val reason: String,
    val suggestions: List<String>? = null,
    @SerialName("detected_at") val detectedAt: String
)

@Serializable
data class ConflictStats(
    @SerialName("total_detected") val totalDetected: Int = 0,
    @SerialName("total_resolved") val totalResolved: Int = 0,
    @SerialName("by_type") val byType: Map<String, Int> = emptyMap(),
    @SerialName("by_severity") val bySeverity: Map<String, Int> = emptyMap()
)

@Serializable
data class CheckConflictRequest(
    @SerialName("agent_id") val agentId: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("resource_type") val resourceType: String,
    @SerialName("resource_id") val resourceId: String,
    val params: Map<String, JsonElement>? = null
)

@Serializable
data class CheckConflictResponse(
    val result: String,  // "ok" | "conflict" | "warning"
    val conflict: Conflict? = null,
    val warning: ConflictWarning? = null
)

@Serializable
data class ConflictWarning(
    val type: String,
    val severity: String,
    val reason: String,
    @SerialName("potential_conflicts") val potentialConflicts: List<Conflict>? = null,
    val suggestions: List<String>? = null
)

// === Handoffs ===

@Serializable
data class HandoffsResponse(
    val handoffs: List<Handoff>,
    val count: Int
)

@Serializable
data class HandoffHistoryResponse(
    val handoffs: List<Handoff>,
    val count: Int
)

@Serializable
data class Handoff(
    val id: String,
    @SerialName("from_agent") val fromAgent: String,
    @SerialName("to_agent") val toAgent: String? = null,
    val status: String,  // "pending" | "accepted" | "rejected" | "cancelled" | "expired"
    val reason: String,
    val context: Map<String, JsonElement>? = null,
    val progress: HandoffProgress? = null,
    val artifacts: List<String>? = null,
    @SerialName("locks_to_transfer") val locksToTransfer: List<String>? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
)

@Serializable
data class HandoffProgress(
    val completed: List<String> = emptyList(),
    val remaining: List<String> = emptyList()
)

@Serializable
data class InitiateHandoffRequest(
    @SerialName("from_agent") val fromAgent: String,
    val reason: String,
    val context: Map<String, JsonElement>? = null,
    val progress: HandoffProgress? = null,
    val artifacts: List<String>? = null
)

@Serializable
data class AcceptHandoffRequest(
    @SerialName("agent_id") val agentId: String
)

// === Shared State ===

@Serializable
data class SharedStateNamespacesResponse(
    val namespaces: List<SharedStateNamespace>
)

@Serializable
data class SharedStateNamespace(
    val namespace: String,
    val scopes: List<String>
)

@Serializable
data class SharedStateEntry(
    val key: String,
    val value: JsonElement,
    val version: Int,
    @SerialName("updated_by") val updatedBy: String,
    @SerialName("updated_at") val updatedAt: String
)

// === UI State ===

data class CoordinationUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    
    // Data
    val overview: CoordinationOverview? = null,
    val health: HealthStatus? = null,
    val locks: List<Lock> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    val conflictStats: ConflictStats? = null,
    val pendingHandoffs: List<Handoff> = emptyList(),
    val handoffHistory: List<Handoff> = emptyList(),
    val namespaces: List<SharedStateNamespace> = emptyList(),
    
    // Selected tab
    val selectedTab: CoordinationTab = CoordinationTab.OVERVIEW
)

enum class CoordinationTab {
    OVERVIEW,
    LOCKS,
    CONFLICTS,
    HANDOFFS,
    STATE
}

// Counts for overview
data class CoordinationCounts(
    val locks: Int = 0,
    val conflicts: Int = 0,
    val handoffs: Int = 0
)
