//! Continuum Studio UI - iced/COSMIC Edition
//!
//! This is the iced-based UI for Continuum Studio, designed for integration
//! with the COSMIC desktop ecosystem.

use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length, Task, Theme};
use std::path::PathBuf;
use tokio::sync::mpsc;

use continuum_studio_iced::core::{
    spawn_core_connection, AuthProfile, AuthState, AuthStatus, ConnectionState, CoreRequest, 
    CoreResponse, CursorVersion, VersionStatus, Workspace, DEFAULT_SOCKET_PATH,
};
use std::collections::HashMap;
use continuum_studio_iced::log_capture::{init_logger, LogBuffer, LogEntry};
use continuum_studio_iced::services::{ServiceConfig, ServiceInfo, ServiceManager, ServiceStatus};
use continuum_studio_iced::monitoring::{SessionMonitor, SessionMetrics, HealthStatus, DashboardData};
use continuum_studio_iced::sessions::{CursorSession, SessionTracker};
use continuum_studio_iced::settings::{CosmicPreset, Settings, ThemePreference};
use continuum_studio_iced::chat_pipeline::{
    ChatApiClient, ChatPipelineState, ChatSubView, ChatStats, Conversation, ConversationDetail,
    SearchResult, Topic, ScanLocations, fmt_num, fmt_bytes, truncate,
};
use continuum_studio_iced::theme::{AppColors, CosmicThemePreset};
use continuum_studio_iced::updater::{UpdateChannel, UpdateChecker, UpdateInfo, ForgeType, InstallationType, SelfUpdater};
use continuum_studio_iced::subagents::{SubagentPanelState, SubagentMessage, MonitorStats, CommandRecord};

/// Async task to check for updates
async fn check_for_updates_task() -> Result<Option<UpdateInfo>, String> {
    let settings = Settings::load();
    let checker = UpdateChecker::new();
    checker.check_for_updates(&settings.updates).await
}

/// Compute disk usage for installed versions (client-side filesystem scan)
async fn compute_disk_usage(
    versions: Vec<String>,
    running_versions: Vec<String>,
) -> Vec<VersionDiskUsage> {
    let home = dirs::home_dir().unwrap_or_default();
    let versions_dir = home.join(".cursor-versions");

    let mut results = Vec::new();
    for version in versions {
        let appimage_path = versions_dir.join(format!("Cursor-{}-x86_64.AppImage", version));
        let data_dir = home.join(format!(".cursor-{}", version));
        let extensions_dir = data_dir.join("extensions");

        let appimage_size = tokio::fs::metadata(&appimage_path)
            .await
            .map(|m| m.len())
            .unwrap_or(0);

        let data_dir_size = if data_dir.exists() {
            dir_size_recursive(&data_dir).await
        } else {
            0
        };

        let extensions_size = if extensions_dir.exists() {
            dir_size_recursive(&extensions_dir).await
        } else {
            0
        };

        // data_dir_size includes extensions, get the data-only portion
        let data_only = data_dir_size.saturating_sub(extensions_size);
        let total = appimage_size + data_dir_size;

        // Check last-used via state.vscdb or data dir mtime
        let last_used = get_last_used_time(&data_dir).await;

        let is_running = running_versions.contains(&version);

        results.push(VersionDiskUsage {
            version,
            appimage_size,
            data_dir_size: data_only,
            extensions_size,
            total_size: total,
            has_data_dir: data_dir.exists(),
            has_extensions: extensions_dir.exists(),
            last_used,
            is_running,
        });
    }

    // Sort by total size descending
    results.sort_by(|a, b| b.total_size.cmp(&a.total_size));
    results
}

/// Recursively compute directory size using `du -sb` for efficiency
async fn dir_size_recursive(path: &std::path::Path) -> u64 {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || {
        std::process::Command::new("du")
            .args(["-sb", &path.to_string_lossy()])
            .output()
            .ok()
            .and_then(|output| {
                if output.status.success() {
                    String::from_utf8_lossy(&output.stdout)
                        .split('\t')
                        .next()
                        .and_then(|s| s.trim().parse::<u64>().ok())
                } else {
                    None
                }
            })
            .unwrap_or(0)
    })
    .await
    .unwrap_or(0)
}

/// Get last-used time from state.vscdb or data dir mtime
async fn get_last_used_time(data_dir: &std::path::Path) -> Option<String> {
    let state_db = data_dir.join("User").join("globalStorage").join("state.vscdb");

    let mtime = if state_db.exists() {
        tokio::fs::metadata(&state_db).await.ok()
    } else if data_dir.exists() {
        tokio::fs::metadata(data_dir).await.ok()
    } else {
        None
    };

    mtime.and_then(|m| {
        m.modified().ok().map(|t| {
            let datetime: chrono::DateTime<chrono::Local> = t.into();
            datetime.format("%Y-%m-%d %H:%M").to_string()
        })
    })
}

/// Format bytes into human-readable string
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

fn main() -> iced::Result {
    // Use capture logger instead of env_logger
    let log_level = std::env::var("RUST_LOG")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(log::LevelFilter::Info);
    let log_buffer = init_logger(log_level);

    iced::application(move || ContinuumStudio::new(log_buffer.clone()), update, view)
        .title("Continuum Studio")
        .theme(|state: &ContinuumStudio| state.theme.clone())
        .window_size(iced::Size::new(1280.0, 800.0))
        .antialiasing(true)
        .subscription(|state| {
            // Batch multiple subscriptions together
            iced::Subscription::batch([
                // Core IPC connection
                core_subscription(),
                // Sub-agent monitor polling (only when on Cursor view + SubAgents tab)
                subagent_subscription(state.current_view == View::Cursor && state.cursor_tab == CursorTab::SubAgents),
            ])
        })
        .run()
}

/// Subscription to handle Core IPC connection
fn core_subscription() -> iced::Subscription<Message> {
    iced::Subscription::run(core_worker)
}

/// Core connection worker that yields Messages
fn core_worker() -> impl iced::futures::Stream<Item = Message> {
    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let socket_path = PathBuf::from(DEFAULT_SOCKET_PATH);

            // Spawn the Core connection
            let (request_tx, mut response_rx, mut state_rx) = spawn_core_connection(socket_path);

            // Send the request sender to the app
            let _ = output.send(Message::CoreConnected(request_tx)).await;

            loop {
                tokio::select! {
                    // Handle connection state changes
                    Some(state) = state_rx.recv() => {
                        let _ = output.send(Message::CoreConnectionStateChanged(state)).await;
                    }
                    // Handle responses from Core
                    Some(response) = response_rx.recv() => {
                        log::debug!("Forwarding CoreResponse to app: {:?}", std::mem::discriminant(&response));
                        let _ = output.send(Message::CoreResponse(response)).await;
                    }
                }
            }
        },
    )
}

/// Subscription for sub-agent monitor polling
/// Only active when the SubAgents view is visible
fn subagent_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    
    // Poll every 2 seconds when view is active
    iced::time::every(std::time::Duration::from_secs(2)).map(|_| {
        Message::SubagentAction(SubagentMessage::Refresh)
    })
}

/// Derive iced Theme from Settings
fn derive_theme(settings: &Settings) -> Theme {
    match settings.theme {
        ThemePreference::Dark => Theme::Dark,
        ThemePreference::Light => Theme::Light,
        ThemePreference::System => Theme::Dark, // TODO: Detect system theme
        ThemePreference::Cosmic => {
            // Convert CosmicPreset to CosmicThemePreset and get theme
            let cosmic_preset = match settings.cosmic_preset {
                CosmicPreset::Dark => CosmicThemePreset::Dark,
                CosmicPreset::Light => CosmicThemePreset::Light,
                CosmicPreset::PopOrange => CosmicThemePreset::PopOrange,
                CosmicPreset::WarmAmber => CosmicThemePreset::WarmAmber,
                CosmicPreset::CoolBlue => CosmicThemePreset::CoolBlue,
                CosmicPreset::Mint => CosmicThemePreset::Mint,
            };
            cosmic_preset.to_iced_theme()
        }
    }
}

/// Boot function for iced application
impl ContinuumStudio {
    fn new(log_buffer: LogBuffer) -> (Self, Task<Message>) {
        // Load settings
        let settings = Settings::load();

        // Derive theme from settings
        let theme = derive_theme(&settings);

        // Start with empty version list - real versions come from Core connection
        let versions: Vec<CursorVersion> = vec![];

        log::info!(
            "Loaded settings: theme={:?}, socket={}",
            settings.theme,
            settings.core_socket_path
        );

        // Check if we should check for updates on startup
        let check_updates = settings.updates.should_check();

        // Initialize service manager and get initial service status
        let service_manager = ServiceManager::new(ServiceConfig::default());
        let services = service_manager.get_services();

        // Check if Core is not running - we'll auto-start it if enabled
        let core_running = service_manager.is_core_running();
        let should_auto_start = settings.auto_start_services && !core_running;

        if should_auto_start {
            log::info!("Core not running - will attempt auto-start");
        } else if !settings.auto_start_services {
            log::info!("Auto-start services disabled in settings");
        }

        // Build startup tasks
        let mut tasks = Vec::new();

        if check_updates {
            tasks.push(Task::perform(check_for_updates_task(), Message::UpdateCheckResult));
        }

        // Auto-start Core if not running
        if should_auto_start {
            tasks.push(Task::perform(
                async {
                    log::info!("Auto-starting Elixir Core...");
                    continuum_studio_iced::services::start_core_auto().await
                },
                |result| Message::ServiceAction(ServiceMessage::ServiceStartResult(
                    "Elixir Core".to_string(),
                    result,
                )),
            ));
        }

        let startup_task = if tasks.is_empty() {
            Task::none()
        } else {
            Task::batch(tasks)
        };

        (
            Self {
                settings,
                theme,
                connection_state: ConnectionState::Disconnected,
                current_view: View::Dashboard,
                cursor_tab: CursorTab::default(),
                versions,
                workspaces: vec![],
                workspace_files: vec![],
                workspace_files_loading: false,
                core_tx: None,
                settings_dirty: false,
                available_update: None,
                checking_updates: check_updates,
                log_buffer,
                log_filter: log::Level::Info,
                colors: AppColors::dark(), // Use dark theme colors by default
                services,
                service_manager,
                session_tracker: SessionTracker::new(),
                cursor_sessions: Vec::new(),
                session_monitor: SessionMonitor::new(),
                session_metrics: Vec::new(),
                dashboard_data: None,
                border_overlay_active: false,
                auth_statuses: HashMap::new(),
                auth_profiles: Vec::new(),
                chat_pipeline: ChatPipelineState::default(),
                subagent_state: SubagentPanelState::default(),
                storage_disk_usage: Vec::new(),
                storage_selected: std::collections::HashSet::new(),
                storage_loading: false,
            },
            startup_task,
        )
    }
}

/// Main application state
struct ContinuumStudio {
    /// Application settings
    settings: Settings,
    /// Current theme (derived from settings)
    theme: Theme,
    /// Connection state to Elixir Core
    connection_state: ConnectionState,
    /// Current page/view
    current_view: View,
    /// Current sub-tab within Cursor view
    cursor_tab: CursorTab,
    /// Available Cursor versions
    versions: Vec<CursorVersion>,
    /// Tracked workspaces
    workspaces: Vec<Workspace>,
    /// Discovered .code-workspace files
    workspace_files: Vec<CodeWorkspaceFile>,
    /// Whether workspace file scan is in progress
    workspace_files_loading: bool,
    /// Core request sender
    core_tx: Option<mpsc::Sender<CoreRequest>>,
    /// Settings have been modified
    settings_dirty: bool,
    /// Available update info
    available_update: Option<UpdateInfo>,
    /// Update check in progress
    checking_updates: bool,
    /// Log buffer for in-app log viewer
    log_buffer: LogBuffer,
    /// Current log filter level
    log_filter: log::Level,
    /// Theme colors for consistent styling
    colors: AppColors,
    /// Service information
    services: Vec<ServiceInfo>,
    /// Service manager
    service_manager: ServiceManager,
    /// Session tracker for running Cursor instances
    session_tracker: SessionTracker,
    /// Detected Cursor sessions
    cursor_sessions: Vec<CursorSession>,
    /// Session monitor for real-time metrics
    session_monitor: SessionMonitor,
    /// Latest session metrics
    session_metrics: Vec<SessionMetrics>,
    /// Dashboard aggregate data
    dashboard_data: Option<DashboardData>,
    /// Whether colored border overlay is active on windows
    border_overlay_active: bool,
    /// Auth status for each version (keyed by version string)
    auth_statuses: HashMap<String, AuthStatus>,
    /// Stored auth profiles (Phase 2)
    auth_profiles: Vec<AuthProfile>,
    /// Chat pipeline state (Synapsix integration)
    chat_pipeline: ChatPipelineState,
    /// Sub-agent monitoring state
    subagent_state: SubagentPanelState,
    /// Detailed disk usage per version (for Storage view)
    storage_disk_usage: Vec<VersionDiskUsage>,
    /// Versions selected for batch cleanup
    storage_selected: std::collections::HashSet<String>,
    /// Whether storage data is loading
    storage_loading: bool,
}

/// Available views in the application
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum View {
    Dashboard,
    Cursor,       // Parent view with sub-tabs
    ChatPipeline,
    Services,
    Storage,
    Settings,
    Logs,
}

/// Sub-tabs within the Cursor view
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum CursorTab {
    #[default]
    Sessions,     // Running Cursor instances
    SubAgents,    // Sub-agent monitoring  
    Auth,         // Authentication management
    Versions,     // Version management
    Workspaces,   // .code-workspace management
}

/// Application messages (Elm architecture)
#[derive(Debug, Clone)]
enum Message {
    /// Navigation
    NavigateTo(View),
    /// Change Cursor sub-tab
    CursorTabChange(CursorTab),
    /// Core connected with request sender
    CoreConnected(mpsc::Sender<CoreRequest>),
    /// Core connection state changed
    CoreConnectionStateChanged(ConnectionState),
    /// Theme preference changed
    ThemePreferenceChanged(ThemePreference),
    /// COSMIC preset changed
    CosmicPresetChanged(CosmicPreset),
    /// Cursor version management
    CursorAction(CursorMessage),
    /// Workspace management
    WorkspaceAction(WorkspaceMessage),
    /// Log viewer actions
    LogAction(LogMessage),
    /// Service management actions
    ServiceAction(ServiceMessage),
    /// Session management actions
    SessionAction(SessionMessage),
    /// Auth management actions
    AuthAction(AuthMessage),
    /// Versions updated from Core
    VersionsUpdated(Vec<CursorVersion>),
    /// Core response received
    CoreResponse(CoreResponse),
    /// Settings action
    SettingsAction(SettingsMessage),
    /// Storage management action
    StorageAction(StorageMessage),
    /// Update check result
    UpdateCheckResult(Result<Option<UpdateInfo>, String>),
    /// Chat pipeline actions
    ChatPipelineAction(ChatPipelineMsg),
    /// Sub-agent monitoring actions
    SubagentAction(SubagentMessage),
}

/// Chat pipeline sub-messages
#[derive(Debug, Clone)]
enum ChatPipelineMsg {
    /// Switch sub-view within chat pipeline
    SwitchSubView(ChatSubView),
    /// Health check result
    HealthChecked(Result<bool, String>),
    /// Stats loaded
    StatsLoaded(Result<ChatStats, String>),
    /// Conversations loaded
    ConversationsLoaded(Result<Vec<Conversation>, String>),
    /// Single conversation detail loaded
    ConversationLoaded(Result<ConversationDetail, String>),
    /// Topics loaded
    TopicsLoaded(Result<Vec<Topic>, String>),
    /// Search results
    SearchCompleted(Result<Vec<SearchResult>, String>),
    /// Scan locations loaded
    LocationsLoaded(Result<ScanLocations, String>),
    /// Search query text changed
    SearchQueryChanged(String),
    /// Trigger search
    DoSearch,
    /// Select a conversation to view
    SelectConversation(String),
    /// Go back from conversation detail
    BackToList,
    /// Batch ingest completed
    BatchIngestDone(Result<String, String>),
    /// Summarize pending completed
    SummarizeDone(Result<String, String>),
    /// Cluster topics completed
    ClusterDone(Result<String, String>),
    /// Import orphaned completed
    ImportOrphanedDone(Result<String, String>),
    /// Trigger batch ingest
    DoBatchIngest,
    /// Trigger summarize pending
    DoSummarizePending,
    /// Trigger cluster topics
    DoClusterTopics,
    /// Trigger import orphaned
    DoImportOrphaned,
    /// Refresh all data
    RefreshAll,
    /// Clear error
    ClearError,
    /// Clear action result
    ClearActionResult,
    /// Toggle a specific message expansion in conversation detail
    ToggleMessageExpand(usize),
    /// Expand/collapse all messages
    ToggleShowFull,
}

/// Settings-related messages
#[derive(Debug, Clone)]
enum SettingsMessage {
    SaveSettings,
    ToggleAutoConnect,
    ToggleAutoStartServices,
    ToggleNotifications,
    SetUpdateChannel(UpdateChannel),
    SetForgeType(ForgeType),
    ToggleAutoCheckUpdates,
    CheckForUpdates,
    ToggleSynapsixDialogRouting,
}

/// Storage management messages
#[derive(Debug, Clone)]
enum StorageMessage {
    /// Refresh disk usage for all installed versions
    RefreshDiskUsage,
    /// Disk usage data loaded (computed client-side)
    DiskUsageLoaded(Vec<VersionDiskUsage>),
    /// Toggle selection of a version for cleanup
    ToggleVersionSelect(String),
    /// Select all versions
    SelectAll,
    /// Deselect all versions
    DeselectAll,
    /// Cleanup selected versions with given mode
    CleanupSelected(CleanupMode),
    /// Cleanup completed
    CleanupComplete(Result<Vec<String>, String>),
}

/// Cleanup mode for version removal
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CleanupMode {
    /// Remove AppImage only
    AppImageOnly,
    /// Remove AppImage and data directory
    AppImageAndData,
    /// Remove AppImage and data, but preserve auth profile first
    KeepAuth,
}

/// Detailed disk usage info for a single Cursor version (client-side computed)
#[derive(Debug, Clone)]
struct VersionDiskUsage {
    version: String,
    appimage_size: u64,
    data_dir_size: u64,
    extensions_size: u64,
    total_size: u64,
    has_data_dir: bool,
    has_extensions: bool,
    last_used: Option<String>,
    is_running: bool,
}

/// Cursor-related messages
#[derive(Debug, Clone)]
enum CursorMessage {
    RefreshVersions,
    LaunchVersion(String),
    InstallVersion(String),
    UninstallVersion(String),
    /// Extract auth profile from a version
    ExtractAuth(String),
    /// Apply auth from the most recent profile to a target version
    ApplyAuthFromLatest(String),
}

/// Discovered .code-workspace file
#[derive(Debug, Clone, Default)]
struct CodeWorkspaceFile {
    /// Full path to the .code-workspace file
    path: String,
    /// Display name (derived from filename)
    name: String,
    /// Folders included in this workspace
    folders: Vec<String>,
    /// Last modification time
    modified: Option<String>,
}

/// Workspace-related messages
#[derive(Debug, Clone)]
enum WorkspaceMessage {
    RefreshWorkspaces,
    TogglePinned(String),
    RefreshGitStats(String),
    OpenInCursor(String, String), // workspace_id, version
    /// Scan for .code-workspace files
    ScanWorkspaceFiles,
    /// Results from scanning
    WorkspaceFilesScanned(Vec<CodeWorkspaceFile>),
    /// Open a .code-workspace file in Cursor
    OpenWorkspaceFile(String), // path to workspace file
}

/// Log viewer messages
#[derive(Debug, Clone)]
enum LogMessage {
    SetFilter(log::Level),
    CopyLogs,
    ClearLogs,
}

/// Service management messages
#[derive(Debug, Clone)]
enum ServiceMessage {
    /// Refresh service status
    RefreshServices,
    /// Start a specific service
    StartService(String),
    /// Start all services
    StartAllServices,
    /// Copy command to clipboard
    CopyCommand(String),
    /// Service start result
    ServiceStartResult(String, Result<(), String>),
}

/// Session management messages
#[derive(Debug, Clone)]
enum SessionMessage {
    /// Scan for running Cursor processes
    RefreshSessions,
    /// Sessions found
    SessionsFound(Vec<CursorSession>),
    /// Collect metrics for all sessions
    CollectMetrics,
    /// Metrics collected
    MetricsCollected(Vec<SessionMetrics>),
    /// Flash-identify a specific instance (highlight its windows briefly)
    FlashIdentify(u32),
    /// Toggle persistent colored border overlay for all instances
    ToggleBorderOverlay,
}

/// Auth management messages
#[derive(Debug, Clone)]
enum AuthMessage {
    /// Refresh auth statuses for all installed versions
    RefreshStatuses,
    /// Extract auth profile from a specific version
    ExtractFromVersion(String),
    /// Apply a profile to a target version
    ApplyProfile { profile_id: String, target_version: String },
    /// Delete a stored profile
    DeleteProfile(String),
    /// Refresh profiles list
    RefreshProfiles,
}

