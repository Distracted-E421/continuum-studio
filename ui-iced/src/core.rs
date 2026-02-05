//! Core IPC client for communicating with Elixir backend
//!
//! This module handles the Unix socket connection to the Continuum Studio Core (Elixir).
//!
//! Features:
//! - Automatic reconnection with exponential backoff
//! - Heartbeat/keepalive mechanism
//! - Connection state tracking
//! - Graceful disconnection handling

use std::path::PathBuf;
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;

/// Default socket path
pub const DEFAULT_SOCKET_PATH: &str = "/tmp/continuum-studio.sock";

/// Reconnection configuration
pub struct ReconnectConfig {
    /// Initial delay before first retry
    pub initial_delay: Duration,
    /// Maximum delay between retries
    pub max_delay: Duration,
    /// Multiplier for exponential backoff
    pub backoff_multiplier: f64,
    /// Heartbeat interval
    pub heartbeat_interval: Duration,
}

impl Default for ReconnectConfig {
    fn default() -> Self {
        Self {
            initial_delay: Duration::from_secs(1),
            max_delay: Duration::from_secs(60),
            backoff_multiplier: 2.0,
            heartbeat_interval: Duration::from_secs(30),
        }
    }
}

/// Connection state for UI display
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionState {
    /// Not connected, not trying
    Disconnected,
    /// Attempting to connect
    Connecting,
    /// Connected and healthy
    Connected,
    /// Connection lost, will retry
    Reconnecting { attempt: u32 },
}

/// Messages sent to the Core
/// Format: {"command": "cmd_name", "params": {...}}
#[derive(Debug, Clone)]
pub enum CoreRequest {
    /// List all versions (with optional filters)
    GetVersions,
    /// List installed versions only
    GetInstalled,
    /// Launch a specific version
    LaunchVersion { version: String, folder: Option<String> },
    /// Download/install a version
    InstallVersion { version: String },
    /// Uninstall a version
    UninstallVersion { version: String },
    /// Get sessions
    GetSessions,
    /// Get version statistics
    GetStats,
    /// List workspaces
    GetWorkspaces { limit: Option<u32> },
    /// Register a workspace
    RegisterWorkspace { path: String },
    /// Toggle workspace pinned status
    ToggleWorkspacePinned { id: String },
    /// Refresh workspace git stats
    RefreshWorkspaceGit { id: String },
    /// Get auth status for a specific version
    GetAuthStatus { version: String },
    /// Get auth statuses for all installed versions
    GetAuthStatuses,
    /// Ping for health check
    Ping,
}

impl CoreRequest {
    /// Serialize to the JSON format expected by Elixir Core
    pub fn to_json(&self) -> String {
        let (command, params) = match self {
            CoreRequest::GetVersions => ("versions_list", serde_json::json!({})),
            CoreRequest::GetInstalled => ("versions_installed", serde_json::json!({})),
            CoreRequest::LaunchVersion { version, folder } => {
                let mut params = serde_json::json!({"version": version});
                if let Some(f) = folder {
                    params["folder"] = serde_json::Value::String(f.clone());
                }
                ("versions_run", params)
            }
            CoreRequest::InstallVersion { version } => {
                ("versions_download", serde_json::json!({"version": version}))
            }
            CoreRequest::UninstallVersion { version } => {
                ("versions_uninstall", serde_json::json!({"version": version}))
            }
            CoreRequest::GetSessions => ("sessions_list", serde_json::json!({})),
            CoreRequest::GetStats => ("versions_stats", serde_json::json!({})),
            CoreRequest::GetWorkspaces { limit } => {
                let mut params = serde_json::json!({});
                if let Some(l) = limit {
                    params["limit"] = serde_json::json!(l);
                }
                ("workspaces_list", params)
            }
            CoreRequest::RegisterWorkspace { path } => {
                ("workspaces_register", serde_json::json!({"path": path}))
            }
            CoreRequest::ToggleWorkspacePinned { id } => {
                ("workspaces_toggle_pinned", serde_json::json!({"id": id}))
            }
            CoreRequest::RefreshWorkspaceGit { id } => {
                ("workspaces_refresh_git", serde_json::json!({"id": id}))
            }
            CoreRequest::GetAuthStatus { version } => {
                ("auth_version_status", serde_json::json!({"version": version}))
            }
            CoreRequest::GetAuthStatuses => ("auth_list_statuses", serde_json::json!({})),
            CoreRequest::Ping => ("ping", serde_json::json!({})),
        };
        
        serde_json::json!({
            "command": command,
            "params": params
        }).to_string()
    }
}

