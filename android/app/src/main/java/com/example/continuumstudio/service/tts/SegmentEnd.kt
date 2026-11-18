package com.example.continuumstudio.service.tts

/**
 * Decides whether a segment of dialog audio has actually finished.
 *
 * This was wrong twice, and both times the symptom was the same: audio "jumped
 * over itself", cutting a word in half and starting the next segment on top of
 * it. The two historical mistakes were
 *
 *  1. treating `!isPlaying` as finished, which is also true while starting and
 *     while buffering, and
 *  2. treating "stopped with unknown duration" as finished, which is also true
 *     while another app holds audio focus.
 *
 * It is extracted here, and pure, so those cases are pinned by tests instead of
 * being re-discovered in a car. [DialogAudioPlayer] remains responsible for
 * gathering the observations.
 */
object SegmentEnd {

    /** How close to the known duration counts as the end, in milliseconds. */
    const val END_TOLERANCE_MS = 80

    /** One observation of the player. */
    data class Observation(
        /** Playback position reported by the player. */
        val positionMs: Int,
        /** Clip duration, or <= 0 when the player could not report one. */
        val durationMs: Int,
        /** The player says it is playing right now. */
        val playingNow: Boolean,
        /** We have observed it playing at least once during this segment. */
        val sawPlaying: Boolean,
        /** MediaSession reported STATE_ENDED. */
        val sessionEnded: Boolean,
        /** Another app currently holds audio focus from us. */
        val heldByFocus: Boolean,
        /** Milliseconds since we last observed it playing. */
        val msSincePlaying: Long,
        /** Grace period before a "stopped" reading is believable. */
        val startGraceMs: Long,
    )

    /**
     * True only when the segment is genuinely over.
     *
     * Every ambiguous state - starting, buffering, held by another app - returns
     * false, because the cost of a wrong `true` is truncated speech while the
     * cost of a wrong `false` is a little latency.
     */
    fun isFinished(o: Observation): Boolean {
        // An explicit end signal outranks everything else.
        if (o.sessionEnded) return true

        // Held by another app: the position is frozen and it is not playing, but
        // the segment is merely paused. Without this the "unknown duration and it
        // stopped" branch below ended the read on the first phone call.
        //
        // This deliberately defers even the reached-the-end case: `position` is
        // stale while held, so acting on it would advance the script while we
        // cannot speak. On resume the position is re-read and the normal end
        // check fires, so nothing is lost - the caller bounds the wait with
        // MAX_HOLD_MS so an unrecoverable loss cannot hang the script.
        if (o.heldByFocus) return false

        val reachedEnd = o.durationMs > 0 && o.positionMs >= o.durationMs - END_TOLERANCE_MS
        if (reachedEnd) return true

        // Unknown duration, it had been playing, and enough time passed that this
        // is not the start-up window: the only safe reading is that it ended.
        return o.sawPlaying &&
            !o.playingNow &&
            o.msSincePlaying > o.startGraceMs &&
            o.durationMs <= 0
    }
}
