package com.example.continuumstudio.service.tts

import android.content.Context
import android.util.Log
import com.example.continuumstudio.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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

        /**
         * Voices offered in the app.
         *
         * All of these were verified against the live API (HTTP 200 with audio)
         * rather than copied from documentation. Kokoro's own docs list more,
         * but offering a voice that 400s would be worse than a shorter list.
         *
         * The trailing letter in Kokoro's convention is a quality grade, which is
         * why some are marked as a secondary option rather than equal choices.
         */
        val VOICES: List<Voice> = listOf(
            Voice("af_heart", "Heart", "American, female", "warmest general-purpose"),
            Voice("af_bella", "Bella", "American, female", "brighter"),
            Voice("af_nicole", "Nicole", "American, female", "lower, softer"),
            Voice("am_fenrir", "Fenrir", "American, male", "deeper"),
            Voice("am_michael", "Michael", "American, male", "neutral"),
            Voice("bf_emma", "Emma", "British, female", "clear"),
            Voice("bm_fable", "Fable", "British, male", "measured"),
        )

        /** Human-friendly name for a voice id, falling back to the raw id. */
        fun voiceLabel(id: String): String =
            VOICES.firstOrNull { it.id == id }?.name ?: id

        /** Fair-use characters are 10M per rolling 30 days on Pro. */
        private const val CACHE_DIR = "easyvoice"

        /**
         * Bumped whenever the dictionary or script compiler changes in a way
         * that alters spoken output.
         *
         * The cache key used to be `voice|speed|text`, so a pronunciation fix was
         * invisible for any text already heard: the input string is unchanged, so
         * the stale clip was served forever. Bump this with the change, not after.
         */
        private const val CACHE_SCHEMA_VERSION = 3

        /**
         * HTTP timeouts for one segment.
         *
         * These must exceed the player's wait budget, or the socket gives up
         * first and the caller sees a failure it could have waited out. A long
         * dialog segment on a poor mobile link is the case that matters, so this
         * is deliberately generous - the player still applies its own, shorter,
         * deadline and can move on.
         */
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 60L
    }

    private val apiKey: String = BuildConfig.EASYVOICE_API_KEY

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // A dead mobile link otherwise hangs until the read timeout on every
        // retry, which is what made a stalled generation feel like a freeze.
        .retryOnConnectionFailure(true)
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

            val call = client.newCall(request)

            // Cancel the HTTP call if the coroutine is cancelled (user pressed
            // stop or skip, or withTimeoutOrNull elapsed). Without this, `execute()`
            // kept running to completion: abandoning playback could still leave two
            // in-flight requests, each burning quota and possibly writing a cache
            // entry for audio nobody will hear.
            val cancelled = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

            try {
                call.execute().use { response ->
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
            } finally {
                cancelled?.dispose()
            }
        } catch (e: Exception) {
            // Includes UnknownHostException when offline: that is expected and
            // not worth a warning, the caller simply falls back to Android TTS.
            Log.d(TAG, "Synthesis error: ${e.message}")
            null
        }
    }

    private fun cacheFile(text: String, voice: String, speed: Float): File {
        // text here is already the compiled *spoken* form, so a dictionary
        // change usually changes it and busts the cache naturally. The version is
        // the safety net for changes that do not alter this particular string but
        // do alter pacing or pauses around it.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("v$CACHE_SCHEMA_VERSION|$voice|$speed|$text".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(cacheDir, "$digest.mp3")
    }

    /**
     * Drop cached audio older than [maxAgeDays].
     *
     * Previously defined and never called, so the cache grew without bound -
     * every dialog ever spoken, kept forever, in internal storage.
     */
    fun pruneCache(maxAgeDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 60L * 60L * 1000L
        var removed = 0
        cacheDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                if (file.delete()) removed++
            }
        }
        if (removed > 0) Log.i(TAG, "pruned $removed cached clips older than ${maxAgeDays}d")
    }

    /** Total bytes held by cached clips, for the settings screen. */
    fun cacheSizeBytes(): Long =
        cacheDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}

/**
 * A selectable synthesis voice.
 *
 * @param id the API voice identifier, e.g. "af_heart"
 * @param name short label for the UI
 * @param accent language and gender summary
 * @param note what makes this voice distinct, for choosing between them
 */
data class Voice(
    val id: String,
    val name: String,
    val accent: String,
    val note: String,
)
