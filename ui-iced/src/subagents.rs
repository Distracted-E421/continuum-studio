//! Sub-agent Monitoring Panel for Continuum Studio
//!
//! Displays real-time sub-agent activity from the synapsix-terminal-monitor D-Bus service.
//!
//! Features:
//! - Active sub-agent count and status
//! - Recent command history with error detection
//! - Sub-agent health metrics
//! - Error pattern suggestions
//! - Randomly generated agent code names (NATO phonetic + color)

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};

/// Maximum sub-agent commands to display in history
const MAX_HISTORY: usize = 50;

/// D-Bus service name for terminal monitor
pub const DBUS_SERVICE: &str = "sh.synapsix.TerminalMonitor";
pub const DBUS_PATH: &str = "/sh/synapsix/TerminalMonitor";
pub const DBUS_INTERFACE: &str = "sh.synapsix.TerminalMonitor1";

// ══════════════════════════════════════════════════════════════════
// Agent Code Names (NATO Phonetic + Colors)
// ══════════════════════════════════════════════════════════════════

/// NATO phonetic alphabet for agent names
const NATO_ALPHABET: &[&str] = &[
    "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel",
    "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa",
    "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey",
    "X-ray", "Yankee", "Zulu",
];

/// Color names for agent identification
const COLORS: &[&str] = &[
    "Red", "Blue", "Green", "Gold", "Silver", "Orange", "Purple", "Cyan",
    "Magenta", "Lime", "Coral", "Teal", "Navy", "Crimson", "Amber", "Jade",
];

/// Color values (RGB) for visual display
const COLOR_RGB: &[(&str, (f32, f32, f32))] = &[
    ("Red", (0.9, 0.2, 0.2)),
    ("Blue", (0.2, 0.4, 0.9)),
    ("Green", (0.2, 0.8, 0.3)),
    ("Gold", (0.9, 0.75, 0.2)),
    ("Silver", (0.7, 0.7, 0.75)),
    ("Orange", (0.95, 0.5, 0.1)),
    ("Purple", (0.6, 0.2, 0.8)),
    ("Cyan", (0.1, 0.8, 0.85)),
    ("Magenta", (0.85, 0.2, 0.6)),
    ("Lime", (0.5, 0.95, 0.2)),
    ("Coral", (0.95, 0.45, 0.4)),
    ("Teal", (0.15, 0.65, 0.6)),
    ("Navy", (0.15, 0.2, 0.5)),
    ("Crimson", (0.8, 0.1, 0.25)),
    ("Amber", (0.95, 0.65, 0.1)),
    ("Jade", (0.25, 0.75, 0.55)),
];

/// Agent code name with display color
#[derive(Debug, Clone)]
pub struct AgentCodeName {
    /// Full code name (e.g., "Alpha-Red")
    pub name: String,
    /// NATO phonetic part (e.g., "Alpha")
    pub phonetic: String,
    /// Color part (e.g., "Red")
    pub color: String,
    /// RGB color for display
    pub rgb: (f32, f32, f32),
}

/// Generate a deterministic code name from a terminal ID
/// Uses a hash to ensure the same terminal always gets the same name
pub fn generate_codename(terminal_id: &str) -> AgentCodeName {
    use std::hash::{Hash, Hasher};
    use std::collections::hash_map::DefaultHasher;
    
    let mut hasher = DefaultHasher::new();
    terminal_id.hash(&mut hasher);
    let hash = hasher.finish();
    
    // Use different bits of hash for NATO and color
    let nato_idx = (hash as usize) % NATO_ALPHABET.len();
    let color_idx = ((hash >> 8) as usize) % COLORS.len();
    
    let phonetic = NATO_ALPHABET[nato_idx].to_string();
    let color = COLORS[color_idx].to_string();
    let rgb = COLOR_RGB.iter()
        .find(|(c, _)| *c == color)
        .map(|(_, rgb)| *rgb)
        .unwrap_or((0.5, 0.5, 0.5));
    
    AgentCodeName {
        name: format!("{}-{}", phonetic, color),
        phonetic,
        color,
        rgb,
    }
}

/// Manages code names for all known terminals
#[derive(Debug, Clone, Default)]
pub struct CodeNameRegistry {
    /// Map from terminal_id to code name
    names: HashMap<String, AgentCodeName>,
}

impl CodeNameRegistry {
    pub fn new() -> Self {
        Self::default()
    }
    
    /// Get or generate a code name for a terminal
    pub fn get_or_create(&mut self, terminal_id: &str) -> AgentCodeName {
        if let Some(name) = self.names.get(terminal_id) {
            return name.clone();
        }
        
        let codename = generate_codename(terminal_id);
        self.names.insert(terminal_id.to_string(), codename.clone());
        codename
    }
    
    /// Get code name if it exists (doesn't create new)
    pub fn get(&self, terminal_id: &str) -> Option<&AgentCodeName> {
        self.names.get(terminal_id)
    }
    
    /// Clear all code names (useful for testing)
    pub fn clear(&mut self) {
        self.names.clear();
    }
}

