package com.example.continuumstudio.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log

/**
 * Reads every live media session on the device, without Shizuku.
 *
 * ## Why this matters
 *
 * The answer to "who is holding the car's audio channel" lives in the media session
 * list, and `dumpsys media_session` was the only way to see it - which needs Shizuku
 * and therefore does not work when Shizuku is down (which it was on the owner's phone,
 * on mobile data, with no wireless debugging).
 *
 * But the app already holds `NotificationListenerService` permission for
 * `NowPlayingService`, and that permission is exactly what unlocks
 * `MediaSessionManager.getActiveSessions`. So this information is available through
 * the UI, with no root, no Shizuku and no network.
 *
 * Read-only: it queries sessions and reports them. It never sends transport commands.
 */
object SessionInspector {

    private const val TAG = "SessionInspector"

    /** One live session, flattened for display and reporting. */
    data class SessionInfo(
        val packageName: String,
        val isActive: Boolean,
        val playbackState: String,
        val playWhenReady: Boolean,
        val title: String?,
        val artist: String?,
        val hasAudioFocusHint: Boolean,
    ) {
        fun describe(): String = buildString {
            append(packageName)
            if (isActive) append(" [ACTIVE]")
            append(" state=").append(playbackState)
            append(" playWhenReady=").append(playWhenReady)
            title?.let { append(" title=\"").append(it).append('"') }
            artist?.let { append(" artist=\"").append(artist).append('"') }
        }
    }

    /**
     * True when this app may enumerate sessions.
     *
     * Requires the notification listener to be enabled by the user in system settings;
     * the permission itself is not grantable at runtime.
     */
    fun isAvailable(context: Context): Boolean = runCatching {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        // Passing a null component probes the permission without depending on a
        // specific listener instance being bound.
        msm.getActiveSessions(null)
        true
    }.getOrElse {
        Log.d(TAG, "session enumeration unavailable: ${it.message}")
        false
    }

    /**
     * Snapshot every active session.
     *
     * @param listenerComponent the notification listener that holds the permission.
     *        Passing it explicitly works even when the service is not currently bound.
     */
    fun sessions(context: Context, listenerComponent: ComponentName?): List<SessionInfo> =
        runCatching {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            msm.getActiveSessions(listenerComponent).map { c -> toInfo(c) }
        }.getOrElse {
            Log.d(TAG, "could not enumerate sessions: ${it.message}")
            emptyList()
        }

    private fun toInfo(controller: MediaController): SessionInfo = SessionInfo(
        packageName = controller.packageName,
        isActive = controller.sessionActivity != null,
        playbackState = when (controller.playbackState?.state) {
            PlaybackState.STATE_NONE -> "NONE"
            PlaybackState.STATE_STOPPED -> "STOPPED"
            PlaybackState.STATE_PAUSED -> "PAUSED"
            PlaybackState.STATE_PLAYING -> "PLAYING"
            PlaybackState.STATE_FAST_FORWARDING -> "FAST_FORWARDING"
            PlaybackState.STATE_REWINDING -> "REWINDING"
            PlaybackState.STATE_BUFFERING -> "BUFFERING"
            PlaybackState.STATE_ERROR -> "ERROR"
            PlaybackState.STATE_CONNECTING -> "CONNECTING"
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> "SKIP_PREV"
            PlaybackState.STATE_SKIPPING_TO_NEXT -> "SKIP_NEXT"
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> "SKIP_QUEUE"
            null -> "UNKNOWN"
            else -> "state=${controller.playbackState?.state}"
        },
        playWhenReady = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
        title = controller.metadata?.description?.title?.toString(),
        artist = controller.metadata?.description?.subtitle?.toString(),
        // There is no public API for "this session holds audio focus"; PLAYING on the
        // active session is the closest observable proxy, and it is what actually
        // matters for "is something else making sound".
        hasAudioFocusHint = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
    )

    /**
     * Sessions other than [excludePackage] that are currently producing sound.
     *
     * This is the specific question for the car problem: if another app is playing
     * while our dialog is silent, our audio path is broken. If nothing else is playing
     * either, the phone is not routing media anywhere - which points at the head unit.
     */
    fun othersPlaying(context: Context, listenerComponent: ComponentName?, excludePackage: String): List<SessionInfo> =
        sessions(context, listenerComponent).filter {
            it.packageName != excludePackage && it.playbackState == "PLAYING"
        }

    /** Human summary for the diagnostics screen. */
    fun describe(context: Context, listenerComponent: ComponentName?): String {
        if (!isAvailable(context)) {
            return "unavailable - enable the Continuum notification listener in system settings"
        }
        val all = sessions(context, listenerComponent)
        if (all.isEmpty()) return "no active media sessions"
        return all.joinToString("\n") { it.describe() }
    }
}
