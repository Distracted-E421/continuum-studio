//! Continuum Studio UI - iced/COSMIC Edition
//!
//! This is the iced-based UI for Continuum Studio, designed for integration
//! with the COSMIC desktop ecosystem.

use iced::widget::{button, column, container, row, scrollable, slider, text, Space};
use iced::window;
use iced::{Alignment, Element, Length, Subscription, Task, Theme};
use std::collections::BTreeMap;
use std::path::PathBuf;
use tokio::sync::mpsc;

use continuum_studio_iced::profile_span;

use continuum_studio_iced::chat_pipeline::{
    fmt_bytes,
    fmt_num,
    truncate,
    ChatApiClient,
    ChatPipelineState,
    ChatStats,
    ChatSubView,
    Conversation,
    ConversationDetail,
    ExportFormat,
    ExportMode,
    ExportResult,
    ScanLocations,
    SearchResult,
    Topic,
    TrainingExportRequest,
    TrainingExportResponse,
    // Training export types
    TrainingFormat,
    TrainingSampleResponse,
    TrainingStatsResponse,
    WatcherStatus,
};
use continuum_studio_iced::cli_agents::{
    view_cli_agents_tab, CLIAgentMessage, CLIAgentTask, CLIAgentsState,
};
use continuum_studio_iced::cli_agents_client::CLIAgentsHttpClient;
use continuum_studio_iced::orchestrator_panel::{
    view_orchestrator_panel, OrchestratorMessage, OrchestratorPanelState,
};
use continuum_studio_iced::activity_feed::{
    view_activity_feed, ActivityFeedState, ActivityMessage,
};
use continuum_studio_iced::parked_agents::{
    view_parked_agents_panel, ParkedAgentTask, ParkedMessage,
};
use continuum_studio_iced::parked_agents_client::ParkedAgentsHttpClient;
use continuum_studio_iced::dialog_client::OrchestratorMode;
use continuum_studio_iced::coordinator_client::{
    Agent as CoordAgent, AgentStatus as CoordAgentStatus, Conflict as CoordConflict,
    CoordinatorHttpClient,
};
use continuum_studio_iced::core::{
    spawn_core_connection, AuthProfile, AuthState, AuthStatus, ConnectionState, CoreRequest,
    CoreResponse, CursorVersion, VersionStatus, Workspace, DEFAULT_SOCKET_PATH,
};
use continuum_studio_iced::feed_client::{
    spawn_feed_websocket, FeedEntry, FeedEvent, FeedHttpClient, FeedSource, FeedStats,
};
use continuum_studio_iced::activity_stream_client::spawn_activity_stream;
use continuum_studio_iced::log_capture::{init_logger, LogBuffer};
use continuum_studio_iced::monitoring::{DashboardData, SessionMetrics, SessionMonitor};
use continuum_studio_iced::services::{ServiceConfig, ServiceInfo, ServiceManager, ServiceStatus};
use continuum_studio_iced::sessions::{CursorSession, SessionTracker};
use continuum_studio_iced::settings::{CosmicPreset, Settings, ThemePreference};
use continuum_studio_iced::subagents::{
    CommandRecord, MonitorStats, SubagentMessage, SubagentPanelState,
};
use continuum_studio_iced::theme::{AppColors, CosmicThemePreset};
use continuum_studio_iced::updater::{
    ForgeType, InstallationType, UpdateChannel, UpdateChecker, UpdateInfo,
};
use std::collections::HashMap;

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
    let versions_dir = home.join(".cursor-versions/downloads");

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
    let state_db = data_dir
        .join("User")
        .join("globalStorage")
        .join("state.vscdb");

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

    // Use daemon for multi-window support
    iced::daemon(
        move || ContinuumStudio::new_multi_window(log_buffer.clone()),
        update,
        view_for_window,
    )
    .title(window_title)
    .theme(|state: &ContinuumStudio, _window_id: window::Id| state.theme.clone())
    .subscription(subscription)
    .antialiasing(true)
    .run()
}

/// Get title for a specific window
fn window_title(state: &ContinuumStudio, window_id: window::Id) -> String {
    state
        .windows
        .get(&window_id)
        .map(|ws| match ws.window_type {
            WindowType::Main => "Continuum Studio".to_string(),
            WindowType::TaskQueue => "Task Queue".to_string(),
            WindowType::DialogPanel => "Dialog Panel".to_string(),
            WindowType::TiledPanel => "Task Queue + Dialog".to_string(),
        })
        .unwrap_or_else(|| "Continuum Studio".to_string())
}

/// Combined subscription for all windows
fn subscription(state: &ContinuumStudio) -> Subscription<Message> {
    // Lazy WebSocket optimization: only connect when tab is visible
    let on_activity_tab =
        state.current_view == View::Cursor && state.cursor_tab == CursorTab::AgentActivity;
    
    Subscription::batch([
        // Core IPC connection
        core_subscription(),
        // Sub-agent monitor polling (only when on Cursor view + SubAgents tab)
        subagent_subscription(
            state.current_view == View::Cursor && state.cursor_tab == CursorTab::SubAgents,
        ),
        // Task queue WebSocket connection (always active for multi-window support)
        task_queue_subscription(true),
        // Dialog daemon monitor (always active for dialog panel)
        dialog_daemon_subscription(),
        // Activity feed WebSocket connection (lazy: only when on AgentActivity tab)
        activity_feed_subscription(on_activity_tab),
        // Agent activity stream from dialog daemon (lazy: only when on AgentActivity tab)
        activity_stream_subscription(on_activity_tab),
        // Agent coordinator polling (lazy: only when on Dashboard view)
        coordinator_poll_subscription(state.current_view == View::Dashboard),
        // CLI agents WebSocket (only when on Cursor view + CLIAgents tab)
        cli_agents_subscription(
            state.current_view == View::Cursor && state.cursor_tab == CursorTab::CLIAgents,
        ),
        // Orchestrator WebSocket (active on Orchestrator tab for real-time triage updates)
        orchestrator_ws_subscription(
            state.current_view == View::Cursor && state.cursor_tab == CursorTab::Orchestrator,
        ),
        // Keyboard shortcuts subscription (always active)
        keyboard_shortcut_subscription(),
        // Agent dialog polling (active on CLI Agents OR Orchestrator tabs)
        dialog_polling_subscription(
            state.current_view == View::Cursor
                && (state.cursor_tab == CursorTab::CLIAgents
                    || state.cursor_tab == CursorTab::Orchestrator),
        ),
        // Triage timeout processing (active on Orchestrator tab when queue non-empty)
        triage_timeout_subscription(
            state.current_view == View::Cursor && state.cursor_tab == CursorTab::Orchestrator,
            !state.orchestrator_state.engine.triage_queue().is_empty(),
        ),
        // Window close events
        window::close_events().map(Message::WindowClosed),
        // Memory stats logging (every 60 seconds)
        memory_stats_subscription(),
        // Toast auto-dismiss (only active when toast is shown)
        toast_timeout_subscription(state.toast.as_ref()),
    ])
}

/// Toast auto-dismiss subscription (3 second timeout)
fn toast_timeout_subscription(toast: Option<&Toast>) -> iced::Subscription<Message> {
    match toast {
        Some(t) => {
            let elapsed = t.shown_at.elapsed();
            let timeout = std::time::Duration::from_secs(3);
            if elapsed >= timeout {
                // Already expired, dismiss immediately via a one-shot
                iced::Subscription::run(|| {
                    futures::stream::once(async { Message::DismissToast })
                })
            } else {
                // Schedule dismissal after remaining time
                let remaining = timeout - elapsed;
                iced::time::every(remaining).map(|_| Message::DismissToast)
            }
        }
        None => iced::Subscription::none(),
    }
}

/// Memory stats logging subscription (every 60 seconds)
fn memory_stats_subscription() -> iced::Subscription<Message> {
    iced::time::every(std::time::Duration::from_secs(60))
        .map(|_| Message::UpdateMemoryStats)
}

/// Subscription to monitor dialog daemon status
fn dialog_daemon_subscription() -> iced::Subscription<Message> {
    iced::Subscription::run(dialog_daemon_worker)
}

/// Dialog daemon monitor worker
fn dialog_daemon_worker() -> impl iced::futures::Stream<Item = Message> {
    use continuum_studio_iced::dialog_client::{spawn_dialog_monitor, DialogClientMessage};

    iced::stream::channel(
        32,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let mut monitor_rx = spawn_dialog_monitor();

            while let Some(event) = monitor_rx.recv().await {
                let msg = match event {
                    DialogClientMessage::Connected(connected) => {
                        DialogMsg::DaemonConnected(connected)
                    }
                    DialogClientMessage::HoldModeChanged(enabled) => {
                        DialogMsg::HoldModeChanged(enabled)
                    }
                    DialogClientMessage::Error(e) => DialogMsg::Error(e),
                    _ => continue, // Ignore other messages for now
                };
                let _ = output.send(Message::DialogAction(msg)).await;
            }
        },
    )
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
    iced::time::every(std::time::Duration::from_secs(2))
        .map(|_| Message::SubagentAction(SubagentMessage::Refresh))
}

/// Subscription for task queue WebSocket real-time updates
fn task_queue_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }

    iced::Subscription::run(task_queue_websocket_worker)
}

/// Task queue WebSocket worker that yields Messages
fn task_queue_websocket_worker() -> impl iced::futures::Stream<Item = Message> {
    use continuum_studio_iced::task_queue_client::{spawn_websocket_connection, TaskQueueEvent};

    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            // Try to connect to WebSocket
            match spawn_websocket_connection(Some("continuum-studio-ui".to_string())).await {
                Ok(mut event_rx) => {
                    // Process events from WebSocket
                    while let Some(event) = event_rx.recv().await {
                        let msg = match event {
                            TaskQueueEvent::Connected => TaskQueueMsg::Connected,
                            TaskQueueEvent::Disconnected => TaskQueueMsg::Disconnected,
                            TaskQueueEvent::InitialState {
                                tasks,
                                stats,
                                current_task,
                            } => TaskQueueMsg::InitialState {
                                tasks,
                                stats,
                                current_task,
                            },
                            TaskQueueEvent::TaskAdded(task) => TaskQueueMsg::TaskAdded(task),
                            TaskQueueEvent::TaskUpdated(task) => TaskQueueMsg::TaskUpdated(task),
                            TaskQueueEvent::TaskStarted(task) => TaskQueueMsg::TaskStarted(task),
                            TaskQueueEvent::TaskCompleted(task) => {
                                TaskQueueMsg::TaskCompleted(task)
                            }
                            TaskQueueEvent::TaskCancelled(task) => {
                                TaskQueueMsg::TaskCompleted(task) // Treat same as completed
                            }
                            TaskQueueEvent::TaskClaimed { task, .. } => {
                                TaskQueueMsg::TaskUpdated(task)
                            }
                            TaskQueueEvent::TaskReleased(task) => TaskQueueMsg::TaskUpdated(task),
                            TaskQueueEvent::TaskRemoved(id) => TaskQueueMsg::TaskRemoved(id),
                            TaskQueueEvent::Error(e) => TaskQueueMsg::Error(e),
                        };

                        let _ = output.send(Message::TaskQueueAction(msg)).await;
                    }
                }
                Err(e) => {
                    log::error!("Failed to connect to task queue WebSocket: {}", e);
                    let _ = output
                        .send(Message::TaskQueueAction(TaskQueueMsg::Error(e)))
                        .await;
                }
            }
        },
    )
}

/// Activity feed WebSocket subscription
/// Only active when on AgentActivity tab (lazy connection optimization)
fn activity_feed_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    iced::Subscription::run(activity_feed_worker)
}

/// Agent activity stream subscription (dialog daemon port 8080)
/// Only active when on AgentActivity tab (lazy connection optimization)
fn activity_stream_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    iced::Subscription::run(activity_stream_worker)
}

/// Activity stream worker - forwards ActivityEvent to agent_activity_state
fn activity_stream_worker() -> impl iced::futures::Stream<Item = Message> {
    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let mut event_rx = spawn_activity_stream().await;
            while let Some(ev) = event_rx.recv().await {
                let _ = output
                    .send(Message::AgentActivityFeed(ActivityMessage::NewEvent(ev)))
                    .await;
            }
        },
    )
}

/// Activity feed WebSocket worker
fn activity_feed_worker() -> impl iced::futures::Stream<Item = Message> {
    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            match spawn_feed_websocket().await {
                Ok(mut event_rx) => {
                    while let Some(event) = event_rx.recv().await {
                        let msg = match event {
                            FeedEvent::Connected => ActivityFeedMsg::Connected,
                            FeedEvent::Disconnected => ActivityFeedMsg::Disconnected,
                            FeedEvent::InitialState { entries, stats } => {
                                ActivityFeedMsg::InitialState { entries, stats }
                            }
                            FeedEvent::EntryAdded(entry) => ActivityFeedMsg::EntryAdded(entry),
                            FeedEvent::Error(e) => ActivityFeedMsg::Error(e),
                        };
                        let _ = output.send(Message::ActivityFeedAction(msg)).await;
                    }
                }
                Err(e) => {
                    log::error!("Failed to connect to activity feed WebSocket: {}", e);
                    let _ = output
                        .send(Message::ActivityFeedAction(ActivityFeedMsg::Error(e)))
                        .await;
                }
            }
        },
    )
}

/// Coordinator polling subscription (refreshes every 10 seconds)
/// Only active when on Dashboard view (lazy connection optimization)
fn coordinator_poll_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    iced::Subscription::run(coordinator_poll_worker)
}

/// Coordinator polling worker
fn coordinator_poll_worker() -> impl iced::futures::Stream<Item = Message> {
    iced::stream::channel(
        32,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let client = CoordinatorHttpClient::new();
            loop {
                // Fetch agents
                let agents_result = client.list_agents().await;
                let _ = output
                    .send(Message::CoordinatorAction(CoordinatorMsg::AgentsLoaded(
                        agents_result,
                    )))
                    .await;

                // Fetch conflicts
                let conflicts_result = client.get_conflicts().await;
                let _ = output
                    .send(Message::CoordinatorAction(CoordinatorMsg::ConflictsLoaded(
                        conflicts_result,
                    )))
                    .await;

                tokio::time::sleep(tokio::time::Duration::from_secs(10)).await;
            }
        },
    )
}

/// CLI agents WebSocket subscription (only when on CLI Agents tab)
fn cli_agents_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    iced::Subscription::run(cli_agents_websocket_worker)
}

/// CLI agents WebSocket worker
fn cli_agents_websocket_worker() -> impl iced::futures::Stream<Item = Message> {
    use continuum_studio_iced::cli_agents_client::{spawn_cli_agents_websocket, CLIAgentWsEvent};

    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let mut event_rx = spawn_cli_agents_websocket(None).await;

            while let Some(event) = event_rx.recv().await {
                let msg = match event {
                    CLIAgentWsEvent::Connected => {
                        log::info!("CLI agents WebSocket connected");
                        CLIAgentMessage::Connected
                    }
                    CLIAgentWsEvent::Started {
                        agent_id,
                        workspace,
                        model,
                    } => CLIAgentMessage::AgentStarted {
                        id: agent_id,
                        workspace,
                        model,
                    },
                    CLIAgentWsEvent::Event { agent_id, event } => {
                        if let Some(cli_event) = event.into_cli_agent_event() {
                            CLIAgentMessage::AgentEvent(agent_id, cli_event)
                        } else {
                            continue;
                        }
                    }
                    CLIAgentWsEvent::Completed {
                        agent_id,
                        result,
                        error,
                    } => {
                        if let Some(e) = error {
                            CLIAgentMessage::AgentCompleted {
                                id: agent_id,
                                result: None,
                                error: Some(e),
                            }
                        } else {
                            CLIAgentMessage::AgentCompleted {
                                id: agent_id,
                                result,
                                error: None,
                            }
                        }
                    }
                    CLIAgentWsEvent::Ping => continue,
                };
                let _ = output.send(Message::CLIAgentAction(msg)).await;
            }
        },
    )
}

/// Orchestrator WebSocket subscription for real-time dialog/triage updates
fn orchestrator_ws_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }
    iced::Subscription::run(orchestrator_ws_worker)
}

/// Orchestrator WebSocket worker
fn orchestrator_ws_worker() -> impl iced::futures::Stream<Item = Message> {
    use continuum_studio_iced::cli_agents_client::spawn_orchestrator_websocket;

    iced::stream::channel(
        100,
        |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
            use iced::futures::SinkExt;

            let mut rx = spawn_orchestrator_websocket(None);

            while let Some(event) = rx.recv().await {
                let _ = output.send(Message::OrchestratorWsEvent(event)).await;
            }
        },
    )
}

/// Keyboard shortcuts subscription for quick orchestrator actions
fn keyboard_shortcut_subscription() -> iced::Subscription<Message> {
    use iced::event::{self, Event};
    use iced::keyboard::{self, Key, Modifiers};

    event::listen_with(|event, _status, _id| match event {
        Event::Keyboard(keyboard::Event::KeyPressed {
            key,
            modifiers,
            ..
        }) => {
            let ctrl = modifiers.contains(Modifiers::CTRL);

            match (ctrl, &key) {
                // Ctrl+1: UserActive mode
                (true, Key::Character(c)) if c.as_str() == "1" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::SetModeUserActive))
                }
                // Ctrl+2: UserDelegate mode
                (true, Key::Character(c)) if c.as_str() == "2" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::SetModeUserDelegate))
                }
                // Ctrl+3: Spectator mode
                (true, Key::Character(c)) if c.as_str() == "3" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::SetModeSpectator))
                }
                // Ctrl+4: Autonomous mode
                (true, Key::Character(c)) if c.as_str() == "4" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::SetModeAutonomous))
                }
                // Ctrl+H: Toggle history
                (true, Key::Character(c)) if c.as_str() == "h" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::ToggleHistory))
                }
                // Ctrl+R: Refresh view
                (true, Key::Character(c)) if c.as_str() == "r" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::RefreshView))
                }
                // Ctrl+Z: Undo (context-dependent)
                (true, Key::Character(c)) if c.as_str() == "z" => {
                    Some(Message::KeyboardShortcut(KeyboardShortcut::UndoDecision))
                }
                _ => None,
            }
        }
        _ => None,
    })
}

/// Triage timeout subscription - processes expired triage items
/// Only polls when there are items in the triage queue (P1 optimization)
fn triage_timeout_subscription(active: bool, has_triage_items: bool) -> iced::Subscription<Message> {
    if !active || !has_triage_items {
        return iced::Subscription::none();
    }

    // Check every second for triage items that need auto-handling
    iced::time::every(std::time::Duration::from_secs(1))
        .map(|_| Message::ProcessTriageTimeouts)
}

/// Agent dialog polling subscription
/// Active when on CLI Agents tab or Orchestrator tab to keep dialogs fresh
fn dialog_polling_subscription(active: bool) -> iced::Subscription<Message> {
    if !active {
        return iced::Subscription::none();
    }

    // Poll every 5 seconds to fetch pending agent dialogs
    iced::time::every(std::time::Duration::from_secs(5))
        .map(|_| Message::CLIAgentAction(CLIAgentMessage::RefreshDialogs))
}

/// Derive iced Theme from Settings
fn derive_theme(settings: &Settings) -> Theme {
    match settings.theme {
        ThemePreference::Dark => Theme::Dark,
        ThemePreference::Light => Theme::Light,
        ThemePreference::System => {
            // Detect system theme via XDG Desktop Portal (Linux/BSD)
            match dark_light::detect() {
                Ok(dark_light::Mode::Dark) => Theme::Dark,
                Ok(dark_light::Mode::Light) => Theme::Light,
                Ok(dark_light::Mode::Unspecified) | Err(_) => {
                    log::debug!("System theme unspecified, defaulting to Dark");
                    Theme::Dark
                }
            }
        }
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
            tasks.push(Task::perform(
                check_for_updates_task(),
                Message::UpdateCheckResult,
            ));
        }

        // Auto-start Core if not running
        if should_auto_start {
            tasks.push(Task::perform(
                async {
                    log::info!("Auto-starting Elixir Core...");
                    continuum_studio_iced::services::start_core_auto().await
                },
                |result| {
                    Message::ServiceAction(ServiceMessage::ServiceStartResult(
                        "Elixir Core".to_string(),
                        result,
                    ))
                },
            ));
        }

        // Sync orchestrator mode from daemon on startup
        tasks.push(Task::perform(
            async {
                use continuum_studio_iced::dialog_client::DialogClient;
                let mut client = DialogClient::new();
                if client.connect().await.is_ok() {
                    client.get_orchestrator_mode().await.ok()
                } else {
                    None
                }
            },
            |result| {
                if let Some(mode) = result {
                    Message::OrchestratorAction(OrchestratorMessage::ModeRefreshed(mode))
                } else {
                    Message::OrchestratorAction(OrchestratorMessage::DaemonConnectionChanged(false))
                }
            },
        ));

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
                windows: BTreeMap::new(),
                main_window_id: None,
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
                cli_agents_state: CLIAgentsState::default(),
                cli_agents_http: CLIAgentsHttpClient::default(),
                storage_disk_usage: Vec::new(),
                storage_selected: std::collections::HashSet::new(),
                storage_loading: false,
                task_queue_tasks: Vec::new(),
                task_queue_stats: continuum_studio_iced::task_queue_client::QueueStats::default(),
                task_queue_current: None,
                task_queue_connected: false,
                task_queue_http: continuum_studio_iced::task_queue_client::TaskQueueHttpClient::new(
                ),
                task_queue_input: String::new(),
                task_queue_selected: None,
                task_queue_editing: None,
                task_queue_subtask_input: String::new(),
                new_task_content: String::new(),
                new_task_priority: continuum_studio_iced::task_queue_client::Priority::Medium,
                new_task_project: String::new(),
                new_task_notes: String::new(),
                new_task_subtasks: Vec::new(),
                new_task_subtask_input: String::new(),
                new_task_prereqs: Vec::new(),
                task_queue_panel: TaskQueuePanel::default(),
                task_queue_layout: TaskQueueLayout::default(),
                task_queue_secondary_panel: TaskQueuePanel::Agents,
                dialog_daemon_connected: false,
                dialog_hold_mode: false,
                // Activity Feed
                activity_feed_entries: Vec::new(),
                activity_feed_stats: FeedStats::default(),
                activity_feed_connected: false,
                activity_feed_http: FeedHttpClient::new(),
                activity_feed_expanded: None,
                activity_feed_source_filter: None,
                // Zone Manager (xx-zones positioning)
                zone_manager: continuum_studio_iced::zones::ZoneManager::new(2560, 1440),
                // Agent Coordinator
                coordinator_agents: Vec::new(),
                coordinator_conflicts: Vec::new(),
                coordinator_http: CoordinatorHttpClient::new(),
                coordinator_selected: None,
                // Offline Mode: load persisted queue, start with disconnected tracker
                offline_queue: continuum_studio_iced::offline::OfflineQueue::load(),
                connection_tracker: continuum_studio_iced::offline::ConnectionTracker::new(),
                // Orchestrator Panel: manage CLI agent dialogs
                orchestrator_state: OrchestratorPanelState::default(),
                parked_agents_state: continuum_studio_iced::parked_agents::ParkedAgentsPanelState::default(),
                parked_agents_http: ParkedAgentsHttpClient::new(),
                agent_activity_state: ActivityFeedState::new(),
                memory_stats: MemoryStats::default(),
                toast: None,
            },
            startup_task,
        )
    }

    /// Multi-window boot function - opens the first window
    fn new_multi_window(log_buffer: LogBuffer) -> (Self, Task<Message>) {
        let (mut state, startup_tasks) = Self::new(log_buffer);

        // Calculate zone-based layout
        let (main_config, panel_config) = state.zone_manager.layout_main_with_side_panel();

        // Open the initial main window with zone-calculated position
        let (main_window_id, open_task) = window::open(window::Settings {
            size: iced::Size::new(main_config.width as f32, main_config.height as f32),
            position: window::Position::Specific(iced::Point::new(
                main_config.x as f32,
                main_config.y as f32,
            )),
            resizable: true,
            decorations: true,
            ..Default::default()
        });

        // Register the main window
        state.main_window_id = Some(main_window_id);
        state.windows.insert(
            main_window_id,
            WindowState {
                window_type: WindowType::Main,
                vertical_split: true,
                split_ratio: 0.5,
            },
        );

        // Auto-open a task queue window with zone-calculated position
        let (tq_window_id, tq_open_task) = window::open(window::Settings {
            size: iced::Size::new(panel_config.width as f32, panel_config.height as f32),
            position: window::Position::Specific(iced::Point::new(
                panel_config.x as f32,
                panel_config.y as f32,
            )),
            resizable: true,
            decorations: true,
            ..Default::default()
        });
        state.windows.insert(
            tq_window_id,
            WindowState {
                window_type: WindowType::TaskQueue,
                vertical_split: true,
                split_ratio: 0.5,
            },
        );
        // Start on Feed panel to show activity timeline by default
        state.task_queue_panel = TaskQueuePanel::Feed;

        // Export zone snapshot so Phosphor knows where windows are
        state.zone_manager.export_snapshot();

        // Combine the open tasks with startup tasks
        let combined_task =
            Task::batch([startup_tasks, open_task.discard(), tq_open_task.discard()]);

        (state, combined_task)
    }
}

/// Toast notification for ephemeral feedback
#[derive(Debug, Clone)]
struct Toast {
    message: String,
    level: ToastLevel,
    shown_at: std::time::Instant,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[allow(dead_code)]
enum ToastLevel {
    Success,
    Info,
    Warning,
    Error,
}

impl ToastLevel {
    fn color(&self) -> iced::Color {
        match self {
            ToastLevel::Success => iced::Color::from_rgb(0.3, 0.7, 0.4),
            ToastLevel::Info => iced::Color::from_rgb(0.4, 0.5, 0.7),
            ToastLevel::Warning => iced::Color::from_rgb(0.8, 0.6, 0.2),
            ToastLevel::Error => iced::Color::from_rgb(0.8, 0.3, 0.3),
        }
    }

    fn icon(&self) -> &'static str {
        match self {
            ToastLevel::Success => "✓",
            ToastLevel::Info => "ℹ",
            ToastLevel::Warning => "⚠",
            ToastLevel::Error => "✕",
        }
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
    /// Multi-window tracking: maps window ID to window state
    windows: BTreeMap<window::Id, WindowState>,
    /// The main window ID (first window created)
    main_window_id: Option<window::Id>,
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
    /// CLI agents state (headless Cursor CLI orchestration)
    cli_agents_state: CLIAgentsState,
    /// CLI agents HTTP client
    cli_agents_http: CLIAgentsHttpClient,
    /// Detailed disk usage per version (for Storage view)
    storage_disk_usage: Vec<VersionDiskUsage>,
    /// Versions selected for batch cleanup
    storage_selected: std::collections::HashSet<String>,
    /// Whether storage data is loading
    storage_loading: bool,
    /// Task queue state for Synapsix integration
    task_queue_tasks: Vec<continuum_studio_iced::task_queue_client::Task>,
    /// Task queue stats
    task_queue_stats: continuum_studio_iced::task_queue_client::QueueStats,
    /// Current task being worked on
    task_queue_current: Option<continuum_studio_iced::task_queue_client::Task>,
    /// Task queue connection state
    task_queue_connected: bool,
    /// Task queue HTTP client
    task_queue_http: continuum_studio_iced::task_queue_client::TaskQueueHttpClient,
    /// Quick add input text for task queue
    task_queue_input: String,
    /// Currently expanded/selected task in the detail view
    task_queue_selected: Option<String>,
    /// Task currently in edit mode (shows priority dropdown, delete, etc.)
    task_queue_editing: Option<String>,
    /// Subtask input text for currently selected task
    task_queue_subtask_input: String,
    /// New task form: title/content
    new_task_content: String,
    /// New task form: selected priority
    new_task_priority: continuum_studio_iced::task_queue_client::Priority,
    /// New task form: project name
    new_task_project: String,
    /// New task form: notes
    new_task_notes: String,
    /// New task form: planned subtask texts (before creation)
    new_task_subtasks: Vec<String>,
    /// New task form: subtask input text
    new_task_subtask_input: String,
    /// New task form: selected prerequisite/blocker task IDs
    new_task_prereqs: Vec<String>,
    /// Active panel in task queue window (Tasks/Agents/History)
    task_queue_panel: TaskQueuePanel,
    /// Layout mode for task queue window (Single/SideBySide/Stacked/ThreeColumn)
    task_queue_layout: TaskQueueLayout,
    /// Secondary panel for multi-panel layouts
    task_queue_secondary_panel: TaskQueuePanel,
    /// Dialog daemon connection state
    dialog_daemon_connected: bool,
    /// Current hold mode state from dialog daemon
    dialog_hold_mode: bool,
    // === Activity Feed State ===
    /// Activity feed entries (most recent first)
    activity_feed_entries: Vec<FeedEntry>,
    /// Activity feed statistics
    activity_feed_stats: FeedStats,
    /// Activity feed WebSocket connection state
    activity_feed_connected: bool,
    /// Activity feed HTTP client
    activity_feed_http: FeedHttpClient,
    /// Currently expanded feed entry ID
    activity_feed_expanded: Option<String>,
    /// Source filter for feed entries (None = show all)
    activity_feed_source_filter: Option<FeedSource>,
    // === Zone Manager (xx-zones positioning) ===
    /// Zone manager for deterministic window positioning
    zone_manager: continuum_studio_iced::zones::ZoneManager,
    // === Agent Coordinator State ===
    /// Registered agents from the coordinator
    coordinator_agents: Vec<CoordAgent>,
    /// Active conflicts detected by the coordinator
    coordinator_conflicts: Vec<CoordConflict>,
    /// Coordinator HTTP client
    coordinator_http: CoordinatorHttpClient,
    /// Selected agent ID in the coordinator panel
    coordinator_selected: Option<String>,
    // === Offline Mode Support ===
    /// Offline operation queue (persisted)
    offline_queue: continuum_studio_iced::offline::OfflineQueue,
    /// Connection tracker for backend status (planned: route ops through queue when offline)
    #[allow(dead_code)]
    connection_tracker: continuum_studio_iced::offline::ConnectionTracker,
    // === Orchestrator Panel State ===
    /// Orchestrator panel state for managing CLI agent dialogs
    orchestrator_state: OrchestratorPanelState,
    /// Parked agents panel state
    parked_agents_state: continuum_studio_iced::parked_agents::ParkedAgentsPanelState,
    /// HTTP client for parked agents API
    parked_agents_http: ParkedAgentsHttpClient,
    /// Agent activity feed state (FileEdit, Command, ToolCall, Dialog events)
    agent_activity_state: ActivityFeedState,
    /// Memory statistics for performance monitoring
    memory_stats: MemoryStats,
    /// Toast notification (ephemeral feedback, auto-dismisses after 3s)
    toast: Option<Toast>,
}

/// Memory statistics for monitoring app state growth
#[derive(Debug, Clone, Default)]
struct MemoryStats {
    /// Number of agents being tracked
    agents_tracked: usize,
    /// Number of dialogs cached in state
    dialogs_cached: usize,
    /// Number of activity feed items
    activity_items: usize,
    /// Decision history size
    decision_history: usize,
    /// Triage queue size
    triage_queue: usize,
    /// Last update time (unix seconds) - reserved for future use
    #[allow(dead_code)]
    updated_at: u64,
}

impl MemoryStats {
    /// Compute current stats from app state
    fn compute(state: &ContinuumStudio) -> Self {
        Self {
            agents_tracked: state.cli_agents_state.agents.len()
                + state.coordinator_agents.len(),
            dialogs_cached: state.cli_agents_state.pending_dialogs.len(),
            activity_items: state.agent_activity_state.events.len(),
            decision_history: state.orchestrator_state.engine.recent_history(usize::MAX).len(),
            triage_queue: state.orchestrator_state.engine.triage_queue().len(),
            updated_at: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_secs())
                .unwrap_or(0),
        }
    }
}

/// Available views in the application
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum View {
    Dashboard,
    Cursor, // Parent view with sub-tabs
    ChatPipeline,
    Services,
    Storage,
    Settings,
    Logs,
}

// ============================================================================
// Multi-Window Support
// ============================================================================

/// Types of windows in the application
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum WindowType {
    /// Main application window
    Main,
    /// Detached task queue panel
    TaskQueue,
    /// Dialog panel (for tiling with task queue)
    DialogPanel,
    /// Combined task queue + dialog (tiled view)
    TiledPanel,
}

/// Active panel within the task queue window
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum TaskQueuePanel {
    /// Task list (pending/current tasks)
    #[default]
    Tasks,
    /// Active agents (session and sub-agents)
    Agents,
    /// Task history (completed/cancelled)
    History,
    /// Create new task form
    NewTask,
    /// Activity feed timeline
    Feed,
    /// Agent coordinator dashboard
    Coordination,
}

/// Layout modes for the task queue window
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum TaskQueueLayout {
    /// Single panel view (default)
    #[default]
    Single,
    /// Two panels side by side (horizontal split)
    SideBySide,
    /// Two panels stacked (vertical split)
    Stacked,
    /// All three panels in a row
    ThreeColumn,
}

impl TaskQueueLayout {
    fn label(&self) -> &'static str {
        match self {
            TaskQueueLayout::Single => "Single",
            TaskQueueLayout::SideBySide => "Side-by-Side",
            TaskQueueLayout::Stacked => "Stacked",
            TaskQueueLayout::ThreeColumn => "Three Column",
        }
    }

    fn icon(&self) -> &'static str {
        match self {
            TaskQueueLayout::Single => "▣",
            TaskQueueLayout::SideBySide => "◫",
            TaskQueueLayout::Stacked => "⬓",
            TaskQueueLayout::ThreeColumn => "☰",
        }
    }
}

/// State for an individual window
#[derive(Debug, Clone)]
struct WindowState {
    window_type: WindowType,
    /// For tiled panels: split orientation (true = vertical, false = horizontal)
    vertical_split: bool,
    /// For tiled panels: split ratio (0.0-1.0, where the value is the size of the first panel)
    split_ratio: f32,
}

