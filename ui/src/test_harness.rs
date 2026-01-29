//! Test Harness for Continuum Studio UI
//!
//! Provides a mock server and automated testing capabilities
//! for visual and functional verification of the UI.

use anyhow::Result;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UnixListener};
use tokio::sync::{broadcast, mpsc, RwLock};
use tracing::{debug, error, info, warn};

/// Test harness configuration
#[derive(Debug, Clone)]
pub struct TestConfig {
    /// Use Unix socket instead of TCP
    pub use_unix_socket: bool,
    /// Socket path (for Unix sockets)
    pub socket_path: PathBuf,
    /// TCP port (for TCP sockets)
    pub tcp_port: u16,
    /// Enable auto-events (simulated harness status changes)
    pub auto_events: bool,
    /// Event interval in milliseconds
    pub event_interval_ms: u64,
}

impl Default for TestConfig {
    fn default() -> Self {
        Self {
            use_unix_socket: true,
            socket_path: PathBuf::from("/tmp/continuum-studio-test.sock"),
            tcp_port: 9999,
            auto_events: true,
            event_interval_ms: 2000,
        }
    }
}

/// Mock event for testing
#[derive(Debug, Clone)]
pub enum MockEvent {
    HarnessStatus { harness: String, status: String },
    AgentMessage { role: String, content: String },
    StateChanged { path: Vec<String>, value: serde_json::Value },
    Error { message: String },
}

impl MockEvent {
    pub fn to_json(&self) -> serde_json::Value {
        match self {
            MockEvent::HarnessStatus { harness, status } => {
                serde_json::json!({
                    "type": "event",
                    "event": "harness_status",
                    "data": { "harness": harness, "status": status }
                })
            }
            MockEvent::AgentMessage { role, content } => {
                serde_json::json!({
                    "type": "event",
                    "event": "agent_response",
                    "data": { "role": role, "content": content }
                })
            }
            MockEvent::StateChanged { path, value } => {
                serde_json::json!({
                    "type": "event",
                    "event": "state_changed",
                    "data": { "path": path, "value": value }
                })
            }
            MockEvent::Error { message } => {
                serde_json::json!({
                    "type": "event",
                    "event": "error",
                    "data": { "message": message }
                })
            }
        }
    }
}

/// Test harness server
pub struct TestHarness {
    config: TestConfig,
    /// Broadcast channel for events
    event_tx: broadcast::Sender<MockEvent>,
    /// Shutdown signal
    shutdown_tx: Option<mpsc::Sender<()>>,
    /// Server state
    state: Arc<RwLock<HarnessState>>,
}

#[derive(Debug, Default)]
struct HarnessState {
    /// Connected clients
    client_count: usize,
    /// Harness statuses
    harnesses: std::collections::HashMap<String, String>,
    /// Running flag
    running: bool,
}

impl TestHarness {
    /// Create a new test harness with default config
    pub fn new() -> Self {
        Self::with_config(TestConfig::default())
    }

    /// Create a new test harness with custom config
    pub fn with_config(config: TestConfig) -> Self {
        let (event_tx, _) = broadcast::channel(100);
        
        // Initialize default harness states
        let mut harnesses = std::collections::HashMap::new();
        harnesses.insert("cursor".to_string(), "stopped".to_string());
        harnesses.insert("android-studio".to_string(), "stopped".to_string());
        harnesses.insert("godot".to_string(), "stopped".to_string());
        
        Self {
            config,
            event_tx,
            shutdown_tx: None,
            state: Arc::new(RwLock::new(HarnessState {
                harnesses,
                ..Default::default()
            })),
        }
    }