// ══════════════════════════════════════════════════════════════════
// Data Types (match D-Bus interface)
// ══════════════════════════════════════════════════════════════════

/// Command record from terminal monitor
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CommandRecord {
    pub terminal_id: String,
    pub pid: u32,
    pub cwd: String,
    pub command: String,
    pub started_at: i64,
    pub elapsed_ms: Option<u64>,
    pub exit_code: Option<i32>,
    pub is_subagent: bool,
    pub errors: Vec<ErrorDetection>,
}

/// Detected error pattern
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorDetection {
    pub category: String,
    pub pattern: String,
    pub suggestion: String,
    pub confidence: f64,
}

/// Monitor statistics
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MonitorStats {
    pub total_commands: u64,
    pub active_commands: u32,
    pub failed_commands: u64,
    pub subagent_commands: u64,
    pub uptime_seconds: u64,
    pub error_rate: f64,
}

impl Default for MonitorStats {
    fn default() -> Self {
        Self {
            total_commands: 0,
            active_commands: 0,
            failed_commands: 0,
            subagent_commands: 0,
            uptime_seconds: 0,
            error_rate: 0.0,
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// Panel State
// ══════════════════════════════════════════════════════════════════

/// State for the sub-agent panel
#[derive(Debug, Clone)]
pub struct SubagentPanelState {
    /// Is the terminal monitor service reachable?
    pub service_available: bool,
    /// Last error message if service unavailable
    pub error_message: Option<String>,
    /// Monitor statistics
    pub stats: MonitorStats,
    /// Recent commands (all, not just sub-agents)
    pub recent_commands: VecDeque<CommandRecord>,
    /// Recent sub-agent commands specifically
    pub subagent_commands: VecDeque<CommandRecord>,
    /// Currently running commands
    pub running_commands: Vec<CommandRecord>,
    /// Last update timestamp
    pub last_update: Option<std::time::Instant>,
    /// Whether we're currently loading data
    pub loading: bool,
    /// Code name registry for friendly agent names
    pub codenames: CodeNameRegistry,
}

impl Default for SubagentPanelState {
    fn default() -> Self {
        Self {
            service_available: false,
            error_message: None,
            stats: MonitorStats::default(),
            recent_commands: VecDeque::with_capacity(MAX_HISTORY),
            subagent_commands: VecDeque::with_capacity(MAX_HISTORY),
            running_commands: Vec::new(),
            last_update: None,
            loading: false,
            codenames: CodeNameRegistry::new(),
        }
    }
}

impl SubagentPanelState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Update stats from D-Bus response
    pub fn update_stats(&mut self, stats: MonitorStats) {
        self.stats = stats;
        self.last_update = Some(std::time::Instant::now());
        self.service_available = true;
        self.error_message = None;
    }

    /// Update recent commands from D-Bus response
    pub fn update_recent(&mut self, commands: Vec<CommandRecord>) {
        self.recent_commands.clear();
        for cmd in commands.into_iter().take(MAX_HISTORY) {
            self.recent_commands.push_back(cmd);
        }
        self.last_update = Some(std::time::Instant::now());
    }

    /// Update sub-agent commands from D-Bus response
    pub fn update_subagents(&mut self, commands: Vec<CommandRecord>) {
        self.subagent_commands.clear();
        for cmd in commands.into_iter().take(MAX_HISTORY) {
            self.subagent_commands.push_back(cmd);
        }
    }

    /// Update running commands from D-Bus response
    pub fn update_running(&mut self, commands: Vec<CommandRecord>) {
        self.running_commands = commands;
    }

    /// Mark service as unavailable with error
    pub fn set_error(&mut self, error: String) {
        self.service_available = false;
        self.error_message = Some(error);
        self.loading = false;
    }

    /// Get health status as color tuple (r, g, b)
    pub fn health_color(&self) -> (f32, f32, f32) {
        if !self.service_available {
            return (0.5, 0.5, 0.5); // Gray - unavailable
        }
        if self.stats.error_rate > 0.3 {
            return (0.9, 0.3, 0.3); // Red - high error rate
        }
        if self.stats.error_rate > 0.1 {
            return (0.9, 0.7, 0.2); // Yellow - moderate error rate
        }
        (0.3, 0.8, 0.4) // Green - healthy
    }
}

// ══════════════════════════════════════════════════════════════════
// Messages
// ══════════════════════════════════════════════════════════════════

/// Messages for the sub-agent panel
#[derive(Debug, Clone)]
pub enum SubagentMessage {
    /// Request to refresh data
    Refresh,
    /// Stats loaded from D-Bus
    StatsLoaded(Result<MonitorStats, String>),
    /// Recent commands loaded
    RecentLoaded(Result<Vec<CommandRecord>, String>),
    /// Sub-agent commands loaded
    SubagentsLoaded(Result<Vec<CommandRecord>, String>),
    /// Running commands loaded
    RunningLoaded(Result<Vec<CommandRecord>, String>),
    /// Service availability check result
    ServiceCheck(bool),
}

// ══════════════════════════════════════════════════════════════════
// D-Bus Client (async operations)
// ══════════════════════════════════════════════════════════════════

/// Async D-Bus client for terminal monitor
pub mod dbus_client {
    use super::*;
    use zbus::{Connection, Proxy};

    /// Get a proxy to the terminal monitor service
    pub async fn get_proxy<'a>(conn: &'a Connection) -> zbus::Result<Proxy<'a>> {
        Proxy::new(
            conn,
            super::DBUS_SERVICE,
            super::DBUS_PATH,
            super::DBUS_INTERFACE,
        )
        .await
    }