/// Sub-tabs within the Cursor view
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
enum CursorTab {
    #[default]
    Sessions,     // Running Cursor instances
    SubAgents,    // Sub-agent monitoring
    CLIAgents,    // Headless CLI agents (NEW!)
    Orchestrator,  // Orchestrator mode and dialog triage
    ParkedAgents,  // Parked agents awaiting task assignment
    AgentActivity, // Agent activity feed (file edits, commands, dialogs)
    Auth,          // Authentication management
    Versions,     // Version management
    Workspaces,   // .code-workspace management
}

/// Application messages (Elm architecture)
#[derive(Debug, Clone)]
#[allow(dead_code)] // Some variants are planned features
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
    /// CLI agent orchestration actions
    CLIAgentAction(CLIAgentMessage),
    /// Orchestrator panel actions (mode switching, triage decisions)
    OrchestratorAction(OrchestratorMessage),
    /// Orchestrator WebSocket events (real-time dialog updates)
    OrchestratorWsEvent(continuum_studio_iced::cli_agents_client::OrchestratorWsEvent),
    /// Keyboard shortcut pressed
    KeyboardShortcut(KeyboardShortcut),
    /// Process triage timeouts (tick every second)
    ProcessTriageTimeouts,
    /// Update memory stats (periodic)
    UpdateMemoryStats,
    /// Parked agents panel actions
    ParkedAgentAction(ParkedMessage),
    /// Agent activity feed actions (FileEdit, Command, ToolCall, Dialog events)
    AgentActivityFeed(ActivityMessage),
    /// Task queue actions
    TaskQueueAction(TaskQueueMsg),

    // === Multi-Window Messages ===
    /// Open a new window of the specified type
    OpenWindow(WindowType),
    /// A window was opened successfully
    WindowOpened(window::Id, WindowType),
    /// A window was closed
    WindowClosed(window::Id),
    /// Toggle vertical/horizontal split in tiled panel
    ToggleSplitOrientation(window::Id),
    /// Adjust split ratio
    AdjustSplitRatio(window::Id, f32),
    /// Pop out task queue to its own window
    PopOutTaskQueue,
    /// Pop out dialog panel to its own window
    PopOutDialogPanel,
    /// Merge task queue and dialog into tiled window
    MergePanels,

    // === Dialog Daemon Messages ===
    /// Dialog daemon actions
    DialogAction(DialogMsg),
    /// Activity feed actions
    ActivityFeedAction(ActivityFeedMsg),
    /// Agent coordinator actions
    CoordinatorAction(CoordinatorMsg),
    /// Zone layout actions
    ZoneAction(ZoneMsg),
    /// No-op message (for ignoring errors gracefully)
    NoOp,

    // === Toast Notifications ===
    /// Show a toast notification
    ShowToast(String, ToastLevel),
    /// Dismiss the current toast
    DismissToast,
}

/// Keyboard shortcuts for quick actions
#[derive(Debug, Clone)]
enum KeyboardShortcut {
    /// Set orchestrator mode to UserActive (Ctrl+1)
    SetModeUserActive,
    /// Set orchestrator mode to UserDelegate (Ctrl+2)
    SetModeUserDelegate,
    /// Set orchestrator mode to Spectator (Ctrl+3)
    SetModeSpectator,
    /// Set orchestrator mode to Autonomous (Ctrl+4)
    SetModeAutonomous,
    /// Toggle history panel (Ctrl+H)
    ToggleHistory,
    /// Undo last decision (Ctrl+Z when on Orchestrator tab)
    UndoDecision,
    /// Refresh current view (Ctrl+R)
    RefreshView,
}

/// Zone manager sub-messages
#[derive(Debug, Clone)]
enum ZoneMsg {
    /// Switch to a different layout
    SetLayout(continuum_studio_iced::zones::ZoneLayout),
    /// Apply layout to current windows
    ApplyLayout,
}

/// Dialog daemon sub-messages
#[derive(Debug, Clone)]
enum DialogMsg {
    /// Daemon connection status changed
    DaemonConnected(bool),
    /// Hold mode state changed
    HoldModeChanged(bool),
    /// Toggle hold mode
    ToggleHoldMode,
    /// Error occurred
    Error(String),
}

/// Activity feed sub-messages
#[derive(Debug, Clone)]
enum ActivityFeedMsg {
    /// WebSocket connected
    Connected,
    /// WebSocket disconnected
    Disconnected,
    /// Initial state received from WebSocket
    InitialState {
        entries: Vec<FeedEntry>,
        stats: FeedStats,
    },
    /// New entry added via WebSocket
    EntryAdded(FeedEntry),
    /// Error from WebSocket or HTTP
    Error(String),
    /// Toggle expansion of a feed entry
    ToggleEntry(String),
    /// Set source filter
    SetSourceFilter(Option<FeedSource>),
    /// Trigger git poll via HTTP
    TriggerGitPoll,
    /// Git poll completed
    GitPollDone(Result<(), String>),
    /// Trigger stats refresh
    RefreshStats,
    /// Stats refreshed
    StatsRefreshed(Result<FeedStats, String>),
}

/// Agent coordinator sub-messages
#[derive(Debug, Clone)]
enum CoordinatorMsg {
    /// Agents list refreshed
    AgentsLoaded(Result<Vec<CoordAgent>, String>),
    /// Conflicts list refreshed
    ConflictsLoaded(Result<Vec<CoordConflict>, String>),
    /// Select an agent for detail view
    SelectAgent(String),
    /// Deselect agent
    DeselectAgent,
    /// Refresh agents and conflicts
    Refresh,
    /// Resolve a conflict
    ResolveConflict(String),
    /// Conflict resolved
    ConflictResolved(Result<(), String>),
    /// Error occurred
    Error(String),
}

/// Task queue sub-messages
#[derive(Debug, Clone)]
enum TaskQueueMsg {
    /// WebSocket connected
    Connected,
    /// WebSocket disconnected
    Disconnected,
    /// Initial state received
    InitialState {
        tasks: Vec<continuum_studio_iced::task_queue_client::Task>,
        stats: continuum_studio_iced::task_queue_client::QueueStats,
        current_task: Option<continuum_studio_iced::task_queue_client::Task>,
    },
    /// Task added
    TaskAdded(continuum_studio_iced::task_queue_client::Task),
    /// Task updated
    TaskUpdated(continuum_studio_iced::task_queue_client::Task),
    /// Task started
    TaskStarted(continuum_studio_iced::task_queue_client::Task),
    /// Task completed
    TaskCompleted(continuum_studio_iced::task_queue_client::Task),
    /// Task removed
    TaskRemoved(String),
    /// Error occurred
    Error(String),
    /// Quick-add input changed
    QuickAddChanged(String),
    /// Submit quick-add
    QuickAddSubmit,
    /// Start a task
    StartTask(String),
    /// Complete current task
    CompleteCurrentTask,
    /// Change task priority
    ChangePriority(String, continuum_studio_iced::task_queue_client::Priority),
    /// Delete a task
    DeleteTask(String),
    /// Cancel a task
    CancelTask(String),
    /// Toggle task detail expansion
    ToggleTaskDetail(String),
    /// Toggle edit mode for a task (priority dropdown, delete, etc.)
    ToggleEditMode(String),
    /// Subtask input changed
    SubtaskInputChanged(String),
    /// Submit subtask
    SubmitSubtask(String),
    /// Subtask added result
    SubtaskAdded(Result<continuum_studio_iced::task_queue_client::Task, String>),
    /// New task form: content changed
    NewTaskContentChanged(String),
    /// New task form: priority changed
    NewTaskPriorityChanged(continuum_studio_iced::task_queue_client::Priority),
    /// New task form: project changed
    NewTaskProjectChanged(String),
    /// New task form: notes changed
    NewTaskNotesChanged(String),
    /// New task form: subtask input changed
    NewTaskSubtaskInputChanged(String),
    /// New task form: add a subtask to the planned list
    NewTaskAddSubtask,
    /// New task form: remove a planned subtask
    NewTaskRemoveSubtask(usize),
    /// New task form: toggle a prereq task
    NewTaskTogglePrereq(String),
    /// Submit the full new task form
    SubmitNewTask,
    /// New task created result (after API call)
    NewTaskCreated(Result<continuum_studio_iced::task_queue_client::Task, String>),
    /// Task was deleted (for local state update)
    TaskDeleted(String),
    /// API operation result
    ApiResult(Result<continuum_studio_iced::task_queue_client::Task, String>),
    /// Switch panel view (Tasks/Agents/History)
    SwitchPanel(TaskQueuePanel),
    /// Change layout mode
    SetLayout(TaskQueueLayout),
    /// Set secondary panel (for multi-panel layouts)
    SetSecondaryPanel(TaskQueuePanel),
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
    /// Set export format
    SetExportFormat(ExportFormat),
    /// Toggle conversation selection for export
    ToggleExportSelect(String),
    /// Select all conversations for export
    SelectAllForExport,
    /// Deselect all conversations
    DeselectAllForExport,
    /// Export a single conversation
    ExportConversation(String),
    /// Export selected conversations
    #[allow(dead_code)]
    ExportSelected,
    /// Export completed
    ExportCompleted(Result<ExportResult, String>),
    /// Clear export result
    ClearExportResult,
    /// Save export to file
    SaveExportToFile,
    /// Copy export to clipboard
    CopyExportToClipboard,
    /// Switch export mode (single vs training)
    SwitchExportMode(ExportMode),
    /// Watcher status loaded
    WatcherStatusLoaded(Result<WatcherStatus, String>),
    /// Trigger watcher scan
    TriggerWatcherScan,
    /// Trigger pipeline process all
    TriggerPipelineProcessAll,
    /// Watcher scan complete
    WatcherScanDone(Result<String, String>),
    /// Pipeline process all complete
    PipelineProcessDone(Result<String, String>),
    // Training Data Export
    /// Set training export format
    SetTrainingFormat(TrainingFormat),
    /// Toggle training filter: has_code
    ToggleTrainingHasCode,
    /// Toggle training filter: agentic_only
    ToggleTrainingAgenticOnly,
    /// Set min turns filter
    SetTrainingMinTurns(u32),
    /// Set min quality score
    SetTrainingMinQuality(f64),
    /// Fetch training stats (preview)
    FetchTrainingStats,
    /// Training stats loaded
    TrainingStatsLoaded(Result<TrainingStatsResponse, String>),
    /// Fetch training samples
    FetchTrainingSamples,
    /// Training samples loaded
    TrainingSamplesLoaded(Result<TrainingSampleResponse, String>),
    /// Run training export
    RunTrainingExport,
    /// Training export complete
    TrainingExportComplete(Result<TrainingExportResponse, String>),
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
#[allow(dead_code)] // UninstallVersion planned for Phase 2
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
#[allow(dead_code)] // RefreshGitStats planned feature
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
#[allow(dead_code)] // Some variants replaced by polling approach
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
#[allow(dead_code)] // DeleteProfile, RefreshProfiles planned for Phase 2
enum AuthMessage {
    /// Refresh auth statuses for all installed versions
    RefreshStatuses,
    /// Extract auth profile from a specific version
    ExtractFromVersion(String),
    /// Apply a profile to a target version
    ApplyProfile {
        profile_id: String,
        target_version: String,
    },
    /// Delete a stored profile
    DeleteProfile(String),
    /// Refresh profiles list
    RefreshProfiles,
}

fn update(state: &mut ContinuumStudio, message: Message) -> Task<Message> {
    profile_span!("update");
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
                return Task::perform(async { SubagentMessage::Refresh }, Message::SubagentAction);
            }
            // Trigger preset and workspace override fetch when switching to CLI Agents tab
            if tab == CursorTab::ParkedAgents {
                return Task::perform(async { ParkedMessage::RefreshList }, Message::ParkedAgentAction);
            }
            if tab == CursorTab::CLIAgents && state.cli_agents_state.presets.is_empty() {
                let client1 = state.cli_agents_http.clone();
                let client2 = state.cli_agents_http.clone();

                let presets_task = Task::perform(
                    async move { client1.list_presets().await },
                    |result| match result {
                        Ok(presets) => {
                            log::info!("Auto-loaded {} presets on tab switch", presets.len());
                            Message::CLIAgentAction(CLIAgentMessage::PresetsLoaded(presets))
                        }
                        Err(e) => {
                            log::error!("Failed to auto-load presets: {}", e);
                            Message::CLIAgentAction(CLIAgentMessage::Error(e))
                        }
                    },
                );

                let overrides_task = Task::perform(
                    async move { client2.list_workspace_overrides().await },
                    |result| match result {
                        Ok(overrides) => {
                            log::info!("Auto-loaded {} workspace overrides", overrides.len());
                            Message::CLIAgentAction(CLIAgentMessage::WorkspaceOverridesLoaded(
                                overrides,
                            ))
                        }
                        Err(e) => {
                            log::warn!("Failed to load workspace overrides: {}", e);
                            Message::NoOp
                        }
                    },
                );

                return Task::batch([presets_task, overrides_task]);
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
            state.connection_state = new_state;

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
                let versions: Vec<String> = state
                    .versions
                    .iter()
                    .filter(|v| {
                        v.status == VersionStatus::Installed || v.status == VersionStatus::Running
                    })
                    .map(|v| v.version.clone())
                    .collect();
                let running_versions: Vec<String> = state
                    .versions
                    .iter()
                    .filter(|v| v.status == VersionStatus::Running)
                    .map(|v| v.version.clone())
                    .collect();
                return Task::perform(
                    async move { compute_disk_usage(versions, running_versions).await },
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
                    selected.len(),
                    remove_data,
                    keep_auth,
                    selected
                );
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx
                                .send(CoreRequest::BatchUninstallVersions {
                                    versions: selected.clone(),
                                    remove_data,
                                    keep_auth,
                                })
                                .await;
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
        Message::CLIAgentAction(msg) => {
            return handle_cli_agent_message(state, msg);
        }
        Message::OrchestratorAction(msg) => {
            return handle_orchestrator_message(state, msg);
        }
        Message::OrchestratorWsEvent(event) => {
            return handle_orchestrator_ws_event(state, event);
        }
        Message::KeyboardShortcut(shortcut) => {
            return handle_keyboard_shortcut(state, shortcut);
        }
        Message::ProcessTriageTimeouts => {
            return process_triage_timeouts(state);
        }
        Message::UpdateMemoryStats => {
            state.memory_stats = MemoryStats::compute(state);
            log::debug!(
                "[PERF] Memory stats: agents={}, dialogs={}, activity={}, history={}, triage={}",
                state.memory_stats.agents_tracked,
                state.memory_stats.dialogs_cached,
                state.memory_stats.activity_items,
                state.memory_stats.decision_history,
                state.memory_stats.triage_queue,
            );
            return Task::none();
        }
        Message::ParkedAgentAction(msg) => {
            return handle_parked_agent_message(state, msg);
        }
        Message::AgentActivityFeed(msg) => {
            state.agent_activity_state.update(msg);
            return Task::none();
        }
        Message::TaskQueueAction(msg) => {
            return handle_task_queue_message(state, msg);
        }
        Message::DialogAction(msg) => {
            return handle_dialog_message(state, msg);
        }
        Message::ActivityFeedAction(msg) => {
            return handle_activity_feed_message(state, msg);
        }
        Message::CoordinatorAction(msg) => {
            return handle_coordinator_message(state, msg);
        }
        Message::ZoneAction(msg) => {
            return handle_zone_message(state, msg);
        }
        Message::NoOp => {
            // Intentionally do nothing
        }

        // === Toast Notification Handlers ===
        Message::ShowToast(message, level) => {
            state.toast = Some(Toast {
                message,
                level,
                shown_at: std::time::Instant::now(),
            });
        }
        Message::DismissToast => {
            state.toast = None;
        }

        // === Multi-Window Message Handlers ===
        Message::OpenWindow(window_type) => {
            return handle_open_window(state, window_type);
        }
        Message::WindowOpened(id, window_type) => {
            log::info!("Window opened: {:?} with type {:?}", id, window_type);
            let window_state = WindowState {
                window_type,
                vertical_split: true,
                split_ratio: 0.5,
            };
            state.windows.insert(id, window_state);

            // Track main window if not set
            if state.main_window_id.is_none() && window_type == WindowType::Main {
                state.main_window_id = Some(id);
            }
        }
        Message::WindowClosed(id) => {
            log::info!("Window closed: {:?}", id);
            state.windows.remove(&id);

            // If main window closed, exit application
            if state.main_window_id == Some(id) {
                return iced::exit();
            }
        }
        Message::ToggleSplitOrientation(id) => {
            if let Some(ws) = state.windows.get_mut(&id) {
                ws.vertical_split = !ws.vertical_split;
            }
        }
        Message::AdjustSplitRatio(id, ratio) => {
            if let Some(ws) = state.windows.get_mut(&id) {
                ws.split_ratio = ratio.clamp(0.1, 0.9);
            }
        }
        Message::PopOutTaskQueue => {
            return handle_open_window(state, WindowType::TaskQueue);
        }
        Message::PopOutDialogPanel => {
            return handle_open_window(state, WindowType::DialogPanel);
        }
        Message::MergePanels => {
            // Close any existing task queue or dialog windows and open a tiled one
            let to_close: Vec<window::Id> = state
                .windows
                .iter()
                .filter(|(_, ws)| {
                    ws.window_type == WindowType::TaskQueue
                        || ws.window_type == WindowType::DialogPanel
                })
                .map(|(id, _)| *id)
                .collect();

            let mut tasks = Vec::new();
            for id in to_close {
                tasks.push(window::close(id));
            }
            tasks.push(handle_open_window(state, WindowType::TiledPanel));
            return Task::batch(tasks);
        }

        Message::UpdateCheckResult(result) => {
            state.checking_updates = false;
            state.settings.updates.mark_checked();

            match result {
                Ok(Some(update)) => {
                    log::info!(
                        "Update available: {} -> {}",
                        state.settings.updates.current_version,
                        update.version
                    );
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
                    let version = version.clone();
                    return Task::perform(
                        async move {
                            let _ = tx
                                .send(CoreRequest::LaunchVersion {
                                    version,
                                    folder: None,
                                })
                                .await;
                        },
                        |_| Message::CursorAction(CursorMessage::RefreshVersions),
                    );
                } else {
                    // Core not connected: fallback to direct AppImage launch
                    log::warn!("Core not connected; launching Cursor directly");

                    // Try to find the AppImage for this version
                    let versions_dir = dirs::home_dir()
                        .map(|h| h.join(".cursor-versions/downloads"))
                        .unwrap_or_default();

                    // Find matching AppImage (or latest if version is "latest")
                    let appimage = if version == "latest" {
                        // Find newest AppImage by file modification time
                        std::fs::read_dir(&versions_dir).ok().and_then(|entries| {
                            entries
                                .filter_map(|e| e.ok())
                                .filter(|e| {
                                    e.path().extension().is_some_and(|ext| ext == "AppImage")
                                })
                                .max_by_key(|e| e.metadata().ok().and_then(|m| m.modified().ok()))
                                .map(|e| e.path())
                        })
                    } else {
                        // Look for specific version pattern
                        let expected_pattern = format!("cursor-{}", version);
                        std::fs::read_dir(&versions_dir).ok().and_then(|entries| {
                            entries
                                .filter_map(|e| e.ok())
                                .filter(|e| {
                                    e.path().extension().is_some_and(|ext| ext == "AppImage")
                                })
                                .find(|e| {
                                    e.file_name().to_string_lossy().contains(&expected_pattern)
                                })
                                .map(|e| e.path())
                        })
                    };

                    if let Some(appimage_path) = appimage {
                        log::info!("Launching AppImage: {}", appimage_path.display());

                        // Build version-specific data directory path
                        let data_dir = dirs::home_dir()
                            .map(|h| h.join(format!(".cursor-{}", version)))
                            .unwrap_or_else(|| {
                                std::path::PathBuf::from(format!(".cursor-{}", version))
                            });

                        // Create data directory if it doesn't exist
                        let _ = std::fs::create_dir_all(&data_dir);

                        // Build Cursor arguments for version-specific data directories
                        let user_data_arg = format!("--user-data-dir={}", data_dir.display());
                        let extensions_arg =
                            format!("--extensions-dir={}", data_dir.join("extensions").display());

                        // On NixOS, use appimage-run wrapper if available
                        // This handles FUSE mounting and library paths correctly
                        let launch_result = std::process::Command::new("appimage-run")
                            .arg(&appimage_path)
                            .arg(&user_data_arg)
                            .arg(&extensions_arg)
                            .spawn()
                            .or_else(|_| {
                                // Fallback to direct execution if appimage-run not available
                                log::info!("appimage-run not found, trying direct execution");
                                std::process::Command::new(&appimage_path)
                                    .arg(&user_data_arg)
                                    .arg(&extensions_arg)
                                    .spawn()
                            });

                        match launch_result {
                            Ok(child) => {
                                log::info!(
                                    "Launched Cursor {} (PID: {}) with data dir: {}",
                                    version,
                                    child.id(),
                                    data_dir.display()
                                );
                            }
                            Err(e) => {
                                log::error!("Failed to launch AppImage: {}", e);
                            }
                        }
                    } else {
                        // Fallback to cursor-versions CLI
                        log::warn!(
                            "AppImage not found in {}, trying cursor-versions CLI",
                            versions_dir.display()
                        );
                        match std::process::Command::new("cursor-versions")
                            .args(["run", &version])
                            .spawn()
                        {
                            Ok(child) => {
                                log::info!("Launched via CLI (PID: {})", child.id());
                            }
                            Err(e) => {
                                log::error!("Failed to launch via CLI: {}", e);
                            }
                        }
                    }
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
                                    let _ = tx
                                        .send(CoreRequest::ApplyAuth {
                                            source,
                                            target: target_version,
                                        })
                                        .await;
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
                            let _ = tx
                                .send(CoreRequest::GetWorkspaces { limit: Some(50) })
                                .await;
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
                if let Some(ws) = state.workspaces.iter().find(|w| w.id == ws_id) {
                    let folder = ws.path.clone();
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        let version = version.clone();
                        return Task::perform(
                            async move {
                                let _ = tx
                                    .send(CoreRequest::LaunchVersion {
                                        version,
                                        folder: Some(folder),
                                    })
                                    .await;
                            },
                            |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                        );
                    } else {
                        // Core not connected: fallback to direct AppImage launch
                        let versions_dir = dirs::home_dir()
                            .map(|h| h.join(".cursor-versions/downloads"))
                            .unwrap_or_default();

                        let appimage = if version == "latest" {
                            std::fs::read_dir(&versions_dir).ok().and_then(|entries| {
                                entries
                                    .filter_map(|e| e.ok())
                                    .filter(|e| {
                                        e.path().extension().is_some_and(|ext| ext == "AppImage")
                                    })
                                    .max_by_key(|e| {
                                        e.metadata().ok().and_then(|m| m.modified().ok())
                                    })
                                    .map(|e| e.path())
                            })
                        } else {
                            std::fs::read_dir(&versions_dir).ok().and_then(|entries| {
                                entries
                                    .filter_map(|e| e.ok())
                                    .find(|e| e.file_name().to_string_lossy().contains(&version))
                                    .map(|e| e.path())
                            })
                        };

                        if let Some(appimage_path) = appimage {
                            // Build version-specific data directory path
                            let data_dir = dirs::home_dir()
                                .map(|h| h.join(format!(".cursor-{}", version)))
                                .unwrap_or_else(|| {
                                    std::path::PathBuf::from(format!(".cursor-{}", version))
                                });

                            // Create data directory if it doesn't exist
                            let _ = std::fs::create_dir_all(&data_dir);

                            // Build Cursor arguments
                            let user_data_arg = format!("--user-data-dir={}", data_dir.display());
                            let extensions_arg = format!(
                                "--extensions-dir={}",
                                data_dir.join("extensions").display()
                            );

                            // On NixOS, use appimage-run wrapper
                            let _ = std::process::Command::new("appimage-run")
                                .arg(&appimage_path)
                                .arg(&user_data_arg)
                                .arg(&extensions_arg)
                                .arg(&folder)
                                .spawn()
                                .or_else(|_| {
                                    // Fallback to direct execution
                                    std::process::Command::new(&appimage_path)
                                        .arg(&user_data_arg)
                                        .arg(&extensions_arg)
                                        .arg(&folder)
                                        .spawn()
                                });
                        } else {
                            let _ = std::process::Command::new("cursor-versions")
                                .args(["run", &version, &folder])
                                .spawn();
                        }
                    }
                }
            }
            WorkspaceMessage::ScanWorkspaceFiles => {
                log::info!("Scanning for .code-workspace files...");
                state.workspace_files_loading = true;
                return Task::perform(scan_workspace_files(), |files| {
                    Message::WorkspaceAction(WorkspaceMessage::WorkspaceFilesScanned(files))
                });
            }
            WorkspaceMessage::WorkspaceFilesScanned(files) => {
                log::info!("Found {} .code-workspace files", files.len());
                state.workspace_files = files;
                state.workspace_files_loading = false;
            }
            WorkspaceMessage::OpenWorkspaceFile(path) => {
                log::info!("Opening workspace file: {}", path);
                if let Some(tx) = &state.core_tx {
                    let tx = tx.clone();
                    return Task::perform(
                        async move {
                            let _ = tx
                                .send(CoreRequest::LaunchVersion {
                                    version: "latest".to_string(),
                                    folder: Some(path),
                                })
                                .await;
                        },
                        |_| Message::WorkspaceAction(WorkspaceMessage::RefreshWorkspaces),
                    );
                } else {
                    let _ = std::process::Command::new("cursor-versions")
                        .args(["run", "latest", &path])
                        .spawn();
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
                        async move { continuum_studio_iced::services::start_core_auto().await },
                        move |result| {
                            Message::ServiceAction(ServiceMessage::ServiceStartResult(
                                name_clone, result,
                            ))
                        },
                    );
                } else if name.contains("Dialog") {
                    return Task::perform(
                        async move { continuum_studio_iced::services::start_dialog_auto().await },
                        move |result| {
                            Message::ServiceAction(ServiceMessage::ServiceStartResult(
                                name_clone, result,
                            ))
                        },
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
                let sessions: Vec<(String, Vec<String>)> = state
                    .cursor_sessions
                    .iter()
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
            AuthMessage::ApplyProfile {
                profile_id: _,
                target_version,
            } => {
                log::info!("Applying auth profile to version: {}", target_version);
                // Use the most recent profile's source version
                if let Some(profile) = state.auth_profiles.first() {
                    if let Some(source) = &profile.extracted_from {
                        let source = source.clone();
                        if let Some(tx) = &state.core_tx {
                            let tx = tx.clone();
                            return Task::perform(
                                async move {
                                    let _ = tx
                                        .send(CoreRequest::ApplyAuth {
                                            source,
                                            target: target_version,
                                        })
                                        .await;
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
            log::info!(
                "CoreResponse received: {:?}",
                std::mem::discriminant(&response)
            );
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
                        if let Some(v) = state
                            .versions
                            .iter_mut()
                            .find(|v| v.version == inst.version)
                        {
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
                    log::info!(
                        "Version stats: {} installed, {} total",
                        stats.installed_count,
                        stats.total_versions
                    );
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
                    if let Some(existing) =
                        state.workspaces.iter_mut().find(|w| w.id == workspace.id)
                    {
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
                    log::info!(
                        "Auth status for {}: {:?}",
                        auth_status.version,
                        auth_status.status
                    );
                    state
                        .auth_statuses
                        .insert(auth_status.version.clone(), auth_status);
                }
                CoreResponse::AuthStatuses(statuses) => {
                    log::info!("Received auth statuses for {} versions", statuses.len());
                    for status in statuses {
                        state.auth_statuses.insert(status.version.clone(), status);
                    }
                }
                // Phase 2: Auth profile extraction/application
                CoreResponse::AuthExtracted(profile) => {
                    log::info!(
                        "Auth extracted from {:?}: {} ({})",
                        profile.extracted_from,
                        profile.email.as_deref().unwrap_or("no email"),
                        profile.membership
                    );
                    // Store profile for later use
                    state.auth_profiles.push(profile);
                }
                CoreResponse::AuthExtractFailed { version, error } => {
                    log::error!("Failed to extract auth from {}: {}", version, error);
                    // Could show error notification to user
                }
                CoreResponse::AuthApplied {
                    source,
                    target,
                    email,
                } => {
                    log::info!("Auth applied from {} to {} ({})", source, target, email);
                    // Refresh auth status for target version
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        let target = target.clone();
                        return Task::perform(
                            async move {
                                let _ = tx
                                    .send(CoreRequest::GetAuthStatus { version: target })
                                    .await;
                            },
                            |_| Message::CursorAction(CursorMessage::RefreshVersions),
                        );
                    }
                }
                CoreResponse::AuthApplyFailed {
                    source,
                    target,
                    error,
                } => {
                    log::error!(
                        "Failed to apply auth from {} to {}: {}",
                        source,
                        target,
                        error
                    );
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

/// Multi-window view dispatcher
fn view_for_window(state: &ContinuumStudio, window_id: window::Id) -> Element<'_, Message> {
    profile_span!("view_for_window");

    match state.windows.get(&window_id) {
        Some(ws) => match ws.window_type {
            WindowType::Main => view_main_window(state),
            WindowType::TaskQueue => view_task_queue_window(state),
            WindowType::DialogPanel => view_dialog_panel_window(state),
            WindowType::TiledPanel => {
                view_tiled_panel_window(state, ws.vertical_split, ws.split_ratio)
            }
        },
        None => view_main_window(state),
    }
}

/// Main application window view
fn view_main_window(state: &ContinuumStudio) -> Element<'_, Message> {
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

    let base_view: Element<'_, Message> = container(main_content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into();

    // Overlay toast notification if active
    if let Some(toast) = &state.toast {
        let toast_view = view_toast(toast);
        iced::widget::stack![base_view, toast_view].into()
    } else {
        base_view
    }
}

/// Render a toast notification overlay
fn view_toast(toast: &Toast) -> Element<'_, Message> {
    use iced::widget::{button, column, container, row, text, Space};

    let icon = text(toast.level.icon()).size(14);
    let message = text(&toast.message).size(12);
    let dismiss_btn = button(text("✕").size(10))
        .padding([2, 6])
        .on_press(Message::DismissToast)
        .style(|_theme, _status| iced::widget::button::Style {
            background: Some(iced::Background::Color(iced::Color::TRANSPARENT)),
            text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
            ..Default::default()
        });

    let toast_content = row![icon, Space::new().width(8), message, Space::new().width(12), dismiss_btn]
        .align_y(iced::Alignment::Center);

    let bg_color = toast.level.color();

    let toast_box = container(toast_content)
        .padding([8, 16])
        .style(move |_| iced::widget::container::Style {
            background: Some(iced::Background::Color(bg_color)),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgba(1.0, 1.0, 1.0, 0.1),
            },
            text_color: Some(iced::Color::WHITE),
            ..Default::default()
        });

    // Position at bottom-center of screen
    container(
        column![Space::new().height(Length::Fill), toast_box, Space::new().height(20),]
            .align_x(iced::Alignment::Center),
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .into()
}

/// Task queue detached window view
fn view_task_queue_window(state: &ContinuumStudio) -> Element<'_, Message> {
    let colors = &state.colors;

    // Header with connection status and layout controls
    let header = row![
        text("📋 Task Queue").size(18).color(colors.text_primary),
        Space::new().width(Length::Fill),
        // Layout buttons
        view_layout_buttons(state),
        Space::new().width(12),
        text(if state.task_queue_connected {
            "● Online"
        } else {
            "○ Offline"
        })
        .size(12)
        .color(if state.task_queue_connected {
            colors.success
        } else {
            colors.text_muted
        }),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    // Panel tabs only shown in Single layout
    let show_tabs = matches!(state.task_queue_layout, TaskQueueLayout::Single);

    let tasks_active = matches!(state.task_queue_panel, TaskQueuePanel::Tasks);
    let agents_active = matches!(state.task_queue_panel, TaskQueuePanel::Agents);
    let history_active = matches!(state.task_queue_panel, TaskQueuePanel::History);
    let new_task_active = matches!(state.task_queue_panel, TaskQueuePanel::NewTask);
    let feed_active = matches!(state.task_queue_panel, TaskQueuePanel::Feed);
    let coord_active = matches!(state.task_queue_panel, TaskQueuePanel::Coordination);

    let tasks_bg = if tasks_active {
        colors.accent
    } else {
        colors.surface
    };
    let agents_bg = if agents_active {
        colors.accent
    } else {
        colors.surface
    };
    let history_bg = if history_active {
        colors.accent
    } else {
        colors.surface
    };
    let new_task_bg = if new_task_active {
        colors.accent
    } else {
        colors.surface
    };
    let feed_bg = if feed_active {
        colors.accent
    } else {
        colors.surface
    };
    let coord_bg = if coord_active {
        colors.accent
    } else {
        colors.surface
    };

    let panel_tabs: Element<'_, Message> = if show_tabs {
        row![
            button(text("Tasks").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::Tasks
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(tasks_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
            button(text("Feed").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::Feed
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(feed_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
            button(text("Agents").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::Agents
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(agents_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
            button(text("Coord").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::Coordination
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(coord_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
            button(text("History").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::History
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(history_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
            button(text("+ New").size(12).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
                    TaskQueuePanel::NewTask
                )))
                .padding([6, 12])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(new_task_bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                }),
        ]
        .spacing(4)
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Secondary panel picker (only for SideBySide/Stacked layouts)
    let show_secondary_picker = matches!(
        state.task_queue_layout,
        TaskQueueLayout::SideBySide | TaskQueueLayout::Stacked
    );
    let secondary_picker: Element<'_, Message> = if show_secondary_picker {
        let secondary = state.task_queue_secondary_panel;
        let make_sec_btn = |panel: TaskQueuePanel, label: &'static str| {
            let is_active = secondary == panel;
            let bg = if is_active {
                iced::Color::from_rgb(0.2, 0.5, 0.3)
            } else {
                iced::Color::from_rgb(0.15, 0.15, 0.15)
            };
            button(text(label).size(10).color(colors.text_primary))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::SetSecondaryPanel(
                    panel,
                )))
                .padding([4, 8])
                .style(move |_theme, _status| button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: colors.text_primary,
                    border: iced::Border::default().rounded(3),
                    ..Default::default()
                })
        };
        row![
            text("2nd:").size(10).color(colors.text_muted),
            make_sec_btn(TaskQueuePanel::Tasks, "Tasks"),
            make_sec_btn(TaskQueuePanel::Agents, "Agents"),
            make_sec_btn(TaskQueuePanel::Feed, "Feed"),
            make_sec_btn(TaskQueuePanel::History, "History"),
            make_sec_btn(TaskQueuePanel::Coordination, "Coord"),
        ]
        .spacing(4)
        .align_y(Alignment::Center)
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Render content based on layout mode
    let panel_content: Element<'_, Message> = match state.task_queue_layout {
        TaskQueueLayout::Single => match state.task_queue_panel {
            TaskQueuePanel::Tasks => view_task_queue_tasks_panel(state),
            TaskQueuePanel::Agents => view_task_queue_agents_panel(state),
            TaskQueuePanel::History => view_task_queue_history_panel(state),
            TaskQueuePanel::NewTask => view_task_queue_new_task_panel(state),
            TaskQueuePanel::Feed => view_activity_feed_panel(state),
            TaskQueuePanel::Coordination => view_coordinator_panel(state),
        },
        TaskQueueLayout::SideBySide => row![
            view_panel_container(state, state.task_queue_panel),
            view_panel_container(state, state.task_queue_secondary_panel),
        ]
        .spacing(8)
        .into(),
        TaskQueueLayout::Stacked => column![
            view_panel_container(state, state.task_queue_panel),
            view_panel_container(state, state.task_queue_secondary_panel),
        ]
        .spacing(8)
        .into(),
        TaskQueueLayout::ThreeColumn => row![
            view_panel_container(state, TaskQueuePanel::Tasks),
            view_panel_container(state, TaskQueuePanel::Agents),
            view_panel_container(state, TaskQueuePanel::History),
        ]
        .spacing(8)
        .into(),
    };

    container(
        column![
            header,
            Space::new().height(8),
            panel_tabs,
            Space::new().height(if show_tabs { 12 } else { 4 }),
            secondary_picker,
            Space::new().height(if show_secondary_picker { 8 } else { 0 }),
            panel_content,
        ]
        .spacing(0)
        .padding(16),
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .style({
        let bg = colors.background;
        move |_| container::Style {
            background: Some(iced::Background::Color(bg)),
            ..Default::default()
        }
    })
    .into()
}

/// Layout buttons for task queue window
fn view_layout_buttons(state: &ContinuumStudio) -> Element<'_, Message> {
    let colors = &state.colors;
    let current_layout = state.task_queue_layout;

    let make_layout_btn = |layout: TaskQueueLayout| {
        let is_active = current_layout == layout;
        let bg = if is_active {
            iced::Color::from_rgb(0.2, 0.4, 0.6)
        } else {
            iced::Color::from_rgb(0.15, 0.15, 0.15)
        };

        // Use both icon and label for accessibility (label shown as tooltip text)
        let label_text = format!("{} {}", layout.icon(), layout.label());
        button(text(label_text).size(10).color(colors.text_primary))
            .on_press(Message::TaskQueueAction(TaskQueueMsg::SetLayout(layout)))
            .padding([4, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(4),
                ..Default::default()
            })
    };

    row![
        make_layout_btn(TaskQueueLayout::Single),
        make_layout_btn(TaskQueueLayout::SideBySide),
        make_layout_btn(TaskQueueLayout::Stacked),
        make_layout_btn(TaskQueueLayout::ThreeColumn),
    ]
    .spacing(2)
    .into()
}

/// Panel container with title bar
fn view_panel_container<'a>(
    state: &'a ContinuumStudio,
    panel: TaskQueuePanel,
) -> Element<'a, Message> {
    let colors = &state.colors;

    let title = match panel {
        TaskQueuePanel::Tasks => "Tasks",
        TaskQueuePanel::Agents => "Agents",
        TaskQueuePanel::History => "History",
        TaskQueuePanel::NewTask => "New Task",
        TaskQueuePanel::Feed => "Activity Feed",
        TaskQueuePanel::Coordination => "Agent Coordinator",
    };

    let title_bar = text(title).size(11).color(colors.text_secondary);

    let content: Element<'_, Message> = match panel {
        TaskQueuePanel::Tasks => view_task_queue_tasks_panel(state),
        TaskQueuePanel::Agents => view_task_queue_agents_panel(state),
        TaskQueuePanel::History => view_task_queue_history_panel(state),
        TaskQueuePanel::NewTask => view_task_queue_new_task_panel(state),
        TaskQueuePanel::Feed => view_activity_feed_panel(state),
        TaskQueuePanel::Coordination => view_coordinator_panel(state),
    };

    container(column![title_bar, Space::new().height(4), content,].spacing(0))
        .width(Length::Fill)
        .height(Length::Fill)
        .padding(8)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.12, 0.12, 0.12,
            ))),
            border: iced::Border::default().rounded(6),
            ..Default::default()
        })
        .into()
}

/// Tasks panel content for task queue window
fn view_task_queue_tasks_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    use continuum_studio_iced::task_queue_client::{Creator, TaskStatus};
    use iced::widget::text_input;

    let colors = &state.colors;

    // Current task section - prominent active task display
    let current_section: Element<'_, Message> = if let Some(task) = &state.task_queue_current {
        let task_id_cancel = task.id.clone();
        let accent = colors.accent;
        let surface = colors.surface;
        let success = colors.success;
        let error = colors.error;
        let text_primary = colors.text_primary;
        let text_muted = colors.text_muted;

        // Count subtask progress
        let subtasks: Vec<_> = state
            .task_queue_tasks
            .iter()
            .filter(|t| t.parent_id.as_deref() == Some(task.id.as_str()))
            .collect();
        let total_subtasks = subtasks.len();
        let completed_subtasks = subtasks
            .iter()
            .filter(|t| t.status == TaskStatus::Completed)
            .count();

        let progress_elem: Element<'_, Message> = if total_subtasks > 0 {
            let progress_text = format!("{}/{} subtasks", completed_subtasks, total_subtasks);
            let progress_pct = if total_subtasks > 0 {
                completed_subtasks as f32 / total_subtasks as f32
            } else {
                0.0
            };
            let bar_width = (progress_pct * 200.0) as u16;
            let success_color = success;
            let surface_dim = iced::Color {
                r: surface.r * 0.8,
                g: surface.g * 0.8,
                b: surface.b * 0.8,
                a: 1.0,
            };
            column![
                text(progress_text).size(11).color(colors.text_secondary),
                // Simple progress bar
                container(row![
                    container(
                        Space::new()
                            .width(Length::Fixed(bar_width as f32))
                            .height(4)
                    )
                    .style(move |_| container::Style {
                        background: Some(iced::Background::Color(success_color)),
                        border: iced::Border {
                            radius: 2.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }),
                    Space::new().width(Length::Fill),
                ])
                .width(200)
                .height(6)
                .style(move |_| container::Style {
                    background: Some(iced::Background::Color(surface_dim)),
                    border: iced::Border {
                        radius: 3.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }),
            ]
            .spacing(3)
            .into()
        } else {
            Element::from(Space::new().height(0))
        };

        // Active task subtask list (show in compact form)
        let subtask_status_elems: Vec<Element<'_, Message>> = subtasks
            .iter()
            .map(|st| {
                let st_icon = match st.status {
                    TaskStatus::Completed => "✓",
                    TaskStatus::InProgress => "⟳",
                    _ => "○",
                };
                let st_color = match st.status {
                    TaskStatus::Completed => colors.success,
                    TaskStatus::InProgress => colors.accent,
                    _ => colors.text_muted,
                };
                Element::from(
                    row![
                        text(st_icon).size(10).color(st_color),
                        text(&st.content)
                            .size(10)
                            .color(if st.status == TaskStatus::Completed {
                                colors.text_muted
                            } else {
                                colors.text_secondary
                            }),
                    ]
                    .spacing(4)
                    .align_y(Alignment::Center),
                )
            })
            .collect();

        let subtask_list: Element<'_, Message> = if subtask_status_elems.is_empty() {
            Element::from(Space::new().height(0))
        } else {
            column(subtask_status_elems).spacing(2).into()
        };

        container(
            column![
                // Header with pulsing accent
                row![
                    text("▶ ACTIVE").size(10).color(accent),
                    Space::new().width(Length::Fill),
                    row![
                        text(task.priority.emoji()).size(11),
                        text(task.priority.label())
                            .size(10)
                            .color(colors.text_secondary),
                    ]
                    .spacing(3),
                ]
                .align_y(Alignment::Center),
                // Task content - larger and bolder
                text(&task.content).size(15).color(text_primary),
                // Subtask progress
                progress_elem,
                // Subtask list
                subtask_list,
                // Action buttons
                row![
                    button(
                        row![text("✓").size(12), text("Complete").size(12),]
                            .spacing(4)
                            .align_y(Alignment::Center)
                    )
                    .on_press(Message::TaskQueueAction(TaskQueueMsg::CompleteCurrentTask))
                    .padding([6, 14])
                    .style(move |_theme, status| {
                        let bg = match status {
                            button::Status::Hovered => iced::Color {
                                r: (success.r * 1.2).min(1.0),
                                g: (success.g * 1.2).min(1.0),
                                b: (success.b * 1.2).min(1.0),
                                a: 1.0,
                            },
                            _ => success,
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: text_primary,
                            border: iced::Border {
                                radius: 5.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
                    button(text("Cancel").size(11).color(colors.text_muted))
                        .on_press(Message::TaskQueueAction(TaskQueueMsg::CancelTask(
                            task_id_cancel
                        )))
                        .padding([6, 12])
                        .style(move |_theme, status| {
                            let bg = match status {
                                button::Status::Hovered => iced::Color {
                                    r: error.r * 0.4,
                                    g: error.g * 0.4,
                                    b: error.b * 0.4,
                                    a: 1.0,
                                },
                                _ => iced::Color::TRANSPARENT,
                            };
                            button::Style {
                                background: Some(iced::Background::Color(bg)),
                                text_color: text_muted,
                                border: iced::Border {
                                    radius: 4.0.into(),
                                    ..Default::default()
                                },
                                ..Default::default()
                            }
                        }),
                ]
                .spacing(8),
            ]
            .spacing(6),
        )
        .padding(12)
        .width(Length::Fill)
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(surface)),
            border: iced::Border {
                color: accent,
                width: 1.5,
                radius: 8.0.into(),
            },
            ..Default::default()
        })
        .into()
    } else {
        container(
            text("No current task - select one to start")
                .size(13)
                .color(colors.text_muted),
        )
        .padding(12)
        .width(Length::Fill)
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(colors.surface)),
            border: iced::Border {
                color: colors.border_subtle,
                width: 1.0,
                radius: 8.0.into(),
            },
            ..Default::default()
        })
        .into()
    };

    // Task count header
    let total_count = state.task_queue_tasks.len();
    let top_level_count = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.parent_id.is_none() && t.status.is_active())
        .count();
    let subtask_count_active = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.parent_id.is_some() && t.status.is_active())
        .count();

    let count_header = row![
        text(format!("{} tasks", top_level_count))
            .size(11)
            .color(colors.text_primary),
        text("·").size(11).color(colors.text_muted),
        text(format!("{} subtasks", subtask_count_active))
            .size(10)
            .color(colors.text_secondary),
        text("·").size(11).color(colors.text_muted),
        text(format!("{} total", total_count))
            .size(10)
            .color(colors.text_muted),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    // Pending tasks - top-level with is_active, tree view shows subtasks
    let pending_tasks: Vec<_> = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status.is_active() && t.parent_id.is_none())
        .collect();

    let pending_items: Vec<Element<'_, Message>> = pending_tasks
        .iter()
        .map(|task| {
            use continuum_studio_iced::task_queue_client::Priority;
            let task_id_toggle = task.id.clone();
            let task_id_start = task.id.clone();
            let task_id_delete = task.id.clone();
            let task_id_priority = task.id.clone();
            let task_id_submit = task.id.clone();
            let task_id_edit = task.id.clone();
            let current_priority = task.priority;

            // Priority-based accent color
            let priority_color = match task.priority {
                Priority::Critical => iced::Color::from_rgb(0.92, 0.34, 0.34),
                Priority::High => iced::Color::from_rgb(0.96, 0.62, 0.04),
                Priority::Medium => iced::Color::from_rgb(0.96, 0.86, 0.07),
                Priority::Low => iced::Color::from_rgb(0.28, 0.73, 0.47),
                Priority::Backlog => iced::Color::from_rgb(0.5, 0.5, 0.5),
            };

            let is_expanded = state.task_queue_selected.as_ref() == Some(&task.id);
            let is_editing = state.task_queue_editing.as_ref() == Some(&task.id);
            let expand_icon = if is_expanded { "▾" } else { "▸" };

            // Count subtasks and blockers for badges
            let subtask_count = state
                .task_queue_tasks
                .iter()
                .filter(|t| t.parent_id.as_deref() == Some(task.id.as_str()))
                .count();
            let blocker_count = task.blocked_by.len();
            let has_notes = task.notes.as_ref().is_some_and(|n| !n.is_empty());

            // Build badges row
            let mut badge_items: Vec<Element<'_, Message>> = Vec::new();
            if let Some(proj) = &task.project {
                if !proj.is_empty() {
                    let accent_text = colors.accent;
                    let surface = colors.surface;
                    badge_items.push(
                        container(text(proj).size(9).color(accent_text))
                            .padding([1, 5])
                            .style(move |_| container::Style {
                                background: Some(iced::Background::Color(surface)),
                                border: iced::Border {
                                    radius: 3.0.into(),
                                    ..Default::default()
                                },
                                ..Default::default()
                            })
                            .into(),
                    );
                }
            }
            if subtask_count > 0 {
                badge_items.push(
                    text(format!("⊟ {}", subtask_count))
                        .size(9)
                        .color(colors.success)
                        .into(),
                );
            }
            if blocker_count > 0 {
                badge_items.push(
                    text(format!("⊘ {}", blocker_count))
                        .size(9)
                        .color(colors.warning)
                        .into(),
                );
            }
            if has_notes {
                badge_items.push(text("📝").size(9).into());
            }
            // Creator badge
            let creator_text = match task.created_by {
                Creator::User => "👤".to_string(),
                Creator::Agent | Creator::Cli => task
                    .agent_id
                    .as_ref()
                    .map(|id| format!("🤖 {}", &id[..12.min(id.len())]))
                    .unwrap_or_else(|| "🤖".to_string()),
                Creator::Unknown => "❓".to_string(),
            };
            badge_items.push(text(creator_text).size(9).color(colors.text_muted).into());
            let badges: Element<'_, Message> = if badge_items.is_empty() {
                Element::from(Space::new().height(0))
            } else {
                row(badge_items)
                    .spacing(6)
                    .align_y(Alignment::Center)
                    .into()
            };

            // Compact card: [expand icon] [priority emoji] [content] [edit pencil]
            let card = container(
                row![
                    // Priority color bar (left accent)
                    container(Space::new().width(4).height(Length::Fill)).style(move |_| {
                        container::Style {
                            background: Some(iced::Background::Color(priority_color)),
                            ..Default::default()
                        }
                    }),
                    // Main content area
                    column![
                        row![
                            // Expand toggle
                            button(text(expand_icon).size(12).color(colors.text_secondary))
                                .on_press(Message::TaskQueueAction(TaskQueueMsg::ToggleTaskDetail(
                                    task_id_toggle.clone()
                                )))
                                .padding([4, 4])
                                .style(|_theme, _status| button::Style {
                                    background: Some(iced::Background::Color(
                                        iced::Color::TRANSPARENT
                                    )),
                                    text_color: colors.text_muted,
                                    ..Default::default()
                                }),
                            // Priority emoji badge
                            text(task.priority.emoji()).size(12),
                            // Task content (clickable to expand)
                            button(text(&task.content).size(12).color(colors.text_primary))
                                .on_press(Message::TaskQueueAction(TaskQueueMsg::ToggleTaskDetail(
                                    task_id_toggle
                                )))
                                .padding([4, 6])
                                .width(Length::Fill)
                                .style(|_theme, status| {
                                    let bg = match status {
                                        button::Status::Hovered => colors.hover,
                                        _ => iced::Color::TRANSPARENT,
                                    };
                                    button::Style {
                                        background: Some(iced::Background::Color(bg)),
                                        text_color: colors.text_primary,
                                        border: iced::Border {
                                            radius: 3.0.into(),
                                            ..Default::default()
                                        },
                                        ..Default::default()
                                    }
                                }),
                            // Edit toggle (pencil icon)
                            button(text("✎").size(11).color(if is_editing {
                                colors.accent
                            } else {
                                colors.text_muted
                            }))
                            .on_press(Message::TaskQueueAction(TaskQueueMsg::ToggleEditMode(
                                task_id_edit
                            )))
                            .padding([4, 5])
                            .style(move |_theme, status| {
                                let bg = match status {
                                    button::Status::Hovered => colors.hover,
                                    _ => iced::Color::TRANSPARENT,
                                };
                                button::Style {
                                    background: Some(iced::Background::Color(bg)),
                                    text_color: colors.text_muted,
                                    border: iced::Border {
                                        radius: 3.0.into(),
                                        ..Default::default()
                                    },
                                    ..Default::default()
                                }
                            }),
                        ]
                        .spacing(3)
                        .align_y(Alignment::Center),
                        // Badges row (project, subtask count, blocker count, notes indicator)
                        badges,
                    ]
                    .spacing(2)
                    .padding([4, 6]),
                ]
                .spacing(0),
            )
            .style({
                let surface = colors.surface;
                move |_| container::Style {
                    background: Some(iced::Background::Color(surface)),
                    border: iced::Border {
                        radius: 6.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            })
            .width(Length::Fill);

            // Always show subtasks in tree view (indented, CLI-style icons)
            let task_subtasks: Vec<_> = state
                .task_queue_tasks
                .iter()
                .filter(|t| t.parent_id.as_deref() == Some(task.id.as_str()))
                .collect();
            let subtask_tree: Vec<Element<'_, Message>> = task_subtasks
                .iter()
                .map(|st| {
                    let st_icon = match st.status {
                        TaskStatus::Completed => "✓",
                        TaskStatus::InProgress => "→",
                        TaskStatus::Cancelled => "✗",
                        _ => "○",
                    };
                    let st_color = match st.status {
                        TaskStatus::Completed => colors.success,
                        TaskStatus::InProgress => colors.accent,
                        TaskStatus::Cancelled => colors.error,
                        _ => colors.text_muted,
                    };
                    let is_blocked = !st.blocked_by.is_empty();
                    let blocked_indicator = if is_blocked { " 🔒" } else { "" };
                    container(
                        row![
                            Space::new().width(24),
                            row![
                                text(st_icon).size(11).color(st_color),
                                text(format!("[{}]", &st.id[..8.min(st.id.len())]))
                                    .size(9)
                                    .color(colors.text_muted),
                                text(format!("{}{}", st.content, blocked_indicator))
                                    .size(11)
                                    .color(if st.status == TaskStatus::Completed {
                                        colors.text_muted
                                    } else {
                                        colors.text_secondary
                                    }),
                            ]
                            .spacing(4)
                            .align_y(Alignment::Center),
                        ]
                        .spacing(0)
                        .align_y(Alignment::Center),
                    )
                    .padding([2, 0])
                    .into()
                })
                .collect();

            // Edit controls (shown when editing)
            let edit_section: Element<'_, Message> = if is_editing {
                container(
                    row![
                        iced::widget::pick_list(
                            Priority::all(),
                            Some(current_priority),
                            move |new_priority| {
                                Message::TaskQueueAction(TaskQueueMsg::ChangePriority(
                                    task_id_priority.clone(),
                                    new_priority,
                                ))
                            }
                        )
                        .text_size(11)
                        .width(Length::Shrink),
                        Space::new().width(Length::Fill),
                        button(text("🗑 Delete").size(11).color(colors.error))
                            .on_press(Message::TaskQueueAction(TaskQueueMsg::DeleteTask(
                                task_id_delete
                            )))
                            .padding([5, 10])
                            .style(|_theme, status| {
                                let bg = match status {
                                    button::Status::Hovered => colors.error,
                                    _ => colors.error,
                                };
                                button::Style {
                                    background: Some(iced::Background::Color(bg)),
                                    text_color: colors.error,
                                    border: iced::Border {
                                        radius: 4.0.into(),
                                        ..Default::default()
                                    },
                                    ..Default::default()
                                }
                            }),
                    ]
                    .spacing(8)
                    .align_y(Alignment::Center),
                )
                .padding([6, 12])
                .style({
                    let bg = colors.background;
                    move |_| container::Style {
                        background: Some(iced::Background::Color(bg)),
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }
                })
                .width(Length::Fill)
                .into()
            } else {
                Element::from(Space::new().height(0))
            };

            // Expanded detail section (shown when expanded)
            let expanded_content: Element<'_, Message> = if is_expanded {
                let mut detail_items: Vec<Element<'_, Message>> = Vec::new();

                // Notes section
                if let Some(notes) = task.notes.as_ref().filter(|n| !n.is_empty()) {
                    detail_items.push(
                        container(
                            column![
                                text("NOTES").size(9).color(colors.text_muted),
                                text(notes).size(11).color(colors.text_secondary),
                            ]
                            .spacing(2),
                        )
                        .padding([6, 10])
                        .style({
                            let bg = colors.background;
                            move |_| container::Style {
                                background: Some(iced::Background::Color(bg)),
                                border: iced::Border {
                                    radius: 4.0.into(),
                                    ..Default::default()
                                },
                                ..Default::default()
                            }
                        })
                        .width(Length::Fill)
                        .into(),
                    );
                }

                // Blocked-by / prerequisites section
                if !task.blocked_by.is_empty() {
                    let blocker_elems: Vec<Element<'_, Message>> = task
                        .blocked_by
                        .iter()
                        .map(|bid| {
                            let blocker = state.task_queue_tasks.iter().find(|t| t.id == *bid);
                            let label = blocker
                                .map(|t| format!("[{}] {}", &t.id[..8.min(t.id.len())], t.content))
                                .unwrap_or_else(|| format!("[{}...]", &bid[..8.min(bid.len())]));
                            let status_icon = blocker.map_or("⊘", |b| {
                                if b.status == TaskStatus::Completed {
                                    "✓"
                                } else {
                                    "⊘"
                                }
                            });
                            let status_color = blocker.map_or(colors.warning, |b| {
                                if b.status == TaskStatus::Completed {
                                    colors.success
                                } else {
                                    colors.warning
                                }
                            });
                            Element::from(
                                row![
                                    text(status_icon).size(11).color(status_color),
                                    text(label).size(11).color(colors.text_secondary),
                                ]
                                .spacing(4)
                                .align_y(Alignment::Center),
                            )
                        })
                        .collect();
                    detail_items.push(
                        container(
                            column![text("BLOCKED BY").size(9).color(colors.warning),]
                                .push(column(blocker_elems).spacing(2))
                                .spacing(3),
                        )
                        .padding([6, 10])
                        .style({
                            let bg = colors.background;
                            move |_| container::Style {
                                background: Some(iced::Background::Color(bg)),
                                border: iced::Border {
                                    radius: 4.0.into(),
                                    ..Default::default()
                                },
                                ..Default::default()
                            }
                        })
                        .width(Length::Fill)
                        .into(),
                    );
                }

                // Add subtask input row
                detail_items.push(
                    row![
                        text_input("Add subtask...", &state.task_queue_subtask_input)
                            .on_input(|s| Message::TaskQueueAction(
                                TaskQueueMsg::SubtaskInputChanged(s)
                            ))
                            .on_submit(Message::TaskQueueAction(TaskQueueMsg::SubmitSubtask(
                                task_id_submit.clone()
                            )))
                            .padding(6)
                            .size(11)
                            .width(Length::Fill),
                        button(text("+").size(12))
                            .on_press(Message::TaskQueueAction(TaskQueueMsg::SubmitSubtask(
                                task_id_submit
                            )))
                            .padding([6, 10])
                            .style(|_theme, status| {
                                let bg = match status {
                                    button::Status::Hovered => colors.success,
                                    _ => colors.success,
                                };
                                button::Style {
                                    background: Some(iced::Background::Color(bg)),
                                    text_color: colors.text_primary,
                                    border: iced::Border {
                                        radius: 4.0.into(),
                                        ..Default::default()
                                    },
                                    ..Default::default()
                                }
                            }),
                    ]
                    .spacing(6)
                    .align_y(Alignment::Center)
                    .into(),
                );

                // Action buttons
                detail_items.push(
                    row![button(
                        row![text("▶").size(11), text("Start").size(11),]
                            .spacing(4)
                            .align_y(Alignment::Center)
                    )
                    .on_press(Message::TaskQueueAction(TaskQueueMsg::StartTask(
                        task_id_start
                    )))
                    .padding([6, 12])
                    .style(|_theme, status| {
                        let bg = match status {
                            button::Status::Hovered => colors.accent_hover,
                            _ => colors.accent,
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: colors.text_primary,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),]
                    .spacing(8)
                    .into(),
                );

                container(column(detail_items).spacing(6))
                    .padding([8, 10])
                    .style({
                        let bg = colors.background;
                        move |_| container::Style {
                            background: Some(iced::Background::Color(bg)),
                            border: iced::Border {
                                radius: 0.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    })
                    .width(Length::Fill)
                    .into()
            } else {
                Element::from(Space::new().height(0))
            };

            let mut col_items: Vec<Element<'_, Message>> = vec![card.into()];
            col_items.extend(subtask_tree);
            col_items.push(edit_section);
            col_items.push(expanded_content);
            column(col_items).spacing(0).into()
        })
        .collect();

    let pending_section: Element<'_, Message> = if pending_items.is_empty() {
        text("All caught up!")
            .size(12)
            .color(colors.text_secondary)
            .into()
    } else {
        scrollable(column(pending_items).spacing(4))
            .height(Length::Fill)
            .into()
    };

    // New Task button (switches to NewTask tab)
    let new_task_button = button(
        row![text("+").size(14), text("New Task").size(12),]
            .spacing(4)
            .align_y(Alignment::Center),
    )
    .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
        TaskQueuePanel::NewTask,
    )))
    .padding([8, 16])
    .width(Length::Fill)
    .style(|_theme, status| {
        let bg = match status {
            button::Status::Hovered => colors.accent_hover,
            _ => colors.accent,
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: colors.text_primary,
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        }
    });

    // Stats
    let stats = &state.task_queue_stats;
    let stats_row = row![
        text(format!("{} pending", stats.pending))
            .size(11)
            .color(colors.text_secondary),
        text("·").size(11).color(colors.text_secondary),
        text(format!("{} active", stats.in_progress))
            .size(11)
            .color(colors.text_secondary),
        text("·").size(11).color(colors.text_secondary),
        text(format!("{} done", stats.completed))
            .size(11)
            .color(colors.text_secondary),
    ]
    .spacing(4);

    // Quick-add input row
    let quick_add_row = row![
        text_input("Quick add task...", &state.task_queue_input)
            .on_input(|s| Message::TaskQueueAction(TaskQueueMsg::QuickAddChanged(s)))
            .on_submit(Message::TaskQueueAction(TaskQueueMsg::QuickAddSubmit))
            .padding(8)
            .size(12)
            .width(Length::Fill),
        button(text("+").size(14).color(colors.text_primary))
            .on_press(Message::TaskQueueAction(TaskQueueMsg::QuickAddSubmit))
            .padding([8, 14])
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Hovered => colors.success,
                    _ => colors.success,
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: colors.text_primary,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            }),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    column![
        current_section,
        Space::new().height(16),
        count_header,
        Space::new().height(4),
        text("PENDING").size(11).color(colors.text_secondary),
        Space::new().height(4),
        pending_section,
        Space::new().height(12),
        quick_add_row,
        Space::new().height(8),
        new_task_button,
        Space::new().height(8),
        stats_row,
    ]
    .spacing(0)
    .into()
}

