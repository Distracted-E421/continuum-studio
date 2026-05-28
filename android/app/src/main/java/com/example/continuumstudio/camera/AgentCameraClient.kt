package com.example.continuumstudio.camera

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Client for sending camera images to Synapsix/Cognix agents.
 * 
 * Supports two modes:
 * 1. Single-shot capture: Manual "Send to Agent" button
 * 2. Agent-triggered: Agent requests a capture via MCP
 * 
 * Uses Tailscale for secure homelab networking.
 */
class AgentCameraClient(
    private val synapsixUrl: String = "http://100.95.167.76:8080",
    private val cognixUrl: String = "http://100.95.167.76:4100"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Send a captured image to the agent context.
     * Image is base64 encoded and sent with metadata.
     */
    suspend fun sendImage(
        bitmap: Bitmap,
        context: CaptureContext,
        destination: Destination = Destination.SYNAPSIX
    ): Result<ImageUploadResponse> = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmap.toBase64(quality = 85)
            
            val payload = ImageUploadPayload(
                version = "1.0",
                source = "continuum_cam",
                image = base64Image,
                format = "jpeg",
                width = bitmap.width,
                height = bitmap.height,
                context = context
            )

            val body = json.encodeToString(payload)
                .toRequestBody("application/json".toMediaType())

            val url = when (destination) {
                Destination.SYNAPSIX -> "$synapsixUrl/api/visual/capture"
                Destination.COGNIX -> "$cognixUrl/api/visual/capture"
            }

            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{\"success\":true}"
                Result.success(json.decodeFromString<ImageUploadResponse>(responseBody))
            } else {
                Result.failure(Exception("Upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if the agent endpoint is reachable.
     */
    suspend fun checkConnection(destination: Destination = Destination.SYNAPSIX): Boolean = 
        withContext(Dispatchers.IO) {
            try {
                val url = when (destination) {
                    Destination.SYNAPSIX -> "$synapsixUrl/health"
                    Destination.COGNIX -> "$cognixUrl/health"
                }
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                client.newCall(request).execute().isSuccessful
            } catch (e: Exception) {
                false
            }
        }

    private fun Bitmap.toBase64(quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    enum class Destination {
        SYNAPSIX,
        COGNIX
    }

    @Serializable
    data class CaptureContext(
        val triggeredBy: String = "user",
        val sessionId: String? = null,
        val agentId: String? = null,
        val description: String? = null,
        val location: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class ImageUploadPayload(
        val version: String,
        val source: String,
        val image: String,
        val format: String,
        val width: Int,
        val height: Int,
        val context: CaptureContext
    )

    @Serializable
    data class ImageUploadResponse(
        val success: Boolean,
        val imageId: String? = null,
        val message: String? = null
    )
}
