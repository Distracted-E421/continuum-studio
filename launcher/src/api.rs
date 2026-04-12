//! API client for Synapsix communication and Cursor version management

use crate::{CursorVersion, SynapsixStatus, Workspace};
use std::path::PathBuf;

const SYNAPSIX_API_BASE: &str = "http://127.0.0.1:4000";
const VERSIONS_FILE: &str = "cursor-versions.json";

pub async fn fetch_versions() -> Result<Vec<CursorVersion>, String> {
    let versions = load_local_versions().await?;
    let installed = get_installed_versions().await;

    Ok(versions
        .into_iter()
        .map(|mut v| {
            v.installed = installed.contains(&v.version);
            v
        })
        .collect())
}

async fn load_local_versions() -> Result<Vec<CursorVersion>, String> {
    let paths = [
        dirs::config_dir().map(|d| d.join("synapsix").join(VERSIONS_FILE)),
        dirs::home_dir().map(|d| d.join(".cursor-versions").join(VERSIONS_FILE)),
        Some(PathBuf::from("/etc/synapsix").join(VERSIONS_FILE)),
    ];

    for path in paths.into_iter().flatten() {
        if path.exists() {
            match tokio::fs::read_to_string(&path).await {
                Ok(content) => {
                    return serde_json::from_str(&content)
                        .map_err(|e| format!("Failed to parse versions: {}", e));
                }
                Err(_) => continue,
            }
        }
    }

    // Try fetching from Synapsix API
    fetch_versions_from_api().await
}

async fn fetch_versions_from_api() -> Result<Vec<CursorVersion>, String> {
    let client = reqwest::Client::new();
    let url = format!("{}/api/cursor/versions", SYNAPSIX_API_BASE);

    match client.get(&url).send().await {
        Ok(resp) if resp.status().is_success() => {
            resp.json().await.map_err(|e| format!("Failed to parse: {}", e))
        }
        Ok(resp) => Err(format!("API error: {}", resp.status())),
        Err(e) => Err(format!("Connection failed: {}", e)),
    }
}

async fn get_installed_versions() -> Vec<String> {
    let versions_dir = dirs::home_dir()
        .map(|d| d.join(".cursor-versions").join("downloads"))
        .unwrap_or_else(|| PathBuf::from(".cursor-versions/downloads"));

    if !versions_dir.exists() {
        return Vec::new();
    }

    let mut installed = Vec::new();

    if let Ok(mut entries) = tokio::fs::read_dir(&versions_dir).await {
        while let Ok(Some(entry)) = entries.next_entry().await {
            if let Some(name) = entry.file_name().to_str() {
                if name.starts_with("cursor-") && name.ends_with(".AppImage") {
                    let version = name
                        .trim_start_matches("cursor-")
                        .trim_end_matches(".AppImage")
                        .to_string();
                    installed.push(version);
                }
            }
        }
    }

    installed
}

pub async fn download_version(version: String) -> (String, Result<(), String>) {
    let client = reqwest::Client::new();
    let url = format!("{}/api/cursor/download/{}", SYNAPSIX_API_BASE, version);

    let result = match client.post(&url).send().await {
        Ok(resp) if resp.status().is_success() => Ok(()),
        Ok(resp) => Err(format!("Download failed: {}", resp.status())),
        Err(e) => Err(format!("Connection failed: {}", e)),
    };

    (version, result)
}

