//! SCP Activity Stream canvas — timeline, grouped, and trace views with filters.

use std::collections::{HashMap, HashSet};
use std::fmt;

use chrono::{DateTime, TimeZone, Utc};
use futures_util::StreamExt;
use iced::widget::{
    button, checkbox, column, container, pick_list, row, scrollable, slider, text, text_input,
    toggler, Space,
};
use iced::{Alignment, Element, Length, Subscription, Task, Theme};

use super::scp_activity_ws::{scp_activity_ws_worker, ScpActivityWsEvent};
use super::{Canvas, CanvasError};
use crate::scp::activity::{ActivityEvent, ActivityType};
use crate::scp::{CanvasType, Importance};

fn utc_fallback() -> DateTime<Utc> {
    Utc.timestamp_opt(0, 0).single().expect("unix epoch")
}

const MAX_EVENTS: usize = 2000;
const CANVAS_ID: &str = "scp-activity-stream";

// -----------------------------------------------------------------------------
// Filters & view mode
// -----------------------------------------------------------------------------

#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize, PartialEq, Eq)]
pub struct ActivityFilters {
    pub types: HashSet<ActivityType>,
    pub agents: HashSet<String>,
    pub importance_min: Importance,
    pub time_range: Option<(DateTime<Utc>, DateTime<Utc>)>,
    pub search_query: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum GroupBy {
    Type,
    File,
    Agent,
}

impl fmt::Display for GroupBy {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            GroupBy::Type => write!(f, "Type"),
            GroupBy::File => write!(f, "File"),
            GroupBy::Agent => write!(f, "Agent"),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum ViewMode {
    Timeline,
    Grouped { group_by: GroupBy },
    Trace,
}

impl Default for ViewMode {
    fn default() -> Self {
        ViewMode::Timeline
    }
}

#[derive(Debug, Clone)]
pub enum ActivityStreamMessage {
    Ws(ScpActivityWsEvent),
    SetViewMode(ViewMode),
    SetGroupBy(GroupBy),
    ToggleType(ActivityType, bool),
    ClearTypesFilter,
    SelectAgent(Option<String>),
    SetImportanceMin(Importance),
    SearchChanged(String),
    TimeStartChanged(String),
    TimeEndChanged(String),
    ApplyTimeRange,
    ClearTimeRange,
    ToggleRelativeTime(bool),
    SelectEvent(Option<usize>),
    ToggleOutputExpanded(String),
    ToggleGroupExpanded(String),
    ToggleTraceCollapsed(String),
    /// Generic SCP canvas WebSocket event (for canvas commands/actions)
    CanvasWsEvent(super::canvas_ws::CanvasWsEvent),
}

#[derive(Debug, Clone)]
pub struct ActivityLayoutError(pub String);

impl std::fmt::Display for ActivityLayoutError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for ActivityLayoutError {}

/// Primary Activity Stream canvas state.
#[derive(Debug, Clone)]
pub struct ActivityStreamCanvas {
    canvas_id: String,
    pub session_id: Option<String>,
    pub task_id: Option<String>,
    ws_url: String,
    events: Vec<ActivityEvent>,
    filtered_indices: Vec<usize>,
    pub filters: ActivityFilters,
    /// When false, all `ActivityType`s pass; when true, only types in `filters.types`.
    type_filter_explicit: bool,
    pub view_mode: ViewMode,
    /// Selected row — index into `events`.
    selected_event: Option<usize>,
    expanded_outputs: HashSet<String>,
    collapsed_groups: HashSet<String>,
    trace_collapsed: HashSet<String>,
    ws_connected: bool,
    search_input: String,
    time_start_input: String,
    time_end_input: String,
    relative_time: bool,
    importance_slider: f32,
    /// Enable generic SCP canvas WebSocket (for canvas commands/actions)
    pub canvas_ws_enabled: bool,
    /// SCP canvas WebSocket subscription ID
    canvas_ws_subscription_id: Option<String>,
}

impl Default for ActivityStreamCanvas {
    fn default() -> Self {
        Self {
            canvas_id: CANVAS_ID.to_string(),
            session_id: None,
            task_id: None,
            ws_url: super::scp_activity_ws::DEFAULT_SCP_ACTIVITY_WS_URL.to_string(),
            events: Vec::new(),
            filtered_indices: Vec::new(),
            filters: ActivityFilters {
                importance_min: Importance::Debug,
                ..Default::default()
            },
            type_filter_explicit: false,
            view_mode: ViewMode::default(),
            selected_event: None,
            expanded_outputs: HashSet::new(),
            collapsed_groups: HashSet::new(),
            trace_collapsed: HashSet::new(),
            ws_connected: false,
            search_input: String::new(),
            time_start_input: String::new(),
            time_end_input: String::new(),
            relative_time: true,
            importance_slider: 0.0,
            canvas_ws_enabled: false,
            canvas_ws_subscription_id: None,
        }
    }
}

impl ActivityStreamCanvas {
    pub fn new(id: impl Into<String>, session_id: Option<String>, task_id: Option<String>) -> Self {
        Self {
            canvas_id: id.into(),
            session_id,
            task_id,
            ..Self::default()
        }
    }

    pub fn with_ws_url(mut self, url: impl Into<String>) -> Self {
        self.ws_url = url.into();
        self
    }

