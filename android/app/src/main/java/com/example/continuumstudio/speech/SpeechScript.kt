package com.example.continuumstudio.speech

/**
 * Compiles a dialog (markdown prompt) into a spoken script.
 *
 * ## Why this exists
 *
 * The old path ran a handful of regexes over the raw markdown and sent the
 * result to EasyVoice in one request. That failed in four visible ways:
 *
 *  - Markdown survived in ways that read badly ("**Done**" -> "Done", but
 *    tables, horizontal rules and nested lists did not).
 *  - Nothing knew that "main.kt" should be "main Kotlin file".
 *  - One request meant one clip, so a long dialog paid full generation time
 *    before a single word was heard.
 *  - No pauses: the model reads straight through.
 *
 * ## What it produces instead
 *
 * A [SpeechScript] is a list of [Segment]s. Each carries its own spoken text and
 * a pause that belongs after it. That shape is what lets the player synthesize
 * segment by segment and start talking early.
 *
 * ## Why pauses are silence, not markup
 *
 * EasyVoice accepts SSML and then **speaks the tag names**. Measured against
 * the live API on 2026-11-17:
 *
 * | input                            | duration |
 * |----------------------------------|----------|
 * | `Hello there.`                   | 1.53s    |
 * | `<speak>Hello there.</speak>`    | 2.23s    |
 * | `<break time="10s"/>`            | 3.00s    |
 *
 * So `<break>` cannot buy silence. The only real levers are punctuation and
 * newlines (a blank line was worth +1.15s versus +0.30s for a full stop). This
 * compiler therefore ignores markup entirely and puts pauses between segments,
 * where the player renders them as actual silence.
 */
object SpeechScriptCompiler {

    /** What a segment is, which decides its pause and any lead-in wording. */
    enum class Kind {
        /** Dialog title. */
        TITLE,
        /** Ordinary prose sentence(s). */
        PARAGRAPH,
        /** A markdown heading inside the prompt. */
        HEADING,
        /** One bullet or numbered item. */
        LIST_ITEM,
        /** A code fence, spoken as a marker rather than read out. */
        CODE,
        /** A blockquote / aside. */
        QUOTE,
        /** One choice option. */
        OPTION,
        /** Closing instruction such as "Say the option number". */
        INSTRUCTION,
    }

    /**
     * One unit of speech.
     *
     * @param spoken what is synthesized
     * @param pauseAfterMs silence inserted after this segment finishes
     * @param source the original markdown, kept so the app can show the script
     *        next to the rendered prompt
     */
    data class Segment(
        val kind: Kind,
        val spoken: String,
        val pauseAfterMs: Int,
        val source: String = "",
        /** Options are numbered so the label can match what is spoken. */
        val optionIndex: Int? = null,
    )

    /** Pause policy, in milliseconds. Tuned against the measured clip lengths. */
    private object Pauses {
        const val TITLE = 500
        const val HEADING = 420
        const val PARAGRAPH = 340
        const val LIST_ITEM = 240
        const val CODE = 320
        const val QUOTE = 380
        const val OPTION = 280
        const val INSTRUCTION = 0
    }

    /**
     * Build a script for a dialog.
     *
     * ## Ordering is deliberate: prompt, then options
     *
     * Measured on a realistic dialog, the prompt is ~17s of speech before the
     * options begin. Moving the options earlier would let you answer sooner, but
     * the owner's decision (2026-11-17) is that **full context first** is what
     * they want while driving - answering without the reasoning is worse than
     * waiting for it.
     *
     * So do not reorder this to put options before the body. The escape hatch for
     * "I already know the context" is the skip-to-options control in the app,
     * which jumps playback to the first OPTION segment rather than changing the
     * default read.
     *
     * @param includeOptions read choice labels aloud, numbered
     * @param includeInstructions add the closing "say the option number" line
     */
    fun compile(
        title: String,
        prompt: String,
        dialogType: String,
        optionLabels: List<String> = emptyList(),
        includeOptions: Boolean = true,
        includeInstructions: Boolean = true,
    ): SpeechScript {
        val segments = mutableListOf<Segment>()

        spoken(title).takeIf { it.isNotEmpty() }?.let {
            segments += Segment(Kind.TITLE, it, Pauses.TITLE, source = title)
        }

        segments += compileBody(prompt)

        if (optionLabels.isNotEmpty() && includeOptions) {
            segments += Segment(
                Kind.INSTRUCTION,
                "Your options are.",
                Pauses.OPTION,
                source = "Your options are:",
            )
            optionLabels.forEachIndexed { index, label ->
                val words = spoken(label)
                if (words.isNotEmpty()) {
                    segments += Segment(
                        kind = Kind.OPTION,
                        spoken = "Option ${index + 1}. $words",
                        pauseAfterMs = Pauses.OPTION,
                        source = label,
                        optionIndex = index,
                    )
                }
            }
        }

        if (includeInstructions) {
            instructionFor(dialogType)?.let {
                segments += Segment(Kind.INSTRUCTION, it, Pauses.INSTRUCTION, source = it)
            }
        }

        return SpeechScript(
            title = title,
            segments = segments,
            // Kept for callers and caches that still want one flat string.
            flattened = segments.joinToString(" ") { it.spoken },
        )
    }

