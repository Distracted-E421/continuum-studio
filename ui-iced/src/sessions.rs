//! Session tracking for Cursor instances
//!
//! This module provides functionality to:
//! - Detect running Cursor processes
//! - Track session information (version, workspace, PID)
//! - Manage multiple concurrent sessions
//! - Apply settings across sessions

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Command;

/// Information about a running Cursor session
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CursorSession {
    /// Process ID
    pub pid: u32,
    /// Cursor version (if detectable)
    pub version: Option<String>,
    /// Workspace/folder path (if detectable)
    pub workspace: Option<String>,
    /// Data directory for this session
    pub data_dir: Option<String>,
    /// Whether the session is active (has focus)
    pub active: bool,
    /// Start time (if available)
    pub started_at: Option<String>,
    /// Window title (if available)
    pub window_title: Option<String>,
    /// Command line arguments
    pub cmdline: String,
}

/// Session tracker that monitors running Cursor instances
pub struct SessionTracker {
    /// Known sessions by PID
    sessions: HashMap<u32, CursorSession>,
}

impl Default for SessionTracker {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionTracker {
    pub fn new() -> Self {
        Self {
            sessions: HashMap::new(),
        }
    }

    /// Scan for running Cursor processes
    pub fn scan_processes(&mut self) -> Vec<CursorSession> {
        let mut found_sessions = Vec::new();

        // Try to find Cursor processes using pgrep
        if let Ok(output) = Command::new("pgrep")
            .args(["-f", "cursor"])
            .output()
        {
            if output.status.success() {
                let pids = String::from_utf8_lossy(&output.stdout);
                for pid_str in pids.lines() {
                    if let Ok(pid) = pid_str.trim().parse::<u32>() {
                        if let Some(session) = self.get_session_info(pid) {
                            found_sessions.push(session.clone());
                            self.sessions.insert(pid, session);
                        }
                    }
                }
            }
        }

        // Clean up dead sessions
        let live_pids: Vec<u32> = found_sessions.iter().map(|s| s.pid).collect();
        self.sessions.retain(|pid, _| live_pids.contains(pid));

        found_sessions
    }

    /// Get information about a specific process
    fn get_session_info(&self, pid: u32) -> Option<CursorSession> {
        // Read /proc/PID/cmdline to get command line
        let cmdline_path = format!("/proc/{}/cmdline", pid);
        let cmdline = std::fs::read_to_string(&cmdline_path)
            .map(|s| s.replace('\0', " ").trim().to_string())
            .unwrap_or_default();

        // Skip if not actually a Cursor process
        if !cmdline.to_lowercase().contains("cursor") {
            return None;
        }

        // Try to extract version from cmdline
        let version = self.extract_version_from_cmdline(&cmdline);

        // Try to extract workspace from cmdline
        let workspace = self.extract_workspace_from_cmdline(&cmdline);

        // Try to extract data directory
        let data_dir = self.extract_data_dir_from_cmdline(&cmdline);

        // Get start time from /proc/PID/stat
        let started_at = self.get_process_start_time(pid);

        // Get window title using wmctrl or xdotool (if available)
        let window_title = self.get_window_title(pid);

        Some(CursorSession {
            pid,
            version,
            workspace,
            data_dir,
            active: false, // Would need window manager integration to detect
            started_at,
            window_title,
            cmdline,
        })
    }

    /// Extract Cursor version from command line
    fn extract_version_from_cmdline(&self, cmdline: &str) -> Option<String> {
        // Look for patterns like:
        // - cursor-2.4.21
        // - Cursor-2.4.21-x86_64.AppImage
        // - /nix/store/.../cursor-2.4.21/bin/cursor
        
        let patterns = [
            r"cursor-(\d+\.\d+\.\d+)",
            r"Cursor-(\d+\.\d+\.\d+)",
            r"\.cursor-(\d+\.\d+\.\d+)",
        ];

        for pattern in patterns {
            if let Ok(re) = regex::Regex::new(pattern) {
                if let Some(caps) = re.captures(cmdline) {
                    if let Some(version) = caps.get(1) {
                        return Some(version.as_str().to_string());
                    }
                }
            }
        }

        // Fallback: check for version in nix store path
        if cmdline.contains("/nix/store/") {
            // Extract from path like /nix/store/xxx-cursor-2.4.21/bin/cursor
            if let Some(start) = cmdline.find("-cursor-") {
                let rest = &cmdline[start + 8..];
                if let Some(end) = rest.find(['/', '-', ' ']) {
                    return Some(rest[..end].to_string());
                }
            }
        }

        None
    }

