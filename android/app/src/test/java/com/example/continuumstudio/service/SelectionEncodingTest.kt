package com.example.continuumstudio.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for encoding a queued dialog selection.
 *
 * A queued answer is replayed to the daemon later, against a `serde_json::Value`
 * field. Encoding the wrong *type* is silent: the answer arrives and is accepted,
 * just with the wrong shape. That is why these check the parsed JSON rather than
 * the string.
 */
class SelectionEncodingTest {

    private fun parse(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `a string stays a string`() {
        val encoded = encodeSelection("continue")
        val parsed = parse(encoded) as JsonPrimitive
        assertEquals("continue", parsed.content)
        assertTrue(parsed.isString)
    }

    @Test
    fun `booleans stay booleans`() {
        // A confirm dialog's answer must be true/false, not "true".
        assertEquals(true, (parse(encodeSelection(true)) as JsonPrimitive).boolean)
        assertEquals(false, (parse(encodeSelection(false)) as JsonPrimitive).boolean)
    }

    @Test
    fun `numbers stay numbers`() {
        val parsed = parse(encodeSelection(42)) as JsonPrimitive
        assertTrue("must not be quoted", !parsed.isString)
        assertEquals("42", parsed.content)
    }

    @Test
    fun `a multi-select answer becomes an array, not a quoted array`() {
        // The regression this exists for: previously this produced the *string*
        // "[\"a\",\"b\"]" and the daemon replayed it as one string.
        val encoded = encodeSelection(listOf("a", "b"))
        val parsed = parse(encoded)
        assertTrue("must be a JSON array, was: $encoded", parsed is JsonArray)
        assertEquals(2, (parsed as JsonArray).size)
        assertEquals("a", parsed[0].let { (it as JsonPrimitive).content })
        assertEquals("b", parsed[1].let { (it as JsonPrimitive).content })
    }

    @Test
    fun `an empty list is an empty array not an empty string`() {
        val parsed = parse(encodeSelection(emptyList<String>()))
        assertTrue(parsed is JsonArray)
        assertEquals(0, (parsed as JsonArray).size)
    }

    @Test
    fun `quotes and backslashes in values do not break the JSON`() {
        val tricky = """He said "yes" \ then left"""
        val parsed = parse(encodeSelection(tricky)) as JsonPrimitive
        assertEquals(tricky, parsed.content)
    }

    @Test
    fun `newlines and tabs are escaped`() {
        val tricky = "line one\nline two\ttabbed"
        val parsed = parse(encodeSelection(tricky)) as JsonPrimitive
        assertEquals(tricky, parsed.content)
    }

    @Test
    fun `a list containing quotes stays valid JSON`() {
        val encoded = encodeSelection(listOf("""say "hi"""", "b"))
        val parsed = parse(encoded) as JsonArray
        assertEquals("""say "hi"""", (parsed[0] as JsonPrimitive).content)
    }

    @Test
    fun `floats from a slider encode as numbers`() {
        val parsed = parse(encodeSelection(37.5f)) as JsonPrimitive
        assertTrue(!parsed.isString)
        assertEquals(37.5, parsed.content.toDouble(), 0.001)
    }
}
