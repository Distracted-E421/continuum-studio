package com.example.continuumstudio.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * MediaSessionService that keeps Continuum's dialog audio on the Android Auto
 * audio channel.
 *
 * Why this exists: the app previously only *read* other apps' MediaSessions
 * (NowPlayingService) and never owned one. On Android Auto, the head unit
 * arbitrates audio through media sessions - with no session of ours, the
 * projection host sees nothing holding the channel, tears the route down, and
 * the car falls back to its own radio. That is the "radio static" symptom.
 *
 * With this service the app is a first-class media app. Dialog speech becomes a
 * MediaItem played by ExoPlayer, so:
 *   - Android Auto keeps the USB/Bluetooth audio route alive
 *   - play/pause/seek on the car screen and steering wheel work natively
 *   - MultiSound has something legitimate to mix against
 *
 * Dialogs are exposed as MediaItems whose title/artist carry the prompt text,
 * so the car's now-playing view can render the question without a template app.
 */
class DialogMediaService : MediaSessionService() {

    companion object {
        const val TAG = "DialogMediaService"

        /** Session id used by MediaController clients to find us. */
        const val SESSION_ID = "continuum-dialog"

        /** Metadata keys used to carry dialog structure through to the car. */
        const val KEY_DIALOG_ID = "dialog_id"
        const val KEY_OPTION_COUNT = "option_count"
    }

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_ASSISTANT)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivity = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): com.google.common.util.concurrent.ListenableFuture<MutableList<MediaItem>> {
                    // Items arrive carrying a local file URI set by the app when
                    // it enqueues speech; nothing to resolve here, so pass them
                    // through unchanged.
                    return com.google.common.util.concurrent.Futures.immediateFuture(mediaItems)
                }
            })
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    /**
     * Drop the session when the user swipes the app away, but keep it alive
     * while playing so Android Auto does not lose the route mid-dialog.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = mediaSession?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/** Build a MediaItem for a synthesized dialog clip. */
fun dialogMediaItem(
    dialogId: String,
    fileUri: String,
    title: String,
    prompt: String,
    optionCount: Int,
): MediaItem = MediaItem.Builder()
    .setUri(fileUri)
    .setMediaId(dialogId)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(prompt)
            .setAlbumTitle("Continuum")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply {
                // Option count lets the car UI know how many choice buttons to show.
                setExtras(android.os.Bundle().apply {
                    putInt(DialogMediaService.KEY_OPTION_COUNT, optionCount)
                    putString(DialogMediaService.KEY_DIALOG_ID, dialogId)
                })
            }
            .build()
    )
    .build()
