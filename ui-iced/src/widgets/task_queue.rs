//! TaskQueue Widget for Continuum Studio
//!
//! Displays and manages the Synapsix persistent task queue.
//! Connects via WebSocket to receive real-time updates.

use iced::widget::{button, column, container, row, scrollable, text, text_input, Space};
use iced::{Alignment, Color, Element, Length};
use serde::{Deserialize, Serialize};
use std::time::Instant;

// Re-export core types from task_queue_client to avoid duplication
pub use crate::task_queue_client::{Priority, QueueStats, Task, TaskStatus};

// ============================================================================
// Agent Types
// ============================================================================

/// Agent type - session or sub-agent
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentType {
    SessionAgent,
    SubAgent,
}

impl AgentType {
    pub fn emoji(&self) -> &'static str {
        match self {
            AgentType::SessionAgent => "🤖",
            AgentType::SubAgent => "🔧",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            AgentType::SessionAgent => "Session",
            AgentType::SubAgent => "Sub-agent",
        }
    }
}

/// Agent status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentStatus {
    Active,
    Idle,
    Waiting,
    Completed,
}

impl AgentStatus {
    pub fn emoji(&self) -> &'static str {
        match self {
            AgentStatus::Active => "▶",
            AgentStatus::Idle => "⏸",
            AgentStatus::Waiting => "⏳",
            AgentStatus::Completed => "✓",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            AgentStatus::Active => "active",
            AgentStatus::Idle => "idle",
            AgentStatus::Waiting => "waiting",
            AgentStatus::Completed => "done",
        }
    }
}

/// An active agent
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Agent {
    pub id: String,
    pub agent_type: AgentType,
    pub name: Option<String>,
    pub status: AgentStatus,
    #[serde(default)]
    pub current_task_id: Option<String>,
    #[serde(default)]
    pub parent_id: Option<String>, // For sub-agents
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default)]
    pub started_at: Option<String>,
    #[serde(default)]
    pub last_activity: Option<String>,
}

/// Task history entry (completed/cancelled task with outcome)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskHistoryEntry {
    pub task: Task,
    pub outcome: Option<String>,
    pub duration_secs: Option<u64>,
}

// ============================================================================
// Theme Colors (subset for widget)
// ============================================================================

/// Colors for the task queue widget
#[derive(Debug, Clone, Copy)]
pub struct TaskQueueColors {
    pub bg: Color,
    pub bg_elevated: Color,
    pub text_primary: Color,
    pub text_secondary: Color,
    pub accent: Color,
    pub success: Color,
    pub warning: Color,
    pub danger: Color,
    pub border: Color,
    pub button_bg: Color,
}

impl Default for TaskQueueColors {
    fn default() -> Self {
        // COSMIC dark theme
        Self {
            bg: Color::from_rgb8(30, 30, 30),
            bg_elevated: Color::from_rgb8(43, 43, 43),
            text_primary: Color::from_rgb8(250, 250, 250),
            text_secondary: Color::from_rgb8(180, 180, 180),
            accent: Color::from_rgb8(77, 136, 230),
            success: Color::from_rgb8(72, 187, 120),
            warning: Color::from_rgb8(245, 158, 11),
            danger: Color::from_rgb8(235, 87, 87),
            border: Color::from_rgb8(55, 55, 62),
            button_bg: Color::from_rgb8(69, 69, 69),
        }
    }
}

// ============================================================================
// Widget State
// ============================================================================

/// Widget size variants
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum WidgetSize {
    /// 1x1: Compact badge
    Compact,
    /// 1x2: Current + next tasks
    #[default]
    Standard,
    /// 2x2: Full list with actions
    Full,
    /// 2x3: Extended with stats
    Extended,
}

/// Active panel in the layout
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ActivePanel {
    #[default]
    Tasks,
    Agents,
    History,
}

/// Panel layout configuration
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PanelLayout {
    /// Single panel view
    Single(ActivePanel),
    /// Side by side (left, right)
    SideBySide(ActivePanel, ActivePanel),
    /// Stacked (top, bottom)
    Stacked(ActivePanel, ActivePanel),
    /// Three-column grid
    ThreeColumn(ActivePanel, ActivePanel, ActivePanel),
}