    /// Start the test harness server
    pub async fn start(&mut self) -> Result<()> {
        let (shutdown_tx, mut shutdown_rx) = mpsc::channel::<()>(1);
        self.shutdown_tx = Some(shutdown_tx);
        
        let state = self.state.clone();
        state.write().await.running = true;
        
        let config = self.config.clone();
        let event_tx = self.event_tx.clone();
        
        if config.use_unix_socket {
            // Remove old socket if exists
            let _ = std::fs::remove_file(&config.socket_path);
            
            let listener = UnixListener::bind(&config.socket_path)?;
            info!("Test harness listening on {:?}", config.socket_path);
            
            // Spawn listener task
            let state_clone = state.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        result = listener.accept() => {
                            match result {
                                Ok((stream, _)) => {
                                    let state = state_clone.clone();
                                    let event_rx = event_tx.subscribe();
                                    tokio::spawn(handle_unix_client(stream, state, event_rx));
                                }
                                Err(e) => {
                                    error!("Accept error: {}", e);
                                }
                            }
                        }
                        _ = shutdown_rx.recv() => {
                            info!("Shutting down test harness");
                            break;
                        }
                    }
                }
            });
        } else {
            let addr: SocketAddr = format!("127.0.0.1:{}", config.tcp_port).parse()?;
            let listener = TcpListener::bind(addr).await?;
            info!("Test harness listening on {}", addr);
            
            let state_clone = state.clone();
            tokio::spawn(async move {
                loop {
                    tokio::select! {
                        result = listener.accept() => {
                            match result {
                                Ok((stream, addr)) => {
                                    info!("New connection from {}", addr);
                                    let state = state_clone.clone();
                                    let event_rx = event_tx.subscribe();
                                    tokio::spawn(handle_tcp_client(stream, state, event_rx));
                                }
                                Err(e) => {
                                    error!("Accept error: {}", e);
                                }
                            }
                        }
                        _ = shutdown_rx.recv() => {
                            info!("Shutting down test harness");
                            break;
                        }
                    }
                }
            });
        }
        
        // Start auto-events if enabled
        if self.config.auto_events {
            self.start_auto_events().await;
        }
        
        Ok(())
    }

    /// Stop the test harness
    pub async fn stop(&mut self) -> Result<()> {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(()).await;
        }
        self.state.write().await.running = false;
        
        // Clean up socket
        if self.config.use_unix_socket {
            let _ = std::fs::remove_file(&self.config.socket_path);
        }
        
        Ok(())
    }

    /// Send a mock event to all connected clients
    pub fn send_event(&self, event: MockEvent) {
        let _ = self.event_tx.send(event);
    }

    /// Start automatic mock events
    async fn start_auto_events(&self) {
        let event_tx = self.event_tx.clone();
        let interval = self.config.event_interval_ms;
        let state = self.state.clone();
        
        tokio::spawn(async move {
            let mut counter = 0u64;
            loop {
                tokio::time::sleep(tokio::time::Duration::from_millis(interval)).await;
                
                if !state.read().await.running {
                    break;
                }
                
                counter += 1;
                
                // Cycle through different event types
                let event = match counter % 4 {
                    0 => MockEvent::HarnessStatus {
                        harness: "cursor".to_string(),
                        status: if counter % 8 == 0 { "running" } else { "idle" }.to_string(),
                    },
                    1 => MockEvent::AgentMessage {
                        role: "assistant".to_string(),
                        content: format!("Auto-message #{}: System operational", counter),
                    },
                    2 => MockEvent::HarnessStatus {
                        harness: "android-studio".to_string(),
                        status: if counter % 6 == 0 { "building" } else { "stopped" }.to_string(),
                    },
                    _ => MockEvent::StateChanged {
                        path: vec!["system".to_string(), "load".to_string()],
                        value: serde_json::json!((counter % 100) as f64 / 100.0),
                    },
                };
                
                let _ = event_tx.send(event);
            }
        });
    }

    /// Get harness status
    pub async fn get_harness_status(&self, harness: &str) -> Option<String> {
        self.state.read().await.harnesses.get(harness).cloned()
    }

    /// Set harness status (and broadcast event)
    pub async fn set_harness_status(&self, harness: &str, status: &str) {
        self.state
            .write()
            .await
            .harnesses
            .insert(harness.to_string(), status.to_string());
        
        self.send_event(MockEvent::HarnessStatus {
            harness: harness.to_string(),
            status: status.to_string(),
        });
    }
}

