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
    /// Pending mode change (for confirmation)
    pub pending_mode_change: Option<OrchestratorMode>,
    /// Stats for display
    pub stats: OrchestratorStats,
}

/// Statistics for the orchestrator panel
#[derive(Debug, Clone, Default)]
pub struct OrchestratorStats {
    /// Total dialogs handled today
    pub dialogs_today: u32,
    /// Auto-handled dialogs today
    pub auto_handled_today: u32,
    /// User-handled dialogs today
    pub user_handled_today: u32,
    /// Average response time (ms)
    pub avg_response_time_ms: u64,
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
            pending_mode_change: None,
            stats: OrchestratorStats::default(),
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
        let timeout = std::time::Duration::from_secs(30);
        self.engine.add_to_triage(TriageItem {
            dialog,
            state,
            added_at: Instant::now(),
            suggested_response: None,
            reasoning,
            timeout,
        });
    }

    /// Add a dialog to the triage queue with full parameters
    pub fn add_to_triage_full(
        &mut self,
        dialog: PendingDialog,
        state: TriageState,
        reasoning: String,
        suggested_response: Option<String>,
        timeout: std::time::Duration,
    ) {
        self.engine.add_to_triage(TriageItem {
            dialog,
            state,
            added_at: Instant::now(),
            suggested_response,
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
    /// Request mode change (shows confirmation for dangerous modes)
    RequestModeChange(OrchestratorMode),
    /// Confirm pending mode change
    ConfirmModeChange,
    /// Cancel pending mode change
    CancelModeChange,
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
    /// Select a decision from history to view details
    SelectDecision(Option<String>),
}

/// Render the orchestrator mode selector
pub fn view_mode_selector<'a, Message: Clone + 'a>(
    state: &'a OrchestratorPanelState,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let modes = [
        (
            OrchestratorMode::UserActive,
            "🟢",
            "User Active",
            "You handle all dialogs",
            false,
        ),
        (
            OrchestratorMode::UserDelegate,
            "🟡",
            "Delegated",
            "Auto-handle routine, escalate high/critical",
            false,
        ),
        (
            OrchestratorMode::Spectator,
            "🟠",
            "Spectator",
            "Auto-handle all, you can claim within timeout",
            true,
        ),
        (
            OrchestratorMode::Autonomous,
            "🔴",
            "Autonomous",
            "Full auto, critical queued for later",
            true,
        ),
    ];

    let current_mode = state.mode;
    let duration = state.mode_duration();
    let duration_str = format_duration(duration);

    let mode_buttons: Vec<Element<'_, Message>> = modes
        .iter()
        .map(|(mode, emoji, label, _desc, requires_confirm)| {
            let is_current = *mode == current_mode;
            let to_msg = to_message.clone();
            let mode_val = *mode;
            let needs_confirm = *requires_confirm && !is_current;

            button(
                row![
                    text(*emoji).size(16),
                    Space::new().width(4),
                    text(*label).size(12),
                ]
                .align_y(Alignment::Center),
            )
            .padding([6, 12])
            .on_press(if needs_confirm {
                to_msg(OrchestratorMessage::RequestModeChange(mode_val))
            } else {
                to_msg(OrchestratorMessage::SetMode(mode_val))
            })
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
                        iced::widget::button::Status::Hovered => {
                            iced::Color::from_rgb(0.25, 0.25, 0.28)
                        }
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

    // Stats row
    let stats = &state.stats;
    let stats_row = row![
        text(format!("📊 Today: {} dialogs", stats.dialogs_today))
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(16),
        text(format!("🤖 Auto: {}", stats.auto_handled_today))
            .size(10)
            .color(iced::Color::from_rgb(0.4, 0.6, 0.4)),
        Space::new().width(8),
        text(format!("👤 Manual: {}", stats.user_handled_today))
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.6, 0.7)),
    ]
    .align_y(Alignment::Center);

    column![
        text("Orchestrator Mode").size(14),
        Space::new().height(8),
        mode_row,
        Space::new().height(4),
        status_text,
        Space::new().height(6),
        stats_row,
    ]
    .into()
}

/// Render mode change confirmation dialog
pub fn view_mode_confirm_dialog<'a, Message: Clone + 'a>(
    pending_mode: &OrchestratorMode,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let to_msg = to_message.clone();
    let to_msg2 = to_message.clone();

    let warning_text = match pending_mode {
        OrchestratorMode::Spectator => {
            "Spectator mode will auto-handle ALL dialogs including high priority ones. You can claim within timeout window."
        }
        OrchestratorMode::Autonomous => {
            "⚠️ Autonomous mode gives full control to agents. Critical dialogs will be queued but NOT blocked. Use with caution!"
        }
        _ => "Confirm mode change?",
    };

    let warning_color = match pending_mode {
        OrchestratorMode::Autonomous => iced::Color::from_rgb(0.9, 0.4, 0.4),
        _ => iced::Color::from_rgb(0.8, 0.7, 0.5),
    };

    container(
        column![
            row![
                text("⚠️ Confirm Mode Change").size(14),
                Space::new().width(Length::Fill),
            ],
            Space::new().height(8),
            text(format!(
                "Switch to {} {}?",
                pending_mode.emoji(),
                pending_mode.label()
            ))
            .size(13),
            Space::new().height(8),
            text(warning_text).size(11).color(warning_color),
            Space::new().height(12),
            row![
                button(text("Cancel").size(12))
                    .padding([6, 16])
                    .on_press(to_msg(OrchestratorMessage::CancelModeChange))
                    .style(|_theme, status| {
                        let bg = match status {
                            iced::widget::button::Status::Hovered => {
                                iced::Color::from_rgb(0.25, 0.25, 0.28)
                            }
                            _ => iced::Color::from_rgb(0.18, 0.18, 0.2),
                        };
                        iced::widget::button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
                Space::new().width(8),
                button(text("Confirm").size(12))
                    .padding([6, 16])
                    .on_press(to_msg2(OrchestratorMessage::ConfirmModeChange))
                    .style(|_theme, status| {
                        let bg = match status {
                            iced::widget::button::Status::Hovered => {
                                iced::Color::from_rgb(0.5, 0.35, 0.2)
                            }
                            _ => iced::Color::from_rgb(0.4, 0.28, 0.15),
                        };
                        iced::widget::button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
            ]
            .align_y(Alignment::Center),
        ]
        .spacing(0),
    )
    .padding(12)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.12, 0.1,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 2.0,
            color: iced::Color::from_rgb(0.4, 0.3, 0.2),
        },
        ..Default::default()
    })
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
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.3, 0.15, 0.15,
            ))),
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
        text(source_badge)
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
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
    let to_msg3 = to_message.clone();
    let to_msg4 = to_message.clone();

    let connection_status = if state.daemon_connected {
        row![
            text("●")
                .size(8)
                .color(iced::Color::from_rgb(0.3, 0.7, 0.3)),
            Space::new().width(4),
            text("Connected")
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
    } else {
        row![
            text("●")
                .size(8)
                .color(iced::Color::from_rgb(0.7, 0.3, 0.3)),
            Space::new().width(4),
            text("Disconnected")
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        ]
    };

    let header = row![
        text("🎛️ Orchestrator").size(16),
        Space::new().width(Length::Fill),
        connection_status,
    ]
    .align_y(Alignment::Center);

    let undoable_count = state.engine.undoable_decisions().len();
    let history_count = state.engine.recent_history(10).len();

    let undo_btn = if undoable_count > 0 {
        button(text(format!("↩ Undo ({})", undoable_count)).size(11))
            .padding([4, 8])
            .on_press(to_msg(OrchestratorMessage::UndoLastDecision))
            .style(|_theme, _status| iced::widget::button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.25, 0.25, 0.3,
                ))),
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
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.15, 0.15, 0.18,
                ))),
                text_color: iced::Color::from_rgb(0.4, 0.4, 0.4),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            })
    };

    let history_btn = button(
        text(if state.show_history {
            format!("📜 History ({})", history_count)
        } else {
            format!("📜 ({})", history_count)
        })
        .size(11),
    )
    .padding([4, 8])
    .on_press(to_msg3(OrchestratorMessage::ToggleHistory))
    .style(move |_theme, status| {
        let bg = if state.show_history {
            iced::Color::from_rgb(0.25, 0.28, 0.35)
        } else {
            match status {
                iced::widget::button::Status::Hovered => iced::Color::from_rgb(0.22, 0.22, 0.25),
                _ => iced::Color::from_rgb(0.15, 0.15, 0.18),
            }
        };
        iced::widget::button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: if state.show_history {
                iced::Color::WHITE
            } else {
                iced::Color::from_rgb(0.6, 0.6, 0.6)
            },
            border: iced::Border {
                radius: 3.0.into(),
                ..Default::default()
            },
            ..Default::default()
        }
    });

    // Build the content column
    let mut content = column![
        header,
        Space::new().height(12),
        view_mode_selector(state, to_msg2.clone()),
    ]
    .spacing(0);

    // Add confirmation dialog if pending mode change
    if let Some(ref pending_mode) = state.pending_mode_change {
        content = content.push(Space::new().height(12));
        content = content.push(view_mode_confirm_dialog(pending_mode, to_msg4));
    }

    content = content.push(Space::new().height(16));
    content = content.push(
        container(Space::new().height(1)).style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.25, 0.25, 0.28,
            ))),
            ..Default::default()
        }),
    );
    content = content.push(Space::new().height(12));
    content = content.push(view_triage_queue(state, to_msg2));

    // Add history panel if enabled
    if state.show_history {
        content = content.push(Space::new().height(12));
        content = content.push(
            container(Space::new().height(1)).style(|_| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.25, 0.25, 0.28,
                ))),
                ..Default::default()
            }),
        );
        content = content.push(Space::new().height(12));
        content = content.push(view_decision_history(state, to_message.clone()));
    }

    content = content.push(Space::new().height(8));
    content = content.push(
        row![history_btn, Space::new().width(Length::Fill), undo_btn,].align_y(Alignment::Center),
    );

    container(content)
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

