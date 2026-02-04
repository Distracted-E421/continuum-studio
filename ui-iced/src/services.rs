//! Service management for Continuum Studio
//!
//! Handles starting, stopping, and monitoring external services:
//! - Elixir Core (studio_core) - Main backend
//! - synapsix-dialog-daemon - Interactive dialog system
//! - Other supporting services

use std::path::PathBuf;
use std::process::{Command, Stdio};
use tokio::process::Command as AsyncCommand;

/// Service configuration
pub struct ServiceConfig {
    /// Path to the studio_core Elixir project
    pub core_path: PathBuf,
    /// Socket path for Core connection
    pub core_socket: PathBuf,
    /// Path to synapsix-dialog-daemon binary
    pub dialog_daemon_path: Option<PathBuf>,
    /// Web port for dialog daemon
    pub dialog_web_port: u16,
}

impl Default for ServiceConfig {
    fn default() -> Self {
        Self {
            core_path: PathBuf::from("/home/e421/continuum-studio/core/studio_core"),
            core_socket: PathBuf::from("/tmp/continuum-studio.sock"),
            dialog_daemon_path: None, // Will search PATH
            dialog_web_port: 8080,
        }
    }
}

/// Service status
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ServiceStatus {
    /// Service is running
    Running,
    /// Service is stopped
    Stopped,
    /// Service status is unknown/checking
    Unknown,
    /// Service is starting
    Starting,
    /// Service failed to start
    Failed,
}

impl std::fmt::Display for ServiceStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ServiceStatus::Running => write!(f, "Running"),
            ServiceStatus::Stopped => write!(f, "Stopped"),
            ServiceStatus::Unknown => write!(f, "Unknown"),
            ServiceStatus::Starting => write!(f, "Starting..."),
            ServiceStatus::Failed => write!(f, "Failed"),
        }
    }
}

/// Information about a managed service
#[derive(Debug, Clone)]
pub struct ServiceInfo {
    pub name: String,
    pub description: String,
    pub status: ServiceStatus,
    /// Command to start the service
    pub start_command: String,
    /// Command to check if service is running
    pub check_command: String,
    /// PID if running
    pub pid: Option<u32>,
}

/// Service manager
pub struct ServiceManager {
    config: ServiceConfig,
}

impl ServiceManager {
    pub fn new(config: ServiceConfig) -> Self {
        Self { config }
    }

    /// Get command to start the Elixir Core
    pub fn core_start_command(&self) -> String {
        format!(
            "cd {} && mix run --no-halt",
            self.config.core_path.display()
        )
    }

    /// Get command to start Core in background with nohup
    pub fn core_start_command_background(&self) -> String {
        format!(
            "cd {} && nohup mix run --no-halt > /tmp/studio-core.log 2>&1 &",
            self.config.core_path.display()
        )
    }

    /// Get command for interactive Core session (for development)
    pub fn core_start_command_interactive(&self) -> String {
        format!(
            "cd {} && iex -S mix",
            self.config.core_path.display()
        )
    }

    /// Check if Core is running by checking socket
    pub fn is_core_running(&self) -> bool {
        self.config.core_socket.exists()
    }

    /// Get command to start synapsix-dialog-daemon
    pub fn dialog_start_command(&self) -> String {
        let daemon = self.config.dialog_daemon_path
            .as_ref()
            .map(|p| p.display().to_string())
            .unwrap_or_else(|| "synapsix-dialog-daemon".to_string());
        
        format!("{} --web-port {}", daemon, self.config.dialog_web_port)
    }

    /// Check if dialog daemon is running
    pub fn is_dialog_running(&self) -> bool {
        // Try to ping the dialog daemon
        match Command::new("synapsix-dialog-cli")
            .arg("ping")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
        {
            Ok(status) => status.success(),
            Err(_) => false,
        }
    }

