package com.example.continuumstudio.service.tts

import android.content.Context
import android.util.Log

/**
 * The single owner of dialog audio playback.
 *
 * ## Why a hub exists
 *
 * [DialogTTSHelper] used to be constructed three times - once by
 * `DialogViewModel`, once by `DialogNotificationService`, once by
 * `AutoReplyService` - and each one built its own [DialogAudioPlayer]. Three
 * players meant three `MediaPlayer`s, three audio-focus requests, three
 * playback state flows and three caches. Consequences that were visible in use:
 *
 *  - The dialog screen could not show playback controls, because the clip
 *    playing might be owned by a different helper than the one the screen could
 *    see. `DialogAudioBar` was written and then left unwired for exactly this
 *    reason.
 *  - Answering from the notification shade and then opening the app could leave
 *    a stale clip playing.
 *  - Two focus requests racing made the car drop our audio route.
 *
 * A hub fixes all three by making the process hold exactly one player. Callers
 * still get their own [DialogTTSHelper] (it carries per-surface settings like
 * `readOptionsAloud`), but the audio itself is shared.
 *
 * Access is only from the main thread; [DialogAudioPlayer] is main-confined.
 */
object AudioHub {

    private const val TAG = "AudioHub"

    @Volatile
    private var player: DialogAudioPlayer? = null

    /** Returns the shared player, creating it on first use. */
    fun player(context: Context): DialogAudioPlayer {
        player?.let { return it }
        return synchronized(this) {
            player ?: DialogAudioPlayer(context.applicationContext).also {
                player = it
                Log.i(TAG, "created shared dialog audio player")
            }
        }
    }

    /**
     * The shared player if one already exists, else null.
     *
     * Read-only surfaces (the audio bar on a screen that is merely being
     * recomposed) use this so they never create a player as a side effect of
     * drawing.
     */
    fun existing(): DialogAudioPlayer? = player

    /**
     * Release everything. Called when the last owner goes away (the ViewModel
     * clearing, or the notification service being destroyed).
     *
     * Safe to call from any surface: another owner may still be alive, in which
     * case this stops the current clip but keeps the player usable. The object
     * itself is cheap, so it is deliberately kept rather than torn down and
     * rebuilt - rebuilding is what caused a visible gap in the car's audio route.
     */
    fun stopAll() {
        player?.stop()
    }

    /** True when audio is currently playing through the shared player. */
    fun isPlaying(): Boolean = player?.isPlaying?.value == true
}
