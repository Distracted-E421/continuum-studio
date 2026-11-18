package com.example.continuumstudio.service

import android.content.Context
import android.util.Log
import com.example.continuumstudio.network.DialogEvent
import com.example.continuumstudio.network.DialogWebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Watches the dialog server independently of the app's UI.
 *
 * ## Why this exists
 *
 * The WebSocket lived only inside `DialogViewModel`, so it existed only while the
 * app's screen was alive. That meant a dialog arriving while the app was
 * backgrounded - or the phone was locked in a car cradle - produced nothing at
 * all: no notification, no audio. The notification service was a foreground
 * service that listened to no source of its own.
 *
 * This holds its own connection so the phone hears dialogs regardless of what the
 * UI is doing.
 *
 * ## Not duplicating the app
 *
 * When the app is in the foreground it has its own connection and shows its own
 * notifications, so this watcher stays quiet ([setAppInForeground]). Two live
 * connections would otherwise mean every dialog notified twice.
 *
 * The connection is deliberately lazy in effect: it connects when the process is
 * alive and stops with the service, rather than trying to survive process death.
 * Android will restart the foreground service if needed.
 */
class BackgroundDialogWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDialogReady: (DialogReady) -> Unit,
) {

    companion object {
        private const val TAG = "BgDialogWatcher"

        /**
         * True while a UI is on screen.
         *
         * Set by the Activity, read here. Process-wide on purpose: there is one
         * app process, so a volatile flag is enough and avoids a broadcast.
         */
        @Volatile
        var appInForeground: Boolean = false
            private set

        fun setAppInForeground(foreground: Boolean) {
            appInForeground = foreground
        }
    }

    /** Dialog details sufficient to notify and speak. */
    data class DialogReady(
        val id: String,
        val title: String,
        val prompt: String,
        val dialogType: String,
        val optionsJson: String?,
    )

    private var client: DialogWebSocketClient? = null
    private var eventJob: Job? = null

    /** Connect using the saved endpoint. Safe to call repeatedly. */
    fun start() {
        if (client != null) return
        val endpoint = EndpointConfig.activeEndpoint(context) ?: run {
            Log.i(TAG, "no enabled endpoint configured; not connecting")
            return
        }

        val ws = DialogWebSocketClient(scope)
        client = ws

        eventJob = scope.launch {
            ws.events.collect { event ->
                when (event) {
                    is DialogEvent.DialogReady -> {
                        // Only act when the UI is not already handling it.
                        if (appInForeground) {
                            Log.d(TAG, "app in foreground; leaving ${event.id.take(8)} to it")
                            return@collect
                        }
                        onDialogReady(
                            DialogReady(
                                id = event.id,
                                title = event.title,
                                prompt = event.prompt,
                                dialogType = event.dialogType,
                                optionsJson = event.optionsJson,
                            )
                        )
                    }
                    is DialogEvent.Connected ->
                        Log.i(TAG, "background watcher connected")
                    is DialogEvent.Disconnected ->
                        Log.i(TAG, "background watcher disconnected: ${event.reason}")
                    else -> Unit
                }
            }
        }

        ws.connect(endpoint.url, endpoint.clientId, endpoint.clientSecret)
        Log.i(TAG, "connecting to ${endpoint.url}")
    }

    fun stop() {
        eventJob?.cancel()
        eventJob = null
        client?.disconnect()
        client = null
    }
}