/// Render the decision history panel
pub fn view_decision_history<'a, Message: Clone + 'a>(
    state: &'a OrchestratorPanelState,
    to_message: impl Fn(OrchestratorMessage) -> Message + Clone + 'a,
) -> Element<'a, Message> {
    let recent = state.engine.recent_history(10);

    if recent.is_empty() {
        return column![
            row![
                text("📜 Decision History").size(13),
                Space::new().width(Length::Fill),
                button(text("Hide").size(10))
                    .padding([3, 8])
                    .on_press(to_message(OrchestratorMessage::ToggleHistory))
                    .style(|_theme, status| {
                        let bg = match status {
                            iced::widget::button::Status::Hovered => {
                                iced::Color::from_rgb(0.22, 0.22, 0.25)
                            }
                            _ => iced::Color::from_rgb(0.15, 0.15, 0.18),
                        };
                        iced::widget::button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: iced::Color::from_rgb(0.6, 0.6, 0.6),
                            border: iced::Border {
                                radius: 3.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
            ]
            .align_y(Alignment::Center),
            Space::new().height(8),
            container(
                text("No decisions recorded yet")
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            )
            .padding(12),
        ]
        .into();
    }

    let items: Vec<Element<'_, Message>> = recent
        .iter()
        .map(|record| {
            let priority_color = match record.priority {
                DialogPriority::Low => iced::Color::from_rgb(0.4, 0.4, 0.4),
                DialogPriority::Normal => iced::Color::from_rgb(0.6, 0.6, 0.6),
                DialogPriority::High => iced::Color::from_rgb(0.9, 0.6, 0.2),
                DialogPriority::Critical => iced::Color::from_rgb(0.9, 0.2, 0.2),
            };

            let auto_badge = if record.auto_handled {
                container(text("🤖 Auto").size(9).color(iced::Color::WHITE))
                    .padding([1, 4])
                    .style(|_| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.2, 0.35, 0.25,
                        ))),
                        border: iced::Border {
                            radius: 2.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    })
            } else {
                container(text("👤 User").size(9).color(iced::Color::WHITE))
                    .padding([1, 4])
                    .style(|_| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.25, 0.3, 0.4,
                        ))),
                        border: iced::Border {
                            radius: 2.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    })
            };

            let timestamp_str = format_timestamp(record.timestamp);
            let response_preview: String = if record.response.len() > 30 {
                format!("{}...", &record.response[..30])
            } else {
                record.response.clone()
            };
            let dialog_id_short = record.dialog_id[..8.min(record.dialog_id.len())].to_string();
            let reasoning_text = record.reasoning.clone();

            container(
                column![
                    row![
                        text(record.priority.emoji()).size(12).color(priority_color),
                        Space::new().width(6),
                        text(dialog_id_short)
                            .size(11)
                            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                        Space::new().width(Length::Fill),
                        auto_badge,
                        Space::new().width(8),
                        text(timestamp_str)
                            .size(10)
                            .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                    ]
                    .align_y(Alignment::Center),
                    Space::new().height(4),
                    row![
                        text("→")
                            .size(10)
                            .color(iced::Color::from_rgb(0.4, 0.5, 0.4)),
                        Space::new().width(4),
                        text(response_preview)
                            .size(11)
                            .color(iced::Color::from_rgb(0.6, 0.7, 0.6)),
                    ],
                    text(reasoning_text)
                        .size(10)
                        .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
                ]
                .spacing(2),
            )
            .padding(8)
            .style(|_| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.12, 0.12, 0.14,
                ))),
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            })
            .into()
        })
        .collect();

    let to_msg = to_message.clone();

    column![
        row![
            text("📜 Decision History").size(13),
            Space::new().width(8),
            text(format!("({} recent)", recent.len()))
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().width(Length::Fill),
            button(text("Hide").size(10))
                .padding([3, 8])
                .on_press(to_msg(OrchestratorMessage::ToggleHistory))
                .style(|_theme, status| {
                    let bg = match status {
                        iced::widget::button::Status::Hovered => {
                            iced::Color::from_rgb(0.22, 0.22, 0.25)
                        }
                        _ => iced::Color::from_rgb(0.15, 0.15, 0.18),
                    };
                    iced::widget::button::Style {
                        background: Some(iced::Background::Color(bg)),
                        text_color: iced::Color::from_rgb(0.6, 0.6, 0.6),
                        border: iced::Border {
                            radius: 3.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }
                }),
        ]
        .align_y(Alignment::Center),
        Space::new().height(8),
        scrollable(column(items).spacing(6)).height(Length::Fixed(200.0)),
    ]
    .into()
}