    /// Get all service info
    pub fn get_services(&self) -> Vec<ServiceInfo> {
        vec![
            ServiceInfo {
                name: "Elixir Core".to_string(),
                description: "Main backend for version management and workspace tracking".to_string(),
                status: if self.is_core_running() {
                    ServiceStatus::Running
                } else {
                    ServiceStatus::Stopped
                },
                start_command: self.core_start_command_background(),
                check_command: format!("test -S {}", self.config.core_socket.display()),
                pid: None, // TODO: Get PID from somewhere
            },
            ServiceInfo {
                name: "Dialog Daemon".to_string(),
                description: "Synapsix interactive dialog system for AI agents".to_string(),
                status: if self.is_dialog_running() {
                    ServiceStatus::Running
                } else {
                    ServiceStatus::Stopped
                },
                start_command: self.dialog_start_command(),
                check_command: "synapsix-dialog-cli ping".to_string(),
                pid: None,
            },
        ]
    }

    /// Start the Elixir Core service
    pub async fn start_core(&self) -> Result<(), String> {
        if self.is_core_running() {
            return Ok(()); // Already running
        }

        // Remove stale socket if it exists
        if self.config.core_socket.exists() {
            let _ = std::fs::remove_file(&self.config.core_socket);
        }

        // Start Core in background
        let result = AsyncCommand::new("sh")
            .arg("-c")
            .arg(&self.core_start_command_background())
            .spawn();

        match result {
            Ok(_) => {
                // Wait a bit for the socket to appear
                for _ in 0..50 {
                    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
                    if self.config.core_socket.exists() {
                        return Ok(());
                    }
                }
                Err("Core started but socket not created within 5 seconds".to_string())
            }
            Err(e) => Err(format!("Failed to start Core: {}", e)),
        }
    }

    /// Start the dialog daemon
    pub async fn start_dialog(&self) -> Result<(), String> {
        if self.is_dialog_running() {
            return Ok(()); // Already running
        }

        let cmd = format!("{} &", self.dialog_start_command());
        let result = AsyncCommand::new("sh")
            .arg("-c")
            .arg(&cmd)
            .spawn();

        match result {
            Ok(_) => {
                // Wait for daemon to be responsive
                for _ in 0..30 {
                    tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
                    if self.is_dialog_running() {
                        return Ok(());
                    }
                }
                Err("Dialog daemon started but not responding within 3 seconds".to_string())
            }
            Err(e) => Err(format!("Failed to start dialog daemon: {}", e)),
        }
    }

    /// Start all services
    pub async fn start_all(&self) -> Vec<Result<String, String>> {
        let mut results = Vec::new();

        match self.start_core().await {
            Ok(_) => results.push(Ok("Elixir Core started".to_string())),
            Err(e) => results.push(Err(format!("Core: {}", e))),
        }

        match self.start_dialog().await {
            Ok(_) => results.push(Ok("Dialog daemon started".to_string())),
            Err(e) => results.push(Err(format!("Dialog: {}", e))),
        }

        results
    }
}

/// Start Core with auto-detection of paths
pub async fn start_core_auto() -> Result<(), String> {
    let config = ServiceConfig::default();
    let manager = ServiceManager::new(config);
    manager.start_core().await
}

/// Start dialog daemon with auto-detection
pub async fn start_dialog_auto() -> Result<(), String> {
    let config = ServiceConfig::default();
    let manager = ServiceManager::new(config);
    manager.start_dialog().await
}

/// Check all service statuses
pub fn check_services() -> Vec<ServiceInfo> {
    let config = ServiceConfig::default();
    let manager = ServiceManager::new(config);
    manager.get_services()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_core_start_command() {
        let config = ServiceConfig::default();
        let manager = ServiceManager::new(config);
        let cmd = manager.core_start_command();
        assert!(cmd.contains("mix run --no-halt"));
    }

    #[test]
    fn test_dialog_start_command() {
        let config = ServiceConfig::default();
        let manager = ServiceManager::new(config);
        let cmd = manager.dialog_start_command();
        assert!(cmd.contains("synapsix-dialog-daemon"));
        assert!(cmd.contains("--web-port 8080"));
    }
}
