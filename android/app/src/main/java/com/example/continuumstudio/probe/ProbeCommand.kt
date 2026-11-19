package com.example.continuumstudio.probe

/**
 * Commands the probe bridge will run through Shizuku.
 *
 * ## Why an allowlist and not a parameter
 *
 * The probe server listens on a network. Exposing a general "run this command"
 * endpoint would be remote code execution on the owner's phone - the same thing
 * this session refused to use on the Synapsix daemon's `shell` action. So the
 * command strings live here as constants and a request may only *name* one.
 *
 * Every entry is read-only. `dumpsys` and `cmd ... get` report state; none of these
 * change anything, which keeps the read endpoints safe to hit at any time, including
 * while the owner is driving.
 */
enum class ProbeCommand(
    /** Key used in the query string. */
    val key: String,
    /** What runs. A constant, never interpolated with caller input. */
    val command: String,
    /** What question this answers, for the UI and for the report. */
    val answers: String,
) {
    /**
     * The authoritative audio state: every stream, its volume, the focus stack and
     * the routing table. This is what we had been *inferring* from
     * `AudioManager`, and it names which app holds focus.
     */
    AUDIO(
        key = "audio",
        command = "dumpsys audio",
        answers = "Which app holds audio focus, every stream volume, and the routing table",
    ),

    /**
     * Every live media session and its state. Directly answers "is Continuum the
     * active session on the car's channel".
     */
    MEDIA_SESSION(
        key = "media_session",
        command = "dumpsys media_session",
        answers = "Which media sessions exist and which is active on the car's channel",
    ),

    /** The AOA/USB accessory handshake, when Android Auto is over the cable. */
    USB(
        key = "usb",
        command = "dumpsys usb",
        answers = "Whether the phone is presenting as an Android Auto accessory over USB",
    ),

    /** Which services are running, i.e. what else could be grabbing audio. */
    SERVICES(
        key = "services",
        command = "dumpsys activity services",
        answers = "Foreground services that could be holding audio",
    ),

    /** Audio policy routing decisions at a lower level than AudioManager exposes. */
    MEDIA_ROUTING(
        key = "media_routing",
        command = "dumpsys media.audio_policy",
        answers = "Low-level audio policy routing, including projection paths",
    ),
    ;

    companion object {
        fun byKey(key: String?): ProbeCommand? =
            entries.firstOrNull { it.key == (key?.trim()?.lowercase() ?: "") }
    }
}

/**
 * Trims a `dumpsys` transcript to something a client can consume.
 *
 * `dumpsys audio` alone is thousands of lines and would blow up a JSON response and
 * a mobile link. Pure, so the trimming rules are testable - and the rules matter,
 * because trimming away the interesting part would silently hide the answer.
 */
object ProbeTranscript {

    /** Cap on returned characters. Generous for a JSON body, small for a phone link. */
    const val MAX_CHARS = 24_000

    /**
     * Keep the head and tail, drop the middle.
     *
     * For `dumpsys` output the useful lines are usually at the start (the summary)
     * and the end (the most recent events), so dropping the middle loses less than
     * truncating the tail.
     */
    fun trim(text: String, max: Int = MAX_CHARS): String {
        if (text.length <= max) return text
        val head = max * 2 / 3
        val tail = max - head
        val omitted = text.length - head - tail
        return buildString {
            append(text, 0, head)
            append("\n\n... [")
            append(omitted)
            append(" characters omitted] ...\n\n")
            append(text, text.length - tail, text.length)
        }
    }

    /**
     * Pull out lines mentioning any [needles].
     *
     * Case-insensitive, order preserved. An empty result is meaningful - "nothing
     * in the audio dump mentions vivaldi" is an answer - so it is returned as empty
     * rather than falling back to the whole transcript, which would bury it.
     */
    fun matching(text: String, needles: List<String>): List<String> {
        if (needles.isEmpty()) return emptyList()
        val lowered = needles.map { it.lowercase() }
        return text.lineSequence()
            .filter { line -> lowered.any { line.lowercase().contains(it) } }
            .toList()
    }
}
