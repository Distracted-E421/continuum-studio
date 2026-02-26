//! Session tracking for Cursor instances
//!
//! Comprehensive process identification for Cursor IDE:
//! - Builds process trees from /proc to identify instance hierarchies
//! - Maps PIDs to windows via kdotool (KDE Plasma / Wayland)
//! - Classifies child processes (renderer, extension host, language server, etc.)
//! - Aggregates per-instance resource usage
//! - Assigns unique colors for visual identification
//! - Detects shadow workspaces and hidden services

use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::PathBuf;
use std::process::Command;

// ---------------------------------------------------------------------------
// Color palette for instance identification
// ---------------------------------------------------------------------------

/// Predefined colors for up to 8 concurrent Cursor instances
const INSTANCE_COLORS: &[&str] = &[
    "#FF6B6B", // Coral Red
    "#4ECDC4", // Teal
    "#FFE66D", // Warm Yellow
    "#A06CD5", // Purple
    "#FF8A5C", // Peach Orange
    "#6BCB77", // Green
    "#4D96FF", // Blue
    "#FF6B9D", // Pink
];

// ---------------------------------------------------------------------------
// Process classification
// ---------------------------------------------------------------------------

/// Type of Cursor-related process
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CursorProcessType {
    /// Main Cursor/Electron process (has --user-data-dir)
    Main,
    /// Bubblewrap/AppImage sandbox wrapper
    Sandbox,
    /// Chrome zygote process
    Zygote,
    /// Crashpad error reporter
    Crashpad,
    /// Extension host (node.mojom.NodeService with --dns-result-order)
    ExtensionHost,
    /// Shared process (node.mojom.NodeService without --dns-result-order)
    SharedProcess,
    /// Network service (network.mojom.NetworkService)
    NetworkService,
    /// Audio service (audio.mojom.AudioService)
    AudioService,
    /// GPU process (gpu-process)
    GpuProcess,
    /// Renderer process (--type=renderer)
    Renderer,
    /// Language server (jsonServerMain, htmlServerMain, etc.)
    LanguageServer(String),
    /// Git worker (gitWorker.js)
    GitWorker,
    /// File watcher / other utility
    Utility,
    /// Unknown cursor-related process
    Unknown,
}

impl CursorProcessType {
    pub fn label(&self) -> &str {
        match self {
            Self::Main => "Main",
            Self::Sandbox => "Sandbox",
            Self::Zygote => "Zygote",
            Self::Crashpad => "Crashpad",
            Self::ExtensionHost => "Extension Host",
            Self::SharedProcess => "Shared Process",
            Self::NetworkService => "Network",
            Self::AudioService => "Audio",
            Self::GpuProcess => "GPU",
            Self::Renderer => "Renderer",
            Self::LanguageServer(name) => {
                // Return a static str approximation
                if name.contains("json") { "JSON LS" }
                else if name.contains("html") { "HTML LS" }
                else if name.contains("markdown") { "Markdown LS" }
                else if name.contains("typescript") { "TypeScript LS" }
                else if name.contains("css") { "CSS LS" }
                else { "Language Server" }
            }
            Self::GitWorker => "Git Worker",
            Self::Utility => "Utility",
            Self::Unknown => "Unknown",
        }
    }

    /// Whether this process type is interesting to show in the UI
    pub fn is_notable(&self) -> bool {
        matches!(
            self,
            Self::Main | Self::ExtensionHost | Self::SharedProcess
                | Self::LanguageServer(_) | Self::GitWorker
        )
    }
}

// ---------------------------------------------------------------------------
// Process info (single process)
// ---------------------------------------------------------------------------

/// Information about a single process in the tree
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProcessInfo {
    /// Process ID
    pub pid: u32,
    /// Parent PID
    pub ppid: u32,
    /// Process type classification
    pub process_type: CursorProcessType,
    /// Resident memory in bytes
    pub rss_bytes: u64,
    /// CPU usage percentage (snapshot)
    pub cpu_percent: f32,
    /// Command line
    pub cmdline: String,
}

// ---------------------------------------------------------------------------
// Window info (KDE Plasma / Wayland)
// ---------------------------------------------------------------------------

/// Information about a window
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowInfo {
    /// KDE window UUID
    pub window_id: String,
    /// Process ID that owns the window
    pub pid: u32,
    /// Window title
    pub title: String,
    /// Whether this is the currently focused window
    pub is_active: bool,
}

