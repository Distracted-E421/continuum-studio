//! Auto-update system for Continuum Studio
//!
//! Supports multiple update channels and git forges:
//! - Channels: stable, beta, nightly
//! - Forges: GitHub, Forgejo/Codeberg/Gitea, Local builds
//!
//! Update mechanism:
//! - Checks releases from configured forge or local builds
//! - Smart detection: Nix store vs direct binary
//! - Can trigger rebuild via nix flake update or direct binary replacement

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

// ---------------------------------------------------------------------------
// Update channel
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Forge type
// ---------------------------------------------------------------------------

/// Git forge / release source type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum ForgeType {
    /// GitHub API (api.github.com)
    #[default]
    GitHub,
    /// Forgejo / Gitea / Codeberg API (compatible v1 API)
    Forgejo,
    /// Local builds from ~/.continuum/builds/
    Local,
}

impl ForgeType {
    pub fn as_str(&self) -> &'static str {
        match self {
            ForgeType::GitHub => "github",
            ForgeType::Forgejo => "forgejo",
            ForgeType::Local => "local",
        }
    }

    pub fn description(&self) -> &'static str {
        match self {
            ForgeType::GitHub => "GitHub (api.github.com)",
            ForgeType::Forgejo => "Forgejo / Codeberg / Gitea (v1 API)",
            ForgeType::Local => "Local builds (~/.continuum/builds/)",
        }
    }
}

// ---------------------------------------------------------------------------
// Update settings
// ---------------------------------------------------------------------------

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

    /// Git forge type for release checking
    #[serde(default)]
    pub forge_type: ForgeType,

    /// Forge base URL (e.g., "https://codeberg.org" for Forgejo)
    /// Not used for GitHub (hardcoded) or Local
    #[serde(default)]
    pub forge_url: Option<String>,

    /// Repository path on the forge (e.g., "e421/continuum-studio")
    #[serde(default = "default_repo_path")]
    pub repo_path: String,
}

fn default_true() -> bool {
    true
}

fn default_check_interval() -> u32 {
    24 // 24 hours
}

