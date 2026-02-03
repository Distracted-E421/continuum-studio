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
    
    /// Window size (width, height)
    #[serde(default = "default_window_size")]
    pub window_size: (u32, u32),
    
    /// Auto-update settings
    #[serde(default)]
    pub updates: UpdateSettings,
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
            window_size: default_window_size(),
            updates: UpdateSettings::default(),
        }
    }
}

impl Settings {
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
