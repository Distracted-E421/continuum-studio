package com.example.continuumstudio.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.example.continuumstudio.speech.SpeechScript
import com.example.continuumstudio.speech.SpeechTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Plays EasyVoice-synthesized dialog audio, one script segment at a time.
 *
 * ## Two behaviours that were wrong before
 *
 * **The 8-second cutoff.** Any generation slower than [EASYVOICE_WAIT_MS] fell
 * back to the on-device robot voice. On a car connection over Tailscale that
 * fired constantly, which is why long dialogs were read by the bad voice. The
 * wait is now [DEFAULT_WAIT_MS] and callers can raise it further.
 *
 * **One clip per dialog.** The whole dialog used to be a single synthesis
 * request, so nothing played until every character was generated. Segments are
 * now synthesized in order and playback starts on the first one, so a long
 * dialog begins talking almost immediately.
 *
 * ## Pauses are silence, generated locally
 *
 * EasyVoice cannot produce silence on demand - it accepts `<break>` and then
 * speaks the word "break" (see [SpeechScriptCompiler]). Gaps are therefore a
 * `delay()` between segments, which is also why pause/resume is exact: there is
 * no audio to re-align, just a paused coroutine and a paused player.
 */
class DialogAudioPlayer(private val context: Context) {

    companion object {
        private const val TAG = "DialogAudioPlayer"

        /**
         * Default patience for one segment.
         *
         * Measured cold latency for a dialog-length prompt is ~2.5-3.4s on wifi.
         * Over a car's tailscale link, with TLS setup and a cold server, 25s is
         * a realistic ceiling before the audio is genuinely lost - and losing it
         * now costs voice quality, so waiting is the better trade.
         */
        const val DEFAULT_WAIT_MS = 25_000L

        /** Backwards-compatible alias for the constant external callers used. */
        const val EASYVOICE_WAIT_MS = DEFAULT_WAIT_MS
    }

    /**
     * Voice used for synthesis.
     *
     * Changing this invalidates the on-disk cache key naturally, because the
     * cache is keyed by voice.
     */
    @Volatile
    var voice: String = EasyVoiceClient.DEFAULT_VOICE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val easyVoice = EasyVoiceClient(context)

    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var scriptJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playback = MutableStateFlow<PlaybackState?>(null)
    val playback: StateFlow<PlaybackState?> = _playback

    /** True when EasyVoice is configured; false means callers should use TTS. */
    val usesEasyVoice: Boolean get() = easyVoice.isConfigured

    /** How long to wait for one segment before giving up. Adjustable by the UI. */
    @Volatile
    var waitMs: Long = DEFAULT_WAIT_MS

    /**
     * True when a MediaSession-owning service should carry playback.
     *
     * Only a MediaSession keeps the Android Auto audio route alive, so when the
     * service is up we hand clips to it instead of using a bare MediaPlayer.
     */
    @Volatile
    var preferMediaSession: Boolean = false

    @Volatile
    private var paused = false

    // --- Playback of a compiled script ------------------------------------

