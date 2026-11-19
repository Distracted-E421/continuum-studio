package com.example.continuumstudio.probe

import com.example.continuumstudio.audio.AudioReport

/**
 * JSON encoding for probe responses.
 *
 * Hand-written rather than using `org.json` or a serialisation library. Two
 * reasons, in order:
 *
 *  1. **`org.json` is an Android class and is stubbed to throw in JVM unit tests.**
 *     The response format is a contract with the client, so it has to be pinnable
 *     by tests; using `org.json` would make the contract untestable off-device -
 *     the same trap that made the server itself untestable until logging was
 *     injected.
 *  2. The shapes here are two flat string maps and a string array. A serialiser
 *     would be more code and more dependencies for less clarity.
 *
 * The encoding is deliberately minimal (no nesting beyond one level), which keeps
 * [escape] the only thing that can be wrong.
 */
object ProbeJson {

    /**
     * An [AudioReport] as JSON.
     *
     * `fields` is an object keyed by report label so a client can address one value
     * (`["volume music"]`). `findings` travels with it because a caller should not
     * reimplement the interpretation - reading raw values without it led to a wrong
     * conclusion once already.
     */
    fun audio(report: AudioReport, findings: List<String>): String = buildString {
        append('{')
        field("ok", "true", first = true)
        field("situation", report.situation)
        append(",\"fields\":")
        append(objectOf(report.lines.associate { it.first to it.second }))
        append(",\"findings\":[")
        findings.forEachIndexed { i, f ->
            if (i > 0) append(',')
            append(quote(f))
        }
        append("]}")
    }

    /** Liveness reply. */
    fun ping(versionName: String, versionCode: Int, tokensRequired: Boolean): String = buildString {
        append('{')
        field("ok", "true", first = true)
        field("service", "continuum-probe")
        field("appVersion", versionName)
        field("appVersionCode", versionCode.toString())
        field("tokensRequired", tokensRequired.toString())
        append('}')
    }

    /** Error reply. `error` is a stable machine token; `message` is for humans. */
    fun error(error: String, message: String): String = buildString {
        append('{')
        field("ok", "false", first = true)
        field("error", error)
        field("message", message)
        append('}')
    }

    /** Generic reply, used by the state and probe endpoints. */
    fun fields(values: Map<String, String?>): String = buildString {
        append('{')
        field("ok", "true", first = true)
        append(",\"fields\":")
        append(objectOf(values.filterValues { it != null }.mapValues { it.value!! }))
        append('}')
    }

    // --- internals -------------------------------------------------------

    private fun StringBuilder.field(name: String, rawValue: String, first: Boolean = false) {
        if (!first) append(',')
        append(quote(name)).append(':').append(quote(rawValue))
    }

    private fun objectOf(map: Map<String, String>): String = buildString {
        append('{')
        var first = true
        map.forEach { (k, v) ->
            if (!first) append(',')
            first = false
            append(quote(k)).append(':').append(quote(v))
        }
        append('}')
    }

    /**
     * Quote and escape a string.
     *
     * Every control character below 0x20 is escaped, not just the common ones: a
     * device name containing an unusual byte is exactly the kind of value this
     * report is meant to capture, and emitting it raw would produce a body the
     * client cannot parse - losing the very evidence that prompted the report.
     */
    internal fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else ->
                    if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
