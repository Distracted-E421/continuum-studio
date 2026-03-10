//! CLI Agents Module
//!
//! Provides UI for orchestrating headless Cursor CLI agents through Synapsix.
//! Supports single agent spawning, batch processing, and real-time event monitoring.

use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Element, Length};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use chrono::{DateTime, Utc};

/// State for CLI agents tab
#[derive(Debug, Clone, Default)]
pub struct CLIAgentsState {
    /// All known agents (keyed by ID)
    pub agents: HashMap<String, CLIAgent>,
    /// Currently selected agent (for detail view)
    pub selected_agent: Option<String>,
    /// Launch form state
    pub launch_form: LaunchForm,
    /// Batch launch form state
    pub batch_form: BatchForm,
    /// Current sub-view
    pub view: CLIAgentsView,
    /// Connection status
    pub connected: bool,
    /// Error message if any
    pub error: Option<String>,
}

/// Sub-views within CLI Agents tab
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CLIAgentsView {
    #[default]
    List,
    Detail,
    Launch,
    Batch,
}

/// Single CLI agent state
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CLIAgent {
    pub id: String,
    pub workspace: String,
    pub prompt: String,
    pub status: CLIAgentStatus,
    pub mode: AgentMode,
    pub model: Option<String>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub events: Vec<CLIAgentEvent>,
    pub result: Option<String>,
    pub error: Option<String>,
}

/// Agent status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum CLIAgentStatus {
    #[default]
    Pending,
    Running,
    Completed,
    Failed,
    Timeout,
}

impl CLIAgentStatus {
    pub fn emoji(&self) -> &'static str {
        match self {
            Self::Pending => "⏳",
            Self::Running => "🟢",
            Self::Completed => "✅",
            Self::Failed => "❌",
            Self::Timeout => "⏰",
        }
    }
    
    pub fn label(&self) -> &'static str {
        match self {
            Self::Pending => "Pending",
            Self::Running => "Running",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
            Self::Timeout => "Timeout",
        }
    }
}

/// Agent execution mode
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum AgentMode {
    #[default]
    Agent,
    Plan,
    Ask,
}

impl AgentMode {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Agent => "Agent",
            Self::Plan => "Plan",
            Self::Ask => "Ask",
        }
    }
}

/// Agent event from CLI stream
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CLIAgentEvent {
    pub event_type: CLIEventType,
    pub timestamp: DateTime<Utc>,
    pub content: Option<String>,
    pub tool_name: Option<String>,
    pub tool_args: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CLIEventType {
    Init,
    Thinking,
    ToolStarted,
    ToolCompleted,
    Response,
    Result,
    Error,
}

impl CLIEventType {
    pub fn emoji(&self) -> &'static str {
        match self {
            Self::Init => "🚀",
            Self::Thinking => "💭",
            Self::ToolStarted => "🔧",
            Self::ToolCompleted => "✓",
            Self::Response => "💬",
            Self::Result => "📋",
            Self::Error => "⚠️",
        }
    }
}

/// Form for launching single agent
#[derive(Debug, Clone, Default)]
pub struct LaunchForm {
    pub workspace: String,
    pub prompt: String,
    pub mode: AgentMode,
    pub force: bool,
    pub approve_mcps: bool,
    pub model: Option<String>,
}

/// Form for batch launch
#[derive(Debug, Clone, Default)]
pub struct BatchForm {
    pub prompt: String,
    pub workspaces: Vec<(String, bool)>, // (path, selected)
    pub max_concurrent: u32,
    pub stop_on_failure: bool,
}

/// Messages for CLI agents tab
#[derive(Debug, Clone)]
pub enum CLIAgentMessage {
    // Navigation
    ChangeView(CLIAgentsView),
    SelectAgent(String),
    DeselectAgent,
    
    // Launch form
    UpdatePrompt(String),
    UpdateWorkspace(String),
    SetMode(AgentMode),
    ToggleForce,
    ToggleApproveMcps,
    Launch,
    
    // Batch form
    UpdateBatchPrompt(String),
    ToggleBatchWorkspace(String),
    SetMaxConcurrent(u32),
    ToggleStopOnFailure,
    AddWorkspace(String),
    LaunchBatch,
    
    // Agent actions
    StopAgent(String),
    RefreshAgents,
    