// ---------------------------------------------------------------------------
// Cursor instance (aggregated)
// ---------------------------------------------------------------------------

/// A complete Cursor instance with all its processes and windows
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CursorSession {
    /// Process ID of the main cursor process
    pub pid: u32,
    /// Cursor version (if detectable)
    pub version: Option<String>,
    /// Workspace/folder path (from window title or cmdline)
    pub workspace: Option<String>,
    /// Data directory for this session
    pub data_dir: Option<String>,
    /// Extensions directory
    pub extensions_dir: Option<String>,
    /// Whether the session has the active (focused) window
    pub active: bool,
    /// Process start time (ISO 8601)
    pub started_at: Option<String>,
    /// Main window title
    pub window_title: Option<String>,
    /// Command line of the main process
    pub cmdline: String,
    /// Assigned color for visual identification
    pub color: String,
    /// Instance index (0-based, for color assignment)
    pub instance_index: usize,
    /// All child processes belonging to this instance
    pub children: Vec<ProcessInfo>,
    /// Windows belonging to this instance
    pub windows: Vec<WindowInfo>,
    /// Aggregate resource usage
    pub total_rss_bytes: u64,
    /// Total process count (including main)
    pub total_process_count: usize,
    /// Extension host count (indicates agent activity)
    pub extension_host_count: usize,
    /// Language server count
    pub language_server_count: usize,
}

impl CursorSession {
    /// Human-readable RSS
    pub fn rss_human(&self) -> String {
        format_bytes(self.total_rss_bytes)
    }
}

// ---------------------------------------------------------------------------
// Session tracker
// ---------------------------------------------------------------------------

/// Session tracker that monitors running Cursor instances
pub struct SessionTracker {
    /// Known sessions by main PID
    sessions: HashMap<u32, CursorSession>,
    /// Color assignment counter
    next_color_index: usize,
    /// Persistent PID -> color mapping (survives rescans)
    color_assignments: HashMap<u32, (usize, String)>,
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
            next_color_index: 0,
            color_assignments: HashMap::new(),
        }
    }

    /// Full scan: enumerate processes, build trees, map windows, classify
    pub fn scan_processes(&mut self) -> Vec<CursorSession> {
        // Step 1: Read all processes from /proc
        let all_procs = read_all_processes();

        // Step 2: Find root Cursor processes (those with --user-data-dir in cmdline)
        let root_pids = find_cursor_roots(&all_procs);

        // Step 3: Build process tree for each root
        let mut instances = Vec::new();

        for (idx, root_pid) in root_pids.iter().enumerate() {
            let root_proc = match all_procs.get(root_pid) {
                Some(p) => p,
                None => continue,
            };

            // Find all descendants
            let children = find_descendants(*root_pid, &all_procs);

            // Classify each child
            let classified_children: Vec<ProcessInfo> = children
                .iter()
                .filter_map(|pid| {
                    all_procs.get(pid).map(|p| {
                        let process_type = classify_process(&p.cmdline);
                        ProcessInfo {
                            pid: *pid,
                            ppid: p.ppid,
                            process_type,
                            rss_bytes: p.rss_bytes,
                            cpu_percent: 0.0, // Would need /proc/PID/stat delta
                            cmdline: p.cmdline.clone(),
                        }
                    })
                })
                .collect();

            // Extract metadata from root process cmdline
            let version = extract_version(&root_proc.cmdline);
            let data_dir = extract_arg(&root_proc.cmdline, "--user-data-dir");
            let extensions_dir = extract_arg(&root_proc.cmdline, "--extensions-dir");

            // Aggregate stats
            let total_rss: u64 = root_proc.rss_bytes
                + classified_children.iter().map(|c| c.rss_bytes).sum::<u64>();
            let ext_host_count = classified_children
                .iter()
                .filter(|c| c.process_type == CursorProcessType::ExtensionHost)
                .count();
            let ls_count = classified_children
                .iter()
                .filter(|c| matches!(c.process_type, CursorProcessType::LanguageServer(_)))
                .count();

            // Assign or reuse color
            let (color_idx, color) = self.assign_color(*root_pid, idx);

            let session = CursorSession {
                pid: *root_pid,
                version,
                workspace: None, // Will be filled from window title
                data_dir,
                extensions_dir,
                active: false, // Will be filled from window info
                started_at: get_start_time(*root_pid),
                window_title: None, // Will be filled from window info
                cmdline: root_proc.cmdline.clone(),
                color,
                instance_index: color_idx,
                children: classified_children,
                windows: vec![],
                total_rss_bytes: total_rss,
                total_process_count: 1 + children.len(),
                extension_host_count: ext_host_count,
                language_server_count: ls_count,
            };

            instances.push(session);
        }

        // Step 4: Map windows to instances via kdotool
        let windows = enumerate_windows();
        for window in windows {
            // Find which instance owns this window
            for instance in &mut instances {
                let instance_pids: Vec<u32> = std::iter::once(instance.pid)
                    .chain(instance.children.iter().map(|c| c.pid))
                    .collect();

                if instance_pids.contains(&window.pid) {
                    if window.is_active {
                        instance.active = true;
                    }
                    // Extract workspace from window title if main window
                    if window.pid == instance.pid && window.title.contains(" - Cursor") {
                        instance.window_title = Some(window.title.clone());
                        instance.workspace = extract_workspace_from_title(&window.title);
                    }
                    instance.windows.push(window.clone());
                    break;
                }
            }
        }

        // Update internal state
        self.sessions.clear();
        for session in &instances {
            self.sessions.insert(session.pid, session.clone());
        }

        instances
    }

    /// Assign or reuse a color for an instance
    fn assign_color(&mut self, pid: u32, _instance_idx: usize) -> (usize, String) {
        if let Some((idx, color)) = self.color_assignments.get(&pid) {
            return (*idx, color.clone());
        }

        let idx = self.next_color_index % INSTANCE_COLORS.len();
        let color = INSTANCE_COLORS[idx].to_string();
        self.color_assignments.insert(pid, (idx, color.clone()));
        self.next_color_index += 1;
        (idx, color)
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
        self.sessions
            .values()
            .filter(|s| {
                s.workspace
                    .as_ref()
                    .map(|w| w == workspace_path)
                    .unwrap_or(false)
            })
            .collect()
    }

    /// Get color for a specific PID (for border overlay use)
    pub fn get_instance_color(&self, pid: u32) -> Option<&str> {
        self.sessions.get(&pid).map(|s| s.color.as_str())
    }

    /// Flash identification: briefly highlight all windows for an instance
    /// Returns the kdotool window IDs that should be highlighted
    pub fn get_window_ids_for_instance(&self, pid: u32) -> Vec<String> {
        self.sessions
            .get(&pid)
            .map(|s| s.windows.iter().map(|w| w.window_id.clone()).collect())
            .unwrap_or_default()
    }
}

