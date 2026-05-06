//! Parked Agents Panel for Continuum Studio
//!
//! Displays agents that have been parked (idle, awaiting task assignment).
//! Allows assigning tasks to unpark and resume agent execution.

use chrono::{DateTime, Utc};
use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};

// =============================================================================
// Data Types
// =============================================================================

/// A parked agent awaiting task assignment
#[derive(Debug, Clone)]
pub struct ParkedAgent {
    pub agent_id: String,
    pub workspace: String,
    pub parked_at: DateTime<Utc>,
    pub capabilities: Vec<String>,
    pub last_heartbeat: DateTime<Utc>,
}

impl ParkedAgent {
    /// Shorten agent ID for display (first 8 chars)
    pub fn short_id(&self) -> String {
        if self.agent_id.len() <= 8 {
            self.agent_id.clone()
        } else {
            format!("{}…", &self.agent_id[..8])
        }
    }

    /// Relative time string (e.g., "5 min ago")
    pub fn time_parked_ago(&self) -> String {
        let now = Utc::now();
        let elapsed = now - self.parked_at;

        if elapsed.num_seconds() < 60 {
            format!("{}s ago", elapsed.num_seconds())
        } else if elapsed.num_minutes() < 60 {
            format!("{} min ago", elapsed.num_minutes())
        } else if elapsed.num_hours() < 24 {
            format!("{} hr ago", elapsed.num_hours())
        } else {
            format!("{} days ago", elapsed.num_days())
        }
    }
}

/// Panel state for parked agents
#[derive(Debug, Clone, Default)]
pub struct ParkedAgentsPanelState {
    pub agents: Vec<ParkedAgent>,
    pub selected_agent: Option<String>,
    /// Task assignment modal state
    pub show_assign_modal: bool,
    pub assign_agent_id: Option<String>,
    pub assign_task_input: String,
    pub error: Option<String>,
}

// =============================================================================
// Messages
// =============================================================================

/// Messages for parked agents panel
#[derive(Debug, Clone)]
pub enum ParkedMessage {
    RefreshList,
    AgentParked(ParkedAgent),
    AgentUnparked(String),
    AssignTask(String),
    UpdateTaskInput(String),
    /// Submit task - reads task from state.assign_task_input
    SubmitTask(String),
    CloseAssignModal,
    AgentsLoaded(Vec<ParkedAgent>),
    Error(String),
    ClearError,
}

// =============================================================================
// Async Tasks
// =============================================================================

/// Tasks to be executed by the main application
#[derive(Debug, Clone)]
pub enum ParkedAgentTask {
    FetchParkedAgents,
    UnparkAndAssignTask {
        agent_id: String,
        task_description: String,
    },
}

// =============================================================================
// Update Logic
// =============================================================================

impl ParkedAgentsPanelState {
    pub fn update(&mut self, message: ParkedMessage) -> Option<ParkedAgentTask> {
        match message {
            ParkedMessage::RefreshList => Some(ParkedAgentTask::FetchParkedAgents),
            ParkedMessage::AgentParked(agent) => {
                if !self.agents.iter().any(|a| a.agent_id == agent.agent_id) {
                    self.agents.push(agent);
                }
                None
            }
            ParkedMessage::AgentUnparked(agent_id) => {
                self.agents.retain(|a| a.agent_id != agent_id);
                if self.selected_agent.as_ref() == Some(&agent_id) {
                    self.selected_agent = None;
                }
                if self.assign_agent_id.as_ref() == Some(&agent_id) {
                    self.show_assign_modal = false;
                    self.assign_agent_id = None;
                    self.assign_task_input.clear();
                }
                None
            }
            ParkedMessage::AssignTask(agent_id) => {
                self.show_assign_modal = true;
                self.assign_agent_id = Some(agent_id);
                self.assign_task_input.clear();
                self.error = None;
                None
            }
            ParkedMessage::UpdateTaskInput(s) => {
                self.assign_task_input = s;
                None
            }
            ParkedMessage::SubmitTask(agent_id) => {
                let task_description = self.assign_task_input.trim().to_string();
                if task_description.is_empty() {
                    self.error = Some("Task description cannot be empty".to_string());
                    return None;
                }
                self.show_assign_modal = false;
                self.assign_agent_id = None;
                self.assign_task_input.clear();
                self.error = None;
                Some(ParkedAgentTask::UnparkAndAssignTask {
                    agent_id,
                    task_description,
                })
            }
            ParkedMessage::CloseAssignModal => {
                self.show_assign_modal = false;
                self.assign_agent_id = None;
                self.assign_task_input.clear();
                self.error = None;
                None
            }
            ParkedMessage::AgentsLoaded(agents) => {
                self.agents = agents;
                None
            }
            ParkedMessage::Error(e) => {
                self.error = Some(e);
                None
            }
            ParkedMessage::ClearError => {
                self.error = None;
                None
            }
        }
    }
}

// =============================================================================
// View Functions
// =============================================================================

