//! Synapsix Launcher
//!
//! Quick-start tool for Cursor with Synapsix integration.
//!
//! Features:
//! - Cursor version management (download, install, launch)
//! - Workspace selection with recent history
//! - Synapsix daemon status and configuration
//! - Auto-update checking

use iced::widget::{button, column, container, pick_list, row, scrollable, text, Space};
use iced::{Alignment, Element, Fill, Length, Task, Theme};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

mod api;
mod config;
mod views;

fn main() -> iced::Result {
    env_logger::init();
    iced::application(Launcher::new, Launcher::update, Launcher::view)
        .title(Launcher::title)
        .window_size(iced::Size::new(600.0, 500.0))
        .antialiasing(true)
        .theme(Launcher::theme)
        .run()
}

#[derive(Debug, Clone)]
pub enum Message {
    // Version management
    LoadVersions,
    VersionsLoaded(Result<Vec<CursorVersion>, String>),
    SelectVersion(String),
    DownloadVersion(String),
    DownloadProgress(String, f32),
    DownloadComplete(String, Result<(), String>),

    // Workspace
    LoadWorkspaces,
    WorkspacesLoaded(Vec<Workspace>),
    SelectWorkspace(PathBuf),
    BrowseWorkspace,
    WorkspaceSelected(Option<PathBuf>),

    // Launch
    Launch,
    LaunchComplete(Result<(), String>),

    // Settings
    ToggleAutoUpdate,
    ToggleStartWithSynapsix,

    // Synapsix status
    RefreshSynapsixStatus,
    SynapsixStatusUpdated(SynapsixStatus),

    // Navigation
    GoToTab(Tab),
    
    // Error dismiss
    DismissError,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Launch,
    Versions,
    Settings,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CursorVersion {
    pub version: String,
    pub date: Option<String>,
    pub installed: bool,
    pub era: String,
    pub notes: Option<String>,
}

#[derive(Debug, Clone)]
pub struct Workspace {
    pub path: PathBuf,
    pub name: String,
    pub last_opened: Option<chrono::DateTime<chrono::Utc>>,
}

#[derive(Debug, Clone, Default)]
pub struct SynapsixStatus {
    pub daemon_running: bool,
    pub dialog_available: bool,
    pub terminal_monitor: bool,
    pub version: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LauncherConfig {
    pub auto_update: bool,
    pub start_with_synapsix: bool,
    pub default_version: Option<String>,
    pub recent_workspaces: Vec<PathBuf>,
}

pub struct Launcher {
    tab: Tab,
    versions: Vec<CursorVersion>,
    selected_version: Option<String>,
    downloading: Option<(String, f32)>,

    workspaces: Vec<Workspace>,
    selected_workspace: Option<PathBuf>,

    synapsix_status: SynapsixStatus,
    config: LauncherConfig,

    error: Option<String>,
    loading: bool,
}

impl Launcher {
    fn new() -> (Self, Task<Message>) {
        let launcher = Launcher {
            tab: Tab::Launch,
            versions: Vec::new(),
            selected_version: None,
            downloading: None,

            workspaces: Vec::new(),
            selected_workspace: None,

            synapsix_status: SynapsixStatus::default(),
            config: config::load_config(),

            error: None,
            loading: true,
        };

        let commands = Task::batch(vec![
            Task::perform(async {}, |_| Message::LoadVersions),
            Task::perform(async {}, |_| Message::LoadWorkspaces),
            Task::perform(async {}, |_| Message::RefreshSynapsixStatus),
        ]);

        (launcher, commands)
    }

    fn title(&self) -> String {
        String::from("Synapsix Launcher")
    }

