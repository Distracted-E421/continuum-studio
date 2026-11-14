package com.example.continuumstudio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts [DialogMediaService] after boot.
 *
 * Android Auto resolves media sources through the media session layer. If the
 * service is not running when the car connects, the head unit may find no source
 * and keep its own tuner, so we make sure the session exists before that happens.
 */
class MediaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            DialogMediaService.start(context)
            Log.i("MediaBootReceiver", "started DialogMediaService after boot")
        } catch (e: Exception) {
            // Android 12+ can refuse a foreground start from a receiver; the
            // service also starts on app launch, so this is not fatal.
            Log.w("MediaBootReceiver", "could not start media service: ${e.message}")
        }
    }
}