fn default_repo_path() -> String {
    "Distracted-E421/continuum-studio".to_string()
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
            forge_type: ForgeType::Local, // Default to local builds
            forge_url: None,
            repo_path: default_repo_path(),
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

// ---------------------------------------------------------------------------
// Release info (forge-agnostic)
// ---------------------------------------------------------------------------

/// Update information from any source
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpdateInfo {
    /// Version string
    pub version: String,
    /// Release channel
    pub channel: UpdateChannel,
    /// Release notes
    pub notes: String,
    /// Download URL or local path
    pub download_url: Option<String>,
    /// Release date
    pub release_date: String,
    /// Whether this is a prerelease
    pub prerelease: bool,
    /// Commit hash (for local builds)
    pub commit: Option<String>,
    /// Source forge type
    pub source: ForgeType,
}

// ---------------------------------------------------------------------------
// Installation path detection
// ---------------------------------------------------------------------------

/// Detected installation type
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallationType {
    /// Running from /nix/store/ -- should update via nix build
    NixStore,
    /// Running from ~/.continuum/bin/ -- direct binary replacement
    ContinuumBin,
    /// Running from cargo target/release -- development mode
    CargoDev,
    /// Unknown location
    Unknown(PathBuf),
}

impl InstallationType {
    /// Detect how the current binary was installed
    pub fn detect() -> Self {
        let exe = match std::env::current_exe() {
            Ok(p) => p,
            Err(_) => return InstallationType::Unknown(PathBuf::new()),
        };

        let exe_str = exe.to_string_lossy();

        if exe_str.contains("/nix/store/") {
            InstallationType::NixStore
        } else if exe_str.contains("/.continuum/") {
            InstallationType::ContinuumBin
        } else if exe_str.contains("/target/release/") || exe_str.contains("/target/debug/") {
            InstallationType::CargoDev
        } else {
            InstallationType::Unknown(exe)
        }
    }

    /// Human-readable description
    pub fn description(&self) -> &str {
        match self {
            InstallationType::NixStore => "Nix store (use nix build to update)",
            InstallationType::ContinuumBin => "Continuum managed (direct binary update)",
            InstallationType::CargoDev => "Development build (cargo build)",
            InstallationType::Unknown(_) => "Unknown installation",
        }
    }

    /// Whether direct binary replacement is supported
    pub fn supports_direct_update(&self) -> bool {
        matches!(self, InstallationType::ContinuumBin | InstallationType::CargoDev)
    }
}

// ---------------------------------------------------------------------------
// Forge release response types
// ---------------------------------------------------------------------------

/// GitHub release response (subset of fields we need)
#[derive(Debug, Clone, Deserialize)]
struct GitHubRelease {
    tag_name: String,
    #[allow(dead_code)]
    name: String,
    body: Option<String>,
    prerelease: bool,
    published_at: String,
    html_url: String,
    assets: Option<Vec<GitHubAsset>>,
}

#[derive(Debug, Clone, Deserialize)]
struct GitHubAsset {
    name: String,
    browser_download_url: String,
    #[allow(dead_code)]
    size: u64,
}

/// Forgejo/Gitea release response
#[derive(Debug, Clone, Deserialize)]
struct ForgejoRelease {
    tag_name: String,
    #[allow(dead_code)]
    name: String,
    body: Option<String>,
    prerelease: bool,
    published_at: Option<String>,
    created_at: String,
    html_url: Option<String>,
    assets: Option<Vec<ForgejoAsset>>,
}

#[derive(Debug, Clone, Deserialize)]
struct ForgejoAsset {
    name: String,
    browser_download_url: String,
    #[allow(dead_code)]
    size: u64,
}

/// Local build metadata (from build-watcher.nu)
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LocalBuildMetadata {
    version: String,
    channel: String,
    commit: String,
    branch: String,
    build_date: String,
    #[allow(dead_code)]
    binary_size: Option<u64>,
}

// ---------------------------------------------------------------------------
// Release provider trait (forge-agnostic abstraction)
// ---------------------------------------------------------------------------

/// Forge-agnostic release provider interface.
///
/// Each implementation knows how to fetch releases and download assets from
/// a specific source: GitHub, Forgejo/Codeberg/Gitea, or local builds.
pub trait ReleaseProvider: Send + Sync {
    /// Fetch the best matching release for the given channel.
    ///
    /// Returns `None` if no matching release is found.
    fn fetch_latest(
        &self,
        settings: &UpdateSettings,
    ) -> impl std::future::Future<Output = Result<Option<UpdateInfo>, String>> + Send;

    /// Download a release asset to the target path.
    fn download_asset(
        &self,
        release: &UpdateInfo,
        target: &Path,
    ) -> impl std::future::Future<Output = Result<(), String>> + Send;

    /// Human-readable provider name (for logging).
    fn provider_name(&self) -> &str;
}

// ---------------------------------------------------------------------------
// GitHub provider
// ---------------------------------------------------------------------------

/// Fetches releases from GitHub (api.github.com).
pub struct GitHubProvider {
    user_agent: String,
}

impl GitHubProvider {
    pub fn new() -> Self {
        Self {
            user_agent: format!(
                "continuum-studio/{} (update-checker)",
                env!("CARGO_PKG_VERSION")
            ),
        }
    }
}

impl ReleaseProvider for GitHubProvider {
    async fn fetch_latest(&self, settings: &UpdateSettings) -> Result<Option<UpdateInfo>, String> {
        let url = format!(
            "https://api.github.com/repos/{}/releases",
            settings.repo_path
        );

        let releases = fetch_json_http::<Vec<GitHubRelease>>(
            &url,
            &self.user_agent,
            Some("application/vnd.github.v3+json"),
        )
        .await?;
        Ok(find_best_github_release(&releases, settings))
    }

    async fn download_asset(&self, release: &UpdateInfo, target: &Path) -> Result<(), String> {
        let url = release
            .download_url
            .as_deref()
            .ok_or("No download URL in GitHub release")?;
        download_to_path(url, target, &self.user_agent).await
    }

    fn provider_name(&self) -> &str {
        "GitHub"
    }
}

// ---------------------------------------------------------------------------
// Forgejo / Codeberg / Gitea provider
// ---------------------------------------------------------------------------

/// Fetches releases from Forgejo-compatible forges (Codeberg, Gitea, self-hosted).
pub struct ForgejoProvider {
    user_agent: String,
}

impl ForgejoProvider {
    pub fn new() -> Self {
        Self {
            user_agent: format!(
                "continuum-studio/{} (update-checker)",
                env!("CARGO_PKG_VERSION")
            ),
        }
    }
}

impl ReleaseProvider for ForgejoProvider {
    async fn fetch_latest(&self, settings: &UpdateSettings) -> Result<Option<UpdateInfo>, String> {
        let base_url = settings
            .forge_url
            .as_deref()
            .unwrap_or("https://codeberg.org");

        let url = format!(
            "{}/api/v1/repos/{}/releases",
            base_url.trim_end_matches('/'),
            settings.repo_path
        );

        let releases =
            fetch_json_http::<Vec<ForgejoRelease>>(&url, &self.user_agent, None).await?;
        Ok(find_best_forgejo_release(&releases, settings, base_url))
    }

    async fn download_asset(&self, release: &UpdateInfo, target: &Path) -> Result<(), String> {
        let url = release
            .download_url
            .as_deref()
            .ok_or("No download URL in Forgejo release")?;
        download_to_path(url, target, &self.user_agent).await
    }

    fn provider_name(&self) -> &str {
        "Forgejo"
    }
}

// ---------------------------------------------------------------------------
// Local build provider
// ---------------------------------------------------------------------------

/// Reads builds produced by the build-watcher from ~/.continuum/builds/.
pub struct LocalBuildProvider;

impl LocalBuildProvider {
    pub fn new() -> Self {
        Self
    }
}

impl ReleaseProvider for LocalBuildProvider {
    async fn fetch_latest(&self, settings: &UpdateSettings) -> Result<Option<UpdateInfo>, String> {
        let channel_dir = local_builds_dir().join(settings.channel.as_str());
        let metadata_path = channel_dir.join("metadata.json");

        if !metadata_path.exists() {
            log::info!("No local build metadata found at {:?}", metadata_path);
            return Ok(None);
        }

        let content = tokio::fs::read_to_string(&metadata_path)
            .await
            .map_err(|e| format!("Failed to read local build metadata: {}", e))?;

        let metadata: LocalBuildMetadata = serde_json::from_str(&content)
            .map_err(|e| format!("Failed to parse local build metadata: {}", e))?;

        let binary_path = channel_dir.join("continuum-studio");
        if !binary_path.exists() {
            log::warn!(
                "Local build metadata exists but binary missing at {:?}",
                binary_path
            );
            return Ok(None);
        }

        Ok(Some(UpdateInfo {
            version: metadata.version,
            channel: match metadata.channel.as_str() {
                "stable" => UpdateChannel::Stable,
                "beta" => UpdateChannel::Beta,
                _ => UpdateChannel::Nightly,
            },
            notes: format!(
                "Local build from branch {} @ {}",
                metadata.branch,
                &metadata.commit[..8.min(metadata.commit.len())]
            ),
            download_url: Some(binary_path.to_string_lossy().to_string()),
            release_date: metadata.build_date,
            prerelease: metadata.channel != "stable",
            commit: Some(metadata.commit),
            source: ForgeType::Local,
        }))
    }

    async fn download_asset(&self, release: &UpdateInfo, target: &Path) -> Result<(), String> {
        // Local builds are just copied from the builds directory
        let source = release
            .download_url
            .as_deref()
            .ok_or("No source path in local build info")?;
        let source = Path::new(source);
        if !source.exists() {
            return Err(format!("Source binary not found: {:?}", source));
        }
        tokio::fs::copy(source, target)
            .await
            .map_err(|e| format!("Failed to copy local build: {}", e))?;

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = std::fs::Permissions::from_mode(0o755);
            tokio::fs::set_permissions(target, perms)
                .await
                .map_err(|e| format!("Failed to set permissions: {}", e))?;
        }

        Ok(())
    }

    fn provider_name(&self) -> &str {
        "Local Builds"
    }
}

