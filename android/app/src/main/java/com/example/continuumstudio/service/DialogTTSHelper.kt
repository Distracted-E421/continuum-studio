package com.example.continuumstudio.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.UUID

/**
 * Helper class for Text-to-Speech dialog reading.
 * Designed for Android Auto use case - reads dialog prompts aloud for hands-free interaction.
 *
 * Features:
 * - Requests audio focus before speaking (respects media playback)
 * - Reads dialog title, prompt, and options for choice dialogs
 * - Supports pause/resume and cancellation
 * - Handles TTS initialization gracefully
 */
class DialogTTSHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    
    // Queue of utterances to speak
    private val utteranceQueue = mutableListOf<String>()
    private var currentUtteranceId: String? = null

    // User preferences
    var ttsEnabled = true
    var speechRate = 1.0f
    var readOptionsAloud = true

    init {
        tts = TextToSpeech(context, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            isInitialized = when (result) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                    Log.w(TAG, "TTS language not supported, trying English")
                    tts?.setLanguage(Locale.US)
                    true
                }
                else -> true
            }
            
            if (isInitialized) {
                tts?.setSpeechRate(speechRate)
                setupUtteranceListener()
                Log.i(TAG, "TTS initialized successfully")
            }
        } else {
            Log.e(TAG, "TTS initialization failed: $status")
            isInitialized = false
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                scope.launch {
                    if (utteranceQueue.isNotEmpty()) {
                        speakNext()
                    } else {
                        _isSpeaking.value = false
                        abandonAudioFocus()
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
                abandonAudioFocus()
            }
            
            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId: $errorCode")
                _isSpeaking.value = false
                abandonAudioFocus()
            }
        })
    }

    /**
     * Read a dialog aloud including title, prompt, and options if applicable.
     *
     * @param title Dialog title (e.g., "Session Progress")
     * @param prompt Dialog prompt text
     * @param dialogType Type of dialog (choice, confirmation, text, slider)
     * @param optionsJson Optional JSON array of options for choice dialogs
     * @param priority Dialog priority (critical dialogs get immediate attention)
     */
    fun speakDialog(
        title: String,
        prompt: String,
        dialogType: String,
        optionsJson: String? = null,
        priority: String = "normal"
    ) {
        if (!ttsEnabled || !isInitialized) {
            Log.d(TAG, "TTS disabled or not initialized")
            return
        }

        scope.launch(Dispatchers.Main) {
            // Clear any pending utterances for a new dialog
            stop()
            utteranceQueue.clear()

            // Build utterance text
            val cleanTitle = cleanTextForSpeech(title)
            val cleanPrompt = cleanTextForSpeech(prompt)

            // Priority announcement for critical/high
            if (priority.lowercase() in listOf("critical", "high")) {
                utteranceQueue.add("Important dialog from Synapsix.")
            }

            // Title and prompt
            utteranceQueue.add("$cleanTitle. $cleanPrompt")

            // Options for choice dialogs
            if (dialogType.lowercase() == "choice" && readOptionsAloud && !optionsJson.isNullOrBlank()) {
                val optionsText = formatOptionsForSpeech(optionsJson)
                if (optionsText.isNotEmpty()) {
                    utteranceQueue.add("Your options are: $optionsText")
                }
            }

            // Instruction based on dialog type
            when (dialogType.lowercase()) {
                "choice" -> utteranceQueue.add("Say the option number or name to reply.")
                "confirm", "confirmation" -> utteranceQueue.add("Say yes or no to respond.")
                "text" -> utteranceQueue.add("Speak your response.")
                "slider" -> utteranceQueue.add("Say a number to set the value.")
            }

            // Request audio focus and start speaking
            if (requestAudioFocus()) {
                speakNext()
            }
        }
    }

    /**
     * Stop current speech and clear queue.
     */
    fun stop() {
        tts?.stop()
        utteranceQueue.clear()
        _isSpeaking.value = false
        abandonAudioFocus()
    }

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        stop()
        scope.cancel()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun speakNext() {
        if (utteranceQueue.isEmpty()) {
            _isSpeaking.value = false
            abandonAudioFocus()
            return
        }

        val text = utteranceQueue.removeAt(0)
        currentUtteranceId = UUID.randomUUID().toString()
        
        tts?.speak(
            text,
            TextToSpeech.QUEUE_ADD,
            null,
            currentUtteranceId
        )
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            stop()
                            hasAudioFocus = false
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            hasAudioFocus = true
                        }
                    }
                }
                .build()

            val result = audioManager.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        stop()
                        hasAudioFocus = false
                    }
                },
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }

        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    /**
     * Clean text for TTS by removing/replacing problematic elements.
     */
    private fun cleanTextForSpeech(text: String): String {
        return text
            // Remove markdown formatting
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("\\*([^*]+)\\*"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
            // Convert bullet points
            .replace("•", "")
            .replace("✅", "completed:")
            .replace("⏳", "pending:")
            .replace("🔴", "urgent:")
            .replace("🚨", "critical:")
            // Remove other emojis
            .replace(Regex("[\\p{So}\\p{Cn}]"), "")
            // Clean up markdown headers
            .replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
            // Normalize whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Format options JSON for speech output.
     */
    private fun formatOptionsForSpeech(optionsJson: String): String {
        return try {
            val jsonArray = Json.parseToJsonElement(optionsJson).jsonArray
            jsonArray.mapIndexedNotNull { index, element ->
                val obj = element.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content
                    ?: obj["value"]?.jsonPrimitive?.content
                    ?: return@mapIndexedNotNull null
                "Option ${index + 1}: $label"
            }.joinToString(". ")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse options for speech: $e")
            ""
        }
    }

    companion object {
        private const val TAG = "DialogTTSHelper"
    }
}