fn update(state: &mut ContinuumStudio, message: Message) -> Task<Message> {
    match message {
        Message::NavigateTo(view) => {
            state.current_view = view;
            // Auto-fetch data when navigating to Chat Pipeline
            if view == View::ChatPipeline && state.chat_pipeline.stats.is_none() {
                return Task::perform(
                    async { ChatPipelineMsg::RefreshAll },
                    Message::ChatPipelineAction,
                );
            }
        }
        Message::CursorTabChange(tab) => {
            state.cursor_tab = tab;
            // Trigger sub-agent refresh when switching to that tab
            if tab == CursorTab::SubAgents {
                return Task::perform(
                    async { SubagentMessage::Refresh },
                    Message::SubagentAction,
                );
            }
        }
        Message::CoreConnected(tx) => {
            // Only process if we don't already have a connection
            if state.core_tx.is_none() {
                log::info!("Core connection established, requesting versions...");
                state.core_tx = Some(tx.clone());
                // Request versions immediately
                return Task::perform(
                    async move {
                        log::info!("Sending GetVersions request to Core");
                        match tx.send(CoreRequest::GetVersions).await {
                            Ok(_) => log::info!("GetVersions request sent successfully"),
                            Err(e) => log::error!("Failed to send GetVersions: {}", e),
                        }
                    },
                    |_| Message::CursorAction(CursorMessage::RefreshVersions),
                );
            } else {
                log::debug!("CoreConnected received but already connected, ignoring");
            }
        }
        Message::CoreConnectionStateChanged(new_state) => {
            let was_connected = matches!(state.connection_state, ConnectionState::Connected);
            let is_connected = matches!(new_state, ConnectionState::Connected);
            state.connection_state = new_state.clone();

            log::info!("Core connection state: {:?}", new_state);

            // NOTE: Don't clear core_tx on disconnect!
            // The mpsc channel between the app and the worker is still valid.
            // The worker handles socket reconnection internally.
            // Clearing core_tx would break commands after reconnection.

            // Full state refresh on (re)connect for hot restart resilience
            if is_connected && !was_connected {
                log::info!("Connection (re)established - requesting full state refresh");
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            // Request all data to rebuild UI state
                            let _ = tx.send(CoreRequest::GetVersions).await;
                            let _ = tx.send(CoreRequest::GetInstalled).await;
                            let _ = tx.send(CoreRequest::GetWorkspaces { limit: None }).await;
                            let _ = tx.send(CoreRequest::GetAuthStatuses).await;
                            let _ = tx.send(CoreRequest::ListProfiles).await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                }
            }
        }
        Message::ThemePreferenceChanged(pref) => {
            state.settings.theme = pref;
            state.theme = derive_theme(&state.settings);
            state.settings_dirty = true;
        }
        Message::CosmicPresetChanged(preset) => {
            state.settings.cosmic_preset = preset;
            state.settings.theme = ThemePreference::Cosmic;
            state.theme = derive_theme(&state.settings);
            state.settings_dirty = true;
        }
        Message::SettingsAction(settings_msg) => match settings_msg {
            SettingsMessage::SaveSettings => {
                if let Err(e) = state.settings.save() {
                    log::error!("Failed to save settings: {}", e);
                }
                state.settings_dirty = false;
            }
            SettingsMessage::ToggleAutoConnect => {
                state.settings.auto_connect = !state.settings.auto_connect;
                state.settings_dirty = true;
            }
            SettingsMessage::ToggleAutoStartServices => {
                state.settings.auto_start_services = !state.settings.auto_start_services;
                state.settings_dirty = true;
            }
            SettingsMessage::ToggleNotifications => {
                state.settings.notify_new_versions = !state.settings.notify_new_versions;
                state.settings_dirty = true;
            }
            SettingsMessage::SetUpdateChannel(channel) => {
                state.settings.updates.channel = channel;
                state.settings_dirty = true;
                log::info!("Update channel changed to: {:?}", channel);
            }
            SettingsMessage::SetForgeType(forge_type) => {
                state.settings.updates.forge_type = forge_type;
                state.settings_dirty = true;
                log::info!("Forge type changed to: {:?}", forge_type);
            }
            SettingsMessage::ToggleAutoCheckUpdates => {
                state.settings.updates.auto_check = !state.settings.updates.auto_check;
                state.settings_dirty = true;
            }
            SettingsMessage::CheckForUpdates => {
                state.checking_updates = true;
                return Task::perform(check_for_updates_task(), Message::UpdateCheckResult);
            }
            SettingsMessage::ToggleSynapsixDialogRouting => {
                state.settings.synapsix_dialog_routing = !state.settings.synapsix_dialog_routing;
                state.settings_dirty = true;
                // Apply the routing change immediately
                match state.settings.apply_dialog_routing() {
                    Ok(results) => {
                        for r in &results {
                            log::info!("Dialog routing: {}", r);
                        }
                    }
                    Err(e) => log::error!("Failed to apply dialog routing: {}", e),
                }
            }
        },
        Message::StorageAction(storage_msg) => match storage_msg {
            StorageMessage::RefreshDiskUsage => {
                state.storage_loading = true;
                let versions: Vec<String> = state.versions.iter()
                    .filter(|v| v.status == VersionStatus::Installed || v.status == VersionStatus::Running)
                    .map(|v| v.version.clone())
                    .collect();
                let running_versions: Vec<String> = state.versions.iter()
                    .filter(|v| v.status == VersionStatus::Running)
                    .map(|v| v.version.clone())
                    .collect();
                return Task::perform(
                    async move {
                        compute_disk_usage(versions, running_versions).await
                    },
                    |data| Message::StorageAction(StorageMessage::DiskUsageLoaded(data)),
                );
            }
            StorageMessage::DiskUsageLoaded(data) => {
                state.storage_disk_usage = data;
                state.storage_loading = false;
            }
            StorageMessage::ToggleVersionSelect(version) => {
                if state.storage_selected.contains(&version) {
                    state.storage_selected.remove(&version);
                } else {
                    state.storage_selected.insert(version);
                }
            }
            StorageMessage::SelectAll => {
                for usage in &state.storage_disk_usage {
                    if !usage.is_running {
                        state.storage_selected.insert(usage.version.clone());
                    }
                }
            }
            StorageMessage::DeselectAll => {
                state.storage_selected.clear();
            }
            StorageMessage::CleanupSelected(mode) => {
                let selected: Vec<String> = state.storage_selected.iter().cloned().collect();
                if selected.is_empty() {
                    log::warn!("No versions selected for cleanup");
                    return Task::none();
                }
                let (remove_data, keep_auth) = match mode {
                    CleanupMode::AppImageOnly => (false, false),
                    CleanupMode::AppImageAndData => (true, false),
                    CleanupMode::KeepAuth => (true, true),
                };
                log::info!(
                    "Cleaning up {} versions (remove_data={}, keep_auth={}): {:?}",
                    selected.len(), remove_data, keep_auth, selected
                );
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::BatchUninstallVersions {
                                versions: selected.clone(),
                                remove_data,
                                keep_auth,
                            }).await;
                            Ok(selected)
                        },
                        |result: Result<Vec<String>, String>| {
                            Message::StorageAction(StorageMessage::CleanupComplete(result))
                        },
                    );
                }
            }
            StorageMessage::CleanupComplete(result) => {
                match result {
                    Ok(versions) => {
                        log::info!("Successfully cleaned up {} versions", versions.len());
                        state.storage_selected.clear();
                        // Refresh versions and disk usage
                        return Task::batch(vec![
                            Task::done(Message::CursorAction(CursorMessage::RefreshVersions)),
                            Task::done(Message::StorageAction(StorageMessage::RefreshDiskUsage)),
                        ]);
                    }
                    Err(e) => {
                        log::error!("Cleanup failed: {}", e);
                    }
                }
            }
        },
        Message::ChatPipelineAction(msg) => {
            return handle_chat_pipeline_message(state, msg);
        }
        Message::SubagentAction(msg) => {
            return handle_subagent_message(state, msg);
        }
        Message::UpdateCheckResult(result) => {
            state.checking_updates = false;
            state.settings.updates.mark_checked();
            
            match result {
                Ok(Some(update)) => {
                    log::info!("Update available: {} -> {}", 
                        state.settings.updates.current_version, 
                        update.version);
                    state.settings.updates.available_version = Some(update.version.clone());
                    state.settings.updates.update_notes = Some(update.notes.clone());
                    state.available_update = Some(update);
                }
                Ok(None) => {
                    log::info!("No updates available");
                    state.available_update = None;
                }
                Err(e) => {
                    log::error!("Update check failed: {}", e);
                }
            }
            
            // Auto-save the update check timestamp
            let _ = state.settings.save();
        }
        Message::CursorAction(cursor_msg) => match cursor_msg {
            CursorMessage::RefreshVersions => {
                log::info!("Refreshing Cursor versions...");
            }
            CursorMessage::LaunchVersion(version) => {
                log::info!("Launching Cursor version: {}", version);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::LaunchVersion { version, folder: None }).await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                }
            }
            CursorMessage::InstallVersion(version) => {
                log::info!("Installing Cursor version: {}", version);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::InstallVersion { version }).await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                }
            }
            CursorMessage::UninstallVersion(version) => {
                log::info!("Uninstalling Cursor version: {}", version);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::UninstallVersion { version }).await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                }
            }
            CursorMessage::ExtractAuth(version) => {
                log::info!("Extracting auth from Cursor version: {}", version);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::ExtractAuth { version }).await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                }
            }
            CursorMessage::ApplyAuthFromLatest(target_version) => {
                log::info!("Applying auth to Cursor version: {}", target_version);
                // Get the most recent profile's source version to use as source
                if let Some(profile) = state.auth_profiles.first() {
                    if let Some(source) = &profile.extracted_from {
                        let source = source.clone();
                        if let Some(tx) = &state.core_tx {
                            let tx = tx.clone();
                            return Task::perform(
                                async move {
                                    let _ = tx.send(CoreRequest::ApplyAuth { source, target: target_version }).await;
                                },
                                |_| Message::CursorAction(CursorMessage::RefreshVersions),
                            );
                        }
                    }
                } else {
                    log::warn!("No auth profiles available to apply");
                }
            }
        },
        Message::WorkspaceAction(ws_msg) => match ws_msg {
            WorkspaceMessage::RefreshWorkspaces => {
                log::info!("Refreshing workspaces...");
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::GetWorkspaces { limit: Some(50) }).await;
                        },
                        |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                    );
                }
            }
            WorkspaceMessage::TogglePinned(id) => {
                log::info!("Toggling pinned status for workspace: {}", id);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::ToggleWorkspacePinned { id }).await;
                        },
                        |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                    );
                }
            }
            WorkspaceMessage::RefreshGitStats(id) => {
                log::info!("Refreshing git stats for workspace: {}", id);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::RefreshWorkspaceGit { id }).await;
                        },
                        |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                    );
                }
            }
            WorkspaceMessage::OpenInCursor(ws_id, version) => {
                log::info!("Opening workspace {} in Cursor {}", ws_id, version);
                // Find workspace path
                if let Some(ws) = state.workspaces.iter().find(|w| w.id == ws_id) {
                    let folder = ws.path.clone();
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        return Task::perform(
                            async move {
                                let _ = tx.send(CoreRequest::LaunchVersion { version, folder: Some(folder) }).await;
                            },
                            |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                        );
                    }
                }
            }
            WorkspaceMessage::ScanWorkspaceFiles => {
                log::info!("Scanning for .code-workspace files...");
                state.workspace_files_loading = true;
                return Task::perform(
                    scan_workspace_files(),
                    |files| Message::WorkspaceAction(WorkspaceMessage::WorkspaceFilesScanned(files)),
                );
            }
            WorkspaceMessage::WorkspaceFilesScanned(files) => {
                log::info!("Found {} .code-workspace files", files.len());
                state.workspace_files = files;
                state.workspace_files_loading = false;
            }
            WorkspaceMessage::OpenWorkspaceFile(path) => {
                log::info!("Opening workspace file: {}", path);
                // Launch Cursor with the workspace file
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::LaunchVersion { 
                                version: "latest".to_string(), 
                                folder: Some(path) 
                            }).await;
                        },
                        |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                    );
                }
            }
        },
        Message::LogAction(log_msg) => match log_msg {
            LogMessage::SetFilter(level) => {
                state.log_filter = level;
            }
            LogMessage::CopyLogs => {
                let logs = state.log_buffer.format_all();
                return iced::clipboard::write(logs);
            }
            LogMessage::ClearLogs => {
                state.log_buffer.clear();
            }
        },
        Message::ServiceAction(service_msg) => match service_msg {
            ServiceMessage::RefreshServices => {
                state.services = state.service_manager.get_services();
            }
            ServiceMessage::StartService(name) => {
                log::info!("Starting service: {}", name);
                let name_clone = name.clone();
                if name.contains("Core") {
                    return Task::perform(
                        async move {
                            continuum_studio_iced::services::start_core_auto().await
                        },
                        move |result| Message::ServiceAction(ServiceMessage::ServiceStartResult(name_clone, result)),
                    );
                } else if name.contains("Dialog") {
                    return Task::perform(
                        async move {
                            continuum_studio_iced::services::start_dialog_auto().await
                        },
                        move |result| Message::ServiceAction(ServiceMessage::ServiceStartResult(name_clone, result)),
                    );
                }
            }
            ServiceMessage::StartAllServices => {
                log::info!("Starting all services");
                return Task::perform(
                    async move {
                        let config = ServiceConfig::default();
                        let manager = ServiceManager::new(config);
                        let _ = manager.start_all().await;
                    },
                    |_| Message::ServiceAction(ServiceMessage::RefreshServices),
                );
            }
            ServiceMessage::CopyCommand(cmd) => {
                return iced::clipboard::write(cmd);
            }
            ServiceMessage::ServiceStartResult(name, result) => {
                match result {
                    Ok(()) => log::info!("Service {} started successfully", name),
                    Err(e) => log::error!("Failed to start {}: {}", name, e),
                }
                // Refresh service status
                state.services = state.service_manager.get_services();
            }
        },
        Message::SessionAction(session_msg) => match session_msg {
            SessionMessage::RefreshSessions => {
                log::info!("Scanning for Cursor sessions...");
                let sessions = state.session_tracker.scan_processes();
                state.cursor_sessions = sessions.clone();
                log::info!("Found {} Cursor sessions", state.cursor_sessions.len());
                
                // Also collect metrics immediately
                let pids: Vec<u32> = sessions.iter().map(|s| s.pid).collect();
                let metrics = state.session_monitor.collect_all_metrics(&pids);
                state.session_metrics = metrics.clone();
                state.dashboard_data = Some(DashboardData::from_metrics(&metrics));
            }
            SessionMessage::SessionsFound(sessions) => {
                state.cursor_sessions = sessions;
            }
            SessionMessage::CollectMetrics => {
                let pids: Vec<u32> = state.cursor_sessions.iter().map(|s| s.pid).collect();
                let metrics = state.session_monitor.collect_all_metrics(&pids);
                state.session_metrics = metrics.clone();
                state.dashboard_data = Some(DashboardData::from_metrics(&metrics));
            }
            SessionMessage::MetricsCollected(metrics) => {
                state.session_metrics = metrics.clone();
                state.dashboard_data = Some(DashboardData::from_metrics(&metrics));
            }
            SessionMessage::FlashIdentify(pid) => {
                // Flash-identify: use kdotool to briefly activate each window for this instance
                let window_ids = state.session_tracker.get_window_ids_for_instance(pid);
                if !window_ids.is_empty() {
                    return Task::perform(
                        async move {
                            flash_instance_windows(&window_ids).await;
                        },
                        |_| Message::NavigateTo(View::Cursor),
                    );
                }
            }
            SessionMessage::ToggleBorderOverlay => {
                state.border_overlay_active = !state.border_overlay_active;
                let active = state.border_overlay_active;
                let sessions: Vec<(String, Vec<String>)> = state.cursor_sessions.iter()
                    .map(|s| {
                        let wids = s.windows.iter().map(|w| w.window_id.clone()).collect();
                        (s.color.clone(), wids)
                    })
                    .collect();
                if active {
                    return Task::perform(
                        async move {
                            apply_kwin_border_overlay(&sessions).await;
                        },
                        |_| Message::NavigateTo(View::Cursor),
                    );
                } else {
                    return Task::perform(
                        async move {
                            remove_kwin_border_overlay().await;
                        },
                        |_| Message::NavigateTo(View::Cursor),
                    );
                }
            }
        },
        Message::AuthAction(auth_msg) => match auth_msg {
            AuthMessage::RefreshStatuses => {
                log::info!("Refreshing auth statuses...");
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::GetAuthStatuses).await;
                            let _ = tx.send(CoreRequest::ListProfiles).await;
                        },
                        |_| Message::NavigateTo(View::Cursor), // Navigate to Auth tab, no loop
                    );
                }
            }
            AuthMessage::ExtractFromVersion(version) => {
                log::info!("Extracting auth from version: {}", version);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::ExtractAuth { version }).await;
                            // Also refresh profiles list after extraction
                            let _ = tx.send(CoreRequest::ListProfiles).await;
                        },
                        |_| Message::NavigateTo(View::Cursor),
                    );
                }
            }
            AuthMessage::ApplyProfile { profile_id: _, target_version } => {
                log::info!("Applying auth profile to version: {}", target_version);
                // Use the most recent profile's source version
                if let Some(profile) = state.auth_profiles.first() {
                    if let Some(source) = &profile.extracted_from {
                        let source = source.clone();
                        if let Some(tx) = &state.core_tx {
                            let tx = tx.clone();
                            return Task::perform(
                                async move {
                                    let _ = tx.send(CoreRequest::ApplyAuth { source, target: target_version }).await;
                                    // Refresh statuses after apply
                                    let _ = tx.send(CoreRequest::GetAuthStatuses).await;
                                },
                                |_| Message::NavigateTo(View::Cursor),
                            );
                        }
                    }
                } else {
                    log::warn!("No profiles available to apply");
                }
            }
            AuthMessage::DeleteProfile(_profile_id) => {
                log::info!("Delete profile not yet implemented");
                // TODO: Add delete profile to Core API
            }
            AuthMessage::RefreshProfiles => {
                log::info!("Refreshing profiles list...");
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::ListProfiles).await;
                        },
                        |_| Message::NavigateTo(View::Cursor),
                    );
                }
            }
        },
        Message::VersionsUpdated(versions) => {
            state.versions = versions;
        }
        Message::CoreResponse(response) => {
            log::info!("CoreResponse received: {:?}", std::mem::discriminant(&response));
            match response {
                CoreResponse::Versions(versions) => {
                    log::info!("Got {} versions from Core!", versions.len());
                    state.versions = versions;
                    // Also request auth statuses for installed versions
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        return Task::perform(
                            async move {
                                let _ = tx.send(CoreRequest::GetAuthStatuses).await;
                            },
                            |_| Message::CursorAction(CursorMessage::RefreshVersions),
                        );
                    }
                }
                CoreResponse::InstalledVersions(installed) => {
                    // Update status of versions that are installed
                    for inst in installed {
                        if let Some(v) = state.versions.iter_mut().find(|v| v.version == inst.version) {
                            v.status = VersionStatus::Installed;
                        }
                    }
                }
                CoreResponse::LaunchResult { success, message } => {
                    if success {
                        log::info!("Launch success: {}", message);
                    } else {
                        log::error!("Launch failed: {}", message);
                    }
                }
                CoreResponse::VersionRunning { version, data_dir } => {
                    log::info!("Version {} running with data dir: {}", version, data_dir);
                    // Update status to Running
                    if let Some(v) = state.versions.iter_mut().find(|v| v.version == version) {
                        v.status = VersionStatus::Running;
                    }
                }
                CoreResponse::DownloadStarted { version } => {
                    log::info!("Download started for version: {}", version);
                    // Update status to Downloading
                    if let Some(v) = state.versions.iter_mut().find(|v| v.version == version) {
                        v.status = VersionStatus::Downloading;
                    }
                }
                CoreResponse::DownloadCompleted { version, path } => {
                    log::info!("Download completed for version {} at {}", version, path);
                    // Update status to Installed
                    if let Some(v) = state.versions.iter_mut().find(|v| v.version == version) {
                        v.status = VersionStatus::Installed;
                        v.installed = true;
                    }
                }
                CoreResponse::DownloadFailed { version, error } => {
                    log::error!("Download failed for version {}: {}", version, error);
                    // Reset status back to Available
                    if let Some(v) = state.versions.iter_mut().find(|v| v.version == version) {
                        v.status = VersionStatus::Available;
                    }
                }
                CoreResponse::Stats(stats) => {
                    log::info!("Version stats: {} installed, {} total", 
                        stats.installed_count, stats.total_versions);
                }
                CoreResponse::Pong => {
                    log::debug!("Received pong from Core");
                }
                CoreResponse::Error { message } => {
                    log::error!("Core error: {}", message);
                }
                CoreResponse::Sessions(_) => {
                    // Handle sessions in future
                }
                CoreResponse::Workspaces(workspaces) => {
                    log::info!("Received {} workspaces", workspaces.len());
                    state.workspaces = workspaces;
                }
                CoreResponse::WorkspaceRegistered(workspace) => {
                    log::info!("Workspace registered: {}", workspace.name);
                    // Add or update in list
                    if let Some(existing) = state.workspaces.iter_mut().find(|w| w.id == workspace.id) {
                        *existing = workspace;
                    } else {
                        state.workspaces.insert(0, workspace);
                    }
                }
                CoreResponse::WorkspacePinned { id, pinned } => {
                    log::info!("Workspace {} pinned: {}", id, pinned);
                    if let Some(ws) = state.workspaces.iter_mut().find(|w| w.id == id) {
                        ws.pinned = pinned;
                    }
                }
                CoreResponse::WorkspaceGitStats { id, git_stats } => {
                    log::info!("Updated git stats for workspace {}", id);
                    if let Some(ws) = state.workspaces.iter_mut().find(|w| w.id == id) {
                        ws.git_stats = git_stats;
                    }
                }
                CoreResponse::AuthStatus(auth_status) => {
                    log::info!("Auth status for {}: {:?}", auth_status.version, auth_status.status);
                    state.auth_statuses.insert(auth_status.version.clone(), auth_status);
                }
                CoreResponse::AuthStatuses(statuses) => {
                    log::info!("Received auth statuses for {} versions", statuses.len());
                    for status in statuses {
                        state.auth_statuses.insert(status.version.clone(), status);
                    }
                }
                // Phase 2: Auth profile extraction/application
                CoreResponse::AuthExtracted(profile) => {
                    log::info!("Auth extracted from {:?}: {} ({})", 
                        profile.extracted_from, 
                        profile.email.as_deref().unwrap_or("no email"),
                        profile.membership);
                    // Store profile for later use
                    state.auth_profiles.push(profile);
                }
                CoreResponse::AuthExtractFailed { version, error } => {
                    log::error!("Failed to extract auth from {}: {}", version, error);
                    // Could show error notification to user
                }
                CoreResponse::AuthApplied { source, target, email } => {
                    log::info!("Auth applied from {} to {} ({})", source, target, email);
                    // Refresh auth status for target version
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        let target = target.clone();
                        return Task::perform(
                            async move {
                                let _ = tx.send(CoreRequest::GetAuthStatus { version: target }).await;
                            },
                            |_| Message::CursorAction(CursorMessage::RefreshVersions),
                        );
                    }
                }
                CoreResponse::AuthApplyFailed { source, target, error } => {
                    log::error!("Failed to apply auth from {} to {}: {}", source, target, error);
                    // Could show error notification to user
                }
                CoreResponse::AuthProfiles(profiles) => {
                    log::info!("Received {} auth profiles", profiles.len());
                    state.auth_profiles = profiles;
                }
            }
        }
    }
    Task::none()
}