/// New Task creation panel for task queue window
fn view_task_queue_new_task_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    use continuum_studio_iced::task_queue_client::Priority;
    use iced::widget::text_input;

    let colors = &state.colors;

    // Header
    let header = text("CREATE NEW TASK")
        .size(13)
        .color(colors.text_secondary);

    // Content/title field
    let content_input = column![
        text("Task Description")
            .size(11)
            .color(colors.text_secondary),
        text_input("What needs to be done?", &state.new_task_content)
            .on_input(|s| Message::TaskQueueAction(TaskQueueMsg::NewTaskContentChanged(s)))
            .padding(10)
            .size(14)
            .width(Length::Fill),
    ]
    .spacing(4);

    // Priority picker
    let priority_section = column![
        text("Priority").size(11).color(colors.text_secondary),
        iced::widget::pick_list(Priority::all(), Some(state.new_task_priority), |p| {
            Message::TaskQueueAction(TaskQueueMsg::NewTaskPriorityChanged(p))
        })
        .text_size(13)
        .width(Length::Fill),
    ]
    .spacing(4);

    // Project field
    let project_input = column![
        text("Project (optional)")
            .size(11)
            .color(colors.text_secondary),
        text_input("e.g. homelab, synapsix...", &state.new_task_project)
            .on_input(|s| Message::TaskQueueAction(TaskQueueMsg::NewTaskProjectChanged(s)))
            .padding(8)
            .size(13)
            .width(Length::Fill),
    ]
    .spacing(4);

    // Notes field
    let notes_input = column![
        text("Notes (optional)")
            .size(11)
            .color(colors.text_secondary),
        text_input("Additional context or details...", &state.new_task_notes)
            .on_input(|s| Message::TaskQueueAction(TaskQueueMsg::NewTaskNotesChanged(s)))
            .padding(8)
            .size(13)
            .width(Length::Fill),
    ]
    .spacing(4);

    // Subtasks section
    let subtask_items: Vec<Element<'_, Message>> = state
        .new_task_subtasks
        .iter()
        .enumerate()
        .map(|(i, st)| {
            row![
                text(format!("  ↳ {}", st))
                    .size(12)
                    .color(colors.text_primary),
                Space::new().width(Length::Fill),
                button(text("✕").size(10))
                    .on_press(Message::TaskQueueAction(
                        TaskQueueMsg::NewTaskRemoveSubtask(i)
                    ))
                    .padding([2, 6])
                    .style(|_theme, status| {
                        let bg = match status {
                            button::Status::Hovered => colors.error,
                            _ => iced::Color::TRANSPARENT,
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: colors.text_muted,
                            border: iced::Border {
                                radius: 3.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
            ]
            .spacing(4)
            .align_y(Alignment::Center)
            .into()
        })
        .collect();

    let subtasks_section = column![
        text("Subtasks").size(11).color(colors.text_secondary),
        column(subtask_items).spacing(2),
        row![
            text_input("Add a subtask...", &state.new_task_subtask_input)
                .on_input(|s| Message::TaskQueueAction(TaskQueueMsg::NewTaskSubtaskInputChanged(s)))
                .on_submit(Message::TaskQueueAction(TaskQueueMsg::NewTaskAddSubtask))
                .padding(8)
                .size(12)
                .width(Length::Fill),
            button(text("+ Add").size(11))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::NewTaskAddSubtask))
                .padding([8, 12])
                .style(|_theme, status| {
                    let bg = match status {
                        button::Status::Hovered => colors.success,
                        _ => colors.success,
                    };
                    button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: colors.text_primary,
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }
                }),
        ]
        .spacing(6)
        .align_y(Alignment::Center),
    ]
    .spacing(4);

    // Prerequisites section - show existing tasks as toggleable checkboxes
    let available_prereqs: Vec<_> = state
        .task_queue_tasks
        .iter()
        .filter(|t| {
            t.status == continuum_studio_iced::task_queue_client::TaskStatus::Pending
                || t.status == continuum_studio_iced::task_queue_client::TaskStatus::InProgress
                || t.status == continuum_studio_iced::task_queue_client::TaskStatus::Claimed
        })
        .collect();

    let prereq_items: Vec<Element<'_, Message>> = available_prereqs
        .iter()
        .map(|task| {
            let is_selected = state.new_task_prereqs.contains(&task.id);
            let task_id_clone = task.id.clone();
            let check_color = if is_selected {
                colors.accent
            } else {
                colors.text_muted
            };
            let check_text = if is_selected { "☑" } else { "☐" };

            button(
                row![
                    text(check_text).size(14).color(check_color),
                    text(&task.content).size(12).color(colors.text_primary),
                ]
                .spacing(6)
                .align_y(Alignment::Center),
            )
            .on_press(Message::TaskQueueAction(TaskQueueMsg::NewTaskTogglePrereq(
                task_id_clone,
            )))
            .padding([4, 8])
            .width(Length::Fill)
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Hovered => colors.hover,
                    _ => iced::Color::TRANSPARENT,
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: colors.text_primary,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            })
            .into()
        })
        .collect();

    let prereqs_section = if available_prereqs.is_empty() {
        column![
            text("Prerequisites (Blocked By)")
                .size(11)
                .color(colors.text_secondary),
            text("No pending tasks to select as prerequisites")
                .size(11)
                .color(colors.text_muted),
        ]
        .spacing(4)
    } else {
        column![
            text("Prerequisites (Blocked By)")
                .size(11)
                .color(colors.text_secondary),
            text("Select tasks that must complete before this one:")
                .size(10)
                .color(colors.text_muted),
            scrollable(column(prereq_items).spacing(2)).height(Length::Shrink),
        ]
        .spacing(4)
    };

    // Submit button
    let can_submit = !state.new_task_content.trim().is_empty();
    let submit_btn = button(
        row![text("✓").size(14), text("Create Task").size(13),]
            .spacing(6)
            .align_y(Alignment::Center),
    )
    .padding([10, 20])
    .width(Length::Fill)
    .style(move |_theme, status| {
        let bg = if !can_submit {
            colors.surface
        } else {
            match status {
                button::Status::Hovered => colors.accent_hover,
                _ => colors.accent,
            }
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: if can_submit {
                colors.text_primary
            } else {
                colors.text_muted
            },
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        }
    });
    let submit_btn = if can_submit {
        submit_btn.on_press(Message::TaskQueueAction(TaskQueueMsg::SubmitNewTask))
    } else {
        submit_btn
    };

    // Cancel/back button
    let back_btn = button(
        text("← Back to Tasks")
            .size(12)
            .color(colors.text_secondary),
    )
    .on_press(Message::TaskQueueAction(TaskQueueMsg::SwitchPanel(
        TaskQueuePanel::Tasks,
    )))
    .padding([6, 12])
    .style(|_theme, status| {
        let bg = match status {
            button::Status::Hovered => colors.hover,
            _ => iced::Color::TRANSPARENT,
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: colors.text_muted,
            border: iced::Border {
                radius: 4.0.into(),
                ..Default::default()
            },
            ..Default::default()
        }
    });

    scrollable(
        column![
            header,
            Space::new().height(12),
            content_input,
            Space::new().height(12),
            priority_section,
            Space::new().height(12),
            project_input,
            Space::new().height(12),
            notes_input,
            Space::new().height(16),
            subtasks_section,
            Space::new().height(16),
            prereqs_section,
            Space::new().height(20),
            submit_btn,
            Space::new().height(8),
            back_btn,
            Space::new().height(16),
        ]
        .spacing(0),
    )
    .height(Length::Fill)
    .into()
}