    /// Check if service is available
    pub async fn check_service(conn: &Connection) -> bool {
        if let Ok(proxy) = get_proxy(conn).await {
            proxy.call_method("GetStats", &()).await.is_ok()
        } else {
            false
        }
    }

    /// Get monitor stats
    pub async fn get_stats(conn: &Connection) -> Result<MonitorStats, String> {
        let proxy = get_proxy(conn).await.map_err(|e| e.to_string())?;
        let result: String = proxy
            .call_method("GetStats", &())
            .await
            .map_err(|e| e.to_string())?
            .body()
            .deserialize()
            .map_err(|e| e.to_string())?;
        serde_json::from_str(&result).map_err(|e| e.to_string())
    }

    /// Get recent commands
    pub async fn get_recent(conn: &Connection, limit: u32) -> Result<Vec<CommandRecord>, String> {
        let proxy = get_proxy(conn).await.map_err(|e| e.to_string())?;
        let result: String = proxy
            .call_method("GetRecentCommands", &(limit,))
            .await
            .map_err(|e| e.to_string())?
            .body()
            .deserialize()
            .map_err(|e| e.to_string())?;
        serde_json::from_str(&result).map_err(|e| e.to_string())
    }

    /// Get sub-agent commands
    pub async fn get_subagents(conn: &Connection, limit: u32) -> Result<Vec<CommandRecord>, String> {
        let proxy = get_proxy(conn).await.map_err(|e| e.to_string())?;
        let result: String = proxy
            .call_method("GetSubagentCommands", &(limit,))
            .await
            .map_err(|e| e.to_string())?
            .body()
            .deserialize()
            .map_err(|e| e.to_string())?;
        serde_json::from_str(&result).map_err(|e| e.to_string())
    }

    /// Get running commands
    pub async fn get_running(conn: &Connection) -> Result<Vec<CommandRecord>, String> {
        let proxy = get_proxy(conn).await.map_err(|e| e.to_string())?;
        let result: String = proxy
            .call_method("GetRunningCommands", &())
            .await
            .map_err(|e| e.to_string())?
            .body()
            .deserialize()
            .map_err(|e| e.to_string())?;
        serde_json::from_str(&result).map_err(|e| e.to_string())
    }
}

// ══════════════════════════════════════════════════════════════════
// View Helpers
// ══════════════════════════════════════════════════════════════════

/// Format a command for display (truncate if needed)
pub fn format_command(cmd: &str, max_len: usize) -> String {
    if cmd.len() <= max_len {
        cmd.to_string()
    } else {
        format!("{}...", &cmd[..max_len - 3])
    }
}

/// Format elapsed time for display
pub fn format_elapsed(ms: Option<u64>) -> String {
    match ms {
        None => "running...".to_string(),
        Some(ms) if ms < 1000 => format!("{}ms", ms),
        Some(ms) if ms < 60_000 => format!("{:.1}s", ms as f64 / 1000.0),
        Some(ms) => format!("{:.1}m", ms as f64 / 60_000.0),
    }
}

/// Format exit code with icon
pub fn format_exit_code(code: Option<i32>) -> (String, (f32, f32, f32)) {
    match code {
        None => ("⏳".to_string(), (0.7, 0.7, 0.2)),
        Some(0) => ("✅".to_string(), (0.3, 0.8, 0.4)),
        Some(c) => (format!("❌ {}", c), (0.9, 0.3, 0.3)),
    }
}

/// Get category icon for error detection
pub fn error_category_icon(category: &str) -> &'static str {
    match category {
        "permission" => "🔒",
        "not_found" => "🔍",
        "compilation" => "🔨",
        "runtime" => "⚡",
        "import" => "📦",
        "network" => "🌐",
        "compat" => "🔗",
        "nix" => "❄️",
        _ => "⚠️",
    }
}

// ══════════════════════════════════════════════════════════════════
// Integration Notes
// ══════════════════════════════════════════════════════════════════

/*
To integrate this panel into main.rs:

1. Add to lib.rs:
   pub mod subagents;

2. Add View variant:
   enum View {
       // ... existing
       SubAgents,
   }

3. Add state field to App:
   subagent_state: subagents::SubagentPanelState,

4. Add message handling:
   Message::SubagentAction(subagents::SubagentMessage)

5. Add view function call:
   View::SubAgents => view_subagents(&self.subagent_state),

6. Add navigation item in sidebar:
   nav_item("Sub-agents", "🤖", View::SubAgents)

7. Add subscription for periodic refresh:
   Subscription::run(...)

See the Sessions view for a similar pattern.
*/
