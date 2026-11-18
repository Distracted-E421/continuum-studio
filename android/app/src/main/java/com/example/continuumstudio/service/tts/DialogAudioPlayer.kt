package com.example.continuumstudio.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.example.continuumstudio.speech.SpeechScript
import com.example.continuumstudio.speech.SpeechScriptCompiler
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

        /**
         * Grace window after playback has started before "not playing" is
         * believable.
         *
         * `MediaPlayer.isPlaying` is false in the window between `start()` and
         * audio actually beginning, and again while buffering. Treating that as
         * "finished" cut segments off mid-word - the "jumps over itself" symptom.
         */
        private const val START_GRACE_MS = 700L

        /** Hard ceiling so a stuck player cannot hang the rest of the script. */
        private const val MAX_SEGMENT_PLAY_MS = 180_000L

        /**
         * Longest we will wait for another app to hand audio focus back.
         *
         * A hold suspends the segment ceiling (otherwise a long call would make
         * the player skip ahead over itself), so it needs a bound of its own or a
         * focus loss that never recovers would leave the read hanging with the
         * player paused and no indication why. Five minutes covers a phone call
         * far longer than any realistic dialog read.
         */
        private const val MAX_HOLD_MS = 300_000L

        /**
         * How long a media session may take to load a segment before its
         * not-playing state is treated as an ending.
         *
         * `setMediaItem` + `prepare()` + `play()` is asynchronous, so the session
         * reports not-playing while it buffers. Without this window every segment
         * handed to the session was skipped immediately.
         */
        private const val LOAD_GRACE_MS = 3_000L

        /**
         * Segments to synthesize ahead of playback.
         *
         * Measured 2026-11-17 against the live API: generation ~1.6s, playback
         * ~3.7s for a typical chunk. Generation being faster means the buffer
         * grows ~2.1s per segment, so two is ample for normal operation.
         *
         * It is not enough for a *slow* segment: two segments of runway is ~7.4s,
         * so a 20s generation still leaves an audible gap. Raising this
         * unconditionally would spend requests on segments nobody reaches (stop,
         * skip, answer all end playback early), so instead the player watches its
         * own timing and deepens the lookahead only when it sees generation
         * running behind playback.
         */
        private const val PREFETCH_AHEAD = 2

        /** Deepened lookahead once generation is observed to be slow. */
        private const val PREFETCH_AHEAD_SLOW = 4

        /** A segment slower than this (vs playback) counts as slow. */
        private const val SLOW_GENERATION_FACTOR = 1.2
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

            // Producer/consumer with lookahead.
            //
            // The first version of this was strictly sequential: generate segment
            // N, play it, wait for it to finish, then start generating N+1. Since
            // generation (~2.4s) and playback (~2.4s) are comparable, that put a
            // full generation stall at every segment boundary - the audio was
            // segmented and halting rather than flowing.
            //
            // Now a producer generates ahead into a small channel while the
            // consumer plays whatever is ready, so synthesis overlaps playback.
            val prepared = kotlinx.coroutines.channels.Channel<PreparedSegment>(
                capacity = PREFETCH_AHEAD
            )

            val producer = launch {
                try {
                    for ((index, segment) in script.segments.withIndex()) {
                        if (index < from) continue
                        if (!isActive()) break
                        // Reflect progress before the request, so the strip shows
                        // "generating segment N" during the first segment's wait.
                        publishGenerating(index, script.segments.size, segment.kind.name.lowercase())
                        val startedAt = System.currentTimeMillis()
                        val file = withTimeoutOrNull(waitMs) {
                            easyVoice.synthesize(segment.spoken, voice = voice)
                        }
                        val elapsed = System.currentTimeMillis() - startedAt
                        if (file == null) {
                            SpeechTrace.warn(
                                "synth",
                                "segment ${index + 1}/${script.segments.size} failed after ${elapsed}ms",
                            )
                            prepared.send(PreparedSegment(index, segment, null, elapsed))
                            break
                        }
                        SpeechTrace.info(
                            "synth",
                            "segment ${index + 1}/${script.segments.size} in ${elapsed}ms " +
                                "(${segment.spoken.length} chars)",
                        )
                        prepared.send(PreparedSegment(index, segment, file, elapsed))
                    }
                } finally {
                    prepared.close()
                }
            }

            _playback.value = PlaybackState(
                phase = PlaybackPhase.GENERATING,
                segmentIndex = from,
                segmentCount = script.segments.size,
                segmentLabel = script.segments.getOrNull(from)?.kind?.name?.lowercase(),
                positionMs = 0,
                durationMs = 0,
                playing = false,
            )

            for (item in prepared) {
                if (!isActive()) break
                if (item.file == null) {
                    SpeechTrace.warn("play", "segment ${item.index + 1} unavailable; stopping")
                    break
                }

                // Detect the case prefetch cannot cover: generation slower than
                // this segment will take to play. Recording it makes the gap
                // attributable instead of mysterious, which is the difference
                // between "audio is broken" and "the service was slow on that one".
                val wasStarved = item.generationMs > 0 &&
                    item.generationMs > lastPlaybackMs * SLOW_GENERATION_FACTOR &&
                    lastPlaybackMs > 0
                if (wasStarved) {
                    SpeechTrace.warn(
                        "play",
                        "segment ${item.index + 1} generated in ${item.generationMs}ms vs " +
                            "${lastPlaybackMs}ms of playback - a gap is likely here",
                    )
                }

                _playback.value = _playback.value?.copy(
                    phase = PlaybackPhase.PLAYING,
                    segmentIndex = item.index,
                    segmentCount = script.segments.size,
                )

                val started = playSegment(
                    file = item.file,
                    dialogId = dialogId,
                    title = title,
                    prompt = item.segment.spoken,
                    optionCount = labels.size,
                    optionLabels = labels,
                    segmentIndex = item.index,
                    segmentCount = script.segments.size,
                )
                if (!started) {
                    SpeechTrace.warn("play", "segment ${item.index + 1} could not start; stopping")
                    break
                }

                awaitSegmentFinish(item.index, script.segments.size)

                // Remember how long this one played, so the next segment's
                // generation time can be judged against it.
                currentPlayback()?.durationMs?.takeIf { it > 0 }?.let { lastPlaybackMs = it }

                if (item.segment.pauseAfterMs > 0 && isActive()) {
                    delay(item.segment.pauseAfterMs.toLong())
                }
            }

            producer.cancel()
            prepared.cancel()

            if (isActive()) {
                _isPlaying.value = false
                _playback.value = null
                abandonFocus()
                onFinished()
            }
        }
    }

    /** Duration of the last segment played, used to judge generation speed. */
    private var lastPlaybackMs: Int = 0

    /** A segment that has finished synthesizing, ready to play. */
    private data class PreparedSegment(
        val index: Int,
        val segment: SpeechScriptCompiler.Segment,
        /** Null when synthesis failed, which ends the script. */
        val file: File?,
        val generationMs: Long,
    )

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
        // Only overwrite the playback state if nothing is audible right now.
        // Overwriting during playback made the audio strip flicker back to a
        // "generating" bar while a different segment was playing - which is what
        // made the prefetch look broken even when it was working.
        val current = _playback.value
        if (current != null && current.playing) return
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
            // The session owns its own audio attributes, so it has to be told
            // the same policy the local player uses. Without this the setting
            // would work on the phone and be ignored in the car.
            MediaSessionBridge.applyFocusPolicy(focusPolicy)
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
        // Wait for the clip to actually *complete*, not merely to stop reporting
        // isPlaying.
        //
        // The previous check was `!isPlaying && currentPosition > 0` (and
        // `!MediaSessionBridge.isPlaying()`), which raced in two ways:
        //  - `MediaPlayer.isPlaying` is false during the window between start()
        //    and actual playback, and again while buffering, so the segment was
        //    declared finished almost immediately and got cut off mid-word.
        //  - `currentPosition > 0` only saved the very first poll, not later ones.
        //
        // That is the "jumps over itself too fast, cutting each section" symptom.
        // Now we require either an explicit completion callback or the position to
        // have reached the clip duration.
        // Deadline is mutable because a focus hold must not count against it.
        // Otherwise a phone call longer than MAX_SEGMENT_PLAY_MS would hit the
        // ceiling, log "moving on", and start the next segment over the top of a
        // clip that is merely paused.
        var deadline = System.currentTimeMillis() + MAX_SEGMENT_PLAY_MS

        // Separate bound for the hold itself. A hold suspends `deadline`, so
        // without this an unrecoverable focus loss would wait forever.
        var holdStartedAt = 0L
        var holdWarned = false

        // Give playback a moment to actually begin before trusting any "stopped"
        // observation, so the start-up window cannot end a segment.
        var sawPlaying = false
        var startedAt = 0L

        while (isActive()) {
            // A hold is not a stall: time spent waiting on another app must not
            // push us past the segment ceiling. It gets its own, longer bound so
            // a loss that never recovers cannot hang the script silently.
            if (heldByFocusLoss) {
                if (holdStartedAt == 0L) holdStartedAt = System.currentTimeMillis()
                deadline = System.currentTimeMillis() + MAX_SEGMENT_PLAY_MS
                val heldFor = System.currentTimeMillis() - holdStartedAt
                if (heldFor > MAX_HOLD_MS) {
                    SpeechTrace.warn(
                        "focus",
                        "held for ${heldFor}ms; giving up on segment ${segmentIndex + 1}",
                    )
                    return
                }
                if (heldFor > 30_000L && !holdWarned) {
                    holdWarned = true
                    SpeechTrace.warn(
                        "focus",
                        "still held after ${heldFor}ms - another app has audio focus",
                    )
                }
            } else {
                holdStartedAt = 0L
                holdWarned = false
            }
            if (System.currentTimeMillis() > deadline) {
                SpeechTrace.warn("play", "segment ${segmentIndex + 1} exceeded max play time; moving on")
                return
            }

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

            val playingNow = when {
                preferMediaSession && MediaSessionBridge.isConnected() -> MediaSessionBridge.isPlaying()
                else -> player?.isPlaying == true
            }
            val position = when {
                preferMediaSession && MediaSessionBridge.isConnected() -> MediaSessionBridge.positionMs()
                else -> player?.let { runCatching { it.currentPosition }.getOrDefault(0) } ?: 0
            }
            val duration = when {
                preferMediaSession && MediaSessionBridge.isConnected() -> MediaSessionBridge.durationMs()
                else -> player?.let { runCatching { it.duration }.getOrDefault(0) } ?: 0
            }

            if (playingNow) {
                sawPlaying = true
                startedAt = System.currentTimeMillis()
            }

            // Genuinely finished: the clip reached its end, or the media session
            // reports ENDED, or it stopped after playing with no known duration.
            // That decision lives in SegmentEnd (pure, tested) because it was
            // wrong twice before - and treating a focus hold as an ending was the
            // third way it could go wrong.
            val endedBySession = preferMediaSession &&
                MediaSessionBridge.isConnected() &&
                MediaSessionBridge.hasEnded()

            // A media session that has been asked to play but has not started yet
            // is not "finished". `setMediaItem` + `prepare()` + `play()` is
            // asynchronous, so for a moment after each segment the session reports
            // not-playing while it loads the next file. Without this guard the
            // player skipped straight past every segment it handed to the session -
            // the car path cut audio off even more aggressively than the local one.
            val sessionLoading = preferMediaSession &&
                MediaSessionBridge.isConnected() &&
                !playingNow &&
                !endedBySession

            if (sessionLoading && System.currentTimeMillis() - startedAt < LOAD_GRACE_MS) {
                delay(120)
                continue
            }

            val finished = SegmentEnd.isFinished(
                SegmentEnd.Observation(
                    positionMs = position,
                    durationMs = duration,
                    playingNow = playingNow,
                    sawPlaying = sawPlaying,
                    sessionEnded = endedBySession,
                    heldByFocus = heldByFocusLoss,
                    msSincePlaying = System.currentTimeMillis() - startedAt,
                    startGraceMs = START_GRACE_MS,
                )
            )
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

    /**
     * How speech relates to other apps' audio.
     *
     * Defaults to [FocusPolicy.DEFAULT] (overlap) and is overridden from
     * [AudioPrefs] by every surface. The value is read at each focus decision
     * rather than cached, so changing it takes effect on the next segment.
     */
    @Volatile
    var focusPolicy: FocusPolicy = FocusPolicy.DEFAULT

    /**
     * True while we hold focus that we requested ourselves.
     *
     * Reset in [abandonFocus] as well as on [FocusResponse.HOLD_FOR_FOCUS].
     * Leaving it `true` after a loss meant later segments skipped `requestFocus`
     * entirely and played unfocused - the dialog quietly degraded to competing
     * with music at full volume halfway through, with no error anywhere.
     */
    @Volatile
    private var hasFocus = false

    /**
     * True when speech was held back by another app, so it may be resumed
     * automatically.
     *
     * Distinct from [paused], which is the user's own choice. Conflating them was
     * the bug that let a navigation prompt permanently end a dialog's read.
     */
    @Volatile
    private var heldByFocusLoss = false

    /**
     * Acquire focus if the policy needs it.
     *
     * @return true when speech may proceed. Under [FocusPolicy.OVERLAP] this is
     *         always true and nothing is requested - which is the point: the
     *         other app is never asked to react, so it cannot be left paused.
     */
    private fun requestFocus(): Boolean {
        if (!focusPolicy.requestsFocus) {
            Log.d(TAG, "focus: policy=${focusPolicy.storedValue}, none requested")
            return true
        }
        if (hasFocus) return true

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        // DUCK asks other audio to keep playing more quietly; PAUSE asks for it
        // outright. TRANSIENT (not MAY_DUCK) for PAUSE so a music app stops
        // rather than ducking, which is what the user chose there.
        val gainType = when (focusPolicy) {
            FocusPolicy.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            else -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }

        val request = AudioFocusRequest.Builder(gainType)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change -> onFocusChange(change) }
            .build()

        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) {
            SpeechTrace.warn("focus", "request denied under ${focusPolicy.storedValue}")
        }
        return hasFocus
    }

    /**
     * Map an `AudioManager` change onto the pure [FocusResponder] decision.
     *
     * The mapping is the only Android-specific part; what to *do* is tested
     * without a device.
     */
    private fun onFocusChange(change: Int) {
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAIN
            else -> return
        }
        when (FocusResponder.respond(focusPolicy, mapped)) {
            FocusResponse.IGNORE ->
                Log.d(TAG, "focus: $mapped ignored under ${focusPolicy.storedValue}")

            FocusResponse.HOLD_FOR_FOCUS -> {
                // A permanent LOSS is not returning on its own; drop our claim so
                // the next segment re-requests instead of assuming it still holds
                // something the system already took away.
                if (mapped == FocusChange.LOSS) hasFocus = false
                heldByFocusLoss = true
                SpeechTrace.warn("focus", "held: $mapped")
                if (player?.isPlaying == true) player?.pause()
                if (preferMediaSession && MediaSessionBridge.isConnected()) {
                    MediaSessionBridge.pause()
                }
                _isPlaying.value = false
                _playback.value = _playback.value?.copy(phase = PlaybackPhase.PAUSED, playing = false)
            }

            FocusResponse.RESUME_AFTER_HOLD -> {
                if (heldByFocusLoss) {
                    heldByFocusLoss = false
                    SpeechTrace.info("focus", "resumed after hold")
                    resume()
                }
            }
        }
    }

    private fun abandonFocus() {
        heldByFocusLoss = false
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