    /**
     * Speak a whole script.
     *
     * Returns immediately; progress is published through [playback]. Callers
     * that need to know whether audio ever started should watch the flow for a
     * non-null value, or use [speakAndWait].
     *
     * @param startAtSegment index to begin from, so callers can jump straight to
     *        the options when the listener already knows the context
     */
    fun playScript(
        script: SpeechScript,
        dialogId: String?,
        startAtSegment: Int = 0,
        onFinished: () -> Unit = {},
    ) {
        stop()
        if (script.isEmpty) {
            Log.d(TAG, "empty script; nothing to play")
            onFinished()
            return
        }
        val from = startAtSegment.coerceIn(0, script.segments.lastIndex)

        val labels = script.optionSegments.map { it.source }
        val title = script.title.ifBlank { "Dialog" }

        scriptJob = scope.launch {
            paused = false
            SpeechTrace.info("play", "script: ${script.segments.size} segments, waitMs=$waitMs")
            _playback.value = PlaybackState(
                phase = PlaybackPhase.GENERATING,
                segmentIndex = 0,
                segmentCount = script.segments.size,
                segmentLabel = script.segments.firstOrNull()?.kind?.name?.lowercase(),
                positionMs = 0,
                durationMs = 0,
                playing = false,
            )

            for ((index, segment) in script.segments.withIndex()) {
                // Skip straight to the requested segment. Anything skipped has
                // already been heard (or the listener chose to skip it), so it is
                // not synthesized at all - which also saves the request.
                if (index < from) continue
                if (!isActive()) return@launch

                publishGenerating(index, script.segments.size, segment.kind.name.lowercase())

                val startedAt = System.currentTimeMillis()
                val file = withTimeoutOrNull(waitMs) {
                    easyVoice.synthesize(segment.spoken, voice = voice)
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (file == null) {
                    SpeechTrace.warn(
                        "synth",
                        "segment ${index + 1}/${script.segments.size} failed after ${elapsed}ms; script stopped",
                    )
                    Log.d(TAG, "segment $index unavailable within ${waitMs}ms; stopping script")
                    break
                }
                SpeechTrace.info(
                    "synth",
                    "segment ${index + 1}/${script.segments.size} in ${elapsed}ms " +
                        "(${segment.spoken.length} chars)",
                )
                if (!isActive()) return@launch

                val started = playSegment(
                    file = file,
                    dialogId = dialogId,
                    title = title,
                    prompt = segment.spoken,
                    optionCount = labels.size,
                    optionLabels = labels,
                    segmentIndex = index,
                    segmentCount = script.segments.size,
                )
                if (!started) {
                    SpeechTrace.warn("play", "segment ${index + 1} could not start; script stopped")
                    Log.d(TAG, "segment $index could not start; stopping script")
                    break
                }

                awaitSegmentFinish(index, script.segments.size)

                // Local silence between segments. This is the pause the API
                // cannot give us.
                if (segment.pauseAfterMs > 0 && isActive()) {
                    delay(segment.pauseAfterMs.toLong())
                }
            }

            if (isActive()) {
                _isPlaying.value = false
                _playback.value = null
                abandonFocus()
                onFinished()
            }
        }
    }

    /**
     * Speak [script] and suspend until playback finishes or [timeoutMs] passes.
     *
     * Used by reply confirmations, which must not block the send for long.
     */
    suspend fun speakAndWait(script: SpeechScript, timeoutMs: Long = 6_000L): Boolean {
        var started = false
        val job = scope.launch { playScript(script, dialogId = null) }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (_playback.value != null) started = true
            if (started && _playback.value == null) break
            if (job.isCompleted && _playback.value == null) break
            delay(100)
        }
        return started
    }

    // --- Simple single-clip API (kept for confirmations and notifications) --

    suspend fun speak(text: String): Boolean =
        speak(text, dialogId = null, title = null, optionCount = 0, optionLabels = emptyList())

    /**
     * Speak one string, optionally publishing dialog metadata to the car.
     */
    suspend fun speak(
        text: String,
        dialogId: String?,
        title: String?,
        optionCount: Int,
        optionLabels: List<String> = emptyList(),
    ): Boolean {
        if (!easyVoice.isConfigured) return false
        if (text.isBlank()) return false

        val file = withTimeoutOrNull(waitMs) { easyVoice.synthesize(text, voice = voice) }
        if (file == null) {
            Log.d(TAG, "EasyVoice unavailable within ${waitMs}ms; caller should fall back")
            return false
        }

        // A single clip is exactly a one-segment script.
        return playSegment(
            file = file,
            dialogId = dialogId,
            title = title ?: "Dialog",
            prompt = text,
            optionCount = optionCount,
            optionLabels = optionLabels,
            segmentIndex = 0,
            segmentCount = 1,
        )
    }

    private fun isActive(): Boolean = scriptJob?.isActive ?: false