    // Updates from backend (WebSocket events)
    Connected,
    AgentStarted {
        id: String,
        workspace: String,
        model: Option<String>,
    },
    AgentCompleted {
        id: String,
        result: Option<String>,
        error: Option<String>,
    },
    AgentSpawned(String, CLIAgent),
    AgentUpdated(String, CLIAgent),
    AgentEvent(String, CLIAgentEvent),
    AgentsLoaded(Vec<String>),
    AgentsFullLoaded(Vec<CLIAgent>),
    
    // Errors
    Error(String),
    ClearError,
}

impl CLIAgentsState {
    pub fn new() -> Self {
        let mut batch_form = BatchForm::default();
        batch_form.max_concurrent = 3;
        
        // Pre-populate with common workspaces
        batch_form.workspaces = vec![
            ("/home/e421/synapsix".to_string(), false),
            ("/home/e421/homelab".to_string(), false),
            ("/home/e421/cortex".to_string(), false),
            ("/home/e421/continuum-studio".to_string(), false),
        ];
        
        Self {
            batch_form,
            ..Default::default()
        }
    }
    
    /// Process a message and return any tasks to run
    pub fn update(&mut self, message: CLIAgentMessage) -> Option<CLIAgentTask> {
        match message {
            CLIAgentMessage::ChangeView(view) => {
                self.view = view;
                None
            }
            CLIAgentMessage::SelectAgent(id) => {
                self.selected_agent = Some(id);
                self.view = CLIAgentsView::Detail;
                None
            }
            CLIAgentMessage::DeselectAgent => {
                self.selected_agent = None;
                self.view = CLIAgentsView::List;
                None
            }
            
            // Launch form
            CLIAgentMessage::UpdatePrompt(p) => {
                self.launch_form.prompt = p;
                None
            }
            CLIAgentMessage::UpdateWorkspace(w) => {
                self.launch_form.workspace = w;
                None
            }
            CLIAgentMessage::SetMode(m) => {
                self.launch_form.mode = m;
                None
            }
            CLIAgentMessage::ToggleForce => {
                self.launch_form.force = !self.launch_form.force;
                None
            }
            CLIAgentMessage::ToggleApproveMcps => {
                self.launch_form.approve_mcps = !self.launch_form.approve_mcps;
                None
            }
            CLIAgentMessage::Launch => {
                Some(CLIAgentTask::SpawnAgent {
                    prompt: self.launch_form.prompt.clone(),
                    workspace: self.launch_form.workspace.clone(),
                    mode: self.launch_form.mode,
                    force: self.launch_form.force,
                    approve_mcps: self.launch_form.approve_mcps,
                })
            }
            
            // Batch form
            CLIAgentMessage::UpdateBatchPrompt(p) => {
                self.batch_form.prompt = p;
                None
            }
            CLIAgentMessage::ToggleBatchWorkspace(ws) => {
                if let Some((_, selected)) = self.batch_form.workspaces.iter_mut()
                    .find(|(w, _)| w == &ws) 
                {
                    *selected = !*selected;
                }
                None
            }
            CLIAgentMessage::SetMaxConcurrent(n) => {
                self.batch_form.max_concurrent = n;
                None
            }
            CLIAgentMessage::ToggleStopOnFailure => {
                self.batch_form.stop_on_failure = !self.batch_form.stop_on_failure;
                None
            }
            CLIAgentMessage::AddWorkspace(ws) => {
                if !ws.is_empty() && !self.batch_form.workspaces.iter().any(|(w, _)| w == &ws) {
                    self.batch_form.workspaces.push((ws, true));
                }
                None
            }
            CLIAgentMessage::LaunchBatch => {
                let workspaces: Vec<String> = self.batch_form.workspaces
                    .iter()
                    .filter(|(_, selected)| *selected)
                    .map(|(ws, _)| ws.clone())
                    .collect();
                    
                if workspaces.is_empty() {
                    self.error = Some("No workspaces selected".to_string());
                    return None;
                }
                
                Some(CLIAgentTask::SpawnBatch {
                    prompt: self.batch_form.prompt.clone(),
                    workspaces,
                    max_concurrent: self.batch_form.max_concurrent,
                    stop_on_failure: self.batch_form.stop_on_failure,
                })
            }
            
            // Agent actions
            CLIAgentMessage::StopAgent(id) => {
                Some(CLIAgentTask::StopAgent { id })
            }
            CLIAgentMessage::RefreshAgents => {
                Some(CLIAgentTask::RefreshAgents)
            }
            
            // Updates from backend (WebSocket events)
            CLIAgentMessage::Connected => {
                self.connected = true;
                self.error = None;
                None
            }
            CLIAgentMessage::AgentStarted { id, workspace, model } => {
                let agent = CLIAgent {
                    id: id.clone(),
                    workspace,
                    prompt: self.launch_form.prompt.clone(),
                    status: CLIAgentStatus::Running,
                    mode: self.launch_form.mode,
                    model,
                    started_at: Some(chrono::Utc::now()),
                    completed_at: None,
                    events: Vec::new(),
                    result: None,
                    error: None,
                };
                self.agents.insert(id, agent);
                self.view = CLIAgentsView::List;
                self.launch_form = LaunchForm::default();
                None
            }
            CLIAgentMessage::AgentCompleted { id, result, error } => {
                if let Some(agent) = self.agents.get_mut(&id) {
                    agent.status = if error.is_some() {
                        CLIAgentStatus::Failed
                    } else {
                        CLIAgentStatus::Completed
                    };
                    agent.completed_at = Some(chrono::Utc::now());
                    agent.result = result;
                    agent.error = error;
                }
                None
            }
            CLIAgentMessage::AgentSpawned(id, agent) => {
                self.agents.insert(id, agent);
                self.view = CLIAgentsView::List;
                self.launch_form = LaunchForm::default();
                None
            }
            CLIAgentMessage::AgentUpdated(id, agent) => {
                self.agents.insert(id, agent);
                None
            }
            CLIAgentMessage::AgentEvent(id, event) => {
                if let Some(agent) = self.agents.get_mut(&id) {
                    agent.events.push(event);
                }
                None
            }
            CLIAgentMessage::AgentsLoaded(_agent_ids) => {
                // TODO: Fetch full agent details for each ID
                None
            }
            CLIAgentMessage::AgentsFullLoaded(agents) => {
                self.agents = agents.into_iter()
                    .map(|a| (a.id.clone(), a))
                    .collect();
                None
            }
            
            // Errors
            CLIAgentMessage::Error(e) => {
                self.error = Some(e);
                None
            }
            CLIAgentMessage::ClearError => {
                self.error = None;
                None
            }
        }
    }
}

