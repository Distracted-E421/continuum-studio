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

use core::{CoreRequest, CoreResponse, CursorVersion, VersionStatus, spawn_core_connection};
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
        let (request_tx, mut response_rx, mut connected_rx) = 
            spawn_core_connection(socket_path);
        
        // Send the request sender to the app
        let _ = output.send(Message::CoreConnected(request_tx)).await;
        
        loop {
            tokio::select! {
                // Handle connection state changes
                Some(connected) = connected_rx.recv() => {
                    let _ = output.send(Message::CoreConnectionChanged(connected)).await;
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
                core_connected: false,
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
    core_connected: bool,
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
    /// Core connection state
    CoreConnectionChanged(bool),
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
        Message::CoreConnectionChanged(connected) => {
            state.core_connected = connected;
            log::info!("Core connection state: {}", if connected { "connected" } else { "disconnected" });
            if connected {
                // Request versions when connected
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

/// Sidebar navigation
fn sidebar(state: &ContinuumStudio) -> Element<Message> {
    let current = state.current_view;
    
    let connection_status = if state.core_connected {
        text("● Connected").color(iced::Color::from_rgb(0.25, 0.75, 0.35))
    } else {
        text("○ Disconnected").color(iced::Color::from_rgb(0.75, 0.35, 0.35))
    };

    container(
        column![
            text("Continuum Studio").size(18),
            text("v0.1.0").size(12),
            connection_status.size(11),
            container(column![]).height(20),
            nav_button("🏠 Dashboard", View::Dashboard, current),
            nav_button("📦 Cursor Versions", View::CursorVersions, current),
            nav_button("💬 Sessions", View::Sessions, current),
            nav_button("⚙️ Settings", View::Settings, current),
        ]
        .spacing(8)
        .padding(16)
        .width(200)
    )
    .height(Length::Fill)
    .into()
}

/// Create a navigation button
fn nav_button(label: &'static str, view: View, _current: View) -> Element<'static, Message> {
    button(text(label).size(14))
        .padding([8, 16])
        .width(Length::Fill)
        .on_press(Message::NavigateTo(view))
        .into()
}

/// Dashboard view
fn view_dashboard(_state: &ContinuumStudio) -> Element<Message> {
    column![
        text("Dashboard").size(24),
        text("Welcome to Continuum Studio").size(14),
        container(column![]).height(20),
        text("Quick Actions:").size(16),
        row![
            button("Launch Cursor")
                .padding([10, 20])
                .on_press(Message::CursorAction(CursorMessage::LaunchVersion("latest".to_string()))),
            button("Refresh Versions")
                .padding([10, 20])
                .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
        ]
        .spacing(10),
    ]
    .spacing(16)
    .into()
}

/// Cursor version management view
fn view_cursor_versions(state: &ContinuumStudio) -> Element<Message> {
    let version_rows: Vec<Element<Message>> = state
        .versions
        .iter()
        .map(|v| {
            version_row_from_data(v)
        })
        .collect();

    let version_list = if version_rows.is_empty() {
        column![text("No versions available").size(14)]
    } else {
        column(version_rows).spacing(4)
    };

    column![
        text("Cursor Versions").size(24),
        text("Manage installed Cursor versions").size(14),
        row![
            button("Refresh")
                .padding([6, 12])
                .on_press(Message::CursorAction(CursorMessage::RefreshVersions)),
        ],
        container(column![]).height(10),
        
        // Version list header
        row![
            text("Version").size(12).width(120),
            text("Status").size(12).width(100),
            text("Release Date").size(12).width(120),
            text("Size").size(12).width(80),
            Space::new().width(Length::Fill),
            text("Actions").size(12).width(100),
        ]
        .padding([8, 12]),
        
        // Version list
        scrollable(
            container(version_list).padding(8)
        )
        .height(400),
    ]
    .spacing(12)
    .into()
}

/// Create a version row from CursorVersion data
fn version_row_from_data(version: &CursorVersion) -> Element<Message> {
    let v = version.version.clone();
    let v2 = version.version.clone();
    
    let action_button: Element<Message> = match version.status {
        VersionStatus::Running => {
            button("Running")
                .padding([4, 8])
                .into()
        }
        VersionStatus::Installed => {
            button("Launch")
                .padding([4, 8])
                .on_press(Message::CursorAction(CursorMessage::LaunchVersion(v)))
                .into()
        }
        VersionStatus::Available => {
            button("Install")
                .padding([4, 8])
                .on_press(Message::CursorAction(CursorMessage::InstallVersion(v2)))
                .into()
        }
        VersionStatus::Downloading => {
            button("Downloading...")
                .padding([4, 8])
                .into()
        }
    };
    
    row![
        text(&version.version).size(14).width(120),
        text(version.status.to_string()).size(12).width(100),
        text(version.release_date.as_deref().unwrap_or("-")).size(12).width(120),
        text(version.size_mb.map(|s| format!("{} MB", s)).unwrap_or("-".to_string())).size(12).width(80),
        Space::new().width(Length::Fill),
        container(action_button).width(100),
    ]
    .spacing(8)
    .align_y(Alignment::Center)
    .padding([6, 12])
    .into()
}


/// Sessions view
fn view_sessions(_state: &ContinuumStudio) -> Element<Message> {
    column![
        text("Sessions").size(24),
        text("Active and past sessions").size(14),
        container(column![]).height(20),
        text("No active sessions").size(14),
    ]
    .spacing(16)
    .into()
}

/// Settings view
fn view_settings(state: &ContinuumStudio) -> Element<Message> {
    let theme_buttons = row![
        text("Theme:").size(14).width(150),
        button(if state.settings.theme == ThemePreference::System { "● System" } else { "System" })
            .padding([6, 12])
            .on_press(Message::ThemePreferenceChanged(ThemePreference::System)),
        button(if state.settings.theme == ThemePreference::Dark { "● Dark" } else { "Dark" })
            .padding([6, 12])
            .on_press(Message::ThemePreferenceChanged(ThemePreference::Dark)),
        button(if state.settings.theme == ThemePreference::Light { "● Light" } else { "Light" })
            .padding([6, 12])
            .on_press(Message::ThemePreferenceChanged(ThemePreference::Light)),
        button(if state.settings.theme == ThemePreference::Cosmic { "● COSMIC" } else { "COSMIC" })
            .padding([6, 12])
            .on_press(Message::ThemePreferenceChanged(ThemePreference::Cosmic)),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    
    // COSMIC preset row (only visible when COSMIC theme is selected)
    let cosmic_presets = if state.settings.theme == ThemePreference::Cosmic {
        row![
            text("COSMIC Preset:").size(14).width(150),
            button(if state.settings.cosmic_preset == CosmicPreset::Dark { "● Dark" } else { "Dark" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::Dark)),
            button(if state.settings.cosmic_preset == CosmicPreset::Light { "● Light" } else { "Light" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::Light)),
            button(if state.settings.cosmic_preset == CosmicPreset::PopOrange { "● Pop" } else { "Pop" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::PopOrange)),
            button(if state.settings.cosmic_preset == CosmicPreset::WarmAmber { "● Amber" } else { "Amber" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::WarmAmber)),
            button(if state.settings.cosmic_preset == CosmicPreset::CoolBlue { "● Blue" } else { "Blue" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::CoolBlue)),
            button(if state.settings.cosmic_preset == CosmicPreset::Mint { "● Mint" } else { "Mint" })
                .padding([4, 8])
                .on_press(Message::CosmicPresetChanged(CosmicPreset::Mint)),
        ]
        .spacing(8)
        .align_y(Alignment::Center)
    } else {
        row![
            text("COSMIC Preset:").size(14).width(150),
            text("Select COSMIC theme to choose preset").size(12),
        ]
        .spacing(10)
        .align_y(Alignment::Center)
    };
    
    let auto_connect = row![
        text("Auto-connect to Core:").size(14).width(150),
        button(if state.settings.auto_connect { "✓ Enabled" } else { "Disabled" })
            .padding([6, 12])
            .on_press(Message::SettingsAction(SettingsMessage::ToggleAutoConnect)),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    
    let notifications = row![
        text("Version notifications:").size(14).width(150),
        button(if state.settings.notify_new_versions { "✓ Enabled" } else { "Disabled" })
            .padding([6, 12])
            .on_press(Message::SettingsAction(SettingsMessage::ToggleNotifications)),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    
    let socket_path = row![
        text("Core socket:").size(14).width(150),
        text(&state.settings.core_socket_path).size(12),
    ]
    .spacing(10)
    .align_y(Alignment::Center);
    
    let save_button = if state.settings_dirty {
        button("Save Settings")
            .padding([10, 20])
            .on_press(Message::SettingsAction(SettingsMessage::SaveSettings))
    } else {
        button("Settings Saved")
            .padding([10, 20])
    };
    
    let settings_path = row![
        text("Settings file:").size(12),
        text(Settings::file_path().display().to_string()).size(11),
    ]
    .spacing(10);
    
    column![
        text("Settings").size(24),
        text("Configure Continuum Studio").size(14),
        container(column![]).height(20),
        
        text("Appearance").size(16),
        theme_buttons,
        cosmic_presets,
        
        container(column![]).height(10),
        text("Connection").size(16),
        auto_connect,
        socket_path,
        
        container(column![]).height(10),
        text("Notifications").size(16),
        notifications,
        
        container(column![]).height(20),
        save_button,
        
        container(column![]).height(20),
        settings_path,
    ]
    .spacing(12)
    .into()
}
