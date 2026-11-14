package com.example.continuumstudio.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Plays EasyVoice-synthesized dialog audio, with a bounded wait and an explicit
 * fallback signal.
 *
 * Policy (per user preference): EasyVoice quality is the default and worth a
 * short wait. Android TTS is only used when EasyVoice genuinely cannot deliver —
 * either it failed outright, or it did not produce audio within
 * [easyVoiceWaitMs]. The caller owns the Android TTS fallback so that this class
 * stays about playback only.
 */
class DialogAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "DialogAudioPlayer"

        /**
         * How long to wait for EasyVoice before falling back to Android TTS.
         *
         * Measured cold latency for a dialog-length prompt is ~2.5-3.4s, and a
         * cached response is immediate. 8s leaves room for a cold TLS handshake
         * plus server load while still being tolerable when it goes wrong.
         */
        const val EASYVOICE_WAIT_MS = 8_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val easyVoice = EasyVoiceClient(context)

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** True when EasyVoice is configured; false means callers should go straight to TTS. */
    val usesEasyVoice: Boolean get() = easyVoice.isConfigured

    /**
     * Speak [text] with EasyVoice.
     *
     * @return true if audio actually started playing. false means the caller must
     *         fall back to Android TTS — this includes the timeout case, so a
     *         slow network degrades rather than leaving dead air indefinitely.
     */
    suspend fun speak(text: String): Boolean {
        if (!easyVoice.isConfigured) return false
        if (text.isBlank()) return false

        val file = withTimeoutOrNull(EASYVOICE_WAIT_MS) {
            easyVoice.synthesize(text)
        }

        if (file == null) {
            Log.d(TAG, "EasyVoice unavailable within ${EASYVOICE_WAIT_MS}ms; caller should fall back")
            return false
        }

        return playFile(file)
    }

    private suspend fun playFile(file: File): Boolean {
        stop()
        if (!requestFocus()) {
            Log.d(TAG, "Audio focus refused; caller should fall back")
            return false
        }

        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                _isPlaying.value = false
                abandonFocus()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _isPlaying.value = false
                abandonFocus()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            _isPlaying.value = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Playback failed: ${e.message}")
            _isPlaying.value = false
            abandonFocus()
            false
        }
    }

    /** Stop playback and release resources. Safe to call when nothing is playing. */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        _isPlaying.value = false
        abandonFocus()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun requestFocus(): Boolean {
        if (hasFocus) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
                }
            }
            .build()

        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }
}