    fn update(&mut self, message: Message) -> Task<Message> {
        match message {
            Message::GoToTab(tab) => {
                self.tab = tab;
                Task::none()
            }

            Message::LoadVersions => {
                self.loading = true;
                Task::perform(api::fetch_versions(), Message::VersionsLoaded)
            }

            Message::VersionsLoaded(result) => {
                self.loading = false;
                match result {
                    Ok(versions) => {
                        self.versions = versions;
                        if self.selected_version.is_none() {
                            self.selected_version = self.config.default_version.clone()
                                .or_else(|| self.versions.first().map(|v| v.version.clone()));
                        }
                    }
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }

            Message::SelectVersion(version) => {
                self.selected_version = Some(version);
                Task::none()
            }

            Message::DownloadVersion(version) => {
                self.downloading = Some((version.clone(), 0.0));
                Task::perform(api::download_version(version), |(v, r)| Message::DownloadComplete(v, r))
            }

            Message::DownloadProgress(version, progress) => {
                if let Some((ref v, _)) = self.downloading {
                    if *v == version {
                        self.downloading = Some((version, progress));
                    }
                }
                Task::none()
            }

            Message::DownloadComplete(version, result) => {
                self.downloading = None;
                match result {
                    Ok(()) => {
                        if let Some(v) = self.versions.iter_mut().find(|v| v.version == version) {
                            v.installed = true;
                        }
                    }
                    Err(e) => self.error = Some(format!("Download failed: {}", e)),
                }
                Task::none()
            }

            Message::LoadWorkspaces => {
                Task::perform(api::fetch_recent_workspaces(), Message::WorkspacesLoaded)
            }

            Message::WorkspacesLoaded(workspaces) => {
                self.workspaces = workspaces;
                if self.selected_workspace.is_none() && !self.workspaces.is_empty() {
                    self.selected_workspace = Some(self.workspaces[0].path.clone());
                }
                Task::none()
            }

            Message::SelectWorkspace(path) => {
                self.selected_workspace = Some(path);
                Task::none()
            }

            Message::BrowseWorkspace => {
                Task::perform(api::browse_for_folder(), Message::WorkspaceSelected)
            }

            Message::WorkspaceSelected(path) => {
                if let Some(p) = path {
                    self.selected_workspace = Some(p);
                }
                Task::none()
            }

            Message::Launch => {
                if let (Some(version), Some(workspace)) = (&self.selected_version, &self.selected_workspace) {
                    let v = version.clone();
                    let w = workspace.clone();
                    Task::perform(api::launch_cursor(v, w), Message::LaunchComplete)
                } else {
                    self.error = Some("Select a version and workspace".to_string());
                    Task::none()
                }
            }

            Message::LaunchComplete(result) => {
                match result {
                    Ok(()) => {
                        if let Some(ref path) = self.selected_workspace {
                            config::add_recent_workspace(&mut self.config, path.clone());
                            config::save_config(&self.config);
                        }
                        std::process::exit(0);
                    }
                    Err(e) => self.error = Some(e),
                }
                Task::none()
            }

            Message::ToggleAutoUpdate => {
                self.config.auto_update = !self.config.auto_update;
                config::save_config(&self.config);
                Task::none()
            }

            Message::ToggleStartWithSynapsix => {
                self.config.start_with_synapsix = !self.config.start_with_synapsix;
                config::save_config(&self.config);
                Task::none()
            }

            Message::RefreshSynapsixStatus => {
                Task::perform(api::check_synapsix_status(), Message::SynapsixStatusUpdated)
            }

            Message::SynapsixStatusUpdated(status) => {
                self.synapsix_status = status;
                Task::none()
            }
            
            Message::DismissError => {
                self.error = None;
                Task::none()
            }
        }
    }

    fn view(&self) -> Element<Message> {
        let header = self.view_header();
        let content = match self.tab {
            Tab::Launch => self.view_launch(),
            Tab::Versions => self.view_versions(),
            Tab::Settings => self.view_settings(),
        };

        let error_banner: Element<Message> = if let Some(ref err) = self.error {
            container(
                row![
                    text(err).color(iced::Color::from_rgb(0.9, 0.3, 0.3)),
                    Space::new().width(Fill),
                    button(text("×")).on_press(Message::DismissError),
                ]
                .spacing(10)
                .align_y(Alignment::Center)
            )
            .padding(10)
            .width(Length::Fill)
            .into()
        } else {
            container(text("")).into()
        };

        container(
            column![
                header,
                error_banner,
                content,
            ]
            .spacing(10)
        )
        .padding(20)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    fn theme(&self) -> Theme {
        match dark_light::detect() {
            Ok(dark_light::Mode::Dark) => Theme::Dark,
            Ok(dark_light::Mode::Light) => Theme::Light,
            _ => Theme::Dark,
        }
    }

    fn view_header(&self) -> Element<Message> {
        let primary_style = |_theme: &Theme, _status: button::Status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.4, 0.8))),
            text_color: iced::Color::WHITE,
            border: iced::Border::default().rounded(4),
            ..Default::default()
        };
        
