//! Settings persistence for Continuum Studio
//!
//! Loads and saves application settings to a JSON file.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

use crate::updater::UpdateSettings;

/// Application settings
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Settings {
    /// UI theme preference
    #[serde(default = "default_theme")]
    pub theme: ThemePreference,
    
    /// COSMIC theme preset (when theme is Cosmic)
    #[serde(default)]
    pub cosmic_preset: CosmicPreset,
    
    /// Path to Core socket
    #[serde(default = "default_socket_path")]
    pub core_socket_path: String,
    
    /// Default Cursor version to launch
    #[serde(default)]
    pub default_cursor_version: Option<String>,
    
    /// Show notifications for new versions
    #[serde(default = "default_true")]
    pub notify_new_versions: bool,
    
    /// Auto-connect to Core on startup
    #[serde(default = "default_true")]
    pub auto_connect: bool,
    
    /// Auto-start services (Core, dialog) if not running
    #[serde(default = "default_true")]
    pub auto_start_services: bool,
    
    /// Window size (width, height)
    #[serde(default = "default_window_size")]
    pub window_size: (u32, u32),
    
    /// Auto-update settings
    #[serde(default)]
    pub updates: UpdateSettings,

    /// Route AI agent dialogs through Synapsix instead of Cursor's AskQuestion
    #[serde(default)]
    pub synapsix_dialog_routing: bool,

    /// Workspace paths where the synapsix dialog rule should be managed
    #[serde(default)]
    pub managed_workspaces: Vec<String>,
}

/// Theme preference
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum ThemePreference {
    #[default]
    System,
    Dark,
    Light,
    /// Use COSMIC-inspired theme
    Cosmic,
}

/// COSMIC theme preset selection
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum CosmicPreset {
    #[default]
    Dark,
    Light,
    PopOrange,
    WarmAmber,
    CoolBlue,
    Mint,
}

fn default_theme() -> ThemePreference {
    ThemePreference::System
}

fn default_socket_path() -> String {
    "/tmp/continuum-studio.sock".to_string()
}

fn default_true() -> bool {
    true
}

fn default_window_size() -> (u32, u32) {
    (1280, 800)
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            theme: ThemePreference::System,
            cosmic_preset: CosmicPreset::Dark,
            core_socket_path: default_socket_path(),
            default_cursor_version: None,
            notify_new_versions: true,
            auto_connect: true,
            auto_start_services: true,
            window_size: default_window_size(),
            updates: UpdateSettings::default(),
            synapsix_dialog_routing: false,
            managed_workspaces: vec![],
        }
    }
}

/// Managed cursor rule for synapsix dialog routing
const SYNAPSIX_RULE_FILENAME: &str = "synapsix-dialog-preference.mdc";

impl Settings {
    /// Apply or remove the synapsix dialog rule to all managed workspaces
    pub fn apply_dialog_routing(&self) -> Result<Vec<String>, String> {
        let rule_content = include_str!("../../scripts/synapsix-dialog-preference.mdc");
        let mut results = Vec::new();

        for workspace in &self.managed_workspaces {
            let rules_dir = PathBuf::from(workspace).join(".cursor").join("rules");
            let rule_path = rules_dir.join(SYNAPSIX_RULE_FILENAME);

            if self.synapsix_dialog_routing {
                // Create rules dir if needed
                if let Err(e) = std::fs::create_dir_all(&rules_dir) {
                    results.push(format!("{}: Failed to create rules dir: {}", workspace, e));
                    continue;
                }
                // Write the rule
                match std::fs::write(&rule_path, rule_content) {
                    Ok(()) => results.push(format!("{}: Rule installed", workspace)),
                    Err(e) => results.push(format!("{}: Failed to write rule: {}", workspace, e)),
                }
            } else {
                // Remove the rule if it exists
                if rule_path.exists() {
                    match std::fs::remove_file(&rule_path) {
                        Ok(()) => results.push(format!("{}: Rule removed", workspace)),
                        Err(e) => results.push(format!("{}: Failed to remove rule: {}", workspace, e)),
                    }
                } else {
                    results.push(format!("{}: No rule to remove", workspace));
                }
            }
        }

        Ok(results)
    }

    /// Check which workspaces have the dialog rule active
    pub fn dialog_rule_status(&self) -> Vec<(String, bool)> {
        self.managed_workspaces.iter().map(|workspace| {
            let rule_path = PathBuf::from(workspace)
                .join(".cursor")
                .join("rules")
                .join(SYNAPSIX_RULE_FILENAME);
            (workspace.clone(), rule_path.exists())
        }).collect()
    }

    /// Get the settings file path
    pub fn file_path() -> PathBuf {
        let config_dir = dirs::config_dir()
            .unwrap_or_else(|| PathBuf::from("."));
        config_dir.join("continuum-studio").join("settings.json")
    }
    
    /// Load settings from file, or return defaults
    pub fn load() -> Self {
        let path = Self::file_path();
        
        if path.exists() {
            match std::fs::read_to_string(&path) {
                Ok(content) => {
                    match serde_json::from_str(&content) {
                        Ok(settings) => {
                            log::info!("Loaded settings from {:?}", path);
                            return settings;
                        }
                        Err(e) => {
                            log::warn!("Failed to parse settings: {}, using defaults", e);
                        }
                    }
                }
                Err(e) => {
                    log::warn!("Failed to read settings file: {}, using defaults", e);
                }
            }
        } else {
            log::info!("No settings file found, using defaults");
        }
        
        Self::default()
    }
    
    /// Save settings to file
    pub fn save(&self) -> Result<(), String> {
        let path = Self::file_path();
        
        // Create parent directory if needed
        if let Some(parent) = path.parent() {
            if !parent.exists() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| format!("Failed to create config directory: {}", e))?;
            }
        }
        
        let content = serde_json::to_string_pretty(self)
            .map_err(|e| format!("Failed to serialize settings: {}", e))?;
        
        std::fs::write(&path, content)
            .map_err(|e| format!("Failed to write settings file: {}", e))?;
        
        log::info!("Saved settings to {:?}", path);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_default_settings() {
        let settings = Settings::default();
        assert_eq!(settings.theme, ThemePreference::System);
        assert!(settings.auto_connect);
    }
    
    #[test]
    fn test_serialize_deserialize() {
        let settings = Settings {
            theme: ThemePreference::Dark,
            default_cursor_version: Some("0.44.11".to_string()),
            ..Default::default()
        };
        
        let json = serde_json::to_string(&settings).unwrap();
        let loaded: Settings = serde_json::from_str(&json).unwrap();
        
        assert_eq!(loaded.theme, ThemePreference::Dark);
        assert_eq!(loaded.default_cursor_version, Some("0.44.11".to_string()));
    }
}