    /// Enable the generic SCP canvas WebSocket for canvas commands/actions
    pub fn with_canvas_ws(mut self, enabled: bool) -> Self {
        self.canvas_ws_enabled = enabled;
        self
    }

    /// Enable/disable the generic SCP canvas WebSocket
    pub fn set_canvas_ws(&mut self, enabled: bool) {
        self.canvas_ws_enabled = enabled;
        if !enabled {
            self.canvas_ws_subscription_id = None;
        }
    }

    fn handle_canvas_ws_event(&mut self, ev: super::canvas_ws::CanvasWsEvent) {
        use super::canvas_ws::{CanvasType as WsCanvasType, CanvasWsEvent};

        match ev {
            CanvasWsEvent::Connected => {
                log::info!(
                    "[activity_stream] Canvas WebSocket connected, canvas_id={}",
                    self.canvas_id
                );
            }
            CanvasWsEvent::Disconnected => {
                log::warn!(
                    "[activity_stream] Canvas WebSocket disconnected, canvas_id={}",
                    self.canvas_id
                );
                self.canvas_ws_subscription_id = None;
            }
            CanvasWsEvent::Subscribed {
                subscription_id,
                canvas_id,
                canvas_type,
            } => {
                if canvas_id == self.canvas_id && canvas_type == WsCanvasType::ActivityStream {
                    log::info!(
                        "[activity_stream] Subscribed to canvas: sub_id={}",
                        subscription_id
                    );
                    self.canvas_ws_subscription_id = Some(subscription_id);
                }
            }
            CanvasWsEvent::Unsubscribed { subscription_id } => {
                if self.canvas_ws_subscription_id.as_deref() == Some(&subscription_id) {
                    log::info!("[activity_stream] Unsubscribed: {}", subscription_id);
                    self.canvas_ws_subscription_id = None;
                }
            }
            CanvasWsEvent::Update {
                subscription_id,
                canvas_id,
                payload,
                ..
            } => {
                if canvas_id != self.canvas_id {
                    return;
                }
                if self.canvas_ws_subscription_id.as_deref() != Some(&subscription_id) {
                    return;
                }
                // Payload can contain filter updates pushed from server
                if let Some(filters_val) = payload.get("filters") {
                    if let Ok(filters) =
                        serde_json::from_value::<ActivityFilters>(filters_val.clone())
                    {
                        self.filters = filters;
                        self.rebuild_filtered();
                        log::debug!("[activity_stream] Filters updated from server");
                    }
                }
                if let Some(view_mode_val) = payload.get("view_mode") {
                    if let Ok(view_mode) =
                        serde_json::from_value::<ViewMode>(view_mode_val.clone())
                    {
                        self.view_mode = view_mode;
                        log::debug!("[activity_stream] View mode updated from server");
                    }
                }
            }
            CanvasWsEvent::ActionReceived { .. } => {}
            CanvasWsEvent::Error { code, message } => {
                log::warn!(
                    "[activity_stream] Canvas WebSocket error: code={}, msg={}",
                    code,
                    message
                );
            }
            CanvasWsEvent::Heartbeat => {}
        }
    }

    pub fn event_count(&self) -> usize {
        self.events.len()
    }

    pub fn ws_connected(&self) -> bool {
        self.ws_connected
    }

    pub fn ws_url(&self) -> &str {
        self.ws_url.as_str()
    }

    pub fn heading_label(&self) -> &'static str {
        "Activity stream"
    }

    pub fn serialize(&self) -> serde_json::Value {
        self.serialize_layout()
    }

    pub fn deserialize(data: serde_json::Value) -> Result<Self, ActivityLayoutError> {
        Self::deserialize_layout(data)
    }

    pub fn serialize_layout(&self) -> serde_json::Value {
        serde_json::json!({
            "canvas_id": CANVAS_ID,
            "session_id": self.session_id,
            "task_id": self.task_id,
            "ws_url": self.ws_url,
            "filters": self.filters,
            "type_filter_explicit": self.type_filter_explicit,
            "view_mode": self.view_mode,
            "relative_time": self.relative_time,
        })
    }

    pub fn deserialize_layout(data: serde_json::Value) -> Result<Self, ActivityLayoutError> {
        #[derive(serde::Deserialize)]
        struct Persisted {
            #[serde(default)]
            session_id: Option<String>,
            #[serde(default)]
            task_id: Option<String>,
            #[serde(default)]
            ws_url: Option<String>,
            #[serde(default)]
            filters: ActivityFilters,
            #[serde(default)]
            type_filter_explicit: bool,
            #[serde(default)]
            view_mode: ViewMode,
            #[serde(default)]
            relative_time: bool,
        }
        let p: Persisted =
            serde_json::from_value(data).map_err(|e| ActivityLayoutError(e.to_string()))?;
        let filters = p.filters;
        let types_nonempty = !filters.types.is_empty();
        let mut canvas = ActivityStreamCanvas {
            session_id: p.session_id,
            task_id: p.task_id,
            ws_url: p
                .ws_url
                .unwrap_or_else(|| super::scp_activity_ws::DEFAULT_SCP_ACTIVITY_WS_URL.to_string()),
            filters,
            type_filter_explicit: p.type_filter_explicit || types_nonempty,
            view_mode: p.view_mode,
            relative_time: p.relative_time,
            importance_slider: 0.0,
            ..ActivityStreamCanvas::default()
        };
        canvas.sync_importance_slider_from_filter();
        Ok(canvas)
    }