impl Default for PanelLayout {
    fn default() -> Self {
        PanelLayout::Single(ActivePanel::Tasks)
    }
}

/// TaskQueue widget state
#[derive(Debug, Clone)]
pub struct TaskQueueWidget {
    /// Widget size/layout mode
    pub size: WidgetSize,
    /// Panel layout
    pub layout: PanelLayout,
    /// All tasks from server
    pub tasks: Vec<Task>,
    /// Current/active task (in_progress)
    pub current_task: Option<Task>,
    /// Queue statistics
    pub stats: QueueStats,
    /// Active agents
    pub agents: Vec<Agent>,
    /// Task history (completed/cancelled)
    pub task_history: Vec<TaskHistoryEntry>,
    /// Max history entries to keep
    pub max_history: usize,
    /// Connection state
    pub connected: bool,
    /// Quick-add input text
    pub quick_add_input: String,
    /// Selected task ID (for details view)
    pub selected_task_id: Option<String>,
    /// Selected agent ID (for details view)
    pub selected_agent_id: Option<String>,
    /// Last update time
    pub last_update: Option<Instant>,
    /// Theme colors
    pub colors: TaskQueueColors,
}

impl Default for TaskQueueWidget {
    fn default() -> Self {
        Self::new(WidgetSize::Standard)
    }
}

impl TaskQueueWidget {
    pub fn new(size: WidgetSize) -> Self {
        Self {
            size,
            layout: PanelLayout::default(),
            tasks: Vec::new(),
            current_task: None,
            stats: QueueStats::default(),
            agents: Vec::new(),
            task_history: Vec::new(),
            max_history: 50,
            connected: false,
            quick_add_input: String::new(),
            selected_task_id: None,
            selected_agent_id: None,
            last_update: None,
            colors: TaskQueueColors::default(),
        }
    }

    /// Set panel layout
    pub fn with_layout(mut self, layout: PanelLayout) -> Self {
        self.layout = layout;
        self
    }

    pub fn with_colors(mut self, colors: TaskQueueColors) -> Self {
        self.colors = colors;
        self
    }