/// Messages received from the Core
/// Format: {"event": "event_name", "data": {...}}
#[derive(Debug, Clone)]
pub enum CoreResponse {
    /// Version list response
    Versions(Vec<CursorVersion>),
    /// Installed versions response
    InstalledVersions(Vec<InstalledVersion>),
    /// Launch result
    LaunchResult { success: bool, message: String },
    /// Version running info
    VersionRunning { version: String, data_dir: String },
    /// Download started
    DownloadStarted { version: String },
    /// Download completed successfully
    DownloadCompleted { version: String, path: String },
    /// Download failed
    DownloadFailed { version: String, error: String },
    /// Version statistics
    Stats(VersionStats),
    /// Sessions list
    Sessions(Vec<Session>),
    /// Workspaces list
    Workspaces(Vec<Workspace>),
    /// Single workspace
    WorkspaceRegistered(Workspace),
    /// Workspace pinned status changed
    WorkspacePinned { id: String, pinned: bool },
    /// Workspace git stats updated
    WorkspaceGitStats { id: String, git_stats: Option<GitStats> },
    /// Auth status for a single version
    AuthStatus(AuthStatus),
    /// Auth statuses for all installed versions
    AuthStatuses(Vec<AuthStatus>),
    /// Pong response
    Pong,
    /// Error occurred
    Error { message: String },
}

impl CoreResponse {
    /// Parse from JSON format sent by Elixir Core
    pub fn from_json(json_str: &str) -> Result<Self, String> {
        let value: serde_json::Value = serde_json::from_str(json_str)
            .map_err(|e| format!("JSON parse error: {}", e))?;
        
        let event = value.get("event")
            .and_then(|v| v.as_str())
            .ok_or("Missing event field")?;
        
        let data = value.get("data").cloned().unwrap_or(serde_json::json!({}));
        
        match event {
            "versions_list" => {
                let mut versions: Vec<CursorVersion> = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse versions: {}", e))?;
                // Compute UI status from installed flag
                for v in &mut versions {
                    v.compute_status();
                }
                Ok(CoreResponse::Versions(versions))
            }
            "versions_installed" => {
                let versions: Vec<InstalledVersion> = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse installed versions: {}", e))?;
                Ok(CoreResponse::InstalledVersions(versions))
            }
            "version_running" => {
                let version = data.get("version")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let data_dir = data.get("data_dir")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                Ok(CoreResponse::VersionRunning { version, data_dir })
            }
            "version_download_started" => {
                let version = data.get("version")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                Ok(CoreResponse::DownloadStarted { version })
            }
            "version_downloaded" => {
                let version = data.get("version")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let path = data.get("path")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                Ok(CoreResponse::DownloadCompleted { version, path })
            }
            "version_download_failed" => {
                let version = data.get("version")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                let error = data.get("error")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown error")
                    .to_string();
                Ok(CoreResponse::DownloadFailed { version, error })
            }
            "launch_result" => {
                let success = data.get("success")
                    .and_then(|v| v.as_bool())
                    .unwrap_or(false);
                let message = data.get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("")
                    .to_string();
                Ok(CoreResponse::LaunchResult { success, message })
            }
            "versions_stats" => {
                let stats: VersionStats = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse stats: {}", e))?;
                Ok(CoreResponse::Stats(stats))
            }
            "sessions" => {
                let sessions: Vec<Session> = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse sessions: {}", e))?;
                Ok(CoreResponse::Sessions(sessions))
            }
            "workspaces_list" => {
                let workspaces: Vec<Workspace> = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse workspaces: {}", e))?;
                Ok(CoreResponse::Workspaces(workspaces))
            }
            "workspace_registered" => {
                let workspace: Workspace = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse workspace: {}", e))?;
                Ok(CoreResponse::WorkspaceRegistered(workspace))
            }
            "workspace_pinned" => {
                let id = data.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let pinned = data.get("pinned").and_then(|v| v.as_bool()).unwrap_or(false);
                Ok(CoreResponse::WorkspacePinned { id, pinned })
            }
            "workspace_git_stats" => {
                let id = data.get("id").and_then(|v| v.as_str()).unwrap_or("").to_string();
                let git_stats: Option<GitStats> = data.get("git_stats")
                    .and_then(|v| serde_json::from_value(v.clone()).ok());
                Ok(CoreResponse::WorkspaceGitStats { id, git_stats })
            }
            "auth_status" => {
                let status: AuthStatus = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse auth status: {}", e))?;
                Ok(CoreResponse::AuthStatus(status))
            }
            "auth_statuses" => {
                let statuses: Vec<AuthStatus> = serde_json::from_value(data)
                    .map_err(|e| format!("Failed to parse auth statuses: {}", e))?;
                Ok(CoreResponse::AuthStatuses(statuses))
            }
            "pong" => Ok(CoreResponse::Pong),
            "error" => {
                let message = data.get("message")
                    .and_then(|v| v.as_str())
                    .unwrap_or("Unknown error")
                    .to_string();
                Ok(CoreResponse::Error { message })
            }
            _ => Err(format!("Unknown event type: {}", event)),
        }
    }
}

