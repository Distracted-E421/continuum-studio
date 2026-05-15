//! Configuration management for the launcher

use crate::LauncherConfig;
use std::path::PathBuf;

const CONFIG_FILE: &str = "synapsix-launcher.json";

fn config_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("synapsix")
}

pub fn load_config() -> LauncherConfig {
    let path = config_dir().join(CONFIG_FILE);

    if path.exists() {
        match std::fs::read_to_string(&path) {
            Ok(content) => {
                serde_json::from_str(&content).unwrap_or_default()
            }
            Err(e) => {
                log::warn!("Failed to read config: {}", e);
                LauncherConfig::default()
            }
        }
    } else {
        LauncherConfig::default()
    }
}

pub fn save_config(config: &LauncherConfig) {
    let dir = config_dir();
    if let Err(e) = std::fs::create_dir_all(&dir) {
        log::error!("Failed to create config dir: {}", e);
        return;
    }

    let path = dir.join(CONFIG_FILE);
    match serde_json::to_string_pretty(config) {
        Ok(content) => {
            if let Err(e) = std::fs::write(&path, content) {
                log::error!("Failed to write config: {}", e);
            }
        }
        Err(e) => {
            log::error!("Failed to serialize config: {}", e);
        }
    }
}

pub fn add_recent_workspace(config: &mut LauncherConfig, path: PathBuf) {
    config.recent_workspaces.retain(|p| p != &path);
    config.recent_workspaces.insert(0, path);
    config.recent_workspaces.truncate(10);
}