    /// Extract workspace path from command line arguments
    fn extract_workspace_from_cmdline(&self, cmdline: &str) -> Option<String> {
        // Common patterns:
        // - Last argument is often the workspace path
        // - --folder=/path/to/workspace
        // - path argument without flags

        let parts: Vec<&str> = cmdline.split_whitespace().collect();
        
        // Look for explicit folder argument
        for (i, part) in parts.iter().enumerate() {
            if *part == "--folder" || *part == "-f" {
                if let Some(path) = parts.get(i + 1) {
                    if path.starts_with('/') {
                        return Some(path.to_string());
                    }
                }
            }
            if part.starts_with("--folder=") {
                return Some(part[9..].to_string());
            }
        }

        // Check last argument (often the workspace)
        if let Some(last) = parts.last() {
            if last.starts_with('/') && !last.starts_with("/nix") && PathBuf::from(last).is_dir() {
                return Some(last.to_string());
            }
        }

        None
    }

    /// Extract data directory from command line
    fn extract_data_dir_from_cmdline(&self, cmdline: &str) -> Option<String> {
        // Look for --user-data-dir argument
        let parts: Vec<&str> = cmdline.split_whitespace().collect();
        
        for (i, part) in parts.iter().enumerate() {
            if *part == "--user-data-dir" {
                if let Some(path) = parts.get(i + 1) {
                    return Some(path.to_string());
                }
            }
            if part.starts_with("--user-data-dir=") {
                return Some(part[16..].to_string());
            }
        }

        None
    }

    /// Get process start time from /proc
    fn get_process_start_time(&self, pid: u32) -> Option<String> {
        // Read /proc/PID/stat and parse start time
        let stat_path = format!("/proc/{}/stat", pid);
        if let Ok(stat) = std::fs::read_to_string(&stat_path) {
            // The 22nd field (index 21) is starttime in clock ticks
            let fields: Vec<&str> = stat.split_whitespace().collect();
            if fields.len() > 21 {
                if let Ok(start_ticks) = fields[21].parse::<u64>() {
                    // Convert to human-readable time (approximation)
                    // This requires knowing the boot time, which is more complex
                    // For now, just return the relative value
                    return Some(format!("started {} ticks ago", start_ticks));
                }
            }
        }
        None
    }

    /// Get window title for a process (requires X11/Wayland tools)
    fn get_window_title(&self, _pid: u32) -> Option<String> {
        // This would require wmctrl, xdotool, or Wayland-specific tools
        // For now, return None - can be implemented later
        None
    }

    /// Get all currently tracked sessions
    pub fn get_sessions(&self) -> Vec<&CursorSession> {
        self.sessions.values().collect()
    }

    /// Get session by PID
    pub fn get_session(&self, pid: u32) -> Option<&CursorSession> {
        self.sessions.get(&pid)
    }

    /// Check if a version is currently running
    pub fn is_version_running(&self, version: &str) -> bool {
        self.sessions.values().any(|s| {
            s.version.as_ref().map(|v| v == version).unwrap_or(false)
        })
    }