/// Installed version with disk info
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct InstalledVersion {
    pub version: String,
    pub path: String,
    pub size: u64,
    pub size_human: String,
    pub date: Option<String>,
}

/// Version statistics
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct VersionStats {
    pub total_versions: u32,
    pub installed_count: u32,
    pub total_disk_usage: u64,
    pub total_disk_usage_human: String,
    pub platform: String,
    pub versions_dir: String,
}

/// Cursor version information (matches Elixir VersionRegistry output)
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct CursorVersion {
    pub version: String,
    /// Release date in YYYY-MM-DD format
    #[serde(default)]
    pub date: Option<String>,
    /// Download URL for this version
    #[serde(default)]
    pub url: Option<String>,
    /// Whether this version is installed
    #[serde(default)]
    pub installed: bool,
    /// Era category (latest, custom_modes, classic)
    #[serde(default)]
    pub era: Option<String>,
    /// Version notes (e.g., "Last version with custom modes")
    #[serde(default)]
    pub notes: Option<String>,
    
    // UI state fields (not from Elixir)
    #[serde(skip)]
    pub status: VersionStatus,
}

impl CursorVersion {
    /// Compute display status from installed flag
    pub fn compute_status(&mut self) {
        self.status = if self.installed {
            VersionStatus::Installed
        } else {
            VersionStatus::Available
        };
    }
}

/// Version installation status (for UI display)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VersionStatus {
    #[default]
    Available,
    Installed,
    Running,
    Downloading,
}

impl std::fmt::Display for VersionStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            VersionStatus::Installed => write!(f, "Installed"),
            VersionStatus::Available => write!(f, "Available"),
            VersionStatus::Running => write!(f, "Running"),
            VersionStatus::Downloading => write!(f, "Downloading"),
        }
    }
}

/// Session information
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct Session {
    pub id: String,
    pub name: String,
    pub status: String,
    pub started_at: Option<String>,
}

