package com.example.continuumstudio.service.tts

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.continuumstudio.service.DialogMediaService
import com.example.continuumstudio.service.dialogMediaItem
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.io.File

/**
 * Sends dialog audio through [DialogMediaService] instead of a local MediaPlayer.
 *
 * Playing via a MediaSession is not cosmetic: Android Auto arbitrates the head
 * unit's audio channel through media sessions. A bare MediaPlayer holds audio
 * focus but has no session, so the projection host sees nothing and reverts the
 * car to its own tuner - the "radio static" symptom.
 *
 * Connection failures are non-fatal: callers fall back to local playback.
 *
 * The transport accessors below exist so [DialogAudioPlayer] can drive either
 * backend through one interface. They all return neutral values when no
 * controller is connected, which keeps the fallback path simple.
 */
object MediaSessionBridge {

    private const val TAG = "MediaSessionBridge"

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var connecting: ListenableFuture<MediaController>? = null

    /** True when a controller exists and its session is live. */
    fun isConnected(): Boolean = controller?.isConnected == true

    /**
     * Queue [file] on the media session.
     *
     * @return true if the item was accepted by the session. false means the
     *         caller should use its own player instead.
     */
    fun play(
        context: Context,
        dialogId: String,
        file: File,
        title: String,
        prompt: String,
        optionCount: Int,
        optionLabels: List<String> = emptyList(),
    ): Boolean {
        val c = controller ?: return false
        if (!c.isConnected) return false

        return try {
            val item = dialogMediaItem(
                dialogId = dialogId,
                fileUri = file.toURI().toString(),
                title = title,
                prompt = prompt,
                optionCount = optionCount,
                optionLabels = optionLabels,
            )
            logState("before-set")
            c.setMediaItem(item)
            c.prepare()
            c.play()
            logState("after-play")
            true
        } catch (e: Exception) {
            Log.e(TAG, "play failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // --- Transport (neutral when disconnected) ----------------------------

    /**
     * Tell the session whether it may request audio focus for itself.
     *
     * ExoPlayer was built with `handleAudioFocus = false`, which was correct
     * when overlap was the only behaviour (and is why the car path never
     * interrupted anything). Under [FocusPolicy.DUCK] or [FocusPolicy.PAUSE]
     * the session must request focus instead, otherwise the setting would be
     * honoured on the phone and silently ignored in the car.
     *
     * Applied on the service's own player rather than through the controller:
     * `setAudioAttributes` is an unstable remote command, and the service lives
     * in this process anyway, so there is no reason to round-trip it.
     */
    fun applyFocusPolicy(policy: FocusPolicy) {
        DialogMediaService.setHandleAudioFocus(policy.requestsFocus)
        Log.i(TAG, "focus policy sent to session: ${policy.storedValue}")
    }

    fun isPlaying(): Boolean = controller?.isPlaying == true

    /** True when the current item has finished and nothing is queued. */
    fun hasEnded(): Boolean {
        val c = controller ?: return true
        return c.playbackState == androidx.media3.common.Player.STATE_ENDED
    }

    fun durationMs(): Int = controller?.duration?.takeIf { it > 0 }?.toInt() ?: 0

    fun positionMs(): Int = controller?.currentPosition?.toInt() ?: 0

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    fun pause() {
        controller?.pause()
    }

    fun resume() {
        controller?.play()
    }

    /**
     * Pause only if something is actually playing.
     *
     * Used by [DialogAudioPlayer.stop] so a plain stop does not tear down a
     * session that another surface is using.
     */
    fun pauseSafely() {
        val c = controller ?: return
        if (c.isConnected && c.isPlaying) c.pause()
    }

    /**
     * Establish the controller connection. Safe to call repeatedly; the first
     * call wins and later calls are no-ops while a connection is pending or up.
     */
    fun connect(context: Context, onReady: (Boolean) -> Unit = {}) {
        if (controller?.isConnected == true) {
            onReady(true)
            return
        }
        if (connecting != null) return

        val token = SessionToken(
            context,
            ComponentName(context, DialogMediaService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        connecting = future

        future.addListener({
            try {
                controller = future.get()
                Log.i(TAG, "CONNECTED to DialogMediaService")
                onReady(true)
            } catch (e: Exception) {
                // Service not registered or app in a bad state; caller falls back.
                Log.e(TAG, "CONTROLLER CONNECT FAILED: ${e.javaClass.simpleName}: ${e.message}")
                controller = null
                onReady(false)
            } finally {
                connecting = null
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * Log the live session state. Called around playback so the car-side
     * behaviour can be correlated with what the app actually did.
     */
    fun logState(stage: String) {
        val c = controller
        if (c == null) {
            Log.i(TAG, "[$stage] controller=null (session not connected)")
            return
        }
        Log.i(
            TAG,
            "[$stage] connected=${c.isConnected} playing=${c.isPlaying} " +
                "playWhenReady=${c.playWhenReady} items=${c.mediaItemCount} " +
                "state=${c.playbackState}"
        )
    }

    fun release() {
        controller?.run {
            if (isConnected) release()
        }
        controller = null
    }
}
