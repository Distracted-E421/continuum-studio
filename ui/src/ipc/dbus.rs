//! D-Bus Client for Dialog Daemon Integration
//!
//! Connects to the Continuum Dialog Daemon (sh.continuum.studio.Dialog)
//! for showing interactive dialogs to the user.
//!
//! Note: Currently uses command-line invocation of `continuum-dialog-cli`
//! for simplicity and compatibility. Direct D-Bus integration may be
//! added in a future version.

use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::process::Command;
use tracing::{debug, error, info, warn};

/// D-Bus connection to dialog daemon (via CLI)
pub struct DialogClient {
    connected: bool,
}

impl DialogClient {
    /// Create a new dialog client
    pub fn new() -> Self {
        Self { connected: false }
    }

    /// Check if connected to daemon
    pub fn is_connected(&self) -> bool {
        self.connected
    }

    /// Test connection to the dialog daemon
    pub fn connect(&mut self) -> Result<()> {
        match Command::new("continuum-dialog-cli").arg("ping").output() {
            Ok(output) if output.status.success() => {
                let response = String::from_utf8_lossy(&output.stdout);
                if response.trim() == "pong" {
                    info!("Connected to dialog daemon");
                    self.connected = true;
                    Ok(())
                } else {
                    Err(anyhow::anyhow!("Unexpected ping response: {}", response))
                }
            }
            Ok(output) => {
                let stderr = String::from_utf8_lossy(&output.stderr);
                error!("Dialog daemon ping failed: {}", stderr);
                Err(anyhow::anyhow!("Ping failed: {}", stderr))
            }
            Err(e) => {
                error!("Failed to execute continuum-dialog-cli: {}", e);
                Err(e.into())
            }
        }
    }

    /// Try to reconnect if disconnected
    pub fn ensure_connected(&mut self) -> bool {
        if self.connected {
            return true;
        }
        match self.connect() {
            Ok(()) => true,
            Err(e) => {
                debug!("Connection attempt failed: {}", e);
                false
            }
        }
    }

    /// Show a choice dialog
    pub fn show_choice(
        &self,
        title: &str,
        prompt: &str,
        options: &[ChoiceOption],
        default: Option<&str>,
    ) -> Result<DialogResponse> {
        if !self.connected {
            return Err(anyhow::anyhow!("Not connected"));
        }

        let options_json = serde_json::to_string(options)?;

        let mut cmd = Command::new("continuum-dialog-cli");
        cmd.arg("choice")
            .arg("--title").arg(title)
            .arg("--prompt").arg(prompt)
            .arg("--options").arg(&options_json);

        if let Some(def) = default {
            cmd.arg("--default").arg(def);
        }

        let output = cmd.output()?;
        if output.status.success() {
            let result = String::from_utf8_lossy(&output.stdout);
            Ok(serde_json::from_str(&result)?)
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(anyhow::anyhow!("Choice dialog failed: {}", stderr))
        }
    }

    /// Show a confirmation dialog
    pub fn show_confirm(
        &self,
        title: &str,
        prompt: &str,
        yes_label: Option<&str>,
        no_label: Option<&str>,
    ) -> Result<DialogResponse> {
        if !self.connected {
            return Err(anyhow::anyhow!("Not connected"));
        }

        let mut cmd = Command::new("continuum-dialog-cli");
        cmd.arg("confirm")
            .arg("--title").arg(title)
            .arg("--prompt").arg(prompt);

        if let Some(yes) = yes_label {
            cmd.arg("--yes").arg(yes);
        }
        if let Some(no) = no_label {
            cmd.arg("--no").arg(no);
        }

        let output = cmd.output()?;
        if output.status.success() {
            let result = String::from_utf8_lossy(&output.stdout);
            Ok(serde_json::from_str(&result)?)
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(anyhow::anyhow!("Confirm dialog failed: {}", stderr))
        }
    }

    /// Show a text input dialog
    pub fn show_text(
        &self,
        title: &str,
        prompt: &str,
        placeholder: &str,
        default: Option<&str>,
    ) -> Result<DialogResponse> {
        if !self.connected {
            return Err(anyhow::anyhow!("Not connected"));
        }

        let mut cmd = Command::new("continuum-dialog-cli");
        cmd.arg("text")
            .arg("--title").arg(title)
            .arg("--prompt").arg(prompt)
            .arg("--placeholder").arg(placeholder);

        if let Some(def) = default {
            cmd.arg("--default").arg(def);
        }

        let output = cmd.output()?;
        if output.status.success() {
            let result = String::from_utf8_lossy(&output.stdout);
            Ok(serde_json::from_str(&result)?)
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(anyhow::anyhow!("Text dialog failed: {}", stderr))
        }
    }

    /// Show a toast notification (fire and forget)
    pub fn show_toast(&self, message: &str, level: ToastLevel) -> Result<()> {
        if !self.connected {
            warn!("Dialog daemon not connected, skipping toast");
            return Ok(());
        }

        let level_str = match level {
            ToastLevel::Info => "info",
            ToastLevel::Success => "success",
            ToastLevel::Warning => "warning",
            ToastLevel::Error => "error",
        };

        let output = Command::new("continuum-dialog-cli")
            .arg("toast")
            .arg("--message").arg(message)
            .arg("--level").arg(level_str)
            .output()?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            warn!("Toast notification failed: {}", stderr);
        }

        Ok(())
    }
}

impl Default for DialogClient {
    fn default() -> Self {
        Self::new()
    }
}

/// Choice option for dialogs
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChoiceOption {
    pub value: String,
    pub label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
}

impl ChoiceOption {
    pub fn new(value: &str, label: &str) -> Self {
        Self {
            value: value.to_string(),
            label: label.to_string(),
            description: None,
        }
    }

    pub fn with_description(mut self, desc: &str) -> Self {
        self.description = Some(desc.to_string());
        self
    }
}

/// Toast notification level
#[derive(Debug, Clone, Copy)]
pub enum ToastLevel {
    Info,
    Success,
    Warning,
    Error,
}

/// Dialog response from daemon
#[derive(Debug, Clone, Deserialize)]
pub struct DialogResponse {
    pub id: String,
    pub selection: serde_json::Value,
    pub cancelled: bool,
    #[serde(default)]
    pub comment: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
    pub timestamp: u64,
}

impl DialogResponse {
    /// Check if the dialog was cancelled
    pub fn is_cancelled(&self) -> bool {
        self.cancelled
    }

    /// Get selection as string
    pub fn as_str(&self) -> Option<&str> {
        self.selection.as_str()
    }

    /// Get selection as bool
    pub fn as_bool(&self) -> Option<bool> {
        self.selection.as_bool()
    }

    /// Get selection as f64
    pub fn as_f64(&self) -> Option<f64> {
        self.selection.as_f64()
    }
}

/// Daemon info response
#[derive(Debug, Clone, Deserialize)]
pub struct DaemonInfo {
    pub version: String,
    pub capabilities: Vec<String>,
    pub platform: String,
}

