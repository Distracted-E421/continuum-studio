//! Orchestrator Mode Panel
//!
//! UI component for managing orchestrator mode and decision triage queue.
//! Designed to be integrated into the CLI Agents view.

use iced::widget::{button, column, container, row, scrollable, text, Space};
use iced::{Alignment, Element, Length};
use std::time::Instant;

use crate::cli_agents::{DialogPriority, DialogSource, PendingDialog};
use crate::decision_engine::{DecisionEngine, DecisionEngineConfig, TriageItem, TriageState};
use crate::dialog_client::OrchestratorMode;

/// State for the orchestrator panel
#[derive(Debug)]
pub struct OrchestratorPanelState {
    /// Current orchestrator mode
    pub mode: OrchestratorMode,
    /// Decision engine
    pub engine: DecisionEngine,
    /// Whether the daemon is connected
    pub daemon_connected: bool,
    /// Mode change timestamp
    pub mode_since: Instant,
    /// Selected decision for details view
    pub selected_decision: Option<String>,
    /// Whether to show history panel
    pub show_history: bool,
}

impl Default for OrchestratorPanelState {
    fn default() -> Self {
        Self {
            mode: OrchestratorMode::UserActive,
            engine: DecisionEngine::new(DecisionEngineConfig::default()),
            daemon_connected: false,
            mode_since: Instant::now(),
            selected_decision: None,
            show_history: false,
        }
    }
}

impl OrchestratorPanelState {
    pub fn new() -> Self {
        Self::default()
    }

    /// Update the orchestrator mode
    pub fn set_mode(&mut self, mode: OrchestratorMode) {
        self.mode = mode;
        self.mode_since = Instant::now();
        self.engine.set_mode(mode);
    }

    /// Add a dialog to the triage queue
    pub fn add_to_triage(&mut self, dialog: PendingDialog, state: TriageState, reasoning: String) {
        let timeout = std::time::Duration::from_secs(30); // Default
        self.engine.add_to_triage(TriageItem {
            dialog,
            state,
            added_at: Instant::now(),
            suggested_response: None,
            reasoning,
            timeout,
        });
    }

    /// Get duration in current mode
    pub fn mode_duration(&self) -> std::time::Duration {
        Instant::now().duration_since(self.mode_since)
    }
}

/// Messages for the orchestrator panel
#[derive(Debug, Clone)]
pub enum OrchestratorMessage {
    /// Change orchestrator mode (sends to daemon)
    SetMode(OrchestratorMode),
    /// Mode was refreshed from daemon
    ModeRefreshed(OrchestratorMode),
    /// Mode set result from daemon (with error handling)
    ModeSetResult(Result<OrchestratorMode, String>),
    /// Daemon connection status changed
    DaemonConnectionChanged(bool),
    /// Approve a triage item
    ApproveTriage(String),
    /// Decline a triage item
    DeclineTriage(String),
    /// Claim a dialog (stop auto-handling)
    ClaimDialog(String),
    /// Set triage state for an item
    SetTriageState(String, TriageState),
    /// Toggle history panel
    ToggleHistory,
    /// Undo last decision
    UndoLastDecision,
    /// Clear all decisions
    ClearTriage,
    /// Refresh from daemon
    RefreshMode,
    /// Result of responding to a triage item (dialog_id, success, error message)
    TriageResponseResult(String, Result<(), String>),
}

/// Render the orchestrator mode selector
pub fn view_mode_selector<'a, Message: Clone + 'a>(
    state: &'a OrchestratorPanelState,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let modes = [
        (OrchestratorMode::UserActive, "🟢", "User Active", "You handle all dialogs"),
        (OrchestratorMode::UserDelegate, "🟡", "Delegated", "Auto-handle routine, escalate high/critical"),
        (OrchestratorMode::Spectator, "🟠", "Spectator", "Auto-handle all, you can claim within timeout"),
        (OrchestratorMode::Autonomous, "🔴", "Autonomous", "Full auto, critical queued for later"),
    ];

    let current_mode = state.mode;
    let duration = state.mode_duration();
    let duration_str = format_duration(duration);

    let mode_buttons: Vec<Element<'_, Message>> = modes
        .iter()
        .map(|(mode, emoji, label, _desc)| {
            let is_current = *mode == current_mode;
            let to_msg = to_message.clone();
            let mode_val = *mode;
            
            button(
                row![
                    text(*emoji).size(16),
                    Space::new().width(4),
                    text(*label).size(12),
                ]
                .align_y(Alignment::Center),
            )
            .padding([6, 12])
            .on_press(to_msg(OrchestratorMessage::SetMode(mode_val)))
            .style(move |_theme, status| {
                let bg = if is_current {
                    match mode_val {
                        OrchestratorMode::UserActive => iced::Color::from_rgb(0.2, 0.5, 0.2),
                        OrchestratorMode::UserDelegate => iced::Color::from_rgb(0.5, 0.45, 0.1),
                        OrchestratorMode::Spectator => iced::Color::from_rgb(0.5, 0.35, 0.1),
                        OrchestratorMode::Autonomous => iced::Color::from_rgb(0.5, 0.2, 0.2),
                    }
                } else {
                    match status {
                        iced::widget::button::Status::Hovered => iced::Color::from_rgb(0.25, 0.25, 0.28),
                        _ => iced::Color::from_rgb(0.15, 0.15, 0.18),
                    }
                };
                iced::widget::button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: if is_current {
                        iced::Color::WHITE
                    } else {
                        iced::Color::from_rgb(0.7, 0.7, 0.7)
                    },
                    border: iced::Border {
                        radius: 4.0.into(),
                        width: if is_current { 1.0 } else { 0.0 },
                        color: iced::Color::from_rgb(0.4, 0.4, 0.4),
                    },
                    ..Default::default()
                }
            })
            .into()
        })
        .collect();

    let mode_row = row(mode_buttons).spacing(8);

    let status_text = text(format!(
        "{} {} for {}",
        current_mode.emoji(),
        current_mode.label(),
        duration_str
    ))
    .size(11)
    .color(iced::Color::from_rgb(0.5, 0.5, 0.5));

    column![
        text("Orchestrator Mode").size(14),
        Space::new().height(8),
        mode_row,
        Space::new().height(4),
        status_text,
    ]
    .into()
}

