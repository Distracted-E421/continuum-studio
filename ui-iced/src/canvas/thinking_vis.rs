//! Thinking visualization canvas — structured view of agent reasoning streams.
//!
//! Parses debounced text chunks into [`ThinkingNode`]s with category + confidence,
//! supports document / tree / timeline layouts and preference tagging.

use std::collections::{HashMap, HashSet};

use chrono::Utc;
use iced::widget::{button, column, container, row, scrollable, text, Row, Space};
use iced::{Alignment, Element, Length, Task};
use regex::Regex;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

use super::{Canvas, CanvasError};
use crate::scp::{CanvasType, ThinkingCategory, ThinkingChunk, ThinkingNode};

// ── public enums / preferences ─────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingViewMode {
    Document,
    Tree,
    Timeline,
}

impl ThinkingViewMode {
    fn label(self) -> &'static str {
        match self {
            ThinkingViewMode::Document => "Document",
            ThinkingViewMode::Tree => "Tree",
            ThinkingViewMode::Timeline => "Timeline",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingPreference {
    Good,
    Bad,
    Neutral,
}

impl ThinkingPreference {
    fn toggle_click(self, node_id: &str, prefs: &mut HashMap<String, ThinkingPreference>) {
        let entry = prefs.get(node_id).copied();
        let next = match (entry, self) {
            (Some(ThinkingPreference::Good), ThinkingPreference::Good) => {
                ThinkingPreference::Neutral
            }
            (Some(ThinkingPreference::Bad), ThinkingPreference::Bad) => ThinkingPreference::Neutral,
            (_, ThinkingPreference::Good) => ThinkingPreference::Good,
            (_, ThinkingPreference::Bad) => ThinkingPreference::Bad,
            (_, ThinkingPreference::Neutral) => ThinkingPreference::Neutral,
        };
        if next == ThinkingPreference::Neutral {
            prefs.remove(node_id);
        } else {
            prefs.insert(node_id.to_string(), next);
        }
    }
}

// ── parser ─────────────────────────────────────────────────────────────────

/// Pattern-based streaming parser: maps utterances to categories + confidence.
#[derive(Debug, Clone)]
pub struct ThinkingParser {
    patterns: Vec<(Regex, ThinkingCategory, f32)>,
    /// Tail of an incomplete line across chunk boundaries.
    buffer: String,
    pub debounce_ms: u64,
    id_counter: u64,
}

impl ThinkingParser {
    pub fn new() -> Self {
        let patterns: Vec<(&str, ThinkingCategory, f32)> = vec![
            (
                r"(?i)(^|\n|\.\s+)\s*i think\b",
                ThinkingCategory::Hypothesis,
                0.95,
            ),
            (r"(?i)\bi think\b", ThinkingCategory::Hypothesis, 0.75),
            (
                r"(?i)\bi(?:'| a)m guessing\b",
                ThinkingCategory::Hypothesis,
                0.85,
            ),
            (
                r"(?i)(^|\n|\.\s+)\s*let me\b",
                ThinkingCategory::Planning,
                0.95,
            ),
            (r"(?i)\blet me\b", ThinkingCategory::Planning, 0.8),
            (
                r"(?i)(^|\n|\.\s+)\s*looking at\b",
                ThinkingCategory::Evaluation,
                0.95,
            ),
            (r"(?i)\blooking at\b", ThinkingCategory::Evaluation, 0.78),
            (r"(?i)\bactually\b", ThinkingCategory::SelfCorrection, 0.88),
            (
                r"(?i)(^|\n|\.\s+)\s*i'll\b",
                ThinkingCategory::Decision,
                0.92,
            ),
            (r"(?i)\bi'll\b", ThinkingCategory::Decision, 0.82),
            (r"(?i)\bwe should\b", ThinkingCategory::Decision, 0.72),
            (r"(?i)\bmaybe\b", ThinkingCategory::Uncertainty, 0.65),
            (r"(?i)\bnot sure\b", ThinkingCategory::Uncertainty, 0.8),
        ];
        let patterns = patterns
            .into_iter()
            .filter_map(|(pat, cat, w)| Regex::new(pat).ok().map(|r| (r, cat, w)))
            .collect();
        Self {
            patterns,
            buffer: String::new(),
            debounce_ms: 100,
            id_counter: 0,
        }
    }

    fn next_id(&mut self) -> String {
        self.id_counter += 1;
        format!("t-{}-{}", Utc::now().timestamp_millis(), self.id_counter)
    }

    /// Append streamed text, emit [`ThinkingNode`]s for completed lines / sentences.
    pub fn parse_chunk(&mut self, chunk: &str) -> Vec<ThinkingNode> {
        self.buffer.push_str(chunk);
        let tmp = std::mem::take(&mut self.buffer);
        let ends_with_nl = tmp.ends_with('\n');
        let mut lines: Vec<&str> = tmp.lines().collect();
        let trailing = if ends_with_nl {
            String::new()
        } else if let Some(last) = lines.pop() {
            last.to_string()
        } else {
            String::new()
        };
        let mut out = Vec::new();
        for line in lines {
            let t = line.trim();
            if !t.is_empty() {
                out.extend(self.utterance_to_nodes(t));
            }
        }
        self.buffer = trailing;
        out
    }

    /// Flush any trailing buffered fragment as one utterance.
    pub fn flush_buffer(&mut self) -> Vec<ThinkingNode> {
        let t = std::mem::take(&mut self.buffer);
        let t = t.trim();
        if t.is_empty() {
            Vec::new()
        } else {
            self.utterance_to_nodes(t)
        }
    }

    fn utterance_to_nodes(&mut self, text: &str) -> Vec<ThinkingNode> {
        let segments = split_sentences(text);
        let mut nodes = Vec::new();
        for seg in segments {
            let seg = seg.trim();
            if seg.is_empty() {
                continue;
            }
            let (detected_cat, confidence) = self.detect_category(seg);
            let (category, confidence) = if confidence < 0.5 {
                (ThinkingCategory::Raw, confidence)
            } else {
                (detected_cat, confidence)
            };
            let ts = Utc::now().to_rfc3339();
            let chunk = ThinkingChunk {
                text: seg.to_string(),
                timestamp: Some(ts.clone()),
            };
            nodes.push(ThinkingNode {
                id: self.next_id(),
                category,
                content: seg.to_string(),
                confidence: Some(confidence as f64),
                timestamp: ts,
                source_chunks: Some(vec![chunk]),
                children: Vec::new(),
                linked_action: None,
            });
        }
        nodes
    }

    /// Best category + confidence in 0..=1 (before Raw fallback rule).
    pub fn detect_category(&self, text: &str) -> (ThinkingCategory, f32) {
        let mut best_cat = ThinkingCategory::Raw;
        let mut best_score = 0.35_f32;
        let len = text.len().max(1) as f32;
        for (re, cat, weight) in &self.patterns {
            if let Some(m) = re.find(text) {
                let ml = m.len() as f32;
                let coverage = (ml / len).min(1.0);
                let score = weight * (0.55 + 0.45 * coverage);
                if score > best_score {
                    best_score = score;
                    best_cat = *cat;
                }
            }
        }
        (best_cat, best_score.min(1.0))
    }
}

impl Default for ThinkingParser {
    fn default() -> Self {
        Self::new()
    }
}

fn split_sentences(text: &str) -> Vec<String> {
    if !text.contains('.') && !text.contains('!') && !text.contains('?') {
        return vec![text.to_string()];
    }
    let mut out = Vec::new();
    let mut start = 0usize;
    let bytes = text.as_bytes();
    let mut i = 0usize;
    while i < text.len() {
        let b = bytes[i];
        if b == b'.' || b == b'!' || b == b'?' {
            let slice = text[start..=i].trim();
            if !slice.is_empty() {
                out.push(slice.to_string());
            }
            start = i + 1;
            while start < text.len() && text.as_bytes()[start].is_ascii_whitespace() {
                start += 1;
            }
            i = start;
            continue;
        }
        i += 1;
    }
    if start < text.len() {
        let slice = text[start..].trim();
        if !slice.is_empty() {
            out.push(slice.to_string());
        }
    }
    if out.is_empty() {
        vec![text.to_string()]
    } else {
        out
    }
}

// ── canvas state ───────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub struct ThinkingVisCanvas {
    canvas_id: String,
    pub session_id: Option<String>,
    pub agent_id: Option<String>,
    /// Hierarchical reasoning tree (Planning roots, others nest under last root).
    pub roots: Vec<ThinkingNode>,
    pub raw_chunks: Vec<ThinkingChunk>,
    pub view_mode: ThinkingViewMode,
    pub selected_flat_index: Option<usize>,
    pub preferences: HashMap<String, ThinkingPreference>,
    pub collapsed_sections: HashSet<ThinkingCategory>,
    parser: ThinkingParser,
    debounce_buffer: String,
    debounce_gen: u64,
    /// Whether to subscribe to the SCP canvas WebSocket for live updates
    pub live_updates_enabled: bool,
    /// WebSocket subscription ID (generated on first subscribe)
    ws_subscription_id: Option<String>,
}

impl ThinkingVisCanvas {
    pub fn new(canvas_id: impl Into<String>) -> Self {
        Self {
            canvas_id: canvas_id.into(),
            session_id: None,
            agent_id: None,
            roots: Vec::new(),
            raw_chunks: Vec::new(),
            view_mode: ThinkingViewMode::Document,
            selected_flat_index: None,
            preferences: HashMap::new(),
            collapsed_sections: HashSet::new(),
            parser: ThinkingParser::new(),
            debounce_buffer: String::new(),
            debounce_gen: 0,
            live_updates_enabled: false,
            ws_subscription_id: None,
        }
    }