    fn push_events(&mut self, incoming: Vec<ActivityEvent>) {
        for ev in incoming {
            if self.events.len() >= MAX_EVENTS {
                self.events.remove(0);
            }
            self.events.push(ev);
        }
        self.rebuild_filtered();
    }

    fn push_one(&mut self, ev: ActivityEvent) {
        self.push_events(vec![ev]);
    }

    fn rebuild_filtered(&mut self) {
        self.filtered_indices.clear();
        let query_lc = self
            .filters
            .search_query
            .as_ref()
            .map(|s| s.trim().to_lowercase())
            .filter(|s| !s.is_empty());

        for (i, ev) in self.events.iter().enumerate() {
            if let Some(ref sid) = self.session_id {
                if ev.session_id.as_deref() != Some(sid.as_str()) {
                    continue;
                }
            }
            if let Some(ref tid) = self.task_id {
                if ev.task_id.as_deref() != Some(tid.as_str()) {
                    continue;
                }
            }
            if self.type_filter_explicit && !self.filters.types.contains(&ev.activity_type) {
                continue;
            }
            if !self.filters.agents.is_empty() {
                match &ev.agent_id {
                    Some(a) if self.filters.agents.contains(a) => {}
                    _ => continue,
                }
            }
            if importance_rank(ev.importance) < importance_rank(self.filters.importance_min) {
                continue;
            }
            if let Some((start, end)) = self.filters.time_range {
                if let Ok(ts) = parse_event_time(ev) {
                    if ts < start || ts > end {
                        continue;
                    }
                }
            }
            if let Some(ref q) = query_lc {
                let hay = format!(
                    "{} {}",
                    event_summary(ev).to_lowercase(),
                    ev.content.to_string().to_lowercase()
                );
                if !hay.contains(q.as_str()) {
                    continue;
                }
            }
            self.filtered_indices.push(i);
        }
    }

    pub fn websocket_subscription(
        ws_url: impl Into<String>,
    ) -> Subscription<ActivityStreamMessage> {
        let url = ws_url.into();
        Subscription::run_with(url.clone(), move |u: &String| {
            scp_activity_ws_worker(Some(u.clone())).map(ActivityStreamMessage::Ws)
        })
    }

    fn sync_importance_slider_from_filter(&mut self) {
        self.importance_slider = importance_rank(self.filters.importance_min) as f32;
    }

    fn agents_for_pick_list(&self) -> Vec<String> {
        let mut v: Vec<String> = self
            .events
            .iter()
            .filter_map(|e| e.agent_id.clone())
            .collect::<HashSet<_>>()
            .into_iter()
            .collect();
        v.sort();
        v
    }
}

impl Canvas for ActivityStreamCanvas {
    type Message = ActivityStreamMessage;

    fn id(&self) -> &str {
        &self.canvas_id
    }

    fn canvas_type(&self) -> CanvasType {
        CanvasType::ActivityStream
    }

    fn title(&self) -> String {
        self.heading_label().to_string()
    }

