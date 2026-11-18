package com.example.continuumstudio.service.tts

/**
 * An incoming audio-focus change, expressed without `AudioManager` constants so
 * the response can be unit-tested.
 *
 * The player maps `AudioManager.AUDIOFOCUS_*` onto these.
 */
enum class FocusChange {
    /** Another app wants us out of the way completely, for now. */
    LOSS_TRANSIENT,

    /** Another app is taking focus for a short blip and we may keep talking quietly. */
    LOSS_TRANSIENT_CAN_DUCK,

    /** We are being displaced; there is no promise of coming back. */
    LOSS,

    /** We are allowed to speak again. */
    GAIN,
}

/** What the player should do about a [FocusChange]. */
enum class FocusResponse {
    /** Not ours to act on. Speech keeps running untouched. */
    IGNORE,

    /**
     * We gave up the mic involuntarily; hold speech and resume automatically
     * when focus is offered back.
     *
     * This is deliberately distinct from a user-initiated pause. The old code
     * called the same `pause()` for both, so a navigation prompt mid-dialog
     * stopped the rest of the reading permanently - in a car, one beep ended it.
     */
    HOLD_FOR_FOCUS,

    /** Resume a hold that [HOLD_FOR_FOCUS] started. A user pause stays paused. */
    RESUME_AFTER_HOLD,
}

/**
 * Decides how speech reacts to other apps' audio-focus behaviour.
 *
 * Kept pure and separate from the player because this is the part that decides
 * whether Vivaldi keeps playing, and that is worth pinning with tests rather
 * than discovering in a car.
 */
object FocusResponder {

    fun respond(policy: FocusPolicy, change: FocusChange): FocusResponse = when (policy) {
        // We never requested focus, so nothing we receive is a real loss for us.
        // Acting on it would be the bug: it would pause speech for an event we
        // are not a participant in.
        FocusPolicy.OVERLAP -> FocusResponse.IGNORE

        FocusPolicy.DUCK -> when (change) {
            // Exactly what we asked other apps for: they quieten down, we talk.
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> FocusResponse.IGNORE
            FocusChange.LOSS_TRANSIENT, FocusChange.LOSS -> FocusResponse.HOLD_FOR_FOCUS
            FocusChange.GAIN -> FocusResponse.RESUME_AFTER_HOLD
        }

        FocusPolicy.PAUSE -> when (change) {
            // A notification blip should not cut a sentence in half; we hold
            // exclusive focus, so tolerating a duck costs nothing audible.
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> FocusResponse.IGNORE
            FocusChange.LOSS_TRANSIENT, FocusChange.LOSS -> FocusResponse.HOLD_FOR_FOCUS
            FocusChange.GAIN -> FocusResponse.RESUME_AFTER_HOLD
        }
    }
}
