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
        // NOTE: deliberately no DialogMediaService.start / MediaSessionBridge.connect
        // here any more (1.19.49).
        //
        // This constructor runs on app launch from the ViewModel, and connecting
        // the media session there made the app a registered Android Auto media
        // source from the moment it started - holding the car's audio channel
        // while playing nothing. That is the likely cause of "no audio in the
        // car from any app". The session is now connected in ensureSessionForRead
        // when a dialog is actually about to be spoken.
    }

    /**
     * Bring up the media session for an imminent read.
     *
     * Kept separate from [ensureSessionForRead]'s callers so that merely opening
     * the app - or receiving a dialog while reading is switched off - never
     * registers us with the car.
     *
     * @param beforePlay runs once the session is actually usable, so the first
     *        segment goes to the car rather than to the phone speaker. Connecting
     *        is asynchronous, and previously it was already connected by the time
     *        anything played; now it has to be awaited.
     */
    private fun ensureSessionForRead(beforePlay: () -> Unit) {
        if (!session.shouldRequestSession(aboutToSpeak = true)) {
            beforePlay()
            return
        }
        session.markRequested()
        try {
            DialogMediaService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "could not start media service: ${e.message}")
            session.markConnectFailed()
            beforePlay()
            return
        }
        MediaSessionBridge.connect(context) { ok ->
            audioPlayer.preferMediaSession = ok
            Log.i(TAG, "media session connected=$ok")
            if (ok) {
                session.markConnected()
            } else {
                session.markConnectFailed()
            }
            beforePlay()
        }
    }

    /** Session lifetime rules, kept in a separately tested unit. */
    private val session = SessionLifetime()

    /** True once we have asked for the session, so it is not started per segment. */
    fun releaseSessionIfIdle() {
        if (!session.shouldReleaseWhenIdle()) return
        session.markReleased()
        MediaSessionBridge.disconnect()
        DialogMediaService.stop(context)
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
            // Only now, with something to actually say, do we register with the
            // car. Doing it at launch held the head unit's media channel while
            // idle (see the class comment). Playback waits for the connection so
            // the first segment does not leak out of the phone speaker.
            ensureSessionForRead {
                audioPlayer.playScript(script, dialogId = dialogId) {
                    // Hand the car's audio route back once the last word is
                    // spoken. Leaving it up is the same failure as starting it at
                    // launch, just delayed until after the dialog.
                    releaseSessionIfIdle()
                }
            }
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