    /// Create with live updates enabled (subscribes to SCP canvas WebSocket)
    pub fn with_live_updates(canvas_id: impl Into<String>) -> Self {
        let mut canvas = Self::new(canvas_id);
        canvas.live_updates_enabled = true;
        canvas
    }

    /// Enable/disable live WebSocket updates
    pub fn set_live_updates(&mut self, enabled: bool) {
        self.live_updates_enabled = enabled;
        if !enabled {
            self.ws_subscription_id = None;
        }
    }

    /// Append a thinking chunk from an activity stream (`ActivityType::ThinkingChunk`).
    pub fn push_thinking_text(&mut self, fragment: &str, timestamp: Option<String>) {
        self.raw_chunks.push(ThinkingChunk {
            text: fragment.to_string(),
            timestamp,
        });
        self.debounce_buffer.push_str(fragment);
        self.debounce_gen += 1;
    }

    fn ingest_parsed_nodes(&mut self, batch: Vec<ThinkingNode>) {
        for node in batch {
            self.append_to_tree(node);
        }
    }

    fn append_to_tree(&mut self, node: ThinkingNode) {
        match node.category {
            ThinkingCategory::Planning => {
                self.roots.push(node);
            }
            _ => {
                if let Some(root) = self.roots.last_mut() {
                    root.children.push(node);
                } else {
                    self.roots.push(node);
                }
            }
        }
    }