// ---------------------------------------------------------------------------
// Provider factory
// ---------------------------------------------------------------------------

/// Create the appropriate release provider for the given forge type.
pub fn create_provider(forge_type: ForgeType) -> Box<dyn std::any::Any + Send + Sync> {
    match forge_type {
        ForgeType::GitHub => Box::new(GitHubProvider::new()),
        ForgeType::Forgejo => Box::new(ForgejoProvider::new()),
        ForgeType::Local => Box::new(LocalBuildProvider::new()),
    }
}

// ---------------------------------------------------------------------------
// Shared HTTP helpers (used by providers)
// ---------------------------------------------------------------------------

async fn fetch_json_http<T: serde::de::DeserializeOwned + Send + 'static>(
    url: &str,
    user_agent: &str,
    accept: Option<&str>,
) -> Result<T, String> {
    let url = url.to_string();
    let user_agent = user_agent.to_string();
    let accept = accept.map(|s| s.to_string());

    tokio::task::spawn_blocking(move || {
        let client = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(30))
            .build();

        let mut req = client.get(&url).set("User-Agent", &user_agent);

        if let Some(ref accept_header) = accept {
            req = req.set("Accept", accept_header);
        }

        let response = req
            .call()
            .map_err(|e| format!("HTTP request failed: {}", e))?;

        response
            .into_json::<T>()
            .map_err(|e| format!("Failed to parse response: {}", e))
    })
    .await
    .map_err(|e| format!("Task failed: {}", e))?
}

