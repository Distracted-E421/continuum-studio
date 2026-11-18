package com.example.continuumstudio.audio

import com.example.continuumstudio.audio.MultiSoundController.Companion.FocusMode
import com.example.continuumstudio.audio.MultiSoundController.Companion.parseFocusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for parsing `cmd appops get ... TAKE_AUDIO_FOCUS`.
 *
 * This op decides whether another app can steal audio focus from us, which is the
 * mechanism behind car audio dropping out. The previous check was
 * `!output.contains("ignore")`: it gave the right answer for the outputs seen on
 * one device, but reported "allowed" for any unrecognised format - and "allowed"
 * is precisely the state that breaks car playback.
 *
 * Output shapes are from One UI 9 / Android 17.
 */
class FocusModeParsingTest {

    @Test
    fun `ignore is recognised`() {
        assertEquals(FocusMode.IGNORE, parseFocusMode("TAKE_AUDIO_FOCUS: ignore"))
    }

    @Test
    fun `default is recognised and is not ignore`() {
        assertEquals(FocusMode.DEFAULT, parseFocusMode("TAKE_AUDIO_FOCUS: default"))
    }

    @Test
    fun `allow is recognised`() {
        assertEquals(FocusMode.ALLOW, parseFocusMode("TAKE_AUDIO_FOCUS: allow"))
    }

    @Test
    fun `an unset op is reported as unset`() {
        // `cmd appops get` prints this when the op was never set for the package.
        assertEquals(FocusMode.UNSET, parseFocusMode("No operations."))
    }

    @Test
    fun `case and surrounding whitespace do not change the result`() {
        assertEquals(FocusMode.IGNORE, parseFocusMode("  TAKE_AUDIO_FOCUS:  IGNORE \n"))
    }

    @Test
    fun `unrecognised output is null rather than a guess`() {
        // Guessing "allowed" is the dangerous direction: it is the state that
        // breaks car audio, so unknown must stay unknown.
        assertNull(parseFocusMode("something unexpected"))
        assertNull(parseFocusMode(""))
        assertNull(parseFocusMode("TAKE_AUDIO_FOCUS:"))
    }

    @Test
    fun `only the first line is read as the mode`() {
        // Extra context lines must not be mistaken for the mode.
        val out = "TAKE_AUDIO_FOCUS: ignore\nPackage: com.spotify.music"
        assertEquals(FocusMode.IGNORE, parseFocusMode(out))
    }
}
