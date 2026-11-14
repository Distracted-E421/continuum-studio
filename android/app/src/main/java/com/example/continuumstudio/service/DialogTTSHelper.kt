package com.example.continuumstudio.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.continuumstudio.service.tts.DialogAudioPlayer
import com.example.continuumstudio.service.tts.MediaSessionBridge
import com.example.continuumstudio.service.tts.PlaybackState
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

    /**
     * Plays EasyVoice (Kokoro) audio when available. Android's built-in
     * TextToSpeech remains as the fallback for when EasyVoice is unconfigured,
     * fails, or does not deliver within DialogAudioPlayer.EASYVOICE_WAIT_MS.
     */
    private val audioPlayer = DialogAudioPlayer(context)

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    /** Playback progress for the audio indicator/scrubber. Null when idle. */
    val playback: StateFlow<PlaybackState?> get() = audioPlayer.playback

    fun seekTo(fraction: Float) = audioPlayer.seekTo(fraction)
    fun pauseAudio() = audioPlayer.pause()
    fun resumeAudio() = audioPlayer.resume()
    fun currentPlayback(): PlaybackState? = audioPlayer.currentPlayback()
    
    // Queue of utterances to speak
    private val utteranceQueue = mutableListOf<String>()
    private var currentUtteranceId: String? = null

    // User preferences
    var ttsEnabled = true
    var speechRate = 1.0f
    var readOptionsAloud = true

    init {
        tts = TextToSpeech(context, this)
        // Connect the media session up front so dialog audio plays through it.
        // Holding a MediaSession is what keeps Android Auto from dropping back
        // to the car's own radio.
        DialogMediaService.start(context)
        MediaSessionBridge.connect(context) { ok ->
            audioPlayer.preferMediaSession = ok
            Log.i(TAG, "media session connected=$ok")
        }
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
        if (!ttsEnabled) {
            Log.d(TAG, "TTS disabled")
            return
        }

        scope.launch(Dispatchers.Main) {
            stop()
            val text = buildDialogUtterance(title, prompt, dialogType, optionsJson, priority)

            // EasyVoice is the preferred path: better voice, and cached audio is
            // instant. It returns false when unconfigured, failed, or exceeded
            // EASYVOICE_WAIT_MS - only then do we use Android TTS.
            if (audioPlayer.usesEasyVoice) {
                val optionCount = countOptions(optionsJson)
                val ok = audioPlayer.speak(
                    text = text,
                    dialogId = title.hashCode().toString(),
                    title = title,
                    optionCount = optionCount,
                )
                if (ok) {
                    Log.d(TAG, "Speaking dialog via EasyVoice")
                    return@launch
                }
            }

            Log.d(TAG, "Falling back to Android TTS")
            speakWithSystemTts(text)
        }
    }

    /** Number of choices in a choice dialog, for the car UI's button row. */
    private fun countOptions(optionsJson: String?): Int {
        if (optionsJson.isNullOrBlank()) return 0
        return try {
            Json.parseToJsonElement(optionsJson).jsonArray.size
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Flatten a dialog into a single spoken string.
     *
     * Built as one utterance rather than a queue so that EasyVoice synthesizes
     * it in one request (one round-trip, one cache entry) instead of several.
     */
    private fun buildDialogUtterance(
        title: String,
        prompt: String,
        dialogType: String,
        optionsJson: String?,
        priority: String
    ): String {
        val parts = mutableListOf<String>()

        if (priority.lowercase() in listOf("critical", "high")) {
            parts.add("Important dialog from Synapsix.")
        }
        parts.add("${cleanTextForSpeech(title)}. ${cleanTextForSpeech(prompt)}")

        if (dialogType.lowercase() == "choice" && readOptionsAloud && !optionsJson.isNullOrBlank()) {
            val optionsText = formatOptionsForSpeech(optionsJson)
            if (optionsText.isNotEmpty()) parts.add("Your options are: $optionsText")
        }

        when (dialogType.lowercase()) {
            "choice" -> parts.add("Say the option number or name to reply.")
            "confirm", "confirmation" -> parts.add("Say yes or no to respond.")
            "text" -> parts.add("Speak your response.")
            "slider" -> parts.add("Say a number to set the value.")
        }

        return parts.joinToString(" ")
    }

    /** Android TTS path. Used only when EasyVoice cannot deliver. */
    private fun speakWithSystemTts(text: String) {
        if (!isInitialized) {
            Log.w(TAG, "System TTS not initialized; cannot speak dialog")
            return
        }
        utteranceQueue.clear()
        utteranceQueue.add(text)
        if (requestAudioFocus()) {
            speakNext()
        }
    }

    /**
     * Speak a confirmation of the parsed response before sending.
     *
     * @param dialogType Type of dialog (choice, confirmation, text, slider)
     * @param parsedValue The parsed value that will be sent
     * @param optionLabel Optional human-readable label for choice dialogs
     * @param onComplete Callback when confirmation speech is complete
     */
    fun speakConfirmation(
        dialogType: String,
        parsedValue: Any,
        optionLabel: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (!ttsEnabled) {
            onComplete?.invoke()
            return
        }

        scope.launch(Dispatchers.Main) {
            stop()
            val confirmationText = buildConfirmationText(dialogType, parsedValue, optionLabel)

            if (audioPlayer.usesEasyVoice) {
                // Confirmation must be short and immediate; if EasyVoice has it
                // cached (same reply as before) playback is instant, otherwise
                // this still respects the wait bound before falling through.
                if (audioPlayer.speak(confirmationText)) {
                    _isSpeaking.value = false
                    onComplete?.invoke()
                    return@launch
                }
            }

            utteranceQueue.clear()
            utteranceQueue.add(confirmationText)
            utteranceQueue.add(confirmationText)
            
            // Set up completion callback
            if (onComplete != null) {
                val originalListener = tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        _isSpeaking.value = false
                        abandonAudioFocus()
                        scope.launch(Dispatchers.Main) {
                            onComplete()
                        }
                        setupUtteranceListener() // Restore original listener
                    }
                    
                    override fun onError(utteranceId: String?) {
                        _isSpeaking.value = false
                        abandonAudioFocus()
                        scope.launch(Dispatchers.Main) {
                            onComplete()
                        }
                        setupUtteranceListener()
                    }
                    
                    @Deprecated("Deprecated in API 21")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onError(utteranceId)
                    }
                })
            }

            if (requestAudioFocus()) {
                speakNext()
            } else {
                onComplete?.invoke()
            }
        }
    }

    private fun buildConfirmationText(dialogType: String, parsedValue: Any, optionLabel: String?): String {
        return when (dialogType.lowercase()) {
            "choice" -> {
                val label = optionLabel ?: parsedValue.toString()
                "Sending: $label"
            }
            "confirm", "confirmation" -> {
                val response = when (parsedValue) {
                    true -> "Yes"
                    false -> "No"
                    else -> parsedValue.toString()
                }
                "Sending: $response"
            }
            "text" -> {
                val text = parsedValue.toString()
                val preview = if (text.length > 30) "${text.take(30)}..." else text
                "Sending: $preview"
            }
            "slider" -> {
                "Sending: $parsedValue"
            }
            else -> "Sending response"
        }
    }

    /**
     * Stop current speech and clear queue.
     */
    fun stop() {
        audioPlayer.stop()
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
        audioPlayer.shutdown()
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
            // Matches the media channel used by DialogMediaService and the
            // local player. USAGE_ASSISTANT would tie the TTS fallback to the
            // STT voice session too.
            .setUsage(AudioAttributes.USAGE_MEDIA)
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
