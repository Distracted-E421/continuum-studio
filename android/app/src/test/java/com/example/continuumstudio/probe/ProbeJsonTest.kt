package com.example.continuumstudio.probe

import com.example.continuumstudio.audio.AudioReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe response format is a contract with whatever client queries the device,
 * so it is pinned here. Written by hand rather than via `org.json` because that is
 * an Android stub in JVM tests - see [ProbeJson].
 */
class ProbeJsonTest {

    @Test
    fun `quotes and escapes a plain string`() {
        assertEquals("\"hello\"", ProbeJson.quote("hello"))
    }

    @Test
    fun `escapes quotes and backslashes`() {
        assertEquals("\"a\\\"b\"", ProbeJson.quote("a\"b"))
        assertEquals("\"a\\\\b\"", ProbeJson.quote("a\\b"))
    }

    @Test
    fun `escapes newlines and tabs`() {
        assertEquals("\"a\\nb\"", ProbeJson.quote("a\nb"))
        assertEquals("\"a\\tb\"", ProbeJson.quote("a\tb"))
        assertEquals("\"a\\rb\"", ProbeJson.quote("a\rb"))
    }

    @Test
    fun `escapes arbitrary control characters`() {
        // A device productName can contain unusual bytes; emitting them raw would
        // produce a body the client cannot parse, losing the evidence the report
        // exists to capture.
        assertEquals("\"\\u0001\"", ProbeJson.quote("\u0001"))
        assertEquals("\"\\u001f\"", ProbeJson.quote("\u001f"))
    }

    @Test
    fun `leaves non-ascii text intact`() {
        // UTF-8 is a valid JSON encoding, so a device name with accents should
        // survive rather than being mangled into escapes.
        assertTrue(ProbeJson.quote("Yue 車載").contains("車載"))
    }

    @Test
    fun `audio report encodes fields and findings`() {
        val report = AudioReport(
            lines = listOf("volume music" to "2/15", "outputs" to "bt-sco(Yue)"),
            situation = "car / Android Auto",
        )
        val json = ProbeJson.audio(report, listOf("music volume is very low"))
        assertTrue(json.contains("\"ok\":\"true\""))
        assertTrue(json.contains("\"situation\":\"car / Android Auto\""))
        assertTrue(json.contains("\"volume music\":\"2/15\""))
        assertTrue(json.contains("\"findings\":[\"music volume is very low\"]"))
    }

    @Test
    fun `audio report with no findings still emits an empty array`() {
        // A client should not have to handle a missing key.
        val json = ProbeJson.audio(AudioReport(emptyList(), "phone"), emptyList())
        assertTrue(json.contains("\"findings\":[]"))
    }

    @Test
    fun `ping reports the version and whether a token is needed`() {
        val json = ProbeJson.ping("1.19.56", 11956, tokensRequired = true)
        assertTrue(json.contains("\"service\":\"continuum-probe\""))
        assertTrue(json.contains("\"appVersion\":\"1.19.56\""))
        assertTrue(json.contains("\"tokensRequired\":\"true\""))
    }

    @Test
    fun `errors carry a machine token and a human message`() {
        val json = ProbeJson.error("unauthorised", "Send the probe token")
        assertTrue(json.contains("\"ok\":\"false\""))
        assertTrue(json.contains("\"error\":\"unauthorised\""))
        assertTrue(json.contains("\"message\":\"Send the probe token\""))
    }

    @Test
    fun `null values are omitted rather than emitted as a wrong type`() {
        // Emitting `null` as a string ("null") would be worse than omitting it: a
        // client would believe a value exists.
        val json = ProbeJson.fields(mapOf("queue" to "3", "activeDialog" to null))
        assertTrue(json.contains("\"queue\":\"3\""))
        assertTrue("null fields must be omitted: $json", !json.contains("activeDialog"))
    }
}