    private fun publishGenerating(index: Int, count: Int, label: String) {
        _playback.value = PlaybackState(
            phase = PlaybackPhase.GENERATING,
            segmentIndex = index,
            segmentCount = count,
            segmentLabel = label,
            positionMs = 0,
            durationMs = 0,
            playing = false,
        )
    }

    /** Start one clip playing. Returns false if it never started. */
    private fun playSegment(
        file: File,
        dialogId: String?,
        title: String,
        prompt: String,
        optionCount: Int,
        optionLabels: List<String>,
        segmentIndex: Int,
        segmentCount: Int,
    ): Boolean {
        if (preferMediaSession) {
            val ok = MediaSessionBridge.play(
                context = context,
                dialogId = dialogId ?: "segment-$segmentIndex",
                file = file,
                title = title,
                prompt = prompt,
                optionCount = optionCount,
                optionLabels = optionLabels,
            )
            if (ok) {
                _isPlaying.value = true
                _playback.value = PlaybackState(
                    phase = PlaybackPhase.PLAYING,
                    segmentIndex = segmentIndex,
                    segmentCount = segmentCount,
                    segmentLabel = null,
                    positionMs = 0,
                    durationMs = MediaSessionBridge.durationMs().coerceAtLeast(0),
                    playing = true,
                )
                return true
            }
            Log.d(TAG, "MediaSession playback unavailable; falling back to local player")
        }
        return playFileLocally(file, segmentIndex, segmentCount)
    }