/// Render the triage queue
pub fn view_triage_queue<'a, Message: Clone + 'a>(
    state: &'a OrchestratorPanelState,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let queue = state.engine.triage_queue();

    if queue.is_empty() {
        return column![
            text("Decision Triage Queue").size(14),
            Space::new().height(8),
            container(
                text("No pending decisions")
                    .size(12)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            )
            .padding(16)
            .style(|_| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.14,
                ))),
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }),
        ]
        .into();
    }

    let items: Vec<Element<'_, Message>> = queue
        .iter()
        .map(|item| view_triage_item(item, to_message.clone()))
        .collect();

    let to_msg = to_message.clone();
    let clear_btn = button(text("Clear All").size(11))
        .padding([4, 8])
        .on_press(to_msg(OrchestratorMessage::ClearTriage))
        .style(|_theme, _status| iced::widget::button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.15, 0.15))),
            text_color: iced::Color::from_rgb(0.9, 0.6, 0.6),
            border: iced::Border {
                radius: 3.0.into(),
                ..Default::default()
            },
            ..Default::default()
        });

    column![
        row![
            text("Decision Triage Queue").size(14),
            Space::new().width(Length::Fill),
            text(format!("{} pending", queue.len()))
                .size(11)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().width(8),
            clear_btn,
        ]
        .align_y(Alignment::Center),
        Space::new().height(8),
        scrollable(column(items).spacing(8)).height(Length::FillPortion(1)),
    ]
    .into()
}

