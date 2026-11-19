package com.example.continuumstudio.update

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateDownloader"

/**
 * Downloads APK updates with progress reporting
 */
class UpdateDownloader(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    
    /**
     * Download APK with progress updates
     */
    fun downloadUpdate(info: UpdateInfo): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0, 0, info.fileSize))
        
        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "ContinuumStudio-Android")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(DownloadState.Error("Download failed: HTTP ${response.code}"))
                return@flow
            }
            
            val body = response.body ?: run {
                emit(DownloadState.Error("Empty response body"))
                return@flow
            }
            
            val totalBytes = body.contentLength().coerceAtLeast(info.fileSize)
            
            // Create updates directory
            val updateDir = File(context.getExternalFilesDir(null), "updates")
            updateDir.mkdirs()
            
            // Create temp file
            val fileName = "continuum-studio-${info.versionName}.apk"
            val apkFile = File(updateDir, fileName)
            val tempFile = File(updateDir, "$fileName.tmp")
            
            // Download with progress.
            //
            // The digest is always computed and the hash is always REQUIRED. Previously
            // both were conditional on `info.sha256 != null`, and every call site passed
            // null - so an APK with `REQUEST_INSTALL_PACKAGES` was downloaded and
            // installed with no integrity check at all. An optional security check that
            // is never armed is worse than none, because it reads as protection.
            val expected = info.sha256?.trim()?.lowercase()
            if (expected.isNullOrEmpty()) {
                emit(
                    DownloadState.Error(
                        "Refusing to install an update with no published checksum. " +
                            "Re-publish the release with a digest, or update via adb."
                    )
                )
                return@flow
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var downloadedBytes = 0L
            var lastEmittedProgress = 0
            
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest?.update(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            -1
                        }
                        
                        // Emit progress updates (throttled to avoid spam)
                        if (progress != lastEmittedProgress && progress >= 0) {
                            emit(DownloadState.Downloading(progress, downloadedBytes, totalBytes))
                            lastEmittedProgress = progress
                        }
                    }
                }
            }
            
            // Verify, unconditionally. `expected` was checked non-empty above, so there
            // is no path to install without a matching checksum.
            run {
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(expected, ignoreCase = true)) {
                    tempFile.delete()
                    emit(
                        DownloadState.Error(
                            "Checksum mismatch - the download is corrupt or was " +
                                "substituted. Expected $expected, got $actualHash. " +
                                "Nothing was installed."
                        )
                    )
                    return@flow
                }
                Log.d(TAG, "SHA256 verified: $actualHash")
            }
            
            // Rename temp to final
            tempFile.renameTo(apkFile)
            
            Log.i(TAG, "Download complete: ${apkFile.absolutePath}")
            emit(DownloadState.Downloaded(apkFile.absolutePath, info))
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            emit(DownloadState.Error("Download failed: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * Delete downloaded APK files
     */
    suspend fun cleanupDownloads() = withContext(Dispatchers.IO) {
        val updateDir = File(context.getExternalFilesDir(null), "updates")
        if (updateDir.exists()) {
            updateDir.listFiles()?.forEach { file ->
                if (file.extension == "apk" || file.extension == "tmp") {
                    file.delete()
                }
            }
        }
    }
}