pub async fn fetch_recent_workspaces() -> Vec<Workspace> {
    let mut workspaces = Vec::new();

    // Load from Cursor's global storage
    let cursor_storage = dirs::home_dir()
        .map(|d| d.join(".config/cursor/User/globalStorage/storage.json"));

    if let Some(path) = cursor_storage {
        if path.exists() {
            if let Ok(content) = tokio::fs::read_to_string(&path).await {
                if let Ok(storage) = serde_json::from_str::<serde_json::Value>(&content) {
                    if let Some(folders) = storage.get("openedPathsList").and_then(|v| v.get("workspaces3")) {
                        if let Some(arr) = folders.as_array() {
                            for item in arr.iter().take(10) {
                                if let Some(uri) = item.as_str() {
                                    if let Some(path_str) = uri.strip_prefix("file://") {
                                        let path = PathBuf::from(path_str);
                                        let name = path.file_name()
                                            .and_then(|n| n.to_str())
                                            .unwrap_or("Unknown")
                                            .to_string();
                                        workspaces.push(Workspace {
                                            path,
                                            name,
                                            last_opened: None,
                                        });
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Also load from launcher config's recent workspaces
    let config = crate::config::load_config();
    for path in config.recent_workspaces {
        if !workspaces.iter().any(|w| w.path == path) {
            let name = path.file_name()
                .and_then(|n| n.to_str())
                .unwrap_or("Unknown")
                .to_string();
            workspaces.push(Workspace {
                path,
                name,
                last_opened: None,
            });
        }
    }

    workspaces
}

pub async fn browse_for_folder() -> Option<PathBuf> {
    // Use native file dialog via zbus/portal or rfd
    // For now, we'll try the XDG Desktop Portal
    match open_folder_dialog().await {
        Ok(path) => Some(path),
        Err(e) => {
            log::warn!("Folder dialog failed: {}", e);
            None
        }
    }
}

async fn open_folder_dialog() -> Result<PathBuf, String> {
    use zbus::Connection;

    let conn = Connection::session()
        .await
        .map_err(|e| format!("D-Bus connection failed: {}", e))?;

    let proxy = zbus::fdo::DBusProxy::new(&conn)
        .await
        .map_err(|e| format!("D-Bus proxy failed: {}", e))?;

    // For now, return home directory as fallback
    // Full implementation would use org.freedesktop.portal.FileChooser
    dirs::home_dir()
        .ok_or_else(|| "Could not determine home directory".to_string())
}

pub async fn launch_cursor(version: String, workspace: PathBuf) -> Result<(), String> {
    let versions_dir = dirs::home_dir()
        .map(|d| d.join(".cursor-versions").join("downloads"))
        .ok_or("Could not determine versions directory")?;

    let appimage_path = versions_dir.join(format!("cursor-{}.AppImage", version));

    if !appimage_path.exists() {
        return Err(format!("Version {} not installed", version));
    }

    // Make sure it's executable
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut perms = std::fs::metadata(&appimage_path)
            .map_err(|e| format!("Failed to read permissions: {}", e))?
            .permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&appimage_path, perms)
            .map_err(|e| format!("Failed to set permissions: {}", e))?;
    }

    // Launch Cursor
    let mut cmd = tokio::process::Command::new(&appimage_path);
    cmd.arg("--no-sandbox");
    cmd.arg(&workspace);

    // Ensure Synapsix is running if configured
    let config = crate::config::load_config();
    if config.start_with_synapsix {
        ensure_synapsix_running().await;
    }

    cmd.spawn()
        .map_err(|e| format!("Failed to launch Cursor: {}", e))?;

    Ok(())
}

async fn ensure_synapsix_running() {
    let status = check_synapsix_status().await;
    if !status.daemon_running {
        log::info!("Starting Synapsix daemon...");
        let _ = tokio::process::Command::new("systemctl")
            .args(["--user", "start", "synapsix"])
            .spawn();
    }
}

pub async fn check_synapsix_status() -> SynapsixStatus {
    let mut status = SynapsixStatus::default();

    // Check if dialog daemon is running via D-Bus
    if let Ok(conn) = zbus::Connection::session().await {
        let proxy_result = conn
            .call_method(
                Some("dev.continuumlogic.synapsix.dialog"),
                "/dev/continuumlogic/synapsix/dialog",
                Some("org.freedesktop.DBus.Peer"),
                "Ping",
                &(),
            )
            .await;

        status.daemon_running = proxy_result.is_ok();
        status.dialog_available = proxy_result.is_ok();
    }

    // Check terminal monitor
    let monitor_socket = dirs::runtime_dir()
        .unwrap_or_else(|| PathBuf::from("/run/user/1000"))
        .join("synapsix-terminal-monitor.sock");

    status.terminal_monitor = monitor_socket.exists();

    // Try to get version from API
    let client = reqwest::Client::new();
    if let Ok(resp) = client.get(format!("{}/api/version", SYNAPSIX_API_BASE)).send().await {
        if let Ok(version_info) = resp.json::<serde_json::Value>().await {
            status.version = version_info.get("version")
                .and_then(|v| v.as_str())
                .map(String::from);
        }
    }

    status
}
