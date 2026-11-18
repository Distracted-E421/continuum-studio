package com.example.continuumstudio.service.tts

/**
 * How dialog speech relates to whatever else is playing.
 *
 * The owner's decision (2026-11-18) is [OVERLAP]: a dialog must never interrupt
 * music in Vivaldi or Spotify. That is not merely a preference - it is the
 * setting that cannot break another app. When we request audio focus, the other
 * app reacts to us: it ducks, or it pauses and (in Vivaldi's case) very often
 * does **not** come back on its own. Declining to request focus at all means the
 * other app is never told anything happened, so there is nothing to get stuck.
 *
 * [DUCK] and [PAUSE] are kept because they are genuinely better in some
 * situations (a quiet room, a phone call), but they are opt-in and their
 * trade-off is stated in the UI rather than implied.
 *
 * Deliberately free of Android imports so the decision logic is unit-testable
 * with plain JUnit; the player maps these onto `AudioManager` constants.
 */
enum class FocusPolicy(val storedValue: String) {
    /** Never request focus. Music keeps playing at its own volume underneath. */
    OVERLAP("overlap"),

    /**
     * Ask for transient focus that lets other audio keep playing more quietly.
     * The other app is expected to raise its volume again when we release.
     */
    DUCK("duck"),

    /** Ask for exclusive transient focus, so other audio stops for the dialog. */
    PAUSE("pause"),
    ;

    /** True when this policy needs us to talk to `AudioManager` at all. */
    val requestsFocus: Boolean get() = this != OVERLAP

    /**
     * True when another app lowering its own volume would be a problem.
     *
     * Under [DUCK] a `LOSS_TRANSIENT_CAN_DUCK` is exactly what we asked for, so
     * it must not be treated as an interruption. Under the other policies that
     * signal means someone else is coming over the top of us.
     */
    val toleratesOthersDucking: Boolean get() = this == DUCK

    companion object {
        /**
         * Used when nothing is stored, and when a stored value is unrecognised.
         *
         * Defaulting to [OVERLAP] is the fail-safe direction: an unreadable
         * preference degrades to "we said nothing to the other app", which cannot
         * leave music paused or broken.
         */
        val DEFAULT = OVERLAP

        /** Parse a persisted value. Unknown or blank input yields [DEFAULT]. */
        fun parse(raw: String?): FocusPolicy {
            val want = raw?.trim()?.lowercase().orEmpty()
            if (want.isEmpty()) return DEFAULT
            return entries.firstOrNull { it.storedValue == want } ?: DEFAULT
        }
    }
}
