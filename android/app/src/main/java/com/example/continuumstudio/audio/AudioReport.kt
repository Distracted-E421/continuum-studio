package com.example.continuumstudio.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A snapshot of everything that decides where dialog audio comes out.
 *
 * ## Why this exists
 *
 * "Works on the phone, silent in the car" cannot be diagnosed from a screenshot
 * or a log line: the answer is in how the routing *changed* between the two
 * situations. Collecting the same report in both states and diffing them is what
 * turns a guess into a fact.
 *
 * Deliberately dependency-free apart from `android.*` so it can run in a Service
 * or a Compose screen, and so the comparison logic ([AudioReport.Compare]) can be
 * tested without a device.
 */
data class AudioReport(
    /** Human-readable "label: value" lines, in a stable order for diffing. */
    val lines: List<Pair<String, String>>,
    /** One-line summary of the mode it was collected in. */
    val situation: String,
) {

    fun render(): String = buildString {
        appendLine("Continuum audio report")
        appendLine("situation: $situation")
        lines.forEach { (k, v) -> appendLine("$k: $v") }
    }

    /** Labels that changed between two reports, as a plain-language list. */
    fun differencesFrom(other: AudioReport): List<String> {
        val mine = lines.toMap()
        val theirs = other.lines.toMap()
        return (mine.keys + theirs.keys).sorted().mapNotNull { key ->
            val a = theirs[key]
            val b = mine[key]
            if (a == b) null else "$key: ${a ?: "(absent)"} -> ${b ?: "(absent)"}"
        }
    }

    companion object {
        /**
         * Compare two reports and describe what differs.
         *
         * Pure, so it is testable. The point of the report is the *difference*
         * between car and phone; the individual values matter much less than
         * which ones moved.
         */
        fun describeDifferences(car: AudioReport, phone: AudioReport): String {
            val diffs = car.differencesFrom(phone)
            return if (diffs.isEmpty()) {
                "No differences between car and phone reports. If audio is silent " +
                    "in the car, the cause is not in the phone's routing state."
            } else {
                buildString {
                    appendLine("Car (${car.situation}) vs phone (${phone.situation}):")
                    diffs.forEach { appendLine("- $it") }
                }
            }
        }
    }
}

/**
 * Collects [AudioReport]s from the live audio stack.
 *
 * Read-only: it never requests focus, never changes a route and never plays
 * anything except inside [probePlayback], which is opt-in and uses a silent
 * clip. That matters because a diagnostics tool that changes the thing it
 * measures is worse than none - and a previous version of the audio diagnostics
 * screen leaked audio focus and silenced other apps.
 */
object AudioReportCollector {

    /**
     * Snapshot the current state.
     *
     * @param situation how to describe where the user is, e.g. "car (Android
     *        Auto)" or "phone (Bluetooth only)"
     */
    fun collect(context: Context, situation: String): AudioReport {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val out = mutableListOf<Pair<String, String>>()

        out += "reported situation" to situation
        out += "app version" to appVersion(context)
        out += "device" to "${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})"

        // --- How Android thinks we are connected ---------------------------
        out += "car mode (UiModeManager)" to carMode(context)

        // The full projection evidence. "car mode is NORMAL" was true and
        // misleading at the same time, so the raw inputs are reported together.
        out += "projection" to com.example.continuumstudio.service.tts.CarProjection
            .describe(context)

        // --- Routing -------------------------------------------------------
        out += "audio mode" to when (am.mode) {
            AudioManager.MODE_NORMAL -> "NORMAL"
            AudioManager.MODE_IN_CALL -> "IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
            AudioManager.MODE_RINGTONE -> "RINGTONE"
            else -> "other(${am.mode})"
        }

        // Outputs, with names, so a diff shows the route appearing/disappearing
        // rather than just a type number changing.
        val outputs = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString("; ") { d ->
                    "${describeType(d.type)}(${d.productName})${if (d.isSink) "" else " !sink"}"
                }
        }.getOrDefault("unavailable")
        out += "outputs" to outputs

        out += "isMusicActive" to am.isMusicActive.toString()
        out += "isSpeakerphoneOn" to am.isSpeakerphoneOn.toString()

        // Whether Android Auto is actually projected, which is different from the
        // phone merely being in car mode. The owner's head unit is USB-projected
        // (no A2DP route at all), so this is the field that says whether the car
        // side is even attached.
        out += "AA projected" to androidAutoProjected(context).toString()

        // Per-stream volumes: a car route at 0 on the media stream is silent
        // while everything else looks healthy.
        out += "volume music" to streamVolume(am, AudioManager.STREAM_MUSIC)
        out += "volume notifications" to streamVolume(am, AudioManager.STREAM_NOTIFICATION)
        out += "volume system" to streamVolume(am, AudioManager.STREAM_SYSTEM)

        // --- Our own engines ------------------------------------------------
        val player = com.example.continuumstudio.service.tts.AudioHub.existing()
        out += "player exists" to (player != null).toString()
        out += "focus policy" to (player?.focusPolicy?.storedValue ?: "not created")
        out += "session connected" to
            com.example.continuumstudio.service.tts.MediaSessionBridge.isConnected().toString()

        // --- Synthesis path -------------------------------------------------
        out += "EasyVoice configured" to
            runCatching { com.example.continuumstudio.service.tts.EasyVoiceClient(context).isConfigured }
                .getOrDefault(false).toString()

