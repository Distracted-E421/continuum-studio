package com.example.continuumstudio.service.tts

import android.content.Context
import android.util.Log
import com.example.continuumstudio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * EasyVoice (Kokoro-82M) text-to-speech client.
 *
 * The API is OpenAI-compatible: `POST /api/v1/audio/speech` returns a complete
 * MP3 file. There is **no streaming endpoint** — the whole file arrives at once,
 * so latency is the full generation time (~2-3s for a dialog-length prompt).
 *
 * The `model` field is accepted and then ignored by the server: Kokoro-82M is
 * the only synthesis engine. Do not rely on it to select a voice model.
 *
 * Because every dialog would otherwise pay that latency, results are cached on
 * disk keyed by a hash of (voice, speed, text). A dialog heard once is instant
 * and works offline thereafter.
 */
class EasyVoiceClient(private val context: Context) {

    companion object {
        private const val TAG = "EasyVoiceClient"
        private const val BASE_URL = "https://easyvoice.ae/api/v1/audio/speech"

        /** Kokoro voice. `af_heart` is the warmest general-purpose English voice. */
        const val DEFAULT_VOICE = "af_heart"

        /** Fair-use characters are 10M per rolling 30 days on Pro. */
        private const val CACHE_DIR = "easyvoice"

        /** Generation is ~2-3s; leave headroom for a cold TLS handshake. */
        private const val TIMEOUT_SECONDS = 20L
    }

    private val apiKey: String = BuildConfig.EASYVOICE_API_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean get() = apiKey.isNotBlank()

    private val cacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
    }

    /**
     * Synthesize [text], returning a cached file when one already exists.
     *
     * Returns null on any failure (no key, network error, non-200, empty body).
     * Callers treat null as "fall back to Android TTS".
     */
    suspend fun synthesize(
        text: String,
        voice: String = DEFAULT_VOICE,
        speed: Float = 1.0f,
    ): File? = withContext(Dispatchers.IO) {
        if (!isConfigured) {
            Log.d(TAG, "No EasyVoice API key configured")
            return@withContext null
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@withContext null

        val target = cacheFile(trimmed, voice, speed)
        if (target.exists() && target.length() > 0) {
            Log.d(TAG, "Cache hit (${target.length()} bytes)")
            return@withContext target
        }

        try {
            val body = JSONObject().apply {
                put("input", trimmed)
                put("voice", voice)
                put("response_format", "mp3")
                put("speed", speed.toDouble())
            }.toString()

            val request = Request.Builder()
                .url(BASE_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Synthesis failed: HTTP ${response.code}")
                    return@withContext null
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.w(TAG, "Synthesis returned an empty body")
                    return@withContext null
                }

                // Write via a temp file so a killed process can't leave a
                // truncated MP3 that looks like a valid cache hit.
                val tmp = File(cacheDir, "${target.name}.tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    Log.w(TAG, "Could not commit cache file")
                    return@withContext null
                }
                Log.d(TAG, "Synthesized and cached ${bytes.size} bytes")
                target
            }
        } catch (e: Exception) {
            // Includes UnknownHostException when offline: that is expected and
            // not worth a warning, the caller simply falls back to Android TTS.
            Log.d(TAG, "Synthesis error: ${e.message}")
            null
        }
    }

    /** Drop cached audio older than [maxAgeDays]. Cache is unbounded otherwise. */
    fun pruneCache(maxAgeDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    private fun cacheFile(text: String, voice: String, speed: Float): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$voice|$speed|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.mp3")
    }
}