    /// Update widget with a message
    pub fn update(&mut self, message: TaskQueueMessage) -> Option<TaskQueueAction> {
        match message {
            TaskQueueMessage::Connected => {
                self.connected = true;
                None
            }
            TaskQueueMessage::Disconnected => {
                self.connected = false;
                None
            }
            TaskQueueMessage::TasksLoaded(tasks) => {
                self.tasks = tasks;
                self.update_derived_state();
                None
            }
            TaskQueueMessage::TaskAdded(task) => {
                self.tasks.push(task);
                self.update_derived_state();
                None
            }
            TaskQueueMessage::TaskUpdated(task) => {
                if let Some(existing) = self.tasks.iter_mut().find(|t| t.id == task.id) {
                    *existing = task;
                }
                self.update_derived_state();
                None
            }
            TaskQueueMessage::TaskCompleted(id) => {
                if let Some(existing) = self.tasks.iter_mut().find(|t| t.id == id) {
                    existing.status = TaskStatus::Completed;
                }
                if self.current_task.as_ref().map(|t| &t.id) == Some(&id) {
                    self.current_task = None;
                }
                self.update_derived_state();
                None
            }
            TaskQueueMessage::TaskRemoved(task_id) => {
                self.tasks.retain(|t| t.id != task_id);
                self.update_derived_state();
                None
            }
            TaskQueueMessage::StatsUpdated(stats) => {
                self.stats = stats;
                None
            }

            // Agent data events
            TaskQueueMessage::AgentsUpdated(agents) => {
                self.agents = agents;
                None
            }
            TaskQueueMessage::AgentAdded(agent) => {
                // Remove existing agent with same ID if present
                self.agents.retain(|a| a.id != agent.id);
                self.agents.push(agent);
                None
            }
            TaskQueueMessage::AgentRemoved(agent_id) => {
                self.agents.retain(|a| a.id != agent_id);
                if self.selected_agent_id.as_ref() == Some(&agent_id) {
                    self.selected_agent_id = None;
                }
                None
            }
            TaskQueueMessage::AgentStatusChanged { id, status } => {
                if let Some(agent) = self.agents.iter_mut().find(|a| a.id == id) {
                    agent.status = status;
                }
                None
            }

            // History events
            TaskQueueMessage::HistoryLoaded(history) => {
                self.task_history = history;
                // Trim to max
                if self.task_history.len() > self.max_history {
                    self.task_history = self
                        .task_history
                        .split_off(self.task_history.len() - self.max_history);
                }
                None
            }
            TaskQueueMessage::HistoryEntryAdded(entry) => {
                self.task_history.push(entry);
                // Trim oldest if over max
                if self.task_history.len() > self.max_history {
                    self.task_history.remove(0);
                }
                None
            }

            // Layout events
            TaskQueueMessage::SetLayout(layout) => {
                self.layout = layout;
                None
            }
            TaskQueueMessage::SwitchPanel(panel) => {
                self.layout = PanelLayout::Single(panel);
                None
            }

            // User actions - Tasks
            TaskQueueMessage::SelectTask(task_id) => {
                self.selected_task_id = Some(task_id);
                None
            }
            TaskQueueMessage::StartTask(task_id) => Some(TaskQueueAction::StartTask(task_id)),
            TaskQueueMessage::CompleteCurrentTask => self
                .current_task
                .as_ref()
                .map(|t| TaskQueueAction::CompleteTask(t.id.clone())),
            TaskQueueMessage::QuickAddInputChanged(text) => {
                self.quick_add_input = text;
                None
            }
            TaskQueueMessage::QuickAddSubmit => {
                if !self.quick_add_input.trim().is_empty() {
                    let content = self.quick_add_input.clone();
                    self.quick_add_input.clear();
                    Some(TaskQueueAction::AddTask(content))
                } else {
                    None
                }
            }
            TaskQueueMessage::Refresh => Some(TaskQueueAction::Refresh),

            // User actions - Agents
            TaskQueueMessage::SelectAgent(agent_id) => {
                self.selected_agent_id = Some(agent_id);
                None
            }

            // User actions - History
            TaskQueueMessage::ClearHistory => {
                self.task_history.clear();
                None
            }
        }
    }

    /// Update derived state (current task, stats) from tasks list
    fn update_derived_state(&mut self) {
        // Find current task (in_progress)
        self.current_task = self
            .tasks
            .iter()
            .find(|t| t.status == TaskStatus::InProgress)
            .cloned();

        // Update stats
        self.stats = QueueStats {
            total: self.tasks.len(),
            pending: self
                .tasks
                .iter()
                .filter(|t| t.status == TaskStatus::Pending)
                .count(),
            in_progress: self
                .tasks
                .iter()
                .filter(|t| t.status == TaskStatus::InProgress)
                .count(),
            completed: self
                .tasks
                .iter()
                .filter(|t| t.status == TaskStatus::Completed)
                .count(),
            cancelled: self
                .tasks
                .iter()
                .filter(|t| t.status == TaskStatus::Cancelled)
                .count(),
        };

        self.last_update = Some(Instant::now());
    }

    /// Get pending tasks sorted by priority
    pub fn pending_tasks(&self) -> Vec<&Task> {
        let mut pending: Vec<_> = self
            .tasks
            .iter()
            .filter(|t| t.status == TaskStatus::Pending || t.status == TaskStatus::Claimed)
            .collect();

        // Sort by priority (critical first)
        pending.sort_by_key(|t| match t.priority {
            Priority::Critical => 0,
            Priority::High => 1,
            Priority::Medium => 2,
            Priority::Low => 3,
            Priority::Backlog => 4,
        });

        pending
    }