/// Tasks to be executed by the main application
#[derive(Debug, Clone)]
pub enum CLIAgentTask {
    SpawnAgent {
        prompt: String,
        workspace: String,
        mode: AgentMode,
        force: bool,
        approve_mcps: bool,
    },
    SpawnBatch {
        prompt: String,
        workspaces: Vec<String>,
        max_concurrent: u32,
        stop_on_failure: bool,
    },
    StopAgent {
        id: String,
    },
    RefreshAgents,
}

// =============================================================================
// View Functions
// =============================================================================

/// Main view router for CLI agents tab
pub fn view_cli_agents_tab<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M> 
where
    M: 'a + Clone,
{
    let header = view_header(state, to_message.clone());
    
    let content: Element<'a, M> = match state.view {
        CLIAgentsView::List => view_agent_list(state, to_message.clone()),
        CLIAgentsView::Detail => view_agent_detail(state, to_message.clone()),
        CLIAgentsView::Launch => view_launch_form(state, to_message.clone()),
        CLIAgentsView::Batch => view_batch_form(state, to_message.clone()),
    };
    
    let error_banner: Element<'a, M> = if let Some(err) = &state.error {
        container(
            row![
                text(format!("⚠️ {}", err)).color(iced::Color::from_rgb(1.0, 0.6, 0.6)),
                Space::new().width(Length::Fill),
                button(text("✕").size(12))
                    .padding(4)
                    .on_press(to_message(CLIAgentMessage::ClearError))
            ]
            .align_y(Alignment::Center)
        )
        .padding(8)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.3, 0.15, 0.15))),
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };
    
    column![
        error_banner,
        header,
        Space::new().height(8),
        content,
    ]
    .spacing(4)
    .padding(16)
    .into()
}