/// Main view for parked agents panel
pub fn view_parked_agents_panel<'a, M>(
    state: &'a ParkedAgentsPanelState,
    to_message: impl Fn(ParkedMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let header = row![
        text("🅿️ Parked Agents").size(20),
        Space::new().width(Length::Fill),
        button(text("⟳ Refresh").size(12))
            .padding([6, 12])
            .on_press(to_message(ParkedMessage::RefreshList))
            .style(|_theme, _status| button::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.2, 0.4, 0.6,
                ))),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 0.0,
                    color: iced::Color::TRANSPARENT,
                },
                ..Default::default()
            })
    ]
    .align_y(Alignment::Center)
    .spacing(8);

    let error_banner: Element<'a, M> = if let Some(err) = &state.error {
        container(
            row![
                text(format!("⚠️ {}", err)).color(iced::Color::from_rgb(1.0, 0.6, 0.6)),
                Space::new().width(Length::Fill),
                button(text("✕").size(12))
                    .padding(4)
                    .on_press(to_message(ParkedMessage::ClearError))
            ]
            .align_y(Alignment::Center),
        )
        .padding(8)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.3, 0.15, 0.15,
            ))),
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    let content: Element<'a, M> = if state.agents.is_empty() {
        container(
            column![
                Space::new().height(48),
                text("No parked agents")
                    .size(16)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("Agents that are parked will appear here. Click Refresh to check.")
                    .size(12)
                    .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
            ]
            .spacing(8)
            .align_x(Alignment::Center),
        )
        .width(Length::Fill)
        .center_y(Length::Fill)
        .into()
    } else {
        let cards: Vec<Element<'a, M>> = state
            .agents
            .iter()
            .map(|agent| view_agent_card(agent, to_message.clone()))
            .collect();

        scrollable(column(cards).spacing(12).padding(8).width(Length::Fill))
            .height(Length::Fill)
            .into()
    };

    let main_view = column![error_banner, header, Space::new().height(16), content,]
        .spacing(8)
        .padding(16)
        .into();

    // Overlay assign task modal
    if state.show_assign_modal {
        if let Some(agent_id) = &state.assign_agent_id {
            return iced::widget::stack![
                main_view,
                view_assign_task_modal(
                    agent_id,
                    &state.assign_task_input,
                    state.agents.iter().find(|a| &a.agent_id == agent_id),
                    to_message,
                ),
            ]
            .into();
        }
    }

    main_view
}

fn view_agent_card<'a, M>(
    agent: &'a ParkedAgent,
    to_message: impl Fn(ParkedMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let agent_id = agent.agent_id.clone();
    let assign_btn = button(text("Assign Task").size(12))
        .padding([6, 12])
        .on_press(to_message(ParkedMessage::AssignTask(agent_id)))
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.2, 0.5, 0.3,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                width: 0.0,
                color: iced::Color::TRANSPARENT,
            },
            ..Default::default()
        });

    let capabilities_row = if agent.capabilities.is_empty() {
        row![text("(no capabilities)")
            .size(11)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5))]
    } else {
        row(agent
            .capabilities
            .iter()
            .map(|c| {
                container(text(c).size(10))
                    .padding([2, 6])
                    .style(|_| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.25, 0.35, 0.45,
                        ))),
                        border: iced::Border {
                            radius: 4.0.into(),
                            width: 0.0,
                            color: iced::Color::TRANSPARENT,
                        },
                        ..Default::default()
                    })
                    .into()
            })
            .collect::<Vec<_>>())
        .spacing(4)
    };

    let card_content = column![
        row![
            text(agent.short_id()).size(14),
            Space::new().width(8),
            text(agent.time_parked_ago())
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.6, 0.7)),
            Space::new().width(Length::Fill),
            assign_btn,
        ]
        .align_y(Alignment::Center)
        .spacing(4),
        Space::new().height(4),
        text(agent.workspace.as_str())
            .size(12)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().height(6),
        capabilities_row,
    ]
    .spacing(2)
    .width(Length::Fill);

    container(card_content)
        .padding(16)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.15, 0.18, 0.22,
            ))),
            border: iced::Border {
                radius: 8.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.25, 0.3, 0.35),
            },
            ..Default::default()
        })
        .into()
}

fn view_assign_task_modal<'a, M>(
    agent_id: &'a str,
    task_input: &'a str,
    agent: Option<&'a ParkedAgent>,
    to_message: impl Fn(ParkedMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    use iced::widget::container;

    let agent_label = agent
        .map(|a| a.short_id())
        .unwrap_or_else(|| agent_id.to_string());

    let agent_id_owned = agent_id.to_string();
    let to_msg_input = to_message.clone();
    let to_msg_submit = to_message.clone();
    let can_submit = !task_input.trim().is_empty();

    let submit_msg = to_msg_submit(ParkedMessage::SubmitTask(agent_id_owned.clone()));

    let task_input_widget = text_input("Describe the task...", task_input)
        .on_input(move |s| to_msg_input(ParkedMessage::UpdateTaskInput(s)))
        .on_submit(submit_msg.clone())
        .padding(12)
        .size(14);

    let submit_btn = button(text("Submit").size(13))
        .padding([8, 16])
        .on_press_maybe(if can_submit { Some(submit_msg) } else { None })
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.2, 0.5, 0.3,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                width: 0.0,
                color: iced::Color::TRANSPARENT,
            },
            ..Default::default()
        });

    let cancel_btn = button(text("Cancel").size(13))
        .padding([8, 16])
        .on_press(to_message(ParkedMessage::CloseAssignModal))
        .style(|_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.35, 0.35, 0.35,
            ))),
            text_color: iced::Color::WHITE,
            border: iced::Border {
                radius: 6.0.into(),
                width: 0.0,
                color: iced::Color::TRANSPARENT,
            },
            ..Default::default()
        });

    let modal_content = container(
        column![
            text(format!("Assign Task to {}", agent_label)).size(18),
            text("Enter the task description. The agent will be unparked and receive this task.")
                .size(12)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(16),
            task_input_widget,
            Space::new().height(20),
            row![submit_btn, Space::new().width(12), cancel_btn]
                .spacing(8)
                .align_y(Alignment::Center),
        ]
        .spacing(12)
        .width(Length::Fixed(450.0)),
    )
    .padding(24)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
        },
        ..Default::default()
    });

    container(container(modal_content).center(Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(
                0.0, 0.0, 0.0, 0.7,
            ))),
            ..Default::default()
        })
        .into()
}
