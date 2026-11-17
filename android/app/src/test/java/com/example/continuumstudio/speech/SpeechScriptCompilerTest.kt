package com.example.continuumstudio.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the speech script compiler.
 *
 * These pin the behaviours that were measured against the live EasyVoice API,
 * so a future refactor cannot quietly reintroduce markup that the service would
 * read aloud.
 */
class SpeechScriptCompilerTest {

    private fun spokenOf(text: String) = SpeechScriptCompiler.spoken(text)

    // --- Dictionary -------------------------------------------------------

    @Test
    fun `project names are respelled for pronunciation`() {
        assertEquals("sin-app-sicks", PronunciationDictionary.lookup("Synapsix")?.spoken)
        // Case-insensitive, because agents capitalise inconsistently.
        assertEquals("sin-app-sicks", PronunciationDictionary.lookup("SYNAPSIX")?.spoken)
    }

    @Test
    fun `file extensions are said as file types`() {
        assertEquals("Kotlin file", PronunciationDictionary.lookup(".kt")?.spoken)
        assertEquals("Nix file", PronunciationDictionary.lookup(".nix")?.spoken)
    }

    @Test
    fun `trailing units split off numbers only`() {
        assertEquals("8" to "seconds", PronunciationDictionary.splitUnit("8s"))
        assertEquals("300" to "milliseconds", PronunciationDictionary.splitUnit("300ms"))
        // Must not mangle ordinary words that happen to end in a unit letter.
        assertEquals(null, PronunciationDictionary.splitUnit("class"))
        assertEquals(null, PronunciationDictionary.splitUnit("hours"))
    }

    @Test
    fun `unknown words pass through untouched`() {
        assertEquals(null, PronunciationDictionary.lookup("refrigerator"))
        assertFalse(PronunciationDictionary.hasPronunciation("refrigerator"))
        assertTrue(PronunciationDictionary.hasPronunciation("meshix"))
    }

    // --- Markdown to words ------------------------------------------------

    @Test
    fun `emphasis markers are removed but words kept`() {
        assertEquals("Done and dusted.", spokenOf("**Done** and _dusted_."))
    }