/// Agents panel content for task queue window
fn view_task_queue_agents_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    use continuum_studio_iced::coordinator_client::{AgentStatus as CoordStatus, AgentType};

    let colors = &state.colors;

    // Separate coordinator agents by type
    let session_agents: Vec<_> = state
        .coordinator_agents
        .iter()
        .filter(|a| a.agent_type == AgentType::SessionAgent)
        .collect();
    let sub_agents: Vec<_> = state
        .coordinator_agents
        .iter()
        .filter(|a| a.agent_type == AgentType::SubAgent)
        .collect();

    // Count CLI agents that are running
    let cli_agent_count = state
        .cli_agents_state
        .agents
        .values()
        .filter(|a| matches!(a.status, continuum_studio_iced::cli_agents::CLIAgentStatus::Running))
        .count();

    let total_count = session_agents.len() + sub_agents.len() + cli_agent_count;

    let header = row![
        text("ACTIVE AGENTS").size(11).color(colors.text_secondary),
        Space::new().width(Length::Fill),
        text(format!("{}", total_count))
            .size(11)
            .color(if total_count > 0 {
                iced::Color::from_rgb(0.3, 0.8, 0.3)
            } else {
                colors.text_secondary
            }),
    ];

    // Helper to get status color
    let status_color = |status: &CoordStatus| -> iced::Color {
        match status {
            CoordStatus::Active => iced::Color::from_rgb(0.3, 0.8, 0.3),
            CoordStatus::Idle => iced::Color::from_rgb(0.8, 0.7, 0.2),
            CoordStatus::Waiting => iced::Color::from_rgb(0.3, 0.5, 0.9),
            CoordStatus::Completed => iced::Color::from_rgb(0.5, 0.5, 0.5),
            CoordStatus::Disconnected => iced::Color::from_rgb(0.8, 0.3, 0.3),
            CoordStatus::Unknown => iced::Color::from_rgb(0.5, 0.5, 0.5),
        }
    };

    // Session agents section
    let session_agents_section = {
        let mut section = column![
            text("Session Agents").size(13).color(colors.text_primary),
            Space::new().height(4),
        ]
        .spacing(4);

        if session_agents.is_empty() {
            section = section.push(
                text("No session agents registered")
                    .size(12)
                    .color(colors.text_secondary),
            );
        } else {
            for agent in &session_agents {
                let workspace_name = agent
                    .workspace
                    .as_ref()
                    .and_then(|w| w.rsplit('/').next())
                    .unwrap_or("unknown");
                let agent_row = row![
                    container(
                        text(agent.status.icon())
                            .size(10)
                            .color(status_color(&agent.status))
                    )
                    .padding([2, 4]),
                    column![
                        text(workspace_name)
                            .size(12)
                            .color(colors.text_primary),
                        text(&agent.id[..8.min(agent.id.len())])
                            .size(10)
                            .color(colors.text_secondary),
                    ]
                    .spacing(2),
                ]
                .spacing(6)
                .align_y(Alignment::Center);
                section = section.push(agent_row);
            }
        }
        section
    };

    // Sub-agents section
    let sub_agents_section = {
        let mut section = column![
            Space::new().height(16),
            text("Sub-Agents").size(13).color(colors.text_primary),
            Space::new().height(4),
        ]
        .spacing(4);

        if sub_agents.is_empty() {
            section = section.push(
                text("No active sub-agents")
                    .size(12)
                    .color(colors.text_secondary),
            );
        } else {
            for agent in &sub_agents {
                let focus_desc = agent
                    .focus
                    .description
                    .as_deref()
                    .unwrap_or("working...");
                let agent_row = row![
                    container(
                        text(agent.status.icon())
                            .size(10)
                            .color(status_color(&agent.status))
                    )
                    .padding([2, 4]),
                    column![
                        text(&agent.id[..8.min(agent.id.len())])
                            .size(12)
                            .color(colors.text_primary),
                        text(focus_desc)
                            .size(10)
                            .color(colors.text_secondary),
                    ]
                    .spacing(2),
                ]
                .spacing(6)
                .align_y(Alignment::Center);
                section = section.push(agent_row);
            }
        }
        section
    };

    // CLI agents section (from cli_agents_state)
    let cli_agents_section = {
        let running_cli_agents: Vec<_> = state
            .cli_agents_state
            .agents
            .values()
            .filter(|a| matches!(a.status, continuum_studio_iced::cli_agents::CLIAgentStatus::Running))
            .collect();

        let mut section = column![
            Space::new().height(16),
            text("CLI Agents").size(13).color(colors.text_primary),
            Space::new().height(4),
        ]
        .spacing(4);

        if running_cli_agents.is_empty() {
            section = section.push(
                text("No CLI agents running")
                    .size(12)
                    .color(colors.text_secondary),
            );
        } else {
            for agent in &running_cli_agents {
                let workspace_name = agent
                    .workspace
                    .rsplit('/')
                    .next()
                    .unwrap_or("unknown");
                let agent_row = row![
                    container(
                        text("▶")
                            .size(10)
                            .color(iced::Color::from_rgb(0.3, 0.8, 0.3))
                    )
                    .padding([2, 4]),
                    column![
                        text(workspace_name)
                            .size(12)
                            .color(colors.text_primary),
                        text(&agent.id[..8.min(agent.id.len())])
                            .size(10)
                            .color(colors.text_secondary),
                    ]
                    .spacing(2),
                ]
                .spacing(6)
                .align_y(Alignment::Center);
                section = section.push(agent_row);
            }
        }
        section
    };

    // Info text showing data source
    let info_text = text(if total_count > 0 {
        "Agent data from Synapsix Coordinator"
    } else {
        "Connect to Synapsix to see agent activity"
    })
    .size(10)
    .color(colors.text_secondary);

    column![
        header,
        Space::new().height(12),
        scrollable(
            column![session_agents_section, sub_agents_section, cli_agents_section,].spacing(0)
        )
        .height(Length::Fill),
        info_text,
    ]
    .spacing(0)
    .into()
}

/// History panel content for task queue window
fn view_task_queue_history_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    use continuum_studio_iced::task_queue_client::TaskStatus;

    let colors = &state.colors;

    // Show completed AND cancelled tasks from the task list
    let history_tasks: Vec<_> = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Completed || t.status == TaskStatus::Cancelled)
        .collect();

    let completed_count = history_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Completed)
        .count();
    let cancelled_count = history_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Cancelled)
        .count();

    let header_text = format!(
        "HISTORY  ({} completed, {} cancelled)",
        completed_count, cancelled_count
    );
    let header = text(header_text).size(11).color(colors.text_secondary);

    let bg_color = colors.background;
    let surface_color = colors.surface;

    let history_items: Vec<Element<'_, Message>> = history_tasks
        .iter()
        .map(|task| {
            let (status_icon, status_color) = match task.status {
                TaskStatus::Completed => ("✓", colors.success),
                TaskStatus::Cancelled => ("✗", colors.error),
                _ => ("?", colors.text_secondary),
            };

            let creator_emoji = task.created_by.emoji();

            // Project badge if present
            let project_text: String = task.project.clone().unwrap_or_default();
            let has_project = !project_text.is_empty();

            // Build the task row
            let mut task_row = row![
                text(status_icon).size(12).color(status_color),
                text(&task.content).size(12).color(colors.text_primary),
            ]
            .spacing(6)
            .align_y(iced::Alignment::Center);

            // Add project badge
            if has_project {
                task_row = task_row.push(
                    container(
                        text(format!("[{}]", project_text))
                            .size(9)
                            .color(colors.accent),
                    )
                    .padding([1, 4])
                    .style(move |_| container::Style {
                        background: Some(iced::Background::Color(iced::Color {
                            a: 0.15,
                            ..surface_color
                        })),
                        border: iced::Border::default().rounded(3),
                        ..Default::default()
                    }),
                );
            }

            // Add creator badge
            task_row = task_row.push(text(creator_emoji).size(10));

            // Priority label
            let priority_line = text(format!("{} priority", task.priority.label()))
                .size(10)
                .color(colors.text_secondary);

            container(column![task_row, priority_line,].spacing(2))
                .padding([8, 10])
                .style(move |_| container::Style {
                    background: Some(iced::Background::Color(bg_color)),
                    border: iced::Border::default().rounded(4),
                    ..Default::default()
                })
                .width(Length::Fill)
                .into()
        })
        .collect();

    let history_section: Element<'_, Message> = if history_items.is_empty() {
        container(
            text("No completed or cancelled tasks yet")
                .size(12)
                .color(colors.text_secondary),
        )
        .padding(20)
        .center_x(Length::Fill)
        .into()
    } else {
        scrollable(column(history_items).spacing(6))
            .height(Length::Fill)
            .into()
    };

    // Stats summary from server
    let stats = &state.task_queue_stats;
    let stats_text = text(format!(
        "Total: {} completed, {} cancelled  |  {} active tasks",
        stats.completed,
        stats.cancelled,
        stats.pending + stats.in_progress
    ))
    .size(11)
    .color(colors.text_secondary);

    column![
        header,
        Space::new().height(12),
        history_section,
        Space::new().height(8),
        stats_text,
    ]
    .spacing(0)
    .into()
}

/// Dialog panel detached window view
fn view_dialog_panel_window(state: &ContinuumStudio) -> Element<'_, Message> {
    let colors = &state.colors;

    // Connection status indicator
    let (status_icon, status_text, status_color) = if state.dialog_daemon_connected {
        ("●", "Connected", iced::Color::from_rgb(0.3, 0.8, 0.3))
    } else {
        ("○", "Disconnected", iced::Color::from_rgb(0.6, 0.6, 0.6))
    };

    // Header with connection status
    let header = row![
        text("🔔 Dialog Panel").size(16).color(colors.text_primary),
        Space::new().width(Length::Fill),
        text(status_icon).size(10).color(status_color),
        text(status_text).size(11).color(status_color),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    // Hold mode toggle
    let hold_mode_btn = button(
        row![
            text(if state.dialog_hold_mode { "⏸" } else { "▶" }).size(12),
            text(if state.dialog_hold_mode {
                "Hold ON"
            } else {
                "Hold OFF"
            })
            .size(11),
        ]
        .spacing(4),
    )
    .on_press(Message::DialogAction(DialogMsg::ToggleHoldMode))
    .padding([6, 12])
    .style(move |_theme, status| {
        let is_held = state.dialog_hold_mode;
        let bg = match (status, is_held) {
            (button::Status::Hovered, true) => iced::Color::from_rgb(0.6, 0.3, 0.2),
            (_, true) => iced::Color::from_rgb(0.5, 0.25, 0.15),
            (button::Status::Hovered, false) => iced::Color::from_rgb(0.25, 0.25, 0.28),
            (_, false) => iced::Color::from_rgb(0.18, 0.18, 0.2),
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: if is_held {
                iced::Color::WHITE
            } else {
                colors.text_secondary
            },
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        }
    });

    // Content area - show waiting message or current dialog
    let content_area: Element<'_, Message> = if state.dialog_daemon_connected {
        column![
            Space::new().height(Length::Fill),
            text("Listening for dialog requests...")
                .size(13)
                .color(colors.text_secondary),
            Space::new().height(8),
            text("Dialogs from AI agents will appear here")
                .size(11)
                .color(colors.text_secondary),
            Space::new().height(16),
            hold_mode_btn,
            Space::new().height(Length::Fill),
        ]
        .spacing(4)
        .align_x(Alignment::Center)
        .into()
    } else {
        column![
            Space::new().height(Length::Fill),
            text("Dialog daemon not connected")
                .size(13)
                .color(colors.text_secondary),
            Space::new().height(8),
            text("Run: systemctl --user start synapsix-dialog")
                .size(10)
                .color(colors.text_secondary),
            Space::new().height(Length::Fill),
        ]
        .spacing(4)
        .align_x(Alignment::Center)
        .into()
    };

    // Footer with instructions
    let footer = row![
        text("💡").size(11),
        text("Use synapsix-dialog-cli to send test dialogs")
            .size(10)
            .color(colors.text_secondary),
    ]
    .spacing(6);

    container(
        column![
            header,
            Space::new().height(12),
            container(content_area)
                .width(Length::Fill)
                .height(Length::Fill)
                .center_x(Length::Fill),
            Space::new().height(8),
            footer,
        ]
        .padding(16),
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.1, 0.1, 0.1,
        ))),
        ..Default::default()
    })
    .into()
}

/// Tiled panel window view (Task Queue + Dialog side by side)
fn view_tiled_panel_window(
    state: &ContinuumStudio,
    vertical_split: bool,
    split_ratio: f32,
) -> Element<'_, Message> {
    let task_queue = view_task_queue_window(state);
    let dialog_panel = view_dialog_panel_window(state);

    // Calculate sizes based on split ratio
    let first_length = Length::FillPortion((split_ratio * 100.0) as u16);
    let second_length = Length::FillPortion(((1.0 - split_ratio) * 100.0) as u16);

    let content: Element<'_, Message> = if vertical_split {
        row![
            container(task_queue)
                .width(first_length)
                .height(Length::Fill),
            container(dialog_panel)
                .width(second_length)
                .height(Length::Fill),
        ]
        .spacing(2)
        .into()
    } else {
        column![
            container(task_queue)
                .width(Length::Fill)
                .height(first_length),
            container(dialog_panel)
                .width(Length::Fill)
                .height(second_length),
        ]
        .spacing(2)
        .into()
    };

    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}

/// Sidebar navigation with COSMIC-inspired styling
fn sidebar(state: &ContinuumStudio) -> Element<'_, Message> {
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
        nav_button("📊  Dashboard", View::Dashboard, current),
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
fn view_dashboard(state: &ContinuumStudio) -> Element<'_, Message> {
    profile_span!("view_dashboard");
    let colors = &state.colors;

    // Status card
    let status_card = card(
        column![
            text("System Status").size(14).color(colors.text_secondary),
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
                    ConnectionState::Connecting | ConnectionState::Reconnecting { .. } =>
                        colors.status_connecting,
                    ConnectionState::Disconnected => colors.status_disconnected,
                }),
            ],
            Space::new().height(4),
            row![
                text("Versions Loaded:").size(13),
                Space::new().width(Length::Fill),
                text(format!("{}", state.versions.len())).size(13),
            ],
            Space::new().height(4),
            // Offline mode status indicator
            {
                use continuum_studio_iced::offline::OfflineState;
                let offline_state = {
                    // Compute offline state from current connection bools
                    let core_connected =
                        matches!(state.connection_state, ConnectionState::Connected);
                    let dialog_connected = state.dialog_daemon_connected;
                    let task_connected = state.task_queue_connected;
                    if core_connected && dialog_connected && task_connected {
                        OfflineState::Online
                    } else if !core_connected && !dialog_connected && !task_connected {
                        OfflineState::FullyOffline
                    } else {
                        OfflineState::PartiallyOffline
                    }
                };
                let queued_count = state.offline_queue.pending_count();
                row![
                    text("Offline Queue:").size(13),
                    Space::new().width(Length::Fill),
                    text(match offline_state {
                        OfflineState::Online if queued_count == 0 => "Online".to_string(),
                        OfflineState::Online => format!("{} pending sync", queued_count),
                        OfflineState::PartiallyOffline =>
                            format!("Partial ({} queued)", queued_count),
                        OfflineState::FullyOffline => format!("Offline ({} queued)", queued_count),
                    })
                    .size(13)
                    .color(match offline_state {
                        OfflineState::Online => colors.status_connected,
                        OfflineState::PartiallyOffline => colors.status_connecting,
                        OfflineState::FullyOffline => colors.status_disconnected,
                    }),
                ]
            },
        ]
        .spacing(4),
    );

    // Quick actions card
    let actions_card = card(
        column![
            text("Quick Actions").size(14).color(colors.text_secondary),
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

    // Task Queue card
    let task_queue_card = view_task_queue_card(state);

    column![
        text("Dashboard").size(26),
        text("Welcome back to Continuum Studio")
            .size(14)
            .color(colors.text_secondary),
        Space::new().height(24),
        row![status_card, Space::new().width(16), actions_card,],
        Space::new().height(16),
        task_queue_card,
    ]
    .spacing(8)
    .into()
}

/// Task Queue card for dashboard
fn view_task_queue_card(state: &ContinuumStudio) -> Element<'_, Message> {
    use continuum_studio_iced::task_queue_client::TaskStatus;

    let colors = &state.colors;
    let success = colors.success;
    let text_primary = colors.text_primary;
    let text_muted = colors.text_muted;
    let hover = colors.hover;
    let accent = colors.accent;

    // Header with connection status and pop-out button
    let header = row![
        text("📋 Task Queue").size(14).color(colors.text_secondary),
        Space::new().width(Length::Fill),
        // Pop-out button
        button(text("↗").size(12).color(colors.text_secondary))
            .on_press(Message::PopOutTaskQueue)
            .padding([2, 6])
            .style(move |_theme, status| {
                let bg = match status {
                    button::Status::Hovered => hover,
                    _ => iced::Color::TRANSPARENT,
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: colors.text_secondary,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            }),
        text(if state.task_queue_connected {
            "●"
        } else {
            "○"
        })
        .size(10)
        .color(if state.task_queue_connected {
            success
        } else {
            text_muted
        }),
        text(format!("{} pending", state.task_queue_stats.pending))
            .size(11)
            .color(colors.text_secondary),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    // Current task section
    let current_section: Element<'_, Message> = if let Some(task) = &state.task_queue_current {
        use continuum_studio_iced::Creator;

        let creator_text: String = match task.created_by {
            Creator::User => "👤 user".to_string(),
            Creator::Agent | Creator::Cli => task
                .agent_id
                .as_ref()
                .map(|id| format!("🤖 {}", id))
                .unwrap_or_else(|| {
                    if task.created_by == Creator::Cli {
                        "🤖 cli".to_string()
                    } else {
                        "🤖 agent".to_string()
                    }
                }),
            Creator::Unknown => "❓ unknown".to_string(),
        };
        let content_text = task.content.clone();

        // Subtask progress for dashboard
        let subtasks: Vec<_> = state
            .task_queue_tasks
            .iter()
            .filter(|t| t.parent_id.as_deref() == Some(task.id.as_str()))
            .collect();
        let total_subtasks = subtasks.len();
        let completed_subtasks = subtasks
            .iter()
            .filter(|t| t.status == TaskStatus::Completed)
            .count();

        let progress_elem: Element<'_, Message> = if total_subtasks > 0 {
            row![
                text(format!("{}/{}", completed_subtasks, total_subtasks))
                    .size(10)
                    .color(success),
                text("subtasks").size(10).color(colors.text_muted),
            ]
            .spacing(4)
            .align_y(Alignment::Center)
            .into()
        } else {
            Element::from(Space::new().height(0))
        };

        column![
            row![
                text("▶").size(12).color(accent),
                text(content_text).size(13).color(colors.text_primary),
            ]
            .spacing(6),
            row![
                text(task.priority.emoji()).size(11),
                text(task.priority.label())
                    .size(11)
                    .color(colors.text_secondary),
                text("·").size(11).color(colors.text_secondary),
                text(creator_text).size(11).color(colors.text_secondary),
            ]
            .spacing(4),
            progress_elem,
            button(text("Complete ✓").size(11))
                .on_press(Message::TaskQueueAction(TaskQueueMsg::CompleteCurrentTask))
                .padding([4, 8])
                .style(move |_theme, status| {
                    let bg = match status {
                        button::Status::Hovered => iced::Color {
                            r: (success.r * 1.2).min(1.0),
                            g: (success.g * 1.2).min(1.0),
                            b: (success.b * 1.2).min(1.0),
                            a: 1.0,
                        },
                        _ => success,
                    };
                    button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: text_primary,
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }
                }),
        ]
        .spacing(6)
        .into()
    } else {
        text("No current task")
            .size(12)
            .color(colors.text_muted)
            .into()
    };

    // Pending tasks list (max 5)
    let pending_tasks: Vec<_> = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Pending || t.status == TaskStatus::Claimed)
        .take(5)
        .collect();

    let pending_section: Element<'_, Message> = if pending_tasks.is_empty() {
        text("No pending tasks 🎉")
            .size(12)
            .color(colors.text_secondary)
            .into()
    } else {
        let items: Vec<Element<'_, Message>> = pending_tasks
            .iter()
            .map(|task| {
                let task_id = task.id.clone();

                row![
                    // Priority emoji
                    text(task.priority.emoji()).size(11),
                    // Task content
                    button(
                        text(truncate(&task.content, 40))
                            .size(12)
                            .color(colors.text_primary)
                    )
                    .on_press(Message::TaskQueueAction(TaskQueueMsg::StartTask(task_id)))
                    .padding([4, 6])
                    .width(Length::Fill)
                    .style(move |_theme, status| {
                        let bg = match status {
                            button::Status::Hovered => hover,
                            _ => iced::Color::TRANSPARENT,
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: text_primary,
                            border: iced::Border {
                                radius: 3.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
                ]
                .spacing(6)
                .align_y(Alignment::Center)
                .into()
            })
            .collect();

        column(items).spacing(4).into()
    };

    // View All button - switch to task queue view
    let view_all_btn = button(
        row![text("View All Tasks →")
            .size(11)
            .color(colors.text_secondary),]
        .align_y(Alignment::Center),
    )
    .on_press(Message::PopOutTaskQueue)
    .padding([6, 10])
    .width(Length::Fill)
    .style(move |_theme, status| {
        let bg = match status {
            button::Status::Hovered => hover,
            _ => iced::Color::TRANSPARENT,
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: text_primary,
            border: iced::Border {
                radius: 4.0.into(),
                width: 1.0,
                color: iced::Color {
                    r: hover.r,
                    g: hover.g,
                    b: hover.b,
                    a: 0.5,
                },
            },
            ..Default::default()
        }
    });

    // Stats footer
    let stats = &state.task_queue_stats;
    let stats_footer = row![
        text(format!("📊 {} total", stats.total))
            .size(10)
            .color(colors.text_secondary),
        text("·").size(10).color(colors.text_secondary),
        text(format!("{} in progress", stats.in_progress))
            .size(10)
            .color(colors.text_secondary),
        text("·").size(10).color(colors.text_secondary),
        text(format!("{} completed", stats.completed))
            .size(10)
            .color(colors.text_secondary),
    ]
    .spacing(4);

    // Build the card
    container(
        column![
            header,
            Space::new().height(8),
            current_section,
            Space::new().height(12),
            text("PENDING").size(10).color(colors.text_secondary),
            Space::new().height(4),
            pending_section,
            Space::new().height(12),
            view_all_btn,
            Space::new().height(8),
            stats_footer,
        ]
        .spacing(0)
        .padding(16),
    )
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
        shadow: iced::Shadow {
            color: iced::Color::from_rgba(0.0, 0.0, 0.0, 0.2),
            offset: iced::Vector::new(0.0, 2.0),
            blur_radius: 8.0,
        },
        ..container::Style::default()
    })
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
fn view_cursor(state: &ContinuumStudio) -> Element<'_, Message> {
    let current_tab = state.cursor_tab;

    // Tab bar
    let tab_button = |label: &'static str, tab: CursorTab| -> Element<Message> {
        let is_active = tab == current_tab;
        button(text(label).size(13).color(if is_active {
            iced::Color::WHITE
        } else {
            iced::Color::from_rgb(0.7, 0.7, 0.7)
        }))
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
        tab_button("⚡ CLI Agents", CursorTab::CLIAgents),
        tab_button("🎛️ Orchestrator", CursorTab::Orchestrator),
        tab_button("🅿️ Parked", CursorTab::ParkedAgents),
        tab_button("📋 Activity", CursorTab::AgentActivity),
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
        CursorTab::CLIAgents => view_cli_agents(state),
        CursorTab::Orchestrator => view_orchestrator(state),
        CursorTab::ParkedAgents => view_parked_agents(state),
        CursorTab::AgentActivity => view_agent_activity(state),
        CursorTab::Auth => view_auth(state),
        CursorTab::Versions => view_cursor_versions(state),
        CursorTab::Workspaces => view_workspaces(state),
    };

    column![header, Space::new().height(16), tabs, tab_content,]
        .spacing(8)
        .into()
}

/// Cursor version management view with improved organization
fn view_cursor_versions(state: &ContinuumStudio) -> Element<'_, Message> {
    // Count versions by category
    let total = state.versions.len();
    let installed_count = state.versions.iter().filter(|v| v.installed).count();
    let supported_count = state
        .versions
        .iter()
        .filter(|v| is_supported_version(&v.version))
        .count();

    // Group versions by major.minor era
    let mut era_groups: Vec<(&str, Vec<&CursorVersion>)> = Vec::new();
    let mut current_era = String::new();
    let mut current_group: Vec<&CursorVersion> = Vec::new();

    for v in &state.versions {
        let era = get_version_era(&v.version);
        if era != current_era {
            if !current_group.is_empty() {
                era_groups.push((
                    Box::leak(current_era.clone().into_boxed_str()),
                    current_group,
                ));
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
                    .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
            )
            .padding([2, 6])
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgba(
                    0.3, 0.7, 0.4, 0.15,
                ))),
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
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .padding([2, 6])
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgba(
                    0.5, 0.5, 0.5, 0.15,
                ))),
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
            background: Some(iced::Background::Color(if is_supported {
                iced::Color::from_rgb(0.12, 0.15, 0.12)
            } else {
                iced::Color::from_rgb(0.1, 0.1, 0.1)
            })),
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
    let quick_jump = container(row![
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
    ])
    .padding([8, 16]);

    scrollable(column![
        header_card,
        Space::new().height(12),
        quick_jump,
        Space::new().height(12),
        version_list,
    ])
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

    log::info!(
        "Applied KWin border overlay for {} instances",
        sessions.len()
    );
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
                text("CPU")
                    .size(9)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{:.1}%", m.cpu_percent))
                    .size(14)
                    .color(cpu_color),
            ]
            .width(Length::FillPortion(1)),
            column![
                text("Memory")
                    .size(9)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(m.memory_human.clone())
                    .size(14)
                    .color(iced::Color::from_rgb(0.6, 0.4, 0.9)),
            ]
            .width(Length::FillPortion(1)),
            column![
                text("Threads")
                    .size(9)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{}", m.thread_count))
                    .size(14)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .width(Length::FillPortion(1)),
            column![
                text("FDs")
                    .size(9)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(format!("{}", m.fd_count))
                    .size(14)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .width(Length::FillPortion(1)),
            column![
                text("State")
                    .size(9)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                text(state_text).size(12).color(state_color),
            ]
            .width(Length::FillPortion(1)),
        ]
        .into()
    } else {
        row![text("No metrics yet - click 'Refresh Metrics'")
            .size(11)
            .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),]
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
fn version_row_from_data<'a>(
    version: &'a CursorVersion,
    auth_status: Option<&'a AuthStatus>,
    has_profiles: bool,
) -> Element<'a, Message> {
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
                            text(email_short)
                                .size(10)
                                .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
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
                                text("—")
                                    .size(11)
                                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                                tiny_button("📥").on_press(Message::CursorAction(
                                    CursorMessage::ApplyAuthFromLatest(v4)
                                )),
                            ]
                            .spacing(4)
                            .align_y(Alignment::Center)
                            .into()
                        } else {
                            text("Not logged in")
                                .size(11)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                                .into()
                        }
                    }
                    AuthState::Stale => row![
                        text("⚠").size(11),
                        text("Stale")
                            .size(11)
                            .color(iced::Color::from_rgb(0.8, 0.6, 0.2)),
                    ]
                    .spacing(4)
                    .align_y(Alignment::Center)
                    .into(),
                    AuthState::Unknown => text("?")
                        .size(11)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                        .into(),
                }
            }
            None => {
                // Auth status not loaded yet
                text("...")
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4))
                    .into()
            }
        }
    } else {
        // Not installed, no auth display
        text("-")
            .size(11)
            .color(iced::Color::from_rgb(0.3, 0.3, 0.3))
            .into()
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
fn view_workspaces(state: &ContinuumStudio) -> Element<'_, Message> {
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
            styled_button("Refresh", false).on_press(Message::WorkspaceAction(
                WorkspaceMessage::RefreshWorkspaces
            )),
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
            .on_press(Message::WorkspaceAction(
                WorkspaceMessage::ScanWorkspaceFiles,
            ))
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
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
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

    let ws_files_card = container(column![ws_files_header, ws_files_list,])
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
            small_button("Open", true).on_press(Message::WorkspaceAction(
                WorkspaceMessage::OpenWorkspaceFile(path)
            )),
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
fn workspace_row(workspace: &Workspace) -> Element<'_, Message> {
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
                small_button("Open", true).on_press(Message::WorkspaceAction(
                    WorkspaceMessage::OpenInCursor(id2, "latest".to_string(),)
                ))
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
fn view_auth(state: &ContinuumStudio) -> Element<'_, Message> {
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
                    border: iced::Border {
                        radius: 6.0.into(),
                        width: 0.0,
                        color: iced::Color::TRANSPARENT,
                    },
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
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(12)
        .into()
    } else {
        let profile_rows: Vec<Element<Message>> = state
            .auth_profiles
            .iter()
            .map(|profile| {
                let email = profile.email.as_deref().unwrap_or("Unknown");
                let provider = profile.provider.as_deref().unwrap_or("Unknown");
                let source = profile.extracted_from.as_deref().unwrap_or("Unknown");
                let membership = if profile.membership.is_empty() {
                    "free"
                } else {
                    &profile.membership
                };

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
                        text("✓")
                            .size(14)
                            .color(iced::Color::from_rgb(0.3, 0.7, 0.4)),
                    ]
                    .spacing(8)
                    .align_y(Alignment::Center),
                )
                .padding(10)
                .style(|_theme| container::Style {
                    background: Some(iced::Background::Color(iced::Color::from_rgb(
                        0.15, 0.15, 0.15,
                    ))),
                    border: iced::Border {
                        radius: 6.0.into(),
                        width: 1.0,
                        color: iced::Color::from_rgb(0.25, 0.25, 0.25),
                    },
                    ..container::Style::default()
                })
                .into()
            })
            .collect();

        column(profile_rows).spacing(6).into()
    };

    let profiles_section =
        container(column![profiles_header, Space::new().height(8), profiles_list,].spacing(4))
            .padding(16)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.12,
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.2),
                },
                ..container::Style::default()
            });

    // Version Auth Status section
    let versions_header = text("📋 Version Auth Status").size(16);

    // Get installed versions with their auth status
    let installed_versions: Vec<&CursorVersion> =
        state.versions.iter().filter(|v| v.installed).collect();

    let version_rows: Vec<Element<Message>> = installed_versions
        .iter()
        .map(|version| {
            let auth_status = state.auth_statuses.get(&version.version);
            let version_str = version.version.clone();
            let version_str2 = version.version.clone();

            let (status_icon, status_text, status_color, can_extract, can_apply) = match auth_status
            {
                Some(status) => match status.status {
                    AuthState::Authenticated => {
                        let email = status.email.as_deref().unwrap_or("Logged in");
                        (
                            "🔑",
                            email.to_string(),
                            iced::Color::from_rgb(0.3, 0.7, 0.4),
                            true,
                            false,
                        )
                    }
                    AuthState::NotLoggedIn => (
                        "—",
                        "Not logged in".to_string(),
                        iced::Color::from_rgb(0.5, 0.5, 0.5),
                        false,
                        true,
                    ),
                    AuthState::Stale => (
                        "⚠️",
                        "Auth may be stale".to_string(),
                        iced::Color::from_rgb(0.8, 0.6, 0.2),
                        true,
                        true,
                    ),
                    AuthState::Unknown => (
                        "?",
                        "Unknown".to_string(),
                        iced::Color::from_rgb(0.4, 0.4, 0.4),
                        false,
                        false,
                    ),
                },
                None => (
                    "?",
                    "Not scanned".to_string(),
                    iced::Color::from_rgb(0.4, 0.4, 0.4),
                    false,
                    false,
                ),
            };

            // Action buttons
            let extract_btn: Element<Message> = if can_extract {
                button(text("📤 Extract").size(11))
                    .padding([4, 8])
                    .on_press(Message::AuthAction(AuthMessage::ExtractFromVersion(
                        version_str,
                    )))
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
                            border: iced::Border {
                                radius: 4.0.into(),
                                width: 0.0,
                                color: iced::Color::TRANSPARENT,
                            },
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
                        target_version: version_str2,
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
                            border: iced::Border {
                                radius: 4.0.into(),
                                width: 0.0,
                                color: iced::Color::TRANSPARENT,
                            },
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
                .align_y(Alignment::Center),
            )
            .padding(10)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.15, 0.15, 0.15,
                ))),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.25, 0.25, 0.25),
                },
                ..container::Style::default()
            })
            .into()
        })
        .collect();

    let versions_list: Element<Message> = if version_rows.is_empty() {
        container(
            text("No installed versions found.")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(12)
        .into()
    } else {
        scrollable(column(version_rows).spacing(6))
            .height(Length::Fill)
            .into()
    };

    let versions_section = container(
        column![versions_header, Space::new().height(8), versions_list,]
            .spacing(4)
            .height(Length::Fill),
    )
    .padding(16)
    .height(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.12,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.2),
        },
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
        .height(Length::Fill),
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .into()
}

