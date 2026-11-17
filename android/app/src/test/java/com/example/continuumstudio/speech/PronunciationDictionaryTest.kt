package com.example.continuumstudio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the dictionary's contract with the compiler.
 *
 * These exist because the dictionary is the one place where a wrong entry is
 * worse than no entry: an expansion that mangles an ordinary word ("class"
 * becoming "cla seconds") would be heard on every dialog.
 */
class PronunciationDictionaryTest {

    @Test
    fun `every entry has a non-blank spoken form`() {
        PronunciationDictionary.allEntries().forEach { (written, spoken, _) ->
            assertTrue("'$written' has blank spoken form", spoken.isNotBlank())
            assertTrue("'$written' key is blank", written.isNotBlank())
        }
    }

    @Test
    fun `entry kinds are only pronunciation or expansion`() {
        PronunciationDictionary.allEntries().forEach { (_, _, kind) ->
            assertTrue("unexpected kind '$kind'", kind == "pronunciation" || kind == "expansion")
        }
    }

    @Test
    fun `no duplicate keys across the two tables`() {
        val keys = PronunciationDictionary.allEntries().map { it.first }
        assertEquals(keys.size, keys.distinct().size)
    }

    @Test
    fun `phonetic spelling never contains digits or symbols that read oddly`() {
        PronunciationDictionary.allEntries()
            .filter { it.third == "pronunciation" }
            .forEach { (written, spoken, _) ->
                assertTrue(
                    "'$written' -> '$spoken' should be a plain respelling",
                    spoken.all { it.isLetter() || it == '-' || it == ' ' },
                )
            }
    }

    @Test
    fun `unit splitting requires a numeric prefix`() {
        // The guard that matters: a word must never lose its ending.
        listOf("class", "hours", "hours", "business", "ms", "hours").forEach {
            assertEquals("'$it' must not split", null, PronunciationDictionary.splitUnit(it))
        }
        assertEquals("5" to "seconds", PronunciationDictionary.splitUnit("5s"))
        assertEquals("1.5" to "seconds", PronunciationDictionary.splitUnit("1.5s"))
        assertEquals("64" to "gigabytes", PronunciationDictionary.splitUnit("64gb"))
    }

    @Test
    fun `lookup is case insensitive in both directions`() {
        assertEquals(
            PronunciationDictionary.lookup("nixos")?.spoken,
            PronunciationDictionary.lookup("NixOS")?.spoken,
        )
    }

    @Test
    fun `phonetic entries win over expansions`() {
        // nixos is both a project name and would otherwise be letter-spelled.
        assertEquals("nicks-oh-ess", PronunciationDictionary.lookup("nixos")?.spoken)
    }
}

/** Tests that the dictionary is actually applied when a dialog is compiled. */
class DictionaryInSpeechTest {

    private fun spoken(text: String) = SpeechScriptCompiler.spoken(text)

    @Test
    fun `project names are respelled in real prose`() {
        val out = spoken("Synapsix is talking to Forgejo.")
        assertTrue(out.contains("sin-app-sicks"))
        assertTrue(out.contains("for-jay-oh"))
    }

    @Test
    fun `file extensions become file types`() {
        // The base name is spoken as written; only the extension becomes words.
        assertTrue(spoken("Edit build.gradle.kts now").contains("Gradle Kotlin script"))
        assertTrue(spoken("Open DialogScreen.kt please").contains("Kotlin file"))
    }

    @Test
    fun `compound extensions win over their suffix`() {
        // ".gradle.kts" must not degrade to the generic ".kts" mapping.
        val out = spoken("build.gradle.kts")
        assertTrue(out, out.contains("Gradle Kotlin script"))
        assertTrue(out, !out.contains("build.gradle Kotlin script"))
    }

    @Test
    fun `abbreviations with dots still expand`() {
        assertTrue(spoken("Use e.g. this").contains("for example"))
        assertTrue(spoken("All of it, etc.").contains("et cetera"))
    }

    @Test
    fun `case is preserved for ordinary words`() {
        // A regression here would lowercase the whole dialog.
        val out = spoken("Alpha Beta Gamma.")
        assertTrue(out.contains("Alpha") && out.contains("Beta"))
    }

    @Test
    fun `snake_case identifiers are not treated as emphasis`() {
        val out = spoken("call submit_answer twice")
        assertTrue("underscores must survive", out.contains("submit_answer"))
    }
}