async fn download_to_path(url: &str, target: &Path, user_agent: &str) -> Result<(), String> {
    let url = url.to_string();
    let target = target.to_path_buf();
    let user_agent = user_agent.to_string();

    tokio::task::spawn_blocking(move || {
        let client = ureq::AgentBuilder::new()
            .timeout(Duration::from_secs(300))
            .build();

        let response = client
            .get(&url)
            .set("User-Agent", &user_agent)
            .call()
            .map_err(|e| format!("Download failed: {}", e))?;

        let mut reader = response.into_reader();
        let mut file =
            std::fs::File::create(&target).map_err(|e| format!("Failed to create file: {}", e))?;

        std::io::copy(&mut reader, &mut file)
            .map_err(|e| format!("Failed to write download: {}", e))?;

        Ok(())
    })
    .await
    .map_err(|e| format!("Download task failed: {}", e))?
}

// ---------------------------------------------------------------------------
// Update checker (uses providers)
// ---------------------------------------------------------------------------

/// Update checker service -- dispatches to the appropriate ReleaseProvider
pub struct UpdateChecker;

impl UpdateChecker {
    /// Create a new update checker
    pub fn new() -> Self {
        Self
    }

    /// Create with specific settings (kept for API compatibility)
    pub fn with_settings(_settings: &UpdateSettings) -> Self {
        Self
    }