fn view(state: &ContinuumStudio) -> Element<Message> {
    let sidebar = sidebar(state);
    let content = match state.current_view {
        View::Dashboard => view_dashboard(state),
        View::Cursor => view_cursor(state),
        View::ChatPipeline => view_chat_pipeline(state),
        View::Services => view_services(state),
        View::Storage => view_storage(state),
        View::Settings => view_settings(state),
        View::Logs => view_logs(state),
    };

    let main_content = row![
        sidebar,
        container(content)
            .width(Length::Fill)
            .height(Length::Fill)
            .padding(20),
    ];

    container(main_content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}

/// Sidebar navigation with COSMIC-inspired styling
fn sidebar(state: &ContinuumStudio) -> Element<Message> {
    let current = state.current_view;

    let (status_text, status_color) = match state.connection_state {
        ConnectionState::Connected => ("● Connected", iced::Color::from_rgb(0.25, 0.75, 0.35)),
        ConnectionState::Connecting => ("◐ Connecting...", iced::Color::from_rgb(0.75, 0.65, 0.25)),
        ConnectionState::Reconnecting { attempt: _ } => {
            ("◐ Reconnecting...", iced::Color::from_rgb(0.75, 0.55, 0.25))
        }
        ConnectionState::Disconnected => {
            ("○ Disconnected", iced::Color::from_rgb(0.75, 0.35, 0.35))
        }
    };

    let connection_status = text(status_text).color(status_color);

    // Logo/header section
    let header = column![
        text("Continuum").size(22),
        text("Studio")
            .size(14)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
    ]
    .spacing(2);

    // Version and connection status section
    let status_section = column![row![
        text("v0.1.0")
            .size(11)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(Length::Fill),
        connection_status.size(10),
    ]
    .align_y(Alignment::Center),]
    .padding([8, 0]);

    // Navigation section with better visual hierarchy
    let nav_section = column![
        text("Navigation")
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().height(8),
        nav_button("🏠  Dashboard", View::Dashboard, current),
        nav_button("🖥️  Cursor", View::Cursor, current),
        nav_button("🧠  Chats", View::ChatPipeline, current),
        nav_button("🔧  Services", View::Services, current),
        nav_button("💾  Storage", View::Storage, current),
        Space::new().height(Length::Fill),
        nav_button("📋  Logs", View::Logs, current),
        nav_button("⚙️  Settings", View::Settings, current),
    ]
    .spacing(4);

    container(
        column![
            header,
            status_section,
            container(Space::new().height(1))
                .width(Length::Fill)
                .style(|_theme| container::Style {
                    background: Some(iced::Background::Color(iced::Color::from_rgb(
                        0.3, 0.3, 0.3
                    ))),
                    ..container::Style::default()
                }),
            Space::new().height(16),
            nav_section,
        ]
        .spacing(0)
        .padding(16)
        .width(220),
    )
    .height(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.12,
        ))),
        border: iced::Border {
            radius: 0.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.2),
        },
        ..container::Style::default()
    })
    .into()
}

/// Create a navigation button with active state styling
fn nav_button(label: &'static str, view: View, current: View) -> Element<'static, Message> {
    let is_active = view == current;

    let btn = button(text(label).size(13).color(if is_active {
        iced::Color::WHITE
    } else {
        iced::Color::from_rgb(0.75, 0.75, 0.75)
    }))
    .padding([10, 14])
    .width(Length::Fill)
    .on_press(Message::NavigateTo(view))
    .style(move |_theme, status| {
        let bg_color = if is_active {
            match status {
                button::Status::Active => iced::Color::from_rgb(0.25, 0.25, 0.25),
                button::Status::Hovered => iced::Color::from_rgb(0.28, 0.28, 0.28),
                button::Status::Pressed => iced::Color::from_rgb(0.22, 0.22, 0.22),
                button::Status::Disabled => iced::Color::from_rgb(0.2, 0.2, 0.2),
            }
        } else {
            match status {
                button::Status::Active => iced::Color::TRANSPARENT,
                button::Status::Hovered => iced::Color::from_rgb(0.18, 0.18, 0.18),
                button::Status::Pressed => iced::Color::from_rgb(0.15, 0.15, 0.15),
                button::Status::Disabled => iced::Color::TRANSPARENT,
            }
        };

        button::Style {
            background: Some(iced::Background::Color(bg_color)),
            text_color: if is_active {
                iced::Color::WHITE
            } else {
                iced::Color::from_rgb(0.75, 0.75, 0.75)
            },
            border: iced::Border {
                radius: 8.0.into(),
                width: 0.0,
                color: iced::Color::TRANSPARENT,
            },
            shadow: iced::Shadow::default(),
            snap: false,
        }
    });

    btn.into()
}

/// Dashboard view with cards
fn view_dashboard(state: &ContinuumStudio) -> Element<Message> {
    let colors = &state.colors;
    
    // Status card
    let status_card = card(
        column![
            text("System Status")
                .size(14)
                .color(colors.text_secondary),
            Space::new().height(12),
            row![
                text("Core Connection:").size(13),
                Space::new().width(Length::Fill),
                text(match state.connection_state {
                    ConnectionState::Connected => "Connected",
                    ConnectionState::Connecting => "Connecting...",
                    ConnectionState::Reconnecting { .. } => "Reconnecting...",
                    ConnectionState::Disconnected => "Disconnected",
                })
                .size(13)
                .color(match state.connection_state {
                    ConnectionState::Connected => colors.status_connected,
                    ConnectionState::Connecting | ConnectionState::Reconnecting { .. } => colors.status_connecting,
                    ConnectionState::Disconnected => colors.status_disconnected,
                }),
            ],
            Space::new().height(4),
            row![
                text("Versions Loaded:").size(13),
                Space::new().width(Length::Fill),
                text(format!("{}", state.versions.len())).size(13),
            ],
        ]
        .spacing(4),
    );

    // Quick actions card
    let actions_card = card(
        column![
            text("Quick Actions")
                .size(14)
                .color(colors.text_secondary),
            Space::new().height(12),
            row![
                styled_button("Launch Cursor", true).on_press(Message::CursorAction(
                    CursorMessage::LaunchVersion("latest".to_string())
                )),
                Space::new().width(12),
                styled_button("Refresh Versions", false)
                    .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
            ]
            .spacing(0),
        ]
        .spacing(4),
    );

    column![
        text("Dashboard").size(26),
        text("Welcome back to Continuum Studio")
            .size(14)
            .color(colors.text_secondary),
        Space::new().height(24),
        row![status_card, Space::new().width(16), actions_card,],
    ]
    .spacing(8)
    .into()
}

/// Card container with COSMIC-style background
fn card<'a>(content: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    container(content)
        .padding(20)
        .width(300)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.15, 0.15, 0.15,
            ))),
            border: iced::Border {
                radius: 12.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.22, 0.22, 0.22),
            },
            shadow: iced::Shadow {
                color: iced::Color::from_rgba(0.0, 0.0, 0.0, 0.2),
                offset: iced::Vector::new(0.0, 2.0),
                blur_radius: 8.0,
            },
            ..container::Style::default()
        })
        .into()
}

/// Styled button (primary or secondary)
fn styled_button(label: &'static str, is_primary: bool) -> button::Button<'static, Message> {
    button(text(label).size(13))
        .padding([10, 20])
        .style(move |_theme, status| {
            let (bg, fg) = if is_primary {
                match status {
                    button::Status::Active => {
                        (iced::Color::from_rgb(0.4, 0.6, 1.0), iced::Color::WHITE)
                    }
                    button::Status::Hovered => {
                        (iced::Color::from_rgb(0.45, 0.65, 1.0), iced::Color::WHITE)
                    }
                    button::Status::Pressed => {
                        (iced::Color::from_rgb(0.35, 0.55, 0.95), iced::Color::WHITE)
                    }
                    button::Status::Disabled => (
                        iced::Color::from_rgb(0.3, 0.4, 0.6),
                        iced::Color::from_rgba(1.0, 1.0, 1.0, 0.5),
                    ),
                }
            } else {
                match status {
                    button::Status::Active => (
                        iced::Color::from_rgb(0.25, 0.25, 0.25),
                        iced::Color::from_rgb(0.85, 0.85, 0.85),
                    ),
                    button::Status::Hovered => {
                        (iced::Color::from_rgb(0.3, 0.3, 0.3), iced::Color::WHITE)
                    }
                    button::Status::Pressed => {
                        (iced::Color::from_rgb(0.2, 0.2, 0.2), iced::Color::WHITE)
                    }
                    button::Status::Disabled => (
                        iced::Color::from_rgb(0.18, 0.18, 0.18),
                        iced::Color::from_rgba(0.85, 0.85, 0.85, 0.5),
                    ),
                }
            };

            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: fg,
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
}

/// Unified Cursor view with tabbed navigation
fn view_cursor(state: &ContinuumStudio) -> Element<Message> {
    let current_tab = state.cursor_tab;
    
    // Tab bar
    let tab_button = |label: &'static str, tab: CursorTab| -> Element<Message> {
        let is_active = tab == current_tab;
        button(
            text(label)
                .size(13)
                .color(if is_active {
                    iced::Color::WHITE
                } else {
                    iced::Color::from_rgb(0.7, 0.7, 0.7)
                })
        )
        .padding([8, 16])
        .on_press(Message::CursorTabChange(tab))
        .style(move |_theme, status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.45, 0.65)
            } else {
                match status {
                    button::Status::Hovered => iced::Color::from_rgb(0.25, 0.25, 0.25),
                    _ => iced::Color::TRANSPARENT,
                }
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                text_color: iced::Color::WHITE,
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
        .into()
    };
    
    let tabs = row![
        tab_button("💬 Sessions", CursorTab::Sessions),
        tab_button("🤖 Sub-agents", CursorTab::SubAgents),
        tab_button("🔑 Auth", CursorTab::Auth),
        tab_button("📦 Versions", CursorTab::Versions),
        tab_button("📁 Workspaces", CursorTab::Workspaces),
    ]
    .spacing(4)
    .padding(0);
    
    // Header
    let header = column![
        text("Cursor Management").size(24),
        text("Sessions, agents, auth, versions, and workspaces")
            .size(12)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
    ]
    .spacing(4);
    
    // Tab content - delegate to existing view functions
    let tab_content: Element<Message> = match current_tab {
        CursorTab::Sessions => view_sessions(state),
        CursorTab::SubAgents => view_subagents(state),
        CursorTab::Auth => view_auth(state),
        CursorTab::Versions => view_cursor_versions(state),
        CursorTab::Workspaces => view_workspaces(state),
    };
    
    column![
        header,
        Space::new().height(16),
        tabs,
        tab_content,
    ]
    .spacing(8)
    .into()
}

/// Cursor version management view with improved organization
fn view_cursor_versions(state: &ContinuumStudio) -> Element<Message> {
    // Count versions by category
    let total = state.versions.len();
    let installed_count = state.versions.iter().filter(|v| v.installed).count();
    let supported_count = state.versions.iter().filter(|v| is_supported_version(&v.version)).count();

    // Group versions by major.minor era
    let mut era_groups: Vec<(&str, Vec<&CursorVersion>)> = Vec::new();
    let mut current_era = String::new();
    let mut current_group: Vec<&CursorVersion> = Vec::new();

    for v in &state.versions {
        let era = get_version_era(&v.version);
        if era != current_era {
            if !current_group.is_empty() {
                era_groups.push((Box::leak(current_era.clone().into_boxed_str()), current_group));
                current_group = Vec::new();
            }
            current_era = era.to_string();
        }
        current_group.push(v);
    }
    if !current_group.is_empty() {
        era_groups.push((Box::leak(current_era.into_boxed_str()), current_group));
    }

    // Build version list with era headers
    let mut version_elements: Vec<Element<Message>> = Vec::new();

    for (era_name, era_versions) in &era_groups {
        // Era header
        let is_supported = is_supported_era(era_name);
        let header_color = if is_supported {
            iced::Color::from_rgb(0.3, 0.7, 0.4)
        } else {
            iced::Color::from_rgb(0.5, 0.5, 0.5)
        };

        let support_badge = if is_supported {
            container(
                text("SUPPORTED")
                    .size(9)
                    .color(iced::Color::from_rgb(0.3, 0.7, 0.4))
            )
            .padding([2, 6])
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(
                    iced::Color::from_rgba(0.3, 0.7, 0.4, 0.15)
                )),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..container::Style::default()
            })
        } else {
            container(
                text("LEGACY")
                    .size(9)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
            )
            .padding([2, 6])
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(
                    iced::Color::from_rgba(0.5, 0.5, 0.5, 0.15)
                )),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..container::Style::default()
            })
        };

        let era_header = container(
            row![
                text(format!("Cursor {}", era_name))
                    .size(14)
                    .color(header_color),
                Space::new().width(12),
                support_badge,
                Space::new().width(Length::Fill),
                text(format!("{} versions", era_versions.len()))
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ]
            .align_y(Alignment::Center),
        )
        .padding([12, 16])
        .width(Length::Fill)
        .style(move |_theme| container::Style {
            background: Some(iced::Background::Color(
                if is_supported {
                    iced::Color::from_rgb(0.12, 0.15, 0.12)
                } else {
                    iced::Color::from_rgb(0.1, 0.1, 0.1)
                }
            )),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: if is_supported {
                    iced::Color::from_rgb(0.2, 0.28, 0.2)
                } else {
                    iced::Color::from_rgb(0.15, 0.15, 0.15)
                },
            },
            ..container::Style::default()
        });

        version_elements.push(era_header.into());
        version_elements.push(Space::new().height(4).into());

        // Version rows for this era
        let has_profiles = !state.auth_profiles.is_empty();
        for v in era_versions {
            let auth_status = state.auth_statuses.get(&v.version);
            version_elements.push(version_row_from_data(v, auth_status, has_profiles));
        }

        version_elements.push(Space::new().height(16).into());
    }

    let version_list: Element<Message> = if version_elements.is_empty() {
        column![
            Space::new().height(40),
            text("No versions available")
                .size(14)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(8),
            text("Connect to Core to load versions")
                .size(12)
                .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            Space::new().height(40),
        ]
        .align_x(Alignment::Center)
        .width(Length::Fill)
        .into()
    } else {
        column(version_elements).spacing(4).into()
    };

    // Header card with stats
    let header_card = container(
        row![
            column![
                text("Cursor Versions").size(20),
                row![
                    text(format!("{} total", total))
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    text(" · ")
                        .size(12)
                        .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
                    text(format!("{} supported", supported_count))
                        .size(12)
                        .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
                    text(" · ")
                        .size(12)
                        .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
                    text(format!("{} installed", installed_count))
                        .size(12)
                        .color(iced::Color::from_rgb(0.4, 0.6, 1.0)),
                ],
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button("Refresh", false)
                .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
        ]
        .align_y(Alignment::Center),
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Quick jump section
    let quick_jump = container(
        row![
            text("Jump to: ")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            text("2.4.x")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            text(" · ")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
            text("2.3.x")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            text(" · ")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
            text("2.2.x")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            text(" · ")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
            text("2.1.x")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            text(" · ")
                .size(11)
                .color(iced::Color::from_rgb(0.3, 0.3, 0.3)),
            text("Legacy")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ],
    )
    .padding([8, 16]);

    scrollable(
        column![
            header_card,
            Space::new().height(12),
            quick_jump,
            Space::new().height(12),
            version_list,
        ]
    )
    .into()
}

/// Flash-identify windows: briefly raise each window with a small delay
async fn flash_instance_windows(window_ids: &[String]) {
    for wid in window_ids {
        let _ = tokio::process::Command::new("kdotool")
            .args(["windowactivate", wid])
            .output()
            .await;
        tokio::time::sleep(std::time::Duration::from_millis(400)).await;
    }
}

/// Apply KWin border overlay by loading a script that colorizes window borders
async fn apply_kwin_border_overlay(sessions: &[(String, Vec<String>)]) {
    // Write a temporary KWin script that applies colored borders
    let script_dir = dirs::cache_dir()
        .unwrap_or_else(|| std::path::PathBuf::from("/tmp"))
        .join("continuum-studio");
    let _ = tokio::fs::create_dir_all(&script_dir).await;

    let script_path = script_dir.join("border-overlay.js");

    // Build a KWin script that uses window rules to highlight
    // KWin scripting API: https://develop.kde.org/docs/plasma/kwin/api/
    let mut js = String::from(
        "// Continuum Studio border overlay\n\
         // This script highlights Cursor windows with instance-specific colors\n\
         var clients = workspace.windowList();\n\
         for (var i = 0; i < clients.length; i++) {\n\
         var c = clients[i];\n",
    );

    for (color, window_ids) in sessions {
        for wid in window_ids {
            js.push_str(&format!(
                "  if (c.internalId.toString() === '{}') {{\n\
                 // Mark for identification - color: {}\n\
                 c.noBorder = false;\n\
                 }}\n",
                wid, color,
            ));
        }
    }
    js.push_str("}\n");

    if let Err(e) = tokio::fs::write(&script_path, &js).await {
        log::error!("Failed to write KWin border script: {}", e);
        return;
    }

    // Load script via D-Bus
    let _ = tokio::process::Command::new("qdbus")
        .args([
            "org.kde.KWin",
            "/Scripting",
            "org.kde.kwin.Scripting.loadScript",
            script_path.to_str().unwrap_or(""),
            "continuum-border-overlay",
        ])
        .output()
        .await;

    log::info!("Applied KWin border overlay for {} instances", sessions.len());
}

/// Remove KWin border overlay
async fn remove_kwin_border_overlay() {
    // Unload the script
    let _ = tokio::process::Command::new("qdbus")
        .args([
            "org.kde.KWin",
            "/Scripting",
            "org.kde.kwin.Scripting.unloadScript",
            "continuum-border-overlay",
        ])
        .output()
        .await;

    log::info!("Removed KWin border overlay");
}

/// Parse a hex color string like "#FF6B6B" into an iced Color
fn parse_hex_color(hex: &str) -> iced::Color {
    let hex = hex.trim_start_matches('#');
    if hex.len() >= 6 {
        let r = u8::from_str_radix(&hex[0..2], 16).unwrap_or(128) as f32 / 255.0;
        let g = u8::from_str_radix(&hex[2..4], 16).unwrap_or(128) as f32 / 255.0;
        let b = u8::from_str_radix(&hex[4..6], 16).unwrap_or(128) as f32 / 255.0;
        iced::Color::from_rgb(r, g, b)
    } else {
        iced::Color::from_rgb(0.5, 0.5, 0.5)
    }
}

/// Build a metrics row for a session (helper function to avoid type inference issues)
fn build_metrics_row(metrics: Option<&SessionMetrics>) -> Element<'static, Message> {
    if let Some(m) = metrics {
        let cpu_color = if m.cpu_percent > 50.0 {
            iced::Color::from_rgb(0.9, 0.7, 0.2)
        } else {
            iced::Color::from_rgb(0.4, 0.6, 1.0)
        };
        let state_text = match m.state {
            'R' => "Running",
            'S' => "Sleeping",
            'D' => "Disk Wait",
            'Z' => "Zombie",
            'T' => "Stopped",
            _ => "Unknown",
        };
        let state_color = if m.state == 'R' {
            iced::Color::from_rgb(0.3, 0.8, 0.4)
        } else {
            iced::Color::from_rgb(0.5, 0.5, 0.5)
        };

        row![
            column![
                text("CPU").size(9).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{:.1}%", m.cpu_percent)).size(14).color(cpu_color),
            ].width(Length::FillPortion(1)),
            column![
                text("Memory").size(9).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(m.memory_human.clone()).size(14).color(iced::Color::from_rgb(0.6, 0.4, 0.9)),
            ].width(Length::FillPortion(1)),
            column![
                text("Threads").size(9).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{}", m.thread_count)).size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ].width(Length::FillPortion(1)),
            column![
                text("FDs").size(9).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{}", m.fd_count)).size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ].width(Length::FillPortion(1)),
            column![
                text("State").size(9).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(state_text).size(12).color(state_color),
            ].width(Length::FillPortion(1)),
        ]
        .into()
    } else {
        row![
            text("No metrics yet - click 'Refresh Metrics'")
                .size(11)
                .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
        ]
        .into()
    }
}

/// Check if a version string is in the supported range (2.1.x and newer)
fn is_supported_version(version: &str) -> bool {
    // Parse version like "2.4.27" or "2.1.0"
    let parts: Vec<&str> = version.split('.').collect();
    if parts.len() >= 2 {
        if let (Ok(major), Ok(minor)) = (parts[0].parse::<u32>(), parts[1].parse::<u32>()) {
            return major > 2 || (major == 2 && minor >= 1);
        }
    }
    false
}

/// Check if an era (like "2.4.x") is supported
fn is_supported_era(era: &str) -> bool {
    let parts: Vec<&str> = era.split('.').collect();
    if parts.len() >= 2 {
        if let (Ok(major), Ok(minor)) = (parts[0].parse::<u32>(), parts[1].parse::<u32>()) {
            return major > 2 || (major == 2 && minor >= 1);
        }
    }
    false
}

/// Get the era string from a version (e.g., "2.4.27" -> "2.4.x")
fn get_version_era(version: &str) -> String {
    let parts: Vec<&str> = version.split('.').collect();
    if parts.len() >= 2 {
        format!("{}.{}.x", parts[0], parts[1])
    } else {
        version.to_string()
    }
}

/// Create a version row from CursorVersion data with polished styling
fn version_row_from_data<'a>(version: &'a CursorVersion, auth_status: Option<&'a AuthStatus>, has_profiles: bool) -> Element<'a, Message> {
    let v = version.version.clone();
    let v2 = version.version.clone();
    let v3 = version.version.clone();
    let v4 = version.version.clone();

    let (status_text, status_color) = match version.status {
        VersionStatus::Running => ("Running", iced::Color::from_rgb(0.25, 0.75, 0.35)),
        VersionStatus::Installed => ("Installed", iced::Color::from_rgb(0.4, 0.6, 1.0)),
        VersionStatus::Available => ("Available", iced::Color::from_rgb(0.5, 0.5, 0.5)),
        VersionStatus::Downloading => ("Downloading...", iced::Color::from_rgb(0.75, 0.65, 0.25)),
    };

    // Build auth display with action buttons for installed versions
    let auth_display: Element<Message> = if version.installed {
        match auth_status {
            Some(status) => {
                match status.status {
                    AuthState::Authenticated => {
                        let email = status.email.as_deref().unwrap_or("Logged in");
                        let email_short = if email.len() > 12 {
                            format!("{}...", &email[..9])
                        } else {
                            email.to_string()
                        };
                        // Authenticated: show email and Extract button
                        row![
                            text("🔑").size(11),
                            text(email_short).size(10).color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
                            tiny_button("📤")
                                .on_press(Message::CursorAction(CursorMessage::ExtractAuth(v3))),
                        ]
                        .spacing(4)
                        .align_y(Alignment::Center)
                        .into()
                    }
                    AuthState::NotLoggedIn => {
                        // Not logged in: show Apply button if profiles available
                        if has_profiles {
                            row![
                                text("—").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                                tiny_button("📥")
                                    .on_press(Message::CursorAction(CursorMessage::ApplyAuthFromLatest(v4))),
                            ]
                            .spacing(4)
                            .align_y(Alignment::Center)
                            .into()
                        } else {
                            text("Not logged in").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).into()
                        }
                    }
                    AuthState::Stale => {
                        row![
                            text("⚠").size(11),
                            text("Stale").size(11).color(iced::Color::from_rgb(0.8, 0.6, 0.2)),
                        ]
                        .spacing(4)
                        .align_y(Alignment::Center)
                        .into()
                    }
                    AuthState::Unknown => {
                        text("?").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).into()
                    }
                }
            }
            None => {
                // Auth status not loaded yet
                text("...").size(11).color(iced::Color::from_rgb(0.4, 0.4, 0.4)).into()
            }
        }
    } else {
        // Not installed, no auth display
        text("-").size(11).color(iced::Color::from_rgb(0.3, 0.3, 0.3)).into()
    };

    let action_button: Element<Message> = match version.status {
        VersionStatus::Running => container(
            text("● Active")
                .size(11)
                .color(iced::Color::from_rgb(0.25, 0.75, 0.35)),
        )
        .padding([6, 12])
        .into(),
        VersionStatus::Installed => small_button("Launch", true)
            .on_press(Message::CursorAction(CursorMessage::LaunchVersion(v)))
            .into(),
        VersionStatus::Available => small_button("Install", false)
            .on_press(Message::CursorAction(CursorMessage::InstallVersion(v2)))
            .into(),
        VersionStatus::Downloading => container(
            text("⏳ ...")
                .size(11)
                .color(iced::Color::from_rgb(0.75, 0.65, 0.25)),
        )
        .padding([6, 12])
        .into(),
    };

    container(
        row![
            text(&version.version).size(13).width(100),
            text(status_text).size(12).color(status_color).width(90),
            container(auth_display).width(130),
            text(version.date.as_deref().unwrap_or("-"))
                .size(12)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                .width(90),
            Space::new().width(Length::Fill),
            container(action_button).width(100),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
        .padding([12, 16]),
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.13, 0.13, 0.13,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.2),
        },
        ..container::Style::default()
    })
    .into()
}

