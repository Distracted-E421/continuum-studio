//! Activity Feed for Continuum Studio
//!
//! Displays real-time activity events from agents: file edits, commands,
//! tool calls, dialogs. Supports filtering and collapsible details.
//!
//! WebSocket integration (terminal monitor, dialog events) is stubbed for
//! future implementation.

use chrono::{DateTime, Utc};
use iced::widget::{button, column, container, pick_list, row, scrollable, text, Space};
use iced::{Alignment, Element, Length};
use std::collections::{HashSet, VecDeque};

/// Maximum events to retain (ring buffer style)
const MAX_EVENTS: usize = 500;

// =============================================================================
// Event Types
// =============================================================================

/// Types of activity events for filtering
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ActivityEventType {
    FileEdit,
    Command,
    ToolCall,
    DialogSent,
    DialogResponse,
}

impl ActivityEventType {
    pub fn all() -> &'static [ActivityEventType] {
        &[
            ActivityEventType::FileEdit,
            ActivityEventType::Command,
            ActivityEventType::ToolCall,
            ActivityEventType::DialogSent,
            ActivityEventType::DialogResponse,
        ]
    }

    pub fn label(&self) -> &'static str {
        match self {
            ActivityEventType::FileEdit => "File Edit",
            ActivityEventType::Command => "Command",
            ActivityEventType::ToolCall => "Tool Call",
            ActivityEventType::DialogSent => "Dialog Sent",
            ActivityEventType::DialogResponse => "Dialog Response",
        }
    }

    pub fn emoji(&self) -> &'static str {
        match self {
            ActivityEventType::FileEdit => "📝",
            ActivityEventType::Command => "⌨️",
            ActivityEventType::ToolCall => "🔧",
            ActivityEventType::DialogSent => "💬",
            ActivityEventType::DialogResponse => "✓",
        }
    }
}

impl std::fmt::Display for ActivityEventType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} {}", self.emoji(), self.label())
    }
}

/// Activity event variants
#[derive(Debug, Clone)]
pub enum ActivityEvent {
    FileEdit {
        agent_id: String,
        path: String,
        lines_changed: i32,
        timestamp: DateTime<Utc>,
    },
    Command {
        agent_id: String,
        command: String,
        exit_code: Option<i32>,
        duration_ms: Option<u64>,
        timestamp: DateTime<Utc>,
    },
    ToolCall {
        agent_id: String,
        tool_name: String,
        status: String,
        timestamp: DateTime<Utc>,
    },
    DialogSent {
        agent_id: String,
        dialog_id: String,
        title: String,
        timestamp: DateTime<Utc>,
    },
    DialogResponse {
        dialog_id: String,
        selection: String,
        timestamp: DateTime<Utc>,
    },
}

impl ActivityEvent {
    pub fn event_type(&self) -> ActivityEventType {
        match self {
            ActivityEvent::FileEdit { .. } => ActivityEventType::FileEdit,
            ActivityEvent::Command { .. } => ActivityEventType::Command,
            ActivityEvent::ToolCall { .. } => ActivityEventType::ToolCall,
            ActivityEvent::DialogSent { .. } => ActivityEventType::DialogSent,
            ActivityEvent::DialogResponse { .. } => ActivityEventType::DialogResponse,
        }
    }

    pub fn agent_id(&self) -> Option<&str> {
        match self {
            ActivityEvent::FileEdit { agent_id, .. }
            | ActivityEvent::Command { agent_id, .. }
            | ActivityEvent::ToolCall { agent_id, .. }
            | ActivityEvent::DialogSent { agent_id, .. } => Some(agent_id.as_str()),
            ActivityEvent::DialogResponse { .. } => None,
        }
    }

    pub fn timestamp(&self) -> DateTime<Utc> {
        match self {
            ActivityEvent::FileEdit { timestamp, .. }
            | ActivityEvent::Command { timestamp, .. }
            | ActivityEvent::ToolCall { timestamp, .. }
            | ActivityEvent::DialogSent { timestamp, .. }
            | ActivityEvent::DialogResponse { timestamp, .. } => *timestamp,
        }
    }

