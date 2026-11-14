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

    /**
     * Playback position and duration for UI indicators and scrubbing.
     * Null when nothing is loaded.
     */
    private val _playback = MutableStateFlow<PlaybackState?>(null)
    val playback: StateFlow<PlaybackState?> = _playback

    /** True when EasyVoice is configured; false means callers should go straight to TTS. */
    val usesEasyVoice: Boolean get() = easyVoice.isConfigured

    /**
     * True when a MediaSession-owning service is responsible for playback.
     *
     * When the media service is running we hand the clip to it rather than
     * playing through a bare MediaPlayer, because only a MediaSession keeps the
     * Android Auto audio route alive.
     */
    @Volatile
    var preferMediaSession: Boolean = false

    /** Seek to [fraction] (0f..1f) of the current clip. No-op when nothing is playing. */
    fun seekTo(fraction: Float) {
        val mp = player ?: return
        val dur = try { mp.duration.takeIf { it > 0 } } catch (e: Exception) { null } ?: return
        val target = (dur * fraction.coerceIn(0f, 1f)).toInt()
        mp.seekTo(target)
        _playback.value = _playback.value?.copy(positionMs = target)
    }

    fun pause() {
        player?.let { if (it.isPlaying) { it.pause(); _isPlaying.value = false } }
    }

    fun resume() {
        player?.let { if (!it.isPlaying) { it.start(); _isPlaying.value = true } }
    }

    /** Current position/duration snapshot, polled by the UI while visible. */
    fun currentPlayback(): PlaybackState? {
        val mp = player ?: return _playback.value?.copy(playing = false)
        return try {
            PlaybackState(
                positionMs = mp.currentPosition,
                durationMs = mp.duration.coerceAtLeast(0),
                playing = mp.isPlaying,
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Speak [text] with EasyVoice.
     *
     * @return true if audio actually started playing. false means the caller must
     *         fall back to Android TTS — this includes the timeout case, so a
     *         slow network degrades rather than leaving dead air indefinitely.
     */
    suspend fun speak(text: String): Boolean = speak(text, dialogId = null, title = null, optionCount = 0)

    /**
     * Speak [text], optionally publishing full dialog metadata to the car.
     *
     * @param dialogId stable id, used as the MediaItem mediaId
     * @param title dialog title shown on the head unit
     * @param optionCount how many choices the dialog offers
     * @return true if audio actually started playing. false means the caller must
     *         fall back to Android TTS - this includes the timeout case, so a
     *         slow network degrades rather than leaving dead air indefinitely.
     */
    suspend fun speak(
        text: String,
        dialogId: String?,
        title: String?,
        optionCount: Int,
    ): Boolean {
        if (!easyVoice.isConfigured) return false
        if (text.isBlank()) return false

        val file = withTimeoutOrNull(EASYVOICE_WAIT_MS) {
            easyVoice.synthesize(text)
        }

        if (file == null) {
            Log.d(TAG, "EasyVoice unavailable within ${EASYVOICE_WAIT_MS}ms; caller should fall back")
            return false
        }

        if (preferMediaSession && dialogId != null) {
            val ok = MediaSessionBridge.play(
                context = context,
                dialogId = dialogId,
                file = file,
                title = title ?: "Dialog",
                prompt = text,
                optionCount = optionCount,
            )
            if (ok) {
                _isPlaying.value = true
                return true
            }
            Log.d(TAG, "MediaSession playback unavailable; falling back to local player")
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
                _playback.value = null
                abandonFocus()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _isPlaying.value = false
                _playback.value = null
                abandonFocus()
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            _isPlaying.value = true
            _playback.value = PlaybackState(
                positionMs = 0,
                durationMs = mp.duration.coerceAtLeast(0),
                playing = true,
            )
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
        // Only tear down the local MediaPlayer. When playback is going through
        // the MediaSession the clip is owned by the service, and releasing it
        // here would drop the session - which is what made Android Auto revert
        // to the car's own radio the moment a dialog finished speaking.
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
        _isPlaying.value = false
        _playback.value = null
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

/** Snapshot of dialog audio progress, for the player indicator and scrubber. */
data class PlaybackState(
    val positionMs: Int,
    val durationMs: Int,
    val playing: Boolean,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun positionLabel(): String = formatMs(positionMs)
    fun durationLabel(): String = formatMs(durationMs)

    private fun formatMs(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(total / 60, total % 60)
    }
}
