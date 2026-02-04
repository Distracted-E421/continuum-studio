//! Auto-update system for Continuum Studio
//!
//! Supports multiple update channels:
//! - stable: Production releases
//! - beta: Pre-release testing
//! - nightly: Latest development builds
//!
//! Update mechanism:
//! - Checks GitHub releases for new versions
//! - Notifies user of available updates
//! - Can trigger rebuild via nix flake update

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// Update channel selection
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum UpdateChannel {
    /// Stable releases only
    #[default]
    Stable,
    /// Beta/pre-release versions
    Beta,
    /// Nightly/development builds
    Nightly,
}

impl UpdateChannel {
    pub fn as_str(&self) -> &'static str {
        match self {
            UpdateChannel::Stable => "stable",
            UpdateChannel::Beta => "beta",
            UpdateChannel::Nightly => "nightly",
        }
    }

    pub fn description(&self) -> &'static str {
        match self {
            UpdateChannel::Stable => "Production releases - most tested and reliable",
            UpdateChannel::Beta => "Pre-release builds - new features, some testing",
            UpdateChannel::Nightly => "Development builds - latest features, may be unstable",
        }
    }
}

/// Update settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateSettings {
    /// Selected update channel
    #[serde(default)]
    pub channel: UpdateChannel,

    /// Automatically check for updates on startup
    #[serde(default = "default_true")]
    pub auto_check: bool,

    /// Automatically download updates (but don't install)
    #[serde(default)]
    pub auto_download: bool,

    /// Check interval in hours
    #[serde(default = "default_check_interval")]
    pub check_interval_hours: u32,

    /// Last check timestamp (Unix epoch seconds)
    #[serde(default)]
    pub last_check: u64,

    /// Currently installed version
    #[serde(default)]
    pub current_version: String,

    /// Available update version (if any)
    #[serde(default)]
    pub available_version: Option<String>,

    /// Update notes for available version
    #[serde(default)]
    pub update_notes: Option<String>,
}

fn default_true() -> bool {
    true
}

fn default_check_interval() -> u32 {
    24 // 24 hours
}

impl Default for UpdateSettings {
    fn default() -> Self {
        Self {
            channel: UpdateChannel::Stable,
            auto_check: true,
            auto_download: false,
            check_interval_hours: 24,
            last_check: 0,
            current_version: env!("CARGO_PKG_VERSION").to_string(),
            available_version: None,
            update_notes: None,
        }
    }
}

impl UpdateSettings {
    /// Check if it's time to check for updates
    pub fn should_check(&self) -> bool {
        if !self.auto_check {
            return false;
        }

        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_secs();

        let interval_secs = (self.check_interval_hours as u64) * 3600;
        now - self.last_check >= interval_secs
    }

    /// Update the last check timestamp
    pub fn mark_checked(&mut self) {
        self.last_check = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or(Duration::ZERO)
            .as_secs();
    }
}

/// Update information from remote
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateInfo {
    /// Version string
    pub version: String,
    /// Release channel
    pub channel: UpdateChannel,
    /// Release notes
    pub notes: String,
    /// Download URL (for reference, Nix handles actual download)
    pub download_url: Option<String>,
    /// Release date
    pub release_date: String,
    /// Whether this is a prerelease
    pub prerelease: bool,
}

/// GitHub release response (subset of fields we need)
#[derive(Debug, Clone, Deserialize)]
struct GitHubRelease {
    tag_name: String,
    name: String,
    body: Option<String>,
    prerelease: bool,
    published_at: String,
    html_url: String,
}

/// Update checker service
pub struct UpdateChecker {
    /// GitHub repository (owner/repo format)
    repo: String,
    /// User agent for API requests
    user_agent: String,
}

impl UpdateChecker {
    /// Create a new update checker for the continuum-studio repo
    pub fn new() -> Self {
        Self {
            repo: "Distracted-E421/continuum-studio".to_string(),
            user_agent: format!(
                "continuum-studio/{} (update-checker)",
                env!("CARGO_PKG_VERSION")
            ),
        }
    }

