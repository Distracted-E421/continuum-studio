//! Continuum Studio UI - iced/COSMIC Edition
//!
//! This is the iced-based UI for Continuum Studio, designed for integration
//! with the COSMIC desktop ecosystem.

use iced::widget::{button, column, container, row, text, Space};
use iced::{Alignment, Element, Length, Task, Theme};

pub mod theme;
pub mod widgets;

fn main() -> iced::Result {
    env_logger::init();
    
    iced::application(ContinuumStudio::new, update, view)
        .title("Continuum Studio")
        .theme(|state: &ContinuumStudio| state.theme.clone())
        .window_size(iced::Size::new(1280.0, 800.0))
        .antialiasing(true)
        .run()
}

/// Boot function for iced application
impl ContinuumStudio {
    fn new() -> (Self, Task<Message>) {
        (
            Self {
                theme: Theme::Dark,
                core_connected: false,
                current_view: View::Dashboard,
            },
            Task::none(),
        )
    }
}

/// Main application state
#[derive(Debug)]
struct ContinuumStudio {
    /// Current theme
    theme: Theme,
    /// Connection state to Elixir Core
    core_connected: bool,
    /// Current page/view
    current_view: View,
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
    /// Core connection state
    CoreConnectionChanged(bool),
    /// Theme changed
    ThemeChanged(Theme),
    /// Cursor version management
    CursorAction(CursorMessage),
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
        Message::CoreConnectionChanged(connected) => {
            state.core_connected = connected;
        }
        Message::ThemeChanged(theme) => {
            state.theme = theme;
        }
        Message::CursorAction(cursor_msg) => {
            match cursor_msg {
                CursorMessage::RefreshVersions => {
                    log::info!("Refreshing Cursor versions...");
                }
                CursorMessage::LaunchVersion(version) => {
                    log::info!("Launching Cursor version: {}", version);
                }
                CursorMessage::InstallVersion(version) => {
                    log::info!("Installing Cursor version: {}", version);
                }
                CursorMessage::UninstallVersion(version) => {
                    log::info!("Uninstalling Cursor version: {}", version);
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
fn view_cursor_versions(_state: &ContinuumStudio) -> Element<Message> {
    column![
        text("Cursor Versions").size(24),
        text("Manage installed Cursor versions").size(14),
        container(column![]).height(20),
        
        // Version list
        container(
            column![
                version_row("0.44.11", "Installed", true),
                version_row("0.44.10", "Installed", false),
                version_row("0.44.9", "Available", false),
            ]
            .spacing(8)
        )
        .padding(16),
    ]
    .spacing(16)
    .into()
}

/// Single version row in the list
fn version_row<'a>(version: &'a str, status: &'a str, is_current: bool) -> Element<'a, Message> {
    let version_str = version.to_string();
    let version_str2 = version.to_string();
    
    let action_button: Element<Message> = if is_current {
        button("Running")
            .padding([6, 12])
            .into()
    } else if status == "Installed" {
        button("Launch")
            .padding([6, 12])
            .on_press(Message::CursorAction(CursorMessage::LaunchVersion(version_str)))
            .into()
    } else {
        button("Install")
            .padding([6, 12])
            .on_press(Message::CursorAction(CursorMessage::InstallVersion(version_str2)))
            .into()
    };
    
    row![
        text(version).size(14).width(100),
        text(status).size(12).width(100),
        Space::new().width(Length::Fill),
        action_button
    ]
    .spacing(8)
    .align_y(Alignment::Center)
    .padding([8, 12])
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
fn view_settings(_state: &ContinuumStudio) -> Element<Message> {
    column![
        text("Settings").size(24),
        text("Configure Continuum Studio").size(14),
        container(column![]).height(20),
        
        row![
            text("Theme:").size(14),
            button("Dark")
                .padding([6, 12])
                .on_press(Message::ThemeChanged(Theme::Dark)),
            button("Light")
                .padding([6, 12])
                .on_press(Message::ThemeChanged(Theme::Light)),
        ]
        .spacing(10)
        .align_y(Alignment::Center),
    ]
    .spacing(16)
    .into()
}