fn view_header<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let title = text("⚡ CLI Agents").size(20);
    
    let running_count = state.agents.values()
        .filter(|a| a.status == CLIAgentStatus::Running)
        .count();
    
    let status_text = if running_count > 0 {
        text(format!("{} running", running_count))
            .size(12)
            .color(iced::Color::from_rgb(0.4, 0.8, 0.4))
    } else {
        text("").size(12)
    };
    
    let nav_buttons = row![
        tab_nav_button("List", CLIAgentsView::List, state.view, to_message.clone()),
        tab_nav_button("Launch", CLIAgentsView::Launch, state.view, to_message.clone()),
        tab_nav_button("Batch", CLIAgentsView::Batch, state.view, to_message.clone()),
    ]
    .spacing(4);
    
    let refresh_btn = button(text("⟳").size(14))
        .padding([6, 10])
        .on_press(to_message(CLIAgentMessage::RefreshAgents));
    
    row![
        title,
        Space::new().width(12),
        status_text,
        Space::new().width(Length::Fill),
        nav_buttons,
        Space::new().width(8),
        refresh_btn,
    ]
    .align_y(Alignment::Center)
    .into()
}

fn tab_nav_button<'a, M>(
    label: &'a str,
    target: CLIAgentsView,
    current: CLIAgentsView,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = target == current;
    
    button(text(label).size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::ChangeView(target)))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.45, 0.65)
            } else {
                iced::Color::from_rgb(0.2, 0.2, 0.25)
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        })
        .into()
}

fn view_agent_list<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    if state.agents.is_empty() {
        return container(
            column![
                text("No CLI agents").size(16),
                Space::new().height(8),
                text("Use the Launch or Batch tabs to spawn agents").size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .align_x(Alignment::Center)
        )
        .width(Length::Fill)
        .padding(40)
        .center(Length::Fill)
        .into();
    }
    
    let mut agents: Vec<_> = state.agents.values().collect();
    agents.sort_by(|a, b| b.started_at.cmp(&a.started_at));
    
    let agent_cards: Vec<Element<'a, M>> = agents
        .into_iter()
        .map(|agent| view_agent_card(agent, to_message.clone()))
        .collect();
    
    scrollable(
        column(agent_cards)
            .spacing(8)
    )
    .height(Length::Fill)
    .into()
}

fn view_agent_card<'a, M>(
    agent: &'a CLIAgent,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let status_emoji = agent.status.emoji();
    let status_label = agent.status.label();
    
    let duration = if let (Some(start), Some(end)) = (agent.started_at, agent.completed_at) {
        let secs = (end - start).num_seconds();
        format!("{}s", secs)
    } else if let Some(start) = agent.started_at {
        let secs = (Utc::now() - start).num_seconds();
        format!("{}s...", secs)
    } else {
        "".to_string()
    };
    
    let header = row![
        text(format!("{} {}", status_emoji, &agent.id[..8])).size(14),
        Space::new().width(8),
        text(format!("| {}", agent.workspace.split('/').last().unwrap_or(&agent.workspace)))
            .size(12)
            .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().width(Length::Fill),
        text(status_label).size(12),
        Space::new().width(8),
        text(duration).size(12).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .align_y(Alignment::Center);
    
    let prompt_preview = if agent.prompt.len() > 60 {
        format!("{}...", &agent.prompt[..60])
    } else {
        agent.prompt.clone()
    };
    
    let details = row![
        text(prompt_preview).size(11).color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(Length::Fill),
        text(format!("Events: {}", agent.events.len())).size(11),
    ];
    
    let stop_btn: Element<'a, M> = if agent.status == CLIAgentStatus::Running {
        button(text("Stop").size(11))
            .padding([4, 8])
            .on_press(to_message(CLIAgentMessage::StopAgent(agent.id.clone())))
            .into()
    } else {
        Space::new().width(0).into()
    };
    
    let actions = row![
        Space::new().width(Length::Fill),
        stop_btn,
        button(text("Details").size(11))
            .padding([4, 8])
            .on_press(to_message(CLIAgentMessage::SelectAgent(agent.id.clone()))),
    ]
    .spacing(8);
    
    container(
        column![header, details, actions]
            .spacing(6)
    )
    .padding(12)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.15))),
        border: iced::Border {
            radius: 6.0.into(),
            ..Default::default()
        },
        ..Default::default()
    })
    .into()
}