    private fun instructionFor(dialogType: String): String? = when (dialogType.lowercase()) {
        "choice" -> "Say the option number to reply."
        "confirm", "confirmation" -> "Say yes or no to reply."
        "text" -> "Speak your reply."
        "slider" -> "Say a number to set the value."
        else -> null
    }

    /**
     * Split markdown into segments.
     *
     * Deliberately hand-rolled rather than pulling in a CommonMark parser: this
     * runs on the dialog hot path on a phone, and it only needs the handful of
     * block forms an agent actually emits.
     */
    private fun compileBody(prompt: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val lines = prompt.lines()
        var i = 0

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            // Blank line: a paragraph break, which becomes a pause not a word.
            if (line.isEmpty()) {
                i++
                continue
            }

            // Fenced code: say what it is, never read the contents.
            if (line.startsWith("```")) {
                val language = line.removePrefix("```").trim()
                var codeLines = 0
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (lines[i].isNotBlank()) codeLines++
                    i++
                }
                if (i < lines.size) i++ // closing fence
                val what = when {
                    language.isBlank() -> "a code block"
                    else -> "a ${spokenPlain(language)} code block"
                }
                out += Segment(
                    Kind.CODE,
                    "Then $what, $codeLines ${if (codeLines == 1) "line" else "lines"}.",
                    Pauses.CODE,
                    source = "```$language ... ```",
                )
                continue
            }

