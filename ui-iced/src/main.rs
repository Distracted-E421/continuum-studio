//! Continuum Studio UI - iced/COSMIC Edition
//!
//! This is the iced-based UI for Continuum Studio, designed for integration
//! with the COSMIC desktop ecosystem.

use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length, Task, Theme};
use std::path::PathBuf;
use tokio::sync::mpsc;

use continuum_studio_iced::core::{
    spawn_core_connection, ConnectionState, CoreRequest, CoreResponse, CursorVersion, VersionStatus,
    Workspace, DEFAULT_SOCKET_PATH,
};
use continuum_studio_iced::log_capture::{init_logger, LogBuffer, LogEntry};
use continuum_studio_iced::services::{ServiceConfig, ServiceInfo, ServiceManager, ServiceStatus};
use continuum_studio_iced::sessions::{CursorSession, SessionTracker};
use continuum_studio_iced::settings::{CosmicPreset, Settings, ThemePreference};
use continuum_studio_iced::theme::{AppColors, CosmicThemePreset};
use continuum_studio_iced::updater::{UpdateChannel, UpdateChecker, UpdateInfo};

/// Async task to check for updates
async fn check_for_updates_task() -> Result<Option<UpdateInfo>, String> {
    let settings = Settings::load();
    let checker = UpdateChecker::new();
    checker.check_for_updates(&settings.updates).await
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
        .subscription(|_state| {
            // Always maintain the Core connection subscription
            // The worker handles connection state internally
            core_subscription()
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
                versions,
                workspaces: vec![],
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
    /// Available Cursor versions
    versions: Vec<CursorVersion>,
    /// Tracked workspaces
    workspaces: Vec<Workspace>,
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
}

/// Available views in the application
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum View {
    Dashboard,
    CursorVersions,
    Workspaces,
    Sessions,
    Services,
    Settings,
    Logs,
}

/// Application messages (Elm architecture)
#[derive(Debug, Clone)]
enum Message {
    /// Navigation
    NavigateTo(View),
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
    /// Versions updated from Core
    VersionsUpdated(Vec<CursorVersion>),
    /// Core response received
    CoreResponse(CoreResponse),
    /// Settings action
    SettingsAction(SettingsMessage),
    /// Update check result
    UpdateCheckResult(Result<Option<UpdateInfo>, String>),
}

/// Settings-related messages
#[derive(Debug, Clone)]
enum SettingsMessage {
    SaveSettings,
    ToggleAutoConnect,
    ToggleAutoStartServices,
    ToggleNotifications,
    SetUpdateChannel(UpdateChannel),
    ToggleAutoCheckUpdates,
    CheckForUpdates,
}

/// Cursor-related messages
#[derive(Debug, Clone)]
enum CursorMessage {
    RefreshVersions,
    LaunchVersion(String),
    InstallVersion(String),
    UninstallVersion(String),
}

/// Workspace-related messages
#[derive(Debug, Clone)]
enum WorkspaceMessage {
    RefreshWorkspaces,
    TogglePinned(String),
    RefreshGitStats(String),
    OpenInCursor(String, String), // workspace_id, version
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
}

fn update(state: &mut ContinuumStudio, message: Message) -> Task<Message> {
    match message {
        Message::NavigateTo(view) => {
            state.current_view = view;
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

            // Clear tx when disconnected to allow reconnection
            if matches!(new_state, ConnectionState::Disconnected) {
                state.core_tx = None;
            }

            // Request versions when newly connected
            if is_connected && !was_connected {
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx.send(CoreRequest::GetVersions).await;
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
            SettingsMessage::ToggleAutoCheckUpdates => {
                state.settings.updates.auto_check = !state.settings.updates.auto_check;
                state.settings_dirty = true;
            }
            SettingsMessage::CheckForUpdates => {
                state.checking_updates = true;
                return Task::perform(check_for_updates_task(), Message::UpdateCheckResult);
            }
        },
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
                state.cursor_sessions = sessions;
                log::info!("Found {} Cursor sessions", state.cursor_sessions.len());
            }
            SessionMessage::SessionsFound(sessions) => {
                state.cursor_sessions = sessions;
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
            }
        }
    }
    Task::none()
}

fn view(state: &ContinuumStudio) -> Element<Message> {
    let sidebar = sidebar(state);
    let content = match state.current_view {
        View::Dashboard => view_dashboard(state),
        View::CursorVersions => view_cursor_versions(state),
        View::Workspaces => view_workspaces(state),
        View::Sessions => view_sessions(state),
        View::Services => view_services(state),
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
        nav_button("📦  Versions", View::CursorVersions, current),
        nav_button("📁  Workspaces", View::Workspaces, current),
        nav_button("💬  Sessions", View::Sessions, current),
        nav_button("🔧  Services", View::Services, current),
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
        for v in era_versions {
            version_elements.push(version_row_from_data(v));
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
fn version_row_from_data(version: &CursorVersion) -> Element<Message> {
    let v = version.version.clone();
    let v2 = version.version.clone();

    let (status_text, status_color) = match version.status {
        VersionStatus::Running => ("Running", iced::Color::from_rgb(0.25, 0.75, 0.35)),
        VersionStatus::Installed => ("Installed", iced::Color::from_rgb(0.4, 0.6, 1.0)),
        VersionStatus::Available => ("Available", iced::Color::from_rgb(0.5, 0.5, 0.5)),
        VersionStatus::Downloading => ("Downloading...", iced::Color::from_rgb(0.75, 0.65, 0.25)),
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
            text(&version.version).size(13).width(120),
            text(status_text).size(12).color(status_color).width(100),
            text(version.date.as_deref().unwrap_or("-"))
                .size(12)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                .width(100),
            text(version.era.as_deref().unwrap_or("-"))
                .size(12)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                .width(80),
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

    column![
        header_card,
        Space::new().height(16),
        table_header,
        Space::new().height(8),
        scrollable(workspace_list).height(400),
    ]
    .spacing(0)
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

/// Sessions view with detected running Cursor instances
fn view_sessions(state: &ContinuumStudio) -> Element<Message> {
    // Header card with refresh button
    let header_card = container(
        row![
            column![
                text("Sessions").size(20),
                text(format!("{} running Cursor instances", state.cursor_sessions.len()))
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
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

    let content: Element<Message> = if state.cursor_sessions.is_empty() {
        // Empty state
        container(
            column![
                text("💬").size(48),
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
        // Session cards
        let session_cards: Vec<Element<Message>> = state
            .cursor_sessions
            .iter()
            .map(|session| {
                let version_text = session.version.as_deref().unwrap_or("Unknown version");
                let workspace_text = session.workspace.as_deref().unwrap_or("No workspace");
                let data_dir_text = session.data_dir.as_deref().unwrap_or("Default data dir");

                container(
                    column![
                        // Header row with PID and version
                        row![
                            text(format!("PID {}", session.pid))
                                .size(14)
                                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                            Space::new().width(12),
                            text(format!("Cursor {}", version_text))
                                .size(16),
                            Space::new().width(Length::Fill),
                            container(
                                text("Running")
                                    .size(11)
                                    .color(iced::Color::from_rgb(0.3, 0.8, 0.3))
                            )
                            .padding([4, 8])
                            .style(|_theme| container::Style {
                                background: Some(iced::Background::Color(
                                    iced::Color::from_rgba(0.3, 0.8, 0.3, 0.15)
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
                        // Workspace row
                        row![
                            text("Workspace:")
                                .size(11)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(8),
                            text(workspace_text)
                                .size(11)
                                .font(iced::Font::MONOSPACE),
                        ],
                        Space::new().height(4),
                        // Data dir row
                        row![
                            text("Data Dir:")
                                .size(11)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(8),
                            text(data_dir_text)
                                .size(11)
                                .font(iced::Font::MONOSPACE)
                                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                        ],
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

        column(session_cards).spacing(12).into()
    };

    scrollable(
        column![
            header_card,
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
            text("Quick Commands").size(14),
            Space::new().height(12),
            // Core interactive
            command_row(
                "Start Core (Interactive)",
                "cd /home/e421/continuum-studio/core/studio_core && iex -S mix",
            ),
            Space::new().height(8),
            // Dialog daemon
            command_row(
                "Start Dialog Daemon", 
                "synapsix-dialog-daemon --web-port 8080 &",
            ),
            Space::new().height(8),
            // Check dialog
            command_row(
                "Check Dialog Status",
                "synapsix-dialog-cli ping",
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
    container(
        row![
            column![
                text(label)
                    .size(11)
                    .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().height(2),
                text(command)
                    .size(10)
                    .font(iced::Font::MONOSPACE)
                    .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
            ],
            Space::new().width(Length::Fill),
            small_button("Copy", false)
                .on_press(Message::ServiceAction(ServiceMessage::CopyCommand(
                    command.to_string(),
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