/// Workspace information (from Elixir Core)
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize, Default)]
pub struct Workspace {
    pub id: String,
    pub path: String,
    pub name: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub last_opened_at: Option<String>,
    #[serde(default)]
    pub open_count: u32,
    #[serde(default)]
    pub pinned: bool,
    #[serde(default)]
    pub tags: Vec<String>,
    #[serde(default)]
    pub color: Option<String>,
    #[serde(default)]
    pub git_stats: Option<GitStats>,
    #[serde(default)]
    pub versions: Vec<WorkspaceVersion>,
}

/// Git statistics for a workspace
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize, Default)]
pub struct GitStats {
    #[serde(default)]
    pub branch: String,
    #[serde(default)]
    pub commit_count: Option<u32>,
    #[serde(default)]
    pub uncommitted_changes: u32,
    #[serde(default)]
    pub last_commit: Option<String>,
    #[serde(default)]
    pub last_commit_message: Option<String>,
    #[serde(default)]
    pub total_files: Option<u32>,
}

/// Version record for a workspace
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct WorkspaceVersion {
    pub version: String,
    #[serde(default)]
    pub first_opened: Option<String>,
    #[serde(default)]
    pub last_opened: Option<String>,
    #[serde(default)]
    pub open_count: u32,
}

/// Authentication status for a Cursor version
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize, Default)]
pub struct AuthStatus {
    /// Version string (e.g., "2.4.27")
    pub version: String,
    /// User email if logged in
    #[serde(default)]
    pub email: Option<String>,
    /// Auth provider (Github, Google, Email)
    #[serde(default)]
    pub provider: Option<String>,
    /// Subscription type (free, pro, business)
    #[serde(default)]
    pub membership: String,
    /// Subscription status (active, canceled, etc.)
    #[serde(default)]
    pub subscription_status: Option<String>,
    /// Whether an access token exists
    #[serde(default)]
    pub has_token: bool,
    /// Overall auth status
    #[serde(default)]
    pub status: AuthState,
    /// Privacy mode setting
    #[serde(default)]
    pub privacy_mode: Option<String>,
}

/// Auth state enum
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AuthState {
    /// Authenticated with valid token
    Authenticated,
    /// Not logged in
    #[default]
    NotLoggedIn,
    /// Token may be stale/expired
    Stale,
    /// Unknown status
    Unknown,
}

impl std::fmt::Display for AuthState {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AuthState::Authenticated => write!(f, "Authenticated"),
            AuthState::NotLoggedIn => write!(f, "Not Logged In"),
            AuthState::Stale => write!(f, "Stale"),
            AuthState::Unknown => write!(f, "Unknown"),
        }
    }
}

/// Core client for IPC communication
pub struct CoreClient {
    socket_path: PathBuf,
    tx: Option<mpsc::Sender<CoreRequest>>,
}

impl CoreClient {
    pub fn new(socket_path: impl Into<PathBuf>) -> Self {
        Self {
            socket_path: socket_path.into(),
            tx: None,
        }
    }

    /// Check if socket exists
    pub fn socket_exists(&self) -> bool {
        self.socket_path.exists()
    }

    /// Send a request to the Core (fire and forget for now)
    pub async fn send(&self, request: CoreRequest) -> Result<(), String> {
        if let Some(tx) = &self.tx {
            tx.send(request).await.map_err(|e| e.to_string())
        } else {
            Err("Not connected".to_string())
        }
    }
}

/// Spawn a background task to maintain connection to Core
///
/// Returns channels for:
/// - Sending requests to Core
/// - Receiving responses from Core  
/// - Receiving connection state changes
pub fn spawn_core_connection(
    socket_path: PathBuf,
) -> (
    mpsc::Sender<CoreRequest>,
    mpsc::Receiver<CoreResponse>,
    mpsc::Receiver<ConnectionState>,
) {
    spawn_core_connection_with_config(socket_path, ReconnectConfig::default())
}