// ---------------------------------------------------------------------------
// Internal /proc reading
// ---------------------------------------------------------------------------

/// Raw process data from /proc
struct RawProcess {
    ppid: u32,
    cmdline: String,
    rss_bytes: u64,
}

/// Read all processes from /proc
fn read_all_processes() -> HashMap<u32, RawProcess> {
    let mut procs = HashMap::new();

    let Ok(entries) = std::fs::read_dir("/proc") else {
        return procs;
    };

    for entry in entries.flatten() {
        let name = entry.file_name();
        let name_str = name.to_string_lossy();

        // Only process numeric directories (PIDs)
        let Ok(pid) = name_str.parse::<u32>() else {
            continue;
        };

        let proc_dir = entry.path();

        // Read cmdline
        let cmdline = std::fs::read_to_string(proc_dir.join("cmdline"))
            .map(|s| s.replace('\0', " ").trim().to_string())
            .unwrap_or_default();

        if cmdline.is_empty() {
            continue;
        }

        // Read PPID from /proc/PID/stat
        let ppid = std::fs::read_to_string(proc_dir.join("stat"))
            .ok()
            .and_then(|s| {
                // Format: PID (comm) state PPID ...
                // Need to find PPID after the closing paren
                let after_comm = s.rfind(')')?;
                let fields: Vec<&str> = s[after_comm + 2..].split_whitespace().collect();
                fields.get(1).and_then(|p| p.parse().ok())
            })
            .unwrap_or(0);

        // Read RSS from /proc/PID/statm (second field, in pages)
        let rss_bytes = std::fs::read_to_string(proc_dir.join("statm"))
            .ok()
            .and_then(|s| {
                let fields: Vec<&str> = s.split_whitespace().collect();
                fields.get(1).and_then(|p| p.parse::<u64>().ok())
            })
            .unwrap_or(0)
            * 4096; // pages to bytes

        procs.insert(pid, RawProcess {
            ppid,
            cmdline,
            rss_bytes,
        });
    }

    procs
}