/// Handle a Unix socket client connection
async fn handle_unix_client(
    mut stream: tokio::net::UnixStream,
    state: Arc<RwLock<HarnessState>>,
    mut event_rx: broadcast::Receiver<MockEvent>,
) {
    state.write().await.client_count += 1;
    info!("Unix client connected (total: {})", state.read().await.client_count);
    
    let (mut read_half, mut write_half) = stream.into_split();
    
    // Spawn event writer
    let write_state = state.clone();
    tokio::spawn(async move {
        while let Ok(event) = event_rx.recv().await {
            let json = event.to_json();
            let payload = serde_json::to_vec(&json).unwrap_or_default();
            let len = payload.len() as u32;
            
            if write_half.write_all(&len.to_be_bytes()).await.is_err() {
                break;
            }
            if write_half.write_all(&payload).await.is_err() {
                break;
            }
        }
        write_state.write().await.client_count = write_state.read().await.client_count.saturating_sub(1);
    });
    
    // Read commands
    loop {
        let mut len_buf = [0u8; 4];
        if read_half.read_exact(&mut len_buf).await.is_err() {
            break;
        }
        
        let len = u32::from_be_bytes(len_buf) as usize;
        let mut payload = vec![0u8; len];
        
        if read_half.read_exact(&mut payload).await.is_err() {
            break;
        }
        
        // Parse and handle command
        if let Ok(json) = serde_json::from_slice::<serde_json::Value>(&payload) {
            debug!("Received command: {:?}", json);
            // Handle commands (echo for now)
        }
    }
}

/// Handle a TCP client connection
async fn handle_tcp_client(
    stream: TcpStream,
    state: Arc<RwLock<HarnessState>>,
    mut event_rx: broadcast::Receiver<MockEvent>,
) {
    state.write().await.client_count += 1;
    info!("TCP client connected (total: {})", state.read().await.client_count);
    
    let (mut read_half, mut write_half) = stream.into_split();
    
    // Spawn event writer
    let write_state = state.clone();
    tokio::spawn(async move {
        while let Ok(event) = event_rx.recv().await {
            let json = event.to_json();
            let payload = serde_json::to_vec(&json).unwrap_or_default();
            let len = payload.len() as u32;
            
            if write_half.write_all(&len.to_be_bytes()).await.is_err() {
                break;
            }
            if write_half.write_all(&payload).await.is_err() {
                break;
            }
        }
        write_state.write().await.client_count = write_state.read().await.client_count.saturating_sub(1);
    });
    
    // Read commands (same as Unix handler)
    loop {
        let mut len_buf = [0u8; 4];
        if read_half.read_exact(&mut len_buf).await.is_err() {
            break;
        }
        
        let len = u32::from_be_bytes(len_buf) as usize;
        let mut payload = vec![0u8; len];
        
        if read_half.read_exact(&mut payload).await.is_err() {
            break;
        }
        
        if let Ok(json) = serde_json::from_slice::<serde_json::Value>(&payload) {
            debug!("Received command: {:?}", json);
        }
    }
}

/// Run standalone test harness (for testing the UI)
pub async fn run_standalone() -> Result<()> {
    info!("Starting standalone test harness...");
    
    let mut harness = TestHarness::new();
    harness.start().await?;
    
    // Wait for Ctrl+C
    tokio::signal::ctrl_c().await?;
    
    harness.stop().await?;
    info!("Test harness stopped");
    
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[tokio::test]
    async fn test_harness_starts() {
        let mut harness = TestHarness::with_config(TestConfig {
            use_unix_socket: false,
            tcp_port: 19999,
            auto_events: false,
            ..Default::default()
        });
        
        assert!(harness.start().await.is_ok());
        assert!(harness.stop().await.is_ok());
    }
}