    /// Check for updates asynchronously using the appropriate provider
    pub async fn check_for_updates(
        &self,
        settings: &UpdateSettings,
    ) -> Result<Option<UpdateInfo>, String> {
        log::info!(
            "Checking for updates on {} channel via {} provider",
            settings.channel.as_str(),
            settings.forge_type.as_str(),
        );

        let update = match settings.forge_type {
            ForgeType::GitHub => {
                let provider = GitHubProvider::new();
                log::info!("Using provider: {}", provider.provider_name());
                provider.fetch_latest(settings).await?
            }
            ForgeType::Forgejo => {
                let provider = ForgejoProvider::new();
                log::info!("Using provider: {}", provider.provider_name());
                provider.fetch_latest(settings).await?
            }
            ForgeType::Local => {
                let provider = LocalBuildProvider::new();
                log::info!("Using provider: {}", provider.provider_name());
                provider.fetch_latest(settings).await?
            }
        };

        if let Some(ref info) = update {
            if is_newer_version(&info.version, &settings.current_version) {
                log::info!(
                    "Update available: {} -> {} (from {})",
                    settings.current_version,
                    info.version,
                    settings.forge_type.as_str(),
                );
                return Ok(Some(info.clone()));
            }
        }

        log::info!(
            "No updates available (current: {})",
            settings.current_version
        );
        Ok(None)
    }
}

impl Default for UpdateChecker {
    fn default() -> Self {
        Self::new()
    }
}

// ---------------------------------------------------------------------------
// Release matching helpers
// ---------------------------------------------------------------------------

fn find_best_github_release(
    releases: &[GitHubRelease],
    settings: &UpdateSettings,
) -> Option<UpdateInfo> {
    for release in releases {
        if channel_matches_release(settings.channel, release.prerelease, &release.tag_name) {
            let version = release.tag_name.trim_start_matches('v').to_string();
            let download_url = release.assets.as_ref()
                .and_then(|assets| {
                    assets.iter().find(|a| {
                        a.name.contains("linux") && a.name.contains("x86_64")
                    })
                    .map(|a| a.browser_download_url.clone())
                })
                .or_else(|| Some(release.html_url.clone()));

            return Some(UpdateInfo {
                version,
                channel: classify_channel(release.prerelease, &release.tag_name),
                notes: release.body.clone().unwrap_or_default(),
                download_url,
                release_date: release.published_at.clone(),
                prerelease: release.prerelease,
                commit: None,
                source: ForgeType::GitHub,
            });
        }
    }
    None
}

fn find_best_forgejo_release(
    releases: &[ForgejoRelease],
    settings: &UpdateSettings,
    _base_url: &str,
) -> Option<UpdateInfo> {
    for release in releases {
        if channel_matches_release(settings.channel, release.prerelease, &release.tag_name) {
            let version = release.tag_name.trim_start_matches('v').to_string();
            let download_url = release.assets.as_ref()
                .and_then(|assets| {
                    assets.iter().find(|a| {
                        a.name.contains("linux") && a.name.contains("x86_64")
                    })
                    .map(|a| a.browser_download_url.clone())
                })
                .or_else(|| release.html_url.clone());

            let date = release.published_at.clone()
                .unwrap_or_else(|| release.created_at.clone());

            return Some(UpdateInfo {
                version,
                channel: classify_channel(release.prerelease, &release.tag_name),
                notes: release.body.clone().unwrap_or_default(),
                download_url,
                release_date: date,
                prerelease: release.prerelease,
                commit: None,
                source: ForgeType::Forgejo,
            });
        }
    }
    None
}

fn channel_matches_release(channel: UpdateChannel, prerelease: bool, tag: &str) -> bool {
    match channel {
        UpdateChannel::Stable => !prerelease && !tag.contains('-'),
        UpdateChannel::Beta => !tag.contains("nightly"),
        UpdateChannel::Nightly => true,
    }
}

fn classify_channel(prerelease: bool, tag: &str) -> UpdateChannel {
    if prerelease {
        if tag.contains("nightly") {
            UpdateChannel::Nightly
        } else {
            UpdateChannel::Beta
        }
    } else {
        UpdateChannel::Stable
    }
}

// ---------------------------------------------------------------------------
// Version comparison
// ---------------------------------------------------------------------------

