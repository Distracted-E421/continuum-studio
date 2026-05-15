package com.example.continuumstudio.update

import kotlinx.serialization.Serializable

/**
 * Update source priority (checked in order)
 */
enum class UpdateSource(val displayName: String) {
    SYNAPSIX("Synapsix Integration"),
    GITEA("Self-hosted (Gitea)"),
    GITHUB("GitHub Releases")
}

/**
 * Update availability info
 */
@Serializable
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long,
    val sha256: String?,
    val source: String,
    val publishedAt: Long,
    val isPreRelease: Boolean = false
)

/**
 * Update check result
 */
sealed class UpdateCheckResult {
    data class Available(val info: UpdateInfo) : UpdateCheckResult()
    data object UpToDate : UpdateCheckResult()
    data class Error(val message: String, val source: UpdateSource) : UpdateCheckResult()
}

/**
 * Download progress
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(val progress: Int, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Downloaded(val filePath: String, val info: UpdateInfo) : DownloadState()
    data class Error(val message: String) : DownloadState()
    data object Installing : DownloadState()
}

/**
 * Update settings persisted in DataStore
 */
@Serializable
data class UpdateSettings(
    val autoCheckEnabled: Boolean = true,
    val checkOnCellular: Boolean = false,
    val autoDownload: Boolean = false,
    val checkIntervalHours: Int = 6,
    val enabledSources: Set<String> = setOf("SYNAPSIX", "GITEA", "GITHUB"),
    val synapsixEndpoint: String = "",
    val giteaEndpoint: String = "http://100.64.222.20:3000/api/v1/repos/e421/continuum-studio/releases",
    val githubRepo: String = "datapunk/continuum-studio",
    val includePreReleases: Boolean = false
)

/**
 * UI state for update screen
 */
data class UpdateUiState(
    val isChecking: Boolean = false,
    val lastCheckTime: Long? = null,
    val availableUpdate: UpdateInfo? = null,
    val downloadState: DownloadState = DownloadState.Idle,
    val currentVersion: String = "",
    val currentVersionCode: Int = 0,
    val settings: UpdateSettings = UpdateSettings(),
    val checkErrors: List<Pair<UpdateSource, String>> = emptyList()
)
