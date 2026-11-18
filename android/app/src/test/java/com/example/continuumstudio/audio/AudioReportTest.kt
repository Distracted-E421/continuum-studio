package com.example.continuumstudio.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio report exists so a "works on the phone, silent in the car" problem can
 * be answered by data rather than guesswork. The useful part is the *difference*
 * between two reports, so that is what is pinned here - the collector itself needs
 * a device and cannot be unit-tested.
 */
class AudioReportTest {

    private fun report(situation: String, vararg pairs: Pair<String, String>) =
        AudioReport(lines = pairs.toList(), situation = situation)

    @Test
    fun `identical reports report no differences`() {
        val a = report("car", "outputs" to "bt-a2dp", "volume music" to "8/15")
        val b = report("phone", "outputs" to "bt-a2dp", "volume music" to "8/15")
        assertTrue(AudioReport.describeDifferences(a, b).contains("No differences"))
    }

    @Test
    fun `a changed value is named with both sides`() {
        val car = report("car", "outputs" to "bt-a2dp(Speaker)")
        val phone = report("phone", "outputs" to "speaker(Phone)")
        val text = AudioReport.describeDifferences(car, phone)
        // Both values must appear, or the reader cannot tell which way it moved.
        assertTrue("missing old value: $text", text.contains("speaker(Phone)"))
        assertTrue("missing new value: $text", text.contains("bt-a2dp(Speaker)"))
        assertTrue("missing key: $text", text.contains("outputs"))
    }

    @Test
    fun `a key present in only one report is shown as absent`() {
        // A route that exists in the car and not on the phone is the interesting
        // case, so the diff must not silently skip a key missing from one side.
        val car = report("car", "outputs" to "bt-sco(X)", "car mode" to "CAR")
        val phone = report("phone", "outputs" to "speaker(Y)")
        val text = AudioReport.describeDifferences(car, phone)
        assertTrue("should mark car mode as absent on the phone: $text", text.contains("car mode"))
        assertTrue(text.contains("(absent)"))
    }

    @Test
    fun `only the changed keys are listed`() {
        val car = report("car", "a" to "same", "b" to "car-value")
        val phone = report("phone", "a" to "same", "b" to "phone-value")
        val diffs = car.differencesFrom(phone)
        assertEquals(1, diffs.size)
        assertTrue(diffs.first().startsWith("b:"))
    }

    @Test
    fun `rendered report includes the situation and every line`() {
        // The situation is what makes two reports comparable later; without it a
        // shared file cannot be interpreted.
        val r = report("car / Android Auto", "outputs" to "bt-a2dp", "volume music" to "3/15")
        val text = r.render()
        assertTrue(text.contains("car / Android Auto"))
        assertTrue(text.contains("outputs: bt-a2dp"))
        assertTrue(text.contains("volume music: 3/15"))
    }

    @Test
    fun `describe is symmetric in what it reports, only direction differs`() {
        // Swapping the arguments must report the same key, not lose it.
        val car = report("car", "x" to "1")
        val phone = report("phone", "x" to "2")
        assertTrue(AudioReport.describeDifferences(car, phone).contains("x"))
        assertTrue(AudioReport.describeDifferences(phone, car).contains("x"))
    }
}