    /// Render the widget
    pub fn view(&self) -> Element<'_, TaskQueueMessage> {
        match self.size {
            WidgetSize::Compact => self.view_compact(),
            WidgetSize::Standard => self.view_standard(),
            WidgetSize::Full => self.view_full(),
            WidgetSize::Extended => self.view_full(),
        }
    }

    /// Compact view
    fn view_compact(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let header = row![
            text("📋 Tasks").size(14).color(c.text_primary),
            Space::new().width(Length::Fill),
            text(if self.connected { "●" } else { "○" })
                .size(10)
                .color(if self.connected {
                    c.success
                } else {
                    c.text_secondary
                }),
            text(format!("{}", self.stats.pending))
                .size(12)
                .color(c.text_secondary),
        ]
        .spacing(4)
        .align_y(Alignment::Center);

        let content = if let Some(task) = &self.current_task {
            column![
                text(format!("● {}", truncate(&task.content, 25)))
                    .size(13)
                    .color(c.text_primary),
                text(format!(
                    "{} · {}",
                    task.status.label(),
                    task.priority.label()
                ))
                .size(11)
                .color(c.text_secondary),
            ]
            .spacing(2)
        } else {
            column![text("No current task").size(13).color(c.text_secondary),]
        };

        container(column![header, content].spacing(8).padding(12))
            .style(move |_| container::Style {
                background: Some(iced::Background::Color(c.bg)),
                border: iced::Border {
                    radius: 10.0.into(),
                    width: 1.0,
                    color: c.border,
                },
                ..Default::default()
            })
            .into()
    }

    /// Standard view with current + pending tasks
    fn view_standard(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let header = row![
            text("📋 Task Queue").size(14).color(c.text_primary),
            Space::new().width(Length::Fill),
            text(if self.connected { "●" } else { "○" })
                .size(10)
                .color(if self.connected {
                    c.success
                } else {
                    c.text_secondary
                }),
        ]
        .spacing(4)
        .align_y(Alignment::Center);

        let current_section = if let Some(task) = &self.current_task {
            column![
                row![
                    text("▶").size(12).color(c.accent),
                    text(truncate(&task.content, 30))
                        .size(13)
                        .color(c.text_primary),
                ]
                .spacing(6),
                row![
                    text(task.assigned_to.as_deref().unwrap_or("unassigned"))
                        .size(11)
                        .color(c.text_secondary),
                    text("·").size(11).color(c.text_secondary),
                    text(task.priority.label()).size(11).color(c.text_secondary),
                ]
                .spacing(4),
                button(text("Complete ✓").size(11).color(c.text_primary))
                    .on_press(TaskQueueMessage::CompleteCurrentTask)
                    .padding([4, 8]),
            ]
            .spacing(4)
        } else {
            column![text("No current task").size(12).color(c.text_secondary),]
        };

        let pending = self.pending_tasks();
        let pending_items: Vec<Element<TaskQueueMessage>> = pending
            .iter()
            .take(4)
            .map(|task| {
                let task_id = task.id.clone();
                button(
                    row![
                        text(task.priority.emoji()).size(12),
                        text(truncate(&task.content, 25))
                            .size(12)
                            .color(c.text_primary),
                    ]
                    .spacing(6),
                )
                .on_press(TaskQueueMessage::StartTask(task_id))
                .padding([4, 8])
                .width(Length::Fill)
                .into()
            })
            .collect();

        let pending_list: Element<TaskQueueMessage> = if pending_items.is_empty() {
            text("No pending tasks")
                .size(12)
                .color(c.text_secondary)
                .into()
        } else {
            column(pending_items).spacing(4).into()
        };

        let add_button = button(
            row![
                text("+").size(14).color(c.text_primary),
                text("Add Task").size(12).color(c.text_primary),
            ]
            .spacing(4)
            .align_y(Alignment::Center),
        )
        .on_press(TaskQueueMessage::QuickAddSubmit)
        .padding([6, 12]);

        container(
            column![
                header,
                Space::new().height(1),
                current_section,
                Space::new().height(1),
                scrollable(pending_list).height(Length::Fill),
                add_button,
            ]
            .spacing(8)
            .padding(12),
        )
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(c.bg)),
            border: iced::Border {
                radius: 10.0.into(),
                width: 1.0,
                color: c.border,
            },
            ..Default::default()
        })
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    /// Full view with all features
    fn view_full(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let header = row![
            text("📋 Task Queue").size(16).color(c.text_primary),
            Space::new().width(Length::Fill),
            text(if self.connected { "●" } else { "○" })
                .size(10)
                .color(if self.connected {
                    c.success
                } else {
                    c.text_secondary
                }),
            button(text("🔄").size(14))
                .on_press(TaskQueueMessage::Refresh)
                .padding([4, 8]),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        // Current task section
        let current_section: Element<TaskQueueMessage> = if let Some(task) = &self.current_task {
            container(
                column![
                    text("▶ CURRENT").size(11).color(c.text_secondary),
                    Space::new().height(4),
                    text(&task.content).size(14).color(c.text_primary),
                    Space::new().height(4),
                    row![
                        text(task.priority.emoji()).size(12),
                        text(task.priority.label()).size(11).color(c.text_secondary),
                        text("·").size(11).color(c.text_secondary),
                        text(task.assigned_to.as_deref().unwrap_or("unassigned"))
                            .size(11)
                            .color(c.text_secondary),
                    ]
                    .spacing(4),
                    Space::new().height(8),
                    row![button(text("Complete ✓").size(11).color(c.text_primary))
                        .on_press(TaskQueueMessage::CompleteCurrentTask)
                        .padding([6, 12]),]
                    .spacing(8),
                ]
                .padding(12),
            )
            .style(move |_| container::Style {
                background: Some(iced::Background::Color(c.bg_elevated)),
                border: iced::Border {
                    radius: 8.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            })
            .into()
        } else {
            text("No current task - select one to start")
                .size(12)
                .color(c.text_secondary)
                .into()
        };

        // Pending tasks section
        let pending = self.pending_tasks();
        let pending_header = row![text(format!("PENDING ({})", pending.len()))
            .size(11)
            .color(c.text_secondary),];

        let pending_items: Vec<Element<TaskQueueMessage>> = pending
            .iter()
            .map(|task| {
                let task_id = task.id.clone();
                let content = task.content.clone();
                let project_text = task
                    .project
                    .clone()
                    .unwrap_or_else(|| "no project".to_string());
                let priority_emoji = task.priority.emoji();

                container(
                    button(
                        row![
                            text(priority_emoji).size(14),
                            column![
                                text(content).size(13).color(c.text_primary),
                                text(project_text).size(10).color(c.text_secondary),
                            ]
                            .spacing(2),
                        ]
                        .spacing(8)
                        .align_y(Alignment::Center),
                    )
                    .on_press(TaskQueueMessage::StartTask(task_id))
                    .padding([8, 12])
                    .width(Length::Fill),
                )
                .into()
            })
            .collect();

        let pending_list: Element<TaskQueueMessage> = if pending_items.is_empty() {
            text("All caught up! 🎉")
                .size(12)
                .color(c.text_secondary)
                .into()
        } else {
            scrollable(column(pending_items).spacing(4))
                .height(Length::Fill)
                .into()
        };

        // Quick add input
        let quick_add = row![
            text_input("Add a task...", &self.quick_add_input)
                .on_input(TaskQueueMessage::QuickAddInputChanged)
                .on_submit(TaskQueueMessage::QuickAddSubmit)
                .padding(8)
                .size(13)
                .width(Length::Fill),
            button(text("+").size(14).color(c.text_primary))
                .on_press(TaskQueueMessage::QuickAddSubmit)
                .padding([8, 12]),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        // Stats footer
        let stats_footer = row![
            text(format!("📊 {} pending", self.stats.pending))
                .size(11)
                .color(c.text_secondary),
            text("·").size(11).color(c.text_secondary),
            text(format!("{} in progress", self.stats.in_progress))
                .size(11)
                .color(c.text_secondary),
            text("·").size(11).color(c.text_secondary),
            text(format!("{} completed", self.stats.completed))
                .size(11)
                .color(c.text_secondary),
        ]
        .spacing(4);

        container(
            column![
                header,
                Space::new().height(1),
                current_section,
                Space::new().height(8),
                pending_header,
                pending_list,
                Space::new().height(1),
                quick_add,
                stats_footer,
            ]
            .spacing(8)
            .padding(16),
        )
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(c.bg)),
            border: iced::Border {
                radius: 10.0.into(),
                width: 1.0,
                color: c.border,
            },
            ..Default::default()
        })
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    // ========================================================================
    // Agent Panel View
    // ========================================================================

    /// Render the agent panel showing active agents
    fn view_agents_panel(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let header = row![
            text("🤖 Active Agents").size(14).color(c.text_primary),
            Space::new().width(Length::Fill),
            text(format!("{}", self.agents.len()))
                .size(12)
                .color(c.text_secondary),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        // Separate session agents and sub-agents
        let session_agents: Vec<_> = self
            .agents
            .iter()
            .filter(|a| a.agent_type == AgentType::SessionAgent)
            .collect();
        let sub_agents: Vec<_> = self
            .agents
            .iter()
            .filter(|a| a.agent_type == AgentType::SubAgent)
            .collect();

        let session_section: Element<TaskQueueMessage> = if session_agents.is_empty() {
            text("No active session agents")
                .size(11)
                .color(c.text_secondary)
                .into()
        } else {
            let items: Vec<Element<TaskQueueMessage>> = session_agents
                .iter()
                .map(|agent| self.view_agent_item(agent))
                .collect();
            column![
                text("Session Agents").size(11).color(c.text_secondary),
                column(items).spacing(4),
            ]
            .spacing(4)
            .into()
        };

        let sub_section: Element<TaskQueueMessage> = if sub_agents.is_empty() {
            column![].into() // Empty when no sub-agents
        } else {
            let items: Vec<Element<TaskQueueMessage>> = sub_agents
                .iter()
                .map(|agent| self.view_agent_item(agent))
                .collect();
            column![
                text("Sub-Agents").size(11).color(c.text_secondary),
                column(items).spacing(4),
            ]
            .spacing(4)
            .into()
        };

        container(
            column![
                header,
                Space::new().height(8),
                scrollable(
                    column![session_section, Space::new().height(8), sub_section,].spacing(4)
                )
                .height(Length::Fill),
            ]
            .spacing(8)
            .padding(12),
        )
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(c.bg)),
            border: iced::Border {
                radius: 10.0.into(),
                width: 1.0,
                color: c.border,
            },
            ..Default::default()
        })
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    /// Render a single agent item
    fn view_agent_item<'a>(&'a self, agent: &'a Agent) -> Element<'a, TaskQueueMessage> {
        let c = &self.colors;
        let agent_id = agent.id.clone();
        let is_selected = self.selected_agent_id.as_ref() == Some(&agent.id);

        let status_color = match agent.status {
            AgentStatus::Active => c.success,
            AgentStatus::Idle => c.text_secondary,
            AgentStatus::Waiting => c.warning,
            AgentStatus::Completed => c.accent,
        };

        let name = agent
            .name
            .as_deref()
            .unwrap_or(&agent.id[..8.min(agent.id.len())]);

        let task_info = if let Some(task_id) = &agent.current_task_id {
            // Find task content
            if let Some(task) = self.tasks.iter().find(|t| &t.id == task_id) {
                truncate(&task.content, 20)
            } else {
                format!("Task {}", &task_id[..8.min(task_id.len())])
            }
        } else {
            "No task".to_string()
        };

        button(
            row![
                text(agent.agent_type.emoji()).size(14),
                column![
                    row![
                        text(name).size(12).color(c.text_primary),
                        text(agent.status.emoji()).size(10).color(status_color),
                    ]
                    .spacing(4),
                    text(task_info).size(10).color(c.text_secondary),
                ]
                .spacing(2),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
        )
        .on_press(TaskQueueMessage::SelectAgent(agent_id))
        .padding([6, 10])
        .width(Length::Fill)
        .style(move |_, _| button::Style {
            background: if is_selected {
                Some(iced::Background::Color(c.accent.scale_alpha(0.2)))
            } else {
                Some(iced::Background::Color(c.bg_elevated))
            },
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            text_color: c.text_primary,
            ..Default::default()
        })
        .into()
    }

    // ========================================================================
    // History Panel View
    // ========================================================================

    /// Render the task history panel
    fn view_history_panel(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let header = row![
            text("📜 Task History").size(14).color(c.text_primary),
            Space::new().width(Length::Fill),
            text(format!("{}", self.task_history.len()))
                .size(12)
                .color(c.text_secondary),
            button(text("🗑").size(12))
                .on_press(TaskQueueMessage::ClearHistory)
                .padding([4, 8]),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        let history_items: Vec<Element<TaskQueueMessage>> = self
            .task_history
            .iter()
            .rev() // Most recent first
            .take(20) // Limit display
            .map(|entry| self.view_history_item(entry))
            .collect();

        let history_list: Element<TaskQueueMessage> = if history_items.is_empty() {
            text("No completed tasks yet")
                .size(11)
                .color(c.text_secondary)
                .into()
        } else {
            scrollable(column(history_items).spacing(4))
                .height(Length::Fill)
                .into()
        };

        container(
            column![header, Space::new().height(8), history_list,]
                .spacing(8)
                .padding(12),
        )
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(c.bg)),
            border: iced::Border {
                radius: 10.0.into(),
                width: 1.0,
                color: c.border,
            },
            ..Default::default()
        })
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
    }

    /// Render a single history item
    fn view_history_item<'a>(
        &'a self,
        entry: &'a TaskHistoryEntry,
    ) -> Element<'a, TaskQueueMessage> {
        let c = &self.colors;
        let task = &entry.task;

        let status_icon = match task.status {
            TaskStatus::Completed => "✓",
            TaskStatus::Cancelled => "✗",
            _ => "?",
        };
        let status_color = match task.status {
            TaskStatus::Completed => c.success,
            TaskStatus::Cancelled => c.danger,
            _ => c.text_secondary,
        };

        let duration_text = entry.duration_secs.map(format_duration).unwrap_or_default();

        container(
            row![
                text(status_icon).size(12).color(status_color),
                column![
                    text(truncate(&task.content, 30))
                        .size(11)
                        .color(c.text_primary),
                    row![
                        text(task.priority.emoji()).size(10),
                        text(task.project.as_deref().unwrap_or("no project"))
                            .size(9)
                            .color(c.text_secondary),
                        if !duration_text.is_empty() {
                            text(format!("· {}", duration_text))
                                .size(9)
                                .color(c.text_secondary)
                        } else {
                            text("").size(9)
                        },
                    ]
                    .spacing(4),
                ]
                .spacing(2),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
        )
        .style(move |_| container::Style {
            background: Some(iced::Background::Color(c.bg_elevated)),
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .padding([6, 10])
        .width(Length::Fill)
        .into()
    }

    // ========================================================================
    // Layout Views
    // ========================================================================

    /// Render based on layout configuration
    pub fn view_with_layout(&self) -> Element<'_, TaskQueueMessage> {
        match self.layout {
            PanelLayout::Single(panel) => self.view_panel(panel),
            PanelLayout::SideBySide(left, right) => {
                row![self.view_panel(left), self.view_panel(right),]
                    .spacing(8)
                    .into()
            }
            PanelLayout::Stacked(top, bottom) => {
                column![self.view_panel(top), self.view_panel(bottom),]
                    .spacing(8)
                    .into()
            }
            PanelLayout::ThreeColumn(left, center, right) => row![
                self.view_panel(left),
                self.view_panel(center),
                self.view_panel(right),
            ]
            .spacing(8)
            .into(),
        }
    }

    /// Render a specific panel
    fn view_panel(&self, panel: ActivePanel) -> Element<'_, TaskQueueMessage> {
        match panel {
            ActivePanel::Tasks => self.view_full(),
            ActivePanel::Agents => self.view_agents_panel(),
            ActivePanel::History => self.view_history_panel(),
        }
    }

    /// Panel switcher toolbar
    pub fn view_panel_switcher(&self) -> Element<'_, TaskQueueMessage> {
        let c = &self.colors;

        let active_panel = match self.layout {
            PanelLayout::Single(p) => Some(p),
            _ => None,
        };

        row![
            panel_tab_button("📋", "Tasks", ActivePanel::Tasks, active_panel, c),
            panel_tab_button("🤖", "Agents", ActivePanel::Agents, active_panel, c),
            panel_tab_button("📜", "History", ActivePanel::History, active_panel, c),
        ]
        .spacing(4)
        .into()
    }
}