/// Find root Cursor processes (those with --user-data-dir AND are the main process)
fn find_cursor_roots(procs: &HashMap<u32, RawProcess>) -> Vec<u32> {
    let mut roots = Vec::new();

    for (&pid, proc) in procs {
        let cmd = &proc.cmdline;

        // The main Cursor process has:
        // - "cursor" in the binary name (not just matching as substring of other words)
        // - --user-data-dir flag
        // - Does NOT have --type= (which indicates a child/utility process)
        let is_cursor_main = (cmd.contains("/cursor ") || cmd.contains("/cursor-") || cmd.ends_with("/cursor"))
            && cmd.contains("--user-data-dir=")
            && !cmd.contains("--type=");

        if is_cursor_main {
            roots.push(pid);
        }
    }

    roots.sort();
    roots
}

/// Find all descendant PIDs of a given root
fn find_descendants(root: u32, procs: &HashMap<u32, RawProcess>) -> Vec<u32> {
    let mut descendants = Vec::new();
    let mut to_visit = vec![root];

    while let Some(parent) = to_visit.pop() {
        for (&pid, proc) in procs {
            if proc.ppid == parent && pid != root && !descendants.contains(&pid) {
                descendants.push(pid);
                to_visit.push(pid);
            }
        }
    }

    descendants
}

/// Classify a process based on its command line
fn classify_process(cmdline: &str) -> CursorProcessType {
    let cmd = cmdline.to_lowercase();

    if cmd.contains("bwrap") || cmd.contains("bubblewrap") || cmd.contains("appimage-run") {
        return CursorProcessType::Sandbox;
    }

    if cmd.contains("--type=zygote") {
        return CursorProcessType::Zygote;
    }

    if cmd.contains("crashpad") || cmd.contains("crash-reporter") {
        return CursorProcessType::Crashpad;
    }

    if cmd.contains("--type=renderer") {
        return CursorProcessType::Renderer;
    }

    if cmd.contains("--type=gpu") || cmd.contains("gpu-process") {
        return CursorProcessType::GpuProcess;
    }

    if cmd.contains("network.mojom.networkservice") {
        return CursorProcessType::NetworkService;
    }

    if cmd.contains("audio.mojom.audioservice") {
        return CursorProcessType::AudioService;
    }

    // Extension host: NodeService with dns-result-order (has network inspection)
    if cmd.contains("node.mojom.nodeservice") {
        if cmd.contains("--dns-result-order") || cmd.contains("--experimental-network-inspection") {
            return CursorProcessType::ExtensionHost;
        }
        return CursorProcessType::SharedProcess;
    }

    // Language servers
    if cmd.contains("gitworker.js") || cmd.contains("gitworker") {
        return CursorProcessType::GitWorker;
    }

    let ls_patterns = [
        ("jsonservermain", "json"),
        ("htmlservermain", "html"),
        ("cssservermain", "css"),
        ("typescriptservermain", "typescript"),
        ("markdownservermain", "markdown"),
        ("yamlservermain", "yaml"),
        ("cursor-always-local", "cursor-local"),
    ];

    for (pattern, name) in ls_patterns {
        if cmd.contains(pattern) {
            return CursorProcessType::LanguageServer(name.to_string());
        }
    }

    // Generic language server detection
    if cmd.contains("servermain") || cmd.contains("--node-ipc") {
        return CursorProcessType::LanguageServer("generic".to_string());
    }

    if cmd.contains("cursor") && !cmd.contains("--type=") {
        return CursorProcessType::Main;
    }

    CursorProcessType::Unknown
}

// ---------------------------------------------------------------------------
// Window enumeration via kdotool (KDE Plasma / Wayland)
// ---------------------------------------------------------------------------