/// Compare version strings (simple semver comparison)
pub fn is_newer_version(new: &str, current: &str) -> bool {
    let parse_version = |s: &str| -> Vec<u32> {
        s.split(|c: char| c == '.' || c == '-')
            .filter_map(|part| part.parse().ok())
            .collect()
    };

    let new_parts = parse_version(new);
    let current_parts = parse_version(current);

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

// ---------------------------------------------------------------------------
// Path helpers
// ---------------------------------------------------------------------------

/// Get the local builds directory
pub fn local_builds_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".continuum")
        .join("builds")
}

/// Get the continuum bin directory
pub fn continuum_bin_dir() -> PathBuf {
    dirs::home_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join(".continuum")
        .join("bin")
}

// ---------------------------------------------------------------------------
// Self-update execution
// ---------------------------------------------------------------------------

/// Execute a self-update based on detected installation type
pub struct SelfUpdater;

impl SelfUpdater {
    /// Apply an update from the given UpdateInfo
    pub async fn apply_update(info: &UpdateInfo) -> Result<String, String> {
        let install_type = InstallationType::detect();
        log::info!("Installation type: {:?}", install_type);

        match install_type {
            InstallationType::NixStore => {
                Self::update_via_nix().await
            }
            InstallationType::ContinuumBin | InstallationType::CargoDev => {
                match info.source {
                    ForgeType::Local => {
                        // Copy from local builds
                        let source = info.download_url.as_deref()
                            .ok_or("No source path in local build info")?;
                        Self::update_direct(Path::new(source)).await
                    }
                    ForgeType::GitHub | ForgeType::Forgejo => {
                        // Download from remote
                        let url = info.download_url.as_deref()
                            .ok_or("No download URL in release info")?;
                        Self::update_from_url(url).await
                    }
                }
            }
            InstallationType::Unknown(_) => {
                Err("Cannot determine update method for unknown installation type. \
                     Try running from ~/.continuum/bin/ or via nix build.".to_string())
            }
        }
    }

    /// Update via Nix flake
    async fn update_via_nix() -> Result<String, String> {
        let flake_path = NixUpdater::default_flake_path();
        let updater = NixUpdater::new(flake_path);

        if !updater.flake_exists() {
            return Err("Nix flake not found. Cannot update via nix build. \
                       Consider switching to 'local' forge type for direct binary updates.".to_string());
        }

        updater.execute_update().await
    }

    /// Update by copying a local binary
    async fn update_direct(source: &Path) -> Result<String, String> {
        if !source.exists() {
            return Err(format!("Source binary not found: {:?}", source));
        }

        let dest = continuum_bin_dir().join("continuum-studio");

        // Create parent dir
        if let Some(parent) = dest.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|e| format!("Failed to create bin directory: {}", e))?;
        }

        // Copy binary
        tokio::fs::copy(source, &dest)
            .await
            .map_err(|e| format!("Failed to copy binary: {}", e))?;

        // Make executable
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = std::fs::Permissions::from_mode(0o755);
            tokio::fs::set_permissions(&dest, perms)
                .await
                .map_err(|e| format!("Failed to set permissions: {}", e))?;
        }

        log::info!("Binary updated at {:?}", dest);
        Ok(format!("Updated binary at {}. Restart to apply.", dest.display()))
    }

    /// Update by downloading from a URL
    async fn update_from_url(url: &str) -> Result<String, String> {
        let dest = continuum_bin_dir().join("continuum-studio");
        let temp = continuum_bin_dir().join("continuum-studio.download");

        // Create parent dir
        if let Some(parent) = dest.parent() {
            tokio::fs::create_dir_all(parent)
                .await
                .map_err(|e| format!("Failed to create bin directory: {}", e))?;
        }

        // Download to temp file
        let url = url.to_string();
        let temp_clone = temp.clone();
        tokio::task::spawn_blocking(move || {
            let client = ureq::AgentBuilder::new()
                .timeout(Duration::from_secs(300))
                .build();

            let response = client
                .get(&url)
                .call()
                .map_err(|e| format!("Download failed: {}", e))?;

            let mut reader = response.into_reader();
            let mut file = std::fs::File::create(&temp_clone)
                .map_err(|e| format!("Failed to create temp file: {}", e))?;

            std::io::copy(&mut reader, &mut file)
                .map_err(|e| format!("Failed to write download: {}", e))?;

            Ok::<(), String>(())
        })
        .await
        .map_err(|e| format!("Download task failed: {}", e))??;

        // Atomically replace
        tokio::fs::rename(&temp, &dest)
            .await
            .map_err(|e| format!("Failed to replace binary: {}", e))?;

        // Make executable
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let perms = std::fs::Permissions::from_mode(0o755);
            tokio::fs::set_permissions(&dest, perms)
                .await
                .map_err(|e| format!("Failed to set permissions: {}", e))?;
        }

        log::info!("Downloaded and installed update at {:?}", dest);
        Ok(format!("Updated binary at {}. Restart to apply.", dest.display()))
    }
}