    fn debounce_tick_task(gen: u64) -> Task<ThinkingVisMessage> {
        Task::perform(
            async move {
                tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
                gen
            },
            ThinkingVisMessage::DebounceTick,
        )
    }

    fn flush_debounce(&mut self, gen: u64) -> Task<ThinkingVisMessage> {
        if gen != self.debounce_gen {
            return Task::none();
        }
        let chunk = std::mem::take(&mut self.debounce_buffer);
        if chunk.is_empty() {
            let tail = self.parser.flush_buffer();
            if !tail.is_empty() {
                self.ingest_parsed_nodes(tail);
            }
            return Task::none();
        }
        let parsed = self.parser.parse_chunk(&chunk);
        self.ingest_parsed_nodes(parsed);
        let tail = self.parser.flush_buffer();
        self.ingest_parsed_nodes(tail);
        Task::none()
    }

    fn dfs_collect(&self) -> Vec<&ThinkingNode> {
        let mut v = Vec::new();
        for r in &self.roots {
            dfs_push(r, &mut v);
        }
        v
    }

    fn confidence_emoji(conf: Option<f64>) -> &'static str {
        let c = conf.unwrap_or(0.0);
        if c > 0.8 {
            "🟢"
        } else if c >= 0.5 {
            "🟡"
        } else {
            "🔴"
        }
    }

