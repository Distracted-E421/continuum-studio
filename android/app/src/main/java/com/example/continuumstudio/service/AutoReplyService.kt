package com.example.continuumstudio.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Service to handle Android Auto voice replies to Synapsix dialogs.
 * 
 * This service receives RemoteInput from MessagingStyle notifications and
 * sends the response to the Synapsix dialog daemon via HTTP API.
 *
 * Features:
 * - Voice reply parsing (fuzzy matching, ordinals, options)
 * - TTS confirmation before sending
 * - Offline queue with auto-sync
 */
class AutoReplyService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // DataStore access for server URL
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
    private val serverUrlKey = stringPreferencesKey("dialog_server_url")
    private val ttsConfirmationKey = stringPreferencesKey("tts_confirmation_enabled")
    
    // Offline queue for storing replies when server is unreachable
    private var offlineQueue: OfflineReplyQueue? = null
    
    // TTS for confirmation messages
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    override fun onCreate() {
        super.onCreate()
        offlineQueue = OfflineReplyQueue(this)
        
        // Initialize TTS for confirmation
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
                Log.d(TAG, "TTS ready for confirmations")
            }
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DialogNotificationService.ACTION_REPLY -> handleReply(intent)
            DialogNotificationService.ACTION_MARK_READ -> handleMarkRead(intent)
        }
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        offlineQueue?.shutdown()
        offlineQueue = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        serviceScope.cancel()
    }

    private fun handleReply(intent: Intent) {
        val dialogId = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_ID) ?: return
        val dialogType = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_TYPE) ?: "text"
        val title = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_TITLE) ?: "Dialog"
        val optionsJson = intent.getStringExtra(DialogNotificationService.EXTRA_OPTIONS_JSON)

        // Get the reply text from RemoteInput
        val results = RemoteInput.getResultsFromIntent(intent)
        val replyText = results?.getCharSequence(DialogNotificationService.KEY_TEXT_REPLY)?.toString()

        if (replyText.isNullOrBlank()) {
            Log.w(TAG, "Empty reply received for dialog $dialogId")
            return
        }

        Log.d(TAG, "Received reply for dialog $dialogId: $replyText (options: $optionsJson)")

        serviceScope.launch {
            try {
                // Get server URL from preferences
                val serverUrl = getServerUrl()
                if (serverUrl == null) {
                    Log.e(TAG, "No server URL configured")
                    showErrorNotification("No server configured", dialogId)
                    return@launch
                }

                // Parse options from JSON if available
                val options = parseOptionsJson(optionsJson)

                // Parse the reply based on dialog type and available options
                val selection = parseReply(dialogType, replyText, options)
                
                // Find the option label for better TTS confirmation
                val optionLabel = findOptionLabel(selection, options)
                
                // Speak confirmation before sending (if TTS confirmation is enabled)
                val confirmationEnabled = isTtsConfirmationEnabled()
                if (confirmationEnabled && ttsReady) {
                    speakConfirmationAndWait(dialogType, selection, optionLabel)
                }

                // Check if we're online
                val isOnline = offlineQueue?.isOnline?.value ?: true

                if (!isOnline) {
                    // Queue for later sync
                    val queued = offlineQueue?.queueReply(
                        dialogId = dialogId,
                        selection = selection,
                        comment = "Replied via Android Auto (queued offline)",
                        priority = if (title.contains("critical", ignoreCase = true)) 2 else 1
                    ) ?: false
                    
                    if (queued) {
                        speakStatus("Queued for later")
                        showQueuedNotification(title, replyText, dialogId, offlineQueue?.pendingCount?.value ?: 1)
                    } else {
                        speakStatus("Queue full")
                        showErrorNotification("Queue full - try again later", dialogId)
                    }
                    return@launch
                }

                // Attempt to send the response
                val success = sendDialogResponse(serverUrl, dialogId, selection)

                if (success) {
                    speakStatus("Sent")
                    showSuccessNotification(title, replyText, dialogId)
                } else {
                    // Server unreachable - queue for later
                    Log.w(TAG, "Server unreachable, queueing reply for later")
                    val queued = offlineQueue?.queueReply(
                        dialogId = dialogId,
                        selection = selection,
                        comment = "Replied via Android Auto (queued - server unreachable)"
                    ) ?: false
                    
                    if (queued) {
                        speakStatus("Server unreachable. Queued.")
                        showQueuedNotification(title, replyText, dialogId, offlineQueue?.pendingCount?.value ?: 1)
                    } else {
                        speakStatus("Failed")
                        showErrorNotification("Failed to send or queue reply", dialogId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending reply", e)
                
                // Try to queue on any exception
                try {
                    val options = parseOptionsJson(optionsJson)
                    val selection = parseReply(dialogType, replyText, options)
                    offlineQueue?.queueReply(dialogId, selection)
                    speakStatus("Error. Queued.")
                    showQueuedNotification(title, replyText, dialogId, offlineQueue?.pendingCount?.value ?: 1)
                } catch (qe: Exception) {
                    speakStatus("Error")
                    showErrorNotification("Error: ${e.message}", dialogId)
                }
            }
        }
    }
    
    private fun findOptionLabel(selection: Any, options: List<DialogOption>): String? {
        val selectionStr = selection.toString()
        return options.find { it.value == selectionStr }?.label
    }
    
    private suspend fun isTtsConfirmationEnabled(): Boolean {
        return try {
            val prefs = dataStore.data.first()
            prefs[ttsConfirmationKey]?.toBoolean() ?: true  // Default enabled
        } catch (e: Exception) {
            true
        }
    }
    
    private suspend fun speakConfirmationAndWait(dialogType: String, selection: Any, optionLabel: String?) {
        val text = buildConfirmationText(dialogType, selection, optionLabel)
        
        suspendCancellableCoroutine<Unit> { continuation ->
            val utteranceId = "confirmation_${System.currentTimeMillis()}"
            
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                
                override fun onDone(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(Unit) {}
                    }
                }
                
                override fun onError(utteranceId: String?) {
                    if (continuation.isActive) {
                        continuation.resume(Unit) {}
                    }
                }
                
                @Deprecated("Deprecated in API 21")
                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId)
                }
            })
            
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            
            // Timeout after 5 seconds max
            serviceScope.launch {
                kotlinx.coroutines.delay(5000)
                if (continuation.isActive) {
                    continuation.resume(Unit) {}
                }
            }
        }
    }
    
    private fun buildConfirmationText(dialogType: String, selection: Any, optionLabel: String?): String {
        return when (dialogType.lowercase()) {
            "choice" -> {
                val label = optionLabel ?: selection.toString()
                "Sending: $label"
            }
            "confirm", "confirmation" -> {
                val response = when (selection) {
                    true -> "Yes"
                    false -> "No"
                    else -> selection.toString()
                }
                "Sending: $response"
            }
            "text" -> {
                val text = selection.toString()
                val preview = if (text.length > 20) "${text.take(20)}..." else text
                "Sending: $preview"
            }
            "slider" -> "Sending: $selection"
            else -> "Sending response"
        }
    }
    
    private fun speakStatus(status: String) {
        if (ttsReady) {
            tts?.speak(status, TextToSpeech.QUEUE_ADD, null, "status_${System.currentTimeMillis()}")
        }
    }

    private fun showQueuedNotification(title: String, reply: String, dialogId: String, pendingCount: Int) {
        val notification = NotificationCompat.Builder(this, DialogNotificationService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("\u23F3 Reply queued")
            .setContentText("$title: ${reply.take(40)}... (${pendingCount} pending)")
            .setAutoCancel(true)
            .setTimeoutAfter(8000)
            .build()

        NotificationManagerCompat.from(this)
            .notify(dialogId.hashCode() + 102, notification)
    }

    private fun parseOptionsJson(json: String?): List<DialogOption> {
        if (json.isNullOrBlank()) return emptyList()
        
        return try {
            val jsonArray = Json.parseToJsonElement(json).jsonArray
            jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val value = obj["value"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val label = obj["label"]?.jsonPrimitive?.content ?: value
                val description = obj["description"]?.jsonPrimitive?.content
                DialogOption(value, label, description)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse options JSON: $json", e)
            emptyList()
        }
    }

    data class DialogOption(
        val value: String,
        val label: String,
        val description: String?
    )

    private fun handleMarkRead(intent: Intent) {
        val dialogId = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_ID) ?: return
        
        // Cancel the notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(DialogNotificationService.DIALOG_NOTIFICATION_ID)
        
        Log.d(TAG, "Marked dialog $dialogId as read")
    }

    private suspend fun getServerUrl(): String? {
        return try {
            val prefs = dataStore.data.first()
            prefs[serverUrlKey]
        } catch (e: Exception) {
            Log.e(TAG, "Error reading server URL", e)
            null
        }
    }

    /**
     * Parse the voice reply based on dialog type with fuzzy matching support.
     * 
     * For choice dialogs with options:
     * - Numeric: "1", "2", "3", "one", "two", "first", "second"
     * - Ordinal: "the first one", "option two", "number three"
     * - Fuzzy label match: matches option labels using Levenshtein distance
     * - Substring match: partial label matches (e.g., "continue" matches "Continue Session")
     * 
     * For confirmation dialogs:
     * - Positive: "yes", "ok", "okay", "confirm", "true", "yep", "yeah", "sure", "do it", "go ahead"
     * - Negative: "no", "nope", "cancel", "false", "nah", "don't", "stop"
     * 
     * For text/slider dialogs:
     * - Pass through with minimal processing
     */
    private fun parseReply(dialogType: String, replyText: String, options: List<DialogOption> = emptyList()): Any {
        val normalized = normalizeVoiceInput(replyText)
        
        return when (dialogType.lowercase()) {
            "choice" -> parseChoiceReply(normalized, replyText, options)
            "confirm", "confirmation" -> parseConfirmationReply(normalized)
            "slider" -> parseSliderReply(normalized, replyText)
            "text" -> replyText.trim()
            else -> replyText.trim()
        }
    }

    private fun normalizeVoiceInput(text: String): String {
        return text
            .trim()
            .lowercase()
            // Remove common voice recognition artifacts
            .replace(Regex("^(um+|uh+|like|so|well|okay so)\\s+"), "")
            // Normalize punctuation
            .replace(Regex("[,.]"), "")
            // Remove "please" at end
            .replace(Regex("\\s*please$"), "")
            .trim()
    }

    private fun parseChoiceReply(normalized: String, original: String, options: List<DialogOption>): Any {
        // 1. Try direct numeric
        val directNum = normalized.toIntOrNull()
        if (directNum != null && directNum > 0 && directNum <= options.size) {
            return options[directNum - 1].value
        }
        if (directNum != null) {
            return directNum.toString()
        }

        // 2. Try word numbers and ordinals
        val indexFromWords = parseOrdinalOrNumber(normalized)
        if (indexFromWords != null && indexFromWords > 0 && indexFromWords <= options.size) {
            return options[indexFromWords - 1].value
        }

        // 3. Try exact label match (case-insensitive)
        options.find { it.label.lowercase() == normalized }?.let {
            return it.value
        }

        // 4. Try exact value match
        options.find { it.value.lowercase() == normalized }?.let {
            return it.value
        }

        // 5. Try substring match on label
        options.find { it.label.lowercase().contains(normalized) || normalized.contains(it.label.lowercase()) }?.let {
            return it.value
        }

        // 6. Fuzzy match using Levenshtein distance
        if (options.isNotEmpty()) {
            val bestMatch = options.minByOrNull { 
                min(
                    levenshteinDistance(normalized, it.label.lowercase()),
                    levenshteinDistance(normalized, it.value.lowercase())
                )
            }
            
            val distance = min(
                levenshteinDistance(normalized, bestMatch!!.label.lowercase()),
                levenshteinDistance(normalized, bestMatch.value.lowercase())
            )
            
            // Accept if distance is less than 40% of the input length
            val threshold = (normalized.length * 0.4).toInt().coerceAtLeast(2)
            if (distance <= threshold) {
                Log.d(TAG, "Fuzzy matched '$normalized' to '${bestMatch.label}' (distance: $distance)")
                return bestMatch.value
            }
        }

        // 7. Fall back to original text for server-side handling
        return original.trim()
    }

    private fun parseOrdinalOrNumber(text: String): Int? {
        val ordinals = mapOf(
            "first" to 1, "1st" to 1, "one" to 1,
            "second" to 2, "2nd" to 2, "two" to 2,
            "third" to 3, "3rd" to 3, "three" to 3,
            "fourth" to 4, "4th" to 4, "four" to 4,
            "fifth" to 5, "5th" to 5, "five" to 5,
            "sixth" to 6, "6th" to 6, "six" to 6,
            "seventh" to 7, "7th" to 7, "seven" to 7,
            "eighth" to 8, "8th" to 8, "eight" to 8,
            "ninth" to 9, "9th" to 9, "nine" to 9,
            "tenth" to 10, "10th" to 10, "ten" to 10
        )

        // Direct match
        ordinals[text]?.let { return it }

        // Pattern matching: "the first one", "option two", "number three"
        val patterns = listOf(
            Regex("(the\\s+)?(\\w+)(\\s+one)?"),
            Regex("option\\s+(\\w+)"),
            Regex("number\\s+(\\w+)"),
            Regex("choice\\s+(\\w+)")
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val groups = match.groupValues.filter { it.isNotBlank() && it != text }
                for (group in groups) {
                    ordinals[group.trim()]?.let { return it }
                    group.trim().toIntOrNull()?.let { return it }
                }
            }
        }

        return null
    }

    private fun parseConfirmationReply(normalized: String): Any {
        val positivePatterns = listOf(
            "yes", "ok", "okay", "confirm", "true", "yep", "yeah", "sure",
            "do it", "go ahead", "that's right", "correct", "affirmative",
            "sounds good", "let's do it", "approved", "accept", "agree"
        )
        val negativePatterns = listOf(
            "no", "nope", "cancel", "false", "nah", "don't", "stop",
            "negative", "decline", "reject", "deny", "not now", "later",
            "never mind", "abort", "skip"
        )

        // Check for positive patterns
        if (positivePatterns.any { normalized.contains(it) }) {
            return true
        }

        // Check for negative patterns
        if (negativePatterns.any { normalized.contains(it) }) {
            return false
        }

        // Let server decide for ambiguous cases
        return normalized
    }

    private fun parseSliderReply(normalized: String, original: String): Any {
        // Try direct number
        normalized.toIntOrNull()?.let { return it }
        normalized.toDoubleOrNull()?.toInt()?.let { return it }

        // Try word numbers
        val wordNumbers = mapOf(
            "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
            "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
            "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
            "hundred" to 100
        )
        wordNumbers[normalized]?.let { return it }

        // Extract number from phrases like "set it to 50" or "fifty percent"
        val numPattern = Regex("(\\d+)")
        numPattern.find(normalized)?.let { match ->
            return match.value.toInt()
        }

        return original.trim()
    }

    /**
     * Calculate Levenshtein distance between two strings for fuzzy matching.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[m][n]
    }

    /**
     * Send dialog response to Synapsix daemon via HTTP API.
     */
    private suspend fun sendDialogResponse(serverUrl: String, dialogId: String, selection: Any): Boolean {
        return try {
            val httpUrl = buildHttpUrl(serverUrl)
            
            val selectionJson = when (selection) {
                is String -> JsonPrimitive(selection)
                is Boolean -> JsonPrimitive(selection)
                is Number -> JsonPrimitive(selection)
                else -> JsonPrimitive(selection.toString())
            }
            
            val requestBody = buildJsonObject {
                put("id", dialogId)
                put("selection", selectionJson)
                put("comment", "Replied via Android Auto")
            }
            
            val request = Request.Builder()
                .url("$httpUrl/api/answer")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            
            Log.d(TAG, "Dialog response sent: success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending dialog response", e)
            false
        }
    }

    private fun buildHttpUrl(serverUrl: String): String {
        return serverUrl
            .replace("ws://", "http://")
            .replace("wss://", "https://")
            .removeSuffix("/ws/dialogs")
            .removeSuffix("/")
    }

    private fun showSuccessNotification(title: String, reply: String, dialogId: String) {
        val notification = NotificationCompat.Builder(this, DialogNotificationService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✓ Reply sent")
            .setContentText("$title: ${reply.take(50)}")
            .setAutoCancel(true)
            .setTimeoutAfter(5000)
            .build()

        NotificationManagerCompat.from(this)
            .notify(dialogId.hashCode() + 100, notification)
    }

    private fun showErrorNotification(message: String, dialogId: String) {
        val notification = NotificationCompat.Builder(this, DialogNotificationService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Reply failed")
            .setContentText(message)
            .setAutoCancel(true)
            .setTimeoutAfter(10000)
            .build()

        NotificationManagerCompat.from(this)
            .notify(dialogId.hashCode() + 101, notification)
    }

    companion object {
        private const val TAG = "AutoReplyService"
    }
}