    /// Check for updates asynchronously
    pub async fn check_for_updates(
        &self,
        settings: &UpdateSettings,
    ) -> Result<Option<UpdateInfo>, String> {
        let url = format!(
            "https://api.github.com/repos/{}/releases",
            self.repo
        );

        log::info!(
            "Checking for updates on {} channel",
            settings.channel.as_str()
        );

        // Make HTTP request
        let response = self.fetch_releases(&url).await?;

        // Find the best release for our channel
        let update = self.find_best_release(&response, settings)?;

        if let Some(ref info) = update {
            if self.is_newer_version(&info.version, &settings.current_version) {
                log::info!(
                    "Update available: {} -> {}",
                    settings.current_version,
                    info.version
                );
                return Ok(Some(info.clone()));
            }
        }

        log::info!("No updates available (current: {})", settings.current_version);
        Ok(None)
    }

    /// Fetch releases from GitHub API
    async fn fetch_releases(&self, url: &str) -> Result<Vec<GitHubRelease>, String> {
        // Use tokio's spawn_blocking for the blocking HTTP request
        let url = url.to_string();
        let user_agent = self.user_agent.clone();

        tokio::task::spawn_blocking(move || {
            let client = ureq::AgentBuilder::new()
                .timeout(Duration::from_secs(30))
                .build();

            let response = client
                .get(&url)
                .set("User-Agent", &user_agent)
                .set("Accept", "application/vnd.github.v3+json")
                .call()
                .map_err(|e| format!("HTTP request failed: {}", e))?;

            let releases: Vec<GitHubRelease> = response
                .into_json()
                .map_err(|e| format!("Failed to parse releases: {}", e))?;

            Ok(releases)
        })
        .await
        .map_err(|e| format!("Task failed: {}", e))?
    }

    /// Find the best release matching our channel
    fn find_best_release(
        &self,
        releases: &[GitHubRelease],
        settings: &UpdateSettings,
    ) -> Result<Option<UpdateInfo>, String> {
        for release in releases {
            let is_match = match settings.channel {
                UpdateChannel::Stable => !release.prerelease && !release.tag_name.contains("-"),
                UpdateChannel::Beta => !release.tag_name.contains("nightly"),
                UpdateChannel::Nightly => true, // Accept all
            };

            if is_match {
                let version = release
                    .tag_name
                    .trim_start_matches('v')
                    .to_string();

                return Ok(Some(UpdateInfo {
                    version,
                    channel: if release.prerelease {
                        if release.tag_name.contains("nightly") {
                            UpdateChannel::Nightly
                        } else {
                            UpdateChannel::Beta
                        }
                    } else {
                        UpdateChannel::Stable
                    },
                    notes: release.body.clone().unwrap_or_default(),
                    download_url: Some(release.html_url.clone()),
                    release_date: release.published_at.clone(),
                    prerelease: release.prerelease,
                }));
            }
        }

        Ok(None)
    }

    /// Compare version strings (simple semver comparison)
    fn is_newer_version(&self, new: &str, current: &str) -> bool {
        // Parse version strings into components
        let parse_version = |s: &str| -> Vec<u32> {
            s.split(|c: char| c == '.' || c == '-')
                .filter_map(|part| part.parse().ok())
                .collect()
        };

        let new_parts = parse_version(new);
        let current_parts = parse_version(current);

        // Compare component by component
        for i in 0..new_parts.len().max(current_parts.len()) {
            let new_part = new_parts.get(i).copied().unwrap_or(0);
            let current_part = current_parts.get(i).copied().unwrap_or(0);

            if new_part > current_part {
                return true;
            } else if new_part < current_part {
                return false;
            }
        }

        false
    }
}

impl Default for UpdateChecker {
    fn default() -> Self {
        Self::new()
    }
}

/// Commands for updating via Nix
pub struct NixUpdater {
    /// Path to the continuum-studio flake
    flake_path: PathBuf,
}

impl NixUpdater {
    pub fn new(flake_path: impl Into<PathBuf>) -> Self {
        Self {
            flake_path: flake_path.into(),
        }
    }

