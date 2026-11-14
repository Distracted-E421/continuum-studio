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

    /** Set by the UI so a granted permission can trigger a refresh. */
    @Volatile
    var onPermissionResult: ((granted: Boolean) -> Unit)? = null

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "permission result granted=$granted")
        onPermissionResult?.invoke(granted)
    }

    private var listenerRegistered = false

    /**
     * Register the permission-result listener once.
     *
     * Shizuku delivers the grant asynchronously through this listener; without
     * it a request appears to do nothing at all, which is exactly the failure
     * this app had.
     */
    fun ensureListener() {
        if (listenerRegistered) return
        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
            listenerRegistered = true
        } catch (e: Throwable) {
            Log.d(TAG, "could not register listener: ${e.message}")
        }
    }

    /** True when the Shizuku service is alive AND we hold shell permission. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        // Thrown when Shizuku is not installed or the binder has died.
        false
    }

    /**
     * Launch the Shizuku permission prompt.
     *
     * Requires the result listener to already be registered, otherwise the grant
     * is never observed. [ensureListener] is idempotent.
     */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.d(TAG, "Shizuku binder not alive; cannot request permission")
                return
            }
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                onPermissionResult?.invoke(true)
                return
            }
            ensureListener()
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Throwable) {
            Log.d(TAG, "requestPermission failed: ${e.message}")
        }
    }

    const val PERMISSION_REQUEST_CODE = 4242

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