/// Small button for table actions
fn small_button(label: &'static str, is_primary: bool) -> button::Button<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .style(move |_theme, status| {
            let (bg, fg) = if is_primary {
                match status {
                    button::Status::Active => {
                        (iced::Color::from_rgb(0.3, 0.5, 0.9), iced::Color::WHITE)
                    }
                    button::Status::Hovered => {
                        (iced::Color::from_rgb(0.35, 0.55, 0.95), iced::Color::WHITE)
                    }
                    button::Status::Pressed => {
                        (iced::Color::from_rgb(0.25, 0.45, 0.85), iced::Color::WHITE)
                    }
                    button::Status::Disabled => (
                        iced::Color::from_rgb(0.2, 0.3, 0.5),
                        iced::Color::from_rgba(1.0, 1.0, 1.0, 0.5),
                    ),
                }
            } else {
                match status {
                    button::Status::Active => (
                        iced::Color::from_rgb(0.2, 0.2, 0.2),
                        iced::Color::from_rgb(0.75, 0.75, 0.75),
                    ),
                    button::Status::Hovered => {
                        (iced::Color::from_rgb(0.25, 0.25, 0.25), iced::Color::WHITE)
                    }
                    button::Status::Pressed => {
                        (iced::Color::from_rgb(0.15, 0.15, 0.15), iced::Color::WHITE)
                    }
                    button::Status::Disabled => (
                        iced::Color::from_rgb(0.15, 0.15, 0.15),
                        iced::Color::from_rgba(0.75, 0.75, 0.75, 0.5),
                    ),
                }
            };

            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: fg,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
}

/// Tiny icon button for auth actions in version rows
fn tiny_button(icon: &'static str) -> button::Button<'static, Message> {
    button(text(icon).size(10))
        .padding([2, 4])
        .style(move |_theme, status| {
            let bg = match status {
                button::Status::Active => iced::Color::from_rgba(0.3, 0.3, 0.3, 0.5),
                button::Status::Hovered => iced::Color::from_rgba(0.4, 0.5, 0.7, 0.7),
                button::Status::Pressed => iced::Color::from_rgba(0.25, 0.35, 0.55, 0.8),
                button::Status::Disabled => iced::Color::from_rgba(0.2, 0.2, 0.2, 0.3),
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
}

/// Workspaces view with tracked projects
fn view_workspaces(state: &ContinuumStudio) -> Element<Message> {
    let workspace_rows: Vec<Element<Message>> = state
        .workspaces
        .iter()
        .map(|ws| workspace_row(ws))
        .collect();

    let workspace_list: Element<Message> = if workspace_rows.is_empty() {
        column![
            Space::new().height(40),
            text("📁").size(48),
            Space::new().height(16),
            text("No workspaces tracked")
                .size(14)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(8),
            text("Open a folder in Cursor to start tracking")
                .size(12)
                .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            Space::new().height(40),
        ]
        .align_x(Alignment::Center)
        .width(Length::Fill)
        .into()
    } else {
        column(workspace_rows).spacing(8).into()
    };

    // Header card with controls
    let header_card = container(
        row![
            column![
                text("Workspaces").size(20),
                text(format!("{} tracked projects", state.workspaces.len()))
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button("Refresh", false)
                .on_press(Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces)),
        ]
        .align_y(Alignment::Center),
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Table header
    let table_header = container(
        row![
            text("Name")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(180),
            text("Git Branch")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(100),
            text("Changes")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(80),
            text("Last Opened")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(100),
            Space::new().width(Length::Fill),
            text("Actions")
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(100),
        ]
        .padding([0, 16]),
    );

    // --- WORKSPACE FILES SECTION ---
    let scan_action: Element<Message> = if state.workspace_files_loading {
        text("Scanning...")
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.8))
            .into()
    } else {
        styled_button("Scan", false)
            .on_press(Message::WorkspaceAction(WorkspaceMessage::ScanWorkspaceFiles))
            .into()
    };

    let ws_files_header = row![
        column![
            text(".code-workspace Files").size(16),
            text(format!("{} files found", state.workspace_files.len()))
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(2),
        Space::new().width(Length::Fill),
        scan_action,
    ]
    .align_y(Alignment::Center)
    .padding([12, 16]);

    let ws_files_list: Element<Message> = if state.workspace_files.is_empty() {
        container(
            text("No .code-workspace files found. Click 'Scan' to search.")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .padding([16, 16])
        .into()
    } else {
        let file_rows: Vec<Element<Message>> = state
            .workspace_files
            .iter()
            .map(|ws| workspace_file_row(ws))
            .collect();
        column(file_rows).spacing(4).into()
    };

    let ws_files_card = container(
        column![
            ws_files_header,
            ws_files_list,
        ]
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.12,
        ))),
        border: iced::Border {
            radius: 10.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.2),
        },
        ..container::Style::default()
    });

    column![
        header_card,
        Space::new().height(16),
        ws_files_card,
        Space::new().height(16),
        text("Tracked Projects").size(16),
        Space::new().height(8),
        table_header,
        Space::new().height(8),
        scrollable(workspace_list).height(300),
    ]
    .spacing(0)
    .into()
}

/// Create a row for a .code-workspace file
fn workspace_file_row(ws: &CodeWorkspaceFile) -> Element<'static, Message> {
    let path = ws.path.clone();
    let name = ws.name.clone();
    let folder_count = ws.folders.len();
    let folders_str: String = if folder_count == 1 {
        ws.folders.first().cloned().unwrap_or_default()
    } else {
        format!("{} folders", folder_count)
    };
    let modified = ws.modified.clone().unwrap_or_else(|| "-".to_string());

    container(
        row![
            column![
                text(name).size(13),
                text(folders_str)
                    .size(10)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(2)
            .width(200),
            text(modified)
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .width(120),
            Space::new().width(Length::Fill),
            small_button("Open", true)
                .on_press(Message::WorkspaceAction(WorkspaceMessage::OpenWorkspaceFile(path))),
        ]
        .spacing(12)
        .align_y(Alignment::Center)
        .padding([8, 12]),
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.14, 0.14, 0.14,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.18),
        },
        ..container::Style::default()
    })
    .into()
}

/// Create a workspace row with git info
fn workspace_row(workspace: &Workspace) -> Element<Message> {
    let id = workspace.id.clone();
    let id2 = workspace.id.clone();
    let name = workspace.name.clone();
    let path = workspace.path.clone();

    let git_branch = workspace
        .git_stats
        .as_ref()
        .map(|g| g.branch.clone())
        .unwrap_or_else(|| "-".to_string());

    let uncommitted = workspace
        .git_stats
        .as_ref()
        .map(|g| g.uncommitted_changes)
        .unwrap_or(0);

    let uncommitted_text = if uncommitted > 0 {
        format!("{} files", uncommitted)
    } else {
        "Clean".to_string()
    };

    let uncommitted_color = if uncommitted > 0 {
        iced::Color::from_rgb(0.75, 0.65, 0.25)
    } else {
        iced::Color::from_rgb(0.25, 0.75, 0.35)
    };

    let last_opened = workspace
        .last_opened_at
        .as_ref()
        .and_then(|s| s.split('T').next())
        .map(|s| s.to_string())
        .unwrap_or_else(|| "-".to_string());

    let pin_icon = if workspace.pinned { "📌" } else { "📍" };

    container(
        row![
            row![
                button(text(pin_icon).size(12))
                    .padding(4)
                    .style(|_theme, _status| button::Style {
                        background: None,
                        text_color: iced::Color::from_rgb(0.6, 0.6, 0.6),
                        border: iced::Border::default(),
                        shadow: iced::Shadow::default(),
                        snap: false,
                    })
                    .on_press(Message::WorkspaceAction(WorkspaceMessage::TogglePinned(id))),
                column![
                    text(name).size(13),
                    text(path)
                        .size(10)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(2),
            ]
            .spacing(8)
            .align_y(Alignment::Center)
            .width(180),
            text(git_branch)
                .size(12)
                .color(iced::Color::from_rgb(0.4, 0.6, 1.0))
                .width(100),
            text(uncommitted_text)
                .size(12)
                .color(uncommitted_color)
                .width(80),
            text(last_opened)
                .size(12)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                .width(100),
            Space::new().width(Length::Fill),
            container(
                small_button("Open", true)
                    .on_press(Message::WorkspaceAction(WorkspaceMessage::OpenInCursor(
                        id2,
                        "latest".to_string(),
                    )))
            )
            .width(100),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
        .padding([12, 16]),
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.13, 0.13, 0.13,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.2),
        },
        ..container::Style::default()
    })
    .into()
}

/// Auth Management view - profiles and version auth status
fn view_auth(state: &ContinuumStudio) -> Element<Message> {
    // Header with title and refresh button
    let header = row![
        text("🔑 Auth Management").size(24),
        Space::new().width(Length::Fill),
        button(text("↻ Refresh").size(12))
            .padding([6, 12])
            .on_press(Message::AuthAction(AuthMessage::RefreshStatuses))
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Active => iced::Color::from_rgb(0.2, 0.4, 0.6),
                    button::Status::Hovered => iced::Color::from_rgb(0.3, 0.5, 0.7),
                    button::Status::Pressed => iced::Color::from_rgb(0.15, 0.35, 0.55),
                    button::Status::Disabled => iced::Color::from_rgb(0.3, 0.3, 0.3),
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: iced::Color::WHITE,
                    border: iced::Border { radius: 6.0.into(), width: 0.0, color: iced::Color::TRANSPARENT },
                    shadow: iced::Shadow::default(),
                    snap: false,
                }
            }),
    ]
    .spacing(12)
    .align_y(Alignment::Center);

    // Stored Profiles section
    let profiles_header = text("📦 Stored Profiles").size(16);
    
    let profiles_list: Element<Message> = if state.auth_profiles.is_empty() {
        container(
            text("No profiles stored. Extract a profile from an authenticated version.")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .padding(12)
        .into()
    } else {
        let profile_rows: Vec<Element<Message>> = state.auth_profiles.iter().map(|profile| {
            let email = profile.email.as_deref().unwrap_or("Unknown");
            let provider = profile.provider.as_deref().unwrap_or("Unknown");
            let source = profile.extracted_from.as_deref().unwrap_or("Unknown");
            let membership = if profile.membership.is_empty() { "free" } else { &profile.membership };
            
            container(
                row![
                    column![
                        text(email).size(13),
                        text(format!("{} • {} • from v{}", provider, membership, source))
                            .size(10)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    ]
                    .spacing(2),
                    Space::new().width(Length::Fill),
                    text("✓").size(14).color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
                ]
                .spacing(8)
                .align_y(Alignment::Center)
            )
            .padding(10)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.15))),
                border: iced::Border { radius: 6.0.into(), width: 1.0, color: iced::Color::from_rgb(0.25, 0.25, 0.25) },
                ..container::Style::default()
            })
            .into()
        }).collect();
        
        column(profile_rows).spacing(6).into()
    };

    let profiles_section = container(
        column![
            profiles_header,
            Space::new().height(8),
            profiles_list,
        ]
        .spacing(4)
    )
    .padding(16)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.12))),
        border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.2, 0.2, 0.2) },
        ..container::Style::default()
    });

    // Version Auth Status section
    let versions_header = text("📋 Version Auth Status").size(16);
    
    // Get installed versions with their auth status
    let installed_versions: Vec<&CursorVersion> = state.versions.iter()
        .filter(|v| v.installed)
        .collect();

    let version_rows: Vec<Element<Message>> = installed_versions.iter().map(|version| {
        let auth_status = state.auth_statuses.get(&version.version);
        let version_str = version.version.clone();
        let version_str2 = version.version.clone();
        
        let (status_icon, status_text, status_color, can_extract, can_apply) = match auth_status {
            Some(status) => match status.status {
                AuthState::Authenticated => {
                    let email = status.email.as_deref().unwrap_or("Logged in");
                    ("🔑", email.to_string(), iced::Color::from_rgb(0.3, 0.7, 0.4), true, false)
                }
                AuthState::NotLoggedIn => {
                    ("—", "Not logged in".to_string(), iced::Color::from_rgb(0.5, 0.5, 0.5), false, true)
                }
                AuthState::Stale => {
                    ("⚠️", "Auth may be stale".to_string(), iced::Color::from_rgb(0.8, 0.6, 0.2), true, true)
                }
                AuthState::Unknown => {
                    ("?", "Unknown".to_string(), iced::Color::from_rgb(0.4, 0.4, 0.4), false, false)
                }
            }
            None => ("?", "Not scanned".to_string(), iced::Color::from_rgb(0.4, 0.4, 0.4), false, false)
        };

        // Action buttons
        let extract_btn: Element<Message> = if can_extract {
            button(text("📤 Extract").size(11))
                .padding([4, 8])
                .on_press(Message::AuthAction(AuthMessage::ExtractFromVersion(version_str)))
                .style(|_theme, status| {
                    let bg = match status {
                        button::Status::Active => iced::Color::from_rgb(0.2, 0.3, 0.5),
                        button::Status::Hovered => iced::Color::from_rgb(0.3, 0.4, 0.6),
                        button::Status::Pressed => iced::Color::from_rgb(0.15, 0.25, 0.45),
                        button::Status::Disabled => iced::Color::from_rgb(0.2, 0.2, 0.2),
                    };
                    button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: iced::Color::WHITE,
                        border: iced::Border { radius: 4.0.into(), width: 0.0, color: iced::Color::TRANSPARENT },
                        shadow: iced::Shadow::default(),
                        snap: false,
                    }
                })
                .into()
        } else {
            Space::new().width(0).into()
        };

        let has_profiles = !state.auth_profiles.is_empty();
        let apply_btn: Element<Message> = if can_apply && has_profiles {
            button(text("📥 Apply").size(11))
                .padding([4, 8])
                .on_press(Message::AuthAction(AuthMessage::ApplyProfile { 
                    profile_id: String::new(), // Will use most recent
                    target_version: version_str2 
                }))
                .style(|_theme, status| {
                    let bg = match status {
                        button::Status::Active => iced::Color::from_rgb(0.3, 0.5, 0.3),
                        button::Status::Hovered => iced::Color::from_rgb(0.4, 0.6, 0.4),
                        button::Status::Pressed => iced::Color::from_rgb(0.25, 0.45, 0.25),
                        button::Status::Disabled => iced::Color::from_rgb(0.2, 0.2, 0.2),
                    };
                    button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: iced::Color::WHITE,
                        border: iced::Border { radius: 4.0.into(), width: 0.0, color: iced::Color::TRANSPARENT },
                        shadow: iced::Shadow::default(),
                        snap: false,
                    }
                })
                .into()
        } else {
            Space::new().width(0).into()
        };

        container(
            row![
                text(format!("v{}", version.version)).size(13),
                Space::new().width(16),
                text(status_icon).size(12),
                text(status_text).size(11).color(status_color),
                Space::new().width(Length::Fill),
                extract_btn,
                Space::new().width(4),
                apply_btn,
            ]
            .spacing(8)
            .align_y(Alignment::Center)
        )
        .padding(10)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.15))),
            border: iced::Border { radius: 6.0.into(), width: 1.0, color: iced::Color::from_rgb(0.25, 0.25, 0.25) },
            ..container::Style::default()
        })
        .into()
    }).collect();

    let versions_list: Element<Message> = if version_rows.is_empty() {
        container(
            text("No installed versions found.")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .padding(12)
        .into()
    } else {
        scrollable(column(version_rows).spacing(6))
            .height(Length::Fill)
            .into()
    };

    let versions_section = container(
        column![
            versions_header,
            Space::new().height(8),
            versions_list,
        ]
        .spacing(4)
        .height(Length::Fill)
    )
    .padding(16)
    .height(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.12))),
        border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.2, 0.2, 0.2) },
        ..container::Style::default()
    });

    // Help text
    let help_text = container(
        text("Extract profiles from authenticated versions, then apply them to newly installed versions.")
            .size(11)
            .color(iced::Color::from_rgb(0.4, 0.4, 0.4))
    )
    .padding([8, 0]);

    container(
        column![
            header,
            Space::new().height(16),
            help_text,
            Space::new().height(8),
            profiles_section,
            Space::new().height(16),
            versions_section,
        ]
        .spacing(0)
        .height(Length::Fill)
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .into()
}

