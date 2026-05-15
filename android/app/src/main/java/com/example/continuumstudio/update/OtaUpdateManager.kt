package com.example.continuumstudio.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

private const val TAG = "OtaUpdateManager"

sealed class OtaState {
    object Idle : OtaState()
    object Checking : OtaState()
    data class Available(val info: UpdateInfo) : OtaState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : OtaState()
    object Installing : OtaState()
    data class Error(val message: String) : OtaState()
    object UpToDate : OtaState()
}

class OtaUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    
    private val _updateState = MutableStateFlow<OtaState>(OtaState.Idle)
    val updateState: StateFlow<OtaState> = _updateState
    
    private val _availableUpdate = MutableStateFlow<UpdateInfo?>(null)
    val availableUpdate: StateFlow<UpdateInfo?> = _availableUpdate
    
    companion object {
        // Gitea on internal network (accessible via Tailscale)
        const val GITEA_API_BASE = "http://100.64.142.88:3000/api/v1"
        const val GITEA_OWNER = "datapunk"
        const val GITEA_REPO = "continuum-studio"
        
        // GitHub as fallback (requires public repo or token)
        const val GITHUB_API_BASE = "https://api.github.com"
        const val GITHUB_OWNER = "Distracted-E421"
        const val GITHUB_REPO = "continuum-studio"
    }
    
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version code", e)
            0
        }
    }
    
    fun getCurrentVersionName(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get version name", e)
            "unknown"
        }
    }
    
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        _updateState.value = OtaState.Checking
        
        try {
            // Try Gitea first (self-hosted, preferred)
            val giteaUpdate = checkGiteaRelease()
            if (giteaUpdate != null && giteaUpdate.versionCode > getCurrentVersionCode()) {
                _updateState.value = OtaState.Available(giteaUpdate)
                _availableUpdate.value = giteaUpdate
                return@withContext giteaUpdate
            }
            
            // Fall back to GitHub
            val githubUpdate = checkGithubRelease()
            if (githubUpdate != null && githubUpdate.versionCode > getCurrentVersionCode()) {
                _updateState.value = OtaState.Available(githubUpdate)
                _availableUpdate.value = githubUpdate
                return@withContext githubUpdate
            }
            
            _updateState.value = OtaState.UpToDate
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for update", e)
            _updateState.value = OtaState.Error("Failed to check for updates: ${e.message}")
            null
        }
    }
    
    private suspend fun checkGiteaRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$GITEA_API_BASE/repos/$GITEA_OWNER/$GITEA_REPO/releases/latest"
            val request = Request.Builder().url(url).get().build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val body = response.body?.string() ?: return@withContext null
                parseGiteaRelease(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gitea check failed", e)
            null
        }
    }
    
    private fun parseGiteaRelease(jsonBody: String): UpdateInfo? {
        return try {
            val release = json.decodeFromString<GiteaRelease>(jsonBody)
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            
            if (apkAsset != null) {
                val versionCode = extractVersionCode(release.tag_name)
                UpdateInfo(
                    versionName = release.tag_name.removePrefix("v"),
                    versionCode = versionCode,
                    downloadUrl = apkAsset.browser_download_url,
                    releaseNotes = release.body,
                    fileSize = apkAsset.size,
                    sha256 = null,
                    source = "GITEA",
                    publishedAt = System.currentTimeMillis(),
                    isPreRelease = false
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Gitea release", e)
            null
        }
    }
    
    private suspend fun checkGithubRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val url = "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github.v3+json")
                .get()
                .build()
            
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val body = response.body?.string() ?: return@withContext null
                parseGithubRelease(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub check failed", e)
            null
        }
    }
    
    private fun parseGithubRelease(jsonBody: String): UpdateInfo? {
        return try {
            val release = json.decodeFromString<GithubRelease>(jsonBody)
            val apkAsset = release.assets.find { it.name.endsWith(".apk") }
            
            if (apkAsset != null) {
                val versionCode = extractVersionCode(release.tag_name)
                UpdateInfo(
                    versionName = release.tag_name.removePrefix("v"),
                    versionCode = versionCode,
                    downloadUrl = apkAsset.browser_download_url,
                    releaseNotes = release.body ?: "",
                    fileSize = apkAsset.size,
                    sha256 = null,
                    source = "GITHUB",
                    publishedAt = System.currentTimeMillis(),
                    isPreRelease = release.prerelease
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GitHub release", e)
            null
        }
    }
    
    private fun extractVersionCode(tagName: String): Int {
        // Parse version code from tag (e.g., "v1.2.3" -> 10203)
        val version = tagName.removePrefix("v").split(".")
        return try {
            val major = version.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = version.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = version.getOrNull(2)?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }
    
    suspend fun downloadAndInstall(updateInfo: UpdateInfo) = withContext(Dispatchers.IO) {
        try {
            _updateState.value = OtaState.Downloading(0f, 0, updateInfo.fileSize)
            
            val apkFile = downloadApk(updateInfo.downloadUrl, updateInfo.fileSize)
            
            _updateState.value = OtaState.Installing
            installApk(apkFile)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download/install update", e)
            _updateState.value = OtaState.Error("Update failed: ${e.message}")
        }
    }
    
    private suspend fun downloadApk(url: String, totalSize: Long): File = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val apkFile = File(cacheDir, "update.apk")
        
        if (apkFile.exists()) apkFile.delete()
        
        val request = Request.Builder().url(url).get().build()
        
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Download failed: ${response.code}")
            }
            
            val body = response.body ?: throw Exception("Empty response")
            val contentLength = body.contentLength().takeIf { it > 0 } ?: totalSize
            
            FileOutputStream(apkFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val progress = if (contentLength > 0) {
                            downloadedBytes.toFloat() / contentLength
                        } else 0f
                        
                        _updateState.value = OtaState.Downloading(
                            progress = progress,
                            downloadedBytes = downloadedBytes,
                            totalBytes = contentLength
                        )
                    }
                }
            }
        }
        
        apkFile
    }
    
    private fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Use FileProvider for Android 7.0+
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                installWithPackageInstaller(uri, apkFile)
            } else {
                // Legacy installation
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            _updateState.value = OtaState.Error("Installation failed: ${e.message}")
        }
    }
    
    private fun installWithPackageInstaller(uri: Uri, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                session.openWrite("update.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            
            val intent = Intent(context, UpdateInstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            
            session.commit(pendingIntent.intentSender)
            
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }
    
    fun resetState() {
        _updateState.value = OtaState.Idle
        _availableUpdate.value = null
    }
}

@Serializable
data class GiteaRelease(
    val tag_name: String,
    val body: String = "",
    val assets: List<GiteaAsset> = emptyList()
)

@Serializable
data class GiteaAsset(
    val name: String,
    val size: Long = 0,
    val browser_download_url: String
)

@Serializable
data class GithubRelease(
    val tag_name: String,
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList()
)

@Serializable
data class GithubAsset(
    val name: String,
    val size: Long = 0,
    val browser_download_url: String
)
