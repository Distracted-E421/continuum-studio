package com.example.continuumstudio.speech

/**
 * Word and pronunciation dictionary for dialog speech.
 *
 * Two separate problems are handled here, and conflating them is what made the
 * old text path sound wrong:
 *
 *  1. **What to say.** Repository names, file extensions, symbols and
 *     abbreviations do not survive being read literally ("main.kt" becomes
 *     "main dot K T" only if we are lucky). [entries] rewrites a token into the
 *     words a person would actually say.
 *
 *  2. **How to say it.** Some words *are* spelled correctly but the
 *     synthesizer mispronounces them (project names, acronyms, non-English
 *     words). [phonetic] gives a respelling that Kokoro says correctly.
 *
 * Why respelling instead of SSML: EasyVoice accepts markup without error but
 * **speaks the tag names**. Measured 2026-11-17: `<speak>Hello there.</speak>`
 * is 0.7s LONGER than "Hello there.", and `<break time="10s"/>` returns 3.0s of
 * the word "break". So there is no `<phoneme>` path - respelling is the only
 * lever, which is why this dictionary matters more than it looks.
 *
 * Entries are matched case-insensitively on whole tokens only. Punctuation is
 * left to [SpeechScript], which owns pauses.
 */
object PronunciationDictionary {

    /**
     * Spoken expansion for a token.
     *
     * @param spoken the words to actually synthesize
     * @param sayAs a short human label used in the on-screen script view, so the
     *        script can be inspected without decoding respellings
     */
    data class Entry(val spoken: String, val sayAs: String? = null)

    /**
     * Project and product names that are spelled right but said wrong.
     *
     * These are respellings, not rewrites: the goal is that Kokoro's letter to
     * sound rules land on the intended syllables. Keep them lowercase and
     * hyphenated - that is what the model reads most reliably.
     *
     * ## How these were checked (2026-11-17)
     *
     * Verified by synthesizing through the live API and transcribing the result
     * with whisper (base and medium). Two limits are worth knowing before adding
     * entries:
     *
     *  - Whisper is a language model, so it cannot spell invented names. It
     *    renders "Synapsix" as "Synapse 6" whatever the audio says, because
     *    "Synapse" is a real word and the trailing sibilant becomes "6".
     *    Control words transcribe exactly ("refrigerator", "obsidian"), so this
     *    is a limit on judging invented names, not on the pipeline.
     *
     *  - The reliable signal is therefore **relative**: does the respelled clip
     *    differ from the plain one, and does it get longer? For "Synapsix",
     *    "sin-app-sicks" is 1.63s against 1.50s plain and produces different
     *    audio, so the respelling is being applied.
     *
     * Entries that measurably work: continuum ("con-tin-yoo-um" transcribes
     * exactly), nixos ("nicks-oh-ess" transcribes as "NixOS").
     */
    private val phonetic: Map<String, Entry> = mapOf(
        // Ecosystem names
        "synapsix" to Entry("sin-app-sicks"),
        "continuum" to Entry("con-tin-yoo-um"),
        "korix" to Entry("kor-icks"),
        "corix" to Entry("kor-icks"),
        "infrix" to Entry("in-fricks"),
        "lucidix" to Entry("loo-sid-icks"),
        "vextor" to Entry("vecks-tor"),
        "meshix" to Entry("mesh-icks"),
        "identix" to Entry("eye-den-ticks"),
        "cortix" to Entry("kor-ticks"),
        "cognix" to Entry("kog-nicks"),
        "lattix" to Entry("lat-ticks"),
        "phosphor" to Entry("foss-for"),
        "nickelz" to Entry("nickels"),
        "corvid" to Entry("kor-vid"),
        "directix" to Entry("die-reck-ticks"),
        "devix" to Entry("dev-icks"),
        "nixos" to Entry("nicks-oh-ess"),
        "nixpkgs" to Entry("nicks-pack-ages"),
        "datapunk" to Entry("day-tuh-punk"),
        "obsidian" to Entry("ob-sid-ee-un"),
        "tailscale" to Entry("tail-scale"),
        "forgejo" to Entry("for-jay-oh"),
        "gitea" to Entry("git-ee-uh"),
        "easyvoice" to Entry("easy-voice"),
        "kokoro" to Entry("koh-koh-roh"),
        "shizuku" to Entry("shee-zoo-koo"),
        "samsung" to Entry("sam-sung"),
        "galaxy" to Entry("gal-ax-ee"),
    )