    /// Get sessions for a specific workspace
    pub fn get_sessions_for_workspace(&self, workspace_path: &str) -> Vec<&CursorSession> {
        self.sessions.values()
            .filter(|s| {
                s.workspace.as_ref().map(|w| w == workspace_path).unwrap_or(false)
            })
            .collect()
    }
}

/// Settings that can be shared across Cursor instances
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SharedCursorSettings {
    /// Theme preference
    pub theme: Option<String>,
    /// Font family
    pub font_family: Option<String>,
    /// Font size
    pub font_size: Option<u32>,
    /// Tab size
    pub tab_size: Option<u32>,
    /// Auto-save setting
    pub auto_save: Option<String>,
    /// Format on save
    pub format_on_save: Option<bool>,
    /// Extensions to install
    pub extensions: Vec<String>,
    /// Custom keybindings file path
    pub keybindings_path: Option<PathBuf>,
    /// Custom settings.json overrides
    pub settings_overrides: HashMap<String, serde_json::Value>,
}

impl SharedCursorSettings {
    /// Apply these settings to a Cursor data directory
    pub fn apply_to_data_dir(&self, data_dir: &PathBuf) -> Result<(), String> {
        let settings_path = data_dir.join("User").join("settings.json");
        
        // Read existing settings if they exist
        let mut settings: serde_json::Value = if settings_path.exists() {
            let content = std::fs::read_to_string(&settings_path)
                .map_err(|e| format!("Failed to read settings: {}", e))?;
            serde_json::from_str(&content)
                .map_err(|e| format!("Failed to parse settings: {}", e))?
        } else {
            serde_json::json!({})
        };

        // Apply our shared settings
        if let Some(ref theme) = self.theme {
            settings["workbench.colorTheme"] = serde_json::json!(theme);
        }
        if let Some(ref font) = self.font_family {
            settings["editor.fontFamily"] = serde_json::json!(font);
        }
        if let Some(size) = self.font_size {
            settings["editor.fontSize"] = serde_json::json!(size);
        }
        if let Some(size) = self.tab_size {
            settings["editor.tabSize"] = serde_json::json!(size);
        }
        if let Some(ref auto_save) = self.auto_save {
            settings["files.autoSave"] = serde_json::json!(auto_save);
        }
        if let Some(format) = self.format_on_save {
            settings["editor.formatOnSave"] = serde_json::json!(format);
        }

        // Apply custom overrides
        for (key, value) in &self.settings_overrides {
            settings[key] = value.clone();
        }

        // Ensure User directory exists
        if let Some(parent) = settings_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create settings directory: {}", e))?;
        }

        // Write settings
        let content = serde_json::to_string_pretty(&settings)
            .map_err(|e| format!("Failed to serialize settings: {}", e))?;
        std::fs::write(&settings_path, content)
            .map_err(|e| format!("Failed to write settings: {}", e))?;

        Ok(())
    }

    /// Load from Continuum Studio config
    pub fn load() -> Self {
        let config_path = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("continuum-studio")
            .join("shared-cursor-settings.json");

        if config_path.exists() {
            if let Ok(content) = std::fs::read_to_string(&config_path) {
                if let Ok(settings) = serde_json::from_str(&content) {
                    return settings;
                }
            }
        }

        Self::default()
    }

    /// Save to Continuum Studio config
    pub fn save(&self) -> Result<(), String> {
        let config_path = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."))
            .join("continuum-studio")
            .join("shared-cursor-settings.json");

        if let Some(parent) = config_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create config directory: {}", e))?;
        }

        let content = serde_json::to_string_pretty(self)
            .map_err(|e| format!("Failed to serialize shared settings: {}", e))?;
        std::fs::write(&config_path, content)
            .map_err(|e| format!("Failed to write shared settings: {}", e))?;

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_extraction() {
        let tracker = SessionTracker::new();
        
        // Test nix store path
        let cmdline = "/nix/store/abc-cursor-2.4.21/bin/cursor --some-arg";
        assert_eq!(tracker.extract_version_from_cmdline(cmdline), Some("2.4.21".to_string()));

        // Test AppImage path
        let cmdline = "/tmp/Cursor-2.4.21-x86_64.AppImage";
        assert_eq!(tracker.extract_version_from_cmdline(cmdline), Some("2.4.21".to_string()));

        // Test cursor-version binary
        let cmdline = "/usr/bin/cursor-2.0.77 /home/user/project";
        assert_eq!(tracker.extract_version_from_cmdline(cmdline), Some("2.0.77".to_string()));
    }

    #[test]
    fn test_workspace_extraction() {
        let tracker = SessionTracker::new();
        
        // Test --folder argument
        let cmdline = "cursor --folder /home/user/project";
        assert_eq!(
            tracker.extract_workspace_from_cmdline(cmdline),
            Some("/home/user/project".to_string())
        );

        // Test --folder= format
        let cmdline = "cursor --folder=/home/user/project";
        assert_eq!(
            tracker.extract_workspace_from_cmdline(cmdline),
            Some("/home/user/project".to_string())
        );
    }
}