    fn category_title(cat: ThinkingCategory) -> &'static str {
        match cat {
            ThinkingCategory::Hypothesis => "Hypothesis",
            ThinkingCategory::Evaluation => "Evaluation",
            ThinkingCategory::Decision => "Decision",
            ThinkingCategory::Planning => "Planning",
            ThinkingCategory::Uncertainty => "Uncertainty",
            ThinkingCategory::SelfCorrection => "Self-correction",
            ThinkingCategory::Raw => "Raw",
        }
    }

    fn handle_ws_event(&mut self, ev: super::canvas_ws::CanvasWsEvent) {
        use super::canvas_ws::{CanvasType as WsCanvasType, CanvasWsEvent};

        match ev {
            CanvasWsEvent::Connected => {
                log::info!(
                    "[thinking_vis] WebSocket connected, canvas_id={}",
                    self.canvas_id
                );
            }
            CanvasWsEvent::Disconnected => {
                log::warn!(
                    "[thinking_vis] WebSocket disconnected, canvas_id={}",
                    self.canvas_id
                );
                self.ws_subscription_id = None;
            }
            CanvasWsEvent::Subscribed {
                subscription_id,
                canvas_id,
                canvas_type,
            } => {
                if canvas_id == self.canvas_id && canvas_type == WsCanvasType::ThinkingVis {
                    log::info!(
                        "[thinking_vis] Subscribed to canvas: sub_id={}",
                        subscription_id
                    );
                    self.ws_subscription_id = Some(subscription_id);
                }
            }
            CanvasWsEvent::Unsubscribed { subscription_id } => {
                if self.ws_subscription_id.as_deref() == Some(&subscription_id) {
                    log::info!("[thinking_vis] Unsubscribed: {}", subscription_id);
                    self.ws_subscription_id = None;
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
                if self.ws_subscription_id.as_deref() != Some(&subscription_id) {
                    return;
                }
                // Payload can contain "fragment" for streaming text or "roots" for full state
                if let Some(fragment) = payload.get("fragment").and_then(|v| v.as_str()) {
                    self.push_thinking_text(fragment, None);
                    log::debug!("[thinking_vis] Live fragment received, len={}", fragment.len());
                } else if let Some(roots_val) = payload.get("roots") {
                    if let Ok(roots) = serde_json::from_value::<Vec<ThinkingNode>>(roots_val.clone())
                    {
                        self.roots = roots;
                        log::debug!(
                            "[thinking_vis] Live roots update, count={}",
                            self.roots.len()
                        );
                    }
                }
            }
            CanvasWsEvent::ActionReceived { .. } => {
                // Action acknowledgment - no action needed
            }
            CanvasWsEvent::Error { code, message } => {
                log::warn!(
                    "[thinking_vis] WebSocket error: code={}, msg={}",
                    code,
                    message
                );
            }
            CanvasWsEvent::Heartbeat => {
                // Heartbeat - connection alive
            }
        }
    }

    /// JSON snapshot for workspace persistence (see SCP canvas sessions).
    pub fn persist_snapshot(&self) -> Value {
        json!({
            "canvas_id": self.canvas_id,
            "session_id": self.session_id,
            "agent_id": self.agent_id,
            "view_mode": self.view_mode,
            "selected_flat_index": self.selected_flat_index,
            "preferences": self.preferences,
            "roots": self.roots,
            "raw_chunks": self.raw_chunks,
            "collapsed_sections": self.collapsed_sections.iter().copied().collect::<Vec<_>>(),
        })
    }

    pub fn apply_persist_snapshot(&mut self, data: &Value) -> Result<(), String> {
        self.session_id = data
            .get("session_id")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        self.agent_id = data
            .get("agent_id")
            .and_then(|v| v.as_str())
            .map(|s| s.to_string());
        if let Some(idx) = data.get("selected_flat_index").and_then(|v| v.as_u64()) {
            self.selected_flat_index = Some(idx as usize);
        }
        if let Some(vm) = data
            .get("view_mode")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
        {
            self.view_mode = vm;
        }
        if let Some(prefs) = data
            .get("preferences")
            .and_then(|v| serde_json::from_value(v.clone()).ok())
        {
            self.preferences = prefs;
        }
        if let Some(roots) = data.get("roots").and_then(|v| v.as_array()) {
            self.roots.clear();
            for item in roots {
                if let Ok(node) = serde_json::from_value::<ThinkingNode>(item.clone()) {
                    self.roots.push(node);
                }
            }
        }
        Ok(())
    }
}