    fn view<'a>(&'a self) -> Element<'a, Self::Message> {
        view(self)
    }

    fn update(&mut self, message: Self::Message) -> Task<Self::Message> {
        match message {
            ActivityStreamMessage::Ws(ev) => match ev {
                ScpActivityWsEvent::Connected => {
                    self.ws_connected = true;
                }
                ScpActivityWsEvent::Disconnected => {
                    self.ws_connected = false;
                }
                ScpActivityWsEvent::Initial { events } => {
                    self.ws_connected = true;
                    self.events.clear();
                    self.push_events(events);
                }
                ScpActivityWsEvent::Event(e) => {
                    self.push_one(e);
                }
                ScpActivityWsEvent::Error(e) => {
                    log::warn!("SCP activity WS: {}", e);
                }
            },
            ActivityStreamMessage::SetViewMode(m) => self.view_mode = m,
            ActivityStreamMessage::SetGroupBy(g) => {
                self.view_mode = ViewMode::Grouped { group_by: g };
            }
            ActivityStreamMessage::ToggleType(t, on) => {
                let all_count = ALL_ACTIVITY_TYPES.len();
                if !self.type_filter_explicit {
                    if !on {
                        self.type_filter_explicit = true;
                        self.filters.types = ALL_ACTIVITY_TYPES
                            .iter()
                            .copied()
                            .filter(|x| *x != t)
                            .collect();
                    }
                } else if on {
                    self.filters.types.insert(t);
                    if self.filters.types.len() == all_count {
                        self.type_filter_explicit = false;
                        self.filters.types.clear();
                    }
                } else {
                    self.filters.types.remove(&t);
                }
                self.rebuild_filtered();
            }
            ActivityStreamMessage::ClearTypesFilter => {
                self.type_filter_explicit = false;
                self.filters.types.clear();
                self.rebuild_filtered();
            }
            ActivityStreamMessage::SelectAgent(agent) => {
                self.filters.agents.clear();
                if let Some(a) = agent {
                    self.filters.agents.insert(a);
                }
                self.rebuild_filtered();
            }
            ActivityStreamMessage::SetImportanceMin(i) => {
                self.filters.importance_min = i;
                self.importance_slider = importance_rank(i) as f32;
                self.rebuild_filtered();
            }
            ActivityStreamMessage::SearchChanged(s) => {
                self.search_input = s.clone();
                self.filters.search_query = if s.trim().is_empty() { None } else { Some(s) };
                self.rebuild_filtered();
            }
            ActivityStreamMessage::TimeStartChanged(s) => self.time_start_input = s,
            ActivityStreamMessage::TimeEndChanged(s) => self.time_end_input = s,
            ActivityStreamMessage::ApplyTimeRange => {
                let start = parse_iso_optional(&self.time_start_input);
                let end = parse_iso_optional(&self.time_end_input);
                self.filters.time_range = match (start, end) {
                    (Some(a), Some(b)) => Some((a, b)),
                    (Some(a), None) => Some((a, Utc::now())),
                    _ => None,
                };
                self.rebuild_filtered();
            }
            ActivityStreamMessage::ClearTimeRange => {
                self.filters.time_range = None;
                self.time_start_input.clear();
                self.time_end_input.clear();
                self.rebuild_filtered();
            }
            ActivityStreamMessage::ToggleRelativeTime(v) => self.relative_time = v,
            ActivityStreamMessage::SelectEvent(idx) => self.selected_event = idx,
            ActivityStreamMessage::ToggleOutputExpanded(id) => {
                if self.expanded_outputs.contains(&id) {
                    self.expanded_outputs.remove(&id);
                } else {
                    self.expanded_outputs.insert(id);
                }
            }
            ActivityStreamMessage::ToggleGroupExpanded(key) => {
                if self.collapsed_groups.contains(&key) {
                    self.collapsed_groups.remove(&key);
                } else {
                    self.collapsed_groups.insert(key);
                }
            }
            ActivityStreamMessage::ToggleTraceCollapsed(id) => {
                if self.trace_collapsed.contains(&id) {
                    self.trace_collapsed.remove(&id);
                } else {
                    self.trace_collapsed.insert(id);
                }
            }
            ActivityStreamMessage::CanvasWsEvent(ev) => {
                self.handle_canvas_ws_event(ev);
            }
        }
        Task::none()
    }

    fn subscription(&self) -> Subscription<Self::Message> {
        use iced::futures::StreamExt;

        let activity_ws = Self::websocket_subscription(self.ws_url.clone());

        if self.canvas_ws_enabled {
            let canvas_ws = Subscription::run(|| {
                super::canvas_ws::canvas_ws_worker(None)
                    .map(ActivityStreamMessage::CanvasWsEvent)
            });
            Subscription::batch([activity_ws, canvas_ws])
        } else {
            activity_ws
        }
    }

    fn serialize_state(&self) -> serde_json::Value {
        self.serialize_layout()
    }

    fn deserialize_state(&mut self, data: &serde_json::Value) -> Result<(), CanvasError> {
        let old_id = self.canvas_id.clone();
        let c = ActivityStreamCanvas::deserialize_layout(data.clone())
            .map_err(|e| CanvasError::Deserialize(e.to_string()))?;
        *self = c;
        self.canvas_id = old_id;
        Ok(())
    }
}

pub fn view_activity_stream(canvas: &ActivityStreamCanvas) -> Element<'_, ActivityStreamMessage> {
    view(canvas)
}

// -----------------------------------------------------------------------------
// Helpers
// -----------------------------------------------------------------------------

const ALL_ACTIVITY_TYPES: &[ActivityType] = &[
    ActivityType::FileEdit,
    ActivityType::FileRead,
    ActivityType::FileCreate,
    ActivityType::FileDelete,
    ActivityType::ShellCommand,
    ActivityType::ShellOutput,
    ActivityType::ToolCall,
    ActivityType::ToolResult,
    ActivityType::ThinkingStart,
    ActivityType::ThinkingChunk,
    ActivityType::ThinkingEnd,
    ActivityType::VerificationStart,
    ActivityType::VerificationResult,
    ActivityType::PlanningPhase,
    ActivityType::ExecutionPhase,
    ActivityType::Error,
    ActivityType::Warning,
    ActivityType::Info,
];

fn importance_rank(i: Importance) -> u8 {
    match i {
        Importance::Debug => 0,
        Importance::Low => 1,
        Importance::Normal | Importance::Medium => 2,
        Importance::High => 3,
        Importance::Critical => 4,
    }
}

fn importance_from_slider(v: f32) -> Importance {
    match v.round().clamp(0.0, 4.0) as u8 {
        1 => Importance::Low,
        2 => Importance::Normal,
        3 => Importance::High,
        4 => Importance::Critical,
        _ => Importance::Debug,
    }
}

fn parse_event_time(ev: &ActivityEvent) -> Result<DateTime<Utc>, chrono::ParseError> {
    DateTime::parse_from_rfc3339(ev.timestamp.trim())
        .map(|d| d.with_timezone(&Utc))
        .or_else(|_| ev.timestamp.parse::<DateTime<Utc>>())
}

fn parse_iso_optional(s: &str) -> Option<DateTime<Utc>> {
    let t = s.trim();
    if t.is_empty() {
        return None;
    }
    DateTime::parse_from_rfc3339(t)
        .map(|d| d.with_timezone(&Utc))
        .ok()
        .or_else(|| t.parse::<DateTime<Utc>>().ok())
}