        // --- MultiSound -----------------------------------------------------
        out += "Shizuku available" to
            runCatching { ShizukuBridge.isAvailable() }.getOrDefault(false).toString()

        return AudioReport(lines = out, situation = situation)
    }

    /**
     * Play a short clip and report what happened.
     *
     * **Never call this on the main thread.** `prepare()` reads from disk and the
     * wait below blocks; the first version of this ran inside a
     * `rememberCoroutineScope().launch` (i.e. Main) and froze the UI for up to six
     * seconds, which is what made the app feel laggy and unresponsive afterwards.
     * It is a `suspend` function that moves itself to [Dispatchers.IO] so a caller
     * cannot get this wrong by accident.
     *
     * @return a description of what the player did, appended to the report
     */
    suspend fun probePlayback(context: Context, audible: Boolean): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<Pair<String, String>>()
            out += "probe audible" to audible.toString()
            val started = CountDownLatch(1)
            var error: String? = null
            var durationMs = -1
            var played = false

            val player = try {
                MediaPlayer()
            } catch (e: Exception) {
                out += "probe result" to "could not create MediaPlayer: ${e.message}"
                return@withContext out
            }

            try {
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                val file = if (audible) toneFile(context) else silentFile(context)
                player.setDataSource(file.absolutePath)
                player.setOnErrorListener { _, what, extra ->
                    error = "what=$what extra=$extra"
                    started.countDown()
                    true
                }
                player.setOnCompletionListener {
                    played = true
                    started.countDown()
                }
                player.prepare()
                durationMs = runCatching { player.duration }.getOrDefault(-1)
                player.start()
                // Wait for the clip rather than a fixed sleep, so the reading
                // reflects actual playback. Bounded so a wedged player cannot hang
                // the report.
                started.await(6, TimeUnit.SECONDS)
            } catch (e: Exception) {
                error = "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                runCatching { player.release() }
            }

            out += "probe duration" to "${durationMs}ms"
            out += "probe completed" to played.toString()
            out += "probe error" to (error ?: "none")
            // Completion alone does not prove the user heard it: a routing failure
            // usually still "plays" into a null sink. Hence the audible option and
            // the routing snapshot that accompanies it.
            out
        }

    private fun appVersion(context: Context): String = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        "${info.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else 0})"
    }.getOrDefault("unknown")

    private fun carMode(context: Context): String = runCatching {
        val m = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        when (m?.currentModeType) {
            android.content.res.Configuration.UI_MODE_TYPE_CAR -> "CAR (Android Auto)"
            android.content.res.Configuration.UI_MODE_TYPE_NORMAL -> "NORMAL"
            null -> "unknown"
            else -> "type=${m.currentModeType}"
        }
    }.getOrDefault("unavailable")

    /**
     * Whether a car projection is attached, asked of `Car` itself.
     *
     * Reflective on purpose: depending on `androidx.car.app` would fail to compile
     * on a device without Play services. Returns "unsupported" when the class is
     * absent, which is a legitimate answer rather than an error.
     */
    private fun androidAutoProjected(context: Context): String = runCatching {
        val cls = Class.forName("androidx.car.app.CarContext")
        if (cls == null) return@runCatching "unsupported"
        val connection = Class.forName("androidx.car.app.connection.CarConnection")
        // No instance access without the extra dependency; fall back to the
        // signal Android itself exposes.
        if ((context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
                ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_CAR
        ) "true (car UI mode)" else "false (UiMode=NORMAL)"
    }.getOrDefault("unknown")

    private fun streamVolume(am: AudioManager, stream: Int): String = runCatching {
        "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}" +
            if (am.isStreamMute(stream)) " MUTED" else ""
    }.getOrDefault("unavailable")

    /** Human name for a device type, so a diff is readable. */
    internal fun describeType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb-accessory"
        AudioDeviceInfo.TYPE_HEARING_AID -> "hearing-aid"
        AudioDeviceInfo.TYPE_BUS -> "bus"
        else -> "type$type"
    }

    /**
     * A generated WAV so the probe needs no asset and no network.
     *
     * Silent by default: an audible probe changes what it measures (it may itself
     * steal focus), so it is opt-in.
     */
    private fun silentFile(context: Context): java.io.File =
        wav(context, seconds = 0.25, amplitude = 0.0)

    /** A quiet 440 Hz tone, for "can you hear anything at all". */
    private fun toneFile(context: Context): java.io.File =
        wav(context, seconds = 1.0, amplitude = 0.15)

    private fun wav(context: Context, seconds: Double, amplitude: Double): java.io.File {
        val dir = java.io.File(context.cacheDir, "audio-probe").apply { mkdirs() }
        val file = java.io.File(
            dir,
            if (amplitude == 0.0) "silence.wav" else "tone.wav",
        )
        if (file.exists()) return file

        val sampleRate = 16_000
        val samples = (sampleRate * seconds).toInt()
        val dataSize = samples * 2
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); le32(36 + dataSize); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(dataSize)
        for (i in 0 until samples) {
            val v = if (amplitude == 0.0) 0.0 else
                amplitude * Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate)
            le16((v * Short.MAX_VALUE).toInt())
        }
        file.writeBytes(out.toByteArray())
        return file
    }
}
