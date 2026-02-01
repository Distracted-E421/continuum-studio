//! Continuum Studio UI - iced/COSMIC Edition
//!
//! This is the iced-based UI for Continuum Studio, designed for integration
//! with the COSMIC desktop ecosystem.

use iced::widget::{button, column, container, row, text, Space, scrollable};
use iced::{Alignment, Element, Length, Task, Theme};
use std::path::PathBuf;
use tokio::sync::mpsc;

pub mod core;
pub mod settings;
pub mod theme;
pub mod widgets;

use core::{CoreRequest, CoreResponse, CursorVersion, VersionStatus, ConnectionState, spawn_core_connection};
use settings::{Settings, ThemePreference, CosmicPreset};
use theme::CosmicThemePreset;

fn main() -> iced::Result {
    env_logger::init();
    
    iced::application(ContinuumStudio::new, update, view)
        .title("Continuum Studio")
        .theme(|state: &ContinuumStudio| state.theme.clone())
        .window_size(iced::Size::new(1280.0, 800.0))
        .antialiasing(true)
        .subscription(|state| {
            // Subscribe to Core connection events
            if state.core_tx.is_some() {
                iced::Subscription::none()
            } else {
                // Start connection subscription
                core_subscription()
            }
        })
        .run()
}

/// Subscription to handle Core IPC connection
fn core_subscription() -> iced::Subscription<Message> {
    iced::Subscription::run(core_worker)
}

