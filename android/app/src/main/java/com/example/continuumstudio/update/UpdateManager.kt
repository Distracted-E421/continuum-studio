package com.example.continuumstudio.update

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateManager"

/**
 * Manages update checking with cascading source support.
 * 
 * Priority: Synapsix → Gitea → GitHub
 * Each source is tried in order until one succeeds with an update.
 */
class UpdateManager(
    private val context: Context
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
    }
    
    /**
     * Check for updates using cascading sources
     */
    suspend fun checkForUpdates(
        settings: UpdateSettings,
        forceCheck: Boolean = false
    ): Pair<UpdateCheckResult, List<Pair<UpdateSource, String>>> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<Pair<UpdateSource, String>>()
        val currentVersionCode = getCurrentVersionCode()
        
        // Check network conditions
        if (!forceCheck && !settings.checkOnCellular && isOnCellular()) {
            return@withContext UpdateCheckResult.Error(
                "Skipping update check on cellular (enable in settings)",
                UpdateSource.SYNAPSIX
            ) to errors
        }
        
        // Try each enabled source in priority order
        val enabledSources = settings.enabledSources.mapNotNull { 
            try { UpdateSource.valueOf(it) } catch (e: Exception) { null }
        }.sortedBy { it.ordinal }
        
        for (source in enabledSources) {
            try {
                Log.d(TAG, "Checking for updates from $source")
                
                val result = when (source) {
                    UpdateSource.SYNAPSIX -> checkSynapsix(settings, currentVersionCode)
                    UpdateSource.GITEA -> checkGitea(settings, currentVersionCode)
                    UpdateSource.GITHUB -> checkGitHub(settings, currentVersionCode)
                }
                
                when (result) {
                    is UpdateCheckResult.Available -> {
                        Log.i(TAG, "Update found from $source: ${result.info.versionName}")
                        return@withContext result to errors
                    }
                    is UpdateCheckResult.UpToDate -> {
                        Log.d(TAG, "$source reports up to date")
                        // Continue to next source to check for newer versions
                    }
                    is UpdateCheckResult.Error -> {
                        Log.w(TAG, "$source error: ${result.message}")
                        errors.add(source to result.message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception checking $source", e)
                errors.add(source to (e.message ?: "Unknown error"))
            }
        }
        
        UpdateCheckResult.UpToDate to errors
    }
    
    /**
     * Check Synapsix endpoint for updates
     */
    private suspend fun checkSynapsix(
        settings: UpdateSettings,
        currentVersionCode: Int
    ): UpdateCheckResult {
        if (settings.synapsixEndpoint.isBlank()) {
            return UpdateCheckResult.Error("Synapsix endpoint not configured", UpdateSource.SYNAPSIX)
        }
        
        val request = Request.Builder()
            .url("${settings.synapsixEndpoint}/api/update/check?app=continuum-studio&version=$currentVersionCode")
            .header("Accept", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return UpdateCheckResult.Error("HTTP ${response.code}", UpdateSource.SYNAPSIX)
        }
        
        val body = response.body?.string() ?: return UpdateCheckResult.Error("Empty response", UpdateSource.SYNAPSIX)
        val jsonObj = json.parseToJsonElement(body).jsonObject
        
        val hasUpdate = jsonObj["has_update"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!hasUpdate) {
            return UpdateCheckResult.UpToDate
        }
        
        val updateObj = jsonObj["update"]?.jsonObject ?: return UpdateCheckResult.Error("Missing update object", UpdateSource.SYNAPSIX)
        
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = updateObj["version_name"]?.jsonPrimitive?.content ?: "",
                versionCode = updateObj["version_code"]?.jsonPrimitive?.intOrNull ?: 0,
                releaseNotes = updateObj["release_notes"]?.jsonPrimitive?.content ?: "",
                downloadUrl = updateObj["download_url"]?.jsonPrimitive?.content ?: "",
                fileSize = updateObj["file_size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = updateObj["sha256"]?.jsonPrimitive?.contentOrNull,
                source = "synapsix",
                publishedAt = updateObj["published_at"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
                isPreRelease = updateObj["is_prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        )
    }
    
    /**
     * Check Gitea releases API
     */
    private suspend fun checkGitea(
        settings: UpdateSettings,
        currentVersionCode: Int
    ): UpdateCheckResult {
        if (settings.giteaEndpoint.isBlank()) {
            return UpdateCheckResult.Error("Gitea endpoint not configured", UpdateSource.GITEA)
        }
        
        val request = Request.Builder()
            .url(settings.giteaEndpoint)
            .header("Accept", "application/json")
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return UpdateCheckResult.Error("HTTP ${response.code}", UpdateSource.GITEA)
        }
        
        val body = response.body?.string() ?: return UpdateCheckResult.Error("Empty response", UpdateSource.GITEA)
        val releases = json.parseToJsonElement(body).jsonArray
        
        if (releases.isEmpty()) {
            return UpdateCheckResult.UpToDate
        }
        
        // Find latest (or latest non-prerelease)
        val latestRelease = releases
            .map { it.jsonObject }
            .filter { release ->
                val isPrerelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                settings.includePreReleases || !isPrerelease
            }
            .firstOrNull() ?: return UpdateCheckResult.UpToDate
        
        // Parse version code from tag (e.g., "v1.2.3" -> 10203)
        val tagName = latestRelease["tag_name"]?.jsonPrimitive?.content ?: ""
        val remoteVersionCode = parseVersionCode(tagName)
        
        if (remoteVersionCode <= currentVersionCode) {
            return UpdateCheckResult.UpToDate
        }
        
        // Find APK asset
        val assets = latestRelease["assets"]?.jsonArray ?: return UpdateCheckResult.Error("No assets", UpdateSource.GITEA)
        val apkAsset = assets
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            ?: return UpdateCheckResult.Error("No APK in release", UpdateSource.GITEA)
        
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = tagName.removePrefix("v"),
                versionCode = remoteVersionCode,
                releaseNotes = latestRelease["body"]?.jsonPrimitive?.content ?: "",
                downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: "",
                fileSize = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = null,
                source = "gitea",
                publishedAt = parseIsoDate(latestRelease["published_at"]?.jsonPrimitive?.content),
                isPreRelease = latestRelease["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        )
    }
    
    /**
     * Check GitHub releases API
     */
    private suspend fun checkGitHub(
        settings: UpdateSettings,
        currentVersionCode: Int
    ): UpdateCheckResult {
        if (settings.githubRepo.isBlank()) {
            return UpdateCheckResult.Error("GitHub repo not configured", UpdateSource.GITHUB)
        }
        
        val url = "https://api.github.com/repos/${settings.githubRepo}/releases"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "ContinuumStudio-Android")
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            return UpdateCheckResult.Error("HTTP ${response.code}", UpdateSource.GITHUB)
        }
        
        val body = response.body?.string() ?: return UpdateCheckResult.Error("Empty response", UpdateSource.GITHUB)
        val releases = json.parseToJsonElement(body).jsonArray
        
        if (releases.isEmpty()) {
            return UpdateCheckResult.UpToDate
        }
        
        // Find latest applicable release
        val latestRelease = releases
            .map { it.jsonObject }
            .filter { release ->
                val isPrerelease = release["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
                val isDraft = release["draft"]?.jsonPrimitive?.booleanOrNull ?: false
                !isDraft && (settings.includePreReleases || !isPrerelease)
            }
            .firstOrNull() ?: return UpdateCheckResult.UpToDate
        
        val tagName = latestRelease["tag_name"]?.jsonPrimitive?.content ?: ""
        val remoteVersionCode = parseVersionCode(tagName)
        
        if (remoteVersionCode <= currentVersionCode) {
            return UpdateCheckResult.UpToDate
        }
        
        // Find APK asset
        val assets = latestRelease["assets"]?.jsonArray ?: return UpdateCheckResult.Error("No assets", UpdateSource.GITHUB)
        val apkAsset = assets
            .map { it.jsonObject }
            .firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
            ?: return UpdateCheckResult.Error("No APK in release", UpdateSource.GITHUB)
        
        return UpdateCheckResult.Available(
            UpdateInfo(
                versionName = latestRelease["name"]?.jsonPrimitive?.content ?: tagName.removePrefix("v"),
                versionCode = remoteVersionCode,
                releaseNotes = latestRelease["body"]?.jsonPrimitive?.content ?: "",
                downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: "",
                fileSize = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0,
                sha256 = null,
                source = "github",
                publishedAt = parseIsoDate(latestRelease["published_at"]?.jsonPrimitive?.content),
                isPreRelease = latestRelease["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        )
    }
    
    /**
     * Get current app version code
     */
    fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }
    
    /**
     * Get current app version name
     */
    fun getCurrentVersionName(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "Unknown"
        }
    }
    
    /**
     * Check if on cellular network
     */
    private fun isOnCellular(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    
    /**
     * Parse semantic version to version code (e.g., "v1.2.3" -> 10203)
     */
    private fun parseVersionCode(tag: String): Int {
        val version = tag.removePrefix("v").split(".")
        return try {
            val major = version.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = version.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = version.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
            major * 10000 + minor * 100 + patch
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Parse ISO 8601 date to epoch millis
     */
    private fun parseIsoDate(date: String?): Long {
        if (date == null) return System.currentTimeMillis()
        return try {
            java.time.Instant.parse(date).toEpochMilli()
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