pub fn icon_for_type(t: ActivityType) -> &'static str {
    match t {
        ActivityType::FileEdit | ActivityType::FileCreate | ActivityType::FileDelete => "✏️",
        ActivityType::FileRead => "🔍",
        ActivityType::ShellCommand | ActivityType::ShellOutput => "💻",
        ActivityType::ToolCall | ActivityType::ToolResult => "🔧",
        ActivityType::ThinkingStart | ActivityType::ThinkingChunk | ActivityType::ThinkingEnd => {
            "💭"
        }
        ActivityType::VerificationStart | ActivityType::VerificationResult => "✅",
        ActivityType::PlanningPhase | ActivityType::ExecutionPhase => "📐",
        ActivityType::Error => "⚠️",
        ActivityType::Warning => "⚡",
        ActivityType::Info => "ℹ️",
    }
}

pub fn event_summary(ev: &ActivityEvent) -> String {
    match ev.activity_type {
        ActivityType::FileEdit | ActivityType::FileCreate | ActivityType::FileDelete => {
            let path = ev
                .content
                .get("path")
                .and_then(|x| x.as_str())
                .unwrap_or("?");
            let lines = ev.content.get("lines").and_then(|x| x.as_i64());
            let add = ev
                .content
                .get("additions")
                .and_then(|x| x.as_i64())
                .or(lines);
            let del = ev.content.get("deletions").and_then(|x| x.as_i64());
            match (add, del) {
                (Some(a), Some(d)) => format!("{path} +{a} -{d}"),
                (Some(a), None) => format!("{path} (+{a})"),
                _ => path.to_string(),
            }
        }
        ActivityType::ShellCommand => ev
            .content
            .get("command")
            .and_then(|x| x.as_str())
            .map(|s| truncate(s, 72))
            .unwrap_or_else(|| "shell".into()),
        ActivityType::ToolCall => ev
            .content
            .get("tool")
            .or_else(|| ev.content.get("name"))
            .and_then(|x| x.as_str())
            .map(|s| truncate(s, 60))
            .unwrap_or_else(|| "tool".into()),
        ActivityType::ThinkingChunk | ActivityType::ThinkingStart => ev
            .content
            .get("text")
            .or_else(|| ev.content.get("chunk"))
            .and_then(|x| x.as_str())
            .map(|s| truncate(s, 80))
            .unwrap_or_else(|| "thinking".into()),
        ActivityType::VerificationResult => ev
            .content
            .get("status")
            .or_else(|| ev.content.get("result"))
            .and_then(|x| x.as_str())
            .map(|s| format!("NeSy: {s}"))
            .unwrap_or_else(|| "verification".into()),
        _ => truncate(&ev.content.to_string(), 72),
    }
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}…", &s[..max.saturating_sub(1)])
    }
}

fn format_time(ev: &ActivityEvent, relative: bool) -> String {
    match parse_event_time(ev) {
        Ok(ts) => {
            if relative {
                let now = Utc::now();
                let d = now.signed_duration_since(ts);
                let secs = d.num_seconds();
                if secs < 60 {
                    format!("{}s ago", secs.max(0))
                } else if secs < 3600 {
                    format!("{}m ago", secs / 60)
                } else if secs < 86400 {
                    format!("{}h ago", secs / 3600)
                } else {
                    ts.format("%Y-%m-%d %H:%M").to_string()
                }
            } else {
                ts.format("%H:%M:%S").to_string()
            }
        }
        Err(_) => ev.timestamp.clone(),
    }
}

fn group_key(ev: &ActivityEvent, by: GroupBy) -> String {
    match by {
        GroupBy::Type => format!("type:{:?}", ev.activity_type).to_lowercase(),
        GroupBy::File => {
            let p = ev
                .content
                .get("path")
                .and_then(|x| x.as_str())
                .unwrap_or("(no path)");
            format!("file:{p}")
        }
        GroupBy::Agent => format!("agent:{}", ev.agent_id.as_deref().unwrap_or("(unknown)")),
    }
}

fn group_label(key: &str) -> &str {
    key.split_once(':').map(|(_, r)| r).unwrap_or(key)
}

/// Build `(event_index, depth)` rows for trace view using `filtered_indices`.
fn trace_rows(canvas: &ActivityStreamCanvas) -> Vec<(usize, u32)> {
    let id_to_idx: HashMap<String, usize> = canvas
        .filtered_indices
        .iter()
        .map(|&i| (canvas.events[i].id.clone(), i))
        .collect();

    let mut children: HashMap<String, Vec<usize>> = HashMap::new();
    let mut roots: Vec<usize> = Vec::new();

    for &i in &canvas.filtered_indices {
        let ev = &canvas.events[i];
        let parent_in_view = ev
            .parent_id
            .as_ref()
            .map(|p| id_to_idx.contains_key(p.as_str()))
            .unwrap_or(false);

        if parent_in_view {
            if let Some(pid) = &ev.parent_id {
                children.entry(pid.clone()).or_default().push(i);
            }
        } else {
            roots.push(i);
        }
    }

    roots.sort_by(|&a, &b| {
        parse_event_time(&canvas.events[b])
            .unwrap_or_else(|_| utc_fallback())
            .cmp(&parse_event_time(&canvas.events[a]).unwrap_or_else(|_| utc_fallback()))
    });

    let mut out = Vec::new();
    for r in roots {
        trace_dfs(canvas, r, 0, &children, &mut out);
    }
    out
}

