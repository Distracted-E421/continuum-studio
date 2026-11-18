package com.example.continuumstudio.service

/**
 * When the media session should exist.
 *
 * The session is what registers this app with Android Auto, and a session that
 * exists while nothing is playing can hold a car's media channel silently. So the
 * rules are: create it only when there is something to say, keep it for the
 * whole read, and hand it back when the read is over.
 *
 * Extracted from [DialogTTSHelper] so those rules are testable - they are not
 * observable without a car, and getting them wrong already cost a debugging
 * session.
 *
 * Deliberately tracks "we asked for it" rather than "it connected". A connection
 * that never resolves must still be releasable, otherwise a hung handshake leaves
 * the service running and the car's channel held - the exact failure being fixed.
 * Stopping a service that never started is harmless, so keying on the request is
 * the safe direction.
 *
 * Not thread-safe by design: the helper only touches it from the main thread.
 */
class SessionLifetime {

    private var requested = false

    /**
     * Whether to bring the session up now.
     *
     * @param aboutToSpeak false for anything that is not an actual read, so
     *        launching the app or receiving a dialog while reading is off never
     *        registers us with the car.
     */
    fun shouldRequestSession(aboutToSpeak: Boolean): Boolean =
        aboutToSpeak && !requested

    /** Record that a connection attempt is under way. */
    fun markRequested() {
        requested = true
    }

    /** Record a working connection. */
    fun markConnected() {
        requested = true
    }

    /**
     * Record that the connection failed.
     *
     * Clears the request as well: leaving it set would mean a single failed
     * connect during a drive stopped every later read from trying, and audio
     * would quietly stay on the phone speaker.
     */
    fun markConnectFailed() {
        requested = false
    }

    /** Whether the read has finished and the session should be handed back. */
    fun shouldReleaseWhenIdle(): Boolean = requested

    /** Record that the session was released. */
    fun markReleased() {
        requested = false
    }
}
