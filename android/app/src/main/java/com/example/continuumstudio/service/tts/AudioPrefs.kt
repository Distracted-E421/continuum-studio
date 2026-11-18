package com.example.continuumstudio.service.tts

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.continuumstudio.viewmodel.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Audio preferences, shared by every surface that speaks a dialog.
 *
 * ## Why this exists
 *
 * The settings lived only in `DialogViewModel`, which meant the notification
 * service - the path that actually fires when a dialog arrives while the app is
 * closed or in the background - spoke with hardcoded defaults. Voice, speed and
 * the wait budget were all ignored there, so a dialog read from the car did not
 * sound like one read from the app. `DialogNotificationService.configureTTS` was
 * written for this and then never called.
 *
 * Preferences are stored in the same DataStore the ViewModel writes, so there is
 * one source of truth rather than two that can drift.
 *
 * Keys are duplicated deliberately rather than shared from the ViewModel: the
 * ViewModel depends on this module's player, so importing it back would create a
 * cycle. [AudioPrefs.KEYS] are asserted against the ViewModel's in tests.
 */
object AudioPrefs {

    private const val TAG = "AudioPrefs"

    /** Must match `DialogViewModel.PrefsKeys` for the same store. */
    object Keys {
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val TTS_SPEED = floatPreferencesKey("tts_speed")
        val TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        val AUDIO_WAIT_MS = longPreferencesKey("audio_wait_ms")

        /**
         * How speech relates to other apps' audio.
         *
         * Stored as the policy's own string rather than an enum ordinal, so a
         * reorder of the enum cannot silently change what a user chose.
         */
        val FOCUS_POLICY = stringPreferencesKey("audio_focus_policy")
    }

    /** Snapshot of the user's audio settings. */
    data class Snapshot(
        val voice: String = EasyVoiceClient.DEFAULT_VOICE,
        val speed: Float = 1.0f,
        val waitMs: Long = DialogAudioPlayer.DEFAULT_WAIT_MS,
        val enabled: Boolean = false,
        val focusPolicy: FocusPolicy = FocusPolicy.DEFAULT,
    )

    /**
     * Read the current preferences.
     *
     * Blocking on purpose: callers are Service callbacks (`onStartCommand`) where
     * there is no scope to suspend in, and the read is a single small file. A
     * timeout is still applied so a wedged DataStore cannot hang a notification.
     */
    fun load(context: Context): Snapshot {
        // Deliberately reuses the ViewModel's DataStore extension property.
        // Creating a second preferencesDataStore for the same file name throws at
        // runtime ("multiple DataStores active for the same file"), so there must
        // be exactly one instance per process.
        val store = context.applicationContext.dataStore
        return try {
            val prefs = runBlocking {
                withTimeoutOrNull(2_000L) { store.data.first() }
            } ?: return Snapshot()
            Snapshot(
                voice = prefs[Keys.TTS_VOICE] ?: EasyVoiceClient.DEFAULT_VOICE,
                speed = prefs[Keys.TTS_SPEED] ?: 1.0f,
                waitMs = prefs[Keys.AUDIO_WAIT_MS] ?: DialogAudioPlayer.DEFAULT_WAIT_MS,
                enabled = prefs[Keys.TTS_ENABLED] ?: false,
                focusPolicy = FocusPolicy.parse(prefs[Keys.FOCUS_POLICY]),
            )
        } catch (e: Exception) {
            Log.d(TAG, "could not read audio prefs: ${e.message}")
            Snapshot()
        }
    }

    /**
     * Apply persisted settings to a helper.
     *
     * Kept here so every surface configures the same way. Previously the app
     * screen used current values and the service used defaults.
     */
    fun applyTo(helper: com.example.continuumstudio.service.DialogTTSHelper, snapshot: Snapshot) {
        helper.ttsEnabled = snapshot.enabled
        helper.speechRate = snapshot.speed
        helper.setGenerationTimeout(snapshot.waitMs)
        // Voice lives on the shared player, so setting it here also covers the
        // case where the service initialised before the app's view model.
        helper.audioPlayer.voice = snapshot.voice
        // Focus policy also lives on the shared player: it decides whether the
        // other app is told anything at all, so the notification path and the
        // in-app path must not disagree about it.
        helper.audioPlayer.focusPolicy = snapshot.focusPolicy
        // The session player is a separate engine from the local one, so it needs
        // the policy too or the car path would keep its own default.
        com.example.continuumstudio.service.tts.MediaSessionBridge.applyFocusPolicy(snapshot.focusPolicy)
        Log.d(
            TAG,
            "applied: enabled=${snapshot.enabled} rate=${snapshot.speed} " +
                "wait=${snapshot.waitMs}ms voice=${snapshot.voice} " +
                "focus=${snapshot.focusPolicy.storedValue}",
        )
    }
}