/// Core connection worker that yields Messages
fn core_worker() -> impl iced::futures::Stream<Item = Message> {
    iced::stream::channel(100, |mut output: iced::futures::channel::mpsc::Sender<Message>| async move {
        use iced::futures::SinkExt;
        
        let socket_path = PathBuf::from(core::DEFAULT_SOCKET_PATH);
        
        // Spawn the Core connection
        let (request_tx, mut response_rx, mut state_rx) = 
            spawn_core_connection(socket_path);
        
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
                    let _ = output.send(Message::CoreResponse(response)).await;
                }
            }
        }
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
    fn new() -> (Self, Task<Message>) {
        // Load settings
        let settings = Settings::load();
        
        // Derive theme from settings
        let theme = derive_theme(&settings);
        
        // Initialize with placeholder versions for now
        // Real versions will come from Core connection
        let versions = vec![
            CursorVersion {
                version: "0.44.11".to_string(),
                status: VersionStatus::Running,
                release_date: Some("2026-01-30".to_string()),
                size_mb: Some(250),
            },
            CursorVersion {
                version: "0.44.10".to_string(),
                status: VersionStatus::Installed,
                release_date: Some("2026-01-28".to_string()),
                size_mb: Some(248),
            },
            CursorVersion {
                version: "0.44.9".to_string(),
                status: VersionStatus::Available,
                release_date: Some("2026-01-25".to_string()),
                size_mb: Some(245),
            },
        ];

        log::info!("Loaded settings: theme={:?}, socket={}", 
            settings.theme, settings.core_socket_path);

        (
            Self {
                settings,
                theme,
                connection_state: ConnectionState::Disconnected,
                current_view: View::Dashboard,
                versions,
                core_tx: None,
                settings_dirty: false,
            },
            Task::none(),
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
    /// Core request sender
    core_tx: Option<mpsc::Sender<CoreRequest>>,
    /// Settings have been modified
    settings_dirty: bool,
}


/// Available views in the application
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum View {
    Dashboard,
    CursorVersions,
    Sessions,
    Settings,
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
    /// Versions updated from Core
    VersionsUpdated(Vec<CursorVersion>),
    /// Core response received
    CoreResponse(CoreResponse),
    /// Settings action
    SettingsAction(SettingsMessage),
}

/// Settings-related messages
#[derive(Debug, Clone)]
enum SettingsMessage {
    SaveSettings,
    ToggleAutoConnect,
    ToggleNotifications,
}

/// Cursor-related messages
#[derive(Debug, Clone)]
enum CursorMessage {
    RefreshVersions,
    LaunchVersion(String),
    InstallVersion(String),
    UninstallVersion(String),
}

fn update(state: &mut ContinuumStudio, message: Message) -> Task<Message> {
    match message {
        Message::NavigateTo(view) => {
            state.current_view = view;
        }
        Message::CoreConnected(tx) => {
            log::info!("Core connection established, requesting versions...");
            state.core_tx = Some(tx.clone());
            // Request versions immediately
            return Task::perform(
                async move {
                    let _ = tx.send(CoreRequest::GetVersions).await;
                },
                |_| Message::CursorAction(CursorMessage::RefreshVersions),
            );
        }
        Message::CoreConnectionStateChanged(new_state) => {
            let was_connected = matches!(state.connection_state, ConnectionState::Connected);
            let is_connected = matches!(new_state, ConnectionState::Connected);
            state.connection_state = new_state;
            
            log::info!("Core connection state: {:?}", new_state);
            
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
        Message::SettingsAction(settings_msg) => {
            match settings_msg {
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
                SettingsMessage::ToggleNotifications => {
                    state.settings.notify_new_versions = !state.settings.notify_new_versions;
                    state.settings_dirty = true;
                }
            }
        }
        Message::CursorAction(cursor_msg) => {
            match cursor_msg {
                CursorMessage::RefreshVersions => {
                    log::info!("Refreshing Cursor versions...");
                }
                CursorMessage::LaunchVersion(version) => {
                    log::info!("Launching Cursor version: {}", version);
                    if let Some(tx) = &state.core_tx {
                        let tx = tx.clone();
                        return Task::perform(
                            async move {
                                let _ = tx.send(CoreRequest::LaunchVersion { version }).await;
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
            }
        }
        Message::VersionsUpdated(versions) => {
            state.versions = versions;
        }
        Message::CoreResponse(response) => {
            match response {
                CoreResponse::Versions(versions) => {
                    state.versions = versions;
                }
                CoreResponse::LaunchResult { success, message } => {
                    if success {
                        log::info!("Launch success: {}", message);
                    } else {
                        log::error!("Launch failed: {}", message);
                    }
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
        View::Sessions => view_sessions(state),
        View::Settings => view_settings(state),
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
        ConnectionState::Disconnected => ("○ Disconnected", iced::Color::from_rgb(0.75, 0.35, 0.35)),
    };
    
    let connection_status = text(status_text).color(status_color);

    // Logo/header section
    let header = column![
        text("Continuum").size(22),
        text("Studio").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
    ]
    .spacing(2);

    // Version and connection status section
    let status_section = column![
        row![
            text("v0.1.0").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().width(Length::Fill),
            connection_status.size(10),
        ]
        .align_y(Alignment::Center),
    ]
    .padding([8, 0]);

    // Navigation section with better visual hierarchy
    let nav_section = column![
        text("Navigation").size(10).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().height(8),
        nav_button("🏠  Dashboard", View::Dashboard, current),
        nav_button("📦  Versions", View::CursorVersions, current),
        nav_button("💬  Sessions", View::Sessions, current),
        Space::new().height(Length::Fill),
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
                    background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.3, 0.3))),
                    ..container::Style::default()
                }),
            Space::new().height(16),
            nav_section,
        ]
        .spacing(0)
        .padding(16)
        .width(220)
    )
    .height(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.12))),
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
    
    let btn = button(
        text(label)
            .size(13)
            .color(if is_active {
                iced::Color::WHITE
            } else {
                iced::Color::from_rgb(0.75, 0.75, 0.75)
            })
    )
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
    // Status card
    let status_card = card(
        column![
            text("System Status").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            row![
                text("Core Connection:").size(13),
                Space::new().width(Length::Fill),
                text(match state.connection_state {
                    ConnectionState::Connected => "Connected",
                    ConnectionState::Connecting => "Connecting...",
                    ConnectionState::Reconnecting { .. } => "Reconnecting...",
                    ConnectionState::Disconnected => "Disconnected",
                }).size(13).color(match state.connection_state {
                    ConnectionState::Connected => iced::Color::from_rgb(0.25, 0.75, 0.35),
                    _ => iced::Color::from_rgb(0.75, 0.55, 0.25),
                }),
            ],
            Space::new().height(4),
            row![
                text("Versions Loaded:").size(13),
                Space::new().width(Length::Fill),
                text(format!("{}", state.versions.len())).size(13),
            ],
        ]
        .spacing(4)
    );

    // Quick actions card
    let actions_card = card(
        column![
            text("Quick Actions").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            row![
                styled_button("Launch Cursor", true)
                    .on_press(Message::CursorAction(CursorMessage::LaunchVersion("latest".to_string()))),
                Space::new().width(12),
                styled_button("Refresh Versions", false)
                    .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
            ]
            .spacing(0),
        ]
        .spacing(4)
    );

    column![
        text("Dashboard").size(26),
        text("Welcome back to Continuum Studio").size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().height(24),
        row![
            status_card,
            Space::new().width(16),
            actions_card,
        ],
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
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.15))),
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
                    button::Status::Active => (
                        iced::Color::from_rgb(0.4, 0.6, 1.0),
                        iced::Color::WHITE,
                    ),
                    button::Status::Hovered => (
                        iced::Color::from_rgb(0.45, 0.65, 1.0),
                        iced::Color::WHITE,
                    ),
                    button::Status::Pressed => (
                        iced::Color::from_rgb(0.35, 0.55, 0.95),
                        iced::Color::WHITE,
                    ),
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
                    button::Status::Hovered => (
                        iced::Color::from_rgb(0.3, 0.3, 0.3),
                        iced::Color::WHITE,
                    ),
                    button::Status::Pressed => (
                        iced::Color::from_rgb(0.2, 0.2, 0.2),
                        iced::Color::WHITE,
                    ),
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

