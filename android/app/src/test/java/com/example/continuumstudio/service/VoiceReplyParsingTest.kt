package com.example.continuumstudio.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for what a spoken reply resolves to.
 *
 * This is the last link in the chain the dialog page starts: the script says
 * "Option 2", the user says "option two", and the reply has to become the right
 * option's *value*. Getting this wrong sends the wrong answer, which is worse
 * than not hearing anything at all.
 *
 * The parser is private inside AutoReplyService, so the logic is mirrored here.
 * If the mirrors drift the tests stop proving anything - hence the comment at
 * the top of each helper pointing at its original.
 */
class VoiceReplyParsingTest {

    // Mirrors AutoReplyService.normalizeVoiceInput
    private fun normalize(text: String): String = text
        .trim()
        .lowercase()
        .replace(Regex("^(um+|uh+|like|so|well|okay so)\\s+"), "")
        .replace(Regex("[,.]"), "")
        .replace(Regex("\\s*please$"), "")
        .trim()

    // Mirrors AutoReplyService.parseOrdinalOrNumber
    private fun parseNumber(text: String): Int? {
        val ordinals = mapOf(
            "first" to 1, "1st" to 1, "one" to 1,
            "second" to 2, "2nd" to 2, "two" to 2,
            "third" to 3, "3rd" to 3, "three" to 3,
            "fourth" to 4, "4th" to 4, "four" to 4,
            "fifth" to 5, "5th" to 5, "five" to 5,
        )
        ordinals[text]?.let { return it }
        val patterns = listOf(
            Regex("(the\\s+)?(\\w+)(\\s+one)?"),
            Regex("option\\s+(\\w+)"),
            Regex("number\\s+(\\w+)"),
            Regex("choice\\s+(\\w+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(text) ?: continue
            val groups = match.groupValues.filter { it.isNotBlank() && it != text }
            for (group in groups) {
                ordinals[group.trim()]?.let { return it }
                // Bare digit inside a phrase ("option 2"). The ordinals map only
                // holds "2nd" and "two", so this branch is what makes "option 2"
                // work. It exists in the real parser; omitting it here made this
                // test fail for the wrong reason.
                group.trim().toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    // Mirrors the numbered part of parseChoiceReply
    private val values = listOf("continue", "cancel", "retry")
    private fun resolve(reply: String): String? {
        val n = normalize(reply)
        n.toIntOrNull()?.let { if (it in 1..values.size) return values[it - 1] }
        parseNumber(n)?.let { if (it in 1..values.size) return values[it - 1] }
        return null
    }

    @Test
    fun `the exact phrasing the script speaks is understood`() {
        // The script says "Option 1. Continue", so this is the expected reply.
        assertEquals("cancel", resolve("option two"))
        assertEquals("cancel", resolve("Option 2"))
        assertEquals("continue", resolve("option one"))
    }

    @Test
    fun `bare digits and words both work`() {
        assertEquals("continue", resolve("1"))
        assertEquals("cancel", resolve("two"))
        assertEquals("retry", resolve("3"))
    }

    @Test
    fun `ordinals work`() {
        assertEquals("cancel", resolve("the second one"))
        assertEquals("retry", resolve("third"))
    }

    @Test
    fun `voice artifacts do not break the match`() {
        assertEquals("cancel", resolve("um, option two please"))
        assertEquals("continue", resolve("So option one."))
    }

    @Test
    fun `number matching is the pattern that actually fires`() {
        // Regression guard: "option two" must not be swallowed by the generic
        // "(the )?(\w+)( one)?" pattern before the number is recognised.
        assertEquals(2, parseNumber("option two"))
        assertEquals(2, parseNumber("number two"))
        assertEquals(2, parseNumber("choice two"))
    }

    @Test
    fun `out of range numbers do not resolve to a value`() {
        // Better to fall back to server-side handling than to answer wrongly.
        assertEquals(null, resolve("9"))
        assertEquals(null, resolve("option nine"))
    }
}