    @Test
    fun `links keep their label and drop the target`() {
        val out = spokenOf("See [the design doc](https://example.com/a/b) for details.")
        assertTrue(out.contains("the design doc"))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("]("))
    }

    @Test
    fun `bare urls are spoken as a link`() {
        val out = spokenOf("Fetch https://example.com/x?y=1 now.")
        assertTrue(out.contains("a link"))
        assertFalse(out.contains("https"))
    }

    @Test
    fun `inline code naming a file becomes a file type`() {
        assertTrue(spokenOf("Edit `DialogScreen.kt` now.").contains("Kotlin file"))
    }

    @Test
    fun `inline code with spaces is summarised not read`() {
        val out = spokenOf("Run `mix test --seed 0` first.")
        assertTrue(out.contains("some code"))
        assertFalse(out.contains("--seed"))
    }

    @Test
    fun `inline code identifiers are spoken as words`() {
        assertTrue(spokenOf("Call `submitAnswer` once.").contains("submitAnswer"))
    }

    @Test
    fun `abbreviations expand into normal words`() {
        assertEquals("for example this", spokenOf("e.g. this"))
        assertTrue(spokenOf("fast, i.e. quick").contains("that is"))
    }

    @Test
    fun `numeric units read as words`() {
        assertTrue(spokenOf("Wait 8s please.").contains("8 seconds"))
        assertTrue(spokenOf("Timeout is 300ms.").contains("300 milliseconds"))
    }

    // --- Markup the service would speak aloud -----------------------------

    @Test
    fun `no SSML is ever emitted, because the service verbalises tags`() {
        val script = SpeechScriptCompiler.compile(
            title = "Test",
            prompt = """
                Hello there.

                - one
                - two
            """.trimIndent(),
            dialogType = "choice",
            optionLabels = listOf("Continue", "Stop"),
        )
        val everything = script.segments.joinToString(" ") { it.spoken }
        assertFalse("must not contain markup", everything.contains("<"))
        assertFalse(everything.contains(">"))
        assertFalse(everything.contains("break"))
        assertFalse(everything.contains("speak"))
    }

    // --- Structure --------------------------------------------------------

    @Test
    fun `code fences are announced with a line count, never read`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "Run this:\n\n```kotlin\nval x = 1\nval y = 2\n```\n",
            dialogType = "text",
        )
        val code = script.segments.first { it.kind == SpeechScriptCompiler.Kind.CODE }
        assertTrue(code.spoken.contains("2 lines"))
        assertFalse(code.spoken.contains("val x"))
    }

    @Test
    fun `tables are summarised by rows`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n",
            dialogType = "text",
        )
        val table = script.segments.first { it.spoken.contains("table") }
        assertTrue(table.spoken.contains("2 rows"))
        assertFalse(table.spoken.contains("|"))
    }

    @Test
    fun `options are numbered to match what is spoken`() {
        val script = SpeechScriptCompiler.compile(
            title = "Pick",
            prompt = "Which one?",
            dialogType = "choice",
            optionLabels = listOf("Continue", "Cancel", "Retry"),
        )
        val options = script.optionSegments
        assertEquals(3, options.size)
        assertEquals(0, options[0].optionIndex)
        assertTrue(options[0].spoken.startsWith("Option 1."))
        assertTrue(options[2].spoken.contains("Retry"))
    }

    @Test
    fun `a closing instruction is added per dialog type`() {
        fun instructionFor(type: String) =
            SpeechScriptCompiler.compile("T", "P", type).segments.last().spoken

        assertTrue(instructionFor("choice").contains("option number"))
        assertTrue(instructionFor("confirm").contains("yes or no"))
        assertTrue(instructionFor("text").contains("Speak"))
        assertTrue(instructionFor("slider").contains("number"))
    }

    @Test
    fun `instructions can be suppressed`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "P",
            dialogType = "choice",
            optionLabels = listOf("A"),
            includeInstructions = false,
        )
        assertFalse(script.segments.any { it.spoken.contains("option number") })
    }

    // --- Pauses and segmentation -----------------------------------------

    @Test
    fun `long prose is split into sentences so playback can start early`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "First sentence here. Second sentence here. Third one.",
            dialogType = "text",
        )
        val paragraphs = script.segments.filter {
            it.kind == SpeechScriptCompiler.Kind.PARAGRAPH
        }
        assertEquals(3, paragraphs.size)
    }

    @Test
    fun `every non-final segment carries a pause`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "One. Two. Three.",
            dialogType = "text",
        )
        // The last segment is the closing instruction and deliberately has none.
        script.segments.dropLast(1).forEach {
            assertTrue("segment '${it.spoken}' should pause", it.pauseAfterMs > 0)
        }
    }

    @Test
    fun `sentence splitting keeps abbreviations intact`() {
        val sentences = SpeechScriptCompiler.splitSentences("Use e.g. this one. Then stop.")
        assertEquals(2, sentences.size)
        assertTrue(sentences[0].contains("e.g."))
    }

    @Test
    fun `headings and list items become their own segments`() {
        val script = SpeechScriptCompiler.compile(
            title = "T",
            prompt = "## Status\n\n- built\n- tests pass\n",
            dialogType = "text",
        )
        assertTrue(script.segments.any { it.kind == SpeechScriptCompiler.Kind.HEADING })
        assertEquals(2, script.segments.count { it.kind == SpeechScriptCompiler.Kind.LIST_ITEM })
    }

    @Test
    fun `empty prompt still yields a usable script`() {
        val script = SpeechScriptCompiler.compile("Title only", "", "text")
        assertFalse(script.isEmpty)
        assertTrue(script.flattened.contains("Title only"))
    }

    @Test
    fun `annotated view exposes kind and pause for inspection`() {
        val script = SpeechScriptCompiler.compile("T", "One. Two.", "text")
        val annotated = script.annotated()
        assertTrue(annotated.contains("[paragraph]"))
        assertTrue(annotated.contains("ms"))
    }

    @Test
    fun `character count is tracked for cost logging`() {
        val script = SpeechScriptCompiler.compile("T", "Some words here.", "text")
        assertTrue(script.characterCount > 0)
        assertEquals(script.segments.sumOf { it.spoken.length }, script.characterCount)
    }
}