/// Cursor version management view with polished styling
fn view_cursor_versions(state: &ContinuumStudio) -> Element<Message> {
    let version_rows: Vec<Element<Message>> = state
        .versions
        .iter()
        .map(|v| version_row_from_data(v))
        .collect();

    let version_list: Element<Message> = if version_rows.is_empty() {
        column![
            Space::new().height(40),
            text("No versions available").size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(8),
            text("Connect to Core to load versions").size(12).color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            Space::new().height(40),
        ]
        .align_x(Alignment::Center)
        .width(Length::Fill)
        .into()
    } else {
        column(version_rows).spacing(8).into()
    };

    // Header card with controls
    let header_card = container(
        row![
            column![
                text("Cursor Versions").size(20),
                text("Manage installed versions").size(12).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .spacing(4),
            Space::new().width(Length::Fill),
            styled_button("Refresh", false)
                .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
        ]
        .align_y(Alignment::Center)
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

    // Table header
    let table_header = container(
        row![
            text("Version").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).width(120),
            text("Status").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).width(100),
            text("Release").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).width(100),
            text("Size").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).width(80),
            Space::new().width(Length::Fill),
            text("Actions").size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)).width(100),
        ]
        .padding([0, 16])
    );

    column![
        header_card,
        Space::new().height(16),
        table_header,
        Space::new().height(8),
        scrollable(version_list).height(400),
    ]
    .spacing(0)
    .into()
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
        VersionStatus::Running => {
            container(
                text("● Active").size(11).color(iced::Color::from_rgb(0.25, 0.75, 0.35))
            )
            .padding([6, 12])
            .into()
        }
        VersionStatus::Installed => {
            small_button("Launch", true)
                .on_press(Message::CursorAction(CursorMessage::LaunchVersion(v)))
                .into()
        }
        VersionStatus::Available => {
            small_button("Install", false)
                .on_press(Message::CursorAction(CursorMessage::InstallVersion(v2)))
                .into()
        }
        VersionStatus::Downloading => {
            container(
                text("⏳ ...").size(11).color(iced::Color::from_rgb(0.75, 0.65, 0.25))
            )
            .padding([6, 12])
            .into()
        }
    };
    
    container(
        row![
            text(&version.version).size(13).width(120),
            text(status_text).size(12).color(status_color).width(100),
            text(version.release_date.as_deref().unwrap_or("-")).size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)).width(100),
            text(version.size_mb.map(|s| format!("{} MB", s)).unwrap_or("-".to_string())).size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6)).width(80),
            Space::new().width(Length::Fill),
            container(action_button).width(100),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
        .padding([12, 16])
    )
    .width(Length::Fill)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.13, 0.13, 0.13))),
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
                    button::Status::Active => (
                        iced::Color::from_rgb(0.3, 0.5, 0.9),
                        iced::Color::WHITE,
                    ),
                    button::Status::Hovered => (
                        iced::Color::from_rgb(0.35, 0.55, 0.95),
                        iced::Color::WHITE,
                    ),
                    button::Status::Pressed => (
                        iced::Color::from_rgb(0.25, 0.45, 0.85),
                        iced::Color::WHITE,
                    ),
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
                    button::Status::Hovered => (
                        iced::Color::from_rgb(0.25, 0.25, 0.25),
                        iced::Color::WHITE,
                    ),
                    button::Status::Pressed => (
                        iced::Color::from_rgb(0.15, 0.15, 0.15),
                        iced::Color::WHITE,
                    ),
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


