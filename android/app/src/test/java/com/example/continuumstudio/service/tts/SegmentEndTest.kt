package com.example.continuumstudio.service.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins "has this segment actually finished", the decision that was wrong twice
 * in this file and produced audible symptoms in a car.
 *
 * The failure mode is always the same: a `true` while audio is still coming out
 * of the speaker means the next segment starts over the top of the current one,
 * which the user hears as a word being cut off. So every ambiguous state must
 * read as *not* finished - the cost of a wrong `false` is a little latency,
 * while a wrong `true` is broken speech.
 */
class SegmentEndTest {

    private fun obs(
        positionMs: Int = 0,
        durationMs: Int = 0,
        playingNow: Boolean = false,
        sawPlaying: Boolean = true,
        sessionEnded: Boolean = false,
        heldByFocus: Boolean = false,
        msSincePlaying: Long = 5_000L,
        startGraceMs: Long = 700L,
    ) = SegmentEnd.Observation(
        positionMs = positionMs,
        durationMs = durationMs,
        playingNow = playingNow,
        sawPlaying = sawPlaying,
        sessionEnded = sessionEnded,
        heldByFocus = heldByFocus,
        msSincePlaying = msSincePlaying,
        startGraceMs = startGraceMs,
    )

    // --- The regression that started all of this -------------------------

    @Test
    fun `not playing during the start-up window is not finished`() {
        // MediaPlayer.isPlaying is false between start() and audible output.
        // Treating that as an ending cut every segment off mid-word.
        assertFalse(
            SegmentEnd.isFinished(
                obs(playingNow = false, sawPlaying = false, durationMs = 0, msSincePlaying = 50L)
            )
        )
    }

    @Test
    fun `stopped with a known duration short of the end is not finished`() {
        // Buffering mid-clip looks exactly like this.
        assertFalse(
            SegmentEnd.isFinished(
                obs(positionMs = 1_000, durationMs = 4_000, playingNow = false)
            )
        )
    }

    @Test
    fun `reaching the known duration is finished even if the player still says playing`() {
        // Some players report isPlaying==true one poll longer than the position
        // suggests; position is the more truthful signal.
        assertTrue(
            SegmentEnd.isFinished(
                obs(positionMs = 4_000, durationMs = 4_000, playingNow = true)
            )
        )
    }

    @Test
    fun `within tolerance of the duration counts as the end`() {
        assertTrue(
            SegmentEnd.isFinished(
                obs(positionMs = 4_000 - SegmentEnd.END_TOLERANCE_MS, durationMs = 4_000)
            )
        )
    }

    @Test
    fun `explicit session end is finished regardless of anything else`() {
        assertTrue(
            SegmentEnd.isFinished(
                obs(positionMs = 0, durationMs = 0, playingNow = true, sessionEnded = true)
            )
        )
    }

    // --- The bug introduced by adding a focus hold -----------------------

    @Test
    fun `held by another app is never an ending`() {
        // This is the case a focus hold created: position frozen, not playing,
        // no known duration. Without the guard, the "unknown duration and it
        // stopped" rule ended the whole read on the first phone call.
        assertFalse(
            SegmentEnd.isFinished(
                obs(
                    positionMs = 2_000,
                    durationMs = 0,
                    playingNow = false,
                    sawPlaying = true,
                    heldByFocus = true,
                    msSincePlaying = 30_000L,
                )
            )
        )
    }

    @Test
    fun `a hold is not an ending even with a known duration`() {
        assertFalse(
            SegmentEnd.isFinished(
                obs(
                    positionMs = 1_000,
                    durationMs = 9_000,
                    playingNow = false,
                    heldByFocus = true,
                )
            )
        )
    }

    @Test
    fun `a hold defers even a reached end, because position is stale while held`() {
        // The player cannot speak during a hold, so advancing the script on a
        // position that was frozen would drop audio. On resume the position is
        // re-read and the normal end check fires; the caller bounds the wait.
        assertFalse(
            SegmentEnd.isFinished(
                obs(
                    positionMs = 9_000,
                    durationMs = 9_000,
                    heldByFocus = true,
                )
            )
        )
    }

    @Test
    fun `an explicit session end still wins during a hold`() {
        // STATE_ENDED is the player telling us the clip is over, not an inference,
        // so it is safe even while held.
        assertTrue(
            SegmentEnd.isFinished(
                obs(positionMs = 9_000, durationMs = 9_000, heldByFocus = true, sessionEnded = true)
            )
        )
    }

    // --- Unknown duration (the car path) --------------------------------

    @Test
    fun `unknown duration after playing and a grace window is an ending`() {
        // The media session cannot always report a duration; waiting forever
        // would hang the script.
        assertTrue(
            SegmentEnd.isFinished(
                obs(durationMs = 0, playingNow = false, sawPlaying = true, msSincePlaying = 2_000L)
            )
        )
    }

    @Test
    fun `unknown duration inside the grace window is not an ending`() {
        assertFalse(
            SegmentEnd.isFinished(
                obs(durationMs = 0, playingNow = false, sawPlaying = true, msSincePlaying = 100L)
            )
        )
    }

    @Test
    fun `never having played with unknown duration is not an ending`() {
        // Otherwise a session that failed to start would be skipped in silence.
        assertFalse(
            SegmentEnd.isFinished(
                obs(durationMs = 0, playingNow = false, sawPlaying = false, msSincePlaying = 60_000L)
            )
        )
    }

    @Test
    fun `still playing with unknown duration is not an ending`() {
        assertFalse(
            SegmentEnd.isFinished(
                obs(durationMs = 0, playingNow = true, sawPlaying = true)
            )
        )
    }
}