            // Tables: agents emit these constantly and they read terribly.
            // Announce the header, then stop.
            if (line.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(TABLE_RULE)) {
                val headers = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                i += 2
                var rows = 0
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows++
                    i++
                }
                val headerWords = headers.map { spoken(it) }.filter { it.isNotEmpty() }
                val intro = if (headerWords.isEmpty()) {
                    "Then a table"
                } else {
                    "Then a table of ${headerWords.joinToString(", ")}"
                }
                out += Segment(
                    Kind.PARAGRAPH,
                    "$intro, $rows ${if (rows == 1) "row" else "rows"}.",
                    Pauses.PARAGRAPH,
                    source = "| table, $rows rows |",
                )
                continue
            }

            // Horizontal rule: silence, plus a small pause.
            if (line.matches(HR_RULE)) {
                i++
                continue
            }

            // Headings. Markdown level is intentionally ignored: spoken
            // headings sound the same at every depth, and announcing "heading
            // two" is noise when driving.
            HEADING.find(line)?.let { match ->
                val text = spoken(match.groupValues[2])
                if (text.isNotEmpty()) {
                    out += Segment(Kind.HEADING, text, Pauses.HEADING, source = line)
                }
                i++
                continue
            }

            // Blockquote: consecutive '>' lines become one aside.
            if (line.startsWith(">")) {
                val quote = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                val text = spoken(quote.joinToString(" "))
                if (text.isNotEmpty()) {
                    out += Segment(Kind.QUOTE, text, Pauses.QUOTE, source = quote.joinToString(" "))
                }
                continue
            }

            // Bullets and numbered items: one segment each, so a pause lands
            // between them and the player can start on the first item.
            val bullet = BULLET.find(line)
            val numbered = NUMBERED.find(line)
            if (bullet != null || numbered != null) {
                val text = spoken((bullet ?: numbered)!!.groupValues[1])
                if (text.isNotEmpty()) {
                    out += Segment(Kind.LIST_ITEM, text, Pauses.LIST_ITEM, source = line)
                }
                i++
                continue
            }

            // Ordinary prose. Gather until a blank line or a different block
            // form, then split into sentences so long paragraphs are not a
            // single 20-second clip.
            val prose = mutableListOf<String>()
            while (i < lines.size) {
                val candidate = lines[i].trim()
                if (candidate.isEmpty() ||
                    candidate.startsWith("```") ||
                    candidate.startsWith(">") ||
                    candidate.startsWith("|") ||
                    candidate.matches(HR_RULE) ||
                    HEADING.matches(candidate) ||
                    BULLET.matches(candidate) ||
                    NUMBERED.matches(candidate)
                ) {
                    break
                }
                prose += candidate
                i++
            }
            prose.joinToString(" ").let { joined ->
                splitSentences(joined).forEach { sentence ->
                    val text = spoken(sentence)
                    if (text.isNotEmpty()) {
                        out += Segment(Kind.PARAGRAPH, text, Pauses.PARAGRAPH, source = sentence)
                    }
                }
            }
        }

        return out
    }

    /**
     * Turn one line of markdown into words.
     *
     * Handles, in order: inline code, links, emphasis, strikethrough, images,
     * raw URLs and paths, then dictionary entries and units.
     */
    fun spoken(markdown: String): String {
        var text = markdown

        // Images before links, so ![alt](src) does not leave a stray "!".
        text = text.replace(IMAGE, " image ")
        // Links keep their label, drop the target.
        text = text.replace(LINK, "$1")
        // Bare URLs and absolute paths are noise when spoken.
        text = text.replace(BARE_URL, " a link ")
        text = text.replace(ABS_PATH, " a file path ")
        // Inline code: if it looks like a filename, say the file type.
        text = text.replace(INLINE_CODE) { match -> inlineCodeSpoken(match.value.trim('`')) }
        // Strip emphasis markers. Doubles first so **bold** does not become
        // *bold*, and single markers only when they wrap a whole word - that
        // keeps snake_case identifiers such as submit_answer intact.
        text = text.replace("**", "").replace("__", "")
        text = text.replace(STRIKETHROUGH, "$1")
        text = text.replace(BOLD_SINGLE, "$1")
        text = text.replace(ITALIC_UNDERSCORE, "$1")

        // Word-level handling: dictionary, units, numbers.
        text = text.split(' ').joinToString(" ") { token ->
            if (token.isBlank()) token else rewriteToken(token)
        }

        // Collapse whitespace and tidy punctuation spacing.
        text = text.replace(WHITESPACE, " ").trim()
        text = text.replace(" ,", ",").replace(" .", ".")
        // A segment should not start or end on a dangling separator.
        return text.trim(' ', '-', ':', ';', ',')
    }

    private fun inlineCodeSpoken(code: String): String {
        val cleaned = code.trim().trim('`')
        if (cleaned.isEmpty()) return ""
        // A file name: say what kind of file it is.
        FILE_EXT.find(cleaned)?.let { match ->
            val base = cleaned.substringBeforeLast('.')
            val type = PronunciationDictionary.lookup(match.value)?.spoken
            if (type != null) {
                return " ${spokenPlain(base)} $type "
            }
        }
        // A bare identifier with no spaces is usually a name, not code.
        if (cleaned.none { it.isWhitespace() }) return " ${spokenPlain(cleaned)} "
        // Anything else: summarise rather than read punctuation aloud.
        return " some code "
    }

    /** Dictionary/unit pass for a single token that keeps its punctuation. */
    private fun rewriteToken(token: String): String {
        // Abbreviations end in a dot and are dictionary keys with that dot
        // (e.g. "e.g.", "etc."), so they have to be tried before the trailing
        // punctuation is trimmed or they never match.
        PronunciationDictionary.lookup(token)?.let { entry ->
            return entry.spoken
        }

        val core = token.trim('.', ',', ';', ':', '!', '?', '"', '\'', ')', '(', '[', ']')
        if (core.isEmpty()) return token

        val prefix = token.takeWhile { it == '(' || it == '[' || it == '"' || it == '\'' }
        val suffix = token.drop(prefix.length + core.length)

        // Units attached to numbers: "8s", "300ms".
        PronunciationDictionary.splitUnit(core)?.let { (number, unit) ->
            return "$prefix$number $unit$suffix"
        }

        // Bare filenames in ordinary prose, e.g. "build.gradle.kts". Left alone
        // this gets read as "build dot gradle dot kts", which is both long and
        // meaningless; the extension is the part that carries information.
        fileNameSpoken(core)?.let { return "$prefix$it$suffix" }

        PronunciationDictionary.lookup(core)?.let { entry ->
            return "$prefix${entry.spoken}$suffix"
        }

        // Numbers above 1000 with separators read better without them.
        if (THOUSANDS.matches(core)) return "$prefix${core.replace(",", "")}$suffix"

        return token
    }

    /**
     * Speak a bare filename by its type rather than spelling out its dots.
     *
     * "build.gradle.kts" -> "build.gradle Kotlin script". The base name is kept
     * because it is usually meaningful ("DialogScreen", "flake"), while the
     * extension becomes words.
     *
     * Returns null when the token is not a recognised filename, so ordinary
     * dotted words ("v1.2", "example.com") are left to other rules.
     */
    private fun fileNameSpoken(token: String): String? {
        if (!token.contains('.')) return null
        // Longest matching extension that the dictionary actually knows wins,
        // so ".gradle.kts" beats ".kts". Falling through to a shorter extension
        // matters: a recognised-but-unmapped extension must not silently drop
        // the whole filename.
        val candidate = PronunciationDictionary.EXTENSIONS
            .filter { token.endsWith(it, ignoreCase = true) && token.length > it.length }
            .sortedByDescending { it.length }
            .firstOrNull { PronunciationDictionary.lookup(it) != null }
            ?: return null
        val type = PronunciationDictionary.lookup(candidate)?.spoken ?: return null
        val base = token.dropLast(candidate.length)
        // A name with no readable base is not a filename worth announcing.
        if (base.isBlank() || base.none { it.isLetter() }) return null
        return "$base $type"
    }

    /** A word with no markdown handling, used for fragments we already cleaned. */
    private fun spokenPlain(text: String): String {
        PronunciationDictionary.lookup(text)?.let { return it.spoken }
        return text
    }

    /**
     * Split prose into sentences.
     *
     * Sentence granularity is what makes early playback possible: the player
     * speaks sentence one while sentence two is still being generated.
     */
    fun splitSentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            val isEnd = c == '.' || c == '!' || c == '?'
            if (isEnd) {
                val next = text.getOrNull(i + 1)
                if (next == null || next.isWhitespace()) {
                    val candidate = current.toString().trim()
                    if (!candidate.isAbbreviation()) {
                        out += candidate
                        current.clear()
                        while (i + 1 < text.length && text[i + 1].isWhitespace()) i++
                    }
                }
            }
            i++
        }
        current.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out.ifEmpty { listOf(text.trim()) }.filter { it.isNotBlank() }
    }

    private fun String.isAbbreviation(): Boolean =
        ABBREVIATIONS.any { lowercase().endsWith(it) }

    private val ABBREVIATIONS = listOf(
        "e.g.", "i.e.", "vs.", "etc.", "dr.", "mr.", "mrs.", "ms.", "prof.", "jr.", "sr.",
        "approx.", "fig.", "no.", "inc.", "ltd.",
    )

    private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val BARE_URL = Regex("https?://\\S+")
    private val ABS_PATH = Regex("(?:/[\\w.+-]+){2,}")
    private val INLINE_CODE = Regex("`[^`]+`")
    private val STRIKETHROUGH = Regex("~~([^~]+)~~")
    /** Single-asterisk emphasis wrapping a word: *word* -> word. */
    private val BOLD_SINGLE = Regex("(?<![\\w*])\\*([^*\\s][^*]*?)\\*(?![\\w*])")
    /** Underscore emphasis wrapping a whole word, but never inside a word. */
    private val ITALIC_UNDERSCORE = Regex("(?<![\\w_])_([^_\\s][^_]*?)_(?![\\w_])")
    private val WHITESPACE = Regex("\\s+")
    private val FILE_EXT = Regex("\\.[A-Za-z0-9]{1,5}$")
    private val THOUSANDS = Regex("\\d{1,3}(,\\d{3})+")
    private val HR_RULE = Regex("^([-*_])\\1{2,}$")
    private val TABLE_RULE = Regex("^\\|?[\\s:-]*\\|[\\s:|-]*$")
    private val HEADING = Regex("^(#{1,6})\\s+(.+)$")
    private val BULLET = Regex("^[-*+]\\s+(.+)$")
    private val NUMBERED = Regex("^\\d+[.)]\\s+(.+)$")
}

