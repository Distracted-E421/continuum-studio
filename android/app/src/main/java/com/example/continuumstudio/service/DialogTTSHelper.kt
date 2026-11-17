package com.example.continuumstudio.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.continuumstudio.service.tts.AudioHub
import com.example.continuumstudio.service.tts.DialogAudioPlayer
import com.example.continuumstudio.service.tts.MediaSessionBridge
import com.example.continuumstudio.service.tts.PlaybackState
import com.example.continuumstudio.speech.SpeechScript
import com.example.continuumstudio.speech.SpeechScriptCompiler
import com.example.continuumstudio.speech.SpeechTrace
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * Reads dialogs aloud.
 *
 * This used to own its own [DialogAudioPlayer], which meant every surface that
 * wanted speech created a second engine with separate audio focus (see
 * `AudioHub`). It is now a thin adapter: it holds per-surface settings and
 * compiles the dialog into a [SpeechScript], then hands playback to the one
 * shared player.
 *
 * Android's own [TextToSpeech] remains only as a last-resort fallback for when
 * EasyVoice is unconfigured or a segment genuinely cannot be generated.
 */
class DialogTTSHelper(private val context: Context) : TextToSpeech.OnInitListener {

    /**
     * The shared player. Every helper in the process talks to this one, so the
     * dialog screen's controls always act on the clip that is actually playing,
     * whether it was started from the screen, a notification, or the car.
     */
    val audioPlayer: DialogAudioPlayer = AudioHub.player(context)

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    /** Live playback state, shared across surfaces. */
    val playback: StateFlow<PlaybackState?> get() = audioPlayer.playback

    // User preferences (per surface)
    var ttsEnabled = true
    var speechRate = 1.0f
    var readOptionsAloud = true

