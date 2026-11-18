package com.example.continuumstudio.service.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the audio-focus decision, which is what decides whether a dialog
 * interrupts Vivaldi.
 *
 * This is deliberately a unit test: the behaviour it protects was previously
 * only verifiable in a car, which is why two real bugs lived there for a while.
 *
 *  1. `hasFocus` was never reset on `AUDIOFOCUS_LOSS`, so once focus was lost
 *     mid-dialog every later segment skipped requesting it and played at full
 *     volume over the music with no error.
 *  2. Every loss called `pause()`, the same call as the user's own pause, so a
 *     single navigation prompt ended the read permanently.
 */
class FocusPolicyTest {

    @Test
    fun `unknown or missing stored value falls back to overlap`() {
        // Fail-safe direction: overlap tells the other app nothing, so a corrupt
        // preference can never leave music paused.
        assertEquals(FocusPolicy.OVERLAP, FocusPolicy.parse(null))
        assertEquals(FocusPolicy.OVERLAP, FocusPolicy.parse(""))
        assertEquals(FocusPolicy.OVERLAP, FocusPolicy.parse("   "))
        assertEquals(FocusPolicy.OVERLAP, FocusPolicy.parse("nonsense"))
    }

    @Test
    fun `stored values round-trip`() {
        FocusPolicy.entries.forEach { policy ->
            assertEquals(policy, FocusPolicy.parse(policy.storedValue))
        }
    }

    @Test
    fun `parsing is case and whitespace tolerant`() {
        assertEquals(FocusPolicy.DUCK, FocusPolicy.parse(" DUCK "))
        assertEquals(FocusPolicy.PAUSE, FocusPolicy.parse("Pause"))
    }

    @Test
    fun `only overlap declines to request focus`() {
        // The whole point of overlap: AudioManager is never consulted, so no
        // other app is asked to react.
        assertFalse(FocusPolicy.OVERLAP.requestsFocus)
        assertTrue(FocusPolicy.DUCK.requestsFocus)
        assertTrue(FocusPolicy.PAUSE.requestsFocus)
    }

    // --- Responder -------------------------------------------------------

    @Test
    fun `overlap ignores every focus change`() {
        // If we never asked for focus, acting on a change is the bug: it would
        // pause speech for an event we are not part of.
        FocusChange.entries.forEach { change ->
            assertEquals(
                "overlap must ignore $change",
                FocusResponse.IGNORE,
                FocusResponder.respond(FocusPolicy.OVERLAP, change),
            )
        }
    }

    @Test
    fun `ducking policy treats a duck request as expected, not an interruption`() {
        assertEquals(
            FocusResponse.IGNORE,
            FocusResponder.respond(FocusPolicy.DUCK, FocusChange.LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun `a real loss holds speech rather than ending it`() {
        // Distinct from the user's pause, so it can be resumed automatically.
        // Calling the same pause() here is what made one nav prompt end a dialog.
        assertEquals(
            FocusResponse.HOLD_FOR_FOCUS,
            FocusResponder.respond(FocusPolicy.DUCK, FocusChange.LOSS_TRANSIENT),
        )
        assertEquals(
            FocusResponse.HOLD_FOR_FOCUS,
            FocusResponder.respond(FocusPolicy.PAUSE, FocusChange.LOSS),
        )
    }

    @Test
    fun `gaining focus reports a resumable state`() {
        // The player only acts on this when it was holding, so a user's own pause
        // is never undone by a stray GAIN.
        assertEquals(
            FocusResponse.RESUME_AFTER_HOLD,
            FocusResponder.respond(FocusPolicy.PAUSE, FocusChange.GAIN),
        )
    }

    @Test
    fun `a notification blip never interrupts an exclusive request`() {
        // Under PAUSE we hold exclusive focus, so a short duck request from
        // another app costs nothing audible; cutting a sentence is worse.
        assertEquals(
            FocusResponse.IGNORE,
            FocusResponder.respond(FocusPolicy.PAUSE, FocusChange.LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    // --- Resuming after a hold -------------------------------------------

    @Test
    fun `a hold resumes when focus comes back and the user has not paused`() {
        assertTrue(HoldResume.shouldResume(heldByFocus = true, userPaused = false))
    }

    @Test
    fun `a pause pressed during the hold is not undone by focus returning`() {
        // The user silencing the dialog mid-call must survive the call ending.
        // Resuming on any GAIN was the obvious implementation and it is wrong.
        assertFalse(HoldResume.shouldResume(heldByFocus = true, userPaused = true))
    }

    @Test
    fun `a plain user pause is never resumed by a stray gain`() {
        assertFalse(HoldResume.shouldResume(heldByFocus = false, userPaused = true))
        assertFalse(HoldResume.shouldResume(heldByFocus = false, userPaused = false))
    }
}