// ============================================================================
// Helper Functions
// ============================================================================

/// Create a panel tab button
fn panel_tab_button<'a>(
    icon: &'a str,
    label: &'a str,
    panel: ActivePanel,
    active: Option<ActivePanel>,
    c: &TaskQueueColors,
) -> Element<'a, TaskQueueMessage> {
    let is_active = active == Some(panel);
    let c = *c;

    button(
        row![
            text(icon).size(12),
            text(label).size(11).color(c.text_primary),
        ]
        .spacing(4)
        .align_y(Alignment::Center),
    )
    .on_press(TaskQueueMessage::SwitchPanel(panel))
    .padding([6, 10])
    .style(move |_, _| button::Style {
        background: if is_active {
            Some(iced::Background::Color(c.accent.scale_alpha(0.3)))
        } else {
            Some(iced::Background::Color(c.bg_elevated))
        },
        border: iced::Border {
            radius: 6.0.into(),
            width: if is_active { 1.0 } else { 0.0 },
            color: c.accent,
        },
        text_color: c.text_primary,
        ..Default::default()
    })
    .into()
}

/// Format duration in seconds to human readable
fn format_duration(secs: u64) -> String {
    if secs < 60 {
        format!("{}s", secs)
    } else if secs < 3600 {
        format!("{}m", secs / 60)
    } else if secs < 86400 {
        format!("{}h", secs / 3600)
    } else {
        format!("{}d", secs / 86400)
    }
}

