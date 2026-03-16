package com.example.continuumstudio.network

import com.example.continuumstudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Synapsix Coordination API at /api/coord/
 * Uses OkHttp for HTTP requests and kotlinx.serialization for JSON parsing.
 */
class CoordinationApiClient(
    private val baseUrl: String = "http://obsidian:4040",
    private val cfAccessClientId: String? = null,
    private val cfAccessClientSecret: String? = null
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json".toMediaType()
    
    private fun buildRequest(path: String): Request.Builder {
        val builder = Request.Builder()
            .url("$baseUrl/api/coord$path")
        
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
    
    // === Overview & Health ===
    
    suspend fun getOverview(): Result<CoordinationOverview> {
        val request = buildRequest("/").get().build()
        return executeRequest(request)
    }
    
    suspend fun getHealth(): Result<HealthStatus> {
        val request = buildRequest("/health").get().build()
        return executeRequest(request)
    }
    
    // === Locks ===
    
    suspend fun getLocks(): Result<LocksResponse> {
        val request = buildRequest("/locks").get().build()
        return executeRequest(request)
    }
    
    suspend fun getLocksForAgent(agentId: String): Result<LocksResponse> {
        val request = buildRequest("/locks/agent/$agentId").get().build()
        return executeRequest(request)
    }
    
    suspend fun acquireLock(lockRequest: LockRequest): Result<Lock> {
        val body = json.encodeToString(LockRequest.serializer(), lockRequest)
            .toRequestBody(jsonMediaType)
        val request = buildRequest("/locks")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun releaseLock(lockId: String): Result<Map<String, String>> {
        val request = buildRequest("/locks/$lockId")
            .delete()
            .build()
        return executeRequest(request)
    }
    
    // === Conflicts ===
    
    suspend fun getConflicts(): Result<ConflictsResponse> {
        val request = buildRequest("/conflicts").get().build()
        return executeRequest(request)
    }
    
    suspend fun getConflictsForAgent(agentId: String): Result<ConflictsResponse> {
        val request = buildRequest("/conflicts/$agentId").get().build()
        return executeRequest(request)
    }
    
    suspend fun getConflictStats(): Result<ConflictStats> {
        val request = buildRequest("/conflicts/stats").get().build()
        return executeRequest(request)
    }
    
    suspend fun checkConflict(checkRequest: CheckConflictRequest): Result<CheckConflictResponse> {
        val body = json.encodeToString(CheckConflictRequest.serializer(), checkRequest)
            .toRequestBody(jsonMediaType)
        val request = buildRequest("/conflicts/check")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun resolveConflict(conflictId: String, resolution: String? = null): Result<Map<String, String>> {
        val bodyJson = buildString {
            append("{")
            resolution?.let { append("\"resolution\":\"$it\"") }
            append("}")
        }
        val body = bodyJson.toRequestBody(jsonMediaType)
        val request = buildRequest("/conflicts/$conflictId/resolve")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    // === Handoffs ===
    
    suspend fun getHandoffs(): Result<HandoffsResponse> {
        val request = buildRequest("/handoffs").get().build()
        return executeRequest(request)
    }
    
    suspend fun getHandoffHistory(): Result<HandoffHistoryResponse> {
        val request = buildRequest("/handoffs/history").get().build()
        return executeRequest(request)
    }
    
    suspend fun initiateHandoff(handoffRequest: InitiateHandoffRequest): Result<Handoff> {
        val body = json.encodeToString(InitiateHandoffRequest.serializer(), handoffRequest)
            .toRequestBody(jsonMediaType)
        val request = buildRequest("/handoffs")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun acceptHandoff(handoffId: String, acceptRequest: AcceptHandoffRequest): Result<Handoff> {
        val body = json.encodeToString(AcceptHandoffRequest.serializer(), acceptRequest)
            .toRequestBody(jsonMediaType)
        val request = buildRequest("/handoffs/$handoffId/accept")
            .post(body)
            .build()
        return executeRequest(request)
    }
    
    suspend fun cancelHandoff(handoffId: String): Result<Map<String, String>> {
        val request = buildRequest("/handoffs/$handoffId")
            .delete()
            .build()
        return executeRequest(request)
    }
    
    // === Shared State ===
    
    suspend fun getNamespaces(): Result<SharedStateNamespacesResponse> {
        val request = buildRequest("/state").get().build()
        return executeRequest(request)
    }
    
    suspend fun getStateValue(namespace: String, scope: String, key: String): Result<SharedStateEntry> {
        val request = buildRequest("/state/$namespace/$scope/$key").get().build()
        return executeRequest(request)
    }
    
    // === Update configuration ===
    
    fun updateConfig(
        newBaseUrl: String? = null,
        newCfAccessClientId: String? = null,
        newCfAccessClientSecret: String? = null
    ): CoordinationApiClient {
        return CoordinationApiClient(
            baseUrl = newBaseUrl ?: baseUrl,
            cfAccessClientId = newCfAccessClientId ?: cfAccessClientId,
            cfAccessClientSecret = newCfAccessClientSecret ?: cfAccessClientSecret
        )
    }
}
