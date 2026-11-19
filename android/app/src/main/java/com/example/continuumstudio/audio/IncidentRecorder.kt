package com.example.continuumstudio.audio

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes an incident report whenever something audio-related happens, so the next
 * failure arrives with evidence attached.
 *
 * ## Why this exists
 *
 * Six releases were spent fixing car audio from descriptions of what happened. The
 * information needed to settle it - what the audio stack looked like *at the moment of
 * failure*, and whether any other app was playing - was obtainable but nobody was
 * holding a phone when it happened.
 *
 * A diagnostic tool that requires the owner to notice a failure, open a screen and
 * remember what they saw is a tool that will be used wrong. So the app records on its
 * own, and the question becomes "what do the files say" rather than "what did it sound
 * like".
 *
 * ## What it writes
 *
 * One file per incident under `filesDir/incidents/`:
 *
 *  - the trigger and a timestamp;
 *  - the full [AudioReport] (routing, volumes, devices, session state, playback);
 *  - the findings, i.e. the interpretation, so nobody has to re-derive it;
 *  - recent speech-trace lines, where "generation took 9s" or "focus held" appears.
 *
 * Deliberately no `dumpsys`: that needs Shizuku, which is frequently unavailable, and a
 * recorder that only works when everything else works is useless.
 *
 * ## Cost
 *
 * Bounded: at most [MAX_FILES] files, oldest deleted first, each small. Safe to leave on.
 */
object IncidentRecorder {

    private const val DIR = "incidents"

    /**
     * How many incidents to keep.
     *
     * Enough for a drive plus the previous one, which is the realistic window between
     * "it happened" and "I can look at it".
     */
    const val MAX_FILES = 40

    /** A trigger that caused a recording. */
    enum class Trigger(val label: String) {
        /** A dialog was spoken: the common case and the one users complain about. */
        DIALOG_SPOKEN("dialog spoken"),

        /** Synthesis or playback failed, worth a record even if audio worked. */
        AUDIO_FAILED("audio failed"),

        /** Audio focus was lost or held; the previous silent bugs lived here. */
        FOCUS_HOLD("focus held"),

        /** Requested from the diagnostics screen. */
        MANUAL("manual capture"),
    }

    /**
     * Write an incident record.
     *
     * Best-effort by design: a diagnostics recorder must never be able to break audio or
     * crash the app, so failures are swallowed. That is a deliberate exception to "do not
     * swallow errors", and it is why the return is nullable rather than throwing.
     *
     * @return the file written, or null if recording failed
     */
    fun record(
        context: Context,
        trigger: Trigger,
        extra: Map<String, String> = emptyMap(),
        traceLines: List<String> = emptyList(),
    ): java.io.File? = try {
        val report = AudioReportCollector.collect(context, trigger.label)
        val findings = AudioFindings.inspect(report)

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val text = buildString {
            appendLine("Continuum incident report")
            appendLine("trigger: ${trigger.label}")
            appendLine("at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine()
            appendLine("FINDINGS")
            if (findings.isEmpty()) {
                appendLine("  (none - nothing suspicious in the audio state)")
            } else {
                findings.forEach { appendLine("  - $it") }
            }
            appendLine()
            if (extra.isNotEmpty()) {
                appendLine("CONTEXT")
                extra.forEach { (k, v) -> appendLine("  $k: $v") }
                appendLine()
            }
            appendLine("AUDIO STATE")
            report.lines.forEach { (k, v) -> appendLine("  $k: $v") }
            if (traceLines.isNotEmpty()) {
                appendLine()
                appendLine("RECENT SPEECH EVENTS")
                traceLines.forEach { appendLine("  $it") }
            }
        }

        val dir = java.io.File(context.filesDir, DIR).apply { mkdirs() }
        val file = java.io.File(dir, "incident-$stamp-${trigger.name.lowercase()}.txt")
        file.writeText(text)
        prune(dir)
        file
    } catch (e: Throwable) {
        // Intentionally swallowed: see the note above.
        android.util.Log.w("IncidentRecorder", "could not record: ${e.message}")
        null
    }

    /**
     * Delete the oldest files beyond [max].
     *
     * Separated so the bound can be tested without a device.
     */
    internal fun prune(dir: java.io.File, max: Int = MAX_FILES) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name } // names begin with a sortable timestamp
            ?: return
        if (files.size <= max) return
        files.take(files.size - max).forEach { it.delete() }
    }

    /** Files currently recorded, newest last. */
    fun list(context: Context): List<java.io.File> {
        val dir = java.io.File(context.filesDir, DIR)
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /** The most recent incident, for a one-tap "show me what happened". */
    fun latest(context: Context): java.io.File? = list(context).lastOrNull()
}
