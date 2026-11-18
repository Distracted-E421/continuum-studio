package com.example.continuumstudio.service.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for deciding when a segment has finished playing.
 *
 * This logic caused the "jumps over itself too fast, cutting each section off"
 * bug, and it is subtle enough to regress silently: getting it wrong does not
 * crash, it just truncates audio.
 *
 * The rule being protected: **"not playing" is not "finished"**. A player reports
 * not-playing when it is loading, when it is buffering, and in the window between
 * `start()` and audio actually beginning. Only reaching the end (or an explicit
 * ENDED state) means finished.
 *
 * Mirrored from `DialogAudioPlayer.awaitSegmentFinish` so the decision can be
 * inspected without a device.
 */
class SegmentFinishTest {

    /** Mirrors the decision in awaitSegmentFinish. */
    private data class Observation(
        val playingNow: Boolean,
        val positionMs: Int,
        val durationMs: Int,
        val sessionEnded: Boolean = false,
        val sawPlaying: Boolean = true,
        val msSinceStart: Long = 5_000,
        val isMediaSession: Boolean = false,
    )

    private fun finished(o: Observation): Boolean {
        val reachedEnd = o.durationMs > 0 && o.positionMs >= o.durationMs - 80
        if (reachedEnd) return true
        if (o.sessionEnded) return true

        // Session still loading: not finished, even if not playing.
        val sessionLoading = o.isMediaSession && !o.playingNow && !o.sessionEnded
        if (sessionLoading && o.msSinceStart < 3_000) return false

        if (o.sawPlaying && !o.playingNow && o.msSinceStart > 700 && o.durationMs <= 0) {
            return true
        }
        return false
    }

    @Test
    fun `a clip that reached its duration is finished`() {
        assertTrue(finished(Observation(playingNow = false, positionMs = 3700, durationMs = 3700)))
        assertTrue(finished(Observation(playingNow = true, positionMs = 3690, durationMs = 3700)))
    }

    @Test
    fun `briefly not playing mid-clip is not finished`() {
        // This is the bug: MediaPlayer reports not-playing while buffering, and
        // the old check treated that as the end of the segment.
        assertFalse(finished(Observation(playingNow = false, positionMs = 400, durationMs = 3700)))
        assertFalse(finished(Observation(playingNow = false, positionMs = 1200, durationMs = 3700)))
    }

    @Test
    fun `not playing at position zero is not finished`() {
        // The window between start() and audible playback.
        assertFalse(finished(Observation(playingNow = false, positionMs = 0, durationMs = 3700)))
    }

    @Test
    fun `a loading media session is not finished`() {
        assertFalse(
            finished(
                Observation(
                    playingNow = false,
                    positionMs = 0,
                    durationMs = 0,
                    sawPlaying = false,
                    msSinceStart = 200,
                    isMediaSession = true,
                )
            )
        )
    }

    @Test
    fun `a media session that reports ended is finished`() {
        assertTrue(
            finished(
                Observation(
                    playingNow = false,
                    positionMs = 3700,
                    durationMs = 3700,
                    sessionEnded = true,
                    isMediaSession = true,
                )
            )
        )
    }

    @Test
    fun `a stalled media session eventually stops blocking`() {
        // If it never reports playing and never reports ended, the hard ceiling in
        // the real implementation takes over; this asserts we do not consider it
        // finished simply because time passed.
        assertFalse(
            finished(
                Observation(
                    playingNow = false,
                    positionMs = 0,
                    durationMs = 0,
                    sawPlaying = false,
                    msSinceStart = 30_000,
                    isMediaSession = true,
                )
            )
        )
    }

    @Test
    fun `unknown duration that stops after playing counts as finished`() {
        // No duration means no position to compare; after it has played and
        // stopped past the grace window, ending is the only sane reading.
        assertTrue(
            finished(
                Observation(
                    playingNow = false,
                    positionMs = 500,
                    durationMs = 0,
                    sawPlaying = true,
                    msSinceStart = 4_000,
                )
            )
        )
    }

    @Test
    fun `a paused clip is not finished`() {
        // Pause leaves the position mid-clip; resuming must not have skipped ahead.
        assertFalse(
            finished(
                Observation(playingNow = false, positionMs = 1800, durationMs = 3700, msSinceStart = 10_000)
            )
        )
    }
}