/// Spawn core connection with custom reconnection configuration
pub fn spawn_core_connection_with_config(
    socket_path: PathBuf,
    config: ReconnectConfig,
) -> (
    mpsc::Sender<CoreRequest>,
    mpsc::Receiver<CoreResponse>,
    mpsc::Receiver<ConnectionState>,
) {
    let (request_tx, mut request_rx) = mpsc::channel::<CoreRequest>(32);
    let (response_tx, response_rx) = mpsc::channel::<CoreResponse>(32);
    let (state_tx, state_rx) = mpsc::channel::<ConnectionState>(8);

    tokio::spawn(async move {
        let mut retry_delay = config.initial_delay;
        let mut retry_count: u32 = 0;

        loop {
            // Notify connecting state
            if retry_count > 0 {
                let _ = state_tx
                    .send(ConnectionState::Reconnecting {
                        attempt: retry_count,
                    })
                    .await;
                log::info!(
                    "Reconnecting to Core (attempt {}), delay: {:?}",
                    retry_count,
                    retry_delay
                );
            } else {
                let _ = state_tx.send(ConnectionState::Connecting).await;
            }

            // Try to connect
            match UnixStream::connect(&socket_path).await {
                Ok(stream) => {
                    // Reset backoff on successful connection
                    retry_delay = config.initial_delay;
                    retry_count = 0;

                    let _ = state_tx.send(ConnectionState::Connected).await;
                    log::info!("Connected to Core at {:?}", socket_path);

                    let (read_half, mut write_half) = stream.into_split();
                    let mut reader = BufReader::new(read_half);

                    // Create heartbeat interval
                    let mut heartbeat = tokio::time::interval(config.heartbeat_interval);
                    heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

                    // Handle requests and responses
                    loop {
                        tokio::select! {
                            // Heartbeat tick
                            _ = heartbeat.tick() => {
                                let ping = CoreRequest::Ping.to_json();
                                if let Err(e) = write_half.write_all(ping.as_bytes()).await {
                                    log::warn!("Heartbeat failed: {}", e);
                                    break;
                                }
                                if let Err(e) = write_half.write_all(b"\n").await {
                                    log::warn!("Heartbeat newline failed: {}", e);
                                    break;
                                }
                                log::debug!("Heartbeat sent");
                            }
                            // Handle outgoing requests
                            Some(request) = request_rx.recv() => {
                                let json = request.to_json();
                                if let Err(e) = write_half.write_all(json.as_bytes()).await {
                                    log::error!("Failed to send request: {}", e);
                                    break;
                                }
                                if let Err(e) = write_half.write_all(b"\n").await {
                                    log::error!("Failed to send newline: {}", e);
                                    break;
                                }
                                log::debug!("Sent request: {:?}", json);
                            }
                            // Handle incoming responses
                            result = async {
                                let mut line = String::new();
                                reader.read_line(&mut line).await.map(|_| line)
                            } => {
                                match result {
                                    Ok(line) if !line.is_empty() => {
                                        match CoreResponse::from_json(&line) {
                                            Ok(response) => {
                                                // Don't send Pong to the app (internal heartbeat)
                                                if !matches!(response, CoreResponse::Pong) {
                                                    let _ = response_tx.send(response).await;
                                                } else {
                                                    log::debug!("Heartbeat pong received");
                                                }
                                            }
                                            Err(e) => {
                                                log::warn!("Failed to parse response: {} - {}", e, line.trim());
                                            }
                                        }
                                    }
                                    Ok(_) => {
                                        log::info!("Core disconnected (EOF)");
                                        break;
                                    }
                                    Err(e) => {
                                        log::error!("Read error: {}", e);
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    let _ = state_tx.send(ConnectionState::Disconnected).await;
                }
                Err(e) => {
                    log::debug!("Failed to connect to Core: {}", e);
                }
            }

            // Exponential backoff
            retry_count += 1;
            tokio::time::sleep(retry_delay).await;

            // Increase delay with exponential backoff
            retry_delay = Duration::from_secs_f64(
                (retry_delay.as_secs_f64() * config.backoff_multiplier)
                    .min(config.max_delay.as_secs_f64()),
            );
        }
    });

    (request_tx, response_rx, state_rx)
}