fn dfs_push<'a>(n: &'a ThinkingNode, out: &mut Vec<&'a ThinkingNode>) {
    out.push(n);
    for c in &n.children {
        dfs_push(c, out);
    }
}

#[derive(Debug, Clone)]
pub enum ThinkingVisMessage {
    SetViewMode(ThinkingViewMode),
    SelectFlatIndex(Option<usize>),
    SetPreference {
        node_id: String,
        pref: ThinkingPreference,
    },
    StreamFragment(String),
    DebounceTick(u64),
    ToggleCollapsed(ThinkingCategory),
    /// WebSocket event from the generic canvas WebSocket
    WsEvent(super::canvas_ws::CanvasWsEvent),
}

impl Canvas for ThinkingVisCanvas {
    type Message = ThinkingVisMessage;

    fn id(&self) -> &str {
        &self.canvas_id
    }

    fn canvas_type(&self) -> CanvasType {
        CanvasType::ThinkingVis
    }

    fn title(&self) -> String {
        format!("Thinking · {}", self.agent_id.as_deref().unwrap_or("agent"))
    }

    fn view<'a>(&'a self) -> Element<'a, Self::Message> {
        view_thinking_vis(self)
    }

    fn update(&mut self, message: Self::Message) -> Task<Self::Message> {
        match message {
            ThinkingVisMessage::SetViewMode(m) => {
                self.view_mode = m;
                Task::none()
            }
            ThinkingVisMessage::SelectFlatIndex(i) => {
                self.selected_flat_index = i;
                Task::none()
            }
            ThinkingVisMessage::SetPreference { node_id, pref } => {
                pref.toggle_click(&node_id, &mut self.preferences);
                Task::none()
            }
            ThinkingVisMessage::StreamFragment(s) => {
                self.push_thinking_text(&s, None);
                let g = self.debounce_gen;
                Self::debounce_tick_task(g)
            }
            ThinkingVisMessage::DebounceTick(g) => self.flush_debounce(g),
            ThinkingVisMessage::ToggleCollapsed(cat) => {
                if self.collapsed_sections.contains(&cat) {
                    self.collapsed_sections.remove(&cat);
                } else {
                    self.collapsed_sections.insert(cat);
                }
                Task::none()
            }
            ThinkingVisMessage::WsEvent(ev) => {
                self.handle_ws_event(ev);
                Task::none()
            }
        }
    }

    fn subscription(&self) -> iced::Subscription<Self::Message> {
        use iced::futures::StreamExt;
        if self.live_updates_enabled {
            iced::Subscription::run(|| {
                super::canvas_ws::canvas_ws_worker(None).map(ThinkingVisMessage::WsEvent)
            })
        } else {
            iced::Subscription::none()
        }
    }

    fn serialize_state(&self) -> Value {
        self.persist_snapshot()
    }

    fn deserialize_state(&mut self, data: &Value) -> Result<(), CanvasError> {
        self.apply_persist_snapshot(data)
            .map_err(CanvasError::Deserialize)
    }
}

