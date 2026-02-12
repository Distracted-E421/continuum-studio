//! Dialog Client for Synapsix Dialog Daemon Integration
//!
//! Connects to the Synapsix dialog daemon via D-Bus and forwards dialog
//! requests to be rendered in the Continuum Studio panel instead of
//! the separate daemon window.

use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use zbus::{Connection, Proxy};

/// A dialog request received from the daemon
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DialogRequest {
    pub id: String,
    pub title: String,
    pub prompt: String,
    pub dialog_type: DialogType,
    pub timeout_ms: Option<u32>,
}

/// Types of dialogs we can render
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum DialogType {
    Choice {
        options: Vec<ChoiceOption>,
        default: Option<String>,
        allow_multiple: bool,
    },
    Confirmation {
        yes_label: String,
        no_label: String,
        default_yes: bool,
    },
    TextInput {
        placeholder: String,
        default: Option<String>,
        multiline: bool,
    },
    Slider {
        min: f64,
        max: f64,
        step: f64,
        default: f64,
        unit: Option<String>,
    },
    Progress {
        progress: Option<f64>,
    },
    Toast {
        message: String,
        level: String,
    },
}

/// A choice option for multiple-choice dialogs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChoiceOption {
    pub value: String,
    pub label: String,
    #[serde(default)]
    pub description: Option<String>,
}

/// Messages from the dialog client to the main app
#[derive(Debug, Clone)]
pub enum DialogClientMessage {
    /// Connection status changed
    Connected(bool),
    /// New dialog request received (to display in panel)
    DialogReceived(DialogRequest),
    /// Dialog was dismissed/completed
    DialogDismissed(String),
    /// Hold mode state changed
    HoldModeChanged(bool),
    /// Error occurred
    Error(String),
}

/// Response to send back for a dialog
#[derive(Debug, Clone, Serialize)]
pub struct DialogResponse {
    pub id: String,
    pub selection: serde_json::Value,
    pub cancelled: bool,
    pub comment: Option<String>,
}

/// D-Bus proxy for the dialog daemon
pub struct DialogClient {
    connection: Option<Connection>,
}

impl DialogClient {
    pub fn new() -> Self {
        Self { connection: None }
    }

    /// Check if the dialog daemon is running
    pub async fn check_daemon_available() -> bool {
        match Connection::session().await {
            Ok(conn) => {
                // Try to call ping
                let proxy = Proxy::new(
                    &conn,
                    "sh.synapsix.Dialog",
                    "/sh/synapsix/Dialog",
                    "sh.synapsix.Dialog1",
                )
                .await;

                match proxy {
                    Ok(p) => {
                        let result: Result<String, _> = p.call("Ping", &()).await;
                        result.is_ok()
                    }
                    Err(_) => false,
                }
            }
            Err(_) => false,
        }
    }

    /// Connect to the dialog daemon
    pub async fn connect(&mut self) -> Result<(), String> {
        let conn = Connection::session()
            .await
            .map_err(|e| format!("Failed to connect to D-Bus session: {}", e))?;

        self.connection = Some(conn);
        log::info!("Connected to D-Bus session for dialog integration");
        Ok(())
    }

    /// Check if connected
    pub fn is_connected(&self) -> bool {
        self.connection.is_some()
    }

    /// Get current hold mode state
    pub async fn get_hold_mode(&self) -> Result<bool, String> {
        let conn = self
            .connection
            .as_ref()
            .ok_or_else(|| "Not connected".to_string())?;

        let proxy = Proxy::new(
            conn,
            "sh.synapsix.Dialog",
            "/sh/synapsix/Dialog",
            "sh.synapsix.Dialog1",
        )
        .await
        .map_err(|e| format!("Failed to create proxy: {}", e))?;

        let result: String = proxy
            .call("GetHoldMode", &())
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        Ok(result == "true")
    }

    /// Set hold mode
    pub async fn set_hold_mode(&self, enabled: bool) -> Result<bool, String> {
        let conn = self
            .connection
            .as_ref()
            .ok_or_else(|| "Not connected".to_string())?;

        let proxy = Proxy::new(
            conn,
            "sh.synapsix.Dialog",
            "/sh/synapsix/Dialog",
            "sh.synapsix.Dialog1",
        )
        .await
        .map_err(|e| format!("Failed to create proxy: {}", e))?;

        let result: String = proxy
            .call("SetHoldMode", &(enabled,))
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        Ok(result == "true")
    }

    /// Toggle hold mode
    pub async fn toggle_hold_mode(&self) -> Result<bool, String> {
        let conn = self
            .connection
            .as_ref()
            .ok_or_else(|| "Not connected".to_string())?;

        let proxy = Proxy::new(
            conn,
            "sh.synapsix.Dialog",
            "/sh/synapsix/Dialog",
            "sh.synapsix.Dialog1",
        )
        .await
        .map_err(|e| format!("Failed to create proxy: {}", e))?;

        let result: String = proxy
            .call("ToggleHoldMode", &())
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        Ok(result == "true")
    }

    /// Get daemon info
    pub async fn get_info(&self) -> Result<serde_json::Value, String> {
        let conn = self
            .connection
            .as_ref()
            .ok_or_else(|| "Not connected".to_string())?;

        let proxy = Proxy::new(
            conn,
            "sh.synapsix.Dialog",
            "/sh/synapsix/Dialog",
            "sh.synapsix.Dialog1",
        )
        .await
        .map_err(|e| format!("Failed to create proxy: {}", e))?;

        let result: String = proxy
            .call("GetInfo", &())
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        serde_json::from_str(&result).map_err(|e| format!("Failed to parse info: {}", e))
    }

    /// Ping the daemon
    pub async fn ping(&self) -> Result<bool, String> {
        let conn = self
            .connection
            .as_ref()
            .ok_or_else(|| "Not connected".to_string())?;

        let proxy = Proxy::new(
            conn,
            "sh.synapsix.Dialog",
            "/sh/synapsix/Dialog",
            "sh.synapsix.Dialog1",
        )
        .await
        .map_err(|e| format!("Failed to create proxy: {}", e))?;

        let result: Result<String, _> = proxy.call("Ping", &()).await;
        Ok(result.map(|s| s == "pong").unwrap_or(false))
    }
}

impl Default for DialogClient {
    fn default() -> Self {
        Self::new()
    }
}

/// Spawn a subscription that monitors the dialog daemon
/// Returns a channel receiver for dialog events
pub fn spawn_dialog_monitor() -> mpsc::Receiver<DialogClientMessage> {
    let (tx, rx) = mpsc::channel(32);

    tokio::spawn(async move {
        let mut client = DialogClient::new();
        let mut was_connected = false;

        loop {
            // Check if daemon is available
            let available = DialogClient::check_daemon_available().await;

            if available && !was_connected {
                // Just connected
                if client.connect().await.is_ok() {
                    was_connected = true;
                    let _ = tx.send(DialogClientMessage::Connected(true)).await;

                    // Get initial hold mode state
                    if let Ok(hold_mode) = client.get_hold_mode().await {
                        let _ = tx.send(DialogClientMessage::HoldModeChanged(hold_mode)).await;
                    }
                }
            } else if !available && was_connected {
                // Lost connection
                was_connected = false;
                let _ = tx.send(DialogClientMessage::Connected(false)).await;
            }

            // Poll every 5 seconds
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    });

    rx
}