    /// Short one-line description for list display
    pub fn summary(&self) -> String {
        match self {
            ActivityEvent::FileEdit {
                path,
                lines_changed,
                ..
            } => {
                let sign = if *lines_changed >= 0 { "+" } else { "" };
                format!("{} {} {} lines", path, sign, lines_changed)
            }
            ActivityEvent::Command { command, .. } => truncate(command, 60),
            ActivityEvent::ToolCall {
                tool_name, status, ..
            } => {
                format!("{} ({})", tool_name, status)
            }
            ActivityEvent::DialogSent { title, .. } => truncate(title, 50),
            ActivityEvent::DialogResponse { selection, .. } => {
                format!("→ {}", truncate(selection, 40))
            }
        }
    }

    /// Full details for expanded view
    pub fn details(&self) -> String {
        match self {
            ActivityEvent::FileEdit {
                agent_id,
                path,
                lines_changed,
                ..
            } => format!(
                "Agent: {}\nPath: {}\nLines changed: {}",
                agent_id, path, lines_changed
            ),
            ActivityEvent::Command {
                agent_id,
                command,
                exit_code,
                duration_ms,
                ..
            } => format!(
                "Agent: {}\nCommand: {}\nExit: {:?}  Duration: {:?}",
                agent_id, command, exit_code, duration_ms
            ),
            ActivityEvent::ToolCall {
                agent_id,
                tool_name,
                status,
                ..
            } => format!(
                "Agent: {}\nTool: {}\nStatus: {}",
                agent_id, tool_name, status
            ),
            ActivityEvent::DialogSent {
                agent_id,
                dialog_id,
                title,
                ..
            } => format!(
                "Agent: {}\nDialog ID: {}\nTitle: {}",
                agent_id, dialog_id, title
            ),
            ActivityEvent::DialogResponse {
                dialog_id,
                selection,
                ..
            } => format!("Dialog ID: {}\nSelection: {}", dialog_id, selection),
        }
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}...", &s[..max.saturating_sub(3)])
    }
}

// =============================================================================
// Filtering
// =============================================================================

/// Filter type for the activity feed
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub enum FilterType {
    #[default]
    All,
    ByAgent(String),
    ByType(ActivityEventType),
}

impl FilterType {
    pub fn all() -> Vec<FilterType> {
        let mut v = vec![FilterType::All];
        v.extend(
            ActivityEventType::all()
                .iter()
                .map(|t| FilterType::ByType(*t)),
        );
        v
    }

    pub fn label(&self, _agents: &[String]) -> String {
        match self {
            FilterType::All => "All".to_string(),
            FilterType::ByAgent(id) => format!("Agent: {}", truncate(id, 20)),
            FilterType::ByType(t) => t.to_string(),
        }
    }
}

impl std::fmt::Display for FilterType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.label(&[]))
    }
}

// =============================================================================
// State
// =============================================================================

/// Activity feed state
#[derive(Debug, Clone)]
pub struct ActivityFeedState {
    /// Ring buffer of events (newest at back)
    pub events: VecDeque<ActivityEvent>,
    /// Current filter
    pub filter: FilterType,
    /// Indices of expanded items (for collapsible details)
    pub expanded: HashSet<usize>,
    /// Unique agent IDs for filter dropdown (when ByAgent)
    pub agent_ids: Vec<String>,
    /// Filter options for pick_list (All + ByType + ByAgent per id)
    pub filter_options: Vec<FilterType>,
}

impl Default for ActivityFeedState {
    fn default() -> Self {
        let mut opts = vec![FilterType::All];
        opts.extend(
            ActivityEventType::all()
                .iter()
                .map(|t| FilterType::ByType(*t)),
        );
        Self {
            events: VecDeque::new(),
            filter: FilterType::default(),
            expanded: HashSet::new(),
            agent_ids: Vec::new(),
            filter_options: opts,
        }
    }
}

impl ActivityFeedState {
    pub fn new() -> Self {
        let mut s = Self::default();
        s.rebuild_filter_options();
        s
    }

    /// Add a new event (ring buffer: drop oldest when at capacity)
    pub fn push(&mut self, event: ActivityEvent) {
        // Update agent_ids
        if let Some(id) = event.agent_id() {
            let id = id.to_string();
            if !self.agent_ids.contains(&id) {
                self.agent_ids.push(id);
                self.agent_ids.sort();
                self.rebuild_filter_options();
            }
        }

        if self.events.len() >= MAX_EVENTS {
            self.events.pop_front();
        }
        self.events.push_back(event);
    }

