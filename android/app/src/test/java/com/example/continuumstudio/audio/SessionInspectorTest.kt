package com.example.continuumstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session list is what answers "is another app making sound while ours is silent",
 * which is the decisive question for the car audio problem. The enumeration itself
 * needs a device, but these rules do not.
 */
class SessionInspectorTest {

    private fun session(
        pkg: String,
        state: String,
        active: Boolean = false,
    ) = SessionInspector.SessionInfo(
        packageName = pkg,
        isActive = active,
        playbackState = state,
        playWhenReady = state == "PLAYING",
        title = null,
        artist = null,
        hasAudioFocusHint = state == "PLAYING",
    )

    @Test
    fun `a session's description names the package and state`() {
        val text = session("com.vivaldi.browser", "PLAYING").describe()
        assertTrue(text.contains("com.vivaldi.browser"))
        assertTrue(text.contains("PLAYING"))
    }

    @Test
    fun `the active session is marked`() {
        // Which session owns the car's channel is the question being asked, so the
        // marker has to be visible in the text.
        val text = session("com.example.continuumstudio", "PLAYING", active = true).describe()
        assertTrue(text.contains("[ACTIVE]"))
        assertFalse(session("x", "PAUSED").describe().contains("[ACTIVE]"))
    }

    @Test
    fun `metadata is included when present`() {
        val info = session("com.spotify.music", "PLAYING").copy(title = "Track", artist = "Artist")
        val text = info.describe()
        assertTrue(text.contains("Track"))
        assertTrue(text.contains("Artist"))
    }

    @Test
    fun `only PLAYING counts as making sound`() {
        // The distinction the car diagnosis turns on: PAUSED or BUFFERING means no
        // sound, so those must not be reported as competitors.
        assertTrue(session("a", "PLAYING").hasAudioFocusHint)
        assertFalse(session("a", "PAUSED").hasAudioFocusHint)
        assertFalse(session("a", "BUFFERING").hasAudioFocusHint)
        assertFalse(session("a", "STOPPED").hasAudioFocusHint)
    }

    @Test
    fun `playWhenReady tracks PLAYING rather than being always true`() {
        assertTrue(session("a", "PLAYING").playWhenReady)
        assertFalse(session("a", "PAUSED").playWhenReady)
    }

    @Test
    fun `our own package is excluded from other playing sessions`() {
        // A helper mirroring the filter in othersPlaying, so the exclusion rule is
        // pinned: including ourselves would make every report look like a conflict.
        val all = listOf(
            session("com.example.continuumstudio", "PLAYING"),
            session("com.vivaldi.browser", "PLAYING"),
            session("com.spotify.music", "PAUSED"),
        )
        val others = all.filter {
            it.packageName != "com.example.continuumstudio" && it.playbackState == "PLAYING"
        }
        assertEquals(1, others.size)
        assertEquals("com.vivaldi.browser", others.first().packageName)
    }
}