        let secondary_style = |_theme: &Theme, _status: button::Status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.3, 0.3))),
            text_color: iced::Color::from_rgb(0.9, 0.9, 0.9),
            border: iced::Border::default().rounded(4),
            ..Default::default()
        };

        row![
            button(text("Launch").size(14))
                .on_press(Message::GoToTab(Tab::Launch))
                .style(if self.tab == Tab::Launch { primary_style } else { secondary_style }),
            button(text("Versions").size(14))
                .on_press(Message::GoToTab(Tab::Versions))
                .style(if self.tab == Tab::Versions { primary_style } else { secondary_style }),
            button(text("Settings").size(14))
                .on_press(Message::GoToTab(Tab::Settings))
                .style(if self.tab == Tab::Settings { primary_style } else { secondary_style }),
            Space::new().width(Fill),
            self.view_synapsix_indicator(),
        ]
        .spacing(10)
        .into()
    }

    fn view_synapsix_indicator(&self) -> Element<Message> {
        let (status_text, color) = if self.synapsix_status.daemon_running {
            ("Synapsix: Running", iced::Color::from_rgb(0.3, 0.8, 0.3))
        } else {
            ("Synapsix: Not running", iced::Color::from_rgb(0.6, 0.6, 0.6))
        };

        text(status_text)
            .size(12)
            .color(color)
            .into()
    }

    fn view_launch(&self) -> Element<Message> {
        let version_selector: Element<Message> = if self.versions.is_empty() {
            text("Loading versions...").into()
        } else {
            let installed: Vec<_> = self.versions.iter()
                .filter(|v| v.installed)
                .map(|v| v.version.clone())
                .collect();

            if installed.is_empty() {
                column![
                    text("No Cursor versions installed"),
                    button(text("Go to Versions"))
                        .on_press(Message::GoToTab(Tab::Versions)),
                ]
                .spacing(10)
                .into()
            } else {
                column![
                    text("Cursor Version:"),
                    pick_list(installed, self.selected_version.clone(), Message::SelectVersion)
                        .width(Length::Fill),
                ]
                .spacing(5)
                .into()
            }
        };

        let workspace_selector = {
            let workspace_names: Vec<String> = self.workspaces.iter()
                .map(|w| w.name.clone())
                .collect();

            let selected_name = self.selected_workspace.as_ref()
                .and_then(|p| self.workspaces.iter().find(|w| &w.path == p))
                .map(|w| w.name.clone());

            let workspaces_clone = self.workspaces.clone();
            
            column![
                text("Workspace:"),
                row![
                    pick_list(workspace_names, selected_name, move |name| {
                        let ws = workspaces_clone.iter().find(|w| w.name == name);
                        Message::SelectWorkspace(ws.map(|w| w.path.clone()).unwrap_or_default())
                    })
                    .width(Length::FillPortion(3)),
                    button(text("Browse..."))
                        .on_press(Message::BrowseWorkspace),
                ]
                .spacing(10),
            ]
            .spacing(5)
        };

        let can_launch = self.selected_version.is_some()
            && self.selected_workspace.is_some()
            && self.downloading.is_none();

        let launch_button = button(
            text("Launch Cursor")
                .size(18)
                .center()
        )
        .width(Length::Fill)
        .padding(15)
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.6, 0.3))),
            text_color: iced::Color::WHITE,
            border: iced::Border::default().rounded(6),
            ..Default::default()
        })
        .on_press_maybe(if can_launch { Some(Message::Launch) } else { None });

        column![
            version_selector,
            workspace_selector,
            container(launch_button)
                .padding(20),
        ]
        .spacing(20)
        .into()
    }

    fn view_versions(&self) -> Element<Message> {
        if self.loading {
            return text("Loading versions...").into();
        }

        let version_list: Element<Message> = scrollable(
            column(
                self.versions.iter().map(|v| {
                    let status = if v.installed {
                        text("Installed").color(iced::Color::from_rgb(0.3, 0.8, 0.3))
                    } else {
                        text("Not installed").color(iced::Color::from_rgb(0.6, 0.6, 0.6))
                    };

                    let action: Element<Message> = if v.installed {
                        button(text("Select"))
                            .on_press(Message::SelectVersion(v.version.clone()))
                            .style(|_theme, _status| button::Style {
                                background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.3, 0.3))),
                                text_color: iced::Color::from_rgb(0.9, 0.9, 0.9),
                                border: iced::Border::default().rounded(4),
                                ..Default::default()
                            })
                            .into()
                    } else if self.downloading.as_ref().map(|(dv, _)| dv) == Some(&v.version) {
                        let progress = self.downloading.as_ref().map(|(_, p)| *p).unwrap_or(0.0);
                        button(text(format!("{:.0}%", progress * 100.0)))
                            .style(|_theme, _status| button::Style {
                                background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.3, 0.3))),
                                text_color: iced::Color::from_rgb(0.9, 0.9, 0.9),
                                border: iced::Border::default().rounded(4),
                                ..Default::default()
                            })
                            .into()
                    } else {
                        button(text("Download"))
                            .on_press(Message::DownloadVersion(v.version.clone()))
                            .style(|_theme, _status| button::Style {
                                background: Some(iced::Background::Color(iced::Color::from_rgb(0.2, 0.4, 0.8))),
                                text_color: iced::Color::WHITE,
                                border: iced::Border::default().rounded(4),
                                ..Default::default()
                            })
                            .into()
                    };

                    row![
                        column![
                            text(&v.version).size(14),
                            text(v.date.as_deref().unwrap_or("")).size(11)
                                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
                        ]
                        .width(Length::FillPortion(2)),
                        container(status).width(Length::FillPortion(1)),
                        action,
                    ]
                    .spacing(10)
                    .padding(10)
                    .align_y(Alignment::Center)
                    .into()
                })
            )
            .spacing(5)
        )
        .height(Length::Fill)
        .into();

        column![
            text("Cursor Versions").size(18),
            version_list,
        ]
        .spacing(10)
        .into()
    }

    fn view_settings(&self) -> Element<Message> {
        let toggle_style = |is_on: bool| {
            move |_theme: &Theme, _status: button::Status| button::Style {
                background: Some(iced::Background::Color(if is_on {
                    iced::Color::from_rgb(0.2, 0.6, 0.3)
                } else {
                    iced::Color::from_rgb(0.3, 0.3, 0.3)
                })),
                text_color: iced::Color::WHITE,
                border: iced::Border::default().rounded(4),
                ..Default::default()
            }
        };

        column![
            text("Settings").size(18),
            
            row![
                text("Auto-update check on launch"),
                Space::new().width(Fill),
                button(text(if self.config.auto_update { "ON" } else { "OFF" }))
                    .on_press(Message::ToggleAutoUpdate)
                    .style(toggle_style(self.config.auto_update)),
            ]
            .spacing(10)
            .align_y(Alignment::Center),
            
            row![
                text("Start Synapsix with Cursor"),
                Space::new().width(Fill),
                button(text(if self.config.start_with_synapsix { "ON" } else { "OFF" }))
                    .on_press(Message::ToggleStartWithSynapsix)
                    .style(toggle_style(self.config.start_with_synapsix)),
            ]
            .spacing(10)
            .align_y(Alignment::Center),
            
            container(column![
                text("Synapsix Status").size(14),
                text(format!("Daemon: {}", if self.synapsix_status.daemon_running { "Running" } else { "Stopped" })),
                text(format!("Dialog: {}", if self.synapsix_status.dialog_available { "Available" } else { "Unavailable" })),
                text(format!("Terminal Monitor: {}", if self.synapsix_status.terminal_monitor { "Active" } else { "Inactive" })),
            ].spacing(5))
            .padding(15),
        ]
        .spacing(20)
        .into()
    }
}