/// Sessions view with detected running Cursor instances and real-time monitoring
fn view_sessions(state: &ContinuumStudio) -> Element<'_, Message> {
    profile_span!("view_sessions");
    // Header card with stats and controls
    let header_card = container(
        row![
            column![
                text("Session Monitor").size(20),
                text(format!(
                    "{} running Cursor instances",
                    state.cursor_sessions.len()
                ))
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button(
                if state.border_overlay_active {
                    "Borders: ON"
                } else {
                    "Borders: OFF"
                },
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
            container(column![
                text("Health")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(8),
                row![
                    container(
                        text(format!("{}", dash.healthy_count))
                            .size(24)
                            .color(iced::Color::from_rgb(0.3, 0.8, 0.4))
                    )
                    .padding([4, 8]),
                    text("healthy")
                        .size(11)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .align_y(Alignment::End),
                text(if dash.warning_count > 0 && dash.critical_count > 0 {
                    format!("{} warn, {} crit", dash.warning_count, dash.critical_count)
                } else if dash.warning_count > 0 {
                    format!("{} warning", dash.warning_count)
                } else if dash.critical_count > 0 {
                    format!("{} critical", dash.critical_count)
                } else {
                    String::new()
                })
                .size(10)
                .color(if dash.critical_count > 0 {
                    iced::Color::from_rgb(0.9, 0.3, 0.3)
                } else if dash.warning_count > 0 {
                    iced::Color::from_rgb(0.9, 0.7, 0.2)
                } else {
                    iced::Color::from_rgb(0.5, 0.5, 0.5)
                }),
            ],)
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.14, 0.12
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.25, 0.2)
                },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // CPU card
            container(column![
                text("CPU")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(8),
                row![text(format!("{:.1}%", dash.total_cpu)).size(24).color(
                    if dash.total_cpu > 80.0 {
                        iced::Color::from_rgb(0.9, 0.3, 0.3)
                    } else if dash.total_cpu > 40.0 {
                        iced::Color::from_rgb(0.9, 0.7, 0.2)
                    } else {
                        iced::Color::from_rgb(0.4, 0.6, 1.0)
                    }
                ),],
                text("total usage")
                    .size(10)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ],)
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.14
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.25)
                },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // Memory card
            container(column![
                text("Memory")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(8),
                row![text(format!("{:.0} MB", dash.total_memory_mb))
                    .size(24)
                    .color(if dash.total_memory_mb > 8000.0 {
                        iced::Color::from_rgb(0.9, 0.3, 0.3)
                    } else if dash.total_memory_mb > 4000.0 {
                        iced::Color::from_rgb(0.9, 0.7, 0.2)
                    } else {
                        iced::Color::from_rgb(0.6, 0.4, 0.9)
                    }),],
                text("total allocated")
                    .size(10)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ],)
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.13, 0.12, 0.14
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.22, 0.2, 0.25)
                },
                ..container::Style::default()
            }),
            Space::new().width(12),
            // Threads/FDs card
            container(column![
                text("Resources")
                    .size(11)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(8),
                row![
                    text(format!("{}", dash.total_threads))
                        .size(20)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    text(" threads")
                        .size(10)
                        .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                ]
                .align_y(Alignment::End),
                row![
                    text(format!("{}", dash.total_fds))
                        .size(14)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                    text(" file descriptors")
                        .size(10)
                        .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                ]
                .align_y(Alignment::End),
            ],)
            .padding(16)
            .width(Length::FillPortion(1))
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.12
                ))),
                border: iced::Border {
                    radius: 8.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.18, 0.18, 0.18)
                },
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
                            container(Space::new().width(8).height(8)).style(move |_theme| {
                                container::Style {
                                    background: Some(iced::Background::Color(instance_color)),
                                    border: iced::Border {
                                        radius: 4.0.into(),
                                        ..Default::default()
                                    },
                                    ..container::Style::default()
                                }
                            }),
                            Space::new().width(8),
                            text(format!("PID {}", session.pid))
                                .size(12)
                                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                            Space::new().width(12),
                            text(format!("Cursor {}", version_text)).size(14),
                            Space::new().width(Length::Fill),
                            text(if session.active { " Active " } else { "" })
                                .size(10)
                                .color(iced::Color::from_rgb(0.3, 0.8, 0.3)),
                            container(text(health_text).size(10).color(health_color))
                                .padding([4, 8])
                                .style(move |_theme| container::Style {
                                    background: Some(iced::Background::Color(
                                        iced::Color::from_rgba(
                                            health_color.r,
                                            health_color.g,
                                            health_color.b,
                                            0.15
                                        )
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
                        text(if !window_title.is_empty() {
                            window_title
                        } else {
                            workspace_text
                        })
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
                        row![styled_button("Flash Identify", false).on_press(
                            Message::SessionAction(SessionMessage::FlashIdentify(session.pid))
                        ),],
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

    scrollable(column![
        header_card,
        Space::new().height(12),
        dashboard_row,
        Space::new().height(16),
        content,
    ])
    .into()
}

/// Services view - manage backend services
fn view_services(state: &ContinuumStudio) -> Element<'_, Message> {
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

            let status_indicator =
                container(text(""))
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

            let can_start = matches!(
                service.status,
                ServiceStatus::Stopped | ServiceStatus::Failed
            );

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
                            container(styled_button("Start", true).on_press(
                                Message::ServiceAction(ServiceMessage::StartService(
                                    service.name.clone(),
                                )),
                            ))
                        } else {
                            container(styled_button("Running", false))
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
                            small_button("Copy", false).on_press(Message::ServiceAction(
                                ServiceMessage::CopyCommand(service.start_command.clone(),)
                            )),
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

    scrollable(column![
        header_card,
        Space::new().height(16),
        services_list,
        Space::new().height(16),
        quick_commands,
    ])
    .into()
}

/// Helper for command rows in quick commands section
fn command_row<'a>(label: &'a str, command: &'a str) -> Element<'a, Message> {
    dual_command_row(label, command, command)
}

/// Helper for command rows with both bash and nushell variants
fn dual_command_row<'a>(
    label: &'a str,
    bash_cmd: &'a str,
    nu_cmd: &'a str,
) -> Element<'a, Message> {
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
                small_button("Copy", false).on_press(Message::ServiceAction(
                    ServiceMessage::CopyCommand(bash_cmd_owned,)
                )),
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
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.1, 0.2, 0.1
                        ))),
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
                    small_button("Copy", false).on_press(Message::ServiceAction(
                        ServiceMessage::CopyCommand(bash_cmd_owned,)
                    )),
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
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.1, 0.15, 0.25
                        ))),
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
                    small_button("Copy", false).on_press(Message::ServiceAction(
                        ServiceMessage::CopyCommand(nu_cmd_owned,)
                    )),
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
fn view_storage(state: &ContinuumStudio) -> Element<'_, Message> {
    let install_type = InstallationType::detect();

    // Header card
    let total_disk: u64 = state.storage_disk_usage.iter().map(|v| v.total_size).sum();
    let selected_count = state.storage_selected.len();
    let selected_size: u64 = state
        .storage_disk_usage
        .iter()
        .filter(|v| state.storage_selected.contains(&v.version))
        .map(|v| v.total_size)
        .sum();

    let header_card = container(column![
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
                text(format!("Total: {}", format_bytes(total_disk))).size(16),
                text(format!(
                    "{} versions installed",
                    state.storage_disk_usage.len()
                ))
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
                text(format!(
                    "{} selected ({})",
                    selected_count,
                    format_bytes(selected_size)
                ))
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
    ])
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

    // Version table with disk usage breakdown
    let version_rows: Vec<Element<Message>> = if state.storage_loading {
        vec![container(
            text("Scanning disk usage...")
                .size(14)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(20)
        .into()]
    } else if state.storage_disk_usage.is_empty() {
        // Show basic list from state.versions if disk usage not yet scanned
        let installed: Vec<&CursorVersion> = state
            .versions
            .iter()
            .filter(|v| v.status == VersionStatus::Installed || v.status == VersionStatus::Running)
            .collect();

        if installed.is_empty() {
            vec![container(column![
                text("No installed Cursor versions found.").size(14),
                Space::new().height(4),
                text("Install versions from the Versions tab, then click 'Scan Disk Usage'.")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ])
            .padding(20)
            .into()]
        } else {
            vec![container(column![
                text(format!("{} installed versions detected.", installed.len())).size(14),
                Space::new().height(4),
                text("Click 'Scan Disk Usage' to see detailed size breakdown per version.")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ])
            .padding(20)
            .into()]
        }
    } else {
        // Table header
        let header_row: Element<Message> = container(
            row![
                Space::new().width(30), // checkbox column
                text("Version")
                    .size(11)
                    .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                Space::new().width(Length::Fill),
                container(
                    text("AppImage")
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                )
                .width(80),
                container(
                    text("Data")
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                )
                .width(80),
                container(
                    text("Extensions")
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                )
                .width(80),
                container(
                    text("Total")
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                )
                .width(80),
                container(
                    text("Last Used")
                        .size(11)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                )
                .width(120),
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
                    .font(iced::Font::MONOSPACE),
            )
            .padding([2, 6])
            .on_press_maybe(if usage.is_running {
                None
            } else {
                Some(Message::StorageAction(StorageMessage::ToggleVersionSelect(
                    version_clone,
                )))
            })
            .style(move |_theme, _status| button::Style {
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
            });

            let version_text = text(format!("Cursor {}", usage.version)).size(13);

            let status_indicator = if usage.is_running {
                text("RUN")
                    .size(10)
                    .color(iced::Color::from_rgb(0.3, 0.8, 0.4))
            } else {
                text("").size(10)
            };

            let last_used_text = text(usage.last_used.as_deref().unwrap_or("Never"))
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
                        text(if usage.has_data_dir {
                            format_bytes(usage.data_dir_size)
                        } else {
                            "-".to_string()
                        })
                        .size(11)
                    )
                    .width(80),
                    container(
                        text(if usage.has_extensions {
                            format_bytes(usage.extensions_size)
                        } else {
                            "-".to_string()
                        })
                        .size(11)
                    )
                    .width(80),
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
        container(column![
            text("Cleanup Options")
                .size(14)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            row![
                styled_button("Remove AppImage Only", false).on_press(Message::StorageAction(
                    StorageMessage::CleanupSelected(CleanupMode::AppImageOnly)
                )),
                Space::new().width(8),
                styled_button("Remove AppImage + Data", false).on_press(Message::StorageAction(
                    StorageMessage::CleanupSelected(CleanupMode::AppImageAndData)
                )),
                Space::new().width(8),
                styled_button("Remove All (Keep Auth)", false).on_press(Message::StorageAction(
                    StorageMessage::CleanupSelected(CleanupMode::KeepAuth)
                )),
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
        ])
        .padding(20)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.18, 0.14, 0.12,
            ))),
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

        container(column![
            text("Continuum Studio Builds")
                .size(14)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            row![
                text("Nightly:").size(13),
                Space::new().width(8),
                text(if nightly_exists {
                    "Available"
                } else {
                    "Not built"
                })
                .size(13)
                .color(if nightly_exists {
                    iced::Color::from_rgb(0.3, 0.7, 0.4)
                } else {
                    iced::Color::from_rgb(0.5, 0.5, 0.5)
                }),
                Space::new().width(24),
                text("Stable:").size(13),
                Space::new().width(8),
                text(if stable_exists {
                    "Available"
                } else {
                    "Not built"
                })
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
        ])
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
    };

    // Assemble the full view
    let mut content = column![header_card, Space::new().height(12),];

    // Add version rows
    for row in version_rows {
        content = content.push(row);
    }

    content = content
        .push(Space::new().height(12))
        .push(cleanup_card)
        .push(Space::new().height(12))
        .push(builds_section);

    scrollable(container(content).width(Length::Fill).padding(10))
        .height(Length::Fill)
        .into()
}

/// Logs view for in-app debugging
fn view_logs(state: &ContinuumStudio) -> Element<'_, Message> {
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
        text("Filter:")
            .size(12)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().width(12),
        log_filter_button("Error", log::Level::Error, state.log_filter),
        log_filter_button("Warn", log::Level::Warn, state.log_filter),
        log_filter_button("Info", log::Level::Info, state.log_filter),
        log_filter_button("Debug", log::Level::Debug, state.log_filter),
        log_filter_button("Trace", log::Level::Trace, state.log_filter),
        Space::new().width(Length::Fill),
        styled_button("Copy All", false).on_press(Message::LogAction(LogMessage::CopyLogs)),
        Space::new().width(8),
        styled_button("Clear", false).on_press(Message::LogAction(LogMessage::ClearLogs)),
    ]
    .spacing(4)
    .align_y(Alignment::Center);

    // Header card with controls
    let header_card = container(column![
        row![column![
            text("Logs").size(20),
            text(format!("{} entries", entries.len()))
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4),],
        Space::new().height(12),
        filter_row,
    ])
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

    column![header_card, Space::new().height(16), log_container,]
        .spacing(0)
        .into()
}

/// Filter button for log level selection
fn log_filter_button(
    label: &'static str,
    level: log::Level,
    current: log::Level,
) -> Element<'static, Message> {
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
fn view_settings(state: &ContinuumStudio) -> Element<'_, Message> {
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
        text(format!(
            "Current: v{}",
            state.settings.updates.current_version
        ))
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
                    channel_pill(
                        "Stable",
                        state.settings.updates.channel == UpdateChannel::Stable,
                        UpdateChannel::Stable
                    ),
                    channel_pill(
                        "Beta",
                        state.settings.updates.channel == UpdateChannel::Beta,
                        UpdateChannel::Beta
                    ),
                    channel_pill(
                        "Nightly",
                        state.settings.updates.channel == UpdateChannel::Nightly,
                        UpdateChannel::Nightly
                    ),
                ]
                .spacing(6),
            ),
            Space::new().height(8),
            settings_row(
                "Release source",
                row![
                    forge_pill(
                        "Local",
                        state.settings.updates.forge_type == ForgeType::Local,
                        ForgeType::Local
                    ),
                    forge_pill(
                        "GitHub",
                        state.settings.updates.forge_type == ForgeType::GitHub,
                        ForgeType::GitHub
                    ),
                    forge_pill(
                        "Forgejo",
                        state.settings.updates.forge_type == ForgeType::Forgejo,
                        ForgeType::Forgejo
                    ),
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
            text(format!(
                "Managed workspaces: {}",
                state.settings.managed_workspaces.len()
            ))
            .size(11)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4),
    );

    // Zone Layout card for window arrangement
    use continuum_studio_iced::zones::ZoneLayout;
    let current_zone = state.zone_manager.layout();
    let zone_card = settings_card(
        "Window Zones",
        column![
            settings_row(
                "Zone Layout",
                row![
                    zone_pill(
                        "Main+Panel",
                        current_zone == ZoneLayout::MainWithSidePanel,
                        ZoneLayout::MainWithSidePanel
                    ),
                    zone_pill(
                        "Split H",
                        current_zone == ZoneLayout::SplitHorizontal,
                        ZoneLayout::SplitHorizontal
                    ),
                    zone_pill(
                        "Split V",
                        current_zone == ZoneLayout::SplitVertical,
                        ZoneLayout::SplitVertical
                    ),
                    zone_pill(
                        "3-Col",
                        current_zone == ZoneLayout::ThreeColumn,
                        ZoneLayout::ThreeColumn
                    ),
                    zone_pill(
                        "Free",
                        current_zone == ZoneLayout::FreeForm,
                        ZoneLayout::FreeForm
                    ),
                ]
                .spacing(4)
            ),
            Space::new().height(8),
            settings_row(
                "Apply Layout",
                styled_button("Apply Now", false)
                    .on_press(Message::ZoneAction(ZoneMsg::ApplyLayout))
            ),
            Space::new().height(4),
            text("Arranges windows according to selected zone layout")
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
        Space::new().height(12),
        zone_card,
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
        .on_press(Message::SettingsAction(SettingsMessage::SetUpdateChannel(
            channel,
        )))
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
        .on_press(Message::SettingsAction(SettingsMessage::SetForgeType(
            forge_type,
        )))
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

/// Zone layout pill button
fn zone_pill(
    label: &'static str,
    is_active: bool,
    layout: continuum_studio_iced::zones::ZoneLayout,
) -> Element<'static, Message> {
    button(text(label).size(10))
        .padding([4, 8])
        .on_press(Message::ZoneAction(ZoneMsg::SetLayout(layout)))
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

fn handle_chat_pipeline_message(
    state: &mut ContinuumStudio,
    msg: ChatPipelineMsg,
) -> Task<Message> {
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
                            |result| {
                                Message::ChatPipelineAction(ChatPipelineMsg::LocationsLoaded(
                                    result,
                                ))
                            },
                        );
                    }
                }
                ChatSubView::Topics => {
                    state.chat_pipeline.loading = true;
                    return Task::perform(
                        async { ChatApiClient::fetch_topics().await },
                        |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::TopicsLoaded(
                                result.map(|t| t.topics),
                            ))
                        },
                    );
                }
                ChatSubView::Conversations => {
                    state.chat_pipeline.loading = true;
                    return Task::perform(
                        async { ChatApiClient::fetch_conversations(100).await },
                        |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::ConversationsLoaded(
                                result.map(|c| c.conversations),
                            ))
                        },
                    );
                }
                ChatSubView::Watcher => {
                    state.chat_pipeline.loading = true;
                    return Task::perform(
                        async { ChatApiClient::fetch_watcher_status().await },
                        |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::WatcherStatusLoaded(
                                result,
                            ))
                        },
                    );
                }
                _ => {}
            }
        }
        ChatPipelineMsg::RefreshAll => {
            state.chat_pipeline.loading = true;
            state.chat_pipeline.error = None;
            // Fire off parallel fetches
            let stats_task =
                Task::perform(async { ChatApiClient::fetch_stats().await }, |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result))
                });
            let health_task =
                Task::perform(async { ChatApiClient::fetch_health().await }, |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::HealthChecked(
                        result.map(|h| h.status == "ok"),
                    ))
                });
            let convos_task = Task::perform(
                async { ChatApiClient::fetch_conversations(100).await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::ConversationsLoaded(
                        result.map(|c| c.conversations),
                    ))
                },
            );
            return Task::batch([stats_task, health_task, convos_task]);
        }
        ChatPipelineMsg::HealthChecked(result) => match result {
            Ok(ok) => {
                state.chat_pipeline.api_available = ok;
                state.chat_pipeline.loading = false;
            }
            Err(e) => {
                state.chat_pipeline.api_available = false;
                state.chat_pipeline.error = Some(format!("API unavailable: {}", e));
                state.chat_pipeline.loading = false;
            }
        },
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
                    state.chat_pipeline.error =
                        Some(format!("Failed to load conversations: {}", e));
                }
            }
        }
        ChatPipelineMsg::ConversationLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(detail) => state.chat_pipeline.selected_conversation = Some(detail),
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to load conversation: {}", e));
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
                    |result| {
                        Message::ChatPipelineAction(ChatPipelineMsg::SearchCompleted(
                            result.map(|s| s.results),
                        ))
                    },
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
            return Task::perform(async { ChatApiClient::batch_ingest().await }, |result| {
                Message::ChatPipelineAction(ChatPipelineMsg::BatchIngestDone(result.map(|r| {
                    format!(
                        "Imported: {}, Summarized: {}, Topics: {}",
                        r.imported, r.summarize_queued, r.topics_created
                    )
                })))
            });
        }
        ChatPipelineMsg::DoSummarizePending => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::summarize_pending().await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::SummarizeDone(
                        result.map(|v| format!("{}", v)),
                    ))
                },
            );
        }
        ChatPipelineMsg::DoClusterTopics => {
            state.chat_pipeline.loading = true;
            return Task::perform(async { ChatApiClient::cluster_topics().await }, |result| {
                Message::ChatPipelineAction(ChatPipelineMsg::ClusterDone(
                    result.map(|v| format!("{}", v)),
                ))
            });
        }
        ChatPipelineMsg::DoImportOrphaned => {
            state.chat_pipeline.loading = true;
            return Task::perform(async { ChatApiClient::import_orphaned().await }, |result| {
                Message::ChatPipelineAction(ChatPipelineMsg::ImportOrphanedDone(
                    result.map(|v| format!("{}", v)),
                ))
            });
        }
        ChatPipelineMsg::BatchIngestDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => {
                    state.chat_pipeline.last_action_result = Some(format!("Batch ingest: {}", msg))
                }
                Err(e) => state.chat_pipeline.error = Some(format!("Batch ingest failed: {}", e)),
            }
            // Refresh stats
            return Task::perform(async { ChatApiClient::fetch_stats().await }, |result| {
                Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result))
            });
        }
        ChatPipelineMsg::SummarizeDone(result) | ChatPipelineMsg::ImportOrphanedDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => state.chat_pipeline.last_action_result = Some(msg),
                Err(e) => state.chat_pipeline.error = Some(e),
            }
            return Task::perform(async { ChatApiClient::fetch_stats().await }, |result| {
                Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result))
            });
        }
        ChatPipelineMsg::ClusterDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => state.chat_pipeline.last_action_result = Some(msg),
                Err(e) => state.chat_pipeline.error = Some(e),
            }
            // Refresh both stats and topics after clustering
            let stats_task =
                Task::perform(async { ChatApiClient::fetch_stats().await }, |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result))
                });
            let topics_task =
                Task::perform(async { ChatApiClient::fetch_topics().await }, |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::TopicsLoaded(
                        result.map(|t| t.topics),
                    ))
                });
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
            state.chat_pipeline.show_full_conversation =
                !state.chat_pipeline.show_full_conversation;
            if !state.chat_pipeline.show_full_conversation {
                state.chat_pipeline.expanded_messages.clear();
            }
        }
        ChatPipelineMsg::SetExportFormat(format) => {
            state.chat_pipeline.export_format = format;
        }
        ChatPipelineMsg::ToggleExportSelect(id) => {
            if state.chat_pipeline.export_selected.contains(&id) {
                state.chat_pipeline.export_selected.remove(&id);
            } else {
                state.chat_pipeline.export_selected.insert(id);
            }
        }
        ChatPipelineMsg::SelectAllForExport => {
            for conv in &state.chat_pipeline.conversations {
                state.chat_pipeline.export_selected.insert(conv.id.clone());
            }
        }
        ChatPipelineMsg::DeselectAllForExport => {
            state.chat_pipeline.export_selected.clear();
        }
        ChatPipelineMsg::ExportConversation(id) => {
            let format = state.chat_pipeline.export_format;
            state.chat_pipeline.exporting = true;
            state.chat_pipeline.export_result = None;
            return Task::perform(
                async move { ChatApiClient::export_conversation(id, format).await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::ExportCompleted(result)),
            );
        }
        ChatPipelineMsg::ExportSelected => {
            if let Some(first_id) = state.chat_pipeline.export_selected.iter().next().cloned() {
                let format = state.chat_pipeline.export_format;
                state.chat_pipeline.exporting = true;
                state.chat_pipeline.export_result = None;
                return Task::perform(
                    async move { ChatApiClient::export_conversation(first_id, format).await },
                    |result| Message::ChatPipelineAction(ChatPipelineMsg::ExportCompleted(result)),
                );
            }
        }
        ChatPipelineMsg::ExportCompleted(result) => {
            state.chat_pipeline.exporting = false;
            match result {
                Ok(export) => {
                    state.chat_pipeline.export_result = Some(export);
                    state.chat_pipeline.last_action_result = Some("Export completed".to_string());
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Export failed: {}", e));
                }
            }
        }
        ChatPipelineMsg::ClearExportResult => {
            state.chat_pipeline.export_result = None;
        }
        ChatPipelineMsg::SaveExportToFile => {
            if let Some(ref export) = state.chat_pipeline.export_result {
                let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
                let path = format!("{}/Downloads/{}", home, export.filename);
                match std::fs::write(&path, &export.content) {
                    Ok(_) => {
                        state.chat_pipeline.last_action_result = Some(format!("Saved to {}", path));
                    }
                    Err(e) => {
                        state.chat_pipeline.error = Some(format!("Save failed: {}", e));
                    }
                }
            }
        }
        ChatPipelineMsg::CopyExportToClipboard => {
            if let Some(ref export) = state.chat_pipeline.export_result {
                state.chat_pipeline.last_action_result = Some("Copied to clipboard".to_string());
                return iced::clipboard::write(export.content.clone());
            }
        }
        ChatPipelineMsg::SwitchExportMode(mode) => {
            state.chat_pipeline.training_export.mode = mode;
        }
        ChatPipelineMsg::WatcherStatusLoaded(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(status) => {
                    state.chat_pipeline.watcher_status = Some(status);
                }
                Err(e) => {
                    state.chat_pipeline.error =
                        Some(format!("Failed to load watcher status: {}", e));
                }
            }
        }
        ChatPipelineMsg::TriggerWatcherScan => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::trigger_watcher_scan().await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::WatcherScanDone(
                        result.map(|_| "Scan triggered".to_string()),
                    ))
                },
            );
        }
        ChatPipelineMsg::TriggerPipelineProcessAll => {
            state.chat_pipeline.loading = true;
            return Task::perform(
                async { ChatApiClient::trigger_pipeline_process_all().await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::PipelineProcessDone(
                        result.map(|_| "Processing started".to_string()),
                    ))
                },
            );
        }
        ChatPipelineMsg::WatcherScanDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => {
                    state.chat_pipeline.last_action_result = Some(msg);
                    // Refresh watcher status
                    return Task::perform(
                        async { ChatApiClient::fetch_watcher_status().await },
                        |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::WatcherStatusLoaded(
                                result,
                            ))
                        },
                    );
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Scan failed: {}", e));
                }
            }
        }
        ChatPipelineMsg::PipelineProcessDone(result) => {
            state.chat_pipeline.loading = false;
            match result {
                Ok(msg) => {
                    state.chat_pipeline.last_action_result = Some(msg);
                    // Refresh watcher status and stats
                    let watcher_task = Task::perform(
                        async { ChatApiClient::fetch_watcher_status().await },
                        |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::WatcherStatusLoaded(
                                result,
                            ))
                        },
                    );
                    let stats_task =
                        Task::perform(async { ChatApiClient::fetch_stats().await }, |result| {
                            Message::ChatPipelineAction(ChatPipelineMsg::StatsLoaded(result))
                        });
                    return Task::batch([watcher_task, stats_task]);
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Processing failed: {}", e));
                }
            }
        }
        // Training Export handlers
        ChatPipelineMsg::SetTrainingFormat(format) => {
            state.chat_pipeline.training_export.format = format;
        }
        ChatPipelineMsg::ToggleTrainingHasCode => {
            state.chat_pipeline.training_export.filters.has_code =
                !state.chat_pipeline.training_export.filters.has_code;
        }
        ChatPipelineMsg::ToggleTrainingAgenticOnly => {
            state.chat_pipeline.training_export.filters.agentic_only =
                !state.chat_pipeline.training_export.filters.agentic_only;
        }
        ChatPipelineMsg::SetTrainingMinTurns(turns) => {
            state.chat_pipeline.training_export.filters.min_turns = Some(turns);
        }
        ChatPipelineMsg::SetTrainingMinQuality(score) => {
            state
                .chat_pipeline
                .training_export
                .filters
                .min_quality_score = Some(score);
        }
        ChatPipelineMsg::FetchTrainingStats => {
            state.chat_pipeline.training_export.loading_preview = true;
            let filters = state.chat_pipeline.training_export.filters.clone();
            return Task::perform(
                async move { ChatApiClient::fetch_training_stats(filters).await },
                |result| Message::ChatPipelineAction(ChatPipelineMsg::TrainingStatsLoaded(result)),
            );
        }
        ChatPipelineMsg::TrainingStatsLoaded(result) => {
            state.chat_pipeline.training_export.loading_preview = false;
            match result {
                Ok(resp) => {
                    state.chat_pipeline.training_export.preview_stats = Some(resp.stats);
                }
                Err(e) => {
                    state.chat_pipeline.error =
                        Some(format!("Failed to load training stats: {}", e));
                }
            }
        }
        ChatPipelineMsg::FetchTrainingSamples => {
            state.chat_pipeline.training_export.loading_preview = true;
            let filters = state.chat_pipeline.training_export.filters.clone();
            return Task::perform(
                async move { ChatApiClient::fetch_training_sample(5, filters).await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::TrainingSamplesLoaded(result))
                },
            );
        }
        ChatPipelineMsg::TrainingSamplesLoaded(result) => {
            state.chat_pipeline.training_export.loading_preview = false;
            match result {
                Ok(resp) => {
                    state.chat_pipeline.training_export.samples = resp.samples;
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Failed to load samples: {}", e));
                }
            }
        }
        ChatPipelineMsg::RunTrainingExport => {
            state.chat_pipeline.training_export.exporting = true;
            let te = &state.chat_pipeline.training_export;
            let request = TrainingExportRequest {
                format: te.format.as_str().to_string(),
                filters: te.filters.clone(),
                sanitization: te.sanitization.clone(),
                augmentation: te.augmentation.clone(),
                val_ratio: Some(te.val_ratio),
                output_dir: None,
                system_prompt: if te.system_prompt.is_empty() {
                    None
                } else {
                    Some(te.system_prompt.clone())
                },
            };
            return Task::perform(
                async move { ChatApiClient::training_export(request).await },
                |result| {
                    Message::ChatPipelineAction(ChatPipelineMsg::TrainingExportComplete(result))
                },
            );
        }
        ChatPipelineMsg::TrainingExportComplete(result) => {
            state.chat_pipeline.training_export.exporting = false;
            match result {
                Ok(resp) => {
                    state.chat_pipeline.training_export.last_export = Some(resp.clone());
                    let train_path = resp.output_files.train.unwrap_or_default();
                    let val_path = resp.output_files.val.unwrap_or_default();
                    state.chat_pipeline.last_action_result = Some(format!(
                        "Export complete! Train: {}, Val: {}",
                        train_path, val_path
                    ));
                }
                Err(e) => {
                    state.chat_pipeline.error = Some(format!("Export failed: {}", e));
                }
            }
        }
    }
    Task::none()
}

// ---------------------------------------------------------------------------
// Chat Pipeline View
// ---------------------------------------------------------------------------

fn view_chat_pipeline(state: &ContinuumStudio) -> Element<'_, Message> {
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
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.2, 0.1, 0.1,
            ))),
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
                    .on_press(Message::ChatPipelineAction(
                        ChatPipelineMsg::ClearActionResult
                    ))
                    .padding([2, 8]),
            ]
            .align_y(Alignment::Center),
        )
        .padding(8)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.18, 0.1,
            ))),
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
        ChatSubView::Export => chat_export_view(cp),
        ChatSubView::Watcher => chat_watcher_view(cp),
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
        button(
            text(if cp.loading {
                "⟳ Loading..."
            } else {
                "⟳ Refresh"
            })
            .size(12)
        )
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
            .on_press(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(
                sub,
            )))
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
        tab("Export", ChatSubView::Export),
        tab("Watcher", ChatSubView::Watcher),
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
                    text(t).size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
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
                card(
                    "Conversations",
                    fmt_num(s.conversations),
                    iced::Color::from_rgb(0.4, 0.7, 1.0)
                ),
                card(
                    "Messages",
                    fmt_num(s.messages),
                    iced::Color::from_rgb(0.5, 0.8, 0.5)
                ),
                card(
                    "Chunks",
                    fmt_num(s.chunks),
                    iced::Color::from_rgb(0.8, 0.7, 0.4)
                ),
                card(
                    "Embeddings",
                    fmt_num(s.embeddings),
                    iced::Color::from_rgb(0.7, 0.5, 0.9)
                ),
            ]
            .spacing(8),
            row![
                card(
                    "Summaries",
                    fmt_num(s.summaries),
                    iced::Color::from_rgb(0.4, 0.8, 0.8)
                ),
                card(
                    "Topics",
                    fmt_num(s.topics),
                    iced::Color::from_rgb(0.9, 0.5, 0.6)
                ),
                card(
                    "LLM Calls",
                    fmt_num(sm.total_llm_calls),
                    iced::Color::from_rgb(0.8, 0.6, 0.4)
                ),
                card(
                    "Workspace Summaries",
                    fmt_num(s.workspace_summaries),
                    iced::Color::from_rgb(0.6, 0.7, 0.9)
                ),
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
                        Some(Message::ChatPipelineAction(
                            ChatPipelineMsg::DoSummarizePending,
                        ))
                    })
                    .padding([8, 16]),
                button(text("Cluster Topics").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(
                            ChatPipelineMsg::DoClusterTopics,
                        ))
                    })
                    .padding([8, 16]),
                button(text("Import Orphaned").size(12))
                    .on_press_maybe(if cp.loading {
                        None
                    } else {
                        Some(Message::ChatPipelineAction(
                            ChatPipelineMsg::DoImportOrphaned,
                        ))
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
                    Some(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(
                        ChatSubView::Scanner,
                    )))
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
                    .on_press(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(
                        ChatSubView::Scanner
                    )))
                    .padding([8, 16]),
            ]
            .spacing(4)
            .align_x(Alignment::Center),
        )
        .center_x(Length::Fill)
        .padding(40)
        .into()
    };

    column![text("Database Scanner").size(16), content,]
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
        let title = conv.title.as_deref().unwrap_or("Untitled conversation");
        let workspace = conv.workspace.as_deref().unwrap_or("unknown");

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
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::SelectConversation(id),
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
    let title = conv.title.as_deref().unwrap_or("Untitled");

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
        row![back_btn, Space::new().width(Length::Fill), expand_btn,].align_y(Alignment::Center),
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
            text(if conv.is_agentic {
                "🤖 Agentic"
            } else {
                "💬 Standard"
            })
            .size(12),
        ]
        .spacing(4),
    ]
    .spacing(4);

    // Summary section
    let summary: Element<'_, Message> = if let Some(ref s) = conv.summary {
        container(
            column![
                text("Summary")
                    .size(14)
                    .color(iced::Color::from_rgb(0.6, 0.8, 1.0)),
                text(s.as_str())
                    .size(12)
                    .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
            ]
            .spacing(4),
        )
        .padding(12)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.12, 0.16,
            ))),
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
            format!(
                "{}... [click to expand]",
                &msg.content[..300.min(msg.content.len())]
            )
        } else {
            msg.content.clone()
        };

        let is_long = msg.content.len() > 300;
        let char_count = msg.content.len();
        let token_info = if msg.input_tokens > 0 || msg.output_tokens > 0 {
            format!(
                " | {}in/{}out tokens",
                fmt_num(msg.input_tokens),
                fmt_num(msg.output_tokens)
            )
        } else {
            String::new()
        };

        let msg_content = container(
            column![
                row![
                    text(role_label).size(11).color(role_color),
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
                Some(Message::ChatPipelineAction(ChatPipelineMsg::SwitchSubView(
                    ChatSubView::Topics,
                )))
            })
            .padding([6, 12]),
        Space::new().width(4),
        button(text("Re-cluster").size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(
                    ChatPipelineMsg::DoClusterTopics,
                ))
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
        let desc = topic.description.as_deref().unwrap_or("No description");

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
    let search_input = iced::widget::text_input(
        "Search conversations, messages, chunks...",
        &cp.search_query,
    )
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
        let mut list = column![text(format!("{} results", cp.search_results.len()))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),]
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

    column![text("Search").size(16), search_bar, results,]
        .spacing(12)
        .into()
}