    private fun playFileLocally(file: File, segmentIndex: Int, segmentCount: Int): Boolean {
        releaseLocalPlayer()
        if (!requestFocus()) {
            Log.d(TAG, "audio focus refused; caller should fall back")
            return false
        }

        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                // Must match DialogMediaService: USAGE_MEDIA keeps the Android
                // Auto route alive; USAGE_ASSISTANT binds to the STT channel and
                // dies when speech recognition ends.
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _isPlaying.value = false
                true
            }
            mp.prepare()
            mp.start()
            player = mp
            _isPlaying.value = true
            _playback.value = PlaybackState(
                phase = PlaybackPhase.PLAYING,
                segmentIndex = segmentIndex,
                segmentCount = segmentCount,
                segmentLabel = null,
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

    /**
     * Wait for the current segment to finish, honouring pause.
     *
     * Polling keeps this engine-agnostic: the local MediaPlayer and the
     * ExoPlayer inside the media session are both just "something with a
     * position that ends".
     */
    private suspend fun awaitSegmentFinish(segmentIndex: Int, segmentCount: Int) {
        while (isActive()) {
            val snapshot = currentPlayback()
            if (snapshot != null) {
                _playback.value = snapshot.copy(
                    segmentIndex = segmentIndex,
                    segmentCount = segmentCount,
                )
            }
            if (paused) {
                delay(120)
                continue
            }
            val finished = when {
                preferMediaSession && MediaSessionBridge.isConnected() ->
                    MediaSessionBridge.hasEnded() || !MediaSessionBridge.isPlaying()
                player != null -> player?.let { !it.isPlaying && it.currentPosition > 0 } ?: true
                else -> true
            }
            if (finished) return
            delay(120)
        }
    }

    // --- Transport --------------------------------------------------------

    /** Seek within the current segment to [fraction] (0f..1f). */
    fun seekTo(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (preferMediaSession && MediaSessionBridge.isConnected()) {
            val dur = MediaSessionBridge.durationMs()
            if (dur > 0) MediaSessionBridge.seekTo((dur * clamped).toLong())
            return
        }
        val mp = player ?: return
        val dur = try { mp.duration.takeIf { it > 0 } } catch (e: Exception) { null } ?: return
        mp.seekTo((dur * clamped).toInt())
    }

    fun pause() {
        paused = true
        if (preferMediaSession && MediaSessionBridge.isConnected()) {
            MediaSessionBridge.pause()
        } else {
            player?.let { if (it.isPlaying) it.pause() }
        }
        _isPlaying.value = false
        _playback.value = _playback.value?.copy(phase = PlaybackPhase.PAUSED, playing = false)
    }

    fun resume() {
        paused = false
        if (preferMediaSession && MediaSessionBridge.isConnected()) {
            MediaSessionBridge.resume()
        } else {
            player?.let { if (!it.isPlaying) it.start() }
        }
        _isPlaying.value = true
        _playback.value = _playback.value?.copy(phase = PlaybackPhase.PLAYING, playing = true)
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    val isPaused: Boolean get() = paused

    /** Snapshot of live position/duration, for the UI to poll while visible. */
    fun currentPlayback(): PlaybackState? {
        if (preferMediaSession && MediaSessionBridge.isConnected()) {
            val dur = MediaSessionBridge.durationMs()
            return PlaybackState(
                phase = if (paused) PlaybackPhase.PAUSED else PlaybackPhase.PLAYING,
                segmentIndex = _playback.value?.segmentIndex ?: 0,
                segmentCount = _playback.value?.segmentCount ?: 1,
                segmentLabel = _playback.value?.segmentLabel,
                positionMs = MediaSessionBridge.positionMs().coerceAtLeast(0),
                durationMs = dur.coerceAtLeast(0),
                playing = MediaSessionBridge.isPlaying(),
            )
        }
        val mp = player ?: return _playback.value?.copy(playing = false)
        return try {
            PlaybackState(
                phase = if (paused) PlaybackPhase.PAUSED else PlaybackPhase.PLAYING,
                segmentIndex = _playback.value?.segmentIndex ?: 0,
                segmentCount = _playback.value?.segmentCount ?: 1,
                segmentLabel = _playback.value?.segmentLabel,
                positionMs = mp.currentPosition,
                durationMs = mp.duration.coerceAtLeast(0),
                playing = mp.isPlaying,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Stop playback and cancel any script in progress. */
    fun stop() {
        scriptJob?.cancel()
        scriptJob = null
        paused = false
        releaseLocalPlayer()
        // Only tear down our own player. When playback is owned by the media
        // session, releasing here would drop the session - which is what made
        // Android Auto revert to the car's own radio after a dialog finished.
        if (!(preferMediaSession && MediaSessionBridge.isConnected())) {
            MediaSessionBridge.pauseSafely()
        }
        _isPlaying.value = false
        _playback.value = null
        abandonFocus()
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun releaseLocalPlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    // --- Audio focus ------------------------------------------------------

    private fun requestFocus(): Boolean {
        if (hasFocus) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
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

/** What the audio engine is currently doing, for the on-screen controls. */
enum class PlaybackPhase {
    /** Synthesizing; nothing audible yet this segment. */
    GENERATING,
    /** Audible. */
    PLAYING,
    /** Held by the user. */
    PAUSED,
}

/** Snapshot of dialog audio progress, for the player indicator and scrubber. */
data class PlaybackState(
    val positionMs: Int,
    val durationMs: Int,
    val playing: Boolean,
    val phase: PlaybackPhase = if (playing) PlaybackPhase.PLAYING else PlaybackPhase.PAUSED,
    val segmentIndex: Int = 0,
    val segmentCount: Int = 1,
    /** Kind of the segment being spoken, e.g. "paragraph" or "option". */
    val segmentLabel: String? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** Overall progress across the whole script, by segment. */
    val scriptProgress: Float
        get() {
            if (segmentCount <= 0) return 0f
            val within = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
            return ((segmentIndex + within) / segmentCount).coerceIn(0f, 1f)
        }

    val isGenerating: Boolean get() = phase == PlaybackPhase.GENERATING

    fun positionLabel(): String = formatMs(positionMs)
    fun durationLabel(): String = formatMs(durationMs)

    /** "2 of 7" style indicator. */
    fun segmentLabelText(): String = "${segmentIndex + 1} of $segmentCount"

    companion object {
        fun formatMs(ms: Int): String {
            val total = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(total / 60, total % 60)
        }
    }
}