    /// Rebuild filter_options for the dropdown
    fn rebuild_filter_options(&mut self) {
        let mut opts = vec![FilterType::All];
        opts.extend(
            ActivityEventType::all()
                .iter()
                .map(|t| FilterType::ByType(*t)),
        );
        opts.extend(self.agent_ids.iter().cloned().map(FilterType::ByAgent));
        self.filter_options = opts;
    }

    /// Get filtered events (newest first for display)
    pub fn filtered_events(&self) -> Vec<(usize, &ActivityEvent)> {
        let iter = self.events.iter().enumerate().rev();
        let filtered: Vec<_> = iter
            .filter(|(_, e)| match &self.filter {
                FilterType::All => true,
                FilterType::ByAgent(id) => e.agent_id().map(|a| a == id).unwrap_or(false),
                FilterType::ByType(t) => e.event_type() == *t,
            })
            .collect();
        filtered
    }

    /// Toggle expanded state for an index (in the unfiltered list, we use storage index)
    pub fn toggle_expanded(&mut self, storage_index: usize) {
        if self.expanded.contains(&storage_index) {
            self.expanded.remove(&storage_index);
        } else {
            self.expanded.insert(storage_index);
        }
    }
}

// =============================================================================
// Messages
// =============================================================================

/// Messages for the activity feed
#[derive(Debug, Clone)]
pub enum ActivityMessage {
    NewEvent(ActivityEvent),
    SetFilter(FilterType),
    ClearEvents,
    ToggleExpand(usize),
}

impl ActivityFeedState {
    pub fn update(&mut self, message: ActivityMessage) {
        match message {
            ActivityMessage::NewEvent(event) => self.push(event),
            ActivityMessage::SetFilter(filter) => self.filter = filter,
            ActivityMessage::ClearEvents => {
                self.events.clear();
                self.expanded.clear();
                self.agent_ids.clear();
                self.filter_options = vec![FilterType::All];
                self.filter_options.extend(
                    ActivityEventType::all()
                        .iter()
                        .map(|t| FilterType::ByType(*t)),
                );
            }
            ActivityMessage::ToggleExpand(idx) => self.toggle_expanded(idx),
        }
    }
}

// =============================================================================
// View
// =============================================================================

/// Hash agent_id to a stable RGB color for badge
fn agent_color(agent_id: &str) -> (f32, f32, f32) {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};

    let mut hasher = DefaultHasher::new();
    agent_id.hash(&mut hasher);
    let h = hasher.finish();

    // Generate hue from hash, keep saturation and value reasonable
    let hue = ((h % 360) as f32) / 360.0;
    // HSL to RGB approximation (simplified)
    let s = 0.6;
    let l = 0.45;
    let c = (1.0_f32 - (2.0_f32 * l - 1.0_f32).abs()) * s;
    let x = c * (1.0 - ((hue * 6.0) % 2.0 - 1.0).abs());
    let m = l - c / 2.0;

    let (r, g, b) = if hue < 1.0 / 6.0 {
        (c, x, 0.0)
    } else if hue < 2.0 / 6.0 {
        (x, c, 0.0)
    } else if hue < 3.0 / 6.0 {
        (0.0, c, x)
    } else if hue < 4.0 / 6.0 {
        (0.0, x, c)
    } else if hue < 5.0 / 6.0 {
        (x, 0.0, c)
    } else {
        (c, 0.0, x)
    };

    (r + m, g + m, b + m)
}

/// Main view for the activity feed
pub fn view_activity_feed<'a, M>(
    state: &'a ActivityFeedState,
    to_message: impl Fn(ActivityMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let header = view_header(state, to_message.clone());
    let event_list = view_event_list(state, to_message);
    let content = column![header, Space::new().height(8), event_list]
        .spacing(4)
        .height(Length::Fill);

    scrollable(content).height(Length::Fill).into()
}