    /**
     * Tokens rewritten into ordinary words: symbols, file names, units and
     * abbreviations that a literal read would mangle.
     */
    private val expansions: Map<String, Entry> = mapOf(
        // Shorthands an agent writes constantly
        "e.g." to Entry("for example"),
        "i.e." to Entry("that is"),
        "etc." to Entry("et cetera"),
        "vs." to Entry("versus"),
        "w/" to Entry("with"),
        "w/o" to Entry("without"),
        "approx." to Entry("approximately"),

        // Status/symbol tokens that appear in agent prose
        "->" to Entry("to"),
        "=>" to Entry("implies"),
        "&" to Entry("and"),
        "+" to Entry("plus"),
        "=" to Entry("equals"),
        "%" to Entry("percent"),
        "#" to Entry("number"),
        "@" to Entry("at"),

        // File types: say the words, not the letters
        ".gradle.kts" to Entry("Gradle Kotlin script"),
        ".d.ts" to Entry("TypeScript type definition"),
        ".tar.gz" to Entry("compressed archive"),
        ".kt" to Entry("Kotlin file"),
        ".kts" to Entry("Kotlin script"),
        ".rs" to Entry("Rust file"),
        ".nix" to Entry("Nix file"),
        ".ncl" to Entry("Nickel file"),
        ".ex" to Entry("Elixir file"),
        ".exs" to Entry("Elixir script"),
        ".zig" to Entry("Zig file"),
        ".md" to Entry("markdown file"),
        ".json" to Entry("JSON file"),
        ".yaml" to Entry("YAML file"),
        ".yml" to Entry("YAML file"),
        ".toml" to Entry("TOML file"),
        ".sh" to Entry("shell script"),
        ".apk" to Entry("Android app package"),

        // Common technical abbreviations
        "api" to Entry("A P I"),
        "url" to Entry("U R L"),
        "urls" to Entry("U R Ls"),
        "ui" to Entry("U I"),
        "ux" to Entry("U X"),
        "id" to Entry("I D"),
        "ids" to Entry("I Ds"),
        "tts" to Entry("text to speech"),
        "stt" to Entry("speech to text"),
        "mcp" to Entry("M C P"),
        "ws" to Entry("websocket"),
        "ssh" to Entry("S S H"),
        "http" to Entry("H T T P"),
        "https" to Entry("H T T P S"),
        "json" to Entry("jason"),
        "yaml" to Entry("yam-ul"),
        "adb" to Entry("A D B"),
        "aa" to Entry("Android Auto"),
        "ai" to Entry("A I"),
        "llm" to Entry("L L M"),
        "ok" to Entry("okay"),
        "vs code" to Entry("V S Code"),
    )

    /** Units that read badly after a number, e.g. "8s" -> "8 seconds". */
    private val units: Map<String, String> = mapOf(
        "ms" to "milliseconds",
        "s" to "seconds",
        "m" to "minutes",
        "h" to "hours",
        "kb" to "kilobytes",
        "mb" to "megabytes",
        "gb" to "gigabytes",
        "px" to "pixels",
        "dp" to "D P",
        "sp" to "S P",
    )

    /**
     * Case-insensitive lookup for [token] (already stripped of punctuation).
     *
     * Phonetic entries win over expansions, because a project name that is both
     * (e.g. "nixos") should be said the way the project says it.
     */
    fun lookup(token: String): Entry? {
        val key = token.lowercase()
        phonetic[key]?.let { return it }
        expansions[key]?.let { return it }
        return null
    }

    /**
     * Split a trailing unit off a token so "8s" becomes "8" + "seconds".
     *
     * Only fires when the leading part is numeric; "class" must not become
     * "cla" + "seconds".
     */
    fun splitUnit(token: String): Pair<String, String>? {
        val match = UNIT_TAIL.find(token) ?: return null
        val number = match.groupValues[1]
        val unit = match.groupValues[2].lowercase()
        val spoken = units[unit] ?: return null
        return number to spoken
    }

    /** All dictionary keys, for the on-device dictionary browser. */
    fun allEntries(): List<Triple<String, String, String>> {
        val phoneticRows = phonetic.entries.map { (word, entry) ->
            Triple(word, entry.spoken, "pronunciation")
        }
        val expansionRows = expansions.entries.map { (word, entry) ->
            Triple(word, entry.spoken, "expansion")
        }
        return phoneticRows + expansionRows
    }

    /**
     * File extensions recognised in ordinary prose, longest first at use site.
     *
     * Compound extensions are included so "build.gradle.kts" resolves to a
     * Kotlin script rather than to a generic ".kts".
     */
    val EXTENSIONS: List<String> = listOf(
        ".gradle.kts", ".d.ts", ".tar.gz",
        ".kt", ".kts", ".rs", ".nix", ".ncl", ".ex", ".exs", ".zig", ".md",
        ".json", ".yaml", ".yml", ".toml", ".sh", ".apk", ".ncl",
    )

    /** True when [word] has an explicit pronunciation override. */
    fun hasPronunciation(word: String): Boolean = phonetic.containsKey(word.lowercase())

    private val UNIT_TAIL = Regex("^(\\d+(?:\\.\\d+)?)(ms|s|m|h|kb|mb|gb|px|dp|sp)$")
}
