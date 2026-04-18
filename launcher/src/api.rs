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

/// Wrapper for versioned JSON format from cursor-versions CLI
#[derive(serde::Deserialize)]
struct VersionsWrapper {
    versions: Vec<CursorVersion>,
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
                    // Try parsing as wrapped format first (from cursor-versions CLI)
                    if let Ok(wrapper) = serde_json::from_str::<VersionsWrapper>(&content) {
                        return Ok(wrapper.versions);
                    }
                    // Fall back to direct array format
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
                    // Extract version from patterns like:
                    // cursor-3.1.14-linux-x64.AppImage -> 3.1.14
                    // cursor-3.1.14.AppImage -> 3.1.14
                    let without_prefix = name.trim_start_matches("cursor-");
                    let without_suffix = without_prefix.trim_end_matches(".AppImage");
                    
                    // Remove platform suffixes
                    let version = without_suffix
                        .trim_end_matches("-linux-x64")
                        .trim_end_matches("-linux-arm64")
                        .trim_end_matches("-darwin-x64")
                        .trim_end_matches("-darwin-arm64")
                        .trim_end_matches("-darwin-universal")
                        .trim_end_matches("-x86_64")
                        .trim_end_matches("-aarch64")
                        .to_string();
                    
                    if !version.is_empty() {
                        installed.push(version);
                    }
                }
            }
        }
    }

    installed
}

pub async fn download_version(version: String) -> (String, Result<(), String>) {
    // Use cursor-versions CLI to install
    let result = tokio::process::Command::new("cursor-versions")
        .args(["install", &version])
        .output()
        .await;

    let result = match result {
        Ok(output) if output.status.success() => Ok(()),
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);
            Err(format!("Install failed: {}{}", stdout, stderr))
        }
        Err(e) => Err(format!("Failed to run cursor-versions: {}", e)),
    };

    (version, result)
}

pub async fn uninstall_version(version: String) -> (String, Result<(), String>) {
    // Use cursor-versions CLI to uninstall
    let result = tokio::process::Command::new("cursor-versions")
        .args(["uninstall", &version])
        .output()
        .await;

    let result = match result {
        Ok(output) if output.status.success() => Ok(()),
        Ok(output) => {
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);
            Err(format!("Uninstall failed: {}{}", stdout, stderr))
        }
        Err(e) => Err(format!("Failed to run cursor-versions: {}", e)),
    };

    (version, result)
}

pub async fn fetch_recent_workspaces() -> Vec<Workspace> {
    let mut workspaces = Vec::new();

    // Load from Cursor's global storage (note: capital C in Cursor)
    let cursor_storage = dirs::home_dir()
        .map(|d| d.join(".config/Cursor/User/globalStorage/storage.json"));

    eprintln!("[DEBUG] Looking for storage at: {:?}", cursor_storage);

    if let Some(ref path) = cursor_storage {
        eprintln!("[DEBUG] Storage path exists: {}", path.exists());
        if path.exists() {
            if let Ok(content) = tokio::fs::read_to_string(&path).await {
                eprintln!("[DEBUG] Read storage.json, {} bytes", content.len());
                if let Ok(storage) = serde_json::from_str::<serde_json::Value>(&content) {
                    // Parse workspaces from profileAssociations.workspaces (keys are URIs)
                    let profiles = storage.get("profileAssociations")
                        .and_then(|v| v.get("workspaces"))
                        .and_then(|v| v.as_object());
                    
                    eprintln!("[DEBUG] Found profiles: {}", profiles.is_some());
                    
                    if let Some(profiles) = profiles {
                        eprintln!("[DEBUG] Profile entries: {}", profiles.len());
                        for (uri, _) in profiles.iter().take(20) {
                            eprintln!("[DEBUG] Processing: {}", uri);
                            if let Some(path_str) = uri.strip_prefix("file://") {
                                let path = PathBuf::from(path_str);
                                // Skip internal Cursor workspace.json files (but allow .code-workspace)
                                if path_str.contains("/Workspaces/") && path_str.contains("/workspace.json") {
                                    eprintln!("[DEBUG] Skipping internal workspace.json: {}", path_str);
                                    continue;
                                }
                                // Only include paths that exist
                                let exists = path.exists();
                                eprintln!("[DEBUG] Path {} exists: {}", path.display(), exists);
                                if !exists {
                                    continue;
                                }
                                let name = path.file_name()
                                    .and_then(|n| n.to_str())
                                    .unwrap_or("Unknown")
                                    .to_string();
                                if !workspaces.iter().any(|w: &Workspace| w.path == path) {
                                    eprintln!("[DEBUG] Adding workspace: {} at {}", name, path.display());
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
    
    eprintln!("[DEBUG] Total workspaces found: {}", workspaces.len());
    
    // Add default workspaces if none found
    if workspaces.is_empty() {
        // Add some common paths
        let common_paths = [
            "/home/e421/homelab",
            "/home/e421/cortex",
            "/home/e421/synapsix",
        ];
        for path_str in &common_paths {
            let path = PathBuf::from(path_str);
            if path.exists() {
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

    // Try different AppImage filename patterns
    let patterns = [
        format!("cursor-{}-linux-x64.AppImage", version),
        format!("cursor-{}-linux-arm64.AppImage", version),
        format!("cursor-{}.AppImage", version),
    ];
    
    let mut appimage_path = None;
    for pattern in &patterns {
        let path = versions_dir.join(pattern);
        if path.exists() {
            appimage_path = Some(path);
            break;
        }
    }
    
    let appimage_path = appimage_path
        .ok_or_else(|| {
            let tried: Vec<_> = patterns.iter().map(|p| versions_dir.join(p)).collect();
            format!(
                "Version '{}' not found. Tried:\n{}",
                version,
                tried.iter()
                    .map(|p| format!("  - {}", p.display()))
                    .collect::<Vec<_>>()
                    .join("\n")
            )
        })?;

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
    // Correct D-Bus name: sh.synapsix.Dialog at /sh/synapsix/Dialog
    if let Ok(conn) = zbus::Connection::session().await {
        let proxy_result = conn
            .call_method(
                Some("sh.synapsix.Dialog"),
                "/sh/synapsix/Dialog",
                Some("org.freedesktop.DBus.Peer"),
                "Ping",
                &(),
            )
            .await;

        status.daemon_running = proxy_result.is_ok();
        status.dialog_available = proxy_result.is_ok();
    }

    // Check terminal monitor via systemd
    let monitor_status = tokio::process::Command::new("systemctl")
        .args(["--user", "is-active", "synapsix-terminal-monitor"])
        .output()
        .await;
    
    status.terminal_monitor = monitor_status
        .map(|o| o.status.success())
        .unwrap_or(false);

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