fn view_header<'a, M>(
    state: &'a ActivityFeedState,
    to_message: impl Fn(ActivityMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let title = text("📋 Activity Feed").size(18);

    let to_msg_filter = to_message.clone();
    let filter_pick = pick_list(
        state.filter_options.as_slice(),
        Some(state.filter.clone()),
        move |f| to_msg_filter(ActivityMessage::SetFilter(f)),
    );

    let clear_btn = button(text("Clear").size(12))
        .padding([6, 10])
        .on_press(to_message(ActivityMessage::ClearEvents));

    let count = text(format!("{} events", state.events.len())).size(12);

    row![
        title,
        Space::new().width(Length::Fill),
        filter_pick,
        Space::new().width(8),
        clear_btn,
        Space::new().width(8),
        count,
    ]
    .align_y(Alignment::Center)
    .spacing(8)
    .into()
}

fn view_event_list<'a, M>(
    state: &'a ActivityFeedState,
    to_message: impl Fn(ActivityMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let filtered = state.filtered_events();

    if filtered.is_empty() {
        let empty = container(
            text("No activity yet. Events will appear as agents work.")
                .size(14)
                .style(|_: &iced::Theme| text::Style {
                    color: Some(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                }),
        )
        .padding(24)
        .center_x(Length::Fill)
        .center_y(Length::Fixed(200.0))
        .width(Length::Fill)
        .height(200)
        .into();

        return empty;
    }

    let items: Vec<Element<'a, M>> = filtered
        .into_iter()
        .map(|(storage_index, event)| {
            view_event_row(
                storage_index,
                event,
                state.expanded.contains(&storage_index),
                to_message.clone(),
            )
        })
        .collect();

    scrollable(column(items).spacing(4))
        .height(Length::Fill)
        .into()
}

fn view_event_row<'a, M>(
    storage_index: usize,
    event: &'a ActivityEvent,
    expanded: bool,
    to_message: impl Fn(ActivityMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let ts = event.timestamp();
    let time_str = ts.format("%H:%M:%S").to_string();

    let badge: Element<'a, M> = event
        .agent_id()
        .map(|id| {
            let (r, g, b) = agent_color(id);
            text(truncate(id, 12))
                .size(11)
                .style(move |_: &iced::Theme| text::Style {
                    color: Some(iced::Color::from_rgb(r, g, b)),
                })
                .into()
        })
        .unwrap_or_else(|| Space::new().width(0).into());

    let event_type = event.event_type();
    let summary = event.summary();

    let expand_icon = if expanded { "▼" } else { "▶" };

    let header_content = row![
        text(time_str)
            .size(11)
            .style(|_: &iced::Theme| text::Style {
                color: Some(iced::Color::from_rgb(0.5, 0.5, 0.55)),
            }),
        Space::new().width(8),
        text(event_type.emoji()).size(12),
        Space::new().width(4),
        badge,
        Space::new().width(8),
        text(summary).size(13),
        Space::new().width(Length::Fill),
        button(text(expand_icon).size(10))
            .padding(2)
            .on_press(to_message(ActivityMessage::ToggleExpand(storage_index))),
    ]
    .align_y(Alignment::Center)
    .spacing(4);

    let row_content = if expanded {
        let details = event.details();
        column![
            container(header_content).padding(8),
            container(text(details).size(12).style(|_: &iced::Theme| text::Style {
                color: Some(iced::Color::from_rgb(0.6, 0.6, 0.65)),
            }))
            .padding(8)
        ]
        .spacing(0)
    } else {
        column![container(header_content).padding(8)].spacing(0)
    };

    container(row_content)
        .style(|_: &iced::Theme| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.15, 0.15, 0.18,
            ))),
            border: iced::Border {
                radius: 6.0.into(),
                width: 1.0,
                color: iced::Color::from_rgb(0.25, 0.25, 0.28),
            },
            ..Default::default()
        })
        .padding(4)
        .into()
}

// =============================================================================
// WebSocket Integration
// =============================================================================
//
// The actual WebSocket implementation is in `activity_stream_client.rs`:
// - Connects to ws://localhost:8080/ws/activity
// - Converts daemon events to ActivityEvent
// - Used by activity_stream_worker() in main.rs
//
// This module focuses on UI state management. Events flow through:
// activity_stream_client -> main.rs -> ActivityMessage::NewEvent -> ActivityFeedState