    /// Default flake path
    pub fn default_flake_path() -> PathBuf {
        dirs::home_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("continuum-studio")
    }

    /// Check if the flake exists
    pub fn flake_exists(&self) -> bool {
        self.flake_path.join("flake.nix").exists()
    }

    /// Generate the nix command to update
    pub fn update_command(&self) -> String {
        format!(
            "cd {} && nix flake update && nix build",
            self.flake_path.display()
        )
    }

    /// Generate the nix command to rebuild
    pub fn rebuild_command(&self) -> String {
        format!("cd {} && nix build", self.flake_path.display())
    }

    /// Execute update via system command
    pub async fn execute_update(&self) -> Result<String, String> {
        let flake_path = self.flake_path.clone();

        tokio::task::spawn_blocking(move || {
            use std::process::Command;

            // First update the flake inputs
            let update_output = Command::new("nix")
                .args(["flake", "update"])
                .current_dir(&flake_path)
                .output()
                .map_err(|e| format!("Failed to run nix flake update: {}", e))?;

            if !update_output.status.success() {
                let stderr = String::from_utf8_lossy(&update_output.stderr);
                return Err(format!("nix flake update failed: {}", stderr));
            }

            // Then rebuild
            let build_output = Command::new("nix")
                .args(["build"])
                .current_dir(&flake_path)
                .output()
                .map_err(|e| format!("Failed to run nix build: {}", e))?;

            if !build_output.status.success() {
                let stderr = String::from_utf8_lossy(&build_output.stderr);
                return Err(format!("nix build failed: {}", stderr));
            }

            Ok("Update completed successfully. Restart to apply.".to_string())
        })
        .await
        .map_err(|e| format!("Task failed: {}", e))?
    }
}

impl Default for NixUpdater {
    fn default() -> Self {
        Self::new(Self::default_flake_path())
    }
}

/// Update the desktop file to point to the new result
pub fn update_desktop_file(result_path: &PathBuf) -> Result<(), String> {
    let desktop_path = dirs::data_dir()
        .unwrap_or_else(|| PathBuf::from(".local/share"))
        .join("applications")
        .join("continuum-studio.desktop");

    if !desktop_path.exists() {
        return Err("Desktop file not found".to_string());
    }

    let binary_path = result_path.join("bin").join("continuum-studio-iced");
    if !binary_path.exists() {
        return Err(format!("Binary not found at {:?}", binary_path));
    }

    let content = std::fs::read_to_string(&desktop_path)
        .map_err(|e| format!("Failed to read desktop file: {}", e))?;

    // Update the Exec line
    let updated = content
        .lines()
        .map(|line| {
            if line.starts_with("Exec=") {
                format!("Exec={}", binary_path.display())
            } else {
                line.to_string()
            }
        })
        .collect::<Vec<_>>()
        .join("\n");

    std::fs::write(&desktop_path, updated)
        .map_err(|e| format!("Failed to write desktop file: {}", e))?;

    log::info!("Updated desktop file to point to {:?}", result_path);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_comparison() {
        let checker = UpdateChecker::new();

        assert!(checker.is_newer_version("0.2.0", "0.1.0"));
        assert!(checker.is_newer_version("1.0.0", "0.9.9"));
        assert!(checker.is_newer_version("0.1.1", "0.1.0"));
        assert!(!checker.is_newer_version("0.1.0", "0.1.0"));
        assert!(!checker.is_newer_version("0.1.0", "0.2.0"));
    }

    #[test]
    fn test_update_settings_default() {
        let settings = UpdateSettings::default();
        assert_eq!(settings.channel, UpdateChannel::Stable);
        assert!(settings.auto_check);
        assert_eq!(settings.check_interval_hours, 24);
    }

    #[test]
    fn test_channel_matching() {
        assert_eq!(UpdateChannel::Stable.as_str(), "stable");
        assert_eq!(UpdateChannel::Beta.as_str(), "beta");
        assert_eq!(UpdateChannel::Nightly.as_str(), "nightly");
    }
}
