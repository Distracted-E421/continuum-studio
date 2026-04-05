package com.example.continuumstudio.network

import com.example.continuumstudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for Synapsix CLI Agents API.
 * Handles agent spawning, presets, and dialog management.
 * Uses OkHttp for HTTP requests and kotlinx.serialization for JSON parsing.
 */
class CLIAgentsApiClient(
    private var baseUrl: String = DEFAULT_CLI_AGENTS_URL,
    private var dialogBaseUrl: String = DEFAULT_DIALOG_URL,
    private var cfAccessClientId: String? = null,
    private var cfAccessClientSecret: String? = null
) {
    companion object {
        // Use Obsidian's Tailscale IP for Android connectivity
        const val DEFAULT_CLI_AGENTS_URL = "http://100.102.101.72:4001"
        const val DEFAULT_DIALOG_URL = "http://100.102.101.72:8082"
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json".toMediaType()
    
    fun updateConfig(
        newBaseUrl: String? = null,
        newDialogBaseUrl: String? = null,
        newCfAccessClientId: String? = null,
        newCfAccessClientSecret: String? = null
    ): CLIAgentsApiClient {
        return CLIAgentsApiClient(
            baseUrl = newBaseUrl ?: this.baseUrl,
            dialogBaseUrl = newDialogBaseUrl ?: this.dialogBaseUrl,
            cfAccessClientId = newCfAccessClientId ?: this.cfAccessClientId,
            cfAccessClientSecret = newCfAccessClientSecret ?: this.cfAccessClientSecret
        )
    }
    
    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        cfAccessClientId?.let { clientId ->
            cfAccessClientSecret?.let { clientSecret ->
                builder.header("CF-Access-Client-Id", clientId)
                builder.header("CF-Access-Client-Secret", clientSecret)
            }
        }
        return builder
    }
    
    private suspend inline fun <reified T> executeRequest(request: Request): Result<T> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (body != null) {
                        Result.success(json.decodeFromString<T>(body))
                    } else {
                        Result.failure(IOException("Empty response body"))
                    }
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    private suspend fun executeRequestUnit(request: Request): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Result.failure(IOException("HTTP ${response.code}: $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // ==========================================================================
    // Agent Management
    // ==========================================================================
    
    suspend fun listAgents(): Result<List<AgentSummary>> {
        val request = buildRequest("$baseUrl/api/cli-agents").get().build()
        return executeRequest<ListAgentsResponse>(request).map { it.agents }
    }
    
    suspend fun getAgent(agentId: String): Result<CLIAgent> {
        val request = buildRequest("$baseUrl/api/cli-agents/$agentId").get().build()
        return executeRequest(request)
    }
    
    suspend fun spawnAgent(spawnRequest: SpawnAgentRequest): Result<SpawnResponse> {
        val body = json.encodeToString(spawnRequest).toRequestBody(jsonMediaType)
        val request = buildRequest("$baseUrl/api/cli-agents/spawn")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun spawnBatch(batchRequest: SpawnBatchRequest): Result<SpawnResponse> {
        val body = json.encodeToString(batchRequest).toRequestBody(jsonMediaType)
        val request = buildRequest("$baseUrl/api/cli-agents/batch")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun stopAgent(agentId: String): Result<Unit> {
        val request = buildRequest("$baseUrl/api/cli-agents/$agentId/stop")
            .post("".toRequestBody(null))
            .build()
        return executeRequestUnit(request)
    }
    
    // ==========================================================================
    // Presets API
    // ==========================================================================
    
    suspend fun listPresets(): Result<List<Preset>> {
        val request = buildRequest("$baseUrl/api/presets").get().build()
        return executeRequest<PresetsResponse>(request).map { it.presets }
    }
    
    suspend fun getPreset(id: String): Result<Preset> {
        val request = buildRequest("$baseUrl/api/presets/$id").get().build()
        return executeRequest<PresetResponse>(request).map { it.preset }
    }
    
    suspend fun createPreset(presetRequest: CreatePresetRequest): Result<Preset> {
        val body = json.encodeToString(presetRequest).toRequestBody(jsonMediaType)
        val request = buildRequest("$baseUrl/api/presets")
            .post(body)
            .build()
        return executeRequest<PresetResponse>(request).map { it.preset }
    }
    
    suspend fun updatePreset(id: String, presetRequest: CreatePresetRequest): Result<Preset> {
        val body = json.encodeToString(presetRequest).toRequestBody(jsonMediaType)
        val request = buildRequest("$baseUrl/api/presets/$id")
            .put(body)
            .build()
        return executeRequest<PresetResponse>(request).map { it.preset }
    }
    
    suspend fun deletePreset(id: String): Result<Unit> {
        val request = buildRequest("$baseUrl/api/presets/$id")
            .delete()
            .build()
        return executeRequestUnit(request)
    }
    
    // ==========================================================================
    // Snippets API
    // ==========================================================================
    
    suspend fun listSnippets(): Result<List<Snippet>> {
        val request = buildRequest("$baseUrl/api/presets/snippets").get().build()
        return executeRequest<SnippetsResponse>(request).map { it.snippets }
    }
    
    // ==========================================================================
    // Agent Dialogs API (for orchestration)
    // ==========================================================================
    
    suspend fun fetchPendingDialogs(): Result<List<PendingAgentDialog>> {
        val request = buildRequest("$dialogBaseUrl/api/agent-dialogs").get().build()
        return executeRequest<AgentDialogsResponse>(request).map { response ->
            if (!response.success) {
                throw IOException(response.error ?: "Unknown error")
            }
            response.data
        }
    }
    
    suspend fun respondToDialog(dialogId: String, selection: String, comment: String? = null): Result<Unit> {
        val body = json.encodeToString(DialogRespondRequest(selection, comment)).toRequestBody(jsonMediaType)
        val request = buildRequest("$dialogBaseUrl/api/agent-dialogs/$dialogId/respond")
            .post(body)
            .build()
        return executeRequestUnit(request)
    }
    
    suspend fun escalateDialog(dialogId: String): Result<Unit> {
        val request = buildRequest("$dialogBaseUrl/api/agent-dialogs/$dialogId/escalate")
            .post("".toRequestBody(null))
            .build()
        return executeRequestUnit(request)
    }
    
    // ==========================================================================
    // Orchestrator Mode API
    // ==========================================================================
    
    suspend fun getOrchestratorMode(): Result<OrchestratorModeResponse> {
        val request = buildRequest("$dialogBaseUrl/api/orchestrator/mode").get().build()
        return executeRequest(request)
    }
    
    suspend fun setOrchestratorMode(mode: String): Result<Unit> {
        val body = """{"mode":"$mode"}""".toRequestBody(jsonMediaType)
        val request = buildRequest("$dialogBaseUrl/api/orchestrator/mode")
            .post(body)
            .build()
        return executeRequestUnit(request)
    }
}

// Orchestrator Mode Response
@kotlinx.serialization.Serializable
data class OrchestratorModeResponse(
    val mode: String,
    val emoji: String? = null
)
