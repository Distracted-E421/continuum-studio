package com.example.continuumstudio.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.continuumstudio.service.tts.AudioHub
import com.example.continuumstudio.service.tts.AudioPrefs
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
 * EasyVoice is unconfigured or a segment genuinely cannot be generated. That
 * engine is shared too ([FallbackTts]) - three helpers each constructing one
 * meant three live TTS engines and three `onInit` callbacks, which is the same
 * class of bug the shared player fixed.
 */
class DialogTTSHelper(private val context: Context) {

    /**
     * The shared player. Every helper in the process talks to this one, so the
     * dialog screen's controls always act on the clip that is actually playing,
     * whether it was started from the screen, a notification, or the car.
     */
    val audioPlayer: DialogAudioPlayer = AudioHub.player(context)

    private var _isSpeaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> get() = _isSpeaking

    /** True once the shared fallback engine is usable. */
    private val ttsAvailable: Boolean get() = FallbackTts.isReady(context)

    /** Live playback state, shared across surfaces. */
    val playback: StateFlow<PlaybackState?> get() = audioPlayer.playback

    // User preferences (per surface)
    var ttsEnabled = true
    var speechRate = 1.0f
    var readOptionsAloud = true

    init {
        // Connect the media session up front: holding one is what keeps Android
        // Auto from dropping back to the car's own radio between dialogs.
        DialogMediaService.start(context)
        MediaSessionBridge.connect(context) { ok ->
            audioPlayer.preferMediaSession = ok
            Log.i(TAG, "media session connected=$ok")
        }
        // Deliberately no AudioPrefs.load here. That read blocks (see AudioPrefs),
        // and this class is constructed on the main thread by the ViewModel. The
        // service surfaces that actually need persisted settings already call
        // AudioPrefs.applyTo(AudioPrefs.load(...)), which now carries the focus
        // policy as well.
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
        FallbackTts.stop()
        _isSpeaking.value = false
    }

    /**
     * Release this surface.
     *
     * Does NOT shut the shared player or the shared fallback engine down -
     * another surface may still need them.
     */
    fun shutdown() {
        stop()
    }

    // --- Android TTS fallback --------------------------------------------

    /**
     * Android TTS path. Only used when EasyVoice cannot deliver at all.
     *
     * Delegates to [FallbackTts], which owns the one engine in the process.
     */
    private fun speakWithSystemTts(text: String, onComplete: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (!ttsAvailable) {
            Log.w(TAG, "fallback TTS not initialised; cannot speak")
            onComplete?.invoke()
            return
        }
        _isSpeaking.value = true
        FallbackTts.speak(context, text, speechRate) {
            _isSpeaking.value = false
            onComplete?.invoke()
        }
    }

    companion object {
        private const val TAG = "DialogTTSHelper"
    }
}