/// Primary iced view (same layout as [`Canvas::view`]).
pub fn view_thinking_vis(canvas: &ThinkingVisCanvas) -> Element<'_, ThinkingVisMessage> {
    let mode_bar = row![
        mode_tab(canvas, ThinkingViewMode::Document),
        Space::new().width(6),
        mode_tab(canvas, ThinkingViewMode::Tree),
        Space::new().width(6),
        mode_tab(canvas, ThinkingViewMode::Timeline),
    ]
    .spacing(4)
    .align_y(Alignment::Center);

    let header = column![
        text("🧠 Thinking visualization").size(18),
        Space::new().height(4),
        mode_bar,
        Space::new().height(8),
    ];

    let flat = canvas.dfs_collect();
    let body: Element<'_, ThinkingVisMessage> = match canvas.view_mode {
        ThinkingViewMode::Document => view_document(canvas, &flat),
        ThinkingViewMode::Tree => view_tree(canvas),
        ThinkingViewMode::Timeline => view_timeline(canvas, &flat),
    };

    let detail = view_detail(canvas, &flat);

    let main = row![
        scrollable(column![body].spacing(8).padding(8))
            .width(Length::FillPortion(3))
            .height(Length::Fill),
        scrollable(detail)
            .width(Length::FillPortion(2))
            .height(Length::Fill),
    ]
    .spacing(12)
    .height(Length::Fill);

    container(column![header, main].spacing(4).padding(12))
        .height(Length::Fill)
        .into()
}

fn mode_tab<'a>(
    canvas: &'a ThinkingVisCanvas,
    mode: ThinkingViewMode,
) -> Element<'a, ThinkingVisMessage> {
    let label = mode.label();
    let active = canvas.view_mode == mode;
    let label_text = format!("{}{}", if active { "▪ " } else { "" }, label);
    if active {
        container(text(label_text).size(14)).padding([6, 10]).into()
    } else {
        button(text(label_text).size(14))
            .on_press(ThinkingVisMessage::SetViewMode(mode))
            .padding([6, 10])
            .into()
    }
}

fn view_document<'a>(
    canvas: &'a ThinkingVisCanvas,
    flat: &[&'a ThinkingNode],
) -> Element<'a, ThinkingVisMessage> {
    if flat.is_empty() {
        return text("Waiting for thinking chunks…").into();
    }
    let mut groups: Vec<(ThinkingCategory, Vec<&ThinkingNode>)> = Vec::new();
    for n in flat {
        if let Some(last) = groups.last_mut() {
            if last.0 == n.category {
                last.1.push(*n);
                continue;
            }
        }
        groups.push((n.category, vec![*n]));
    }

    let mut sections = column![].spacing(12);
    for (cat, nodes) in groups {
        let collapsed = canvas.collapsed_sections.contains(&cat);
        let title_row = row![button(text(format!(
            "{} {}",
            if collapsed { "▶" } else { "▼" },
            ThinkingVisCanvas::category_title(cat)
        )))
        .on_press(ThinkingVisMessage::ToggleCollapsed(cat)),];
        sections = sections.push(title_row);
        if !collapsed {
            let mut block = column![].spacing(8);
            for n in nodes {
                block = block.push(document_node_row(canvas, n, flat));
            }
            sections = sections.push(container(block).padding(iced::Padding::from([12u16, 16])));
        }
    }
    sections.into()
}

