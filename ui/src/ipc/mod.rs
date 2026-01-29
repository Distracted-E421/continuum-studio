//! IPC Module for Studio Core Communication
//!
//! This module provides multiple IPC mechanisms:
//! - **Unix Sockets**: For local communication with Studio Core (ETF-framed)
//! - **D-Bus**: For dialog daemon integration
//! - **WebSocket**: For remote/browser-based connections (future)
//!
//! # Protocol
//!
//! Messages are framed with a 4-byte big-endian length prefix:
//! ```text
//! +--------+----------------+
//! | length | ETF payload    |
//! | 4 bytes| variable       |
//! +--------+----------------+
//! ```
//!
//! # Commands (UI → Core)
//!
//! - `{:command, :harness_start, %{type: :cursor}}`
//! - `{:command, :state_set, %{path: [...], value: ...}}`
//! - `{:command, :agent_message, %{text: "...", provider: :cursor}}`
//!
//! # Events (Core → UI)
//!
//! - `{:event, :harness_status, %{harness: "cursor", status: "running"}}`
//! - `{:event, :state_changed, %{path: [...], value: ...}}`
//! - `{:event, :agent_response, %{content: "...", role: "assistant"}}`

pub mod dbus;
pub mod etf;

pub use dbus::{DialogClient, DialogResponse, ChoiceOption, ToastLevel};
pub use etf::{encode_command, decode_event};

use std::path::PathBuf;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::UnixStream;
use tokio::sync::mpsc;
use thiserror::Error;

/// IPC errors
#[derive(Error, Debug)]
pub enum IpcError {
    #[error("Connection failed: {0}")]
    ConnectionFailed(String),
    
    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),
    
    #[error("Encoding error: {0}")]
    Encoding(String),
    
    #[error("Decoding error: {0}")]
    Decoding(String),
    
    #[error("Channel closed")]
    ChannelClosed,
}

/// Command types sent from UI to Core
#[derive(Clone, Debug)]
pub enum Command {
    /// Start a harness
    HarnessStart { harness_type: String },
    /// Stop a harness
    HarnessStop { harness_type: String },
    /// Set application state
    StateSet { path: Vec<String>, value: serde_json::Value },
    /// Send message to agent
    AgentMessage { text: String, provider: String },
    /// Ping for health check
    Ping,
}

/// Events received from Core to UI
#[derive(Clone, Debug)]
pub enum Event {
    /// Harness status changed
    HarnessStatus { harness: String, status: String },
    /// State changed
    StateChanged { path: Vec<String>, value: serde_json::Value },
    /// Agent response
    AgentResponse { content: String, role: String },
    /// Pong response
    Pong,
    /// Error occurred
    Error { message: String },
}

/// IPC Client for communicating with Studio Core
pub struct IpcClient {
    /// Socket path
    socket_path: PathBuf,
    /// Command sender
    cmd_tx: Option<mpsc::Sender<Command>>,
    /// Event receiver
    event_rx: Option<mpsc::Receiver<Event>>,
}

impl IpcClient {
    /// Create a new IPC client
    pub fn new(socket_path: PathBuf) -> Self {
        Self {
            socket_path,
            cmd_tx: None,
            event_rx: None,
        }
    }
    
    /// Default socket path
    pub fn default_socket_path() -> PathBuf {
        PathBuf::from("/tmp/continuum-studio.sock")
    }
    
    /// Check if connected
    pub fn is_connected(&self) -> bool {
        self.cmd_tx.is_some()
    }
    
    /// Connect to Studio Core
    pub async fn connect(&mut self) -> Result<(), IpcError> {
        let stream = UnixStream::connect(&self.socket_path)
            .await
            .map_err(|e| IpcError::ConnectionFailed(e.to_string()))?;
        
        let (read_half, write_half) = stream.into_split();
        
        // Create channels
        let (cmd_tx, mut cmd_rx) = mpsc::channel::<Command>(32);
        let (event_tx, event_rx) = mpsc::channel::<Event>(32);
        
        self.cmd_tx = Some(cmd_tx);
        self.event_rx = Some(event_rx);
        
        // Spawn writer task
        let mut write_half = write_half;
        tokio::spawn(async move {
            while let Some(cmd) = cmd_rx.recv().await {
                match etf::encode_command(&cmd) {
                    Ok(payload) => {
                        if let Err(e) = write_frame(&mut write_half, &payload).await {
                            tracing::error!("Failed to write command: {}", e);
                            break;
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to encode command: {}", e);
                    }
                }
            }
        });
        
        // Spawn reader task
        let mut read_half = read_half;
        tokio::spawn(async move {
            loop {
                match read_frame(&mut read_half).await {
                    Ok(payload) => {
                        match etf::decode_event(&payload) {
                            Ok(Some(event)) => {
                                if event_tx.send(event).await.is_err() {
                                    break;
                                }
                            }
                            Ok(None) => {
                                tracing::warn!("Unknown event format");
                            }
                            Err(e) => {
                                tracing::error!("Failed to decode event: {}", e);
                            }
                        }
                    }
                    Err(e) => {
                        tracing::error!("Failed to read event: {}", e);
                        break;
                    }
                }
            }
        });
        
        Ok(())
    }
    
    /// Send a command to Studio Core
    pub async fn send(&self, cmd: Command) -> Result<(), IpcError> {
        if let Some(tx) = &self.cmd_tx {
            tx.send(cmd).await.map_err(|_| IpcError::ChannelClosed)
        } else {
            Err(IpcError::ConnectionFailed("Not connected".to_string()))
        }
    }
    
    /// Try to receive an event (non-blocking)
    pub fn try_recv(&mut self) -> Option<Event> {
        if let Some(rx) = &mut self.event_rx {
            rx.try_recv().ok()
        } else {
            None
        }
    }
}

/// Write a length-prefixed frame
async fn write_frame(stream: &mut tokio::net::unix::OwnedWriteHalf, payload: &[u8]) -> Result<(), IpcError> {
    let len = payload.len() as u32;
    stream.write_all(&len.to_be_bytes()).await?;
    stream.write_all(payload).await?;
    stream.flush().await?;
    Ok(())
}

/// Read a length-prefixed frame
async fn read_frame(stream: &mut tokio::net::unix::OwnedReadHalf) -> Result<Vec<u8>, IpcError> {
    let mut len_buf = [0u8; 4];
    stream.read_exact(&mut len_buf).await?;
    let len = u32::from_be_bytes(len_buf) as usize;
    
    let mut payload = vec![0u8; len];
    stream.read_exact(&mut payload).await?;
    Ok(payload)
}