fn chat_export_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let is_single = cp.training_export.mode == ExportMode::Single;
    let is_training = !is_single;

    // Export mode tabs - dynamically styled based on active mode
    let single_tab = button(text("Single Export").size(12))
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::SwitchExportMode(ExportMode::Single),
        ))
        .padding([8, 16])
        .style(move |_theme, _status| {
            let (bg, fg, border_color) = if is_single {
                (
                    iced::Color::from_rgb(0.2, 0.35, 0.5),
                    iced::Color::WHITE,
                    iced::Color::from_rgb(0.25, 0.4, 0.55),
                )
            } else {
                (
                    iced::Color::from_rgb(0.12, 0.12, 0.14),
                    iced::Color::from_rgb(0.6, 0.6, 0.6),
                    iced::Color::from_rgb(0.2, 0.2, 0.22),
                )
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: fg,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: border_color,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        });

    let training_tab = button(text("Training Export").size(12))
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::SwitchExportMode(ExportMode::Training),
        ))
        .padding([8, 16])
        .style(move |_theme, _status| {
            let (bg, fg, border_color) = if is_training {
                (
                    iced::Color::from_rgb(0.35, 0.25, 0.5),
                    iced::Color::WHITE,
                    iced::Color::from_rgb(0.45, 0.35, 0.6),
                )
            } else {
                (
                    iced::Color::from_rgb(0.12, 0.12, 0.14),
                    iced::Color::from_rgb(0.6, 0.6, 0.6),
                    iced::Color::from_rgb(0.2, 0.2, 0.22),
                )
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: fg,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: border_color,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        });

    let mode_tabs = row![single_tab, training_tab].spacing(0);

    // Branch based on mode
    let content: Element<'_, Message> = if is_training {
        training_export_content(cp)
    } else {
        single_export_content(cp)
    };

    column![text("Export Conversations").size(16), mode_tabs, content,]
        .spacing(12)
        .into()
}

/// Single export mode: export individual conversations
fn single_export_content(cp: &ChatPipelineState) -> Element<'_, Message> {
    // Format selector
    let format_selector = row(ExportFormat::ALL
        .iter()
        .map(|&fmt| {
            let is_selected = cp.export_format == fmt;
            button(text(fmt.label()).size(12))
                .on_press(Message::ChatPipelineAction(
                    ChatPipelineMsg::SetExportFormat(fmt),
                ))
                .padding([6, 12])
                .style(move |_theme, _status| {
                    let bg = if is_selected {
                        iced::Color::from_rgb(0.25, 0.45, 0.65)
                    } else {
                        iced::Color::from_rgb(0.15, 0.15, 0.18)
                    };
                    button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: if is_selected {
                            iced::Color::WHITE
                        } else {
                            iced::Color::from_rgb(0.6, 0.6, 0.6)
                        },
                        border: iced::Border {
                            radius: 4.0.into(),
                            width: 1.0,
                            color: if is_selected {
                                iced::Color::from_rgb(0.35, 0.55, 0.75)
                            } else {
                                iced::Color::from_rgb(0.2, 0.2, 0.22)
                            },
                        },
                        shadow: iced::Shadow::default(),
                        snap: false,
                    }
                })
                .into()
        })
        .collect::<Vec<Element<'_, Message>>>())
    .spacing(4);

    let format_row = row![
        text("Format:")
            .size(13)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        format_selector,
    ]
    .spacing(12)
    .align_y(Alignment::Center);

    // Selection controls
    let select_controls = row![
        button(text("Select All").size(11))
            .on_press(Message::ChatPipelineAction(
                ChatPipelineMsg::SelectAllForExport
            ))
            .padding([4, 10])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.14
                ))),
                text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.22)
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }),
        button(text("Deselect All").size(11))
            .on_press(Message::ChatPipelineAction(
                ChatPipelineMsg::DeselectAllForExport
            ))
            .padding([4, 10])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.14
                ))),
                text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.2, 0.2, 0.22)
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }),
        text(format!("{} selected", cp.export_selected.len()))
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    // Conversation list with checkboxes
    let mut conv_list = column![].spacing(4);

    for conv in &cp.conversations {
        let is_selected = cp.export_selected.contains(&conv.id);
        let conv_id = conv.id.clone();
        let export_id = conv.id.clone();

        let title = conv.title.as_deref().unwrap_or("Untitled");
        let truncated_title = truncate(title, 60);

        let checkbox_icon = if is_selected { "[x]" } else { "[ ]" };

        let conv_row = button(
            row![
                text(checkbox_icon).size(12).color(if is_selected {
                    iced::Color::from_rgb(0.4, 0.7, 0.4)
                } else {
                    iced::Color::from_rgb(0.4, 0.4, 0.4)
                }),
                column![
                    text(truncated_title)
                        .size(13)
                        .color(iced::Color::from_rgb(0.8, 0.8, 0.8)),
                    row![
                        text(format!("{} msgs", conv.message_count))
                            .size(10)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        text(conv.workspace.as_deref().unwrap_or(""))
                            .size(10)
                            .color(iced::Color::from_rgb(0.4, 0.5, 0.6)),
                    ]
                    .spacing(8),
                ]
                .spacing(2),
                Space::new().width(Length::Fill),
                button(text("Export").size(10))
                    .on_press(Message::ChatPipelineAction(
                        ChatPipelineMsg::ExportConversation(export_id)
                    ))
                    .padding([4, 8])
                    .style(|_theme, _status| button::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.2, 0.35, 0.5
                        ))),
                        text_color: iced::Color::WHITE,
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        shadow: iced::Shadow::default(),
                        snap: false,
                    }),
            ]
            .spacing(12)
            .align_y(Alignment::Center)
            .padding([8, 12]),
        )
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::ToggleExportSelect(conv_id),
        ))
        .width(Length::Fill)
        .style(move |_theme, _status| button::Style {
            background: Some(iced::Background::Color(if is_selected {
                iced::Color::from_rgb(0.12, 0.15, 0.18)
            } else {
                iced::Color::from_rgb(0.08, 0.08, 0.1)
            })),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: if is_selected {
                    iced::Color::from_rgb(0.25, 0.35, 0.45)
                } else {
                    iced::Color::from_rgb(0.15, 0.15, 0.17)
                },
            },
            shadow: iced::Shadow::default(),
            snap: false,
        });

        conv_list = conv_list.push(conv_row);
    }

    let scrollable_list = scrollable(conv_list).height(Length::FillPortion(2));

    // Export result preview (if available)
    let preview: Element<'_, Message> = if let Some(ref export) = cp.export_result {
        let preview_text = if export.content.len() > 2000 {
            format!(
                "{}...\n\n[{} chars total]",
                &export.content[..2000],
                export.content.len()
            )
        } else {
            export.content.clone()
        };

        container(
            column![
                row![
                    text(format!("Exported: {}", export.filename))
                        .size(13)
                        .color(iced::Color::from_rgb(0.4, 0.7, 0.4)),
                    Space::new().width(Length::Fill),
                    button(text("Save").size(11))
                        .on_press(Message::ChatPipelineAction(
                            ChatPipelineMsg::SaveExportToFile
                        ))
                        .padding([4, 10])
                        .style(|_theme, _status| button::Style {
                            background: Some(iced::Background::Color(iced::Color::from_rgb(
                                0.2, 0.4, 0.3
                            ))),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            shadow: iced::Shadow::default(),
                            snap: false,
                        }),
                    button(text("Copy").size(11))
                        .on_press(Message::ChatPipelineAction(
                            ChatPipelineMsg::CopyExportToClipboard
                        ))
                        .padding([4, 10])
                        .style(|_theme, _status| button::Style {
                            background: Some(iced::Background::Color(iced::Color::from_rgb(
                                0.3, 0.35, 0.5
                            ))),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            shadow: iced::Shadow::default(),
                            snap: false,
                        }),
                    button(text("X").size(11))
                        .on_press(Message::ChatPipelineAction(
                            ChatPipelineMsg::ClearExportResult
                        ))
                        .padding([4, 8])
                        .style(|_theme, _status| button::Style {
                            background: Some(iced::Background::Color(iced::Color::from_rgb(
                                0.4, 0.2, 0.2
                            ))),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            shadow: iced::Shadow::default(),
                            snap: false,
                        }),
                ]
                .spacing(8)
                .align_y(Alignment::Center),
                scrollable(
                    container(
                        text(preview_text)
                            .size(11)
                            .font(iced::Font::MONOSPACE)
                            .color(iced::Color::from_rgb(0.7, 0.7, 0.7))
                    )
                    .padding(12)
                )
                .height(Length::FillPortion(1)),
            ]
            .spacing(8),
        )
        .padding(12)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.08, 0.1,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.2, 0.15),
            },
            ..Default::default()
        })
        .into()
    } else if cp.exporting {
        container(
            text("Exporting...")
                .size(13)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.8)),
        )
        .center_x(Length::Fill)
        .center_y(Length::FillPortion(1))
        .into()
    } else {
        container(
            column![
                text("Select conversations and click Export")
                    .size(13)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("or export individual conversations with the Export button")
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ]
            .spacing(4)
            .align_x(Alignment::Center),
        )
        .center_x(Length::Fill)
        .center_y(Length::FillPortion(1))
        .into()
    };

    column![format_row, select_controls, scrollable_list, preview,]
        .spacing(12)
        .into()
}

/// Training export mode: bulk export for fine-tuning
fn training_export_content(cp: &ChatPipelineState) -> Element<'_, Message> {
    let te = &cp.training_export;

    // Format selector (OpenAI / Alpaca / ShareGPT)
    let format_buttons = row![
        training_format_btn(TrainingFormat::Openai, te.format),
        training_format_btn(TrainingFormat::Alpaca, te.format),
        training_format_btn(TrainingFormat::Sharegpt, te.format),
    ]
    .spacing(4);

    let format_row = row![
        text("Format:")
            .size(13)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        format_buttons,
    ]
    .spacing(12)
    .align_y(Alignment::Center);

    // Filters section
    let has_code_toggle = filter_checkbox(
        "Requires Code",
        te.filters.has_code,
        ChatPipelineMsg::ToggleTrainingHasCode,
    );
    let agentic_toggle = filter_checkbox(
        "Agentic Only",
        te.filters.agentic_only,
        ChatPipelineMsg::ToggleTrainingAgenticOnly,
    );

    // Min turns slider (1-50)
    let min_turns_val = te.filters.min_turns.unwrap_or(2) as f64;
    let min_turns_slider = row![
        text(format!("Min Turns: {}", min_turns_val as u32))
            .size(12)
            .width(Length::Fixed(100.0))
            .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        slider(1.0..=50.0, min_turns_val, |v| {
            Message::ChatPipelineAction(ChatPipelineMsg::SetTrainingMinTurns(v as u32))
        })
        .width(Length::Fixed(150.0))
        .step(1.0),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    // Min quality slider (0.0-1.0)
    let min_quality_val = te.filters.min_quality_score.unwrap_or(0.3);
    let min_quality_slider = row![
        text(format!("Min Quality: {:.2}", min_quality_val))
            .size(12)
            .width(Length::Fixed(120.0))
            .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        slider(0.0..=1.0, min_quality_val, |v| {
            Message::ChatPipelineAction(ChatPipelineMsg::SetTrainingMinQuality(v))
        })
        .width(Length::Fixed(150.0))
        .step(0.05),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let filters_section = container(
        column![
            text("Filters")
                .size(14)
                .color(iced::Color::from_rgb(0.8, 0.8, 0.8)),
            row![has_code_toggle, agentic_toggle].spacing(16),
            min_turns_slider,
            min_quality_slider,
        ]
        .spacing(10),
    )
    .padding(12)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.1, 0.1, 0.12,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.2),
        },
        ..Default::default()
    });

    // Stats/Preview section
    let stats_section: Element<'_, Message> = if te.loading_preview {
        container(
            text("Loading preview...")
                .size(13)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.8)),
        )
        .center_x(Length::Fill)
        .padding(20)
        .into()
    } else if let Some(ref stats) = te.preview_stats {
        let total_str = fmt_num(stats.total_conversations);
        let filter_str = fmt_num(stats.after_filter);
        let tokens_str = fmt_num(stats.estimated_tokens);
        let train_str = fmt_num(stats.train_examples);
        let val_str = fmt_num(stats.val_examples);

        container(
            column![
                text("Preview Stats")
                    .size(14)
                    .color(iced::Color::from_rgb(0.8, 0.8, 0.8)),
                row![
                    stat_box_owned("Total", total_str),
                    stat_box_owned("After Filter", filter_str),
                    stat_box_owned("Est. Tokens", tokens_str),
                ]
                .spacing(12),
                row![
                    stat_box_owned("Train", train_str),
                    stat_box_owned("Val", val_str),
                ]
                .spacing(12),
            ]
            .spacing(8),
        )
        .padding(12)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.1, 0.08,
            ))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.2, 0.15),
            },
            ..Default::default()
        })
        .into()
    } else {
        container(
            column![text("Click 'Preview' to see export statistics")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),]
            .align_x(Alignment::Center),
        )
        .center_x(Length::Fill)
        .padding(20)
        .into()
    };

    // Action buttons
    let preview_btn = button(text("Preview").size(12))
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::FetchTrainingStats,
        ))
        .padding([8, 16])
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.2, 0.3, 0.45,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            shadow: iced::Shadow::default(),
            snap: false,
        });

    let sample_btn = button(text("Load Samples").size(12))
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::FetchTrainingSamples,
        ))
        .padding([8, 16])
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.25, 0.35, 0.45,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            shadow: iced::Shadow::default(),
            snap: false,
        });

    let export_btn = if te.exporting {
        button(text("Exporting...").size(12))
            .padding([8, 20])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.3, 0.3, 0.4,
                ))),
                text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                shadow: iced::Shadow::default(),
                snap: false,
            })
    } else {
        button(text("Export Training Data").size(12))
            .on_press(Message::ChatPipelineAction(
                ChatPipelineMsg::RunTrainingExport,
            ))
            .padding([8, 20])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.35, 0.5, 0.35,
                ))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                shadow: iced::Shadow::default(),
                snap: false,
            })
    };

    let action_row = row![
        preview_btn,
        sample_btn,
        Space::new().width(Length::Fill),
        export_btn
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    // Sample conversations preview
    let samples_section: Element<'_, Message> = if te.samples.is_empty() {
        container(
            text("No samples loaded")
                .size(12)
                .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
        )
        .center_x(Length::Fill)
        .padding(12)
        .into()
    } else {
        let mut sample_list = column![].spacing(6);
        for sample in te.samples.iter().take(5) {
            let sample_card = container(
                column![
                    text(sample.title.as_deref().unwrap_or("Untitled"))
                        .size(12)
                        .color(iced::Color::from_rgb(0.8, 0.8, 0.9)),
                    text(format!(
                        "{} msgs, quality: {:.2}",
                        sample.message_count, sample.quality_score
                    ))
                    .size(10)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.6)),
                ]
                .spacing(2),
            )
            .padding(8)
            .style(|_theme| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.1, 0.1, 0.12,
                ))),
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.15, 0.15, 0.18),
                },
                ..Default::default()
            });
            sample_list = sample_list.push(sample_card);
        }
        scrollable(sample_list)
            .height(Length::FillPortion(1))
            .into()
    };

    // Export result (if available)
    let result_section: Element<'_, Message> = if let Some(ref result) = te.last_export {
        container(
            column![
                text("Export Complete!")
                    .size(14)
                    .color(iced::Color::from_rgb(0.4, 0.8, 0.4)),
                text(format!("Train: {} examples", result.stats.train_examples))
                    .size(12)
                    .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
                text(format!("Val: {} examples", result.stats.val_examples))
                    .size(12)
                    .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
                text(format!(
                    "Output: {}",
                    result.output_files.train.as_deref().unwrap_or("N/A")
                ))
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.6)),
            ]
            .spacing(4),
        )
        .padding(12)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.12, 0.08,
            ))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.15, 0.25, 0.15),
            },
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    column![
        format_row,
        filters_section,
        action_row,
        stats_section,
        samples_section,
        result_section,
    ]
    .spacing(12)
    .into()
}

fn training_format_btn(fmt: TrainingFormat, current: TrainingFormat) -> Element<'static, Message> {
    let is_selected = fmt == current;
    let label = match fmt {
        TrainingFormat::Openai => "OpenAI",
        TrainingFormat::Alpaca => "Alpaca",
        TrainingFormat::Sharegpt => "ShareGPT",
    };
    button(text(label).size(11))
        .on_press(Message::ChatPipelineAction(
            ChatPipelineMsg::SetTrainingFormat(fmt),
        ))
        .padding([6, 12])
        .style(move |_theme, _status| {
            let (bg, fg, border) = if is_selected {
                (
                    iced::Color::from_rgb(0.35, 0.25, 0.5),
                    iced::Color::WHITE,
                    iced::Color::from_rgb(0.45, 0.35, 0.6),
                )
            } else {
                (
                    iced::Color::from_rgb(0.15, 0.15, 0.18),
                    iced::Color::from_rgb(0.6, 0.6, 0.6),
                    iced::Color::from_rgb(0.2, 0.2, 0.22),
                )
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: fg,
                border: iced::Border {
                    radius: 4.0.into(),
                    width: 1.0,
                    color: border,
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }
        })
        .into()
}

fn filter_checkbox(label: &str, checked: bool, msg: ChatPipelineMsg) -> Element<'_, Message> {
    let icon = if checked { "[x]" } else { "[ ]" };
    let color = if checked {
        iced::Color::from_rgb(0.5, 0.8, 0.5)
    } else {
        iced::Color::from_rgb(0.5, 0.5, 0.5)
    };
    button(
        row![
            text(icon).size(12).color(color),
            text(label)
                .size(12)
                .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        ]
        .spacing(6),
    )
    .on_press(Message::ChatPipelineAction(msg))
    .padding([4, 8])
    .style(|_theme, _status| button::Style {
        background: Some(iced::Background::Color(iced::Color::TRANSPARENT)),
        text_color: iced::Color::WHITE,
        border: iced::Border::default(),
        shadow: iced::Shadow::default(),
        snap: false,
    })
    .into()
}

fn stat_box_owned(label: &'static str, value: String) -> Element<'static, Message> {
    container(
        column![
            text(value)
                .size(16)
                .color(iced::Color::from_rgb(0.9, 0.9, 0.9)),
            text(label)
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(2)
        .align_x(Alignment::Center),
    )
    .padding([8, 16])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.14,
        ))),
        border: iced::Border {
            radius: 4.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.2),
        },
        ..Default::default()
    })
    .into()
}

fn chat_watcher_view(cp: &ChatPipelineState) -> Element<'_, Message> {
    let watcher_status = cp.watcher_status.as_ref();

    // Action buttons
    let action_buttons = row![
        button(text("Scan Workspaces").size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(
                    ChatPipelineMsg::TriggerWatcherScan,
                ))
            })
            .padding([8, 16])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.2, 0.35, 0.5
                ))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }),
        button(text("Process All DBs").size(12))
            .on_press_maybe(if cp.loading {
                None
            } else {
                Some(Message::ChatPipelineAction(
                    ChatPipelineMsg::TriggerPipelineProcessAll,
                ))
            })
            .padding([8, 16])
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.25, 0.45, 0.35
                ))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                shadow: iced::Shadow::default(),
                snap: false,
            }),
    ]
    .spacing(8);

    // Watcher status card
    let watcher_card: Element<'_, Message> = if let Some(status) = watcher_status {
        let watching_indicator = if status.watcher.watching {
            text("● Watching")
                .size(12)
                .color(iced::Color::from_rgb(0.3, 0.75, 0.4))
        } else {
            text("○ Not Watching")
                .size(12)
                .color(iced::Color::from_rgb(0.75, 0.4, 0.4))
        };

        container(
            column![
                row![
                    text("Watcher Status").size(14),
                    Space::new().width(Length::Fill),
                    watching_indicator,
                ]
                .align_y(Alignment::Center),
                row![
                    text("Known Databases:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(format!("{}", status.watcher.known_databases)).size(12),
                ]
                .spacing(8),
                row![
                    text("Pending Changes:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(format!("{}", status.watcher.pending_changes)).size(12),
                ]
                .spacing(8),
                row![
                    text("Cursor Base:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(truncate(&status.watcher.cursor_base, 50))
                        .size(11)
                        .font(iced::Font::MONOSPACE),
                ]
                .spacing(8),
            ]
            .spacing(6),
        )
        .padding(12)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.12, 0.14,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.2, 0.22),
            },
            ..Default::default()
        })
        .into()
    } else {
        container(
            text("Loading watcher status...")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(12)
        .into()
    };

    // Pipeline status card
    let pipeline_card: Element<'_, Message> = if let Some(status) = watcher_status {
        let processing_indicator = if status.pipeline.processing {
            text("● Processing")
                .size(12)
                .color(iced::Color::from_rgb(0.4, 0.6, 0.9))
        } else {
            text("○ Idle")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
        };

        let last_run = status
            .pipeline
            .stats
            .last_run_at
            .as_deref()
            .unwrap_or("Never");
        let last_duration = status
            .pipeline
            .stats
            .last_run_duration_ms
            .map(|d| format!("{}ms", d))
            .unwrap_or_else(|| "-".to_string());

        container(
            column![
                row![
                    text("Pipeline Status").size(14),
                    Space::new().width(Length::Fill),
                    processing_indicator,
                ]
                .align_y(Alignment::Center),
                row![
                    text("Queue Size:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(format!("{}", status.pipeline.queue_size)).size(12),
                ]
                .spacing(8),
                row![
                    text("Conversations Imported:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(fmt_num(status.pipeline.stats.total_conversations_imported)).size(12),
                ]
                .spacing(8),
                row![
                    text("Messages Imported:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(fmt_num(status.pipeline.stats.total_messages_imported)).size(12),
                ]
                .spacing(8),
                row![
                    text("Chunks Created:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(fmt_num(status.pipeline.stats.total_chunks_created)).size(12),
                ]
                .spacing(8),
                row![
                    text("Last Run:")
                        .size(12)
                        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                    text(truncate(last_run, 25))
                        .size(11)
                        .font(iced::Font::MONOSPACE),
                    text(format!("({})", last_duration))
                        .size(10)
                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                ]
                .spacing(8),
            ]
            .spacing(6),
        )
        .padding(12)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.12, 0.14,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.18, 0.2, 0.22),
            },
            ..Default::default()
        })
        .into()
    } else {
        container(Space::new()).into()
    };

    // Known databases list
    let databases_list: Element<'_, Message> = if let Some(status) = watcher_status {
        if status.databases.is_empty() {
            container(
                text("No databases discovered yet")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            )
            .padding(12)
            .into()
        } else {
            let mut db_list =
                column![
                    text(format!("Discovered Databases ({})", status.databases.len()))
                        .size(13)
                        .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
                ]
                .spacing(4);

            for db in &status.databases {
                let short_path = db
                    .replace(&std::env::var("HOME").unwrap_or_default(), "~")
                    .replace("/.config/Cursor/User/", "/…/");

                db_list = db_list.push(
                    container(
                        text(short_path)
                            .size(11)
                            .font(iced::Font::MONOSPACE)
                            .color(iced::Color::from_rgb(0.65, 0.65, 0.65)),
                    )
                    .padding([4, 8])
                    .style(|_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.08, 0.08, 0.1,
                        ))),
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }),
                );
            }

            scrollable(db_list).height(Length::Fill).into()
        }
    } else {
        container(Space::new()).into()
    };

    column![
        text("Real-time Watcher").size(16),
        text("Monitor Cursor database changes and pipeline processing")
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        action_buttons,
        row![watcher_card, pipeline_card,].spacing(12),
        databases_list,
    ]
    .spacing(12)
    .into()
}

// ===========================================================================
// Workspace File Discovery
// ===========================================================================