fn document_node_row<'a>(
    canvas: &'a ThinkingVisCanvas,
    n: &'a ThinkingNode,
    flat: &[&'a ThinkingNode],
) -> Element<'a, ThinkingVisMessage> {
    let idx = flat.iter().position(|x| x.id == n.id);
    let emoji = ThinkingVisCanvas::confidence_emoji(n.confidence);
    let pct = n
        .confidence
        .map(|c| format!(" {:.0}%", (c * 100.0).clamp(0.0, 100.0)))
        .unwrap_or_else(|| " —".to_string());
    let raw_note = if n.category == ThinkingCategory::Raw {
        " (uncertain)"
    } else {
        ""
    };
    let summary = text(format!("{}{}{}{}", emoji, pct, raw_note, ""));
    let body = text(format!("{}", n.content));
    let prefs = row![
        button(text("👍")).on_press(ThinkingVisMessage::SetPreference {
            node_id: n.id.clone(),
            pref: ThinkingPreference::Good,
        }),
        Space::new().width(6),
        button(text("👎")).on_press(ThinkingVisMessage::SetPreference {
            node_id: n.id.clone(),
            pref: ThinkingPreference::Bad,
        }),
    ];
    let mut col = column![
        row![summary, Space::new().width(8), prefs].spacing(4),
        Space::new().height(4),
        body,
    ]
    .spacing(2);
    if let Some(i) = idx {
        let sel = canvas.selected_flat_index == Some(i);
        let pick = button(text(if sel {
            "● Selected"
        } else {
            "○ Full context"
        }))
        .on_press(ThinkingVisMessage::SelectFlatIndex(Some(i)));
        col = col.push(Space::new().height(4)).push(pick);
    }
    container(col).padding(8).into()
}

fn view_tree<'a>(canvas: &'a ThinkingVisCanvas) -> Element<'a, ThinkingVisMessage> {
    if canvas.roots.is_empty() {
        return text("No structured nodes yet.").into();
    }
    let mut col = column![].spacing(6);
    let flat = canvas.dfs_collect();
    for root in &canvas.roots {
        col = col.push(view_tree_node(canvas, root, 0, &flat));
    }
    col.into()
}

fn view_tree_node<'a>(
    canvas: &'a ThinkingVisCanvas,
    node: &'a ThinkingNode,
    depth: usize,
    flat: &[&'a ThinkingNode],
) -> Element<'a, ThinkingVisMessage> {
    let pad = (depth as u16) * 12;
    let prefix = "│   ".repeat(depth).replace('│', " ");
    let branch = if depth == 0 { "▼ " } else { "├─ " };
    let cat = ThinkingVisCanvas::category_title(node.category);
    let line = text(format!(
        "{}{}{}: {}",
        prefix,
        branch,
        cat,
        truncate(&node.content, 72)
    ));
    let idx = flat.iter().position(|x| x.id == node.id);
    let mut header = row![
        Space::new().width(pad as f32),
        line,
        Space::new().width(8_f32)
    ]
    .spacing(4);
    if let Some(i) = idx {
        header = header
            .push(button(text("focus")).on_press(ThinkingVisMessage::SelectFlatIndex(Some(i))));
    }
    let mut col = column![container(header)];
    for c in &node.children {
        col = col.push(view_tree_node(canvas, c, depth + 1, flat));
    }
    col.into()
}