fn trace_dfs(
    canvas: &ActivityStreamCanvas,
    idx: usize,
    depth: u32,
    children: &HashMap<String, Vec<usize>>,
    out: &mut Vec<(usize, u32)>,
) {
    out.push((idx, depth));
    let id = canvas.events[idx].id.clone();
    if canvas.trace_collapsed.contains(&id) {
        return;
    }
    let mut ch = children.get(&id).cloned().unwrap_or_default();
    ch.sort_by(|&a, &b| {
        parse_event_time(&canvas.events[a])
            .unwrap_or_else(|_| utc_fallback())
            .cmp(&parse_event_time(&canvas.events[b]).unwrap_or_else(|_| utc_fallback()))
    });
    for c in ch {
        trace_dfs(canvas, c, depth + 1, children, out);
    }
}

// -----------------------------------------------------------------------------
// View
// -----------------------------------------------------------------------------

fn view<'a>(canvas: &'a ActivityStreamCanvas) -> Element<'a, ActivityStreamMessage> {
    let conn = if canvas.ws_connected {
        "● live"
    } else {
        "○ offline"
    };
    let header = row![
        text(canvas.heading_label()).size(16),
        Space::new().width(Length::Fill),
        text(conn).size(11),
    ]
    .align_y(Alignment::Center);

    let mode_row = row![
        button(text("Timeline").size(11))
            .on_press(ActivityStreamMessage::SetViewMode(ViewMode::Timeline)),
        button(text("Grouped").size(11)).on_press(ActivityStreamMessage::SetViewMode(
            ViewMode::Grouped {
                group_by: GroupBy::Type
            }
        )),
        button(text("Trace").size(11))
            .on_press(ActivityStreamMessage::SetViewMode(ViewMode::Trace)),
        Space::new().width(12),
        pick_list(
            [GroupBy::Type, GroupBy::File, GroupBy::Agent],
            Some(match &canvas.view_mode {
                ViewMode::Grouped { group_by } => *group_by,
                _ => GroupBy::Type,
            }),
            ActivityStreamMessage::SetGroupBy,
        ),
        Space::new().width(Length::Fill),
        toggler(canvas.relative_time)
            .label("relative time")
            .spacing(8)
            .on_toggle(ActivityStreamMessage::ToggleRelativeTime),
    ]
    .spacing(6)
    .align_y(Alignment::Center);

    let agent_opts = canvas.agents_for_pick_list();
    let agent_pick: Element<'_, ActivityStreamMessage> = if agent_opts.is_empty() {
        text("(no agents yet)").size(11).into()
    } else {
        pick_list(
            agent_opts.clone(),
            canvas
                .filters
                .agents
                .iter()
                .next()
                .cloned()
                .filter(|a| agent_opts.contains(a)),
            |a| ActivityStreamMessage::SelectAgent(Some(a)),
        )
        .into()
    };

    let importance_label = text(format!(
        "min importance: {:?}",
        canvas.filters.importance_min
    ))
    .size(11);

    let filter_panel = container(
        column![
            text("Filters").size(12),
            row![
                text("Search").size(11),
                text_input("substring…", &canvas.search_input)
                    .on_input(ActivityStreamMessage::SearchChanged)
                    .width(Length::Fixed(180.0)),
                Space::new().width(12),
                text("Agent").size(11),
                agent_pick,
            ]
            .spacing(6)
            .align_y(Alignment::Center),
            row![
                text("Time (RFC3339)").size(11),
                text_input("start", &canvas.time_start_input)
                    .on_input(ActivityStreamMessage::TimeStartChanged)
                    .width(Length::Fixed(200.0)),
                text_input("end", &canvas.time_end_input)
                    .on_input(ActivityStreamMessage::TimeEndChanged)
                    .width(Length::Fixed(200.0)),
                button(text("Apply").size(11)).on_press(ActivityStreamMessage::ApplyTimeRange),
                button(text("Clear τ").size(11)).on_press(ActivityStreamMessage::ClearTimeRange),
            ]
            .spacing(6)
            .align_y(Alignment::Center),
            row![
                importance_label,
                slider(0.0..=4.0, canvas.importance_slider, |v| {
                    ActivityStreamMessage::SetImportanceMin(importance_from_slider(v))
                })
                .width(Length::Fixed(160.0)),
            ]
            .spacing(8)
            .align_y(Alignment::Center),
            type_checkbox_grid(canvas),
            button(text("All activity types").size(11))
                .on_press(ActivityStreamMessage::ClearTypesFilter),
        ]
        .spacing(6),
    )
    .padding(8)
    .style(|_: &Theme| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.14, 0.14, 0.16,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.22, 0.22, 0.26),
        },
        ..Default::default()
    });

    let list = match &canvas.view_mode {
        ViewMode::Timeline => view_timeline(canvas),
        ViewMode::Grouped { group_by } => view_grouped(canvas, *group_by),
        ViewMode::Trace => view_trace(canvas),
    };

    let detail = view_detail(canvas);

    scrollable(
        column![
            header,
            Space::new().height(8),
            mode_row,
            Space::new().height(8),
            filter_panel,
            Space::new().height(8),
            list,
            Space::new().height(12),
            detail,
        ]
        .spacing(0),
    )
    .height(Length::Fill)
    .into()
}

