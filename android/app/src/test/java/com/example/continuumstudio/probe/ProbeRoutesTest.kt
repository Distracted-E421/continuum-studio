package com.example.continuumstudio.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing and authorisation, which are the security-relevant parts of the probe
 * bridge. Pinned as pure tests because a socket is not needed to get these wrong,
 * and an auth bug here is worse than any diagnostic problem: the server is
 * network-reachable by design.
 */
class ProbeRoutesTest {

    // --- Routing ---------------------------------------------------------

    @Test
    fun `routes resolve by method and path`() {
        assertEquals(ProbeRoute.PING, ProbeRoute.match("GET", "/probe/ping"))
        assertEquals(ProbeRoute.AUDIO, ProbeRoute.match("GET", "/probe/audio"))
        assertEquals(ProbeRoute.STATE, ProbeRoute.match("GET", "/probe/state"))
        assertEquals(ProbeRoute.PROBE_AUDIO, ProbeRoute.match("POST", "/probe/audio/probe"))
    }

    @Test
    fun `method is significant, so a GET cannot trigger playback`() {
        // Playing a tone is a POST for a reason: a stray GET (a browser prefetch, a
        // link preview, an aggressive retry) must never make the phone speak.
        assertNull(ProbeRoute.match("GET", "/probe/audio/probe"))
    }

    @Test
    fun `unknown paths do not resolve`() {
        assertNull(ProbeRoute.match("GET", "/probe/nope"))
        assertNull(ProbeRoute.match("GET", "/"))
        assertNull(ProbeRoute.match("DELETE", "/probe/audio"))
    }

    @Test
    fun `trailing slashes are tolerated`() {
        assertEquals(ProbeRoute.AUDIO, ProbeRoute.match("GET", "/probe/audio/"))
    }

    @Test
    fun `method matching is case-insensitive because clients vary`() {
        assertEquals(ProbeRoute.PING, ProbeRoute.match("get", "/probe/ping"))
    }

    // --- Authorisation ---------------------------------------------------

    @Test
    fun `only ping is reachable without a token`() {
        assertFalse(ProbeRoute.PING.requiresToken)
        assertTrue(ProbeRoute.AUDIO.requiresToken)
        assertTrue(ProbeRoute.STATE.requiresToken)
        assertTrue(ProbeRoute.PROBE_AUDIO.requiresToken)
    }

    @Test
    fun `ping works with no token configured`() {
        // Deliberate: a client must be able to discover the server and learn that it
        // needs a token, rather than concluding the app is broken.
        assertEquals(
            ProbeAuth.Allowed,
            ProbeAuthorizer.authorize(ProbeRoute.PING, null, null),
        )
    }

    @Test
    fun `a data route is refused when no token is configured`() {
        // Refusing is the whole point: the server must not serve data on the
        // strength of "nothing is set" - that would be a fail-open default.
        assertEquals(
            ProbeAuth.NoTokenConfigured,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, "anything", null),
        )
        assertEquals(
            ProbeAuth.NoTokenConfigured,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, "anything", "  "),
        )
    }

    @Test
    fun `a missing token is refused even when one is configured`() {
        assertEquals(
            ProbeAuth.Unauthorised,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, null, "secret"),
        )
        assertEquals(
            ProbeAuth.Unauthorised,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, "", "secret"),
        )
    }

    @Test
    fun `a wrong token is refused`() {
        assertEquals(
            ProbeAuth.Unauthorised,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, "wrong", "secret"),
        )
    }

    @Test
    fun `the right token is allowed`() {
        assertEquals(
            ProbeAuth.Allowed,
            ProbeAuthorizer.authorize(ProbeRoute.AUDIO, "secret", "secret"),
        )
    }

    @Test
    fun `an unmatched route is refused before any token check`() {
        // An unknown path must not be distinguishable from an unauthorised one, so
        // probing for endpoints gains nothing.
        assertEquals(
            ProbeAuth.Unauthorised,
            ProbeAuthorizer.authorize(null, "secret", "secret"),
        )
    }
}