/**
 * A compiled, ready-to-speak script.
 *
 * [segments] is the source of truth. [flattened] exists only because a few
 * callers (cache keys, the car's now-playing text) want one string.
 */
data class SpeechScript(
    val title: String,
    val segments: List<SpeechScriptCompiler.Segment>,
    val flattened: String,
) {
    val isEmpty: Boolean get() = segments.isEmpty()

    /** Total spoken characters, useful for logging and cost estimates. */
    val characterCount: Int get() = segments.sumOf { it.spoken.length }

    /** Options in the script, ordered, for the app's script inspector. */
    val optionSegments: List<SpeechScriptCompiler.Segment>
        get() = segments.filter { it.kind == SpeechScriptCompiler.Kind.OPTION }

    /**
     * Index of the first option, or -1 when the dialog has none.
     *
     * Used by the skip-to-options control. The default read is deliberately
     * prompt-first (see [SpeechScriptCompiler.compile]), so this is the escape
     * hatch for when the listener already knows the context.
     */
    fun firstOptionIndex(): Int = segments.indexOfFirst {
        it.kind == SpeechScriptCompiler.Kind.OPTION
    }

    /**
     * Human-readable annotated script, shown in the app so the read can be
     * checked before or during playback.
     */
    fun annotated(): String = buildString {
        segments.forEachIndexed { index, segment ->
            append("${index + 1}. [${segment.kind.name.lowercase()}] ${segment.spoken}")
            if (segment.pauseAfterMs > 0) append("  (+${segment.pauseAfterMs}ms)")
            append('\n')
        }
    }
}
