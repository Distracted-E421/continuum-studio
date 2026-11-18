package com.example.continuumstudio.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

        /** Where pre-change focus modes are remembered so they can be restored. */
        const val PRIOR_MODES_PREFS = "multisound_prior_modes"

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

        /** AppOps modes we care about for TAKE_AUDIO_FOCUS. */
        enum class FocusMode { ALLOW, DEFAULT, IGNORE, UNSET }

        /**
         * Apps whose focus-stealing we actually care about, i.e. the ones that
         * would interrupt a dialog.
         *
         * Vivaldi is deliberately **not** included: the owner's requirement is
         * that our audio work must not disturb browser playback, and blanket
         * suppressing a browser's focus is exactly the kind of change that
         * breaks someone else's app.
         */
        val DIALOG_COMPETITORS = listOf(
            "com.spotify.music" to "Spotify",
            "com.audible.application" to "Audible",
        )

        /**
         * Parse `cmd appops get` output for TAKE_AUDIO_FOCUS.
         *
         * Pure, so it is testable without a device. The previous check was
         * `!output.contains("ignore")`, which gave the right answer for the shapes
         * seen on one device but reported "allowed" for any unrecognised format -
         * and "allowed" is exactly the state that breaks car playback.
         *
         * Returns null when the output is unrecognisable, so callers treat it as
         * unknown rather than guessing.
         */
        fun parseFocusMode(output: String): FocusMode? {
            val text = output.trim()
            if (text.isEmpty()) return null
            if (text.startsWith("No operations", ignoreCase = true)) return FocusMode.UNSET
            // Only the first line describes the op; anything after is context.
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            val mode = firstLine.substringAfterLast(':', "").trim().lowercase()
            return when (mode) {
                "allow" -> FocusMode.ALLOW
                "default" -> FocusMode.DEFAULT
                "ignore" -> FocusMode.IGNORE
                else -> null
            }
        }
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
     * Before the first change to a package, its current mode is recorded so
     * [clearFocusOverrides] can put back exactly what was there. The earlier
     * version always wrote `default`, which silently overwrote a deliberate
     * `allow` or `ignore` the user had set elsewhere - i.e. it broke apps in the
     * name of un-breaking them.
     */
    suspend fun setFocusStealingAllowed(pkg: String, allowed: Boolean): Result<Unit> {
        rememberPriorMode(pkg)
        val mode = if (allowed) FocusMode.DEFAULT else FocusMode.IGNORE
        return shell("cmd appops set $pkg TAKE_AUDIO_FOCUS ${mode.name.lowercase()}")
    }

    /**
     * Record [pkg]'s focus mode the first time we change it.
     *
     * Only the first observation is kept: once we have written `ignore`, reading
     * it back would record our own change and make the restore a no-op.
     */
    private fun rememberPriorMode(pkg: String) {
        val store = priorModes()
        if (store.contains(pkg)) return
        val current = runCatching {
            runBlocking {
                withTimeoutOrNull(2_000L) {
                    readSettingRaw("cmd appops get $pkg TAKE_AUDIO_FOCUS")
                }
            }
        }.getOrNull()?.let { parseFocusMode(it) } ?: return
        store.edit().putString(pkg, current.name).apply()
        Log.d(TAG, "recorded prior focus mode for $pkg: ${current.name}")
    }

    private fun priorModes(): android.content.SharedPreferences =
        context.getSharedPreferences(PRIOR_MODES_PREFS, Context.MODE_PRIVATE)

    /** The mode recorded before we first changed [pkg], if any. */
    fun priorModeFor(pkg: String): FocusMode? =
        priorModes().getString(pkg, null)?.let { name ->
            FocusMode.entries.firstOrNull { it.name == name }
        }

    /**
     * Whether [pkg] is currently allowed to steal audio focus.
     *
     * `cmd appops get` has three shapes worth knowing, and the old
     * `!out.contains("ignore")` only got them right by accident:
     *
     *   "No operations."                        -> the op is not set at all
     *   "TAKE_AUDIO_FOCUS: default"             -> explicitly default
     *   "TAKE_AUDIO_FOCUS: ignore"              -> suppressed by us
     *
     * Anything other than an explicit `ignore` counts as allowed, but the modes are
     * matched exactly rather than by substring, because a locale or an Android
     * version formatting this differently would have silently reported the wrong
     * state - and for this op the wrong state is what makes car audio drop out.
     */
    suspend fun isFocusStealingAllowed(pkg: String): Boolean? {
        val out = readSettingRaw("cmd appops get $pkg TAKE_AUDIO_FOCUS") ?: return null
        return parseFocusMode(out)?.let { it != FocusMode.IGNORE }
    }

    /**
     * Apply the recommended profile for car use.
     *
     * Enables multi-audio focus and suppresses focus-stealing for [packages].
     * Deliberately does not touch per-app volume values - those are not
     * writable from shell, and silently failing at that would be worse than
     * not offering it.
     *
     * Pass [DIALOG_COMPETITORS] rather than every media app: suppressing a
     * browser's focus is a change to someone else's app, and the default should
     * be the smallest set that solves the dialog problem.
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

    /** Restore every app in [packages] to the mode it had before we changed it. */
    suspend fun clearFocusOverrides(packages: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        var restored = 0
        packages.forEach { pkg ->
            val recorded = priorModeFor(pkg)
            if (recorded == null) {
                // We never changed this one, so there is nothing of ours to undo.
                // Writing "default" here is what clobbered settings the user had
                // made deliberately in Sound Assistant.
                Log.d(TAG, "no recorded mode for $pkg; leaving untouched")
                return@forEach
            }
            val target = when (recorded) {
                // An unset op is cleared rather than set to `default`, so the app
                // returns to inheriting the platform default.
                FocusMode.UNSET -> "cmd appops reset $pkg TAKE_AUDIO_FOCUS"
                else -> "cmd appops set $pkg TAKE_AUDIO_FOCUS ${recorded.name.lowercase()}"
            }
            shell(target).onSuccess {
                restored++
                priorModes().edit().remove(pkg).apply()
            }
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