fn view_timeline<'a>(
    _canvas: &'a ThinkingVisCanvas,
    flat: &[&'a ThinkingNode],
) -> Element<'a, ThinkingVisMessage> {
    if flat.is_empty() {
        return text("Timeline appears after first nodes.").into();
    }
    let labels = Row::with_children(
        flat.iter()
            .map(|n| {
                container(text(truncate(
                    ThinkingVisCanvas::category_title(n.category),
                    10,
                )))
                .width(Length::Fill)
                .center_x(Length::Fill)
                .into()
            })
            .collect::<Vec<_>>(),
    )
    .spacing(4);

    let dots = Row::with_children(
        flat.iter()
            .enumerate()
            .map(|(i, _)| {
                button(text("●"))
                    .on_press(ThinkingVisMessage::SelectFlatIndex(Some(i)))
                    .into()
            })
            .collect::<Vec<_>>(),
    )
    .spacing(4);

    let times = Row::with_children(
        flat.iter()
            .map(|n| {
                let short = n
                    .timestamp
                    .chars()
                    .skip_while(|c| *c != 'T')
                    .take(10)
                    .collect::<String>()
                    .trim_start_matches('T')
                    .to_string();
                let show = if short.len() >= 8 {
                    short[..8.min(short.len())].to_string()
                } else {
                    truncate(&n.timestamp, 12)
                };
                container(text(show))
                    .width(Length::Fill)
                    .center_x(Length::Fill)
                    .into()
            })
            .collect::<Vec<_>>(),
    )
    .spacing(4);

    column![
        labels,
        Space::new().height(8),
        dots,
        Space::new().height(8),
        times,
    ]
    .spacing(4)
    .into()
}

fn view_detail<'a>(
    canvas: &'a ThinkingVisCanvas,
    flat: &[&'a ThinkingNode],
) -> Element<'a, ThinkingVisMessage> {
    let idx = match canvas.selected_flat_index {
        Some(i) if i < flat.len() => i,
        _ => {
            return container(text("Select a node for full context."))
                .padding(12)
                .into();
        }
    };
    let n = flat[idx];
    let pref = canvas
        .preferences
        .get(&n.id)
        .map(|p| format!("{:?}", p))
        .unwrap_or_else(|| "none".to_string());
    column![
        text("Full context").size(16),
        Space::new().height(8),
        text(format!("id: {}", n.id)),
        text(format!(
            "category: {}",
            ThinkingVisCanvas::category_title(n.category)
        )),
        text(format!("confidence: {:?}", n.confidence)),
        text(format!("preference: {}", pref)),
        Space::new().height(8),
        text(n.content.clone()),
        Space::new().height(12),
        button(text("Clear selection")).on_press(ThinkingVisMessage::SelectFlatIndex(None)),
    ]
    .spacing(4)
    .padding(12)
    .into()
}

fn truncate(s: &str, max: usize) -> String {
    if s.len() <= max {
        s.to_string()
    } else {
        format!("{}…", &s[..max.saturating_sub(1)])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parser_hypothesis() {
        let p = ThinkingParser::new();
        let (c, conf) = p.detect_category("I think the bug is in the encoder.");
        assert_eq!(c, ThinkingCategory::Hypothesis);
        assert!(conf > 0.5);
    }

    #[test]
    fn parser_planning() {
        let p = ThinkingParser::new();
        let (c, _) = p.detect_category("Let me check the file.");
        assert_eq!(c, ThinkingCategory::Planning);
    }

    #[test]
    fn parser_raw_low_confidence() {
        let mut p = ThinkingParser::new();
        let nodes = p.parse_chunk("foobar baz\n");
        assert_eq!(nodes.len(), 1);
        assert_eq!(nodes[0].category, ThinkingCategory::Raw);
    }

    #[test]
    fn canvas_streams_debounce_updates_roots() {
        let mut c = ThinkingVisCanvas::new("c1");
        let _stream_task = c.update(ThinkingVisMessage::StreamFragment(
            "Let me trace this.\n".into(),
        ));
        let _ = c.update(ThinkingVisMessage::DebounceTick(c.debounce_gen));
        assert!(!c.roots.is_empty());
    }
}
