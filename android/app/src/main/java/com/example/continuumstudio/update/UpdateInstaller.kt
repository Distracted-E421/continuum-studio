package com.example.continuumstudio.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.FileInputStream

private const val TAG = "UpdateInstaller"
private const val ACTION_INSTALL_STATUS = "com.example.continuumstudio.INSTALL_STATUS"

/**
 * Installation result
 */
sealed class InstallResult {
    data object Success : InstallResult()
    data class Failure(val message: String) : InstallResult()
    data object UserCancelled : InstallResult()
    data object Pending : InstallResult()
}

/**
 * Handles APK installation using PackageInstaller
 */
class UpdateInstaller(
    private val context: Context
) {
    private val installResultChannel = Channel<InstallResult>(Channel.CONFLATED)
    val installResult: Flow<InstallResult> = installResultChannel.receiveAsFlow()
    
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    Log.d(TAG, "User action required")
                    val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    confirmIntent?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    }
                    installResultChannel.trySend(InstallResult.Pending)
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    Log.i(TAG, "Installation successful")
                    installResultChannel.trySend(InstallResult.Success)
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    Log.w(TAG, "Installation cancelled by user")
                    installResultChannel.trySend(InstallResult.UserCancelled)
                }
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Log.e(TAG, "Installation failed: $status - $message")
                    installResultChannel.trySend(InstallResult.Failure(message ?: "Unknown error (status: $status)"))
                }
            }
        }
    }
    
    init {
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(installReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(installReceiver, filter)
        }
    }
    
    /**
     * Install APK using PackageInstaller (Android 5.0+)
     */
    fun installApk(apkPath: String) {
        Log.i(TAG, "Starting installation: $apkPath")
        
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file not found: $apkPath")
            installResultChannel.trySend(InstallResult.Failure("APK file not found"))
            return
        }
        
        try {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            
            // Set installer package name (for update without prompts if same installer)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            
            // Write APK to session
            FileInputStream(apkFile).use { input ->
                session.openWrite("continuum-studio.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            
            // Create intent for result broadcast
            val intent = Intent(ACTION_INSTALL_STATUS).apply {
                `package` = context.packageName
            }
            
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            // Commit the session
            session.commit(pendingIntent.intentSender)
            session.close()
            
            Log.d(TAG, "Install session committed: $sessionId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start installation", e)
            installResultChannel.trySend(InstallResult.Failure("Installation failed: ${e.message}"))
        }
    }
    
    /**
     * Install APK using Intent (fallback for older behavior)
     */
    fun installApkViaIntent(apkPath: String) {
        Log.i(TAG, "Installing via intent: $apkPath")
        
        val apkFile = File(apkPath)
        if (!apkFile.exists()) {
            installResultChannel.trySend(InstallResult.Failure("APK file not found"))
            return
        }
        
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(intent)
            installResultChannel.trySend(InstallResult.Pending)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open install intent", e)
            installResultChannel.trySend(InstallResult.Failure("Could not open installer: ${e.message}"))
        }
    }
    
    /**
     * Check if app has install permission
     */
    fun canRequestInstall(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }
    
    /**
     * Open settings for install permissions
     */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
    
    /**
     * Cleanup when done
     */
    fun cleanup() {
        try {
            context.unregisterReceiver(installReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered")
        }
    }
}