/// Scan for .code-workspace files in common locations
async fn scan_workspace_files() -> Vec<CodeWorkspaceFile> {
    use std::fs;
    use std::path::PathBuf;

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
                if path
                    .extension()
                    .map(|e| e == "code-workspace")
                    .unwrap_or(false)
                {
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
    let folders: Vec<String> = if let Ok(json) = serde_json::from_str::<serde_json::Value>(&content)
    {
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
            "--user",
            "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetStats",
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;

    if !output.status.success() {
        return Err(format!(
            "D-Bus call failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
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
            "--user",
            "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetSubagentCommands",
            "u",
            &limit.to_string(),
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;

    if !output.status.success() {
        return Err(format!(
            "D-Bus call failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
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
            "--user",
            "call",
            "sh.synapsix.TerminalMonitor",
            "/sh/synapsix/TerminalMonitor",
            "sh.synapsix.TerminalMonitor1",
            "GetRunningCommands",
        ])
        .output()
        .map_err(|e| format!("Failed to execute busctl: {}", e))?;

    if !output.status.success() {
        return Err(format!(
            "D-Bus call failed: {}",
            String::from_utf8_lossy(&output.stderr)
        ));
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
                Task::perform(fetch_subagent_stats(), |result| {
                    Message::SubagentAction(SubagentMessage::StatsLoaded(result))
                }),
                Task::perform(fetch_subagent_commands(20), |result| {
                    Message::SubagentAction(SubagentMessage::SubagentsLoaded(result))
                }),
                Task::perform(fetch_running_commands(), |result| {
                    Message::SubagentAction(SubagentMessage::RunningLoaded(result))
                }),
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
                state
                    .subagent_state
                    .set_error("Terminal monitor service not available".to_string());
            }
            Task::none()
        }
    }
}

/// Handle CLI agent messages
fn handle_cli_agent_message(state: &mut ContinuumStudio, msg: CLIAgentMessage) -> Task<Message> {
    // Check if this is DialogsLoaded to also wire to orchestrator triage queue
    if let CLIAgentMessage::DialogsLoaded(ref dialogs) = msg {
        use continuum_studio_iced::cli_agents::DialogPriority;
        use continuum_studio_iced::decision_engine::TriageState;

        // Add dialogs to orchestrator's triage queue
        for dialog in dialogs {
            // Determine triage state based on priority
            // Critical/High -> Manual (requires human attention)
            // Lower priority -> Manual by default (can be changed to AutoApprove)
            let triage_state = match dialog.priority {
                DialogPriority::Critical | DialogPriority::High => TriageState::Manual,
                _ => TriageState::Manual,
            };
            let reasoning = format!(
                "Dialog from {:?} agent in {}",
                dialog.source,
                dialog.workspace.as_deref().unwrap_or("unknown")
            );
            state.orchestrator_state.add_to_triage(dialog.clone(), triage_state, reasoning);
        }
        log::info!(
            "Added {} dialogs to orchestrator triage queue (total: {})",
            dialogs.len(),
            state.orchestrator_state.engine.triage_queue().len()
        );
    }

    // Update state and get any async task to perform
    let task = state.cli_agents_state.update(msg);

    match task {
        Some(CLIAgentTask::SpawnAgent {
            prompt,
            workspace,
            mode,
            force,
            approve_mcps,
            prefix,
            suffix,
        }) => {
            log::info!(
                "CLI Agent spawn requested: workspace={}, mode={:?}, force={}, has_prefix={}, has_suffix={}",
                workspace, mode, force, prefix.is_some(), suffix.is_some()
            );

            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .spawn_agent_with_preset(
                            &prompt,
                            &workspace,
                            Some(mode),
                            Some(force),
                            Some(approve_mcps),
                            prefix,
                            suffix,
                        )
                        .await
                },
                |result| match result {
                    Ok(resp) => {
                        log::info!("Agent spawned: {}", resp.agent_id);
                        Message::CLIAgentAction(CLIAgentMessage::AgentStarted {
                            id: resp.agent_id,
                            workspace: String::new(),
                            model: None,
                        })
                    }
                    Err(e) => {
                        log::error!("Failed to spawn agent: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::SpawnBatch {
            prompt,
            workspaces,
            max_concurrent,
            stop_on_failure,
        }) => {
            log::info!(
                "CLI Batch spawn requested: {} workspaces, max_concurrent={}",
                workspaces.len(),
                max_concurrent
            );

            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .spawn_batch(
                            &prompt,
                            workspaces,
                            Some(max_concurrent),
                            Some(stop_on_failure),
                        )
                        .await
                },
                |result| match result {
                    Ok(resp) => {
                        log::info!("Batch spawned: {:?}", resp.batch_id);
                        Message::CLIAgentAction(CLIAgentMessage::RefreshAgents)
                    }
                    Err(e) => {
                        log::error!("Failed to spawn batch: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::StopAgent { id }) => {
            log::info!("CLI Agent stop requested: id={}", id);

            let client = state.cli_agents_http.clone();
            let agent_id = id.clone();
            Task::perform(
                async move { client.stop_agent(&agent_id).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Agent stopped: {}", id);
                        Message::CLIAgentAction(CLIAgentMessage::RefreshAgents)
                    }
                    Err(e) => {
                        log::error!("Failed to stop agent: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::RefreshAgents) => {
            log::info!("CLI Agents refresh requested");

            let client = state.cli_agents_http.clone();
            Task::perform(
                async move { client.list_agents().await },
                |result| match result {
                    Ok(agents) => {
                        log::info!("Refreshed {} agents", agents.len());
                        Message::CLIAgentAction(CLIAgentMessage::AgentsLoaded(
                            agents.into_iter().map(|a| a.id).collect(),
                        ))
                    }
                    Err(e) => {
                        log::error!("Failed to refresh agents: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::FetchAgentDetails { ids }) => {
            log::info!("Fetching details for {} agents", ids.len());
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    let mut agents = Vec::new();
                    for id in ids {
                        match client.get_agent(&id).await {
                            Ok(details) => {
                                agents.push(continuum_studio_iced::cli_agents::CLIAgent::from_details(details));
                            }
                            Err(e) => {
                                log::warn!("Failed to fetch agent {}: {}", id, e);
                            }
                        }
                    }
                    agents
                },
                |agents| {
                    log::info!("Fetched full details for {} agents", agents.len());
                    Message::CLIAgentAction(CLIAgentMessage::AgentsFullLoaded(agents))
                },
            )
        }

        // Preset tasks
        Some(CLIAgentTask::FetchPresets) => {
            log::info!("Fetching presets");
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move { client.list_presets().await },
                |result| match result {
                    Ok(presets) => {
                        log::info!("Loaded {} presets", presets.len());
                        Message::CLIAgentAction(CLIAgentMessage::PresetsLoaded(presets))
                    }
                    Err(e) => {
                        log::error!("Failed to fetch presets: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::FetchSnippets) => {
            log::info!("Fetching snippets");
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move { client.list_snippets().await },
                |result| match result {
                    Ok(snippets) => {
                        log::info!("Loaded {} snippets", snippets.len());
                        Message::CLIAgentAction(CLIAgentMessage::SnippetsLoaded(snippets))
                    }
                    Err(e) => {
                        log::error!("Failed to fetch snippets: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::CreatePreset {
            name,
            description,
            category,
            prefix,
            suffix,
        }) => {
            log::info!("Creating preset: {}", name);
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .create_preset(&name, description, &category, prefix, suffix)
                        .await
                },
                |result| match result {
                    Ok(preset) => {
                        log::info!("Created preset: {}", preset.id);
                        Message::CLIAgentAction(CLIAgentMessage::PresetSaved(preset))
                    }
                    Err(e) => {
                        log::error!("Failed to create preset: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::UpdatePreset {
            id,
            name,
            description,
            category,
            prefix,
            suffix,
        }) => {
            log::info!("Updating preset: {}", id);
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .update_preset(&id, &name, description, &category, prefix, suffix)
                        .await
                },
                |result| match result {
                    Ok(preset) => {
                        log::info!("Updated preset: {}", preset.id);
                        Message::CLIAgentAction(CLIAgentMessage::PresetSaved(preset))
                    }
                    Err(e) => {
                        log::error!("Failed to update preset: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::DeletePreset { id }) => {
            log::info!("Deleting preset: {}", id);
            let client = state.cli_agents_http.clone();
            let preset_id = id.clone();
            Task::perform(
                async move { client.delete_preset(&preset_id).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Deleted preset: {}", id);
                        Message::CLIAgentAction(CLIAgentMessage::PresetDeleted(id.clone()))
                    }
                    Err(e) => {
                        log::error!("Failed to delete preset: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::CreateSnippet {
            name,
            description,
            content,
            position,
        }) => {
            log::info!("Creating snippet: {}", name);
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .create_snippet(&name, description, &content, &position)
                        .await
                },
                |result| match result {
                    Ok(snippet) => {
                        log::info!("Created snippet: {}", snippet.id);
                        Message::CLIAgentAction(CLIAgentMessage::SnippetSaved(snippet))
                    }
                    Err(e) => {
                        log::error!("Failed to create snippet: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::UpdateSnippet {
            id,
            name,
            description,
            content,
            position,
        }) => {
            log::info!("Updating snippet: {}", id);
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move {
                    client
                        .update_snippet(&id, &name, description, &content, &position)
                        .await
                },
                |result| match result {
                    Ok(snippet) => {
                        log::info!("Updated snippet: {}", snippet.id);
                        Message::CLIAgentAction(CLIAgentMessage::SnippetSaved(snippet))
                    }
                    Err(e) => {
                        log::error!("Failed to update snippet: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::DeleteSnippet { id }) => {
            log::info!("Deleting snippet: {}", id);
            let client = state.cli_agents_http.clone();
            let snippet_id = id.clone();
            Task::perform(
                async move { client.delete_snippet(&snippet_id).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Deleted snippet: {}", id);
                        Message::CLIAgentAction(CLIAgentMessage::SnippetDeleted(id.clone()))
                    }
                    Err(e) => {
                        log::error!("Failed to delete snippet: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::FetchWorkspaceOverrides) => {
            log::info!("Fetching workspace overrides");
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move { client.list_workspace_overrides().await },
                |result| match result {
                    Ok(overrides) => {
                        log::info!("Loaded {} workspace overrides", overrides.len());
                        Message::CLIAgentAction(CLIAgentMessage::WorkspaceOverridesLoaded(
                            overrides,
                        ))
                    }
                    Err(e) => {
                        log::error!("Failed to load workspace overrides: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::SetWorkspaceOverride {
            workspace,
            preset_id,
        }) => {
            log::info!("Setting workspace override: {} -> {}", workspace, preset_id);
            let client = state.cli_agents_http.clone();
            let ws = workspace.clone();
            let pid = preset_id.clone();
            Task::perform(
                async move { client.set_workspace_override(&ws, &pid).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Set workspace override: {} -> {}", workspace, preset_id);
                        Message::CLIAgentAction(CLIAgentMessage::WorkspaceOverrideSet {
                            workspace: workspace.clone(),
                            preset_id: preset_id.clone(),
                        })
                    }
                    Err(e) => {
                        log::error!("Failed to set workspace override: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::ClearWorkspaceOverride { workspace }) => {
            log::info!("Clearing workspace override: {}", workspace);
            let client = state.cli_agents_http.clone();
            let ws = workspace.clone();
            Task::perform(
                async move { client.clear_workspace_override(&ws).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Cleared workspace override: {}", workspace);
                        Message::CLIAgentAction(CLIAgentMessage::WorkspaceOverrideCleared(
                            workspace.clone(),
                        ))
                    }
                    Err(e) => {
                        log::error!("Failed to clear workspace override: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        // Dialog Orchestration Tasks
        Some(CLIAgentTask::FetchPendingDialogs) => {
            log::info!("Fetching pending dialogs from daemon");
            let client = state.cli_agents_http.clone();
            Task::perform(
                async move { client.fetch_pending_dialogs().await },
                |result| match result {
                    Ok(dialogs) => {
                        log::info!("Fetched {} pending dialogs", dialogs.len());
                        Message::CLIAgentAction(CLIAgentMessage::DialogsLoaded(dialogs))
                    }
                    Err(e) => {
                        log::error!("Failed to fetch dialogs: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::RespondToDialog {
            dialog_id,
            selection,
            comment,
        }) => {
            log::info!(
                "Responding to dialog {}: selection={}",
                dialog_id,
                selection
            );
            let client = state.cli_agents_http.clone();
            let id = dialog_id.clone();
            Task::perform(
                async move { client.respond_to_dialog(&id, &selection, comment).await },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Dialog response submitted: {}", dialog_id);
                        Message::CLIAgentAction(CLIAgentMessage::DialogResponseSubmitted(
                            dialog_id.clone(),
                        ))
                    }
                    Err(e) => {
                        log::error!("Failed to respond to dialog: {}", e);
                        Message::CLIAgentAction(CLIAgentMessage::Error(e))
                    }
                },
            )
        }
        Some(CLIAgentTask::CopyPromptToClipboard { prompt }) => {
            let char_count = prompt.len();
            log::info!("Copying prompt to clipboard ({} chars)", char_count);
            Task::perform(
                async move {
                    use arboard::Clipboard;
                    match Clipboard::new() {
                        Ok(mut clipboard) => clipboard
                            .set_text(&prompt)
                            .map_err(|e| format!("Clipboard error: {}", e)),
                        Err(e) => Err(format!("Failed to access clipboard: {}", e)),
                    }
                },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Prompt copied to clipboard");
                        Message::ShowToast(
                            format!("Copied {} chars to clipboard", char_count),
                            ToastLevel::Success,
                        )
                    }
                    Err(e) => {
                        log::error!("Failed to copy prompt: {}", e);
                        Message::ShowToast(
                            format!("Copy failed: {}", e),
                            ToastLevel::Error,
                        )
                    }
                },
            )
        }
        None => Task::none(),
    }
}

/// Handle orchestrator panel messages
fn handle_orchestrator_message(
    state: &mut ContinuumStudio,
    msg: OrchestratorMessage,
) -> Task<Message> {
    use OrchestratorMessage::*;
    match msg {
        SetMode(mode) => {
            // Fire async D-Bus call to set mode
            // OrchestratorMode is Copy, so we can use it directly in the async block
            Task::perform(
                async move {
                    use continuum_studio_iced::dialog_client::DialogClient;
                    let mut client = DialogClient::new();
                    if client.connect().await.is_ok() {
                        client.set_orchestrator_mode(mode).await
                    } else {
                        Err("Failed to connect to daemon".to_string())
                    }
                },
                |result| Message::OrchestratorAction(ModeSetResult(result)),
            )
        }
        ModeRefreshed(mode) => {
            state.orchestrator_state.mode = mode;
            state.orchestrator_state.mode_since = std::time::Instant::now();
            state.orchestrator_state.engine.set_mode(mode);
            state.orchestrator_state.daemon_connected = true;
            log::info!("Orchestrator mode refreshed from daemon: {:?}", mode);
            Task::none()
        }
        ModeSetResult(result) => {
            match result {
                Ok(mode) => {
                    state.orchestrator_state.mode = mode;
                    state.orchestrator_state.mode_since = std::time::Instant::now();
                    state.orchestrator_state.engine.set_mode(mode);
                    state.orchestrator_state.daemon_connected = true;
                    log::info!("Orchestrator mode set via D-Bus: {:?}", mode);
                }
                Err(e) => {
                    log::error!("Failed to set orchestrator mode: {}", e);
                }
            }
            Task::none()
        }
        DaemonConnectionChanged(connected) => {
            state.orchestrator_state.daemon_connected = connected;
            log::info!("Orchestrator daemon connected: {}", connected);
            Task::none()
        }
        ApproveTriage(id) => {
            // Find the dialog and determine the response to send
            let response = if let Some(item) = state
                .orchestrator_state
                .engine
                .triage_queue()
                .iter()
                .find(|item| item.dialog.id == id)
            {
                // Use suggested response if available, otherwise pick first option
                let selection = item.suggested_response.clone().unwrap_or_else(|| {
                    item.dialog
                        .options
                        .as_ref()
                        .and_then(|opts| opts.first())
                        .map(|opt| opt.value.clone())
                        .unwrap_or_else(|| "continue".to_string())
                });
                log::info!("Approving triage item: {} -> {}", id, selection);
                Some((id.clone(), selection))
            } else {
                log::warn!("Triage item not found for approve: {}", id);
                None
            };

            // Remove from queue optimistically
            state.orchestrator_state.engine.remove_from_triage(&id);

            // Send response to daemon
            if let Some((dialog_id, selection)) = response {
                let client = state.cli_agents_http.clone();
                let id_for_result = dialog_id.clone();
                Task::perform(
                    async move {
                        client
                            .respond_to_dialog(&dialog_id, &selection, Some("Approved via triage".to_string()))
                            .await
                    },
                    move |result| {
                        Message::OrchestratorAction(OrchestratorMessage::TriageResponseResult(
                            id_for_result.clone(),
                            result,
                        ))
                    },
                )
            } else {
                Task::none()
            }
        }
        DeclineTriage(id) => {
            log::info!("Declining triage item: {}", id);

            // Find the dialog to get cancel option if available
            let cancel_selection = state
                .orchestrator_state
                .engine
                .triage_queue()
                .iter()
                .find(|item| item.dialog.id == id)
                .and_then(|item| {
                    // Try to find a cancel/decline option
                    item.dialog.options.as_ref().and_then(|opts| {
                        opts.iter()
                            .find(|opt| {
                                let v = opt.value.to_lowercase();
                                v.contains("cancel")
                                    || v.contains("decline")
                                    || v.contains("no")
                                    || v.contains("stop")
                            })
                            .map(|opt| opt.value.clone())
                    })
                })
                .unwrap_or_else(|| "cancelled".to_string());

            // Remove from queue optimistically
            state.orchestrator_state.engine.remove_from_triage(&id);

            // Send response to daemon
            let client = state.cli_agents_http.clone();
            let dialog_id = id.clone();
            let id_for_result = id.clone();
            Task::perform(
                async move {
                    client
                        .respond_to_dialog(&dialog_id, &cancel_selection, Some("Declined via triage".to_string()))
                        .await
                },
                move |result| {
                    Message::OrchestratorAction(OrchestratorMessage::TriageResponseResult(
                        id_for_result.clone(),
                        result,
                    ))
                },
            )
        }
        ClaimDialog(id) => {
            log::info!("User claiming dialog: {}", id);
            state.orchestrator_state.engine.remove_from_triage(&id);
            Task::none()
        }
        SetTriageState(id, triage_state) => {
            state
                .orchestrator_state
                .engine
                .set_triage_state(&id, triage_state);
            Task::none()
        }
        ToggleHistory => {
            state.orchestrator_state.show_history = !state.orchestrator_state.show_history;
            Task::none()
        }
        UndoLastDecision => {
            if let Some(record) = state.orchestrator_state.engine.undo_last() {
                log::info!(
                    "Undoing decision: {} (was: {})",
                    record.dialog_id,
                    record.response
                );
                
                // Decrement auto-handled count if it was auto-handled
                if record.auto_handled && state.orchestrator_state.stats.auto_handled_today > 0 {
                    state.orchestrator_state.stats.auto_handled_today -= 1;
                }
                
                // Re-create the PendingDialog from the record and add to pending for user review
                let pending = continuum_studio_iced::cli_agents::PendingDialog {
                    id: record.dialog_id.clone(),
                    title: format!("[UNDO] {}", record.dialog_id),
                    prompt: format!(
                        "Previous response '{}' was undone.\nOriginal reasoning: {}",
                        record.response, record.reasoning
                    ),
                    dialog_type: "choice".to_string(),
                    options: None,
                    created_at: None,
                    agent_id: record.agent_id.clone(),
                    source: record.source.clone(),
                    priority: record.priority.clone(),
                    workspace: record.workspace.clone(),
                    orchestrator_id: None,
                };
                
                // Add to pending dialogs for manual review
                state.cli_agents_state.pending_dialogs.push(pending);
                state.orchestrator_state.stats.user_handled_today += 1;
            } else {
                log::info!("No decisions available to undo");
            }
            Task::none()
        }
        ClearTriage => {
            log::info!("Clearing triage queue");
            let queue_ids: Vec<String> = state
                .orchestrator_state
                .engine
                .triage_queue()
                .iter()
                .map(|item| item.dialog.id.clone())
                .collect();
            for id in queue_ids {
                state.orchestrator_state.engine.remove_from_triage(&id);
            }
            Task::none()
        }
        RefreshMode => {
            log::info!("Refreshing orchestrator mode from daemon");
            Task::perform(
                async {
                    use continuum_studio_iced::dialog_client::DialogClient;
                    let mut client = DialogClient::new();
                    if client.connect().await.is_ok() {
                        client.get_orchestrator_mode().await.ok()
                    } else {
                        None
                    }
                },
                |result| {
                    if let Some(mode) = result {
                        Message::OrchestratorAction(ModeRefreshed(mode))
                    } else {
                        Message::OrchestratorAction(DaemonConnectionChanged(false))
                    }
                },
            )
        }
        TriageResponseResult(dialog_id, result) => {
            match result {
                Ok(()) => {
                    log::info!("Triage response sent successfully for dialog: {}", dialog_id);
                }
                Err(e) => {
                    log::error!("Failed to send triage response for {}: {}", dialog_id, e);
                }
            }
            Task::none()
        }
        RequestModeChange(mode) => {
            log::info!("Requesting mode change to {:?} (pending confirmation)", mode);
            state.orchestrator_state.pending_mode_change = Some(mode);
            Task::none()
        }
        ConfirmModeChange => {
            if let Some(mode) = state.orchestrator_state.pending_mode_change.take() {
                log::info!("Confirming mode change to {:?}", mode);
                // OrchestratorMode is Copy, so we can use it directly in the async block
                Task::perform(
                    async move {
                        use continuum_studio_iced::dialog_client::DialogClient;
                        let mut client = DialogClient::new();
                        if client.connect().await.is_ok() {
                            client.set_orchestrator_mode(mode).await
                        } else {
                            Err("Failed to connect to daemon".to_string())
                        }
                    },
                    |result| Message::OrchestratorAction(ModeSetResult(result)),
                )
            } else {
                Task::none()
            }
        }
        CancelModeChange => {
            log::info!("Cancelled mode change");
            state.orchestrator_state.pending_mode_change = None;
            Task::none()
        }
        SelectDecision(decision_id) => {
            state.orchestrator_state.selected_decision = decision_id;
            Task::none()
        }
    }
}

/// Handle orchestrator WebSocket events (real-time dialog updates)
fn handle_orchestrator_ws_event(
    state: &mut ContinuumStudio,
    event: continuum_studio_iced::cli_agents_client::OrchestratorWsEvent,
) -> Task<Message> {
    use continuum_studio_iced::cli_agents_client::OrchestratorWsEvent;
    use continuum_studio_iced::decision_engine::{DecisionResult, TriageState};

    match event {
        OrchestratorWsEvent::Connected => {
            log::info!("Orchestrator WebSocket connected");
            state.orchestrator_state.daemon_connected = true;
            Task::none()
        }
        OrchestratorWsEvent::DialogCreated { dialog } => {
            let pending_dialog = (*dialog).into_pending_dialog();
            log::info!(
                "New dialog from orchestrator WS: {} (priority: {:?})",
                pending_dialog.id,
                pending_dialog.priority
            );

            // Evaluate the dialog using the decision engine
            let result = state.orchestrator_state.engine.evaluate(&pending_dialog);
            match result {
                DecisionResult::AutoHandle { response, reasoning } => {
                    log::info!("Auto-handling dialog {}: {}", pending_dialog.id, reasoning);
                    // Update stats
                    state.orchestrator_state.stats.dialogs_today += 1;
                    state.orchestrator_state.stats.auto_handled_today += 1;

                    // Record the decision for history and undo
                    let timestamp = std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|d| d.as_secs())
                        .unwrap_or(0);
                    state.orchestrator_state.engine.record_decision(
                        continuum_studio_iced::decision_engine::DecisionRecord {
                            dialog_id: pending_dialog.id.clone(),
                            agent_id: pending_dialog.agent_id.clone(),
                            workspace: pending_dialog.workspace.clone(),
                            source: pending_dialog.source.clone(),
                            priority: pending_dialog.priority.clone(),
                            response: response.clone(),
                            reasoning: reasoning.clone(),
                            auto_handled: true,
                            timestamp,
                            mode: state.orchestrator_state.mode.as_str().to_string(),
                            undoable: true,
                        },
                    );

                    // Send auto response
                    let dialog_id = pending_dialog.id.clone();
                    let client = state.cli_agents_http.clone();
                    Task::perform(
                        async move {
                            client
                                .respond_to_dialog(&dialog_id, &response, Some(reasoning))
                                .await
                        },
                        |result| {
                            if let Err(e) = result {
                                log::error!("Failed to auto-respond to dialog: {}", e);
                            }
                            Message::NoOp
                        },
                    )
                }
                DecisionResult::RequireUser { reasoning } => {
                    log::info!("Dialog requires user: {}", reasoning);
                    state.orchestrator_state.stats.dialogs_today += 1;
                    // Add to CLI agents pending dialogs for display
                    state.cli_agents_state.pending_dialogs.push(pending_dialog);
                    Task::none()
                }
                DecisionResult::Triage {
                    state: triage_state,
                    suggested_response,
                    reasoning,
                    timeout,
                } => {
                    log::info!(
                        "Dialog triaged: {:?} - {} (timeout: {:?})",
                        triage_state,
                        reasoning,
                        timeout
                    );
                    state.orchestrator_state.stats.dialogs_today += 1;
                    // Add to triage queue with full parameters
                    state.orchestrator_state.add_to_triage_full(
                        pending_dialog.clone(),
                        triage_state,
                        reasoning.clone(),
                        suggested_response,
                        timeout,
                    );
                    // Also add to pending dialogs list for display
                    state.cli_agents_state.pending_dialogs.push(pending_dialog);
                    Task::none()
                }
            }
        }
        OrchestratorWsEvent::DialogAnswered { dialog_id } => {
            log::info!("Dialog answered: {}", dialog_id);
            // Remove from triage queue if present
            state.orchestrator_state.engine.remove_from_triage(&dialog_id);
            // Remove from pending dialogs
            state
                .cli_agents_state
                .pending_dialogs
                .retain(|d| d.id != dialog_id);
            Task::none()
        }
        OrchestratorWsEvent::DialogEscalated { dialog_id } => {
            log::info!("Dialog escalated: {}", dialog_id);
            // Update triage state to Manual (requires user)
            state
                .orchestrator_state
                .engine
                .set_triage_state(&dialog_id, TriageState::Manual);
            Task::none()
        }
        OrchestratorWsEvent::Ping => {
            // Heartbeat - no action needed
            Task::none()
        }
    }
}

/// Handle keyboard shortcuts
fn handle_keyboard_shortcut(
    state: &mut ContinuumStudio,
    shortcut: KeyboardShortcut,
) -> Task<Message> {
    use OrchestratorMessage::*;

    // Only process orchestrator shortcuts when on the Orchestrator tab
    let on_orchestrator_tab =
        state.current_view == View::Cursor && state.cursor_tab == CursorTab::Orchestrator;

    match shortcut {
        KeyboardShortcut::SetModeUserActive => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Set mode to UserActive (Ctrl+1)");
                return handle_orchestrator_message(
                    state,
                    SetMode(OrchestratorMode::UserActive),
                );
            }
            Task::none()
        }
        KeyboardShortcut::SetModeUserDelegate => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Set mode to UserDelegate (Ctrl+2)");
                return handle_orchestrator_message(
                    state,
                    SetMode(OrchestratorMode::UserDelegate),
                );
            }
            Task::none()
        }
        KeyboardShortcut::SetModeSpectator => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Request Spectator mode (Ctrl+3)");
                return handle_orchestrator_message(
                    state,
                    RequestModeChange(OrchestratorMode::Spectator),
                );
            }
            Task::none()
        }
        KeyboardShortcut::SetModeAutonomous => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Request Autonomous mode (Ctrl+4)");
                return handle_orchestrator_message(
                    state,
                    RequestModeChange(OrchestratorMode::Autonomous),
                );
            }
            Task::none()
        }
        KeyboardShortcut::ToggleHistory => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Toggle history (Ctrl+H)");
                return handle_orchestrator_message(state, ToggleHistory);
            }
            Task::none()
        }
        KeyboardShortcut::UndoDecision => {
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Undo decision (Ctrl+Z)");
                return handle_orchestrator_message(state, UndoLastDecision);
            }
            Task::none()
        }
        KeyboardShortcut::RefreshView => {
            // Refresh based on current view
            if on_orchestrator_tab {
                log::info!("Keyboard shortcut: Refresh mode (Ctrl+R)");
                return handle_orchestrator_message(state, RefreshMode);
            }
            Task::none()
        }
    }
}

/// Process triage timeouts - auto-handle items that have timed out
fn process_triage_timeouts(state: &mut ContinuumStudio) -> Task<Message> {
    profile_span!("process_triage_timeouts", 5);
    // Get items that have timed out
    let expired = state.orchestrator_state.engine.process_triage_timeouts();

    if expired.is_empty() {
        return Task::none();
    }

    // Process each expired item
    let mut tasks = Vec::new();
    for (dialog, triage_state, response) in expired {
        match triage_state {
            continuum_studio_iced::decision_engine::TriageState::AutoApprove => {
                log::info!(
                    "Triage timeout: Auto-approving dialog {} with response: {}",
                    dialog.id,
                    response
                );
                state.orchestrator_state.stats.auto_handled_today += 1;

                // Record the decision for history and undo
                let timestamp = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                state.orchestrator_state.engine.record_decision(
                    continuum_studio_iced::decision_engine::DecisionRecord {
                        dialog_id: dialog.id.clone(),
                        agent_id: dialog.agent_id.clone(),
                        workspace: dialog.workspace.clone(),
                        source: dialog.source.clone(),
                        priority: dialog.priority.clone(),
                        response: response.clone(),
                        reasoning: "Auto-approved on triage timeout".to_string(),
                        auto_handled: true,
                        timestamp,
                        mode: state.orchestrator_state.mode.as_str().to_string(),
                        undoable: true,
                    },
                );

                let dialog_id = dialog.id.clone();
                let client = state.cli_agents_http.clone();
                let response_clone = response.clone();
                tasks.push(Task::perform(
                    async move {
                        client
                            .respond_to_dialog(
                                &dialog_id,
                                &response_clone,
                                Some("Auto-approved on timeout".to_string()),
                            )
                            .await
                    },
                    |result| {
                        if let Err(e) = result {
                            log::error!("Failed to auto-respond on timeout: {}", e);
                        }
                        Message::NoOp
                    },
                ));

                // Remove from pending dialogs
                state
                    .cli_agents_state
                    .pending_dialogs
                    .retain(|d| d.id != dialog.id);
            }
            continuum_studio_iced::decision_engine::TriageState::AutoDecline => {
                log::info!("Triage timeout: Auto-declining dialog {}", dialog.id);
                state.orchestrator_state.stats.auto_handled_today += 1;

                // Find a cancel/decline option
                let decline_response = dialog
                    .options
                    .as_ref()
                    .and_then(|opts| {
                        opts.iter()
                            .find(|opt| {
                                let v = opt.value.to_lowercase();
                                v.contains("cancel")
                                    || v.contains("decline")
                                    || v.contains("no")
                                    || v.contains("stop")
                            })
                            .map(|opt| opt.value.clone())
                    })
                    .unwrap_or_else(|| "cancelled".to_string());

                // Record the decision for history and undo
                let timestamp = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_secs())
                    .unwrap_or(0);
                state.orchestrator_state.engine.record_decision(
                    continuum_studio_iced::decision_engine::DecisionRecord {
                        dialog_id: dialog.id.clone(),
                        agent_id: dialog.agent_id.clone(),
                        workspace: dialog.workspace.clone(),
                        source: dialog.source.clone(),
                        priority: dialog.priority.clone(),
                        response: decline_response.clone(),
                        reasoning: "Auto-declined on triage timeout".to_string(),
                        auto_handled: true,
                        timestamp,
                        mode: state.orchestrator_state.mode.as_str().to_string(),
                        undoable: true,
                    },
                );

                let dialog_id = dialog.id.clone();
                let client = state.cli_agents_http.clone();
                tasks.push(Task::perform(
                    async move {
                        client
                            .respond_to_dialog(
                                &dialog_id,
                                &decline_response,
                                Some("Auto-declined on timeout".to_string()),
                            )
                            .await
                    },
                    |result| {
                        if let Err(e) = result {
                            log::error!("Failed to auto-decline on timeout: {}", e);
                        }
                        Message::NoOp
                    },
                ));

                // Remove from pending dialogs
                state
                    .cli_agents_state
                    .pending_dialogs
                    .retain(|d| d.id != dialog.id);
            }
            _ => {
                // Manual or Paused - shouldn't happen but log it
                log::warn!(
                    "Unexpected triage state {:?} in timeout processing",
                    triage_state
                );
            }
        }
    }

    if tasks.is_empty() {
        Task::none()
    } else {
        Task::batch(tasks)
    }
}

/// Handle parked agents panel messages
fn handle_parked_agent_message(
    state: &mut ContinuumStudio,
    msg: ParkedMessage,
) -> Task<Message> {
    let task = state.parked_agents_state.update(msg);

    match task {
        Some(ParkedAgentTask::FetchParkedAgents) => {
            let client = state.parked_agents_http.clone();
            Task::perform(
                async move { client.fetch_parked_agents().await },
                |result| match result {
                    Ok(agents) => {
                        log::info!("Loaded {} parked agents", agents.len());
                        Message::ParkedAgentAction(ParkedMessage::AgentsLoaded(agents))
                    }
                    Err(e) => {
                        log::error!("Failed to fetch parked agents: {}", e);
                        Message::ParkedAgentAction(ParkedMessage::Error(e))
                    }
                },
            )
        }
        Some(ParkedAgentTask::UnparkAndAssignTask {
            agent_id,
            task_description,
        }) => {
            let client = state.parked_agents_http.clone();
            let agent_id_clone = agent_id.clone();
            Task::perform(
                async move {
                    client
                        .unpark_and_assign(&agent_id_clone, &task_description)
                        .await
                },
                move |result| match result {
                    Ok(()) => {
                        log::info!("Agent unparked and task assigned: {}", agent_id);
                        Message::ParkedAgentAction(ParkedMessage::AgentUnparked(agent_id))
                    }
                    Err(e) => {
                        log::error!("Failed to unpark agent: {}", e);
                        Message::ParkedAgentAction(ParkedMessage::Error(e))
                    }
                },
            )
        }
        None => Task::none(),
    }
}

// ============================================================================
// Multi-Window Helpers
// ============================================================================

/// Handle opening a new window
fn handle_open_window(_state: &mut ContinuumStudio, window_type: WindowType) -> Task<Message> {
    let size = match window_type {
        WindowType::Main => iced::Size::new(1280.0, 800.0),
        WindowType::TaskQueue => iced::Size::new(400.0, 600.0),
        WindowType::DialogPanel => iced::Size::new(500.0, 400.0),
        WindowType::TiledPanel => iced::Size::new(800.0, 600.0),
    };

    let settings = window::Settings {
        size,
        resizable: true,
        decorations: true,
        ..Default::default()
    };

    let (id, open_task) = window::open(settings);

    Task::batch([
        open_task.discard(),
        Task::done(Message::WindowOpened(id, window_type)),
    ])
}

/// Handle task queue messages
fn handle_task_queue_message(state: &mut ContinuumStudio, msg: TaskQueueMsg) -> Task<Message> {
    use continuum_studio_iced::task_queue_client::Priority;

    match msg {
        TaskQueueMsg::Connected => {
            state.task_queue_connected = true;
            log::info!("Task queue WebSocket connected");
            Task::none()
        }
        TaskQueueMsg::Disconnected => {
            state.task_queue_connected = false;
            log::warn!("Task queue WebSocket disconnected");
            Task::none()
        }
        TaskQueueMsg::InitialState {
            tasks,
            stats,
            current_task,
        } => {
            let total = tasks.len();
            let completed = tasks
                .iter()
                .filter(|t| {
                    t.status == continuum_studio_iced::task_queue_client::TaskStatus::Completed
                })
                .count();
            let cancelled = tasks
                .iter()
                .filter(|t| {
                    t.status == continuum_studio_iced::task_queue_client::TaskStatus::Cancelled
                })
                .count();
            let active = total - completed - cancelled;
            eprintln!("[task_queue_update] InitialState: {} total ({} active, {} completed, {} cancelled)", total, active, completed, cancelled);
            log::info!(
                "Task queue loaded: {} total ({} active, {} completed, {} cancelled)",
                total,
                active,
                completed,
                cancelled
            );
            state.task_queue_tasks = tasks;
            state.task_queue_stats = stats;
            state.task_queue_current = current_task;
            Task::none()
        }
        TaskQueueMsg::TaskAdded(task) => {
            state.task_queue_tasks.push(task);
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::TaskUpdated(task) => {
            if let Some(existing) = state.task_queue_tasks.iter_mut().find(|t| t.id == task.id) {
                *existing = task.clone();
            }
            // Update current task if it was the updated one
            if state.task_queue_current.as_ref().map(|t| &t.id) == Some(&task.id) {
                state.task_queue_current = Some(task);
            }
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::TaskStarted(task) => {
            if let Some(existing) = state.task_queue_tasks.iter_mut().find(|t| t.id == task.id) {
                *existing = task.clone();
            }
            state.task_queue_current = Some(task);
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::TaskCompleted(task) => {
            if let Some(existing) = state.task_queue_tasks.iter_mut().find(|t| t.id == task.id) {
                *existing = task.clone();
            }
            if state.task_queue_current.as_ref().map(|t| &t.id) == Some(&task.id) {
                state.task_queue_current = None;
            }
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::TaskRemoved(id) => {
            state.task_queue_tasks.retain(|t| t.id != id);
            if state.task_queue_current.as_ref().map(|t| &t.id) == Some(&id) {
                state.task_queue_current = None;
            }
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::Error(e) => {
            log::error!("Task queue error: {}", e);
            Task::none()
        }
        TaskQueueMsg::QuickAddChanged(text) => {
            state.task_queue_input = text;
            Task::none()
        }
        TaskQueueMsg::QuickAddSubmit => {
            if state.task_queue_input.trim().is_empty() {
                return Task::none();
            }
            let content = state.task_queue_input.clone();
            state.task_queue_input.clear();
            let http = state.task_queue_http.clone();

            Task::perform(
                async move { http.add_task(&content, Priority::Medium, None).await },
                |result| Message::TaskQueueAction(TaskQueueMsg::ApiResult(result)),
            )
        }
        TaskQueueMsg::StartTask(task_id) => {
            let http = state.task_queue_http.clone();
            let agent_id = "continuum-studio".to_string();

            Task::perform(
                async move { http.start_task(&task_id, &agent_id).await },
                |result| Message::TaskQueueAction(TaskQueueMsg::ApiResult(result)),
            )
        }
        TaskQueueMsg::CompleteCurrentTask => {
            if let Some(current) = &state.task_queue_current {
                let task_id = current.id.clone();
                let http = state.task_queue_http.clone();

                Task::perform(
                    async move { http.complete_task(&task_id, None).await },
                    |result| Message::TaskQueueAction(TaskQueueMsg::ApiResult(result)),
                )
            } else {
                Task::none()
            }
        }
        TaskQueueMsg::ChangePriority(task_id, priority) => {
            let http = state.task_queue_http.clone();

            Task::perform(
                async move { http.update_priority(&task_id, priority).await },
                |result| Message::TaskQueueAction(TaskQueueMsg::ApiResult(result)),
            )
        }
        TaskQueueMsg::DeleteTask(task_id) => {
            let http = state.task_queue_http.clone();
            let task_id_owned = task_id.clone();

            Task::perform(
                async move { http.delete_task(&task_id).await },
                move |result| match result {
                    Ok(()) => Message::TaskQueueAction(TaskQueueMsg::TaskDeleted(task_id_owned)),
                    Err(e) => Message::TaskQueueAction(TaskQueueMsg::ApiResult(Err(e))),
                },
            )
        }
        TaskQueueMsg::CancelTask(task_id) => {
            let http = state.task_queue_http.clone();

            Task::perform(
                async move { http.cancel_task(&task_id, Some("Cancelled from UI")).await },
                |result| Message::TaskQueueAction(TaskQueueMsg::ApiResult(result)),
            )
        }
        TaskQueueMsg::ToggleTaskDetail(task_id) => {
            if state.task_queue_selected.as_ref() == Some(&task_id) {
                state.task_queue_selected = None;
            } else {
                state.task_queue_selected = Some(task_id);
            }
            state.task_queue_editing = None;
            state.task_queue_subtask_input.clear();
            Task::none()
        }
        TaskQueueMsg::ToggleEditMode(task_id) => {
            if state.task_queue_editing.as_ref() == Some(&task_id) {
                state.task_queue_editing = None;
            } else {
                state.task_queue_editing = Some(task_id);
            }
            Task::none()
        }
        TaskQueueMsg::SubtaskInputChanged(text) => {
            state.task_queue_subtask_input = text;
            Task::none()
        }
        TaskQueueMsg::SubmitSubtask(parent_id) => {
            let content = state.task_queue_subtask_input.clone();
            if content.trim().is_empty() {
                return Task::none();
            }
            state.task_queue_subtask_input.clear();
            let http = state.task_queue_http.clone();
            Task::perform(
                async move {
                    http.add_subtask(
                        &parent_id,
                        &content,
                        continuum_studio_iced::task_queue_client::Priority::Medium,
                    )
                    .await
                },
                |result| Message::TaskQueueAction(TaskQueueMsg::SubtaskAdded(result)),
            )
        }
        TaskQueueMsg::SubtaskAdded(result) => {
            match result {
                Ok(_task) => {
                    // Task will come through WebSocket, no need to manually add
                }
                Err(e) => {
                    log::error!("Failed to add subtask: {}", e);
                }
            }
            Task::none()
        }
        TaskQueueMsg::NewTaskContentChanged(text) => {
            state.new_task_content = text;
            Task::none()
        }
        TaskQueueMsg::NewTaskPriorityChanged(priority) => {
            state.new_task_priority = priority;
            Task::none()
        }
        TaskQueueMsg::NewTaskProjectChanged(text) => {
            state.new_task_project = text;
            Task::none()
        }
        TaskQueueMsg::NewTaskNotesChanged(text) => {
            state.new_task_notes = text;
            Task::none()
        }
        TaskQueueMsg::NewTaskSubtaskInputChanged(text) => {
            state.new_task_subtask_input = text;
            Task::none()
        }
        TaskQueueMsg::NewTaskAddSubtask => {
            let text = state.new_task_subtask_input.trim().to_string();
            if !text.is_empty() {
                state.new_task_subtasks.push(text);
                state.new_task_subtask_input.clear();
            }
            Task::none()
        }
        TaskQueueMsg::NewTaskRemoveSubtask(index) => {
            if index < state.new_task_subtasks.len() {
                state.new_task_subtasks.remove(index);
            }
            Task::none()
        }
        TaskQueueMsg::NewTaskTogglePrereq(task_id) => {
            if let Some(pos) = state.new_task_prereqs.iter().position(|id| id == &task_id) {
                state.new_task_prereqs.remove(pos);
            } else {
                state.new_task_prereqs.push(task_id);
            }
            Task::none()
        }
        TaskQueueMsg::SubmitNewTask => {
            let content = state.new_task_content.trim().to_string();
            if content.is_empty() {
                return Task::none();
            }
            let priority = state.new_task_priority;
            let project = if state.new_task_project.trim().is_empty() {
                None
            } else {
                Some(state.new_task_project.clone())
            };
            let notes = state.new_task_notes.clone();
            let subtasks = state.new_task_subtasks.clone();
            let prereqs = state.new_task_prereqs.clone();

            // Clear the form
            state.new_task_content.clear();
            state.new_task_priority = continuum_studio_iced::task_queue_client::Priority::Medium;
            state.new_task_project.clear();
            state.new_task_notes.clear();
            state.new_task_subtasks.clear();
            state.new_task_subtask_input.clear();
            state.new_task_prereqs.clear();

            // Switch back to tasks panel
            state.task_queue_panel = TaskQueuePanel::Tasks;

            let http = state.task_queue_http.clone();

            Task::perform(
                async move {
                    // Create the main task
                    let task = http
                        .add_task(&content, priority, project.as_deref())
                        .await?;

                    // Update notes if provided
                    if !notes.trim().is_empty() {
                        let _ = http.update_notes(&task.id, &notes).await;
                    }

                    // Add subtasks
                    for subtask_content in &subtasks {
                        let _ = http
                            .add_subtask(
                                &task.id,
                                subtask_content,
                                continuum_studio_iced::task_queue_client::Priority::Medium,
                            )
                            .await;
                    }

                    // Add prereqs/blockers
                    for prereq_id in &prereqs {
                        let _ = http.add_blocker(&task.id, prereq_id).await;
                    }

                    Ok(task)
                },
                |result| Message::TaskQueueAction(TaskQueueMsg::NewTaskCreated(result)),
            )
        }
        TaskQueueMsg::NewTaskCreated(result) => {
            match result {
                Ok(task) => {
                    log::info!("New task created: {} - {}", task.id, task.content);
                }
                Err(e) => {
                    log::error!("Failed to create task: {}", e);
                }
            }
            Task::none()
        }
        TaskQueueMsg::TaskDeleted(task_id) => {
            state.task_queue_tasks.retain(|t| t.id != task_id);
            if state.task_queue_current.as_ref().map(|t| &t.id) == Some(&task_id) {
                state.task_queue_current = None;
            }
            update_task_queue_stats(state);
            Task::none()
        }
        TaskQueueMsg::ApiResult(result) => {
            match result {
                Ok(task) => {
                    // Update local state with returned task
                    if let Some(existing) =
                        state.task_queue_tasks.iter_mut().find(|t| t.id == task.id)
                    {
                        *existing = task.clone();
                    } else {
                        state.task_queue_tasks.push(task.clone());
                    }
                    // Update current task if applicable
                    if task.status
                        == continuum_studio_iced::task_queue_client::TaskStatus::InProgress
                    {
                        state.task_queue_current = Some(task);
                    } else if state.task_queue_current.as_ref().map(|t| &t.id) == Some(&task.id) {
                        state.task_queue_current = None;
                    }
                    update_task_queue_stats(state);
                }
                Err(e) => {
                    log::error!("Task queue API error: {}", e);
                }
            }
            Task::none()
        }
        TaskQueueMsg::SwitchPanel(panel) => {
            state.task_queue_panel = panel;
            log::debug!("Switched task queue panel to {:?}", state.task_queue_panel);
            Task::none()
        }
        TaskQueueMsg::SetLayout(layout) => {
            state.task_queue_layout = layout;
            log::debug!(
                "Switched task queue layout to {:?}",
                state.task_queue_layout
            );
            Task::none()
        }
        TaskQueueMsg::SetSecondaryPanel(panel) => {
            state.task_queue_secondary_panel = panel;
            log::debug!(
                "Set secondary panel to {:?}",
                state.task_queue_secondary_panel
            );
            Task::none()
        }
    }
}

/// Update task queue stats from current tasks
fn update_task_queue_stats(state: &mut ContinuumStudio) {
    use continuum_studio_iced::task_queue_client::TaskStatus;

    state.task_queue_stats.total = state.task_queue_tasks.len();
    state.task_queue_stats.pending = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Pending || t.status == TaskStatus::Claimed)
        .count();
    state.task_queue_stats.in_progress = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::InProgress)
        .count();
    state.task_queue_stats.completed = state
        .task_queue_tasks
        .iter()
        .filter(|t| t.status == TaskStatus::Completed)
        .count();
}

/// Handle dialog daemon messages
fn handle_dialog_message(state: &mut ContinuumStudio, msg: DialogMsg) -> Task<Message> {
    match msg {
        DialogMsg::DaemonConnected(connected) => {
            state.dialog_daemon_connected = connected;
            if connected {
                log::info!("Dialog daemon connected");
            } else {
                log::warn!("Dialog daemon disconnected");
            }
            Task::none()
        }
        DialogMsg::HoldModeChanged(enabled) => {
            state.dialog_hold_mode = enabled;
            log::info!("Dialog hold mode: {}", enabled);
            Task::none()
        }
        DialogMsg::ToggleHoldMode => {
            // Fire-and-forget async toggle
            Task::perform(
                async {
                    use continuum_studio_iced::dialog_client::DialogClient;
                    let mut client = DialogClient::new();
                    if client.connect().await.is_ok() {
                        match client.toggle_hold_mode().await {
                            Ok(new_state) => Some(new_state),
                            Err(e) => {
                                log::error!("Failed to toggle hold mode: {}", e);
                                None
                            }
                        }
                    } else {
                        None
                    }
                },
                |result| {
                    if let Some(enabled) = result {
                        Message::DialogAction(DialogMsg::HoldModeChanged(enabled))
                    } else {
                        Message::DialogAction(DialogMsg::Error("Failed to toggle hold mode".into()))
                    }
                },
            )
        }
        DialogMsg::Error(e) => {
            log::error!("Dialog daemon error: {}", e);
            Task::none()
        }
    }
}

/// View for CLI agent management (headless Cursor CLI orchestration)
fn view_cli_agents(state: &ContinuumStudio) -> Element<'_, Message> {
    profile_span!("view_cli_agents");
    view_cli_agents_tab(&state.cli_agents_state, Message::CLIAgentAction)
}

/// View for orchestrator panel (mode switching, dialog triage)
fn view_orchestrator(state: &ContinuumStudio) -> Element<'_, Message> {
    profile_span!("view_orchestrator");
    view_orchestrator_panel(&state.orchestrator_state, Message::OrchestratorAction)
}

/// View for parked agents panel
fn view_agent_activity(state: &ContinuumStudio) -> Element<'_, Message> {
    view_activity_feed(&state.agent_activity_state, Message::AgentActivityFeed)
}

fn view_parked_agents(state: &ContinuumStudio) -> Element<'_, Message> {
    view_parked_agents_panel(&state.parked_agents_state, Message::ParkedAgentAction)
}

/// View for sub-agent monitoring panel
fn view_subagents(state: &ContinuumStudio) -> Element<'_, Message> {
    let subagent = &state.subagent_state;

    // Header
    let header = row![
        text("🤖 Sub-agent Monitor").size(20),
        Space::new().width(Length::Fill),
        button(text("⟳ Refresh").size(12))
            .padding([6, 12])
            .on_press(Message::SubagentAction(SubagentMessage::Refresh))
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.2, 0.4, 0.6
                ))),
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
            text(if subagent.service_available {
                "●"
            } else {
                "○"
            })
            .color(health_color),
            text(if subagent.service_available {
                "Connected to Terminal Monitor"
            } else {
                "Terminal Monitor Unavailable"
            })
            .size(12)
            .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        ]
        .spacing(8),
    )
    .padding([8, 12])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.14,
        ))),
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
        stat_card(
            "Error Rate",
            &format!("{:.1}%", stats.error_rate * 100.0),
            "⚠️"
        ),
    ]
    .spacing(12);

    // Running commands section
    let running_section = if subagent.running_commands.is_empty() {
        container(
            text("No commands currently running")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(16)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.1, 0.12,
            ))),
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
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.1, 0.1, 0.12,
                ))),
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
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        )
        .padding(16)
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.1, 0.12,
            ))),
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
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.1, 0.1, 0.12,
                ))),
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
            .spacing(8),
        )
        .padding([8, 12])
        .width(Length::Fill)
        .style(|_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.25, 0.18, 0.1,
            ))),
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
        .padding(4),
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
        .align_x(Alignment::Center),
    )
    .padding([12, 16])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.1, 0.1, 0.12,
        ))),
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
fn subagent_command_row_impl(
    cmd: &CommandRecord,
    show_codename: bool,
) -> Element<'static, Message> {
    use continuum_studio_iced::subagents::{
        format_command, format_elapsed, format_exit_code, generate_codename,
    };

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
            .spacing(4),
        )
        .padding([2, 6])
        .style(move |_theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.15, 0.18, 0.25,
            ))),
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
            text(exit_text).size(10).color(iced::Color::from_rgb(
                exit_color.0,
                exit_color.1,
                exit_color.2
            )),
        ]
        .spacing(8)
        .align_y(Alignment::Center),
    )
    .padding([6, 10])
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.08, 0.08, 0.1,
        ))),
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

