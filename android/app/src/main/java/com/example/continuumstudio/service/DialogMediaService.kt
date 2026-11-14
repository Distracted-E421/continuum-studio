package com.example.continuumstudio.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Browsable media service that keeps Continuum's dialog audio on the Android
 * Auto audio channel.
 *
 * Two things had to be true for Android Auto to keep the route alive, and the
 * app previously had neither:
 *
 *  1. **Own a MediaSession.** The app only read other apps' sessions
 *     (NowPlayingService). AA arbitrates the head unit's audio channel through
 *     sessions, so with none of ours it saw nothing holding the route, tore it
 *     down, and the car reverted to its own tuner - the "radio static" symptom.
 *
 *  2. **Be browsable.** AA lists media apps via MediaLibraryService and
 *     onGetLibraryRoot. A bare MediaSessionService is not enough to appear as a
 *     media source.
 *
 * Additionally the service must be able to run as a foreground service with
 * type mediaPlayback, which requires FOREGROUND_SERVICE_MEDIA_PLAYBACK on
 * Android 14+. Without that permission the service fails to start and the
 * session dies immediately, which looks exactly like "audio gets forced off".
 */
class DialogMediaService : MediaLibraryService() {

    companion object {
        const val TAG = "DialogMediaService"
        const val SESSION_ID = "continuum-dialog"

        /** Root node id for the browsable tree. */
        const val ROOT_ID = "continuum_root"

        /** Node holding pending dialogs. */
        const val DIALOGS_ID = "continuum_dialogs"

        const val KEY_DIALOG_ID = "dialog_id"
        const val KEY_OPTION_COUNT = "option_count"

        /** Foreground notification id. */
        const val FOREGROUND_ID = 7301
        const val CHANNEL_ID = "continuum_media"

        /** Start the media service. Safe to call repeatedly. */
        fun start(context: android.content.Context) {
            val intent = Intent(context, DialogMediaService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /** Low-importance notification required by foreground service rules. */
    private fun buildForegroundNotification(): android.app.Notification {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Dialog audio",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps dialog audio available to Android Auto"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Continuum")
            .setContentText("Dialog audio ready")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .apply { open?.let { setContentIntent(it) } }
            .build()
    }

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        // Stay alive as a foreground service so Android Auto always sees a
        // media source. A session that only exists while speaking is exactly
        // what caused the car to revert to its tuner between dialogs.
        startForeground(FOREGROUND_ID, buildForegroundNotification())

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

        val callback = object : MediaLibrarySession.Callback {

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> =
                // Items already carry a local file URI set by the app.
                Futures.immediateFuture(mediaItems)

            override fun onGetLibraryRoot(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(
                    LibraryResult.ofItem(
                        MediaItem.Builder()
                            .setMediaId(ROOT_ID)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Continuum")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .build()
                            )
                            .build(),
                        params
                    )
                )

            override fun onGetChildren(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                page: Int,
                pageSize: Int,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                // Dialog content is pushed on demand rather than enumerated; an
                // empty child list still makes the app a valid media source.
                val children = if (parentId == ROOT_ID) {
                    ImmutableList.of(
                        MediaItem.Builder()
                            .setMediaId(DIALOGS_ID)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle("Dialogs")
                                    .setIsBrowsable(true)
                                    .setIsPlayable(false)
                                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                                    .build()
                            )
                            .build()
                    )
                } else {
                    ImmutableList.of()
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
            }

            override fun onSubscribe(
                session: MediaLibrarySession,
                browser: MediaSession.ControllerInfo,
                parentId: String,
                params: LibraryParams?
            ): ListenableFuture<LibraryResult<Void>> =
                Futures.immediateFuture(LibraryResult.ofVoid())
        }

        mediaSession = MediaLibrarySession.Builder(this, player, callback)
            .setId(SESSION_ID)
            .apply { sessionActivity?.let { setSessionActivity(it) } }
            .build()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Deliberately does NOT stop the service.
        //
        // Stopping when idle was the cause of the remaining car-audio failure:
        // the session would die the instant a dialog finished speaking, Android
        // Auto would see the media source disappear, and the head unit would
        // revert to its own tuner - which reads as "audio only works while it
        // is talking". A media app must stay resident so AA keeps the route.
        //
        // The session is released in onDestroy when the service is genuinely
        // torn down (by the system or explicitly), not on task removal.
        Log.i(TAG, "task removed; keeping session alive so AA retains the audio route")
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