/// Enumerate all windows and map to PIDs
fn enumerate_windows() -> Vec<WindowInfo> {
    let mut windows = Vec::new();

    // Get active window ID
    let active_wid = Command::new("kdotool")
        .arg("getactivewindow")
        .output()
        .ok()
        .and_then(|o| {
            if o.status.success() {
                Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
            } else {
                None
            }
        });

    // Search all windows
    let Ok(output) = Command::new("kdotool")
        .args(["search", "--name", "."])
        .output()
    else {
        return windows;
    };

    if !output.status.success() {
        return windows;
    }

    let window_ids: Vec<String> = String::from_utf8_lossy(&output.stdout)
        .lines()
        .map(|l| l.trim().to_string())
        .filter(|l| !l.is_empty())
        .collect();

    for wid in window_ids {
        // Get PID
        let pid = Command::new("kdotool")
            .args(["getwindowpid", &wid])
            .output()
            .ok()
            .and_then(|o| {
                if o.status.success() {
                    String::from_utf8_lossy(&o.stdout)
                        .trim()
                        .parse::<u32>()
                        .ok()
                } else {
                    None
                }
            });

        let Some(pid) = pid else { continue };

        // Get window name
        let title = Command::new("kdotool")
            .args(["getwindowname", &wid])
            .output()
            .ok()
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
            .unwrap_or_default();

        let is_active = active_wid.as_deref() == Some(&wid);

        windows.push(WindowInfo {
            window_id: wid,
            pid,
            title,
            is_active,
        });
    }

    windows
}

// ---------------------------------------------------------------------------
// Metadata extraction helpers
// ---------------------------------------------------------------------------

/// Extract version from cmdline
fn extract_version(cmdline: &str) -> Option<String> {
    // Pattern: --user-data-dir=.../.cursor-VERSION or cursor-VERSION paths
    if let Some(data_dir) = extract_arg(cmdline, "--user-data-dir") {
        // ~/.cursor-2.4.27 -> 2.4.27
        if let Some(idx) = data_dir.rfind(".cursor-") {
            let version_part = &data_dir[idx + 8..];
            // Take until next / or end
            let version = version_part.split('/').next().unwrap_or(version_part);
            if version.contains('.') {
                return Some(version.to_string());
            }
        }
    }

    // Fallback: regex-like search for version patterns
    let patterns = ["cursor-", "Cursor-"];
    for pat in patterns {
        if let Some(start) = cmdline.find(pat) {
            let rest = &cmdline[start + pat.len()..];
            let version: String = rest
                .chars()
                .take_while(|c| c.is_ascii_digit() || *c == '.')
                .collect();
            if version.contains('.') && version.len() >= 3 {
                return Some(version);
            }
        }
    }

    None
}

/// Extract a --key=value argument from cmdline
fn extract_arg(cmdline: &str, key: &str) -> Option<String> {
    let prefix = format!("{}=", key);
    for part in cmdline.split_whitespace() {
        if let Some(stripped) = part.strip_prefix(&prefix) {
            return Some(stripped.to_string());
        }
    }
    None
}

/// Extract workspace name from window title
/// Title format: "workspace name - folder (Workspace) - Cursor"
fn extract_workspace_from_title(title: &str) -> Option<String> {
    if !title.contains(" - Cursor") {
        return None;
    }

    // Remove " - Cursor" suffix
    let base = title.strip_suffix(" - Cursor").unwrap_or(title);

    // If it contains "(Workspace)", extract the folder before it
    if let Some(ws_idx) = base.find(" (Workspace)") {
        let before_ws = &base[..ws_idx];
        // The last " - " separates the file/context from the folder
        if let Some(dash_idx) = before_ws.rfind(" - ") {
            return Some(before_ws[dash_idx + 3..].to_string());
        }
        return Some(before_ws.to_string());
    }

    // Otherwise, take the last segment before " - Cursor"
    if let Some(dash_idx) = base.rfind(" - ") {
        return Some(base[dash_idx + 3..].to_string());
    }

    Some(base.to_string())
}

/// Get process start time as ISO 8601
fn get_start_time(pid: u32) -> Option<String> {
    // Read /proc/PID/stat for starttime field
    let stat = std::fs::read_to_string(format!("/proc/{}/stat", pid)).ok()?;

    // Parse starttime (field 22, 0-indexed after closing paren)
    let after_comm = stat.rfind(')')?;
    let fields: Vec<&str> = stat[after_comm + 2..].split_whitespace().collect();
    let start_ticks: u64 = fields.get(19)?.parse().ok()?;

    // Get system uptime
    let uptime_str = std::fs::read_to_string("/proc/uptime").ok()?;
    let uptime_secs: f64 = uptime_str.split_whitespace().next()?.parse().ok()?;

    // Get clock ticks per second (usually 100 on Linux)
    let ticks_per_sec: u64 = 100; // sysconf(_SC_CLK_TCK)

    // Calculate process age
    let start_secs = start_ticks / ticks_per_sec;
    let now_secs = uptime_secs as u64;
    let age_secs = now_secs.saturating_sub(start_secs);

    // Convert to approximate timestamp
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .ok()?;
    let started_epoch = now.as_secs() - age_secs;

    // Format as ISO 8601 (simplified)
    let hours = age_secs / 3600;
    let mins = (age_secs % 3600) / 60;

    if hours > 0 {
        Some(format!("{}h {}m ago (epoch: {})", hours, mins, started_epoch))
    } else {
        Some(format!("{}m ago (epoch: {})", mins, started_epoch))
    }
}