// ============================================================================
// Messages and Actions
// ============================================================================

/// Messages the widget can receive
#[derive(Debug, Clone)]
pub enum TaskQueueMessage {
    // Connection events
    Connected,
    Disconnected,

    // Task data events
    TasksLoaded(Vec<Task>),
    TaskAdded(Task),
    TaskUpdated(Task),
    TaskCompleted(String),
    TaskRemoved(String),
    StatsUpdated(QueueStats),

    // Agent data events
    AgentsUpdated(Vec<Agent>),
    AgentAdded(Agent),
    AgentRemoved(String),
    AgentStatusChanged { id: String, status: AgentStatus },

    // History events
    HistoryLoaded(Vec<TaskHistoryEntry>),
    HistoryEntryAdded(TaskHistoryEntry),

    // Layout events
    SetLayout(PanelLayout),
    SwitchPanel(ActivePanel),

    // User interactions - Tasks
    SelectTask(String),
    StartTask(String),
    CompleteCurrentTask,
    QuickAddInputChanged(String),
    QuickAddSubmit,
    Refresh,

    // User interactions - Agents
    SelectAgent(String),

    // User interactions - History
    ClearHistory,
}

/// Actions to be performed by the parent application
#[derive(Debug, Clone)]
pub enum TaskQueueAction {
    AddTask(String),
    StartTask(String),
    CompleteTask(String),
    Refresh,
    RequestAgents,
    RequestHistory,
}

// ============================================================================
// Helper Functions
// ============================================================================

/// Truncate string to max length with ellipsis
fn truncate(s: &str, max_len: usize) -> String {
    if s.chars().count() <= max_len {
        s.to_string()
    } else {
        format!(
            "{}…",
            s.chars()
                .take(max_len.saturating_sub(1))
                .collect::<String>()
        )
    }
}