/// Sessions view with detected running Cursor instances and real-time monitoring
fn view_sessions(state: &ContinuumStudio) -> Element<Message> {
    // Header card with stats and controls
    let header_card = container(
        row![
            column![
                text("Session Monitor").size(20),
                text(format!("{} running Cursor instances", state.cursor_sessions.len()))
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button(
                if state.border_overlay_active { "Borders: ON" } else { "Borders: OFF" },
                state.border_overlay_active,
            )
            .on_press(Message::SessionAction(SessionMessage::ToggleBorderOverlay)),
            Space::new().width(8),
            styled_button("Refresh Metrics", false)
                .on_press(Message::SessionAction(SessionMessage::CollectMetrics)),
            Space::new().width(8),
            styled_button("Scan Processes", false)
                .on_press(Message::SessionAction(SessionMessage::RefreshSessions)),
        ]
        .align_y(Alignment::Center),
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Dashboard overview cards (if we have data)
    let dashboard_row: Element<Message> = if let Some(ref dash) = state.dashboard_data {
        row![
            // Health status card
            container(
                column![
                    text("Health").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    Space::new().height(8),
                    row![
                        container(text(format!("{}", dash.healthy_count)).size(24).color(iced::Color::from_rgb(0.3, 0.8, 0.4)))
                            .padding([4, 8]),
                        text("healthy").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    ].align_y(Alignment::End),
                    text(
                        if dash.warning_count > 0 && dash.critical_count > 0 {
                            format!("{} warn, {} crit", dash.warning_count, dash.critical_count)
                        } else if dash.warning_count > 0 {
                            format!("{} warning", dash.warning_count)
                        } else if dash.critical_count > 0 {
                            format!("{} critical", dash.critical_count)
                        } else {
                            String::new()
                        }
                    )
                    .size(10)
                    .color(if dash.critical_count > 0 {
                        iced::Color::from_rgb(0.9, 0.3, 0.3)
                    } else if dash.warning_count > 0 {
                        iced::Color::from_rgb(0.9, 0.7, 0.2)
                    } else {
                        iced::Color::from_rgb(0.5, 0.5, 0.5)
                    }),
                ],
            )
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.14, 0.12))),
                border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.2, 0.25, 0.2) },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // CPU card
            container(
                column![
                    text("CPU").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    Space::new().height(8),
                    row![
                        text(format!("{:.1}%", dash.total_cpu)).size(24).color(
                            if dash.total_cpu > 80.0 { iced::Color::from_rgb(0.9, 0.3, 0.3) }
                            else if dash.total_cpu > 40.0 { iced::Color::from_rgb(0.9, 0.7, 0.2) }
                            else { iced::Color::from_rgb(0.4, 0.6, 1.0) }
                        ),
                    ],
                    text("total usage").size(10).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                ],
            )
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.14))),
                border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.2, 0.2, 0.25) },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // Memory card
            container(
                column![
                    text("Memory").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    Space::new().height(8),
                    row![
                        text(format!("{:.0} MB", dash.total_memory_mb)).size(24).color(
                            if dash.total_memory_mb > 8000.0 { iced::Color::from_rgb(0.9, 0.3, 0.3) }
                            else if dash.total_memory_mb > 4000.0 { iced::Color::from_rgb(0.9, 0.7, 0.2) }
                            else { iced::Color::from_rgb(0.6, 0.4, 0.9) }
                        ),
                    ],
                    text("total allocated").size(10).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                ],
            )
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.13, 0.12, 0.14))),
                border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.22, 0.2, 0.25) },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // Threads/FDs card
            container(
                column![
                    text("Resources").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    Space::new().height(8),
                    row![
                        text(format!("{}", dash.total_threads)).size(20).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        text(" threads").size(10).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                    ].align_y(Alignment::End),
                    row![
                        text(format!("{}", dash.total_fds)).size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        text(" file descriptors").size(10).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                    ].align_y(Alignment::End),
                ],
            )
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.12))),
                border: iced::Border { radius: 8.0.into(), width: 1.0, color: iced::Color::from_rgb(0.18, 0.18, 0.18) },
                ..container::Style::default()
            }),
        ]
        .into()
    } else {
        Space::new().height(0).into()
    };

    let content: Element<Message> = if state.cursor_sessions.is_empty() {
        // Empty state
        container(
            column![
                text("📊").size(48),
                Space::new().height(16),
                text("No active sessions detected").size(16),
                Space::new().height(8),
                text("Click 'Scan Processes' to detect running Cursor instances")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .align_x(Alignment::Center),
        )
        .width(Length::Fill)
        .padding(60)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.12, 0.12, 0.12,
            ))),
            border: iced::Border {
                radius: 12.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.18, 0.18),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        // Session cards with metrics
        let session_cards: Vec<Element<Message>> = state
            .cursor_sessions
            .iter()
            .map(|session| {
                let version_text = session.version.as_deref().unwrap_or("Unknown");
                let workspace_text = session.workspace.as_deref().unwrap_or("No workspace");
                let window_title = session.window_title.as_deref().unwrap_or("");

                // Parse instance color from hex string
                let instance_color = parse_hex_color(&session.color);

                // Find metrics for this session
                let metrics = state.session_metrics.iter().find(|m| m.pid == session.pid);

                // Determine health status and colors
                let (health_text, health_color) = if let Some(m) = metrics {
                    let (r, g, b) = m.health.color();
                    (m.health.as_str(), iced::Color::from_rgb(r, g, b))
                } else {
                    ("Unknown", iced::Color::from_rgb(0.5, 0.5, 0.5))
                };

                // Process summary line
                let process_summary = format!(
                    "{} processes | {} ext hosts | {} lang servers | {}",
                    session.total_process_count,
                    session.extension_host_count,
                    session.language_server_count,
                    session.rss_human(),
                );

                container(
                    column![
                        // Header row with color indicator, PID, version, and health badge
                        row![
                            // Color dot indicator
                            container(Space::new().width(8).height(8))
                                .style(move |_theme| container::Style {
                                    background: Some(iced::Background::Color(instance_color)),
                                    border: iced::Border {
                                        radius: 4.0.into(),
                                        ..Default::default()
                                    },
                                    ..container::Style::default()
                                }),
                            Space::new().width(8),
                            text(format!("PID {}", session.pid))
                                .size(12)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(12),
                            text(format!("Cursor {}", version_text))
                                .size(14),
                            Space::new().width(Length::Fill),
                            text(if session.active { " Active " } else { "" })
                                .size(10)
                                .color(iced::Color::from_rgb(0.3, 0.8, 0.3)),
                            container(
                                text(health_text)
                                    .size(10)
                                    .color(health_color)
                            )
                            .padding([4, 8])
                            .style(move |_theme| container::Style {
                                background: Some(iced::Background::Color(
                                    iced::Color::from_rgba(health_color.r, health_color.g, health_color.b, 0.15)
                                )),
                                border: iced::Border {
                                    radius: 4.0.into(),
                                    ..Default::default()
                                },
                                ..container::Style::default()
                            }),
                        ]
                        .align_y(Alignment::Center),
                        Space::new().height(8),
                        // Window title / workspace
                        text(if !window_title.is_empty() { window_title } else { workspace_text })
                            .size(11)
                            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                        Space::new().height(4),
                        // Process summary
                        text(process_summary.clone())
                            .size(10)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        Space::new().height(4),
                        // Windows count
                        text(format!("{} windows", session.windows.len()))
                            .size(10)
                            .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                        Space::new().height(12),
                        // Metrics display
                        build_metrics_row(metrics),
                        Space::new().height(8),
                        // Action buttons
                        row![
                            styled_button("Flash Identify", false)
                                .on_press(Message::SessionAction(SessionMessage::FlashIdentify(session.pid))),
                        ],
                    ]
                    .padding([16, 20]),
                )
                .width(Length::Fill)
                .style(move |_theme| container::Style {
                    background: Some(iced::Background::Color(iced::Color::from_rgb(
                        0.13, 0.13, 0.13,
                    ))),
                    border: iced::Border {
                        radius: 8.0.into(),
                        width: 2.0,
                        color: iced::Color::from_rgba(
                            instance_color.r,
                            instance_color.g,
                            instance_color.b,
                            0.6,
                        ),
                    },
                    ..container::Style::default()
                })
                .into()
            })
            .collect();

        column(session_cards).spacing(12).into()
    };

    scrollable(
        column![
            header_card,
            Space::new().height(12),
            dashboard_row,
            Space::new().height(16),
            content,
        ]
    )
    .into()
}

/// Services view - manage backend services
fn view_services(state: &ContinuumStudio) -> Element<Message> {
    // Header card
    let header_card = container(
        row![
            column![
                text("Services").size(20),
                text("Manage backend services for Continuum Studio")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button("Refresh", false)
                .on_press(Message::ServiceAction(ServiceMessage::RefreshServices)),
            Space::new().width(8),
            styled_button("Start All", true)
                .on_press(Message::ServiceAction(ServiceMessage::StartAllServices)),
        ]
        .align_y(Alignment::Center),
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Service cards
    let service_cards: Vec<Element<Message>> = state
        .services
        .iter()
        .map(|service| {
            let status_color = match service.status {
                ServiceStatus::Running => iced::Color::from_rgb(0.3, 0.8, 0.3),
                ServiceStatus::Stopped => iced::Color::from_rgb(0.8, 0.3, 0.3),
                ServiceStatus::Starting => iced::Color::from_rgb(0.8, 0.7, 0.2),
                ServiceStatus::Failed => iced::Color::from_rgb(0.9, 0.2, 0.2),
                ServiceStatus::Unknown => iced::Color::from_rgb(0.5, 0.5, 0.5),
            };

            let status_indicator = container(text(""))
                .width(8)
                .height(8)
                .style(move |_theme| container::Style {
                    background: Some(iced::Background::Color(status_color)),
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..container::Style::default()
                });

            let can_start = matches!(service.status, ServiceStatus::Stopped | ServiceStatus::Failed);

            container(
                column![
                    // Title row with status
                    row![
                        status_indicator,
                        Space::new().width(12),
                        text(&service.name).size(16),
                        Space::new().width(12),
                        text(format!("{}", service.status))
                            .size(12)
                            .color(status_color),
                        Space::new().width(Length::Fill),
                        if can_start {
                            container(
                                styled_button("Start", true)
                                    .on_press(Message::ServiceAction(ServiceMessage::StartService(
                                        service.name.clone(),
                                    )))
                            )
                        } else {
                            container(
                                styled_button("Running", false)
                            )
                        },
                    ]
                    .align_y(Alignment::Center),
                    Space::new().height(8),
                    // Description
                    text(&service.description)
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    Space::new().height(12),
                    // Command box
                    container(
                        row![
                            column![
                                text("Start command:")
                                    .size(10)
                                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                                Space::new().height(4),
                                text(&service.start_command)
                                    .size(11)
                                    .font(iced::Font::MONOSPACE)
                                    .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
                            ],
                            Space::new().width(Length::Fill),
                            small_button("Copy", false)
                                .on_press(Message::ServiceAction(ServiceMessage::CopyCommand(
                                    service.start_command.clone(),
                                ))),
                        ]
                        .align_y(Alignment::Center)
                        .padding([8, 12]),
                    )
                    .width(Length::Fill)
                    .style(|_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.08, 0.08, 0.08,
                        ))),
                        border: iced::Border {
                            radius: 6.0.into(),
                            width: 1.0,
                            color: iced::Color::from_rgb(0.15, 0.15, 0.15),
                        },
                        ..container::Style::default()
                    }),
                ]
                .padding([16, 20]),
            )
            .width(Length::Fill)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.13, 0.13, 0.13,
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.2),
                },
                ..container::Style::default()
            })
            .into()
        })
        .collect();

    // Quick commands section
    let quick_commands = container(
        column![
            row![
                text("Quick Commands").size(14),
                Space::new().width(8),
                container(
                    text("bash/nu")
                        .size(9)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.6))
                )
                .padding([2, 6])
                .style(|_theme| container::Style {
                    background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.15))),
                    border: iced::Border {
                        radius: 4.0.into(),
                        width: 0.0,
                        color: iced::Color::TRANSPARENT,
                    },
                    ..container::Style::default()
                }),
            ]
            .align_y(Alignment::Center),
            Space::new().height(12),
            // Core interactive - same in both shells
            command_row(
                "Start Core (Interactive)",
                "cd /home/e421/continuum-studio/core/studio_core && iex -S mix",
            ),
            Space::new().height(8),
            // Dialog daemon - background syntax differs
            dual_command_row(
                "Start Dialog Daemon", 
                "synapsix-dialog-daemon --web-port 8080 &",
                "synapsix-dialog-daemon --web-port 8080 | ignore",
            ),
            Space::new().height(8),
            // Check dialog - same
            command_row(
                "Check Dialog Status",
                "synapsix-dialog-cli ping",
            ),
            Space::new().height(8),
            // Terminal monitor - background syntax
            dual_command_row(
                "Start Terminal Monitor",
                "synapsix-terminal-monitor &",
                "synapsix-terminal-monitor | ignore",
            ),
            Space::new().height(8),
            // UI launch
            dual_command_row(
                "Start Continuum Studio UI",
                "cd /home/e421/continuum-studio/ui-iced && ./target/release/continuum-studio-iced &",
                "cd /home/e421/continuum-studio/ui-iced; ./target/release/continuum-studio-iced | ignore",
            ),
        ]
        .padding([16, 20]),
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.12,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.18),
        },
        ..container::Style::default()
    });

    let services_list = column(service_cards).spacing(12);

    scrollable(
        column![
            header_card,
            Space::new().height(16),
            services_list,
            Space::new().height(16),
            quick_commands,
        ]
    )
    .into()
}

/// Helper for command rows in quick commands section
fn command_row<'a>(label: &'a str, command: &'a str) -> Element<'a, Message> {
    dual_command_row(label, command, command)
}

/// Helper for command rows with both bash and nushell variants
fn dual_command_row<'a>(label: &'a str, bash_cmd: &'a str, nu_cmd: &'a str) -> Element<'a, Message> {
    let bash_cmd_owned = bash_cmd.to_string();
    let nu_cmd_owned = nu_cmd.to_string();
    let is_same = bash_cmd == nu_cmd;
    
    if is_same {
        // Single command variant (shell-agnostic)
        container(
            row![
                column![
                    text(label)
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    Space::new().height(2),
                    text(bash_cmd)
                        .size(10)
                        .font(iced::Font::MONOSPACE)
                        .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
                ],
                Space::new().width(Length::Fill),
                small_button("Copy", false)
                    .on_press(Message::ServiceAction(ServiceMessage::CopyCommand(
                        bash_cmd_owned,
                    ))),
            ]
            .align_y(Alignment::Center)
            .padding([6, 10]),
        )
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.08, 0.08,
            ))),
            border: iced::Border {
                radius: 4.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.15, 0.15),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        // Dual command variant (bash + nushell)
        container(
            column![
                text(label)
                    .size(11)
                    .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().height(4),
                // Bash row
                row![
                    container(
                        text("bash")
                            .size(8)
                            .color(iced::Color::from_rgb(0.4, 0.7, 0.4))
                    )
                    .padding([1, 4])
                    .style(|_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.2, 0.1))),
                        border: iced::Border {
                            radius: 3.0.into(),
                            width: 0.0,
                            color: iced::Color::TRANSPARENT,
                        },
                        ..container::Style::default()
                    }),
                    Space::new().width(8),
                    text(bash_cmd)
                        .size(10)
                        .font(iced::Font::MONOSPACE)
                        .color(iced::Color::from_rgb(0.65, 0.65, 0.65)),
                    Space::new().width(Length::Fill),
                    small_button("Copy", false)
                        .on_press(Message::ServiceAction(ServiceMessage::CopyCommand(
                            bash_cmd_owned,
                        ))),
                ]
                .align_y(Alignment::Center),
                Space::new().height(4),
                // Nushell row
                row![
                    container(
                        text("nu")
                            .size(8)
                            .color(iced::Color::from_rgb(0.4, 0.6, 0.9))
                    )
                    .padding([1, 4])
                    .style(|_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.15, 0.25))),
                        border: iced::Border {
                            radius: 3.0.into(),
                            width: 0.0,
                            color: iced::Color::TRANSPARENT,
                        },
                        ..container::Style::default()
                    }),
                    Space::new().width(8),
                    text(nu_cmd)
                        .size(10)
                        .font(iced::Font::MONOSPACE)
                        .color(iced::Color::from_rgb(0.65, 0.65, 0.65)),
                    Space::new().width(Length::Fill),
                    small_button("Copy", false)
                        .on_press(Message::ServiceAction(ServiceMessage::CopyCommand(
                            nu_cmd_owned,
                        ))),
                ]
                .align_y(Alignment::Center),
            ]
            .padding([6, 10]),
        )
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.08, 0.08,
            ))),
            border: iced::Border {
                radius: 4.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.15, 0.15),
            },
            ..container::Style::default()
        })
        .into()
    }
}

