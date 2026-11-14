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
 */
object MediaSessionBridge {

    private const val TAG = "MediaSessionBridge"

    @Volatile
    private var controller: MediaController? = null

    @Volatile
    private var connecting: ListenableFuture<MediaController>? = null

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
            )
            c.setMediaItem(item)
            c.prepare()
            c.play()
            true
        } catch (e: Exception) {
            Log.w(TAG, "play failed: ${e.message}")
            false
        }
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
                Log.d(TAG, "connected to DialogMediaService")
                onReady(true)
            } catch (e: Exception) {
                // Service not registered or app in a bad state; caller falls back.
                Log.w(TAG, "controller connect failed: ${e.message}")
                controller = null
                onReady(false)
            } finally {
                connecting = null
            }
        }, MoreExecutors.directExecutor())
    }

    fun release() {
        controller?.run {
            if (isConnected) release()
        }
        controller = null
    }
}