/// Sessions view with polished styling
fn view_sessions(_state: &ContinuumStudio) -> Element<Message> {
    // Header card
    let header_card = container(
        column![
            text("Sessions").size(20),
            text("View active and past Cursor sessions").size(12).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .spacing(4)
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

    // Empty state
    let empty_state = container(
        column![
            text("💬").size(48),
            Space::new().height(16),
            text("No active sessions").size(16),
            Space::new().height(8),
            text("Start a Cursor session to see it here").size(12).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
        .align_x(Alignment::Center)
    )
    .width(Length::Fill)
    .padding(60)
    .style(|_theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.12))),
        border: iced::Border {
            radius: 12.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.18),
        },
        ..container::Style::default()
    });

    column![
        header_card,
        Space::new().height(16),
        empty_state,
    ]
    .spacing(0)
    .into()
}

/// Settings view with polished card-based layout
fn view_settings(state: &ContinuumStudio) -> Element<Message> {
    // Header
    let header = column![
        text("Settings").size(26),
        text("Configure Continuum Studio").size(14).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .spacing(4);

    // Appearance card
    let appearance_card = settings_card(
        "Appearance",
        column![
            settings_row(
                "Theme",
                row![
                    theme_pill("System", state.settings.theme == ThemePreference::System, ThemePreference::System),
                    theme_pill("Dark", state.settings.theme == ThemePreference::Dark, ThemePreference::Dark),
                    theme_pill("Light", state.settings.theme == ThemePreference::Light, ThemePreference::Light),
                    theme_pill("COSMIC", state.settings.theme == ThemePreference::Cosmic, ThemePreference::Cosmic),
                ].spacing(6)
            ),
            if state.settings.theme == ThemePreference::Cosmic {
                settings_row(
                    "COSMIC Preset",
                    row![
                        cosmic_pill("Dark", state.settings.cosmic_preset == CosmicPreset::Dark, CosmicPreset::Dark),
                        cosmic_pill("Light", state.settings.cosmic_preset == CosmicPreset::Light, CosmicPreset::Light),
                        cosmic_pill("Pop", state.settings.cosmic_preset == CosmicPreset::PopOrange, CosmicPreset::PopOrange),
                        cosmic_pill("Blue", state.settings.cosmic_preset == CosmicPreset::CoolBlue, CosmicPreset::CoolBlue),
                    ].spacing(6)
                )
            } else {
                Space::new().height(0).into()
            },
        ].spacing(16)
    );

    // Connection card
    let connection_card = settings_card(
        "Connection",
        column![
            settings_row(
                "Auto-connect",
                toggle_button(state.settings.auto_connect, SettingsMessage::ToggleAutoConnect)
            ),
            settings_row(
                "Socket path",
                text(&state.settings.core_socket_path).size(12).color(iced::Color::from_rgb(0.6, 0.6, 0.6))
            ),
        ].spacing(16)
    );

    // Notifications card
    let notifications_card = settings_card(
        "Notifications",
        settings_row(
            "Version updates",
            toggle_button(state.settings.notify_new_versions, SettingsMessage::ToggleNotifications)
        )
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
        Space::new().height(24),
        save_section,
        Space::new().height(16),
        footer,
    ]
    .spacing(0)
    .into()
}

/// Settings card container
fn settings_card<'a>(title: &'a str, content: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    container(
        column![
            text(title).size(14).color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(12),
            content.into(),
        ]
        .spacing(0)
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
    .into()
}

/// Settings row with label and control
fn settings_row<'a>(label: &'a str, control: impl Into<Element<'a, Message>>) -> Element<'a, Message> {
    row![
        text(label).size(13),
        Space::new().width(Length::Fill),
        control.into(),
    ]
    .align_y(Alignment::Center)
    .into()
}

/// Theme selection pill button
fn theme_pill(label: &'static str, is_active: bool, pref: ThemePreference) -> Element<'static, Message> {
    button(text(label).size(11))
        .padding([6, 12])
        .on_press(Message::ThemePreferenceChanged(pref))
        .style(move |_theme, status| pill_style(is_active, status))
        .into()
}

/// COSMIC preset pill button
fn cosmic_pill(label: &'static str, is_active: bool, preset: CosmicPreset) -> Element<'static, Message> {
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
        text_color: if is_active { iced::Color::WHITE } else { iced::Color::from_rgb(0.7, 0.7, 0.7) },
        border: iced::Border {
            radius: 6.0.into(),
            width: 0.0,
            color: iced::Color::TRANSPARENT,
        },
        shadow: iced::Shadow::default(),
        snap: false,
    }
}
