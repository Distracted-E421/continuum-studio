package com.example.continuumstudio.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the session-lifecycle rules that caused "no audio in the car from any app".
 *
 * A resident media session makes the app a registered Android Auto media source
 * for the whole uptime. Observed in a Subaru head unit (2026-11-18): Continuum
 * Studio appeared highlighted in the Android Auto app bar with no dialog present
 * - it held the car's media channel while playing nothing.
 *
 * The Android service objects cannot be unit-tested here, so the decisions are
 * extracted into [SessionLifetime] and pinned.
 */
class SessionLifetimeTest {

    @Test
    fun `a launch with nothing to say does not request a session`() {
        val life = SessionLifetime()
        assertFalse(life.shouldRequestSession(aboutToSpeak = false))
    }

    @Test
    fun `speaking requests a session`() {
        val life = SessionLifetime()
        assertTrue(life.shouldRequestSession(aboutToSpeak = true))
    }

    @Test
    fun `a session is requested once, not per segment`() {
        val life = SessionLifetime()
        assertTrue(life.shouldRequestSession(aboutToSpeak = true))
        life.markRequested()
        // Later segments must not try to reconnect: that would race the first
        // connection with playback.
        assertFalse(life.shouldRequestSession(aboutToSpeak = true))
    }

    @Test
    fun `a finished read releases the session`() {
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        life.markConnected()
        assertTrue(life.shouldReleaseWhenIdle())
        life.markReleased()
        // Releasing twice would stop a service a newer read had just started.
        assertFalse(life.shouldReleaseWhenIdle())
    }

    @Test
    fun `a failed connection does not latch the requested flag`() {
        // Otherwise one failed connect during a drive would mean no later read
        // ever tries the session again and audio stays on the phone speaker.
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        life.markConnectFailed()
        assertTrue(
            "a failed connection must allow a retry",
            life.shouldRequestSession(aboutToSpeak = true),
        )
    }

    @Test
    fun `a release is allowed before the connection resolves`() {
        // A handshake that never completes must still be releasable, or the
        // service keeps running and holds the car's channel - the failure being
        // fixed. Stopping a service that never started is harmless.
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        assertTrue(life.shouldReleaseWhenIdle())
    }

    @Test
    fun `release after a failed connection does not tear down a stranger`() {
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        life.markConnectFailed()
        assertFalse(life.shouldReleaseWhenIdle())
    }

    @Test
    fun `release after marking released is not repeated`() {
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        life.markConnected()
        assertTrue(life.shouldReleaseWhenIdle())
        life.markReleased()
        // Releasing twice would stop a service a newer read had just started.
        assertFalse(life.shouldReleaseWhenIdle())
    }

    @Test
    fun `a read after a release can start a new session`() {
        val life = SessionLifetime()
        life.shouldRequestSession(aboutToSpeak = true)
        life.markRequested()
        life.shouldReleaseWhenIdle()
        life.markReleased()
        assertTrue(life.shouldRequestSession(aboutToSpeak = true))
    }
}