/// Storage management view - shows disk usage and cleanup options for Cursor versions
fn view_storage(state: &ContinuumStudio) -> Element<Message> {
    let install_type = InstallationType::detect();

    // Header card
    let total_disk: u64 = state.storage_disk_usage.iter().map(|v| v.total_size).sum();
    let selected_count = state.storage_selected.len();
    let selected_size: u64 = state.storage_disk_usage.iter()
        .filter(|v| state.storage_selected.contains(&v.version))
        .map(|v| v.total_size)
        .sum();

    let header_card = container(
        column![
            row![
                column![
                    text("Storage Management").size(20),
                    text(format!("Installation: {}", install_type.description()))
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(4),
                Space::new().width(Length::Fill),
                column![
                    text(format!("Total: {}", format_bytes(total_disk)))
                        .size(16),
                    text(format!("{} versions installed", state.storage_disk_usage.len()))
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(4)
                .align_x(Alignment::End),
            ]
            .align_y(Alignment::Center),
            Space::new().height(12),
            row![
                styled_button("Scan Disk Usage", false)
                    .on_press(Message::StorageAction(StorageMessage::RefreshDiskUsage)),
                Space::new().width(12),
                styled_button("Select All", false)
                    .on_press(Message::StorageAction(StorageMessage::SelectAll)),
                Space::new().width(4),
                styled_button("Deselect", false)
                    .on_press(Message::StorageAction(StorageMessage::DeselectAll)),
                Space::new().width(Length::Fill),
                if selected_count > 0 {
                    text(format!("{} selected ({})", selected_count, format_bytes(selected_size)))
                        .size(12)
                        .color(iced::Color::from_rgb(0.9, 0.6, 0.3))
                } else {
                    text("No versions selected")
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                },
            ]
            .spacing(4)
            .align_y(Alignment::Center),
        ],
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.15))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Version table with disk usage breakdown
    let version_rows: Vec<Element<Message>> = if state.storage_loading {
        vec![
            container(
                text("Scanning disk usage...").size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5))
            )
            .padding(20)
            .into()
        ]
    } else if state.storage_disk_usage.is_empty() {
        // Show basic list from state.versions if disk usage not yet scanned
        let installed: Vec<&CursorVersion> = state.versions.iter()
            .filter(|v| v.status == VersionStatus::Installed || v.status == VersionStatus::Running)
            .collect();

        if installed.is_empty() {
            vec![
                container(
                    column![
                        text("No installed Cursor versions found.").size(14),
                        Space::new().height(4),
                        text("Install versions from the Versions tab, then click 'Scan Disk Usage'.")
                            .size(12)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    ]
                )
                .padding(20)
                .into()
            ]
        } else {
            vec![
                container(
                    column![
                        text(format!("{} installed versions detected.", installed.len())).size(14),
                        Space::new().height(4),
                        text("Click 'Scan Disk Usage' to see detailed size breakdown per version.")
                            .size(12)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    ]
                )
                .padding(20)
                .into()
            ]
        }
    } else {
        // Table header
        let header_row: Element<Message> = container(
            row![
                Space::new().width(30), // checkbox column
                text("Version").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().width(Length::Fill),
                container(text("AppImage").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6))).width(80),
                container(text("Data").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6))).width(80),
                container(text("Extensions").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6))).width(80),
                container(text("Total").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6))).width(80),
                container(text("Last Used").size(11).color(iced::Color::from_rgb(0.6, 0.6, 0.6))).width(120),
                Space::new().width(30), // status column
            ]
            .spacing(8)
            .align_y(Alignment::Center),
        )
        .padding([6, 12])
        .width(Length::Fill)
        .into();

        let mut rows = vec![header_row];

        for usage in &state.storage_disk_usage {
            let is_selected = state.storage_selected.contains(&usage.version);
            let version_clone = usage.version.clone();

            let select_btn = button(
                text(if is_selected { "x" } else { " " })
                    .size(11)
                    .font(iced::Font::MONOSPACE)
            )
            .padding([2, 6])
            .on_press_maybe(
                if usage.is_running { None } else { Some(Message::StorageAction(StorageMessage::ToggleVersionSelect(version_clone))) }
            )
            .style(move |_theme, _status| {
                button::Style {
                    background: Some(iced::Background::Color(if is_selected {
                        iced::Color::from_rgb(0.25, 0.45, 0.7)
                    } else {
                        iced::Color::from_rgb(0.2, 0.2, 0.2)
                    })),
                    text_color: iced::Color::WHITE,
                    border: iced::Border {
                        radius: 4.0.into(),
                        width: 1.0,
                        color: iced::Color::from_rgb(0.3, 0.3, 0.3),
                    },
                    shadow: iced::Shadow::default(),
                    snap: false,
                }
            });

            let version_text = text(format!("Cursor {}", usage.version)).size(13);

            let status_indicator = if usage.is_running {
                text("RUN").size(10).color(iced::Color::from_rgb(0.3, 0.8, 0.4))
            } else if usage.has_data_dir {
                text("").size(10)
            } else {
                text("").size(10)
            };

            let last_used_text = text(
                usage.last_used.as_deref().unwrap_or("Never")
            )
            .size(11)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5));

            let bg_color = if is_selected {
                iced::Color::from_rgb(0.15, 0.2, 0.28)
            } else {
                iced::Color::from_rgb(0.12, 0.12, 0.12)
            };

            let row_content: Element<Message> = container(
                row![
                    select_btn,
                    version_text,
                    Space::new().width(Length::Fill),
                    container(text(format_bytes(usage.appimage_size)).size(11)).width(80),
                    container(
                        text(if usage.has_data_dir { format_bytes(usage.data_dir_size) } else { "-".to_string() })
                            .size(11)
                    ).width(80),
                    container(
                        text(if usage.has_extensions { format_bytes(usage.extensions_size) } else { "-".to_string() })
                            .size(11)
                    ).width(80),
                    container(text(format_bytes(usage.total_size)).size(11)).width(80),
                    container(last_used_text).width(120),
                    status_indicator,
                ]
                .spacing(8)
                .align_y(Alignment::Center),
            )
            .padding([8, 12])
            .width(Length::Fill)
            .style(move |_theme| container::Style {
                background: Some(iced::Background::Color(bg_color)),
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                ..container::Style::default()
            })
            .into();

            rows.push(row_content);
        }

        rows
    };

    // Cleanup actions card (only visible when versions are selected)
    let cleanup_card: Element<Message> = if selected_count > 0 {
        container(
            column![
                text("Cleanup Options").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().height(12),
                row![
                    styled_button("Remove AppImage Only", false)
                        .on_press(Message::StorageAction(StorageMessage::CleanupSelected(CleanupMode::AppImageOnly))),
                    Space::new().width(8),
                    styled_button("Remove AppImage + Data", false)
                        .on_press(Message::StorageAction(StorageMessage::CleanupSelected(CleanupMode::AppImageAndData))),
                    Space::new().width(8),
                    styled_button("Remove All (Keep Auth)", false)
                        .on_press(Message::StorageAction(StorageMessage::CleanupSelected(CleanupMode::KeepAuth))),
                ]
                .spacing(4),
                Space::new().height(8),
                text("AppImage Only: Removes the installer, keeps data and extensions intact.")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("AppImage + Data: Removes everything including settings and extensions.")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("Keep Auth: Removes everything but extracts auth profile first.")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ],
        )
        .padding(20)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.18, 0.14, 0.12))),
            border: iced::Border {
                radius: 12.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.35, 0.25, 0.18),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Continuum Studio builds section
    let builds_section = {
        let builds_dir = continuum_studio_iced::updater::local_builds_dir();
        let nightly_exists = builds_dir.join("nightly").join("continuum-studio").exists();
        let stable_exists = builds_dir.join("stable").join("continuum-studio").exists();

        container(
            column![
                text("Continuum Studio Builds").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().height(12),
                row![
                    text("Nightly:").size(13),
                    Space::new().width(8),
                    text(if nightly_exists { "Available" } else { "Not built" })
                        .size(13)
                        .color(if nightly_exists {
                            iced::Color::from_rgb(0.3, 0.7, 0.4)
                        } else {
                            iced::Color::from_rgb(0.5, 0.5, 0.5)
                        }),
                    Space::new().width(24),
                    text("Stable:").size(13),
                    Space::new().width(8),
                    text(if stable_exists { "Available" } else { "Not built" })
                        .size(13)
                        .color(if stable_exists {
                            iced::Color::from_rgb(0.3, 0.7, 0.4)
                        } else {
                            iced::Color::from_rgb(0.5, 0.5, 0.5)
                        }),
                ],
                Space::new().height(4),
                text(format!("Builds dir: {}", builds_dir.display()))
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ],
        )
        .padding(20)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.15))),
            border: iced::Border {
                radius: 12.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.22, 0.22, 0.22),
            },
            ..container::Style::default()
        })
    };

    // Assemble the full view
    let mut content = column![
        header_card,
        Space::new().height(12),
    ];

    // Add version rows
    for row in version_rows {
        content = content.push(row);
    }

    content = content
        .push(Space::new().height(12))
        .push(cleanup_card)
        .push(Space::new().height(12))
        .push(builds_section);

    scrollable(
        container(content)
            .width(Length::Fill)
            .padding(10)
    )
    .height(Length::Fill)
    .into()
}

/// Logs view for in-app debugging
fn view_logs(state: &ContinuumStudio) -> Element<Message> {
    let entries = state.log_buffer.entries_filtered(state.log_filter);
    
    let log_rows: Vec<Element<Message>> = entries
        .iter()
        .map(|entry| {
            container(
                text(entry.format())
                    .size(11)
                    .color(entry.level_color())
                    .font(iced::Font::MONOSPACE),
            )
            .padding([4, 8])
            .width(Length::Fill)
            .into()
        })
        .collect();

    let log_content: Element<Message> = if log_rows.is_empty() {
        container(
            column![
                text("📋").size(48),
                Space::new().height(16),
                text("No log entries").size(16),
                Space::new().height(8),
                text("Logs will appear here as they are generated")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .align_x(Alignment::Center),
        )
        .width(Length::Fill)
        .padding(40)
        .into()
    } else {
        column(log_rows).spacing(2).into()
    };

    // Filter buttons
    let filter_row = row![
        text("Filter:").size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().width(12),
        log_filter_button("Error", log::Level::Error, state.log_filter),
        log_filter_button("Warn", log::Level::Warn, state.log_filter),
        log_filter_button("Info", log::Level::Info, state.log_filter),
        log_filter_button("Debug", log::Level::Debug, state.log_filter),
        log_filter_button("Trace", log::Level::Trace, state.log_filter),
        Space::new().width(Length::Fill),
        styled_button("Copy All", false)
            .on_press(Message::LogAction(LogMessage::CopyLogs)),
        Space::new().width(8),
        styled_button("Clear", false)
            .on_press(Message::LogAction(LogMessage::ClearLogs)),
    ]
    .spacing(4)
    .align_y(Alignment::Center);

    // Header card with controls
    let header_card = container(
        column![
            row![
                column![
                    text("Logs").size(20),
                    text(format!("{} entries", entries.len()))
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(4),
            ],
            Space::new().height(12),
            filter_row,
        ],
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    });

    // Log container with monospace font
    let log_container = container(scrollable(log_content).height(500))
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.08, 0.08,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.15, 0.15),
            },
            ..container::Style::default()
        });

    column![
        header_card,
        Space::new().height(16),
        log_container,
    ]
    .spacing(0)
    .into()
}

/// Filter button for log level selection
fn log_filter_button(label: &'static str, level: log::Level, current: log::Level) -> Element<'static, Message> {
    let is_active = level == current;
    
    button(text(label).size(11))
        .padding([4, 10])
        .style(move |_theme, status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.45, 0.7)
            } else {
                match status {
                    button::Status::Hovered => iced::Color::from_rgb(0.25, 0.25, 0.25),
                    _ => iced::Color::from_rgb(0.18, 0.18, 0.18),
                }
            };
            
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: if is_active {
                    iced::Color::WHITE
                } else {
                    iced::Color::from_rgb(0.7, 0.7, 0.7)
                },
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
        .on_press(Message::LogAction(LogMessage::SetFilter(level)))
        .into()
}

/// Settings view with polished card-based layout
fn view_settings(state: &ContinuumStudio) -> Element<Message> {
    // Header
    let header = column![
        text("Settings").size(26),
        text("Configure Continuum Studio")
            .size(14)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .spacing(4);

    // Appearance card
    let appearance_card = settings_card(
        "Appearance",
        column![
            settings_row(
                "Theme",
                row![
                    theme_pill(
                        "System",
                        state.settings.theme == ThemePreference::System,
                        ThemePreference::System
                    ),
                    theme_pill(
                        "Dark",
                        state.settings.theme == ThemePreference::Dark,
                        ThemePreference::Dark
                    ),
                    theme_pill(
                        "Light",
                        state.settings.theme == ThemePreference::Light,
                        ThemePreference::Light
                    ),
                    theme_pill(
                        "COSMIC",
                        state.settings.theme == ThemePreference::Cosmic,
                        ThemePreference::Cosmic
                    ),
                ]
                .spacing(6)
            ),
            if state.settings.theme == ThemePreference::Cosmic {
                settings_row(
                    "COSMIC Preset",
                    row![
                        cosmic_pill(
                            "Dark",
                            state.settings.cosmic_preset == CosmicPreset::Dark,
                            CosmicPreset::Dark
                        ),
                        cosmic_pill(
                            "Light",
                            state.settings.cosmic_preset == CosmicPreset::Light,
                            CosmicPreset::Light
                        ),
                        cosmic_pill(
                            "Pop",
                            state.settings.cosmic_preset == CosmicPreset::PopOrange,
                            CosmicPreset::PopOrange
                        ),
                        cosmic_pill(
                            "Blue",
                            state.settings.cosmic_preset == CosmicPreset::CoolBlue,
                            CosmicPreset::CoolBlue
                        ),
                    ]
                    .spacing(6),
                )
            } else {
                Space::new().height(0).into()
            },
        ]
        .spacing(16),
    );

    // Connection card
    let connection_card = settings_card(
        "Connection",
        column![
            settings_row(
                "Auto-connect",
                toggle_button(
                    state.settings.auto_connect,
                    SettingsMessage::ToggleAutoConnect
                )
            ),
            settings_row(
                "Auto-start services",
                toggle_button(
                    state.settings.auto_start_services,
                    SettingsMessage::ToggleAutoStartServices
                )
            ),
            settings_row(
                "Socket path",
                text(&state.settings.core_socket_path)
                    .size(12)
                    .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
            ),
        ]
        .spacing(16),
    );

    // Notifications card
    let notifications_card = settings_card(
        "Notifications",
        settings_row(
            "Version updates",
            toggle_button(
                state.settings.notify_new_versions,
                SettingsMessage::ToggleNotifications,
            ),
        ),
    );
    
    // Updates card
    let update_status: Element<Message> = if state.checking_updates {
        text("Checking for updates...")
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
            .into()
    } else if let Some(ref update) = state.available_update {
        column![
            text(format!("Update available: v{}", update.version))
                .size(12)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            text("Run 'nix build' to update")
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4)
        .into()
    } else {
        text(format!("Current: v{}", state.settings.updates.current_version))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
            .into()
    };
    
    let updates_card = settings_card(
        "Updates",
        column![
            settings_row(
                "Update channel",
                row![
                    channel_pill("Stable", state.settings.updates.channel == UpdateChannel::Stable, UpdateChannel::Stable),
                    channel_pill("Beta", state.settings.updates.channel == UpdateChannel::Beta, UpdateChannel::Beta),
                    channel_pill("Nightly", state.settings.updates.channel == UpdateChannel::Nightly, UpdateChannel::Nightly),
                ]
                .spacing(6),
            ),
            Space::new().height(8),
            settings_row(
                "Release source",
                row![
                    forge_pill("Local", state.settings.updates.forge_type == ForgeType::Local, ForgeType::Local),
                    forge_pill("GitHub", state.settings.updates.forge_type == ForgeType::GitHub, ForgeType::GitHub),
                    forge_pill("Forgejo", state.settings.updates.forge_type == ForgeType::Forgejo, ForgeType::Forgejo),
                ]
                .spacing(6),
            ),
            Space::new().height(4),
            text(state.settings.updates.forge_type.description())
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(8),
            settings_row(
                "Auto-check updates",
                toggle_button(
                    state.settings.updates.auto_check,
                    SettingsMessage::ToggleAutoCheckUpdates,
                ),
            ),
            Space::new().height(8),
            settings_row(
                "Status",
                row![
                    update_status,
                    Space::new().width(12),
                    styled_button("Check Now", false)
                        .on_press(Message::SettingsAction(SettingsMessage::CheckForUpdates)),
                ]
                .align_y(Alignment::Center),
            ),
        ]
        .spacing(8),
    );

    // Dialog routing card
    let dialog_status_text = if state.settings.synapsix_dialog_routing {
        "Active - agents will use synapsix-dialog-cli"
    } else {
        "Disabled - agents use Cursor's built-in AskQuestion"
    };

    let dialog_card = settings_card(
        "Dialog Routing",
        column![
            settings_row(
                "Route via Synapsix",
                toggle_button(
                    state.settings.synapsix_dialog_routing,
                    SettingsMessage::ToggleSynapsixDialogRouting,
                ),
            ),
            Space::new().height(4),
            text(dialog_status_text)
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(4),
            text(format!("Managed workspaces: {}", state.settings.managed_workspaces.len()))
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4),
    );

    // Save button
    let save_section: Element<Message> = if state.settings_dirty {
        styled_button("Save Settings", true)
            .on_press(Message::SettingsAction(SettingsMessage::SaveSettings))
            .into()
    } else {
        text("✓ Settings saved")
            .size(12)
            .color(iced::Color::from_rgb(0.4, 0.6, 0.4))
            .into()
    };

    // Footer info
    let footer = text(format!("Config: {}", Settings::file_path().display()))
        .size(10)
        .color(iced::Color::from_rgb(0.4, 0.4, 0.4));

    column![
        header,
        Space::new().height(24),
        appearance_card,
        Space::new().height(12),
        connection_card,
        Space::new().height(12),
        notifications_card,
        Space::new().height(12),
        updates_card,
        Space::new().height(12),
        dialog_card,
        Space::new().height(24),
        save_section,
        Space::new().height(16),
        footer,
    ]
    .spacing(0)
    .into()
}

/// Update channel pill button
fn channel_pill(
    label: &'static str,
    is_active: bool,
    channel: UpdateChannel,
) -> Element<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .on_press(Message::SettingsAction(SettingsMessage::SetUpdateChannel(channel)))
        .style(move |_theme, status| pill_style(is_active, status))
        .into()
}

/// Forge type pill button for release source selection
fn forge_pill(
    label: &'static str,
    is_active: bool,
    forge_type: ForgeType,
) -> Element<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .on_press(Message::SettingsAction(SettingsMessage::SetForgeType(forge_type)))
        .style(move |_theme, status| pill_style(is_active, status))
        .into()
}

/// Settings card container
fn settings_card<'a>(
    title: &'a str,
    content: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    container(
        column![
            text(title)
                .size(14)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            content.into(),
        ]
        .spacing(0),
    )
    .padding(20)
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.15,
        ))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.22),
        },
        ..container::Style::default()
    })
    .into()
}

/// Settings row with label and control
fn settings_row<'a>(
    label: &'a str,
    control: impl Into<Element<'a, Message>>,
) -> Element<'a, Message> {
    row![
        text(label).size(13),
        Space::new().width(Length::Fill),
        control.into(),
    ]
    .align_y(Alignment::Center)
    .into()
}

/// Theme selection pill button
fn theme_pill(
    label: &'static str,
    is_active: bool,
    pref: ThemePreference,
) -> Element<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .on_press(Message::ThemePreferenceChanged(pref))
        .style(move |_theme, status| pill_style(is_active, status))
        .into()
}

/// COSMIC preset pill button
fn cosmic_pill(
    label: &'static str,
    is_active: bool,
    preset: CosmicPreset,
) -> Element<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .on_press(Message::CosmicPresetChanged(preset))
        .style(move |_theme, status| pill_style(is_active, status))
        .into()
}