fn type_checkbox_grid(canvas: &ActivityStreamCanvas) -> Element<'static, ActivityStreamMessage> {
    let types = [
        ActivityType::FileEdit,
        ActivityType::FileRead,
        ActivityType::ShellCommand,
        ActivityType::ToolCall,
        ActivityType::ThinkingChunk,
        ActivityType::VerificationResult,
        ActivityType::Error,
        ActivityType::Info,
    ];
    let mut rows = Vec::new();
    for t in types {
        let checked = !canvas.type_filter_explicit || canvas.filters.types.contains(&t);
        let label = activity_type_label(t);
        rows.push(
            checkbox(checked)
                .label(label)
                .on_toggle(move |on| ActivityStreamMessage::ToggleType(t, on))
                .into(),
        );
    }
    row(rows).spacing(12).into()
}

fn activity_type_label(t: ActivityType) -> &'static str {
    match t {
        ActivityType::FileEdit => "edit",
        ActivityType::FileRead => "read",
        ActivityType::FileCreate => "create",
        ActivityType::FileDelete => "delete",
        ActivityType::ShellCommand => "shell",
        ActivityType::ShellOutput => "shell out",
        ActivityType::ToolCall => "tool",
        ActivityType::ToolResult => "tool result",
        ActivityType::ThinkingStart => "think ▶",
        ActivityType::ThinkingChunk => "think",
        ActivityType::ThinkingEnd => "think ◼",
        ActivityType::VerificationStart => "verify ▶",
        ActivityType::VerificationResult => "verify",
        ActivityType::PlanningPhase => "plan",
        ActivityType::ExecutionPhase => "exec",
        ActivityType::Error => "error",
        ActivityType::Warning => "warn",
        ActivityType::Info => "info",
    }
}

fn card_style() -> impl Fn(&Theme) -> container::Style + Copy {
    |_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.25, 0.25, 0.28),
        },
        ..Default::default()
    }
}

fn view_timeline<'a>(canvas: &'a ActivityStreamCanvas) -> Element<'a, ActivityStreamMessage> {
    let mut items: Vec<Element<'a, ActivityStreamMessage>> = Vec::new();
    let mut order: Vec<usize> = canvas.filtered_indices.clone();
    order.sort_by(|&a, &b| {
        parse_event_time(&canvas.events[b])
            .unwrap_or_else(|_| utc_fallback())
            .cmp(&parse_event_time(&canvas.events[a]).unwrap_or_else(|_| utc_fallback()))
    });

    if order.is_empty() {
        return container(text("No events (check filters or Synapsix /scp/activity).").size(13))
            .padding(16)
            .into();
    }

    for i in order {
        items.push(view_event_row(canvas, i, 0));
    }

    column(items).spacing(4).into()
}

fn view_grouped<'a>(
    canvas: &'a ActivityStreamCanvas,
    by: GroupBy,
) -> Element<'a, ActivityStreamMessage> {
    let mut groups: HashMap<String, Vec<usize>> = HashMap::new();
    for &i in &canvas.filtered_indices {
        let k = group_key(&canvas.events[i], by);
        groups.entry(k).or_default().push(i);
    }

    let mut keys: Vec<_> = groups.keys().cloned().collect();
    keys.sort();

    if keys.is_empty() {
        return container(text("No events.").size(13)).padding(16).into();
    }

    let mut cols: Vec<Element<'a, ActivityStreamMessage>> = Vec::new();

    for key in keys {
        let collapsed = canvas.collapsed_groups.contains(&key);
        let count = groups[&key].len();
        let header = row![
            button(text(if collapsed { "▶" } else { "▼" }).size(11))
                .on_press(ActivityStreamMessage::ToggleGroupExpanded(key.clone())),
            text(format!("{} ({})", truncate(group_label(&key), 48), count)).size(12),
        ]
        .spacing(6)
        .align_y(Alignment::Center);

        cols.push(header.into());

        if !collapsed {
            let mut v = groups[&key].clone();
            v.sort_by(|&a, &b| {
                parse_event_time(&canvas.events[b])
                    .unwrap_or_else(|_| utc_fallback())
                    .cmp(&parse_event_time(&canvas.events[a]).unwrap_or_else(|_| utc_fallback()))
            });
            for i in v {
                cols.push(view_event_row(canvas, i, 1));
            }
        }
    }

    column(cols).spacing(6).into()
}

fn view_trace<'a>(canvas: &'a ActivityStreamCanvas) -> Element<'a, ActivityStreamMessage> {
    let rows = trace_rows(canvas);
    if rows.is_empty() {
        return container(text("No events.").size(13)).padding(16).into();
    }
    let mut items = Vec::new();
    for (i, depth) in rows {
        items.push(view_event_row(canvas, i, depth));
    }
    column(items).spacing(2).into()
}