    init {
        tts = TextToSpeech(context, this)
        // Connect the media session up front: holding one is what keeps Android
        // Auto from dropping back to the car's own radio between dialogs.
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
                Log.i(TAG, "fallback TTS initialised")
            }
        } else {
            Log.e(TAG, "TTS initialisation failed: $status")
            isInitialized = false
        }
    }

    // --- Transport, forwarded so screens talk to one object ---------------

    fun seekAudio(fraction: Float) = audioPlayer.seekTo(fraction)
    fun pauseAudio() = audioPlayer.pause()
    fun resumeAudio() = audioPlayer.resume()
    fun togglePauseAudio() = audioPlayer.togglePause()
    fun currentPlayback(): PlaybackState? = audioPlayer.currentPlayback()
    val isPaused: Boolean get() = audioPlayer.isPaused

    /** Raise or lower how long a segment may take before falling back. */
    fun setGenerationTimeout(ms: Long) {
        audioPlayer.waitMs = ms.coerceIn(5_000L, 120_000L)
    }

    // --- Dialog speech ----------------------------------------------------

    /**
     * Compile a dialog into a script and speak it.
     *
     * The compile step is synchronous and cheap (no I/O); only synthesis is
     * async. Keeping it inline is the point: the moment a dialog is written, its
     * annotated script exists.
     */
    fun speakDialog(
        title: String,
        prompt: String,
        dialogType: String,
        optionsJson: String? = null,
        priority: String = "normal",
        dialogId: String? = null,
    ) {
        if (!ttsEnabled) {
            SpeechTrace.info("speak", "skipped: reading disabled")
            Log.d(TAG, "TTS disabled")
            return
        }

        val labels = optionLabels(optionsJson)
        val script = buildScript(title, prompt, dialogType, labels, priority)
        SpeechTrace.scriptCompiled(script)

        if (audioPlayer.usesEasyVoice) {
            audioPlayer.playScript(script, dialogId = dialogId)
            return
        }

        SpeechTrace.warn("speak", "EasyVoice not configured; using fallback TTS")
        Log.d(TAG, "EasyVoice not configured; using fallback TTS")
        speakWithSystemTts(script.flattened)
    }

    /**
     * Compile without speaking, so the UI can show the script immediately.
     *
     * Called when a dialog arrives regardless of whether audio is enabled - the
     * annotated script is useful on screen even when muted.
     */
    fun compileScript(
        title: String,
        prompt: String,
        dialogType: String,
        optionsJson: String? = null,
        priority: String = "normal",
    ): SpeechScript = buildScript(title, prompt, dialogType, optionLabels(optionsJson), priority)

    private fun buildScript(
        title: String,
        prompt: String,
        dialogType: String,
        labels: List<String>,
        priority: String,
    ): SpeechScript {
        val base = SpeechScriptCompiler.compile(
            title = title,
            prompt = prompt,
            dialogType = dialogType,
            optionLabels = labels,
            includeOptions = readOptionsAloud,
        )

        // Urgency is worth one short clause, and only once. Repeated "important
        // dialog" prefixes made every dialog sound identical.
        if (priority.lowercase() !in listOf("critical", "high")) return base

        val lead = SpeechScriptCompiler.Segment(
            kind = SpeechScriptCompiler.Kind.INSTRUCTION,
            spoken = "Important dialog.",
            pauseAfterMs = 420,
            source = "[priority: $priority]",
        )
        val segments = listOf(lead) + base.segments
        return base.copy(
            segments = segments,
            flattened = segments.joinToString(" ") { it.spoken },
        )
    }

    /** Choice labels for a choice dialog, used for speech and car rows. */
    private fun optionLabels(optionsJson: String?): List<String> {
        if (optionsJson.isNullOrBlank()) return emptyList()
        return try {
            Json.parseToJsonElement(optionsJson).jsonArray.mapNotNull { el ->
                val obj = el.jsonObject
                (obj["label"] ?: obj["value"])?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Speak a confirmation of the parsed response before sending.
     *
     * Kept short and bounded: a reply must not wait on synthesis.
     */
    fun speakConfirmation(
        dialogType: String,
        parsedValue: Any,
        optionLabel: String? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        if (!ttsEnabled) {
            onComplete?.invoke()
            return
        }
        val text = buildConfirmationText(dialogType, parsedValue, optionLabel)
        val script = SpeechScriptCompiler.compile(
            title = "",
            prompt = text,
            dialogType = "text",
            includeInstructions = false,
        )

        if (audioPlayer.usesEasyVoice) {
            audioPlayer.playScript(script, dialogId = null) { onComplete?.invoke() }
            return
        }
        speakWithSystemTts(script.flattened, onComplete)
    }

    private fun buildConfirmationText(
        dialogType: String,
        parsedValue: Any,
        optionLabel: String?,
    ): String = when (dialogType.lowercase()) {
        "choice" -> "Sending ${optionLabel ?: parsedValue}."
        "confirm", "confirmation" -> {
            val response = when (parsedValue) {
                true -> "yes"
                false -> "no"
                else -> parsedValue.toString()
            }
            "Sending $response."
        }
        "text" -> {
            val text = parsedValue.toString()
            val preview = if (text.length > 30) "${text.take(30)}..." else text
            "Sending $preview."
        }
        "slider" -> "Sending $parsedValue."
        else -> "Sending response."
    }

    /** Stop speech on this surface. */
    fun stop() {
        audioPlayer.stop()
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Release this surface.
     *
     * Does NOT shut the shared player down - another surface may still need it.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    // --- Android TTS fallback --------------------------------------------

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }

            override fun onError(utteranceId: String?) {
                Log.e(TAG, "TTS error for utterance: $utteranceId")
                _isSpeaking.value = false
            }

            @Deprecated("Deprecated in API 21")
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error for utterance $utteranceId: $errorCode")
                _isSpeaking.value = false
            }
        })
    }

    /** Android TTS path. Only used when EasyVoice cannot deliver at all. */
    private fun speakWithSystemTts(text: String, onComplete: (() -> Unit)? = null) {
        if (!isInitialized) {
            Log.w(TAG, "fallback TTS not initialised; cannot speak")
            onComplete?.invoke()
            return
        }
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (onComplete != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _isSpeaking.value = true
                }

                override fun onDone(utteranceId: String?) {
                    _isSpeaking.value = false
                    onComplete()
                    setupUtteranceListener()
                }

                override fun onError(utteranceId: String?) {
                    _isSpeaking.value = false
                    onComplete()
                    setupUtteranceListener()
                }
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dialog-${System.currentTimeMillis()}")
    }

    companion object {
        private const val TAG = "DialogTTSHelper"
    }
}
