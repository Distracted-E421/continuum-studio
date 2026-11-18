package com.example.continuumstudio.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

    /**
     * Starts [DialogMediaService] after boot.
     *
     * **Deliberately does nothing** since 1.19.49.
     *
     * It used to start the media service at boot so Android Auto would find a
     * media source. Observed in a Subaru head unit (2026-11-18): the effect was
     * that Continuum Studio appeared selected in the Android Auto app bar with
     * no dialog present, i.e. the app held the car's media channel
     * permanently while playing nothing. The head unit can then stay routed to
     * a silent source, which presents as "no audio in the car from any app".
     *
     * The receiver is kept (it is still the documented owner of this
     * behaviour) but it now records the event and does not start anything: the
     * session is created when a dialog is actually read.
     */
class MediaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(
            "MediaBootReceiver",
            "boot: not starting the media session; it is created when a dialog is read",
        )
    }
}