fn view_event_row<'a>(
    canvas: &'a ActivityStreamCanvas,
    event_idx: usize,
    indent: u32,
) -> Element<'a, ActivityStreamMessage> {
    let ev = &canvas.events[event_idx];
    let selected = canvas.selected_event == Some(event_idx);
    let time_s = format_time(ev, canvas.relative_time);
    let icon = icon_for_type(ev.activity_type);
    let summary = event_summary(ev);
    let agent = ev
        .agent_id
        .as_deref()
        .map(|a| format!("[{a}]"))
        .unwrap_or_default();

    let mut indent_px = 8 + indent.saturating_mul(12);

    let trace_toggle: Element<'a, ActivityStreamMessage> =
        if matches!(canvas.view_mode, ViewMode::Trace) {
            indent_px += 4;
            let id = ev.id.clone();
            let has_kids = canvas
                .events
                .iter()
                .any(|e| e.parent_id.as_deref() == Some(id.as_str()));
            if has_kids {
                let sym = if canvas.trace_collapsed.contains(&id) {
                    "▶"
                } else {
                    "▼"
                };
                button(text(sym).size(9))
                    .on_press(ActivityStreamMessage::ToggleTraceCollapsed(id))
                    .into()
            } else {
                Space::new().width(0).into()
            }
        } else {
            Space::new().width(0).into()
        };

    let bg = if selected {
        iced::Color::from_rgb(0.22, 0.24, 0.30)
    } else {
        iced::Color::from_rgb(0.15, 0.15, 0.18)
    };

    let row_inner = row![
        trace_toggle,
        Space::new().width(Length::Fixed(indent_px as f32)),
        text(time_s).size(10),
        Space::new().width(8),
        text(icon).size(11),
        Space::new().width(6),
        text(summary).size(12),
        Space::new().width(Length::Fill),
        text(agent).size(10),
    ]
    .spacing(4)
    .align_y(Alignment::Center);

    button(container(row_inner).padding(6))
        .on_press(ActivityStreamMessage::SelectEvent(Some(event_idx)))
        .padding(2)
        .style(move |_t, status| {
            let base_bg = bg;
            let pressed = iced::Color::from_rgb(0.28, 0.30, 0.36);
            let hover = iced::Color::from_rgb(0.18, 0.18, 0.22);
            button::Style {
                background: Some(iced::Background::Color(
                    if matches!(status, button::Status::Pressed) {
                        pressed
                    } else if matches!(status, button::Status::Hovered) {
                        hover
                    } else {
                        base_bg
                    },
                )),
                border: iced::Border {
                    radius: 6.0.into(),
                    width: 1.0,
                    color: iced::Color::from_rgb(0.25, 0.25, 0.28),
                },
                ..Default::default()
            }
        })
        .into()
}

fn view_detail<'a>(canvas: &'a ActivityStreamCanvas) -> Element<'a, ActivityStreamMessage> {
    let Some(i) = canvas.selected_event else {
        return container(text("Select an event for details.").size(12))
            .padding(8)
            .into();
    };
    let ev = match canvas.events.get(i) {
        Some(e) => e,
        None => {
            return container(text("Invalid selection.").size(12)).into();
        }
    };

    let id = ev.id.clone();
    let expanded = canvas.expanded_outputs.contains(&id);

    let header = row![
        text("Event details").size(13),
        Space::new().width(Length::Fill),
        text(format!("{:?}", ev.activity_type)).size(11),
    ]
    .spacing(8);

    let pretty =
        serde_json::to_string_pretty(&ev.content).unwrap_or_else(|_| ev.content.to_string());

    let body: Element<'a, ActivityStreamMessage> = if expanded {
        text(pretty).size(11).font(iced::Font::MONOSPACE).into()
    } else {
        text(truncate(&pretty, 400))
            .size(11)
            .font(iced::Font::MONOSPACE)
            .into()
    };

    let meta = format!(
        "id={}\nsession={:?}\ntask={:?}\nparent={:?}\nimportance={:?}",
        ev.id, ev.session_id, ev.task_id, ev.parent_id, ev.importance
    );

    let toggle_btn = button(
        text(if expanded {
            "Collapse output"
        } else {
            "Expand output"
        })
        .size(11),
    )
    .on_press(ActivityStreamMessage::ToggleOutputExpanded(id));

    let links = text("Related canvases (topology, decision tree): MCP routing planned.").size(10);

    container(
        column![
            header,
            Space::new().height(6),
            text(meta).size(10).font(iced::Font::MONOSPACE),
            Space::new().height(8),
            toggle_btn,
            Space::new().height(4),
            scrollable(container(body).padding(8)).height(Length::Fixed(220.0)),
            Space::new().height(8),
            links,
        ]
        .spacing(4),
    )
    .padding(10)
    .style(card_style())
    .into()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn layout_json_roundtrip() {
        let c = ActivityStreamCanvas::default();
        let v = c.serialize_layout();
        let c2 = ActivityStreamCanvas::deserialize_layout(v).unwrap();
        assert_eq!(c2.ws_url(), c.ws_url());
    }

    #[test]
    fn summary_file_edit() {
        let ev: ActivityEvent = serde_json::from_value(json!({
            "id": "1",
            "type": "file_edit",
            "timestamp": "2026-04-29T16:00:00Z",
            "content": {"path": "lib/a.ex", "additions": 15, "deletions": 3},
            "importance": "normal",
            "source": {"channel": "synapsix_feed"}
        }))
        .unwrap();
        assert!(event_summary(&ev).contains("lib/a.ex"));
    }
}