fn view_agent_detail<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let agent = match &state.selected_agent {
        Some(id) => state.agents.get(id),
        None => None,
    };
    
    let Some(agent) = agent else {
        return column![
            text("Agent not found").size(16),
            button(text("← Back")).on_press(to_message(CLIAgentMessage::DeselectAgent)),
        ].into();
    };
    
    let back_btn = button(text("← Back to List").size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::DeselectAgent));
    
    let header = row![
        back_btn,
        Space::new().width(16),
        text(format!("{} Agent {}", agent.status.emoji(), &agent.id[..8])).size(18),
    ]
    .align_y(Alignment::Center);
    
    let info = column![
        text(format!("Workspace: {}", agent.workspace)).size(12),
        text(format!("Mode: {}", agent.mode.label())).size(12),
        text(format!("Status: {}", agent.status.label())).size(12),
        if let Some(model) = &agent.model {
            text(format!("Model: {}", model)).size(12)
        } else {
            text("").size(0)
        },
    ]
    .spacing(4);
    
    let prompt_section = container(
        column![
            text("Prompt:").size(12),
            text(&agent.prompt).size(11).color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        ]
        .spacing(4)
    )
    .padding(8)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.1, 0.12))),
        ..Default::default()
    });
    
    // Events timeline
    let events: Vec<Element<'a, M>> = agent.events.iter()
        .map(|e| {
            let time = e.timestamp.format("%H:%M:%S").to_string();
            let content = e.content.as_deref().unwrap_or("");
            let tool = e.tool_name.as_deref().unwrap_or("");
            
            let line = match e.event_type {
                CLIEventType::ToolStarted => format!("{} 🔧 Tool: {}", time, tool),
                CLIEventType::ToolCompleted => format!("{} ✓ Tool completed: {}", time, tool),
                CLIEventType::Thinking => format!("{} 💭 {}", time, &content[..content.len().min(50)]),
                CLIEventType::Response => format!("{} 💬 Response received", time),
                _ => format!("{} {} {}", time, e.event_type.emoji(), content),
            };
            
            text(line).size(11).into()
        })
        .collect();
    
    let events_section = container(
        column![
            text("Events Timeline").size(14),
            Space::new().height(8),
            scrollable(
                column(events).spacing(4)
            )
            .height(200),
        ]
    )
    .padding(12)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.15))),
        border: iced::Border { radius: 6.0.into(), ..Default::default() },
        ..Default::default()
    });
    
    // Result section
    let result_section: Element<'a, M> = if let Some(result) = &agent.result {
        container(
            column![
                text("Result:").size(14),
                Space::new().height(8),
                scrollable(
                    text(result).size(11)
                )
                .height(150),
            ]
        )
        .padding(12)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(0.1, 0.15, 0.1))),
            border: iced::Border { radius: 6.0.into(), ..Default::default() },
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };
    
    scrollable(
        column![
            header,
            Space::new().height(16),
            info,
            Space::new().height(12),
            prompt_section,
            Space::new().height(12),
            events_section,
            Space::new().height(12),
            result_section,
        ]
        .spacing(4)
    )
    .height(Length::Fill)
    .into()
}

fn view_launch_form<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let form = &state.launch_form;
    let to_message1 = to_message.clone();
    let to_message2 = to_message.clone();
    
    let workspace_input = column![
        text("Workspace:").size(12),
        text_input("e.g., /home/e421/synapsix", &form.workspace)
            .padding(8)
            .on_input(move |s| to_message1(CLIAgentMessage::UpdateWorkspace(s))),
    ]
    .spacing(4);
    
    let prompt_input = column![
        text("Prompt:").size(12),
        text_input("What should the agent do?", &form.prompt)
            .padding(8)
            .on_input(move |s| to_message2(CLIAgentMessage::UpdatePrompt(s))),
    ]
    .spacing(4);
    
    let mode_selector = row![
        text("Mode:").size(12),
        Space::new().width(12),
        mode_button("Agent", AgentMode::Agent, form.mode, to_message.clone()),
        mode_button("Plan", AgentMode::Plan, form.mode, to_message.clone()),
        mode_button("Ask", AgentMode::Ask, form.mode, to_message.clone()),
    ]
    .spacing(8)
    .align_y(Alignment::Center);
    
    let options = row![
        checkbox_button("Force trust", form.force, 
            to_message(CLIAgentMessage::ToggleForce)),
        Space::new().width(16),
        checkbox_button("Auto-approve MCPs", form.approve_mcps,
            to_message(CLIAgentMessage::ToggleApproveMcps)),
    ];
    
    let can_launch = !form.workspace.is_empty() && !form.prompt.is_empty();
    
    let launch_btn = button(
        text("▶ Launch Agent").size(14)
    )
    .padding([10, 20])
    .on_press_maybe(if can_launch {
        Some(to_message(CLIAgentMessage::Launch))
    } else {
        None
    })
    .style(|_theme, status| {
        let bg = match status {
            button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
            button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
            _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: iced::Color::WHITE,
            border: iced::Border { radius: 6.0.into(), ..Default::default() },
            ..Default::default()
        }
    });
    
    container(
        column![
            text("Launch CLI Agent").size(18),
            Space::new().height(16),
            workspace_input,
            Space::new().height(12),
            prompt_input,
            Space::new().height(12),
            mode_selector,
            Space::new().height(12),
            options,
            Space::new().height(20),
            launch_btn,
        ]
        .width(Length::Fill)
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.15))),
        border: iced::Border { radius: 8.0.into(), ..Default::default() },
        ..Default::default()
    })
    .into()
}