/// Format bytes to human-readable
fn format_bytes(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{} B", bytes)
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else if bytes < 1024 * 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else {
        format!("{:.2} GB", bytes as f64 / (1024.0 * 1024.0 * 1024.0))
    }
}

// ---------------------------------------------------------------------------
// Shared Cursor settings (preserved from original)
// ---------------------------------------------------------------------------

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
    pub fn apply_to_data_dir(&self, data_dir: &std::path::Path) -> Result<(), String> {
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

        for (key, value) in &self.settings_overrides {
            settings[key] = value.clone();
        }

        if let Some(parent) = settings_path.parent() {
            std::fs::create_dir_all(parent)
                .map_err(|e| format!("Failed to create settings directory: {}", e))?;
        }

        let content = serde_json::to_string_pretty(&settings)
            .map_err(|e| format!("Failed to serialize settings: {}", e))?;
        std::fs::write(&settings_path, content)
            .map_err(|e| format!("Failed to write settings: {}", e))?;

        Ok(())
    }

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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_version_extraction() {
        // From user-data-dir
        let cmd = "cursor --user-data-dir=/home/e421/.cursor-2.4.27 --extensions-dir=/home/e421/.cursor-2.4.27/extensions";
        assert_eq!(extract_version(cmd), Some("2.4.27".to_string()));

        // From AppImage path
        let cmd = "/tmp/Cursor-2.4.21-x86_64.AppImage --user-data-dir=/home/user/.cursor-2.4.21";
        assert_eq!(extract_version(cmd), Some("2.4.21".to_string()));

        // Nix store
        let cmd = "/nix/store/abc-cursor-2.0.77/bin/cursor --user-data-dir=/home/user/.cursor-2.0.77";
        assert_eq!(extract_version(cmd), Some("2.0.77".to_string()));
    }

    #[test]
    fn test_workspace_from_title() {
        let title = "main.rs - homelab (Workspace) - Cursor";
        assert_eq!(
            extract_workspace_from_title(title),
            Some("homelab".to_string())
        );

        let title = "Continuum studio chat context system - synapsix-continuum-studio-homelab-nixcursor (Workspace) - Cursor";
        assert_eq!(
            extract_workspace_from_title(title),
            Some("synapsix-continuum-studio-homelab-nixcursor".to_string())
        );

        let title = "index.html - Cursor";
        assert_eq!(
            extract_workspace_from_title(title),
            Some("index.html".to_string())
        );
    }

    #[test]
    fn test_classify_process() {
        assert_eq!(
            classify_process("cursor --user-data-dir=foo"),
            CursorProcessType::Main
        );
        assert_eq!(
            classify_process("/proc/self/exe --type=zygote"),
            CursorProcessType::Zygote
        );
        assert_eq!(
            classify_process("/proc/self/exe --type=utility --utility-sub-type=node.mojom.NodeService --dns-result-order=ipv4first"),
            CursorProcessType::ExtensionHost
        );
        assert_eq!(
            classify_process("/proc/self/exe --type=utility --utility-sub-type=network.mojom.NetworkService"),
            CursorProcessType::NetworkService
        );
        assert!(matches!(
            classify_process("cursor /path/to/jsonServerMain --node-ipc"),
            CursorProcessType::LanguageServer(_)
        ));
    }

    #[test]
    fn test_extract_arg() {
        let cmd = "cursor --user-data-dir=/home/e421/.cursor-2.4.27 --extensions-dir=/foo/bar";
        assert_eq!(
            extract_arg(cmd, "--user-data-dir"),
            Some("/home/e421/.cursor-2.4.27".to_string())
        );
        assert_eq!(
            extract_arg(cmd, "--extensions-dir"),
            Some("/foo/bar".to_string())
        );
        assert_eq!(extract_arg(cmd, "--nonexistent"), None);
    }
}
