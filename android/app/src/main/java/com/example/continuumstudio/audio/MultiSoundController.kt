package com.example.continuumstudio.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Controls Samsung MultiSound (multi-app audio) without root.
 *
 * All of this was verified against a Galaxy S24 Ultra (SM-S928U1, One UI 9,
 * Android 17) over ADB shell. The important findings that shaped this class:
 *
 *  - MultiSound is a **framework** feature inside `system_server`
 *    (`SEC_AUDIO_MULTI_SOUND=true`). `com.samsung.android.soundassistant` is an
 *    ordinary /data/app user app that only provides the UI. There is no
 *    `semaudio` binder service and `cmd audio` has **no** per-uid volume command.
 *  - Its configuration lives in `Settings.System`, which shell can write:
 *    `multi_audio_focus_enabled`, `multi_control_enabled`, `multisound_app`.
 *  - Per-app volume **values** are held in memory in system_server and are
 *    readable (`dumpsys audio`) but not writable from shell. This class
 *    therefore does not promise to set volumes; it toggles the feature and the
 *    focus behaviour that actually causes the "it stops working in the car" symptom.
 *  - `TAKE_AUDIO_FOCUS` is a real AppOp that shell can set. Marking a media app
 *    `ignore` stops it stealing focus from whatever else is playing, which is
 *    the behaviour Android Auto otherwise reinjects on every route change.
 *
 * Every method is a no-op returning [Result.failure] when Shizuku is not running,
 * so callers can surface "grant Shizuku" instead of silently doing nothing.
 */
class MultiSoundController(private val context: Context) {

    companion object {
        private const val TAG = "MultiSoundController"

        const val SOUND_ASSISTANT_PKG = "com.samsung.android.soundassistant"
        const val SETTINGS_NS = "system"

        // Keys verified present on One UI 9.
        const val KEY_MULTI_AUDIO_FOCUS = "multi_audio_focus_enabled"
        const val KEY_MULTI_CONTROL = "multi_control_enabled"
        const val KEY_MULTISOUND_APP = "multisound_app"

        /** Media apps that commonly steal focus; user-visible as suggestions. */
        val COMMON_MEDIA_APPS = listOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.youtube" to "YouTube",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.audible.application" to "Audible",
            "com.amazon.avod.thirdpartyclient" to "Prime Video",
            "com.vivaldi.browser" to "Vivaldi",
        )
    }

    fun isSupportedDevice(): Boolean =
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun isSoundAssistantInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SOUND_ASSISTANT_PKG, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** True when Shizuku has a live shell server we can borrow. */
    suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            ShizukuBridge.isAvailable()
        } catch (e: Throwable) {
            Log.d(TAG, "Shizuku unavailable: ${e.message}")
            false
        }
    }

    /** Current on/off state of the multi-audio-focus feature. */
    suspend fun isMultiAudioFocusEnabled(): Boolean = readSetting(KEY_MULTI_AUDIO_FOCUS) == "1"

    suspend fun isMultiControlEnabled(): Boolean = readSetting(KEY_MULTI_CONTROL) == "1"

    /**
     * Enable or disable multi-app audio focus.
     *
     * This is the setting that makes several apps able to play at once; it is
     * what Sound Assistant's "Multi Sound" toggle ultimately drives.
     */
    suspend fun setMultiAudioFocus(enabled: Boolean): Result<Unit> =
        writeSetting(KEY_MULTI_AUDIO_FOCUS, if (enabled) 1 else 0)

    suspend fun setMultiControl(enabled: Boolean): Result<Unit> =
        writeSetting(KEY_MULTI_CONTROL, if (enabled) 1 else 0)

    /**
     * Stop [pkg] from stealing audio focus.
     *
     * Android Auto re-evaluates focus on every audio-route change, so an app
     * that grabs focus will interrupt playback no matter what MultiSound does.
     * Marking it `ignore` is the durable fix for that specific failure.
     */
    suspend fun setFocusStealingAllowed(pkg: String, allowed: Boolean): Result<Unit> =
        shell("cmd appops set $pkg TAKE_AUDIO_FOCUS ${if (allowed) "default" else "ignore"}")

    /** Whether [pkg] is currently allowed to steal focus. */
    suspend fun isFocusStealingAllowed(pkg: String): Boolean? {
        val out = readSettingRaw("cmd appops get $pkg TAKE_AUDIO_FOCUS") ?: return null
        return !out.contains("ignore")
    }

    /**
     * Apply the recommended profile for car use.
     *
     * Enables multi-audio focus and suppresses focus-stealing for the given
     * packages. Deliberately does not touch per-app volume values - those are
     * not writable from shell, and silently failing at that would be worse than
     * not offering it.
     */
    suspend fun applyCarProfile(packages: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext Result.failure(
            IllegalStateException("Shizuku is not running. Start Shizuku and grant permission.")
        )

        var applied = 0
        setMultiAudioFocus(true).onSuccess { applied++ }
        setMultiControl(true).onSuccess { applied++ }
        packages.forEach { pkg ->
            setFocusStealingAllowed(pkg, allowed = false).onSuccess { applied++ }
        }
        Result.success(applied)
    }

    /** Restore every app in [packages] to default focus behaviour. */
    suspend fun clearFocusOverrides(packages: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        var restored = 0
        packages.forEach { pkg ->
            setFocusStealingAllowed(pkg, allowed = true).onSuccess { restored++ }
        }
        Result.success(restored)
    }

    // --- internals -------------------------------------------------------

    private suspend fun writeSetting(key: String, value: Int): Result<Unit> =
        shell("settings put $SETTINGS_NS $key $value")

    private suspend fun readSetting(key: String): String? {
        val out = readSettingRaw("settings get $SETTINGS_NS $key") ?: return null
        return out.trim().ifEmpty { null }
    }

    private suspend fun readSettingRaw(command: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!ShizukuBridge.isAvailable()) return@withContext null
            ShizukuBridge.exec(command).getOrNull()
        } catch (e: Throwable) {
            Log.d(TAG, "read failed for '$command': ${e.message}")
            null
        }
    }

    private suspend fun shell(command: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ShizukuBridge.isAvailable()) {
                return@withContext Result.failure(
                    IllegalStateException("Shizuku is not running. Start Shizuku and grant permission.")
                )
            }
            val r = ShizukuBridge.exec(command)
            if (r.isSuccess) Result.success(Unit) else Result.failure(
                IllegalStateException("Command failed: $command")
            )
        } catch (e: Throwable) {
            Log.w(TAG, "shell '$command' threw: ${e.message}")
            Result.failure(e)
        }
    }
}