/// Toggle button for settings
fn toggle_button(is_enabled: bool, msg: SettingsMessage) -> Element<'static, Message> {
    button(text(if is_enabled { "✓ On" } else { "Off" }).size(11))
        .padding([6, 14])
        .on_press(Message::SettingsAction(msg))
        .style(move |_theme, status| {
            let bg = if is_enabled {
                match status {
                    button::Status::Active => iced::Color::from_rgb(0.25, 0.5, 0.35),
                    button::Status::Hovered => iced::Color::from_rgb(0.3, 0.55, 0.4),
                    button::Status::Pressed => iced::Color::from_rgb(0.2, 0.45, 0.3),
                    button::Status::Disabled => iced::Color::from_rgb(0.2, 0.35, 0.25),
                }
            } else {
                match status {
                    button::Status::Active => iced::Color::from_rgb(0.22, 0.22, 0.22),
                    button::Status::Hovered => iced::Color::from_rgb(0.28, 0.28, 0.28),
                    button::Status::Pressed => iced::Color::from_rgb(0.18, 0.18, 0.18),
                    button::Status::Disabled => iced::Color::from_rgb(0.18, 0.18, 0.18),
                }
            };

            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
        .into()
}

/// Pill button style
fn pill_style(is_active: bool, status: button::Status) -> button::Style {
    let bg = if is_active {
        match status {
            button::Status::Active => iced::Color::from_rgb(0.3, 0.5, 0.9),
            button::Status::Hovered => iced::Color::from_rgb(0.35, 0.55, 0.95),
            button::Status::Pressed => iced::Color::from_rgb(0.25, 0.45, 0.85),
            button::Status::Disabled => iced::Color::from_rgb(0.2, 0.35, 0.6),
        }
    } else {
        match status {
            button::Status::Active => iced::Color::from_rgb(0.22, 0.22, 0.22),
            button::Status::Hovered => iced::Color::from_rgb(0.28, 0.28, 0.28),
            button::Status::Pressed => iced::Color::from_rgb(0.18, 0.18, 0.18),
            button::Status::Disabled => iced::Color::from_rgb(0.18, 0.18, 0.18),
        }
    };

    button::Style {
        background: Some(iced::Background::Color(bg)),
        text_color: if is_active {
            iced::Color::WHITE
        } else {
            iced::Color::from_rgb(0.7, 0.7, 0.7)
        },
        border: iced::Border {
            radius: 6.0.into(),
            width: 0.0,
            color: iced::Color::TRANSPARENT,
        },
        shadow: iced::Shadow::default(),
        snap: false,
    }
}

// ===========================================================================
// Chat Pipeline Integration
// ===========================================================================

fn handle_chat_pipeline_message(state: &mut ContinuumStudio, msg: ChatPipelineMsg) -> Task<Message> {
    match msg {
        ChatPipelineMsg::SwitchSubView(sub) => {
            state.chat_pipeline.sub_view = sub;
            // Auto-fetch data for the sub-view
            match sub {
                ChatSubView::Scanner => {
                    if state.chat_pipeline.locations.is_none() {
                        state.chat_pipeline.loading = true;
                        return Task::perform(
                            async { ChatApiClient::fetch_locations().await },
                            |result| Message::ChatPipelineAction(ChatPipelineMsg::LocationsLoaded(result)),
                        );
                    }
                }
                ChatSubView::Topics => {
                    state.chat_pipeline.loading = true;
                    return Task::perform(
                        async { ChatApiClient::fetch_topics().await },
                        |result| Message::ChatPipelineAction(
                            ChatPipelineMsg::TopicsLoaded(result.map(|t| t.topics)),
                        ),
                    );
                }
                ChatSubView::Conversations => {
                    state.chat_pipeline.loading = true;
                    return Task::perform(
                        async { ChatApiClient::fetch_conversations(100).await },
                        |result| Message::ChatPipelineAction(
                            ChatPipelineMsg::ConversationsLoaded(result.map(|c| c.conversations)),
                        ),
                    );
                }
                _ => {}
            }
        }
        ChatPipelineMsg::RefreshAll => {
            state.chat_pipeline.loading = true;
            state.chat_pipeline.error = None;
            // Fire off parallel fetches
            let stats_task = Task::perform(
                async { ChatApiClient::fetch_stats().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result)),
            );
            let health_task = Task::perform(
                async { ChatApiClient::fetch_health().await },
                |result| Message::ChatPipelineAction(
                    ChatPipelineMsg::HealthChecked(result.map(|h| h.status == "ok")),
                ),
            );
            let convos_task = Task::perform(
                async { ChatApiClient::fetch_conversations(100).await },
                |result| Message::ChatPipelineAction(
                    ChatPipelineMsg::ConversationsLoaded(result.map(|c| c.conversations)),
                ),
            );
            return Task::batch([stats_task, health_task, convos_task]);
        }
        ChatPipelineMsg::HealthChecked(result) => {
            match result {
                Ok(ok) => {
                    state.chat_pipeline.api_available = ok;
                    state.chat_pipeline.loading = false;
                }
                Err(e) => {
                    state.chat_pipeline.api_available = false;
                    state.chat_pipeline.error = Some(format!("API unavailable: {}", e));
                    state.chat_pipeline.loading = false;
                }
            }
        }
        ChatPipelineMsg::StatsLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(stats) => {
                    state.chat_pipeline.api_available = true;
                    state.chat_pipeline.stats = Some(stats);
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to load stats: {}", e));
                }
            }
        }
        ChatPipelineMsg::ConversationsLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(convos) => state.chat_pipeline.conversations = convos,
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to load conversations: {}", e));
                }
            }
        }
        ChatPipelineMsg::ConversationLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(detail) => state.chat_pipeline.selected_conversation = Some(detail),
                Err(e) => {
                    state.chat_pipeline.error =
                        Some(format!("Failed to load conversation: {}", e));
                }
            }
        }
        ChatPipelineMsg::TopicsLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(topics) => state.chat_pipeline.topics = topics,
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to load topics: {}", e));
                }
            }
        }
        ChatPipelineMsg::SearchCompleted(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(results) => state.chat_pipeline.search_results = results,
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Search failed: {}", e));
                }
            }
        }
        ChatPipelineMsg::LocationsLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(locations) => state.chat_pipeline.locations = Some(locations),
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to scan locations: {}", e));
                }
            }
        }
        ChatPipelineMsg::SearchQueryChanged(query) => {
            state.chat_pipeline.search_query = query;
        }
        ChatPipelineMsg::DoSearch => {
            let query = state.chat_pipeline.search_query.clone();
            if !query.is_empty() {
                state.chat_pipeline.loading = true;
                return Task::perform(
                    async move { ChatApiClient::search(query, "hybrid".to_string()).await },
                    |result| Message::ChatPipelineAction(
                        ChatPipelineMsg::SearchCompleted(result.map(|s| s.results)),
                    ),
                );
            }
        }
        ChatPipelineMsg::SelectConversation(id) => {
            state.chat_pipeline.loading = true;
            state.chat_pipeline.expanded_messages.clear();
            state.chat_pipeline.show_full_conversation = false;
            return Task::perform(
                async move { ChatApiClient::fetch_conversation(id).await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::ConversationLoaded(result)),
            );
        }
        ChatPipelineMsg::BackToList => {
            state.chat_pipeline.selected_conversation = None;
            state.chat_pipeline.expanded_messages.clear();
            state.chat_pipeline.show_full_conversation = false;
        }
        ChatPipelineMsg::DoBatchIngest => {
            state.chat_pipeline.loading = true;
            state.chat_pipeline.last_action_result = None;
            return Task::perform(
                async { ChatApiClient::batch_ingest().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::BatchIngestDone(
                    result.map(|r| format!("Imported: {}, Summarized: {}, Topics: {}",
                        r.imported, r.summarize_queued, r.topics_created)),
                )),
            );
        }
        ChatPipelineMsg::DoSummarizePending => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::summarize_pending().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::SummarizeDone(
                    result.map(|v| format!("{}", v)),
                )),
            );
        }
        ChatPipelineMsg::DoClusterTopics => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::cluster_topics().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::ClusterDone(
                    result.map(|v| format!("{}", v)),
                )),
            );
        }
        ChatPipelineMsg::DoImportOrphaned => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::import_orphaned().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::ImportOrphanedDone(
                    result.map(|v| format!("{}", v)),
                )),
            );
        }
        ChatPipelineMsg::BatchIngestDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => state.chat_pipeline.last_action_result = Some(format!("Batch ingest: {}", msg)),
                Err(e) => state.chat_pipeline.error = Some(format!("Batch ingest failed: {}", e)),
            }
            // Refresh stats
            return Task::perform(
                async { ChatApiClient::fetch_stats().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result)),
            );
        }
        ChatPipelineMsg::SummarizeDone(result) | ChatPipelineMsg::ImportOrphanedDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => state.chat_pipeline.last_action_result = Some(msg),
                Err(e) => state.chat_pipeline.error = Some(e),
            }
            return Task::perform(
                async { ChatApiClient::fetch_stats().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result)),
            );
        }
        ChatPipelineMsg::ClusterDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => state.chat_pipeline.last_action_result = Some(msg),
                Err(e) => state.chat_pipeline.error = Some(e),
            }
            // Refresh both stats and topics after clustering
            let stats_task = Task::perform(
                async { ChatApiClient::fetch_stats().await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result)),
            );
            let topics_task = Task::perform(
                async { ChatApiClient::fetch_topics().await },
                |result| Message::ChatPipelineAction(
                    ChatPipelineMsg::TopicsLoaded(result.map(|t| t.topics)),
                ),
            );
            return Task::batch([stats_task, topics_task]);
        }
        ChatPipelineMsg::ClearError => {
            state.chat_pipeline.error = None;
        }
        ChatPipelineMsg::ClearActionResult => {
            state.chat_pipeline.last_action_result = None;
        }
        ChatPipelineMsg::ToggleMessageExpand(idx) => {
            if state.chat_pipeline.expanded_messages.contains(&idx) {
                state.chat_pipeline.expanded_messages.remove(&idx);
            } else {
                state.chat_pipeline.expanded_messages.insert(idx);
            }
        }
        ChatPipelineMsg::ToggleShowFull => {
            state.chat_pipeline.show_full_conversation = !state.chat_pipeline.show_full_conversation;
            if !state.chat_pipeline.show_full_conversation {
                state.chat_pipeline.expanded_messages.clear();
            }
        }
    }
    Task::none()
}

// ---------------------------------------------------------------------------
// Chat Pipeline View
// ---------------------------------------------------------------------------

fn view_chat_pipeline(state: &ContinuumStudio) -> Element<Message> {
    let cp = &state.chat_pipeline;

    // Sub-navigation tabs
    let tab_bar = chat_tab_bar(cp.sub_view);

    // Status indicator
    let status = if cp.api_available {
        text("● Synapsix Connected")
            .size(12)
            .color(iced::Color::from_rgb(0.25, 0.75, 0.35))
    } else {
        text("○ Synapsix Offline")
            .size(12)
            .color(iced::Color::from_rgb(0.75, 0.35, 0.35))
    };

    // Error banner
    let error_banner: Element<Message> = if let Some(ref err) = cp.error {
        container(
            row![
                text(format!("⚠ {}", err))
                    .size(12)
                    .color(iced::Color::from_rgb(1.0, 0.6, 0.4)),
                Space::new().width(Length::Fill),
                button(text("✕").size(12))
                    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::ClearError))
                    .padding([2, 8]),
            ]
            .align_y(Alignment::Center),
        )
        .padding(8)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.1, 0.1))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.4, 0.2, 0.2),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Action result banner
    let result_banner: Element<Message> = if let Some(ref msg) = cp.last_action_result {
        container(
            row![
                text(format!("✓ {}", msg))
                    .size(12)
                    .color(iced::Color::from_rgb(0.4, 0.9, 0.5)),
                Space::new().width(Length::Fill),
                button(text("✕").size(12))
                    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::ClearActionResult))
                    .padding([2, 8]),
            ]
            .align_y(Alignment::Center),
        )
        .padding(8)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.08, 0.18, 0.1))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.2, 0.4, 0.25),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Content based on sub-view
    let content: Element<Message> = match cp.sub_view {
        ChatSubView::Dashboard => chat_dashboard_view(cp),
        ChatSubView::Scanner => chat_scanner_view(cp),
        ChatSubView::Conversations => {
            if cp.selected_conversation.is_some() {
                chat_conversation_detail_view(cp)
            } else {
                chat_conversations_view(cp)
            }
        }
        ChatSubView::Topics => chat_topics_view(cp),
        ChatSubView::Search => chat_search_view(cp),
    };

    // Header
    let header = row![
        column![
            text("Chat Pipeline").size(24),
            text("Synapsix-powered chat intelligence")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4),
        Space::new().width(Length::Fill),
        status,
        Space::new().width(10),
        button(text(if cp.loading { "⟳ Loading..." } else { "⟳ Refresh" }).size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(ChatPipelineMsg::RefreshAll))
            })
            .padding([6, 12]),
    ]
    .align_y(Alignment::Center)
    .spacing(8);

    scrollable(
        column![header, tab_bar, error_banner, result_banner, content,]
            .spacing(12)
            .width(Length::Fill),
    )
    .into()
}

fn chat_tab_bar(current: ChatSubView) -> Element<'static, Message> {
    let tab = |label: &'static str, sub: ChatSubView| -> Element<'static, Message> {
        let is_active = current == sub;
        let btn = button(text(label).size(13))
            .on_press(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(sub)))
            .padding([6, 16])
            .style(move |_theme, _status| {
                let bg = if is_active {
                    iced::Color::from_rgb(0.2, 0.35, 0.55)
                } else {
                    iced::Color::from_rgb(0.15, 0.15, 0.15)
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: if is_active {
                        iced::Color::WHITE
                    } else {
                        iced::Color::from_rgb(0.6, 0.6, 0.6)
                    },
                    border: iced::Border {
                        radius: 6.0.into(),
                        width: 0.0,
                        color: iced::Color::TRANSPARENT,
                    },
                    shadow: iced::Shadow::default(),
                    snap: false,
                }
            });
        btn.into()
    };

    row![
        tab("Dashboard", ChatSubView::Dashboard),
        tab("Scanner", ChatSubView::Scanner),
        tab("Conversations", ChatSubView::Conversations),
        tab("Topics", ChatSubView::Topics),
        tab("Search", ChatSubView::Search),
    ]
    .spacing(4)
    .into()
}

// ---------------------------------------------------------------------------
// Chat Sub-Views
// ---------------------------------------------------------------------------

fn chat_dashboard_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let stats_cards: Element<'_, Message> = if let Some(ref stats) = cp.stats {
        let s = &stats.store;
        let sm = &stats.summarizer;

        let card = |title: &str, value: String, color: iced::Color| -> Element<'static, Message> {
            let t = title.to_string();
            container(
                column![
                    text(value).size(28).color(color),
                    text(t)
                        .size(11)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(4)
                .align_x(Alignment::Center),
            )
            .padding(16)
            .width(Length::Fill)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.15,
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.25),
                },
                ..container::Style::default()
            })
            .into()
        };

        column![
            text("Overview").size(16),
            row![
                card("Conversations", fmt_num(s.conversations), iced::Color::from_rgb(0.4, 0.7, 1.0)),
                card("Messages", fmt_num(s.messages), iced::Color::from_rgb(0.5, 0.8, 0.5)),
                card("Chunks", fmt_num(s.chunks), iced::Color::from_rgb(0.8, 0.7, 0.4)),
                card("Embeddings", fmt_num(s.embeddings), iced::Color::from_rgb(0.7, 0.5, 0.9)),
            ]
            .spacing(8),
            row![
                card("Summaries", fmt_num(s.summaries), iced::Color::from_rgb(0.4, 0.8, 0.8)),
                card("Topics", fmt_num(s.topics), iced::Color::from_rgb(0.9, 0.5, 0.6)),
                card("LLM Calls", fmt_num(sm.total_llm_calls), iced::Color::from_rgb(0.8, 0.6, 0.4)),
                card("Workspace Summaries", fmt_num(s.workspace_summaries), iced::Color::from_rgb(0.6, 0.7, 0.9)),
            ]
            .spacing(8),
            Space::new().height(16),
            text("Actions").size(16),
            row![
                button(text("Import + Summarize + Cluster").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(ChatPipelineMsg::DoBatchIngest))
                    })
                    .padding([8, 16]),
                button(text("Summarize Pending").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(ChatPipelineMsg::DoSummarizePending))
                    })
                    .padding([8, 16]),
                button(text("Cluster Topics").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(ChatPipelineMsg::DoClusterTopics))
                    })
                    .padding([8, 16]),
                button(text("Import Orphaned").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(ChatPipelineMsg::DoImportOrphaned))
                    })
                    .padding([8, 16]),
            ]
            .spacing(8),
        ]
        .spacing(12)
        .into()
    } else if cp.loading {
        container(
            text("Loading pipeline stats...")
                .size(14)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .center_x(Length::Fill)
        .padding(40)
        .into()
    } else {
        container(
            column![
                text("No data available").size(16),
                text("Make sure Synapsix is running on port 4001")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(8),
                button(text("Connect & Refresh").size(12))
                    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::RefreshAll))
                    .padding([8, 16]),
            ]
            .spacing(8)
            .align_x(Alignment::Center),
        )
        .center_x(Length::Fill)
        .padding(40)
        .into()
    };

    stats_cards
}

fn chat_scanner_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let content: Element<'_, Message> = if let Some(ref locs) = cp.locations {
        let header = row![
            text(format!("Platform: {}", locs.platform)).size(14),
            Space::new().width(Length::Fill),
            text(format!("{} locations found", locs.location_count))
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ];

        let mut items = column![].spacing(4);
        for loc in &locs.locations {
            let exists_indicator = if loc.exists {
                text("●")
                    .size(10)
                    .color(iced::Color::from_rgb(0.3, 0.8, 0.4))
            } else {
                text("○")
                    .size(10)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
            };

            let loc_row = container(
                row![
                    exists_indicator,
                    Space::new().width(8),
                    column![
                        text(truncate(&loc.path, 80))
                            .size(12)
                            .color(iced::Color::from_rgb(0.8, 0.8, 0.8)),
                        row![
                            text(format!("Type: {}", loc.loc_type))
                                .size(10)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(12),
                            text(format!("Source: {}", loc.source))
                                .size(10)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(12),
                            text(if loc.size_bytes > 0 {
                                fmt_bytes(loc.size_bytes)
                            } else {
                                "—".to_string()
                            })
                            .size(10)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        ]
                        .spacing(4),
                    ]
                    .spacing(2),
                ]
                .align_y(Alignment::Center),
            )
            .padding([6, 10])
            .width(Length::Fill)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.1, 0.1, 0.12,
                ))),
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.2),
                },
                ..container::Style::default()
            });

            items = items.push(loc_row);
        }

        column![
            header,
            Space::new().height(8),
            button(text("Rescan Locations").size(12))
                .on_press_maybe(if cp.loading {
                    None
                } else {
                    Some(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(ChatSubView::Scanner)))
                })
                .padding([6, 12]),
            Space::new().height(8),
            items,
        ]
        .spacing(8)
        .into()
    } else if cp.loading {
        container(
            text("Scanning locations...")
                .size(14)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .center_x(Length::Fill)
        .padding(40)
        .into()
    } else {
        container(
            column![
                text("Click to scan for Cursor databases").size(14),
                Space::new().height(8),
                button(text("Scan Locations").size(12))
                    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(ChatSubView::Scanner)))
                    .padding([8, 16]),
            ]
            .spacing(4)
            .align_x(Alignment::Center),
        )
        .center_x(Length::Fill)
        .padding(40)
        .into()
    };

    column![
        text("Database Scanner").size(16),
        content,
    ]
    .spacing(12)
    .into()
}

fn chat_conversations_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let count = cp.conversations.len();
    let header = row![
        text("Conversations").size(16),
        Space::new().width(Length::Fill),
        text(format!("{} total", count))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ];

    if cp.conversations.is_empty() && !cp.loading {
        return column![
            header,
            container(
                text("No conversations found. Run batch ingest from Dashboard.")
                    .size(13)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .center_x(Length::Fill)
            .padding(40),
        ]
        .spacing(12)
        .into();
    }

    let mut list = column![].spacing(4);
    for conv in &cp.conversations {
        let title = conv
            .title
            .as_deref()
            .unwrap_or("Untitled conversation");
        let workspace = conv
            .workspace
            .as_deref()
            .unwrap_or("unknown");

        let has_summary = conv.summary.is_some();
        let id = conv.id.clone();

        let conv_row = button(
            container(
                row![
                    column![
                        text(truncate(title, 70))
                            .size(13)
                            .color(iced::Color::from_rgb(0.85, 0.85, 0.9)),
                        row![
                            text(format!("{} msgs", conv.message_count))
                                .size(10)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(8),
                            text(truncate(workspace, 30))
                                .size(10)
                                .color(iced::Color::from_rgb(0.4, 0.5, 0.6)),
                            Space::new().width(8),
                            text(if has_summary { "✓ summarized" } else { "" })
                                .size(10)
                                .color(iced::Color::from_rgb(0.4, 0.7, 0.5)),
                        ]
                        .spacing(4),
                    ]
                    .spacing(3)
                    .width(Length::Fill),
                    text(if conv.is_agentic { "🤖" } else { "💬" }).size(14),
                ]
                .align_y(Alignment::Center),
            )
            .padding([8, 12])
            .width(Length::Fill),
        )
        .on_press(Message::ChatPipelineAction(ChatPipelineMsg::SelectConversation(id)))
        .width(Length::Fill)
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.1, 0.12,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.18, 0.2),
            },
            shadow: iced::Shadow::default(),
            snap: false,
        });

        list = list.push(conv_row);
    }

    column![header, list,].spacing(12).into()
}

