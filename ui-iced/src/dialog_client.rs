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

/// Orchestrator mode for dialog handling automation
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OrchestratorMode {
    /// User handles all dialogs (default)
    #[default]
    UserActive,
    /// User delegates routine dialogs; high/critical escalate to user
    UserDelegate,
    /// Orchestrator in charge; user can claim dialogs within timeout
    Spectator,
    /// User fully AFK; orchestrator handles everything autonomously
    Autonomous,
}

impl std::str::FromStr for OrchestratorMode {
    type Err = std::convert::Infallible;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(match s {
            "user_active" => Self::UserActive,
            "user_delegate" => Self::UserDelegate,
            "spectator" => Self::Spectator,
            "autonomous" => Self::Autonomous,
            _ => Self::UserActive,
        })
    }
}

impl OrchestratorMode {

    pub fn as_str(&self) -> &'static str {
        match self {
            Self::UserActive => "user_active",
            Self::UserDelegate => "user_delegate",
            Self::Spectator => "spectator",
            Self::Autonomous => "autonomous",
        }
    }

    pub fn emoji(&self) -> &'static str {
        match self {
            Self::UserActive => "🟢",
            Self::UserDelegate => "🟡",
            Self::Spectator => "🟠",
            Self::Autonomous => "🔴",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            Self::UserActive => "User Active",
            Self::UserDelegate => "Delegated",
            Self::Spectator => "Spectator",
            Self::Autonomous => "Autonomous",
        }
    }

    /// Whether this mode allows auto-handling dialogs
    pub fn allows_auto_handle(&self) -> bool {
        matches!(self, Self::UserDelegate | Self::Spectator | Self::Autonomous)
    }
}

/// Extended orchestrator mode info
#[derive(Debug, Clone)]
pub struct OrchestratorModeInfo {
    pub mode: OrchestratorMode,
    pub since_secs: u64,
    pub inactivity_timeout_secs: u64,
    pub spectator_claim_timeout_secs: u64,
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
    /// Orchestrator mode changed
    OrchestratorModeChanged(OrchestratorMode),
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

    // ========================================================================
    // Orchestrator Mode Methods
    // ========================================================================

    /// Get current orchestrator mode
    pub async fn get_orchestrator_mode(&self) -> Result<OrchestratorMode, String> {
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
            .call("GetOrchestratorMode", &())
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        Ok(result.parse().unwrap())
    }

    /// Set orchestrator mode
    pub async fn set_orchestrator_mode(&self, mode: OrchestratorMode) -> Result<OrchestratorMode, String> {
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
            .call("SetOrchestratorMode", &(mode.as_str(),))
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        // D-Bus returns JSON: {"emoji":"🟡","new_mode":"user_delegate","old_mode":"autonomous","success":true}
        // Parse the JSON and extract new_mode
        let json: serde_json::Value = serde_json::from_str(&result)
            .map_err(|e| format!("Failed to parse mode response: {}", e))?;
        
        let new_mode = json["new_mode"]
            .as_str()
            .ok_or_else(|| "Missing new_mode in response".to_string())?;
        
        Ok(OrchestratorMode::from_str(new_mode))
    }

    /// Get detailed orchestrator mode info
    pub async fn get_orchestrator_mode_info(&self) -> Result<OrchestratorModeInfo, String> {
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
            .call("GetOrchestratorModeInfo", &())
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        let json: serde_json::Value =
            serde_json::from_str(&result).map_err(|e| format!("Failed to parse info: {}", e))?;

        Ok(OrchestratorModeInfo {
            mode: OrchestratorMode::from_str(json["mode"].as_str().unwrap_or("user_active")),
            since_secs: json["since_secs"].as_u64().unwrap_or(0),
            inactivity_timeout_secs: json["inactivity_timeout_secs"].as_u64().unwrap_or(300),
            spectator_claim_timeout_secs: json["spectator_claim_timeout_secs"].as_u64().unwrap_or(30),
        })
    }

    /// Update orchestrator configuration (timeouts)
    pub async fn set_orchestrator_config(
        &self,
        inactivity_timeout_secs: Option<u64>,
        spectator_claim_timeout_secs: Option<u64>,
    ) -> Result<(), String> {
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

        let config = serde_json::json!({
            "inactivity_timeout_secs": inactivity_timeout_secs,
            "spectator_claim_timeout_secs": spectator_claim_timeout_secs,
        });

        let _result: String = proxy
            .call("SetOrchestratorConfig", &(config.to_string(),))
            .await
            .map_err(|e| format!("D-Bus call failed: {}", e))?;

        Ok(())
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