// ============================================================================
// Activity Feed Handler & View
// ============================================================================

fn handle_activity_feed_message(
    state: &mut ContinuumStudio,
    msg: ActivityFeedMsg,
) -> Task<Message> {
    match msg {
        ActivityFeedMsg::Connected => {
            state.activity_feed_connected = true;
            log::info!("Activity feed connected");
            Task::none()
        }
        ActivityFeedMsg::Disconnected => {
            state.activity_feed_connected = false;
            log::warn!("Activity feed disconnected");
            Task::none()
        }
        ActivityFeedMsg::InitialState { entries, stats } => {
            state.activity_feed_entries = entries;
            state.activity_feed_stats = stats;
            Task::none()
        }
        ActivityFeedMsg::EntryAdded(entry) => {
            // Insert at front (most recent first)
            state.activity_feed_entries.insert(0, entry);
            // Cap at 200 entries in the UI
            if state.activity_feed_entries.len() > 200 {
                state.activity_feed_entries.truncate(200);
            }
            Task::none()
        }
        ActivityFeedMsg::Error(e) => {
            log::error!("Activity feed error: {}", e);
            Task::none()
        }
        ActivityFeedMsg::ToggleEntry(id) => {
            if state.activity_feed_expanded.as_deref() == Some(&id) {
                state.activity_feed_expanded = None;
            } else {
                state.activity_feed_expanded = Some(id);
            }
            Task::none()
        }
        ActivityFeedMsg::SetSourceFilter(filter) => {
            state.activity_feed_source_filter = filter;
            Task::none()
        }
        ActivityFeedMsg::TriggerGitPoll => {
            let http = state.activity_feed_http.clone();
            Task::perform(async move { http.trigger_git_poll().await }, |result| {
                Message::ActivityFeedAction(ActivityFeedMsg::GitPollDone(result))
            })
        }
        ActivityFeedMsg::GitPollDone(_result) => Task::none(),
        ActivityFeedMsg::RefreshStats => {
            let http = state.activity_feed_http.clone();
            Task::perform(async move { http.get_stats().await }, |result| {
                Message::ActivityFeedAction(ActivityFeedMsg::StatsRefreshed(result))
            })
        }
        ActivityFeedMsg::StatsRefreshed(result) => {
            if let Ok(stats) = result {
                state.activity_feed_stats = stats;
            }
            Task::none()
        }
    }
}

/// View: Activity Feed panel - NL timeline of all system activity
fn view_activity_feed_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    let colors = &state.colors;

    // Header with connection status and filter controls
    let conn_indicator = if state.activity_feed_connected {
        text("● Connected").size(11).color(colors.success)
    } else {
        text("○ Disconnected").size(11).color(colors.text_muted)
    };

    let stats_text = text(format!("{} entries", state.activity_feed_entries.len()))
        .size(11)
        .color(colors.text_secondary);

    // Source filter buttons
    let filter_label = match &state.activity_feed_source_filter {
        None => "All".to_string(),
        Some(s) => format!("{} {}", s.icon(), s.label()),
    };

    let active_filter = state.activity_feed_source_filter.clone();
    let all_bg = if active_filter.is_none() {
        colors.accent
    } else {
        colors.surface
    };
    let git_bg = if active_filter == Some(FeedSource::Git) {
        colors.accent
    } else {
        colors.surface
    };
    let agent_bg = if active_filter == Some(FeedSource::Agent) {
        colors.accent
    } else {
        colors.surface
    };
    let task_bg = if active_filter == Some(FeedSource::TaskQueue) {
        colors.accent
    } else {
        colors.surface
    };
    let nesy_bg = if active_filter == Some(FeedSource::Nesy) {
        colors.accent
    } else {
        colors.surface
    };

    let filter_row = row![
        button(text("All").size(10).color(colors.text_primary))
            .on_press(Message::ActivityFeedAction(
                ActivityFeedMsg::SetSourceFilter(None)
            ))
            .padding([3, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(all_bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(3),
                ..Default::default()
            }),
        button(text("🔀 Git").size(10).color(colors.text_primary))
            .on_press(Message::ActivityFeedAction(
                ActivityFeedMsg::SetSourceFilter(Some(FeedSource::Git))
            ))
            .padding([3, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(git_bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(3),
                ..Default::default()
            }),
        button(text("🤖 Agent").size(10).color(colors.text_primary))
            .on_press(Message::ActivityFeedAction(
                ActivityFeedMsg::SetSourceFilter(Some(FeedSource::Agent))
            ))
            .padding([3, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(agent_bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(3),
                ..Default::default()
            }),
        button(text("📋 Task").size(10).color(colors.text_primary))
            .on_press(Message::ActivityFeedAction(
                ActivityFeedMsg::SetSourceFilter(Some(FeedSource::TaskQueue))
            ))
            .padding([3, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(task_bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(3),
                ..Default::default()
            }),
        button(text("🧮 NeSy").size(10).color(colors.text_primary))
            .on_press(Message::ActivityFeedAction(
                ActivityFeedMsg::SetSourceFilter(Some(FeedSource::Nesy))
            ))
            .padding([3, 8])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(nesy_bg)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(3),
                ..Default::default()
            }),
    ]
    .spacing(3);

    let refresh_btn = button(text("🔄").size(10).color(colors.text_primary))
        .on_press(Message::ActivityFeedAction(ActivityFeedMsg::RefreshStats))
        .padding([3, 6])
        .style(move |_theme, _status| button::Style {
            background: Some(iced::Background::Color(colors.surface)),
            text_color: colors.text_primary,
            border: iced::Border::default().rounded(3),
            ..Default::default()
        });

    let header = column![
        row![
            text("Activity Feed").size(14).color(colors.text_primary),
            Space::new().width(Length::Fill),
            stats_text,
            Space::new().width(8),
            refresh_btn,
            Space::new().width(4),
            conn_indicator,
        ]
        .spacing(8)
        .align_y(Alignment::Center),
        filter_row,
    ]
    .spacing(6);

    // Filter entries by source
    let filtered_entries: Vec<&FeedEntry> = state
        .activity_feed_entries
        .iter()
        .filter(|e| match &state.activity_feed_source_filter {
            None => true,
            Some(filter) => &e.source == filter,
        })
        .take(100) // Limit rendered entries
        .collect();

    // Build timeline entries
    let timeline: Element<'_, Message> = if filtered_entries.is_empty() {
        container(
            text(if state.activity_feed_connected {
                "No activity yet. Events will appear as agents work."
            } else {
                "Connecting to Activity Feed..."
            })
            .size(12)
            .color(colors.text_muted),
        )
        .padding(20)
        .width(Length::Fill)
        .center_x(Length::Fill)
        .into()
    } else {
        let mut entries_col = column![].spacing(2);

        for entry in &filtered_entries {
            let is_expanded = state.activity_feed_expanded.as_deref() == Some(&entry.id);
            let entry_id = entry.id.clone();

            // Source icon + timestamp
            let ts_display = format_feed_timestamp(&entry.timestamp);

            let source_color = match entry.source {
                FeedSource::Git => iced::Color::from_rgb(0.4, 0.8, 0.4),
                FeedSource::Agent => iced::Color::from_rgb(0.4, 0.6, 1.0),
                FeedSource::TaskQueue => iced::Color::from_rgb(1.0, 0.7, 0.3),
                FeedSource::Nesy => iced::Color::from_rgb(0.8, 0.4, 1.0),
                FeedSource::System => colors.text_secondary,
                FeedSource::FileChange => iced::Color::from_rgb(0.6, 0.8, 0.6),
                FeedSource::Unknown => colors.text_muted,
            };

            let header_row = row![
                text(entry.source.icon()).size(12),
                text(ts_display).size(10).color(colors.text_muted),
                text(entry.title.clone()).size(11).color(source_color),
            ]
            .spacing(6)
            .align_y(Alignment::Center);

            let mut entry_content = column![header_row].spacing(2);

            // Show body when expanded
            if is_expanded {
                if let Some(body) = &entry.body {
                    entry_content = entry_content.push(
                        container(text(body).size(11).color(colors.text_secondary)).padding(
                            iced::Padding {
                                top: 2.0,
                                right: 0.0,
                                bottom: 2.0,
                                left: 22.0,
                            },
                        ),
                    );
                }

                // Show links
                if !entry.links.is_empty() {
                    let links_text: String = entry
                        .links
                        .iter()
                        .map(|l| format!("[{}]", l.label))
                        .collect::<Vec<_>>()
                        .join(" ");
                    entry_content =
                        entry_content.push(text(links_text).size(10).color(colors.accent));
                }

                // Show tags
                if !entry.tags.is_empty() {
                    let tags_text = entry
                        .tags
                        .iter()
                        .map(|t| format!("#{}", t))
                        .collect::<Vec<_>>()
                        .join(" ");
                    entry_content = entry_content.push(
                        container(text(tags_text).size(9).color(colors.text_muted)).padding(
                            iced::Padding {
                                top: 0.0,
                                right: 0.0,
                                bottom: 0.0,
                                left: 22.0,
                            },
                        ),
                    );
                }

                // Show agent_id and project if present
                let mut meta_parts = Vec::new();
                if let Some(agent) = &entry.agent_id {
                    meta_parts.push(format!("agent: {}", agent));
                }
                if let Some(project) = &entry.project {
                    meta_parts.push(format!("project: {}", project));
                }
                if let Some(repo) = &entry.repo {
                    meta_parts.push(format!("repo: {}", repo));
                }
                if !meta_parts.is_empty() {
                    entry_content = entry_content.push(
                        container(
                            text(meta_parts.join(" | "))
                                .size(9)
                                .color(colors.text_muted),
                        )
                        .padding(iced::Padding {
                            top: 0.0,
                            right: 0.0,
                            bottom: 0.0,
                            left: 22.0,
                        }),
                    );
                }
            }

            let entry_border = if is_expanded {
                source_color
            } else {
                colors.border_subtle
            };

            let entry_widget = button(
                container(entry_content)
                    .padding([4, 8])
                    .width(Length::Fill)
                    .style(move |_theme| container::Style {
                        background: Some(iced::Background::Color(if is_expanded {
                            iced::Color::from_rgba(
                                source_color.r,
                                source_color.g,
                                source_color.b,
                                0.05,
                            )
                        } else {
                            iced::Color::TRANSPARENT
                        })),
                        border: iced::Border {
                            radius: 4.0.into(),
                            width: if is_expanded { 1.0 } else { 0.0 },
                            color: entry_border,
                        },
                        ..container::Style::default()
                    }),
            )
            .on_press(Message::ActivityFeedAction(ActivityFeedMsg::ToggleEntry(
                entry_id,
            )))
            .padding(0)
            .width(Length::Fill)
            .style(move |_theme, _status| button::Style {
                background: None,
                text_color: iced::Color::WHITE,
                border: iced::Border::default(),
                shadow: iced::Shadow::default(),
                snap: false,
            });

            entries_col = entries_col.push(entry_widget);
        }

        scrollable(entries_col).height(Length::Fill).into()
    };

    // Git poll button
    let git_poll_btn = button(text("🔄 Poll Git").size(11).color(colors.text_primary))
        .on_press(Message::ActivityFeedAction(ActivityFeedMsg::TriggerGitPoll))
        .padding([4, 10])
        .style(move |_theme, _status| button::Style {
            background: Some(iced::Background::Color(colors.surface)),
            text_color: colors.text_primary,
            border: iced::Border::default().rounded(4),
            ..Default::default()
        });

    let footer = row![
        git_poll_btn,
        Space::new().width(Length::Fill),
        text(format!("Filter: {}", filter_label))
            .size(10)
            .color(colors.text_muted),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    column![header, timeline, footer,]
        .spacing(8)
        .padding(8)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}

/// Helper: Format a feed timestamp for display
fn format_feed_timestamp(ts: &str) -> String {
    // Try to parse ISO 8601 and show relative/short time
    // For now, just show the time portion
    if let Some(t_pos) = ts.find('T') {
        let time_part = &ts[t_pos + 1..];
        if let Some(dot_pos) = time_part.find('.') {
            return time_part[..dot_pos].to_string();
        }
        if let Some(z_pos) = time_part.find('Z') {
            return time_part[..z_pos].to_string();
        }
        return time_part.to_string();
    }
    ts.to_string()
}

// ============================================================================
// Zone Manager Handler
// ============================================================================

fn apply_zone_layout(state: &ContinuumStudio) -> Task<Message> {
    let layout = state.zone_manager.calculate_layout();
    let mut tasks: Vec<Task<Message>> = Vec::new();

    if let Some(main_id) = state.main_window_id {
        tasks.push(window::move_to::<Message>(
            main_id,
            iced::Point::new(layout.main.x as f32, layout.main.y as f32),
        ));
        tasks.push(window::resize::<Message>(
            main_id,
            iced::Size::new(layout.main.width as f32, layout.main.height as f32),
        ));
    }

    // Find task queue window
    if let Some((&tq_id, _)) = state
        .windows
        .iter()
        .find(|(_, ws)| ws.window_type == WindowType::TaskQueue)
    {
        tasks.push(window::move_to::<Message>(
            tq_id,
            iced::Point::new(layout.side_panel.x as f32, layout.side_panel.y as f32),
        ));
        tasks.push(window::resize::<Message>(
            tq_id,
            iced::Size::new(
                layout.side_panel.width as f32,
                layout.side_panel.height as f32,
            ),
        ));
    }

    Task::batch(tasks)
}

fn handle_zone_message(state: &mut ContinuumStudio, msg: ZoneMsg) -> Task<Message> {
    match msg {
        ZoneMsg::SetLayout(layout) => {
            state.zone_manager.set_layout(layout);
            // Export snapshot for Phosphor
            state.zone_manager.export_snapshot();
            // Immediately apply the new layout
            apply_zone_layout(state)
        }
        ZoneMsg::ApplyLayout => {
            // Export snapshot for Phosphor
            state.zone_manager.export_snapshot();
            apply_zone_layout(state)
        }
    }
}

// ============================================================================
// Agent Coordinator Handler & View
// ============================================================================

fn handle_coordinator_message(state: &mut ContinuumStudio, msg: CoordinatorMsg) -> Task<Message> {
    match msg {
        CoordinatorMsg::AgentsLoaded(result) => {
            match result {
                Ok(agents) => {
                    state.coordinator_agents = agents;
                }
                Err(e) => {
                    // Silently handle - coordinator might not be running
                    log::debug!("Coordinator agents poll: {}", e);
                }
            }
            Task::none()
        }
        CoordinatorMsg::ConflictsLoaded(result) => {
            match result {
                Ok(conflicts) => {
                    state.coordinator_conflicts = conflicts;
                }
                Err(e) => {
                    log::debug!("Coordinator conflicts poll: {}", e);
                }
            }
            Task::none()
        }
        CoordinatorMsg::SelectAgent(id) => {
            state.coordinator_selected = Some(id);
            Task::none()
        }
        CoordinatorMsg::DeselectAgent => {
            state.coordinator_selected = None;
            Task::none()
        }
        CoordinatorMsg::Refresh => {
            let http = state.coordinator_http.clone();
            Task::batch([
                Task::perform(
                    {
                        let http = http.clone();
                        async move { http.list_agents().await }
                    },
                    |result| Message::CoordinatorAction(CoordinatorMsg::AgentsLoaded(result)),
                ),
                Task::perform(async move { http.get_conflicts().await }, |result| {
                    Message::CoordinatorAction(CoordinatorMsg::ConflictsLoaded(result))
                }),
            ])
        }
        CoordinatorMsg::ResolveConflict(conflict_id) => {
            let http = state.coordinator_http.clone();
            Task::perform(
                async move { http.resolve_conflict(&conflict_id, "Resolved via UI").await },
                |result| Message::CoordinatorAction(CoordinatorMsg::ConflictResolved(result)),
            )
        }
        CoordinatorMsg::ConflictResolved(result) => {
            match result {
                Ok(()) => {
                    // Success - trigger refresh
                    handle_coordinator_message(state, CoordinatorMsg::Refresh)
                }
                Err(e) => {
                    // Emit error and then refresh
                    Task::batch([
                        Task::done(Message::CoordinatorAction(CoordinatorMsg::Error(e))),
                        handle_coordinator_message(state, CoordinatorMsg::Refresh),
                    ])
                }
            }
        }
        CoordinatorMsg::Error(e) => {
            log::error!("Coordinator error: {}", e);
            Task::none()
        }
    }
}

/// View: Agent Coordinator panel - shows registered agents, conflicts, and coordination status
fn view_coordinator_panel(state: &ContinuumStudio) -> Element<'_, Message> {
    let colors = &state.colors;

    let active_count = state
        .coordinator_agents
        .iter()
        .filter(|a| matches!(a.status, CoordAgentStatus::Active))
        .count();
    let total_count = state.coordinator_agents.len();
    let conflict_count = state
        .coordinator_conflicts
        .iter()
        .filter(|c| !c.resolved)
        .count();

    // Header with summary stats
    let header = row![
        text("Agent Coordinator")
            .size(14)
            .color(colors.text_primary),
        Space::new().width(Length::Fill),
        text(format!("{}/{} active", active_count, total_count))
            .size(11)
            .color(if active_count > 0 {
                colors.success
            } else {
                colors.text_muted
            }),
        Space::new().width(8),
        text(format!("{} conflicts", conflict_count))
            .size(11)
            .color(if conflict_count > 0 {
                colors.warning
            } else {
                colors.text_muted
            }),
        Space::new().width(8),
        button(text("🔄").size(12))
            .on_press(Message::CoordinatorAction(CoordinatorMsg::Refresh))
            .padding([2, 6])
            .style(move |_theme, _status| button::Style {
                background: Some(iced::Background::Color(colors.surface)),
                text_color: colors.text_primary,
                border: iced::Border::default().rounded(4),
                ..Default::default()
            }),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    // Conflicts section (shown first if any unresolved)
    let conflicts_section: Element<'_, Message> = if conflict_count > 0 {
        let mut conflict_col =
            column![text("⚠️ Active Conflicts").size(12).color(colors.warning),].spacing(4);

        for conflict in state.coordinator_conflicts.iter().filter(|c| !c.resolved) {
            let conflict_id = conflict.id.clone();
            let conflict_row = row![
                text(format!("⚡ {}", conflict.conflict_type))
                    .size(11)
                    .color(colors.warning),
                text(format!("on: {}", conflict.resource))
                    .size(10)
                    .color(colors.text_secondary),
                text(format!("agents: {}", conflict.agents.join(", ")))
                    .size(10)
                    .color(colors.text_muted),
                Space::new().width(Length::Fill),
                button(text("Resolve").size(10).color(colors.text_primary))
                    .on_press(Message::CoordinatorAction(CoordinatorMsg::ResolveConflict(
                        conflict_id
                    )))
                    .padding([2, 8])
                    .style(move |_theme, _status| button::Style {
                        background: Some(iced::Background::Color(colors.warning)),
                        text_color: colors.text_primary,
                        border: iced::Border::default().rounded(3),
                        ..Default::default()
                    }),
            ]
            .spacing(6)
            .align_y(Alignment::Center);

            conflict_col = conflict_col.push(
                container(conflict_row)
                    .padding([4, 8])
                    .width(Length::Fill)
                    .style(move |_theme| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgba(
                            1.0, 0.8, 0.0, 0.05,
                        ))),
                        border: iced::Border {
                            radius: 4.0.into(),
                            width: 1.0,
                            color: iced::Color::from_rgba(1.0, 0.8, 0.0, 0.2),
                        },
                        ..container::Style::default()
                    }),
            );
        }

        conflict_col.into()
    } else {
        Space::new().height(0).into()
    };

    // Agents list
    let agents_section: Element<'_, Message> = if state.coordinator_agents.is_empty() {
        container(
            text("No agents registered. Agents will appear when they connect.")
                .size(12)
                .color(colors.text_muted),
        )
        .padding(20)
        .width(Length::Fill)
        .center_x(Length::Fill)
        .into()
    } else {
        let mut agents_col = column![text("Registered Agents")
            .size(12)
            .color(colors.text_secondary),]
        .spacing(3);

        for agent in &state.coordinator_agents {
            let is_selected = state.coordinator_selected.as_deref() == Some(&agent.id);
            let agent_id = agent.id.clone();

            let status_color = match agent.status {
                CoordAgentStatus::Active => colors.success,
                CoordAgentStatus::Idle => iced::Color::from_rgb(0.9, 0.8, 0.2),
                CoordAgentStatus::Waiting => iced::Color::from_rgb(0.3, 0.5, 1.0),
                CoordAgentStatus::Completed => colors.text_muted,
                CoordAgentStatus::Disconnected => colors.error,
                CoordAgentStatus::Unknown => colors.text_muted,
            };

            // Agent summary row
            let mut agent_row = row![
                text(agent.status.icon()).size(12),
                text(agent.agent_type.icon()).size(12),
                text(&agent.id).size(11).color(colors.text_primary),
                text(format!("({})", agent.status.label()))
                    .size(10)
                    .color(status_color),
            ]
            .spacing(6)
            .align_y(Alignment::Center);

            // Show focus area if set
            if let Some(desc) = &agent.focus.description {
                agent_row = agent_row.push(
                    text(format!("→ {}", desc))
                        .size(10)
                        .color(colors.text_secondary),
                );
            }

            // Show current task if any
            if let Some(task_id) = &agent.current_task_id {
                agent_row = agent_row.push(
                    text(format!("📋 {}", task_id))
                        .size(10)
                        .color(colors.accent),
                );
            }

            let mut agent_content = column![agent_row].spacing(2);

            // Show details when selected
            if is_selected {
                // File claims
                if !agent.file_claims.is_empty() {
                    let claims_text = agent
                        .file_claims
                        .iter()
                        .take(5)
                        .map(|f| format!("  📄 {}", f))
                        .collect::<Vec<_>>()
                        .join("\n");
                    let remaining = agent.file_claims.len().saturating_sub(5);
                    let claims_display = if remaining > 0 {
                        format!("{}\n  ... +{} more", claims_text, remaining)
                    } else {
                        claims_text
                    };
                    agent_content = agent_content.push(
                        container(
                            text(format!("File claims:\n{}", claims_display))
                                .size(10)
                                .color(colors.text_secondary),
                        )
                        .padding(iced::Padding {
                            top: 2.0,
                            right: 0.0,
                            bottom: 2.0,
                            left: 22.0,
                        }),
                    );
                }

                // Focus repos/files
                if !agent.focus.repos.is_empty() {
                    agent_content = agent_content.push(
                        container(
                            text(format!("Repos: {}", agent.focus.repos.join(", ")))
                                .size(10)
                                .color(colors.text_secondary),
                        )
                        .padding(iced::Padding {
                            top: 2.0,
                            right: 0.0,
                            bottom: 0.0,
                            left: 22.0,
                        }),
                    );
                }
                if !agent.focus.files.is_empty() {
                    let files_display: Vec<_> = agent.focus.files.iter().take(5).collect();
                    agent_content = agent_content.push(
                        container(
                            text(format!(
                                "Files: {}",
                                files_display
                                    .iter()
                                    .map(|f| f.as_str())
                                    .collect::<Vec<_>>()
                                    .join(", ")
                            ))
                            .size(10)
                            .color(colors.text_secondary),
                        )
                        .padding(iced::Padding {
                            top: 0.0,
                            right: 0.0,
                            bottom: 0.0,
                            left: 22.0,
                        }),
                    );
                }

                // Capabilities
                if !agent.capabilities.is_empty() {
                    agent_content = agent_content.push(
                        container(
                            text(format!("Capabilities: {}", agent.capabilities.join(", ")))
                                .size(10)
                                .color(colors.text_muted),
                        )
                        .padding(iced::Padding {
                            top: 0.0,
                            right: 0.0,
                            bottom: 2.0,
                            left: 22.0,
                        }),
                    );
                }
            }

            let sel_bg = if is_selected {
                iced::Color::from_rgba(status_color.r, status_color.g, status_color.b, 0.06)
            } else {
                iced::Color::TRANSPARENT
            };
            let sel_border = if is_selected {
                status_color
            } else {
                colors.border_subtle
            };

            let agent_widget = button(
                container(agent_content)
                    .padding([4, 8])
                    .width(Length::Fill)
                    .style(move |_theme| container::Style {
                        background: Some(iced::Background::Color(sel_bg)),
                        border: iced::Border {
                            radius: 4.0.into(),
                            width: if is_selected { 1.0 } else { 0.0 },
                            color: sel_border,
                        },
                        ..container::Style::default()
                    }),
            )
            .on_press(if is_selected {
                Message::CoordinatorAction(CoordinatorMsg::DeselectAgent)
            } else {
                Message::CoordinatorAction(CoordinatorMsg::SelectAgent(agent_id))
            })
            .padding(0)
            .width(Length::Fill)
            .style(move |_theme, _status| button::Style {
                background: None,
                text_color: iced::Color::WHITE,
                border: iced::Border::default(),
                shadow: iced::Shadow::default(),
                snap: false,
            });

            agents_col = agents_col.push(agent_widget);
        }

        scrollable(agents_col).height(Length::Fill).into()
    };

    column![header, conflicts_section, agents_section,]
        .spacing(8)
        .padding(8)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}
