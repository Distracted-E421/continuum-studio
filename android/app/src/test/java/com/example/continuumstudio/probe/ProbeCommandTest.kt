package com.example.continuumstudio.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `dumpsys` allowlist is a security boundary, not a convenience.
 *
 * The probe server listens on a network. If the command were caller-supplied this
 * would be remote code execution on the owner's phone - the exact thing this session
 * refused to do on the Synapsix daemon. So a request may only *name* one of a fixed
 * set, and these tests pin that.
 */
class ProbeCommandTest {

    @Test
    fun `known command keys resolve`() {
        assertEquals(ProbeCommand.AUDIO, ProbeCommand.byKey("audio"))
        assertEquals(ProbeCommand.MEDIA_SESSION, ProbeCommand.byKey("media_session"))
        assertEquals(ProbeCommand.USB, ProbeCommand.byKey("usb"))
        assertEquals(ProbeCommand.SERVICES, ProbeCommand.byKey("services"))
        assertEquals(ProbeCommand.MEDIA_ROUTING, ProbeCommand.byKey("media_routing"))
    }

    @Test
    fun `unknown keys do not resolve, including shell-looking ones`() {
        // A client trying to get a command in through the key is exactly the attack
        // this prevents.
        assertNull(ProbeCommand.byKey("rm -rf /"))
        assertNull(ProbeCommand.byKey("audio; reboot"))
        assertNull(ProbeCommand.byKey("$(id)"))
        assertNull(ProbeCommand.byKey(null))
        assertNull(ProbeCommand.byKey(""))
    }

    @Test
    fun `every command is read-only`() {
        // The read endpoints must be safe to hit at any moment, which means no
        // command may mutate state. Checked structurally rather than by review.
        val mutating = listOf("set ", "put ", "rm ", "kill", "reboot", "pm ", "am start")
        ProbeCommand.entries.forEach { cmd ->
            val lowered = cmd.command.lowercase()
            assertTrue(
                "${cmd.key} looks mutating: ${cmd.command}",
                mutating.none { lowered.contains(it) },
            )
            assertTrue(
                "${cmd.key} should be a dumpsys or a read: ${cmd.command}",
                lowered.startsWith("dumpsys"),
            )
        }
    }

    @Test
    fun `keys are case and whitespace tolerant`() {
        assertEquals(ProbeCommand.AUDIO, ProbeCommand.byKey(" AUDIO "))
        assertEquals(ProbeCommand.MEDIA_SESSION, ProbeCommand.byKey("Media_Session"))
    }

    @Test
    fun `each command says what question it answers`() {
        // The point of exposing these is answering "who holds the car's audio
        // channel" - a command with no stated purpose is one nobody will reach for.
        ProbeCommand.entries.forEach { cmd ->
            assertTrue("${cmd.key} needs an 'answers' sentence", cmd.answers.length > 20)
        }
    }

    // --- Transcript handling ---------------------------------------------

    @Test
    fun `short transcripts pass through untouched`() {
        val text = "line one\nline two"
        assertEquals(text, ProbeTranscript.trim(text, max = 100))
    }

    @Test
    fun `long transcripts keep the head and the tail`() {
        // dumpsys audio is thousands of lines; the interesting parts are the summary
        // at the top and the most recent events at the bottom.
        val text = buildString {
            append("HEADMARK\n")
            repeat(5000) { append("padding line $it\n") }
            append("TAILMARK")
        }
        val trimmed = ProbeTranscript.trim(text, max = 1000)
        assertTrue("head must survive: ${trimmed.take(40)}", trimmed.contains("HEADMARK"))
        assertTrue("tail must survive", trimmed.contains("TAILMARK"))
        assertTrue("must say how much was dropped", trimmed.contains("characters omitted"))
        assertTrue("must not exceed the cap by much", trimmed.length < 1200)
    }

    @Test
    fun `matching pulls only lines that mention a needle`() {
        val dump = """
            audio mode: NORMAL
            focus stack: com.vivaldi.browser
            focus stack: com.example.continuumstudio
            stream music volume 2
        """.trimIndent()
        val hits = ProbeTranscript.matching(dump, listOf("vivaldi"))
        assertEquals(1, hits.size)
        assertTrue(hits.first().contains("vivaldi"))
    }

    @Test
    fun `matching is case-insensitive and multi-needle`() {
        val dump = "FOCUS: Vivaldi\nother\nFocus: Continuum"
        val hits = ProbeTranscript.matching(dump, listOf("vivaldi", "continuum"))
        assertEquals(2, hits.size)
    }

    @Test
    fun `no needles means no filtering, not everything`() {
        // Returning everything would be the easy mistake and would bury the answer.
        assertTrue(ProbeTranscript.matching("a\nb\nc", emptyList()).isEmpty())
    }

    @Test
    fun `no match is an empty result, which is itself informative`() {
        // "nothing mentions vivaldi" is an answer; falling back to the whole dump
        // would hide it.
        assertTrue(ProbeTranscript.matching("nothing relevant", listOf("vivaldi")).isEmpty())
    }
}