fn view_batch_form<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let form = &state.batch_form;
    let to_message1 = to_message.clone();
    
    let prompt_input = column![
        text("Prompt (applies to all workspaces):").size(12),
        text_input("What should agents do?", &form.prompt)
            .padding(8)
            .on_input(move |s| to_message1(CLIAgentMessage::UpdateBatchPrompt(s))),
    ]
    .spacing(4);
    
    let workspace_checkboxes: Vec<Element<'a, M>> = form.workspaces
        .iter()
        .map(|(ws, selected)| {
            let ws_clone = ws.clone();
            checkbox_button(
                ws.split('/').last().unwrap_or(ws),
                *selected,
                to_message(CLIAgentMessage::ToggleBatchWorkspace(ws_clone)),
            )
        })
        .collect();
    
    let workspaces_section = column![
        text("Select Workspaces:").size(12),
        Space::new().height(8),
        column(workspace_checkboxes).spacing(6),
    ];
    
    let selected_count = form.workspaces.iter().filter(|(_, s)| *s).count();
    
    let options = row![
        text(format!("Max concurrent: {}", form.max_concurrent)).size(12),
        Space::new().width(20),
        checkbox_button("Stop on failure", form.stop_on_failure,
            to_message(CLIAgentMessage::ToggleStopOnFailure)),
    ];
    
    let can_launch = !form.prompt.is_empty() && selected_count > 0;
    
    let launch_btn = button(
        text(format!("▶ Launch Batch ({} agents)", selected_count)).size(14)
    )
    .padding([10, 20])
    .on_press_maybe(if can_launch {
        Some(to_message(CLIAgentMessage::LaunchBatch))
    } else {
        None
    })
    .style(|_theme, status| {
        let bg = match status {
            button::Status::Active => iced::Color::from_rgb(0.2, 0.4, 0.5),
            button::Status::Hovered => iced::Color::from_rgb(0.25, 0.5, 0.6),
            _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
        };
        button::Style {
            background: Some(iced::Background::Color(bg)),
            text_color: iced::Color::WHITE,
            border: iced::Border { radius: 6.0.into(), ..Default::default() },
            ..Default::default()
        }
    });
    
    container(
        column![
            text("Batch Launch").size(18),
            Space::new().height(16),
            prompt_input,
            Space::new().height(16),
            workspaces_section,
            Space::new().height(16),
            options,
            Space::new().height(20),
            launch_btn,
        ]
        .width(Length::Fill)
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(0.12, 0.12, 0.15))),
        border: iced::Border { radius: 8.0.into(), ..Default::default() },
        ..Default::default()
    })
    .into()
}

fn mode_button<'a, M>(
    label: &'a str,
    target: AgentMode,
    current: AgentMode,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = target == current;
    let indicator = if is_active { "◉" } else { "○" };
    
    button(text(format!("{} {}", indicator, label)).size(12))
        .padding([4, 8])
        .on_press(to_message(CLIAgentMessage::SetMode(target)))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.35, 0.5)
            } else {
                iced::Color::TRANSPARENT
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::from_rgb(0.8, 0.8, 0.8),
                border: iced::Border { radius: 4.0.into(), ..Default::default() },
                ..Default::default()
            }
        })
        .into()
}

fn checkbox_button<'a, M: 'a + Clone>(
    label: &'a str,
    checked: bool,
    on_press: M,
) -> Element<'a, M> {
    let indicator = if checked { "☑" } else { "☐" };
    
    button(text(format!("{} {}", indicator, label)).size(12))
        .padding([4, 8])
        .on_press(on_press)
        .style(move |_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::TRANSPARENT)),
            text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
            ..Default::default()
        })
        .into()
}