fn chat_conversation_detail_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let detail = match &cp.selected_conversation {
        Some(d) => d,
        None => {
            return text("No conversation selected").into();
        }
    };

    let conv = &detail.conversation;
    let title = conv
        .title
        .as_deref()
        .unwrap_or("Untitled");

    let back_btn = button(text("← Back to list").size(12))
        .on_press(Message::ChatPipelineAction(ChatPipelineMsg::BackToList))
        .padding([6, 12]);

    let expand_btn = button(
        text(if cp.show_full_conversation {
            "Collapse All"
        } else {
            "Expand All"
        })
        .size(12),
    )
    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::ToggleShowFull))
    .padding([6, 12]);

    let header = column![
        row![
            back_btn,
            Space::new().width(Length::Fill),
            expand_btn,
        ]
        .align_y(Alignment::Center),
        Space::new().height(8),
        text(title).size(18),
        row![
            text(format!("{} messages", conv.message_count))
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().width(12),
            text(format!(
                "Tokens: {} in / {} out",
                fmt_num(conv.total_input_tokens),
                fmt_num(conv.total_output_tokens)
            ))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().width(12),
            text(if conv.is_agentic { "🤖 Agentic" } else { "💬 Standard" })
                .size(12),
        ]
        .spacing(4),
    ]
    .spacing(4);

    // Summary section
    let summary: Element<'_, Message> = if let Some(ref s) = conv.summary {
        container(
            column![
                text("Summary").size(14).color(iced::Color::from_rgb(0.6, 0.8, 1.0)),
                text(s.as_str()).size(12).color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
            ]
            .spacing(4),
        )
        .padding(12)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.12, 0.16))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.2, 0.3),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Messages with expand/collapse
    let mut messages = column![].spacing(4);
    for (idx, msg) in detail.messages.iter().enumerate() {
        let is_expanded = cp.show_full_conversation || cp.expanded_messages.contains(&idx);
        let (role_label, role_color) = match msg.role.as_str() {
            "user" => ("User", iced::Color::from_rgb(0.4, 0.7, 1.0)),
            "assistant" => ("Assistant", iced::Color::from_rgb(0.5, 0.9, 0.5)),
            _ => ("System", iced::Color::from_rgb(0.6, 0.6, 0.6)),
        };

        let content_text = if is_expanded {
            msg.content.clone()
        } else if msg.content.len() > 300 {
            format!("{}... [click to expand]", &msg.content[..300.min(msg.content.len())])
        } else {
            msg.content.clone()
        };

        let is_long = msg.content.len() > 300;
        let char_count = msg.content.len();
        let token_info = if msg.input_tokens > 0 || msg.output_tokens > 0 {
            format!(" | {}in/{}out tokens", fmt_num(msg.input_tokens), fmt_num(msg.output_tokens))
        } else {
            String::new()
        };

        let msg_content = container(
            column![
                row![
                    text(role_label)
                        .size(11)
                        .color(role_color),
                    Space::new().width(8),
                    text(format!("#{}", idx + 1))
                        .size(10)
                        .color(iced::Color::from_rgb(0.35, 0.35, 0.4)),
                    Space::new().width(Length::Fill),
                    text(format!(
                        "{} chars{}",
                        fmt_num(char_count as u64),
                        token_info,
                    ))
                    .size(10)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.5)),
                    Space::new().width(8),
                    text(msg.model.as_deref().unwrap_or(""))
                        .size(10)
                        .color(iced::Color::from_rgb(0.4, 0.4, 0.5)),
                ],
                text(content_text)
                    .size(12)
                    .color(iced::Color::from_rgb(0.75, 0.75, 0.75)),
            ]
            .spacing(4),
        )
        .padding([8, 12])
        .width(Length::Fill)
        .style(move |_theme| {
            let bg = match role_label {
                "User" => iced::Color::from_rgb(0.1, 0.12, 0.16),
                "Assistant" => iced::Color::from_rgb(0.1, 0.14, 0.1),
                _ => iced::Color::from_rgb(0.1, 0.1, 0.1),
            };
            container::Style {
                background: Some(iced::Background::Color(bg)),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.2),
                },
                ..container::Style::default()
            }
        });

        // Make long messages clickable to expand/collapse
        if is_long {
            let msg_btn = button(msg_content)
                .on_press(Message::ChatPipelineAction(
                    ChatPipelineMsg::ToggleMessageExpand(idx),
                ))
                .width(Length::Fill)
                .style(|_theme, _status| button::Style {
                    background: None,
                    text_color: iced::Color::WHITE,
                    border: iced::Border {
                        radius: 0.0.into(),
                        width: 0.0,
                        color: iced::Color::TRANSPARENT,
                    },
                    shadow: iced::Shadow::default(),
                    snap: false,
                });
            messages = messages.push(msg_btn);
        } else {
            messages = messages.push(msg_content);
        }
    }

    column![
        header,
        summary,
        Space::new().height(8),
        text(format!("Messages ({})", detail.messages.len())).size(14),
        messages,
    ]
    .spacing(8)
    .into()
}

fn chat_topics_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let header = row![
        text("Topics").size(16),
        Space::new().width(Length::Fill),
        text(format!("{} topics", cp.topics.len()))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(8),
        button(text("Refresh").size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(ChatSubView::Topics)))
            })
            .padding([6, 12]),
        Space::new().width(4),
        button(text("Re-cluster").size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(ChatPipelineMsg::DoClusterTopics))
            })
            .padding([6, 12]),
    ]
    .align_y(Alignment::Center);

    if cp.topics.is_empty() && !cp.loading {
        return column![
            header,
            container(
                column![
                    text("No topics yet").size(14),
                    text("Run 'Re-cluster' above or use 'Import + Summarize + Cluster' from Dashboard")
                        .size(12)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(4)
                .align_x(Alignment::Center),
            )
            .center_x(Length::Fill)
            .padding(40),
        ]
        .spacing(12)
        .into();
    }

    if cp.loading {
        return column![
            header,
            container(
                text("Loading topics...")
                    .size(14)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .center_x(Length::Fill)
            .padding(40),
        ]
        .spacing(12)
        .into();
    }

    let mut list = column![].spacing(8);
    for topic in &cp.topics {
        let desc = topic
            .description
            .as_deref()
            .unwrap_or("No description");

        let topic_card = container(
            column![
                row![
                    text(&topic.label)
                        .size(15)
                        .color(iced::Color::from_rgb(0.8, 0.7, 1.0)),
                    Space::new().width(Length::Fill),
                    container(
                        text(format!("{} conversations", topic.conversation_count))
                            .size(11)
                            .color(iced::Color::from_rgb(0.7, 0.8, 0.9)),
                    )
                    .padding([3, 8])
                    .style(|_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.15, 0.18, 0.25,
                        ))),
                        border: iced::Border {
                            radius: 10.0.into(),
                            width: 0.0,
                            color: iced::Color::TRANSPARENT,
                        },
                        ..container::Style::default()
                    }),
                ]
                .align_y(Alignment::Center),
                text(desc)
                    .size(12)
                    .color(iced::Color::from_rgb(0.55, 0.55, 0.6)),
            ]
            .spacing(6),
        )
        .padding([12, 16])
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.1, 0.13,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.2, 0.18, 0.28),
            },
            ..container::Style::default()
        });

        list = list.push(topic_card);
    }

    column![header, list,].spacing(12).into()
}

fn chat_search_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let search_input = iced::widget::text_input("Search conversations, messages, chunks...", &cp.search_query)
        .on_input(|s| Message::ChatPipelineAction(ChatPipelineMsg::SearchQueryChanged(s)))
        .on_submit(Message::ChatPipelineAction(ChatPipelineMsg::DoSearch))
        .padding([8, 12])
        .size(14);

    let search_btn = button(text("Search").size(12))
        .on_press_maybe(if cp.loading || cp.search_query.is_empty() {
            None
        } else {
            Some(Message::ChatPipelineAction(ChatPipelineMsg::DoSearch))
        })
        .padding([8, 16]);

    let search_bar = row![search_input, search_btn,]
        .spacing(8)
        .align_y(Alignment::Center);

    let results: Element<'_, Message> = if cp.search_results.is_empty() {
        if cp.search_query.is_empty() {
            container(
                text("Enter a query to search across all conversations")
                    .size(13)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .center_x(Length::Fill)
            .padding(40)
            .into()
        } else {
            container(
                text("No results found")
                    .size(13)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .center_x(Length::Fill)
            .padding(40)
            .into()
        }
    } else {
        let mut list = column![
            text(format!("{} results", cp.search_results.len()))
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4);

        for result in &cp.search_results {
            let conv_id = result.conversation_id.clone();
            let result_row = button(
                container(
                    column![
                        row![
                            text(format!("Score: {:.2}", result.score))
                                .size(10)
                                .color(iced::Color::from_rgb(0.4, 0.7, 0.4)),
                            Space::new().width(8),
                            text(result.source.as_deref().unwrap_or(""))
                                .size(10)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.6)),
                        ],
                        text(truncate(&result.text, 200))
                            .size(12)
                            .color(iced::Color::from_rgb(0.75, 0.75, 0.75)),
                    ]
                    .spacing(4),
                )
                .padding([8, 12])
                .width(Length::Fill),
            )
            .on_press(Message::ChatPipelineAction(
                ChatPipelineMsg::SelectConversation(conv_id),
            ))
            .width(Length::Fill)
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.1, 0.1, 0.12,
                ))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.2),
                },
                shadow: iced::Shadow::default(),
                snap: false,
            });

            list = list.push(result_row);
        }

        list.into()
    };

    column![
        text("Search").size(16),
        search_bar,
        results,
    ]
    .spacing(12)
    .into()
}

// ===========================================================================
// Workspace File Discovery
// ===========================================================================

/// Scan for .code-workspace files in common locations
async fn scan_workspace_files() -> Vec<CodeWorkspaceFile> {
    use std::path::PathBuf;
    use std::fs;
    
    let mut files = Vec::new();
    let home = std::env::var("HOME").unwrap_or_else(|_| "/home/e421".to_string());
    
    // Directories to scan for .code-workspace files
    let scan_dirs = vec![
        PathBuf::from(&home),
        PathBuf::from(format!("{}/homelab", home)),
        PathBuf::from(format!("{}/continuum-studio", home)),
        PathBuf::from(format!("{}/synapsix", home)),
        PathBuf::from(format!("{}/phosphor", home)),
        PathBuf::from(format!("{}/projects", home)),
        PathBuf::from(format!("{}/code", home)),
    ];
    
    for dir in scan_dirs {
        if !dir.exists() {
            continue;
        }
        
        // Look for .code-workspace files (non-recursive for now, just top level)
        if let Ok(entries) = fs::read_dir(&dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if path.extension().map(|e| e == "code-workspace").unwrap_or(false) {
                    if let Some(ws) = parse_workspace_file(&path) {
                        files.push(ws);
                    }
                }
            }
        }
    }
    
    // Sort by modified time (newest first)
    files.sort_by(|a, b| b.modified.cmp(&a.modified));
    
    files
}

/// Parse a .code-workspace file and extract relevant info
fn parse_workspace_file(path: &std::path::Path) -> Option<CodeWorkspaceFile> {
    use std::fs;
    
    let content = fs::read_to_string(path).ok()?;
    let name = path.file_stem()?.to_string_lossy().to_string();
    let full_path = path.to_string_lossy().to_string();
    
    // Parse JSON to extract folders
    let folders: Vec<String> = if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content) {
        json.get("folders")
            .and_then(|f| f.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|f| f.get("path").and_then(|p| p.as_str()))
                    .map(|s| s.to_string())
                    .collect()
            })
            .unwrap_or_default()
    } else {
        Vec::new()
    };
    
    // Get modification time
    let modified = fs::metadata(path)
        .ok()
        .and_then(|m| m.modified().ok())
        .map(|t| {
            let datetime: chrono::DateTime<chrono::Local> = t.into();
            datetime.format("%Y-%m-%d %H:%M").to_string()
        });
    
    Some(CodeWorkspaceFile {
        path: full_path,
        name,
        folders,
        modified,
    })
}

// ===========================================================================
// Sub-agent Monitoring Integration
// ===========================================================================

/// Async function to fetch sub-agent stats from D-Bus
async fn fetch_subagent_stats() -> Result<MonitorStats, String> {
    use std::process::Command;
    
    let output = Command::new("busctl")
        .args([
            "--user", "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetStats",
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;
    
    if !output.status.success() {
        return Err(format!("D-Bus call failed: {}", String::from_utf8_lossy(&output.stderr)));
    }
    
    // Parse the response (busctl returns "s \"json_string\"")
    let stdout = String::from_utf8_lossy(&output.stdout);
    let json_start = stdout.find('"').ok_or("Invalid response format")?;
    let json_end = stdout.rfind('"').ok_or("Invalid response format")?;
    if json_start >= json_end {
        return Err("Empty JSON response".to_string());
    }
    
    let json_str = &stdout[json_start + 1..json_end];
    // Unescape the JSON string (busctl escapes quotes)
    let unescaped = json_str.replace("\\\"", "\"").replace("\\\\", "\\");
    
    serde_json::from_str(&unescaped).map_err(|e| format!("JSON parse error: {}", e))
}

/// Async function to fetch recent commands from D-Bus
async fn fetch_subagent_commands(limit: u32) -> Result<Vec<CommandRecord>, String> {
    use std::process::Command;
    
    let output = Command::new("busctl")
        .args([
            "--user", "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetSubagentCommands",
            "u", &limit.to_string(),
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;
    
    if !output.status.success() {
        return Err(format!("D-Bus call failed: {}", String::from_utf8_lossy(&output.stderr)));
    }
    
    let stdout = String::from_utf8_lossy(&output.stdout);
    let json_start = stdout.find('"').ok_or("Invalid response format")?;
    let json_end = stdout.rfind('"').ok_or("Invalid response format")?;
    if json_start >= json_end {
        return Ok(Vec::new()); // Empty array
    }
    
    let json_str = &stdout[json_start + 1..json_end];
    let unescaped = json_str.replace("\\\"", "\"").replace("\\\\", "\\");
    
    serde_json::from_str(&unescaped).map_err(|e| format!("JSON parse error: {}", e))
}

/// Async function to fetch running commands from D-Bus  
async fn fetch_running_commands() -> Result<Vec<CommandRecord>, String> {
    use std::process::Command;
    
    let output = Command::new("busctl")
        .args([
            "--user", "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetRunningCommands",
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;
    
    if !output.status.success() {
        return Err(format!("D-Bus call failed: {}", String::from_utf8_lossy(&output.stderr)));
    }
    
    let stdout = String::from_utf8_lossy(&output.stdout);
    let json_start = stdout.find('"').ok_or("Invalid response format")?;
    let json_end = stdout.rfind('"').ok_or("Invalid response format")?;
    if json_start >= json_end {
        return Ok(Vec::new());
    }
    
    let json_str = &stdout[json_start + 1..json_end];
    let unescaped = json_str.replace("\\\"", "\"").replace("\\\\", "\\");
    
    serde_json::from_str(&unescaped).map_err(|e| format!("JSON parse error: {}", e))
}

/// Handle sub-agent panel messages
fn handle_subagent_message(state: &mut ContinuumStudio, msg: SubagentMessage) -> Task<Message> {
    match msg {
        SubagentMessage::Refresh => {
            state.subagent_state.loading = true;
            // Batch multiple D-Bus queries
            Task::batch([
                Task::perform(
                    fetch_subagent_stats(),
                    |result| Message::SubagentAction(SubagentMessage::StatsLoaded(result))
                ),
                Task::perform(
                    fetch_subagent_commands(20),
                    |result| Message::SubagentAction(SubagentMessage::SubagentsLoaded(result))
                ),
                Task::perform(
                    fetch_running_commands(),
                    |result| Message::SubagentAction(SubagentMessage::RunningLoaded(result))
                ),
            ])
        }
        SubagentMessage::StatsLoaded(result) => {
            state.subagent_state.loading = false;
            match result {
                Ok(stats) => {
                    state.subagent_state.update_stats(stats);
                }
                Err(e) => {
                    state.subagent_state.set_error(e);
                }
            }
            Task::none()
        }
        SubagentMessage::RecentLoaded(result) => {
            if let Ok(commands) = result {
                state.subagent_state.update_recent(commands);
            }
            Task::none()
        }
        SubagentMessage::SubagentsLoaded(result) => {
            if let Ok(commands) = result {
                state.subagent_state.update_subagents(commands);
            }
            Task::none()
        }
        SubagentMessage::RunningLoaded(result) => {
            if let Ok(commands) = result {
                state.subagent_state.update_running(commands);
            }
            Task::none()
        }
        SubagentMessage::ServiceCheck(available) => {
            state.subagent_state.service_available = available;
            if !available {
                state.subagent_state.set_error("Terminal monitor service not available".to_string());
            }
            Task::none()
        }
    }
}

/// View for sub-agent monitoring panel
fn view_subagents(state: &ContinuumStudio) -> Element<Message> {
    let subagent = &state.subagent_state;
    
    // Header
    let header = row![
        text("🤖 Sub-agent Monitor").size(20),
        Space::new().width(Length::Fill),
        button(text("⟳ Refresh").size(12))
            .padding([6, 12])
            .on_press(Message::SubagentAction(SubagentMessage::Refresh))
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.4, 0.6))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }),
    ]
    .align_y(Alignment::Center)
    .spacing(12);

    // Service status
    let (health_r, health_g, health_b) = subagent.health_color();
    let health_color = iced::Color::from_rgb(health_r, health_g, health_b);
    
    let status_indicator = container(
        row![
            text(if subagent.service_available { "●" } else { "○" })
                .color(health_color),
            text(if subagent.service_available { "Connected to Terminal Monitor" } else { "Terminal Monitor Unavailable" })
                .size(12)
                .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        ]
        .spacing(8)
    )
    .padding([8, 12])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.14))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.22),
        },
        ..container::Style::default()
    });

    // Stats cards
    let stats = &subagent.stats;
    let stats_row = row![
        stat_card("Total Commands", &stats.total_commands.to_string(), "📊"),
        stat_card("Active", &stats.active_commands.to_string(), "⏳"),
        stat_card("Failed", &stats.failed_commands.to_string(), "❌"),
        stat_card("Sub-agents", &stats.subagent_commands.to_string(), "🤖"),
        stat_card("Error Rate", &format!("{:.1}%", stats.error_rate * 100.0), "⚠️"),
    ]
    .spacing(12);

    // Running commands section
    let running_section = if subagent.running_commands.is_empty() {
        container(
            text("No commands currently running")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .padding(16)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.18, 0.2),
            },
            ..container::Style::default()
        })
    } else {
        let mut running_list = column![].spacing(4);
        for cmd in subagent.running_commands.iter().take(5) {
            running_list = running_list.push(subagent_command_row(cmd));
        }
        container(running_list)
            .padding(12)
            .width(Length::Fill)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.2),
                },
                ..container::Style::default()
            })
    };

    // Recent sub-agent commands
    let subagent_section = if subagent.subagent_commands.is_empty() {
        container(
            text("No sub-agent activity recorded yet")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        )
        .padding(16)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.18, 0.2),
            },
            ..container::Style::default()
        })
    } else {
        let mut cmd_list = column![].spacing(4);
        for cmd in subagent.subagent_commands.iter().take(10) {
            cmd_list = cmd_list.push(subagent_command_row_with_codename(cmd));
        }
        container(cmd_list)
            .padding(12)
            .width(Length::Fill)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.2),
                },
                ..container::Style::default()
            })
    };

    // Error message if any
    let error_banner: Element<Message> = if let Some(ref err) = subagent.error_message {
        container(
            row![
                text("⚠️").size(14),
                text(err)
                    .size(12)
                    .color(iced::Color::from_rgb(0.9, 0.7, 0.4)),
            ]
            .spacing(8)
        )
        .padding([8, 12])
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.25, 0.18, 0.1))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.4, 0.3, 0.2),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Main content
    scrollable(
        column![
            header,
            Space::new().height(16),
            status_indicator,
            error_banner,
            Space::new().height(20),
            text("📊 Statistics").size(14),
            stats_row,
            Space::new().height(20),
            text("⏳ Running Commands").size(14),
            running_section,
            Space::new().height(20),
            text("🤖 Recent Sub-agent Activity").size(14),
            subagent_section,
        ]
        .spacing(8)
        .padding(4)
    )
    .into()
}

/// Create a stats card widget
fn stat_card(label: &str, value: &str, icon: &str) -> Element<'static, Message> {
    let label_owned = label.to_string();
    let value_owned = value.to_string();
    let icon_owned = icon.to_string();
    
    container(
        column![
            text(icon_owned).size(16),
            text(value_owned).size(18),
            text(label_owned)
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4)
        .align_x(Alignment::Center)
    )
    .padding([12, 16])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.2),
        },
        ..container::Style::default()
    })
    .into()
}

/// Create a command row widget for sub-agent view
/// show_codename: if true, always shows the codename badge (used for sub-agent sections)
fn subagent_command_row_impl(cmd: &CommandRecord, show_codename: bool) -> Element<'static, Message> {
    use continuum_studio_iced::subagents::{format_command, format_elapsed, format_exit_code, generate_codename};
    
    let truncated_cmd = format_command(&cmd.command, 50);
    let elapsed = format_elapsed(cmd.elapsed_ms);
    let (exit_text, exit_color) = format_exit_code(cmd.exit_code);
    
    // Show codename if explicitly requested or if command is marked as subagent
    let display_codename = show_codename || cmd.is_subagent;
    
    // Generate code name from terminal_id
    let codename = generate_codename(&cmd.terminal_id);
    
    let agent_badge: Element<Message> = if display_codename {
        let name = codename.name.clone();
        let rgb = codename.rgb;
        container(
            row![
                text("🤖").size(10),
                text(name)
                    .size(9)
                    .color(iced::Color::from_rgb(rgb.0, rgb.1, rgb.2)),
            ]
            .spacing(4)
        )
        .padding([2, 6])
        .style(move |_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.18, 0.25))),
            border: iced::Border {
                radius: 4.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(rgb.0 * 0.5, rgb.1 * 0.5, rgb.2 * 0.5),
            },
            ..container::Style::default()
        })
        .into()
    } else {
        Space::new().width(0).into()
    };

    container(
        row![
            agent_badge,
            text(truncated_cmd)
                .size(11)
                .color(iced::Color::from_rgb(0.8, 0.8, 0.8)),
            Space::new().width(Length::Fill),
            text(elapsed)
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            text(exit_text)
                .size(10)
                .color(iced::Color::from_rgb(exit_color.0, exit_color.1, exit_color.2)),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
    )
    .padding([6, 10])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.08, 0.08, 0.1))),
        border: iced::Border {
            radius: 4.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.15, 0.15, 0.17),
        },
        ..container::Style::default()
    })
    .into()
}

/// Create a command row widget for sub-agent view (default: show codename based on is_subagent)
fn subagent_command_row(cmd: &CommandRecord) -> Element<'static, Message> {
    subagent_command_row_impl(cmd, false)
}

/// Create a command row widget that always shows the codename (for sub-agent sections)
fn subagent_command_row_with_codename(cmd: &CommandRecord) -> Element<'static, Message> {
    subagent_command_row_impl(cmd, true)
}