/// Render a single triage item
fn view_triage_item<'a, Message: Clone + 'a>(
    item: &'a TriageItem,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let dialog = &item.dialog;
    let elapsed = Instant::now().duration_since(item.added_at);
    let remaining = item.timeout.saturating_sub(elapsed);
    
    let state_emoji = match item.state {
        TriageState::Manual => "⏸️",
        TriageState::AutoApprove => "✅",
        TriageState::AutoDecline => "❌",
        TriageState::Paused => "⏹️",
    };

    let priority_color = match dialog.priority {
        DialogPriority::Low => iced::Color::from_rgb(0.4, 0.4, 0.4),
        DialogPriority::Normal => iced::Color::from_rgb(0.6, 0.6, 0.6),
        DialogPriority::High => iced::Color::from_rgb(0.9, 0.6, 0.2),
        DialogPriority::Critical => iced::Color::from_rgb(0.9, 0.2, 0.2),
    };

    let source_badge = match dialog.source {
        DialogSource::SessionAgent => "🤖 Session",
        DialogSource::SubAgent => "📦 Sub-agent",
        DialogSource::Orchestrator => "🎯 Orchestrator",
        DialogSource::External => "🌐 External",
    };

    let dialog_id = dialog.id.clone();
    let dialog_id2 = dialog.id.clone();
    let dialog_id3 = dialog.id.clone();
    let to_msg = to_message.clone();
    let to_msg2 = to_message.clone();
    let to_msg3 = to_message.clone();

    let approve_btn = button(text("✓").size(14))
        .padding([4, 8])
        .on_press(to_msg(OrchestratorMessage::ApproveTriage(dialog_id)))
        .style(|_theme, status| {
            let bg = match status {
                iced::widget::button::Status::Hovered => iced::Color::from_rgb(0.25, 0.5, 0.25),
                _ => iced::Color::from_rgb(0.2, 0.4, 0.2),
            };
            iced::widget::button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    let decline_btn = button(text("✕").size(14))
        .padding([4, 8])
        .on_press(to_msg2(OrchestratorMessage::DeclineTriage(dialog_id2)))
        .style(|_theme, status| {
            let bg = match status {
                iced::widget::button::Status::Hovered => iced::Color::from_rgb(0.5, 0.25, 0.25),
                _ => iced::Color::from_rgb(0.4, 0.2, 0.2),
            };
            iced::widget::button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    let claim_btn = button(text("Claim").size(11))
        .padding([3, 6])
        .on_press(to_msg3(OrchestratorMessage::ClaimDialog(dialog_id3)))
        .style(|_theme, status| {
            let bg = match status {
                iced::widget::button::Status::Hovered => iced::Color::from_rgb(0.3, 0.35, 0.5),
                _ => iced::Color::from_rgb(0.2, 0.25, 0.4),
            };
            iced::widget::button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    let title_owned = dialog.title.clone();
    let header_row = row![
        text(state_emoji).size(14),
        Space::new().width(6),
        text(title_owned).size(13),
        Space::new().width(Length::Fill),
        text(source_badge).size(10).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(8),
        text(dialog.priority.emoji()).size(12).color(priority_color),
    ]
    .align_y(Alignment::Center);

    let prompt_preview: String = if dialog.prompt.len() > 100 {
        format!("{}...", &dialog.prompt[..100])
    } else {
        dialog.prompt.clone()
    };
    
    let reasoning_owned = item.reasoning.clone();

    let info_row = row![
        text(format_duration(remaining))
            .size(10)
            .color(if remaining.as_secs() < 10 {
                iced::Color::from_rgb(0.9, 0.4, 0.4)
            } else {
                iced::Color::from_rgb(0.5, 0.5, 0.5)
            }),
        Space::new().width(Length::Fill),
        approve_btn,
        Space::new().width(4),
        decline_btn,
        Space::new().width(4),
        claim_btn,
    ]
    .align_y(Alignment::Center);

    container(
        column![
            header_row,
            Space::new().height(4),
            text(prompt_preview)
                .size(11)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(4),
            text(reasoning_owned)
                .size(10)
                .color(iced::Color::from_rgb(0.4, 0.5, 0.4)),
            Space::new().height(6),
            info_row,
        ]
        .spacing(2),
    )
    .padding(10)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.14, 0.14, 0.16,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.2, 0.2, 0.22),
        },
        ..Default::default()
    })
    .into()
}

/// Render the full orchestrator panel
pub fn view_orchestrator_panel<'a, Message: Clone + 'a>(
    state: &'a OrchestratorPanelState,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let to_msg = to_message.clone();
    let to_msg2 = to_message.clone();

    let connection_status = if state.daemon_connected {
        row![
            text("●").size(8).color(iced::Color::from_rgb(0.3, 0.7, 0.3)),
            Space::new().width(4),
            text("Connected").size(10).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
    } else {
        row![
            text("●").size(8).color(iced::Color::from_rgb(0.7, 0.3, 0.3)),
            Space::new().width(4),
            text("Disconnected").size(10).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
    };

    let header = row![
        text("🎛️ Orchestrator").size(16),
        Space::new().width(Length::Fill),
        connection_status,
    ]
    .align_y(Alignment::Center);

    let undoable_count = state.engine.undoable_decisions().len();
    let undo_btn = if undoable_count > 0 {
        button(text(format!("Undo ({})", undoable_count)).size(11))
            .padding([4, 8])
            .on_press(to_msg(OrchestratorMessage::UndoLastDecision))
            .style(|_theme, _status| iced::widget::button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.25, 0.25, 0.3))),
                text_color: iced::Color::from_rgb(0.7, 0.7, 0.9),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            })
    } else {
        button(text("No undos").size(11))
            .padding([4, 8])
            .style(|_theme, _status| iced::widget::button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.15, 0.15, 0.18))),
                text_color: iced::Color::from_rgb(0.4, 0.4, 0.4),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            })
    };

    container(
        column![
            header,
            Space::new().height(12),
            view_mode_selector(state, to_msg2.clone()),
            Space::new().height(16),
            container(Space::new().height(1)).style(|_| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(0.25, 0.25, 0.28))),
                ..Default::default()
            }),
            Space::new().height(12),
            view_triage_queue(state, to_msg2),
            Space::new().height(8),
            row![
                Space::new().width(Length::Fill),
                undo_btn,
            ],
        ]
        .spacing(0),
    )
    .padding(16)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.1, 0.1, 0.12,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.18, 0.18, 0.2),
        },
        ..Default::default()
    })
    .into()
}

/// Format a duration for display
fn format_duration(d: std::time::Duration) -> String {
    let secs = d.as_secs();
    if secs < 60 {
        format!("{}s", secs)
    } else if secs < 3600 {
        format!("{}m {}s", secs / 60, secs % 60)
    } else {
        format!("{}h {}m", secs / 3600, (secs % 3600) / 60)
    }
}
