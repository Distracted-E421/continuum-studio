package com.example.continuumstudio.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory event log for the speech pipeline.
 *
 * ## Why this is in the app rather than only in logcat
 *
 * When something sounded wrong over Remote/tailnet while working away from the
 * machine, the only feedback available was "that read weirdly". Logcat was not
 * reachable from the driver's seat, so the actual sequence was guesswork.
 *
 * This keeps the last [MAX_EVENTS] events in memory and exposes them on the
 * Audio diagnostics screen: which script was compiled, how many segments, which
 * segment was requested, how long synthesis took, and why a read was skipped.
 * That turns a vague complaint into a concrete observation.
 *
 * Deliberately not persisted: it is a debugging aid, and writing it to disk
 * would mean shipping dialog text into storage for no operational gain.
 */
object SpeechTrace {

    private const val MAX_EVENTS = 120

    data class Event(
        val at: Long,
        val tag: String,
        val message: String,
        val level: Level,
    ) {
        enum class Level { INFO, WARN, ERROR }

        fun timeLabel(): String =
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))
    }

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events

    fun info(tag: String, message: String) = add(tag, message, Event.Level.INFO)

    fun warn(tag: String, message: String) = add(tag, message, Event.Level.WARN)

    fun error(tag: String, message: String) = add(tag, message, Event.Level.ERROR)

    /**
     * Record that a script was compiled.
     *
     * The segment list is summarised rather than dumped: the counts and kinds
     * answer "did it split correctly?" without filling the screen.
     */
    fun scriptCompiled(script: SpeechScript) {
        val kinds = script.segments
            .groupingBy { it.kind.name.lowercase() }
            .eachCount()
            .entries
            .joinToString(", ") { "${it.key}=${it.value}" }
        info(
            "script",
            "${script.segments.size} segments (${script.characterCount} chars): $kinds",
        )
    }

    /** Record the annotated read, one line per segment. */
    fun scriptDetail(script: SpeechScript) {
        script.segments.forEachIndexed { index, segment ->
            info(
                "segment ${index + 1}",
                "[${segment.kind.name.lowercase()}] ${segment.spoken} (+${segment.pauseAfterMs}ms)",
            )
        }
    }

    fun clear() {
        _events.value = emptyList()
    }

    private fun add(tag: String, message: String, level: Event.Level) {
        val event = Event(System.currentTimeMillis(), tag, message, level)
        _events.value = (_events.value + event).takeLast(MAX_EVENTS)
    }
}
