package com.example.continuumstudio.network

import com.example.continuumstudio.service.GeminiInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Client for syncing captured Gemini interactions to Cognix.
 *
 * Uses Tailscale IP for secure local network sync.
 * No data leaves the homelab network.
 */
class CognixSyncClient(
    private val baseUrl: String = "http://100.95.167.76:4100"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun syncInteractions(interactions: List<GeminiInteraction>): Result<SyncResponse> =
        withContext(Dispatchers.IO) {
            try {
                val payload = SyncPayload(
                    version = "1.0",
                    source = "android_accessibility",
                    interactions = interactions.map { it.toSyncFormat() }
                )

                val body = json.encodeToString(payload)
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/api/cognix/sync")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: "{}"
                    Result.success(json.decodeFromString<SyncResponse>(responseBody))
                } else {
                    Result.failure(Exception("Sync failed: ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun checkConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    private fun GeminiInteraction.toSyncFormat() = SyncInteraction(
        id = id,
        timestamp = timestamp,
        interactionType = "qa_pair",
        query = query,
        response = response,
        thinking = thinking,
        metadata = mapOf(
            "source" to source,
            "packageName" to packageName
        )
    )

    @Serializable
    data class SyncPayload(
        val version: String,
        val source: String,
        val interactions: List<SyncInteraction>
    )

    @Serializable
    data class SyncInteraction(
        val id: String,
        val timestamp: String,
        val interactionType: String,
        val query: String,
        val response: String,
        val thinking: String? = null,
        val metadata: Map<String, String> = emptyMap()
    )

    @Serializable
    data class SyncResponse(
        val success: Boolean,
        val imported: Int = 0,
        val message: String? = null
    )
}
