package com.example.continuumstudio.audio

import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Thin wrapper over Shizuku's process API.
 *
 * Shizuku runs a persistent ADB-shell server as UID 2000. Spawning a command
 * through it gives us exactly the privileges verified over `adb shell` during
 * device research (writing Settings.System keys, `cmd appops`), with no root and
 * no per-command ADB connection.
 *
 * Kept deliberately small: [exec] runs one command and captures stdout. Anything
 * smarter belongs in the caller.
 */
object ShizukuBridge {

    private const val TAG = "ShizukuBridge"

    /** True when the Shizuku service is alive AND we hold shell permission. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        // Thrown when Shizuku is not installed or the binder has died.
        false
    }

    /** Launch the Shizuku permission prompt. No-op if already alive or not installed. */
    fun requestPermission(requestCode: Int) {
        try {
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "requestPermission failed: ${e.message}")
        }
    }

    /**
     * Run a shell command as the Shizuku user and return its stdout.
     *
     * [Shizuku.newProcess] executes commands in the shell process, so the string
     * is split naively: commands used here are simple and contain no quoting
     * that would matter (`settings put system key 1`, `cmd appops set pkg op mode`).
     */
    fun exec(command: String): Result<String> = try {
        val remote = newRemoteProcess(command)
        val stdout = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
            .use { input -> BufferedReader(InputStreamReader(input)).readText() }
        val exit = remote.waitFor()
        remote.destroy()
        if (exit == 0) Result.success(stdout)
        else Result.failure(IllegalStateException("exit=$exit: $command"))
    } catch (e: Throwable) {
        Log.w(TAG, "exec failed: ${e.message}")
        Result.failure(e)
    }

    /**
     * Spawn a shell command through Shizuku's service.
     *
     * `Shizuku.newProcess` is private in api 13.1.5 and `ShizukuRemoteProcess`
     * has a package-private constructor, so go through the AIDL interface
     * directly: `ShizukuBinderWrapper` forwards the call over the binder to the
     * Shizuku server process (UID 2000), and `IRemoteProcess` hands back
     * ParcelFileDescriptors for the streams.
     */
    private fun newRemoteProcess(command: String): IRemoteProcess {
        val binder = Shizuku.getBinder()
            ?: throw IllegalStateException("Shizuku binder is not available")
        val service = IShizukuService.Stub.asInterface(ShizukuBinderWrapper(binder))
        return service.newProcess(arrayOf("sh", "-c", command), null, null)
    }
}