// ---------------------------------------------------------------------------
// Nix updater (preserved from original)
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Desktop file helper
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_comparison() {
        assert!(is_newer_version("0.2.0", "0.1.0"));
        assert!(is_newer_version("1.0.0", "0.9.9"));
        assert!(is_newer_version("0.1.1", "0.1.0"));
        assert!(!is_newer_version("0.1.0", "0.1.0"));
        assert!(!is_newer_version("0.1.0", "0.2.0"));
    }

    #[test]
    fn test_update_settings_default() {
        let settings = UpdateSettings::default();
        assert_eq!(settings.channel, UpdateChannel::Stable);
        assert!(settings.auto_check);
        assert_eq!(settings.check_interval_hours, 24);
        assert_eq!(settings.forge_type, ForgeType::Local);
    }

    #[test]
    fn test_channel_matching() {
        assert_eq!(UpdateChannel::Stable.as_str(), "stable");
        assert_eq!(UpdateChannel::Beta.as_str(), "beta");
        assert_eq!(UpdateChannel::Nightly.as_str(), "nightly");
    }

    #[test]
    fn test_forge_type() {
        assert_eq!(ForgeType::GitHub.as_str(), "github");
        assert_eq!(ForgeType::Forgejo.as_str(), "forgejo");
        assert_eq!(ForgeType::Local.as_str(), "local");
    }

    #[test]
    fn test_channel_matching_logic() {
        // Stable should only match non-prerelease, no dashes
        assert!(channel_matches_release(UpdateChannel::Stable, false, "v0.1.0"));
        assert!(!channel_matches_release(UpdateChannel::Stable, true, "v0.1.0-beta.1"));
        assert!(!channel_matches_release(UpdateChannel::Stable, false, "v0.1.0-rc1"));

        // Beta should match everything except nightly
        assert!(channel_matches_release(UpdateChannel::Beta, false, "v0.1.0"));
        assert!(channel_matches_release(UpdateChannel::Beta, true, "v0.1.0-beta.1"));
        assert!(!channel_matches_release(UpdateChannel::Beta, true, "v0.1.0-nightly.20260205"));

        // Nightly matches all
        assert!(channel_matches_release(UpdateChannel::Nightly, false, "v0.1.0"));
        assert!(channel_matches_release(UpdateChannel::Nightly, true, "v0.1.0-nightly.20260205"));
    }

    #[test]
    fn test_installation_type_description() {
        assert_eq!(InstallationType::NixStore.description(), "Nix store (use nix build to update)");
        assert_eq!(InstallationType::ContinuumBin.description(), "Continuum managed (direct binary update)");
        assert!(InstallationType::ContinuumBin.supports_direct_update());
        assert!(!InstallationType::NixStore.supports_direct_update());
    }

    #[test]
    fn test_local_builds_dir() {
        let dir = local_builds_dir();
        assert!(dir.to_string_lossy().contains(".continuum"));
        assert!(dir.to_string_lossy().contains("builds"));
    }
}
