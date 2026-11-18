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
        const val KEY_OPTION_LABELS = "option_labels"

        /** Foreground notification id. */
        const val FOREGROUND_ID = 7301
        const val CHANNEL_ID = "continuum_media"

        /**
         * Whether the shared ExoPlayer should manage audio focus itself.
         *
         * Static because the player is owned by this process's service instance
         * while the policy is chosen in the app; the flag is read when the
         * player is built and re-applied whenever the policy changes.
         *
         * Default false = the "overlap" behaviour: the service says nothing to
         * other apps, so music cannot be paused or left stuck by us.
         */
        @Volatile
        private var handleAudioFocus = false

        /** The live player, so a policy change can reach it. */
        @Volatile
        private var activePlayer: ExoPlayer? = null

        /**
         * Apply the user's audio-focus policy to the session player.
         *
         * Rebuilding the attributes on the live player is what makes the setting
         * take effect immediately rather than at the next service start.
         */
        fun setHandleAudioFocus(handle: Boolean) {
            handleAudioFocus = handle
            val p = activePlayer ?: return
            try {
                p.setAudioAttributes(audioAttributes(), handle)
                Log.i(TAG, "exo handleAudioFocus=$handle")
            } catch (e: Exception) {
                Log.w(TAG, "could not update audio focus handling: ${e.message}")
            }
        }

        /**
         * Player audio attributes.
         *
         * USAGE_MEDIA, not USAGE_ASSISTANT.
         *
         * ASSISTANT routes through Android Auto's voice channel, which is opened
         * by speech-to-text and closed the moment STT ends. That made playback
         * survive only while the mic was active and revert to the car's tuner
         * otherwise. MEDIA is the normal music/audiobook channel, which AA keeps
         * alive for as long as the session exists.
         */
        private fun audioAttributes() = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        /**
         * Tear the session down once there is nothing to play.
         *
         * This service used to be resident for the whole uptime: it started from
         * a BOOT_COMPLETED receiver and called `startForeground` in `onCreate`.
         * Because it declares `<uses name="media"/>` to Android Auto, that made
         * Continuum a permanently-registered media source. Observed in a Subaru
         * head unit (2026-11-18): Continuum Studio appeared highlighted in the
         * Android Auto app bar with no dialog present at all, i.e. it held the
         * media channel while completely idle.
         *
         * A phone media app that keeps the channel and never plays anything can
         * leave the head unit routed to it with silence - which looks
         * indistinguishable from "the car has no audio at all". Since dialog
         * reading is normally off, this session was holding the route for a
         * feature the user had not enabled.
         */
        fun stop(context: android.content.Context) {
            try {
                context.stopService(Intent(context, DialogMediaService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "could not stop media service: ${e.message}")
            }
        }

        /**
         * Start the media service so a dialog can be played through it.
         *
         * Safe to call repeatedly.
         */
        fun start(context: android.content.Context) {
            val intent = Intent(context, DialogMediaService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Background-start restrictions (Android 12+) or a
                // ForegroundServiceStartNotAllowedException both land here.
                // Letting this propagate crashed the app; the service also
                // starts from the foreground activity, so failing quietly is
                // correct here.
                Log.w(TAG, "could not start media service from background: ${e.message}")
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
                // USAGE_MEDIA, not USAGE_ASSISTANT - see audioAttributes().
                // handleAudioFocus follows the user's policy; false (overlap) is
                // the default because it is the setting that cannot pause
                // someone else's music.
                audioAttributes(),
                handleAudioFocus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        activePlayer = player

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
        //
        // Note: liveness is now bounded by the *read* rather than by the
        // process. AudioPrefs/dialog completion calls [stop] when playback ends,
        // so the session is not held while idle - see [stop] for why that
        // matters in a car.
        Log.i(TAG, "task removed; keeping session alive so AA retains the audio route")
    }

    /**
     * Called when the last segment of a read has finished.
     *
     * Hands the audio route back to whatever the user was listening to. The
     * delay lets the final clip finish audibly before the session disappears;
     * tearing it down at once made the car cut the last word.
     */
    fun scheduleStopWhenIdle(reason: String) {
        Log.i(TAG, "stop scheduled when idle: $reason")
        stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        activePlayer = null
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
    optionLabels: List<String> = emptyList(),
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
                    putStringArrayList(
                        DialogMediaService.KEY_OPTION_LABELS,
                        ArrayList(optionLabels)
                    )
                })
            }
            .build()
    )
    .build()
