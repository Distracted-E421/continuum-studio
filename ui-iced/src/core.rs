//! Core IPC client for communicating with Elixir backend
//!
//! This module handles the Unix socket connection to the Continuum Studio Core (Elixir).

use std::path::PathBuf;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::UnixStream;
use tokio::sync::mpsc;

/// Default socket path
pub const DEFAULT_SOCKET_PATH: &str = "/tmp/continuum-studio.sock";

/// Messages sent to the Core
#[derive(Debug, Clone, serde::Serialize)]
#[serde(tag = "type", content = "data")]
pub enum CoreRequest {
    #[serde(rename = "get_versions")]
    GetVersions,
    #[serde(rename = "launch_version")]
    LaunchVersion { version: String },
    #[serde(rename = "install_version")]
    InstallVersion { version: String },
    #[serde(rename = "uninstall_version")]
    UninstallVersion { version: String },
    #[serde(rename = "get_sessions")]
    GetSessions,
    #[serde(rename = "ping")]
    Ping,
}

/// Messages received from the Core
#[derive(Debug, Clone, serde::Deserialize)]
#[serde(tag = "type", content = "data")]
pub enum CoreResponse {
    #[serde(rename = "versions")]
    Versions(Vec<CursorVersion>),
    #[serde(rename = "launch_result")]
    LaunchResult { success: bool, message: String },
    #[serde(rename = "sessions")]
    Sessions(Vec<Session>),
    #[serde(rename = "pong")]
    Pong,
    #[serde(rename = "error")]
    Error { message: String },
}

/// Cursor version information
#[derive(Debug, Clone, serde::Deserialize, serde::Serialize)]
pub struct CursorVersion {
    pub version: String,
    pub status: VersionStatus,
    pub release_date: Option<String>,
    pub size_mb: Option<u32>,
}

/// Version installation status
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize, serde::Serialize)]
#[serde(rename_all = "lowercase")]
pub enum VersionStatus {
    Installed,
    Available,
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
pub fn spawn_core_connection(
    socket_path: PathBuf,
) -> (
    mpsc::Sender<CoreRequest>,
    mpsc::Receiver<CoreResponse>,
    mpsc::Receiver<bool>,
) {
    let (request_tx, mut request_rx) = mpsc::channel::<CoreRequest>(32);
    let (response_tx, response_rx) = mpsc::channel::<CoreResponse>(32);
    let (connected_tx, connected_rx) = mpsc::channel::<bool>(8);

    tokio::spawn(async move {
        loop {
            // Try to connect
            match UnixStream::connect(&socket_path).await {
                Ok(stream) => {
                    let _ = connected_tx.send(true).await;
                    log::info!("Connected to Core at {:?}", socket_path);

                    let (read_half, mut write_half) = stream.into_split();
                    let mut reader = BufReader::new(read_half);

                    // Handle requests and responses
                    loop {
                        tokio::select! {
                            // Handle outgoing requests
                            Some(request) = request_rx.recv() => {
                                let json = serde_json::to_string(&request).unwrap();
                                if let Err(e) = write_half.write_all(json.as_bytes()).await {
                                    log::error!("Failed to send request: {}", e);
                                    break;
                                }
                                if let Err(e) = write_half.write_all(b"\n").await {
                                    log::error!("Failed to send newline: {}", e);
                                    break;
                                }
                            }
                            // Handle incoming responses
                            result = async {
                                let mut line = String::new();
                                reader.read_line(&mut line).await.map(|_| line)
                            } => {
                                match result {
                                    Ok(line) if !line.is_empty() => {
                                        match serde_json::from_str::<CoreResponse>(&line) {
                                            Ok(response) => {
                                                let _ = response_tx.send(response).await;
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

                    let _ = connected_tx.send(false).await;
                }
                Err(e) => {
                    log::debug!("Failed to connect to Core: {}", e);
                }
            }

            // Wait before retry
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    });

    (request_tx, response_rx, connected_rx)
}