/// Format a Unix timestamp for display
fn format_timestamp(ts: u64) -> String {
    use std::time::{Duration, SystemTime, UNIX_EPOCH};
    let time = UNIX_EPOCH + Duration::from_secs(ts);
    let now = SystemTime::now();
    if let Ok(elapsed) = now.duration_since(time) {
        let secs = elapsed.as_secs();
        if secs < 60 {
            format!("{}s ago", secs)
        } else if secs < 3600 {
            format!("{}m ago", secs / 60)
        } else if secs < 86400 {
            format!("{}h ago", secs / 3600)
        } else {
            format!("{}d ago", secs / 86400)
        }
    } else {
        "just now".to_string()
    }
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli_agents::{DialogOption, DialogPriority, DialogSource, PendingDialog};

    fn make_test_dialog(priority: DialogPriority) -> PendingDialog {
        PendingDialog {
            id: "test-dialog-1".to_string(),
            title: "Test Dialog".to_string(),
            prompt: "Do you want to continue?".to_string(),
            dialog_type: "choice".to_string(),
            options: Some(vec![
                DialogOption {
                    value: "yes".to_string(),
                    label: "Yes".to_string(),
                    description: None,
                },
                DialogOption {
                    value: "no".to_string(),
                    label: "No".to_string(),
                    description: None,
                },
            ]),
            created_at: None,
            agent_id: Some("agent-123".to_string()),
            source: DialogSource::SessionAgent,
            priority,
            workspace: Some("/home/user/project".to_string()),
            orchestrator_id: None,
        }
    }

    #[test]
    fn test_orchestrator_state_default() {
        let state = OrchestratorPanelState::default();
        assert_eq!(state.mode, OrchestratorMode::UserActive);
        assert!(!state.daemon_connected);
        assert!(!state.show_history);
        assert!(state.pending_mode_change.is_none());
    }

    #[test]
    fn test_set_mode_updates_engine() {
        let mut state = OrchestratorPanelState::default();
        assert_eq!(state.mode, OrchestratorMode::UserActive);

        state.set_mode(OrchestratorMode::Autonomous);
        assert_eq!(state.mode, OrchestratorMode::Autonomous);
        assert_eq!(state.engine.mode(), OrchestratorMode::Autonomous);
    }

    #[test]
    fn test_add_to_triage_basic() {
        let mut state = OrchestratorPanelState::default();
        let dialog = make_test_dialog(DialogPriority::Normal);

        assert!(state.engine.triage_queue().is_empty());

        state.add_to_triage(
            dialog.clone(),
            TriageState::AutoApprove,
            "Test reason".to_string(),
        );

        assert_eq!(state.engine.triage_queue().len(), 1);
        assert_eq!(state.engine.triage_queue()[0].dialog.id, "test-dialog-1");
        assert_eq!(
            state.engine.triage_queue()[0].state,
            TriageState::AutoApprove
        );
    }

    #[test]
    fn test_add_to_triage_full() {
        let mut state = OrchestratorPanelState::default();
        let dialog = make_test_dialog(DialogPriority::High);
        let timeout = std::time::Duration::from_secs(60);

        state.add_to_triage_full(
            dialog.clone(),
            TriageState::Manual,
            "High priority needs user".to_string(),
            Some("yes".to_string()),
            timeout,
        );

        assert_eq!(state.engine.triage_queue().len(), 1);
        let item = &state.engine.triage_queue()[0];
        assert_eq!(item.state, TriageState::Manual);
        assert_eq!(item.suggested_response, Some("yes".to_string()));
        assert_eq!(item.timeout, timeout);
    }

    #[test]
    fn test_mode_duration() {
        let state = OrchestratorPanelState::default();
        let duration = state.mode_duration();
        assert!(duration.as_secs() < 1);
    }

    #[test]
    fn test_stats_default() {
        let stats = OrchestratorStats::default();
        assert_eq!(stats.dialogs_today, 0);
        assert_eq!(stats.auto_handled_today, 0);
        assert_eq!(stats.user_handled_today, 0);
        assert_eq!(stats.avg_response_time_ms, 0);
    }

    #[test]
    fn test_format_duration() {
        assert_eq!(format_duration(std::time::Duration::from_secs(5)), "5s");
        assert_eq!(format_duration(std::time::Duration::from_secs(65)), "1m 5s");
        assert_eq!(
            format_duration(std::time::Duration::from_secs(3665)),
            "1h 1m"
        );
    }

    #[test]
    fn test_format_timestamp_recent() {
        use std::time::{SystemTime, UNIX_EPOCH};
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_secs();
        let result = format_timestamp(now - 30);
        assert!(result.ends_with("s ago"));
    }

    #[test]
    fn test_pending_mode_change_workflow() {
        let mut state = OrchestratorPanelState::default();

        // No pending change initially
        assert!(state.pending_mode_change.is_none());

        // Set pending mode change (simulates RequestModeChange)
        state.pending_mode_change = Some(OrchestratorMode::Autonomous);
        assert_eq!(
            state.pending_mode_change,
            Some(OrchestratorMode::Autonomous)
        );

        // Confirm (simulates ConfirmModeChange)
        let confirmed_mode = state.pending_mode_change.take();
        assert!(state.pending_mode_change.is_none());
        assert_eq!(confirmed_mode, Some(OrchestratorMode::Autonomous));
    }

    #[test]
    fn test_cancel_mode_change() {
        let mut state = OrchestratorPanelState {
            pending_mode_change: Some(OrchestratorMode::Spectator),
            ..Default::default()
        };
        assert!(state.pending_mode_change.is_some());

        // Cancel (simulates CancelModeChange)
        state.pending_mode_change = None;
        assert!(state.pending_mode_change.is_none());
    }
}
