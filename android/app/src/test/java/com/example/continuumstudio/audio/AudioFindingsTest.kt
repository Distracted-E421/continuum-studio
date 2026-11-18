package com.example.continuumstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the interpretations that matter, using values from a real report captured
 * in a Subaru (2026-11-18). That report printed clean-looking numbers except for
 * `volume music: 2/15` and `notifications: 0/15`, and a silent car - so the point
 * of these tests is that the obvious suspects get called out in plain language.
 */
class AudioFindingsTest {

    private fun report(vararg pairs: Pair<String, String>) =
        AudioReport(lines = pairs.toList(), situation = "car")

    /** A healthy baseline to vary one field at a time. */
    private fun healthy(vararg overrides: Pair<String, String>): AudioReport {
        val base = mutableMapOf(
            "volume music" to "8/15",
            "volume notifications" to "7/15",
            "volume system" to "6/15",
            "outputs" to "speaker(SM-S928U1); bt-a2dp(Car)",
            "audio mode" to "NORMAL",
            "isSpeakerphoneOn" to "false",
            "car mode (UiModeManager)" to "CAR (Android Auto)",
            "session connected" to "false",
            "isMusicActive" to "0".let { "false" },
        )
        overrides.forEach { (k, v) -> base[k] = v }
        return AudioReport(lines = base.toList(), situation = "car")
    }

    @Test
    fun `a healthy report has no findings`() {
        val findings = AudioFindings.inspect(healthy())
        assertEquals("unexpected findings: $findings", emptyList<String>(), findings)
    }

    @Test
    fun `zero notification volume is called out by name`() {
        // Taken verbatim from the car report: 0/15 on notifications.
        val findings = AudioFindings.inspect(healthy("volume notifications" to "0/15"))
        assertTrue(findings.any { it.contains("notifications") && it.contains("zero") })
    }

    @Test
    fun `near-zero music volume is called out as barely audible`() {
        // 2/15 in the real report. Audible on a phone held to the ear, inaudible
        // over road noise, which is why the threshold is not simply zero.
        val findings = AudioFindings.inspect(healthy("volume music" to "2/15"))
        assertTrue(findings.any { it.contains("music") && it.contains("very low") })
    }

    @Test
    fun `a muted stream is distinguished from a low one`() {
        val findings = AudioFindings.inspect(healthy("volume music" to "5/15 MUTED"))
        assertTrue(findings.any { it.contains("MUTED") })
        assertFalse(findings.any { it.contains("very low") })
    }

    @Test
    fun `remote-submix is explained rather than left as a number`() {
        // The real report listed two `type25` outputs, which only mean something
        // once resolved to remote-submix = a remote capture path.
        val findings = AudioFindings.inspect(
            healthy("outputs" to "speaker(X); type25(remote-submix)")
        )
        assertTrue(findings.any { it.contains("remote-submix") })
    }

    @Test
    fun `sco without a2dp is reported as no media route over bluetooth`() {
        // Also from the real report: bt-sco present, bt-a2dp absent. Media cannot
        // play over SCO, so this is a route problem, not a volume problem.
        val findings = AudioFindings.inspect(
            healthy("outputs" to "speaker(X); bt-sco(Yue)")
        )
        assertTrue(
            "should explain the missing media route: $findings",
            findings.any { it.contains("A2DP") },
        )
    }

    @Test
    fun `a non-normal audio mode is flagged`() {
        val findings = AudioFindings.inspect(healthy("audio mode" to "IN_COMMUNICATION"))
        assertTrue(findings.any { it.contains("IN_COMMUNICATION") })
    }

    @Test
    fun `an idle connected session is flagged as possibly holding the channel`() {
        val findings = AudioFindings.inspect(
            healthy("session connected" to "true", "isMusicActive" to "false")
        )
        assertTrue(findings.any { it.contains("holding the car") })
    }

    @Test
    fun `missing car mode is reported because the layout depends on it`() {
        val findings = AudioFindings.inspect(healthy("car mode (UiModeManager)" to "NORMAL"))
        assertTrue(findings.any { it.contains("car mode") })
    }

    @Test
    fun `no speaker and no a2dp and no submix is reported as no output`() {
        val findings = AudioFindings.inspect(healthy("outputs" to "earpiece(X)"))
        assertTrue(findings.any { it.contains("No usable media output") })
    }
}
