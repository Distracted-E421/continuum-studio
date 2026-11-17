package com.example.continuumstudio.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * The one Android TTS engine in the process.
 *
 * ## Why this is shared
 *
 * `DialogTTSHelper` is constructed by three surfaces (the dialog view model, the
 * notification service, and the auto-reply service). When each helper owned its
 * own `TextToSpeech`, the app had three live synthesis engines and three
 * `onInit` callbacks - the same class of bug that having three `MediaPlayer`s
 * caused for audio routing, and it made "exactly one TTS engine" in the commit
 * history untrue.
 *
 * This object owns a single engine, initialises it lazily on first use, and
 * serialises utterances. It is only a fallback: EasyVoice is the normal path and
 * the reason the voice sounds good, so this exists for "unconfigured key" and
 * "generation genuinely failed" rather than for routine playback.
 *
 * Main-thread confined: `TextToSpeech` construction and `speak` must be called
 * from a thread with a Looper, and every caller here is a Compose/ViewModel or
 * Service callback.
 */
object FallbackTts {

    private const val TAG = "FallbackTts"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var pending: (() -> Unit)? = null

    /** True once an engine exists and reported success. */
    fun isReady(context: Context): Boolean {
        ensureStarted(context)
        return initialized
    }

    /** Create the engine if it does not exist yet. Idempotent. */
    private fun ensureStarted(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts
                val language = engine?.setLanguage(Locale.getDefault())
                val ok = when (language) {
                    TextToSpeech.LANG_MISSING_DATA,
                    TextToSpeech.LANG_NOT_SUPPORTED -> {
                        Log.w(TAG, "default language unsupported; trying US English")
                        engine?.setLanguage(Locale.US) != TextToSpeech.LANG_NOT_SUPPORTED
                    }
                    null -> false
                    else -> true
                }
                initialized = ok
                if (ok) {
                    engine?.setOnUtteranceProgressListener(listener)
                    Log.i(TAG, "fallback engine ready")
                } else {
                    Log.e(TAG, "no usable language for fallback TTS")
                }
            } else {
                Log.e(TAG, "fallback engine init failed: $status")
                initialized = false
            }
            pending?.invoke()
            pending = null
        }
    }

    /** Last registered completion callback, cleared on each new utterance. */
    @Volatile
    private var onDone: (() -> Unit)? = null

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            complete()
        }

        override fun onError(utteranceId: String?) {
            Log.e(TAG, "utterance error: $utteranceId")
            complete()
        }

        @Deprecated("Deprecated in API 21")
        override fun onError(utteranceId: String?, errorCode: Int) {
            Log.e(TAG, "utterance error $utteranceId: $errorCode")
            complete()
        }
    }

    private fun complete() {
        val cb = onDone
        onDone = null
        cb?.invoke()
    }

    /**
     * Speak [text], replacing anything in flight.
     *
     * @param onComplete invoked on finish or error, exactly once
     */
    fun speak(context: Context, text: String, rate: Float, onComplete: (() -> Unit)? = null) {
        ensureStarted(context)
        val engine = tts
        if (engine == null || !initialized) {
            onComplete?.invoke()
            return
        }
        onDone = onComplete
        engine.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
        engine.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "fallback-${System.currentTimeMillis()}",
        )
    }

    fun stop() {
        onDone = null
        tts?.stop()
    }

    /**
     * Tear the engine down.
     *
     * Deliberately only called from app teardown, never from a single surface
     * releasing - a surface going away must not break the fallback for another.
     */
    fun shutdown() {
        onDone = null
        tts?.shutdown()
        tts = null
        initialized = false
    }
}
