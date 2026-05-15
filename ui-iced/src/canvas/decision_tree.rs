//! Decision tree canvas — NeSy verification flow (`scp/decision.ncl`).

use iced::border::Radius;
use iced::event::Event;
use iced::keyboard;
use iced::keyboard::key::{Key, Named};
use iced::mouse::{self, Button, Cursor, ScrollDelta};
use iced::touch;
use iced::widget::canvas;
use iced::widget::Action;
use iced::widget::{button, column, row, scrollable, text, Canvas as IcedCanvas};
use iced::{
    Alignment, Color, Element, Font, Length, Point, Rectangle, Size, Subscription, Task, Theme,
    Vector,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::time::{Duration, Instant};

use super::{Canvas, CanvasError};
use crate::scp::{
    CanvasType, DecisionNode, NesyRiskLevel, SmtData, VerificationResult, VerificationTier,
};

/// Zoom bounds per SCP.decision-tree-canvas.009.
pub const ZOOM_MIN: f32 = 0.25;
pub const ZOOM_MAX: f32 = 2.0;

const NODE_SIZE: Size = Size::new(220.0, 118.0);
const TREE_GAP_X: f32 = 36.0;
const TREE_GAP_Y: f32 = 56.0;
const DOUBLE_CLICK_MS: u64 = 420;

/// Disclosure tier for SMT / Nickel payloads shown beneath the graph.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DisclosureLevel {
    #[default]
    Summary,
    Code,
    Full,
}

/// Cached geometry + viewport for the decision forest.
#[derive(Debug, Clone)]
pub struct TreeLayout {
    pub positions: HashMap<String, Point>,
    pub viewport: Rectangle,
    pub zoom: f32,
    pub pan_offset: Vector,
}

impl Default for TreeLayout {
    fn default() -> Self {
        Self {
            positions: HashMap::new(),
            viewport: Rectangle::with_size(Size::new(800.0, 520.0)),
            zoom: 1.0,
            pan_offset: Vector::ZERO,
        }
    }
}

#[derive(Debug, Clone)]
pub enum DecisionGraphMsg {
    /// Absolute pan offset in canvas space after zoom (`screen = world * zoom + pan`).
    Pan(Vector),
    Zoom {
        cursor_in_canvas: Point,
        delta_steps: f32,
    },
    ClickNode(String),
    DoubleClickNode(String),
}

#[derive(Debug, Clone)]
pub enum DecisionTreeMsg {
    Graph(DecisionGraphMsg),
    ToggleDisclosure(DisclosureLevel),
    Keyboard(keyboard::Event),
    /// WebSocket event from the generic canvas WebSocket
    WsEvent(super::canvas_ws::CanvasWsEvent),
}

#[derive(Debug, Clone)]
pub struct DecisionTreeCanvas {
    pub canvas_id: String,
    pub session_id: Option<String>,
    /// Last nested tree payload (used for persistence round-trips).
    cached_root: Option<DecisionNode>,
    nodes: HashMap<String, DecisionNode>,
    edges: Vec<(String, String)>,
    pub layout: TreeLayout,
    pub selected_node: Option<String>,
    pub smt_disclosure: HashMap<String, DisclosureLevel>,
    expanded_parallel_groups: HashSet<String>,
    root_ids: Vec<String>,
    child_groups: HashMap<String, Vec<LayoutSlot>>,
    group_members: HashMap<String, Vec<String>>,
    navigation_order: Vec<String>,
    /// Whether to subscribe to the SCP canvas WebSocket for live updates
    pub live_updates_enabled: bool,
    /// WebSocket subscription ID (generated on first subscribe)
    ws_subscription_id: Option<String>,
}

#[derive(Debug, Clone)]
enum LayoutSlot {
    Single(String),
    ParallelGroup(Vec<String>),
}

#[derive(Default)]
struct GraphInteractionState {
    dragging: bool,
    drag_anchor_canvas: Point,
    drag_anchor_pan: Vector,
    last_click: Option<(Instant, Point, String)>,
}

struct DecisionGraphSnapshot {
    positions: HashMap<String, Point>,
    edges: Vec<(String, String)>,
    nodes_subset: HashMap<String, DecisionNode>,
    risks: HashMap<String, NesyRiskLevel>,
    short_lines: HashMap<String, String>,
    selected: Option<String>,
    zoom: f32,
    pan_offset: Vector,
}

struct DecisionGraphProgram {
    snapshot: DecisionGraphSnapshot,
}

fn risk_palette(level: NesyRiskLevel) -> Color {
    match level {
        NesyRiskLevel::Low => Color::from_rgb8(0x22, 0xc5, 0x5e),
        NesyRiskLevel::Medium => Color::from_rgb8(0xea, 0xb3, 0x08),
        NesyRiskLevel::High => Color::from_rgb8(0xf9, 0x73, 0x16),
        NesyRiskLevel::Critical => Color::from_rgb8(0xef, 0x44, 0x44),
    }
}

fn tier_short_name(tier: Option<VerificationTier>) -> String {
    match tier {
        Some(VerificationTier::Engram) => "Engram (T0)".into(),
        Some(VerificationTier::FastCheck) => "FastCheck (T1)".into(),
        Some(VerificationTier::ConstraintCheck) => "ConstraintCheck (T2)".into(),
        Some(VerificationTier::Smt) => "SMT (T3)".into(),
        None => "—".into(),
    }
}

fn result_symbol(result: Option<VerificationResult>) -> &'static str {
    match result {
        Some(VerificationResult::Approved) => "✓ Approved",
        Some(VerificationResult::Rejected) => "✗ Rejected",
        Some(VerificationResult::Conditional) => "⚠ Conditional",
        Some(VerificationResult::Timeout) => "⌛ Timeout",
        None => "—",
    }
}

fn nesy_risk_from_action(action: &Option<serde_json::Map<String, Value>>) -> NesyRiskLevel {
    let Some(map) = action else {
        return NesyRiskLevel::Medium;
    };
    let keys = [
        "nesy_risk_level",
        "risk_level",
        "risk",
        "nesy_risk",
        "tier_risk",
    ];
    for k in keys {
        if let Some(v) = map.get(k) {
            if let Some(s) = v.as_str() {
                if let Some(r) = parse_risk_str(s) {
                    return r;
                }
            }
            if let Some(o) = v.as_object() {
                let lv = o
                    .get("level")
                    .and_then(|x| x.as_str())
                    .or_else(|| o.get("tier").and_then(|x| x.as_str()));
                if let Some(s) = lv {
                    if let Some(r) = parse_risk_str(s) {
                        return r;
                    }
                }
            }
        }
    }
    NesyRiskLevel::Medium
}

fn parse_risk_str(s: &str) -> Option<NesyRiskLevel> {
    match s.to_ascii_lowercase().as_str() {
        "low" => Some(NesyRiskLevel::Low),
        "medium" | "normal" => Some(NesyRiskLevel::Medium),
        "high" => Some(NesyRiskLevel::High),
        "critical" => Some(NesyRiskLevel::Critical),
        _ => None,
    }
}

fn parallel_signature(node: &DecisionNode) -> String {
    let tier = tier_short_name(node.verification_tier);
    let tool = node
        .action
        .as_ref()
        .and_then(|m| {
            m.get("tool")
                .or_else(|| m.get("kind"))
                .or_else(|| m.get("type"))
                .and_then(|v| v.as_str())
        })
        .unwrap_or("");
    format!("{}|{}", tier, tool)
}

fn action_headline(node: &DecisionNode) -> String {
    if node.id.starts_with("__parallel__") {
        let count = node
            .action
            .as_ref()
            .and_then(|m| m.get("parallel_count"))
            .and_then(|v| v.as_u64())
            .unwrap_or(0);
        let sig = node
            .action
            .as_ref()
            .and_then(|m| m.get("parallel_signature"))
            .and_then(|v| v.as_str())
            .unwrap_or("parallel");
        let short = sig.split('|').last().unwrap_or(sig);
        return format!("Parallel ×{} [{}]", count, short);
    }
    node.action
        .as_ref()
        .and_then(|m| {
            m.get("label")
                .or_else(|| m.get("description"))
                .or_else(|| m.get("tool"))
                .and_then(|v| v.as_str())
        })
        .map(|s| {
            let t = s.trim();
            if t.len() > 42 {
                format!("{}…", &t[..42])
            } else {
                t.to_string()
            }
        })
        .unwrap_or_else(|| format!("node {}", &node.id[..node.id.len().min(12)]))
}

fn flatten_tree(
    node: &DecisionNode,
    parent: Option<&str>,
    nodes: &mut HashMap<String, DecisionNode>,
    edges: &mut Vec<(String, String)>,
) {
    let id = node.id.clone();
    let mut stored = node.clone();
    stored.children.clear();
    nodes.insert(id.clone(), stored);
    if let Some(p) = parent {
        edges.push((p.to_string(), id.clone()));
    }
    for ch in &node.children {
        flatten_tree(ch, Some(&id), nodes, edges);
    }
}

fn edges_to_children(edges: &[(String, String)]) -> HashMap<String, Vec<String>> {
    let mut m: HashMap<String, Vec<String>> = HashMap::new();
    for (p, c) in edges {
        m.entry(p.clone()).or_default().push(c.clone());
    }
    for v in m.values_mut() {
        v.sort();
        v.dedup();
    }
    m
}

fn roots_from_edges(
    edges: &[(String, String)],
    nodes: &HashMap<String, DecisionNode>,
) -> Vec<String> {
    let mut child_set: HashSet<String> = HashSet::new();
    for (_, c) in edges {
        child_set.insert(c.clone());
    }
    nodes
        .keys()
        .filter(|id| !child_set.contains(*id))
        .cloned()
        .collect()
}

fn partition_parallel(
    children: &[String],
    nodes: &HashMap<String, DecisionNode>,
) -> Vec<LayoutSlot> {
    if children.is_empty() {
        return Vec::new();
    }
    let mut slots = Vec::new();
    let mut i = 0;
    while i < children.len() {
        let id = &children[i];
        let sig = nodes.get(id).map(parallel_signature).unwrap_or_default();
        let mut j = i + 1;
        while j < children.len() {
            let sj = nodes
                .get(&children[j])
                .map(parallel_signature)
                .unwrap_or_default();
            if sj != sig {
                break;
            }
            j += 1;
        }
        if j - i >= 2 {
            slots.push(LayoutSlot::ParallelGroup(children[i..j].to_vec()));
        } else {
            slots.push(LayoutSlot::Single(children[i].clone()));
        }
        i = j;
    }
    slots
}

fn apply_parallel_slots(
    roots: &[String],
    cmap_orig: &HashMap<String, Vec<String>>,
    nodes: &mut HashMap<String, DecisionNode>,
    edges: &mut Vec<(String, String)>,
    groups: &mut HashMap<String, Vec<String>>,
    slots_store: &mut HashMap<String, Vec<LayoutSlot>>,
) {
    edges.clear();
    groups.clear();
    slots_store.clear();
    let mut stack: Vec<String> = roots.to_vec();
    while let Some(pid) = stack.pop() {
        let raw_children = cmap_orig.get(&pid).cloned().unwrap_or_default();
        let slots = partition_parallel(&raw_children, nodes);
        slots_store.insert(pid.clone(), slots.clone());

        for slot in slots {
            match slot {
                LayoutSlot::Single(cid) => {
                    edges.push((pid.clone(), cid.clone()));
                    if cmap_orig.contains_key(&cid) {
                        stack.push(cid);
                    }
                }
                LayoutSlot::ParallelGroup(member_ids) => {
                    let gid = format!("__parallel__{}__{}", pid, member_ids[0]);
                    let sig_line = nodes
                        .get(&member_ids[0])
                        .map(parallel_signature)
                        .unwrap_or_default();
                    let meta = json!({
                        "parallel_group": true,
                        "parallel_members": member_ids.clone(),
                        "parallel_signature": sig_line,
                        "parallel_count": member_ids.len(),
                    });
                    let synthetic = DecisionNode {
                        id: gid.clone(),
                        action: meta.as_object().cloned(),
                        verification_tier: nodes
                            .get(&member_ids[0])
                            .and_then(|n| n.verification_tier),
                        result: None,
                        timing_ms: None,
                        timestamp: None,
                        smt_data: None,
                        children: vec![],
                    };
                    nodes.insert(gid.clone(), synthetic);
                    edges.push((pid.clone(), gid.clone()));
                    groups.insert(gid.clone(), member_ids.clone());
                    for mid in &member_ids {
                        edges.push((gid.clone(), mid.clone()));
                        if cmap_orig.contains_key(mid) {
                            stack.push(mid.clone());
                        }
                    }
                }
            }
        }
    }
}

fn dfs_order(root: &str, cmap: &HashMap<String, Vec<String>>, out: &mut Vec<String>) {
    out.push(root.to_string());
    if let Some(ch) = cmap.get(root) {
        for c in ch {
            dfs_order(c, cmap, out);
        }
    }
}

fn visible_children_for_layout(
    parent: &str,
    cmap: &HashMap<String, Vec<String>>,
    expanded: &HashSet<String>,
    slots_store: &HashMap<String, Vec<LayoutSlot>>,
) -> Vec<String> {
    let Some(slots) = slots_store.get(parent) else {
        return cmap.get(parent).cloned().unwrap_or_default();
    };
    let mut out = Vec::new();
    for slot in slots {
        match slot {
            LayoutSlot::Single(id) => out.push(id.clone()),
            LayoutSlot::ParallelGroup(ids) => {
                let gid = format!("__parallel__{}__{}", parent, ids[0]);
                if expanded.contains(&gid) {
                    out.extend(ids.iter().cloned());
                } else {
                    out.push(gid);
                }
            }
        }
    }
    out
}

fn layout_positions_for_roots(
    roots: &[String],
    cmap: &HashMap<String, Vec<String>>,
    expanded: &HashSet<String>,
    slots_store: &HashMap<String, Vec<LayoutSlot>>,
    positions: &mut HashMap<String, Point>,
) {
    positions.clear();
    let mut leaf_cursor = 0.0_f32;

    fn dfs(
        id: &str,
        depth: usize,
        cmap: &HashMap<String, Vec<String>>,
        expanded: &HashSet<String>,
        slots_store: &HashMap<String, Vec<LayoutSlot>>,
        positions: &mut HashMap<String, Point>,
        leaf_cursor: &mut f32,
    ) -> (f32, f32) {
        let children_visible = visible_children_for_layout(id, cmap, expanded, slots_store);
        if children_visible.is_empty() {
            let x = *leaf_cursor;
            *leaf_cursor += NODE_SIZE.width + TREE_GAP_X;
            let y = depth as f32 * (NODE_SIZE.height + TREE_GAP_Y);
            positions.insert(id.to_string(), Point::new(x, y));
            return (x, x + NODE_SIZE.width);
        }

        let mut intervals = Vec::new();
        for cid in &children_visible {
            intervals.push(dfs(
                cid,
                depth + 1,
                cmap,
                expanded,
                slots_store,
                positions,
                leaf_cursor,
            ));
        }
        let min_l = intervals
            .iter()
            .map(|(l, _)| *l)
            .fold(f32::INFINITY, f32::min);
        let max_r = intervals
            .iter()
            .map(|(_, r)| *r)
            .fold(f32::NEG_INFINITY, f32::max);
        let cx = (min_l + max_r) * 0.5 - NODE_SIZE.width * 0.5;
        let y = depth as f32 * (NODE_SIZE.height + TREE_GAP_Y);
        positions.insert(id.to_string(), Point::new(cx, y));
        (min_l, max_r)
    }

    for r in roots {
        dfs(
            r,
            0,
            cmap,
            expanded,
            slots_store,
            positions,
            &mut leaf_cursor,
        );
    }
}

fn screen_to_world(screen: Point, pan: Vector, zoom: f32) -> Point {
    Point::new((screen.x - pan.x) / zoom, (screen.y - pan.y) / zoom)
}

fn world_to_screen(world: Point, pan: Vector, zoom: f32) -> Point {
    Point::new(world.x * zoom + pan.x, world.y * zoom + pan.y)
}

fn hit_node(cursor_canvas: Point, snapshot: &DecisionGraphSnapshot) -> Option<String> {
    let world = screen_to_world(cursor_canvas, snapshot.pan_offset, snapshot.zoom);
    let mut hit: Option<(String, f32)> = None;
    for (id, pos) in &snapshot.positions {
        let rect = Rectangle::new(*pos, NODE_SIZE);
        if rect.contains(world) {
            let dist = pos.distance(world);
            match hit {
                None => hit = Some((id.clone(), dist)),
                Some((_, best)) if dist < best => hit = Some((id.clone(), dist)),
                _ => {}
            }
        }
    }
    hit.map(|(id, _)| id)
}

fn newest_node_id(nodes: &HashMap<String, DecisionNode>) -> Option<String> {
    nodes
        .iter()
        .filter(|(_, n)| n.timestamp.is_some())
        .max_by(|a, b| {
            let ta = a.1.timestamp.as_deref().unwrap_or("");
            let tb = b.1.timestamp.as_deref().unwrap_or("");
            ta.cmp(tb)
        })
        .map(|(id, _)| id.clone())
        .or_else(|| nodes.keys().max().cloned())
}

fn palette_colors(theme: &Theme) -> (Color, Color) {
    let palette = theme.palette();
    (palette.background, palette.text)
}

fn enrich_snapshot(snap: &mut DecisionGraphSnapshot, all: &HashMap<String, DecisionNode>) {
    snap.nodes_subset.clear();
    snap.risks.clear();
    snap.short_lines.clear();
    for id in snap.positions.keys() {
        if let Some(n) = all.get(id) {
            snap.nodes_subset.insert(id.clone(), n.clone());
            snap.risks
                .insert(id.clone(), nesy_risk_from_action(&n.action));
            snap.short_lines.insert(id.clone(), action_headline(n));
        }
    }
}

fn disclosure_level_from_str(s: &str) -> Option<DisclosureLevel> {
    match s.to_ascii_lowercase().as_str() {
        "summary" => Some(DisclosureLevel::Summary),
        "code" => Some(DisclosureLevel::Code),
        "full" | "full_smt" | "smt" => Some(DisclosureLevel::Full),
        _ => None,
    }
}

fn disclosure_level_to_str(d: DisclosureLevel) -> &'static str {
    match d {
        DisclosureLevel::Summary => "summary",
        DisclosureLevel::Code => "code",
        DisclosureLevel::Full => "full",
    }
}

impl canvas::Program<DecisionTreeMsg, Theme> for DecisionGraphProgram {
    type State = GraphInteractionState;

    fn update(
        &self,
        state: &mut GraphInteractionState,
        event: &Event,
        bounds: Rectangle,
        cursor: Cursor,
    ) -> Option<Action<DecisionTreeMsg>> {
        let canvas_pos = cursor.position_in(bounds);

        match event {
            Event::Mouse(mouse::Event::WheelScrolled { delta }) => {
                let Some(p) = canvas_pos else {
                    return None;
                };
                let dy = match delta {
                    ScrollDelta::Lines { y, .. } => *y,
                    ScrollDelta::Pixels { y, .. } => y / 80.0,
                };
                if dy.abs() < f32::EPSILON {
                    return None;
                }
                let zdelta = -dy * 0.12;
                Some(
                    Action::publish(DecisionTreeMsg::Graph(DecisionGraphMsg::Zoom {
                        cursor_in_canvas: p,
                        delta_steps: zdelta,
                    }))
                    .and_capture(),
                )
            }
            Event::Mouse(mouse::Event::ButtonPressed(Button::Left)) => {
                let Some(p) = canvas_pos else {
                    return None;
                };
                state.dragging = true;
                state.drag_anchor_canvas = p;
                state.drag_anchor_pan = self.snapshot.pan_offset;

                if let Some(id) = hit_node(p, &self.snapshot) {
                    let now = Instant::now();
                    let double = state.last_click.as_ref().is_some_and(|(t, prev_p, prev)| {
                        prev == &id
                            && prev_p.distance(p) < 6.0
                            && now.duration_since(*t) < Duration::from_millis(DOUBLE_CLICK_MS)
                    });
                    state.last_click = Some((now, p, id.clone()));

                    if double {
                        Some(
                            Action::publish(DecisionTreeMsg::Graph(
                                DecisionGraphMsg::DoubleClickNode(id),
                            ))
                            .and_capture(),
                        )
                    } else {
                        Some(
                            Action::publish(DecisionTreeMsg::Graph(DecisionGraphMsg::ClickNode(
                                id,
                            )))
                            .and_capture(),
                        )
                    }
                } else {
                    state.last_click = None;
                    Some(Action::capture())
                }
            }
            Event::Mouse(mouse::Event::CursorMoved { .. }) => {
                if state.dragging {
                    let Some(p) = canvas_pos else {
                        return None;
                    };
                    let new_pan = state.drag_anchor_pan + (p - state.drag_anchor_canvas);
                    Some(
                        Action::publish(DecisionTreeMsg::Graph(DecisionGraphMsg::Pan(new_pan)))
                            .and_capture(),
                    )
                } else {
                    None
                }
            }
            Event::Mouse(mouse::Event::ButtonReleased(Button::Left)) => {
                state.dragging = false;
                None
            }
            Event::Touch(touch::Event::FingerPressed { .. }) => {
                let Some(p) = canvas_pos else {
                    return None;
                };
                state.dragging = true;
                state.drag_anchor_canvas = p;
                state.drag_anchor_pan = self.snapshot.pan_offset;
                None
            }
            Event::Touch(touch::Event::FingerMoved { .. }) => {
                if state.dragging {
                    let Some(p) = canvas_pos else {
                        return None;
                    };
                    let new_pan = state.drag_anchor_pan + (p - state.drag_anchor_canvas);
                    Some(
                        Action::publish(DecisionTreeMsg::Graph(DecisionGraphMsg::Pan(new_pan)))
                            .and_capture(),
                    )
                } else {
                    None
                }
            }
            Event::Touch(touch::Event::FingerLifted { .. }) => {
                state.dragging = false;
                None
            }
            _ => None,
        }
    }

    fn draw(
        &self,
        _state: &GraphInteractionState,
        renderer: &iced::Renderer,
        theme: &Theme,
        bounds: Rectangle,
        _cursor: Cursor,
    ) -> Vec<canvas::Geometry> {
        let (bg, fg) = palette_colors(theme);
        let mut frame = canvas::Frame::new(renderer, bounds.size());
        frame.fill_rectangle(
            Point::ORIGIN,
            bounds.size(),
            canvas::Fill::from(bg.scale_alpha(0.94)),
        );

        let snap = &self.snapshot;
        let zoom = snap.zoom;
        let pan = snap.pan_offset;

        for (parent, child) in &snap.edges {
            let Some(p1) = snap.positions.get(parent) else {
                continue;
            };
            let Some(p2) = snap.positions.get(child) else {
                continue;
            };
            let start = world_to_screen(
                Point::new(p1.x + NODE_SIZE.width * 0.5, p1.y + NODE_SIZE.height),
                pan,
                zoom,
            );
            let end = world_to_screen(Point::new(p2.x + NODE_SIZE.width * 0.5, p2.y), pan, zoom);
            let mid_y = (start.y + end.y) * 0.5;
            let c1 = Point::new(start.x, mid_y);
            let c2 = Point::new(end.x, mid_y);
            let path = canvas::Path::new(|b| {
                b.move_to(start);
                b.bezier_curve_to(c1, c2, end);
            });
            frame.stroke(
                &path,
                canvas::Stroke::default()
                    .with_width(1.6)
                    .with_color(fg.scale_alpha(0.35)),
            );

            let arrow = canvas::Path::new(|b| {
                let tip = end;
                let left = Point::new(tip.x - 7.0, tip.y - 9.0);
                let right = Point::new(tip.x + 7.0, tip.y - 9.0);
                b.move_to(tip);
                b.line_to(left);
                b.move_to(tip);
                b.line_to(right);
            });
            frame.stroke(
                &arrow,
                canvas::Stroke::default()
                    .with_width(1.4)
                    .with_color(fg.scale_alpha(0.45)),
            );
        }

        for (id, world_top_left) in &snap.positions {
            let risk = snap.risks.get(id).copied().unwrap_or(NesyRiskLevel::Medium);
            let accent = risk_palette(risk);
            let screen_tl = world_to_screen(*world_top_left, pan, zoom);
            let size = Size::new(NODE_SIZE.width * zoom, NODE_SIZE.height * zoom);
            if screen_tl.x + size.width < 0.0
                || screen_tl.y + size.height < 0.0
                || screen_tl.x > bounds.width
                || screen_tl.y > bounds.height
            {
                continue;
            }

            let radius = Radius::new(7.0 * zoom.max(0.4));
            let fill_color = if snap.selected.as_ref() == Some(id) {
                accent.scale_alpha(0.35)
            } else {
                accent.scale_alpha(0.22)
            };
            frame.fill(
                &canvas::Path::rounded_rectangle(screen_tl, size, radius),
                canvas::Fill::from(fill_color),
            );
            frame.stroke(
                &canvas::Path::rounded_rectangle(screen_tl, size, radius),
                canvas::Stroke::default()
                    .with_width(if snap.selected.as_ref() == Some(id) {
                        2.6
                    } else {
                        1.3
                    })
                    .with_color(accent.scale_alpha(0.95)),
            );

            let header = format!(
                "📝 {}",
                snap.short_lines.get(id).cloned().unwrap_or_default()
            );
            frame.fill_text(canvas::Text {
                content: header,
                position: Point::new(screen_tl.x + 8.0 * zoom, screen_tl.y + 10.0 * zoom),
                size: iced::Pixels(13.0 * zoom.clamp(0.55, 2.0)),
                color: fg,
                max_width: size.width - 16.0 * zoom,
                ..canvas::Text::default()
            });

            let node = snap.nodes_subset.get(id);
            let tier_line = format!(
                "Tier: {}",
                tier_short_name(node.and_then(|n| n.verification_tier))
            );
            frame.fill_text(canvas::Text {
                content: tier_line,
                position: Point::new(screen_tl.x + 8.0 * zoom, screen_tl.y + 34.0 * zoom),
                size: iced::Pixels(11.0 * zoom.clamp(0.55, 2.0)),
                color: fg.scale_alpha(0.82),
                max_width: size.width - 16.0 * zoom,
                ..canvas::Text::default()
            });

            let result_line = format!("Result: {}", result_symbol(node.and_then(|n| n.result)));
            frame.fill_text(canvas::Text {
                content: result_line,
                position: Point::new(screen_tl.x + 8.0 * zoom, screen_tl.y + 52.0 * zoom),
                size: iced::Pixels(11.0 * zoom.clamp(0.55, 2.0)),
                color: fg.scale_alpha(0.82),
                max_width: size.width - 16.0 * zoom,
                ..canvas::Text::default()
            });

            let time_line = node
                .and_then(|n| n.timing_ms)
                .map(|ms| format!("Time: {:.0}ms", ms))
                .unwrap_or_else(|| "Time: —".into());
            frame.fill_text(canvas::Text {
                content: time_line,
                position: Point::new(screen_tl.x + 8.0 * zoom, screen_tl.y + 70.0 * zoom),
                size: iced::Pixels(11.0 * zoom.clamp(0.55, 2.0)),
                color: fg.scale_alpha(0.78),
                max_width: size.width - 16.0 * zoom,
                ..canvas::Text::default()
            });

            let risk_label = match risk {
                NesyRiskLevel::Low => "Risk: 🟢 Low",
                NesyRiskLevel::Medium => "Risk: 🟡 Medium",
                NesyRiskLevel::High => "Risk: 🟠 High",
                NesyRiskLevel::Critical => "Risk: 🔴 Critical",
            };
            frame.fill_text(canvas::Text {
                content: risk_label.into(),
                position: Point::new(screen_tl.x + 8.0 * zoom, screen_tl.y + 88.0 * zoom),
                size: iced::Pixels(11.0 * zoom.clamp(0.55, 2.0)),
                color: fg.scale_alpha(0.78),
                max_width: size.width - 16.0 * zoom,
                ..canvas::Text::default()
            });
        }

        vec![frame.into_geometry()]
    }

    fn mouse_interaction(
        &self,
        _state: &GraphInteractionState,
        bounds: Rectangle,
        cursor: Cursor,
    ) -> mouse::Interaction {
        match cursor.position_in(bounds) {
            Some(p) if hit_node(p, &self.snapshot).is_some() => mouse::Interaction::Pointer,
            Some(_) => mouse::Interaction::Grab,
            None => mouse::Interaction::default(),
        }
    }
}

impl DecisionTreeCanvas {
    pub fn new(canvas_id: impl Into<String>, session_id: Option<String>) -> Self {
        Self {
            canvas_id: canvas_id.into(),
            session_id,
            cached_root: None,
            nodes: HashMap::new(),
            edges: Vec::new(),
            layout: TreeLayout::default(),
            selected_node: None,
            smt_disclosure: HashMap::new(),
            expanded_parallel_groups: HashSet::new(),
            root_ids: Vec::new(),
            child_groups: HashMap::new(),
            group_members: HashMap::new(),
            navigation_order: Vec::new(),
            live_updates_enabled: false,
            ws_subscription_id: None,
        }
    }

    /// Create with live updates enabled (subscribes to SCP canvas WebSocket)
    pub fn with_live_updates(canvas_id: impl Into<String>, session_id: Option<String>) -> Self {
        let mut canvas = Self::new(canvas_id, session_id);
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

    pub fn load_tree(&mut self, root: DecisionNode, viewport_size: Size) {
        self.cached_root = Some(root.clone());
        self.nodes.clear();
        self.edges.clear();
        flatten_tree(&root, None, &mut self.nodes, &mut self.edges);
        let cmap_orig = edges_to_children(&self.edges);
        self.root_ids = roots_from_edges(&self.edges, &self.nodes);
        apply_parallel_slots(
            &self.root_ids,
            &cmap_orig,
            &mut self.nodes,
            &mut self.edges,
            &mut self.group_members,
            &mut self.child_groups,
        );
        let cmap = edges_to_children(&self.edges);
        layout_positions_for_roots(
            &self.root_ids,
            &cmap,
            &self.expanded_parallel_groups,
            &self.child_groups,
            &mut self.layout.positions,
        );
        self.navigation_order.clear();
        for r in &self.root_ids {
            dfs_order(r, &cmap, &mut self.navigation_order);
        }
        self.layout.viewport = Rectangle::with_size(viewport_size);
        self.center_on_newest(viewport_size);
    }

    pub fn center_on_newest(&mut self, viewport: Size) {
        let Some(target) = newest_node_id(&self.nodes) else {
            return;
        };
        self.center_on_node(&target, viewport);
    }

    pub fn center_on_node(&mut self, id: &str, viewport: Size) {
        let Some(pos) = self.layout.positions.get(id).copied() else {
            return;
        };
        let cx = pos.x + NODE_SIZE.width * 0.5;
        let cy = pos.y + NODE_SIZE.height * 0.5;
        let z = self.layout.zoom.clamp(ZOOM_MIN, ZOOM_MAX);
        self.layout.zoom = z;
        self.layout.pan_offset = Vector::new(
            viewport.width * 0.5 - cx * z,
            viewport.height * 0.5 - cy * z,
        );
    }

    pub(crate) fn apply_zoom(&mut self, cursor_canvas: Point, delta_steps: f32) {
        let old = self.layout.zoom.clamp(ZOOM_MIN, ZOOM_MAX);
        let factor = (1.0 + delta_steps).clamp(0.85, 1.18);
        let new_z = (old * factor).clamp(ZOOM_MIN, ZOOM_MAX);
        let world = screen_to_world(cursor_canvas, self.layout.pan_offset, old);
        self.layout.zoom = new_z;
        self.layout.pan_offset = Vector::new(
            cursor_canvas.x - world.x * new_z,
            cursor_canvas.y - world.y * new_z,
        );
    }

    fn graph_snapshot(&self, bounds_size: Size) -> DecisionGraphSnapshot {
        let _ = bounds_size;
        let cmap = edges_to_children(&self.edges);
        let mut positions = HashMap::new();
        layout_positions_for_roots(
            &self.root_ids,
            &cmap,
            &self.expanded_parallel_groups,
            &self.child_groups,
            &mut positions,
        );
        let mut visible_edges: Vec<(String, String)> = Vec::new();
        let mut stack: Vec<String> = self.root_ids.clone();
        let mut seen: HashSet<String> = HashSet::new();
        while let Some(pid) = stack.pop() {
            if !seen.insert(pid.clone()) {
                continue;
            }
            let ch = visible_children_for_layout(
                &pid,
                &cmap,
                &self.expanded_parallel_groups,
                &self.child_groups,
            );
            for c in ch {
                visible_edges.push((pid.clone(), c.clone()));
                stack.push(c);
            }
        }

        let mut snap = DecisionGraphSnapshot {
            positions,
            edges: visible_edges,
            nodes_subset: HashMap::new(),
            risks: HashMap::new(),
            short_lines: HashMap::new(),
            selected: self.selected_node.clone(),
            zoom: self.layout.zoom,
            pan_offset: self.layout.pan_offset,
        };
        enrich_snapshot(&mut snap, &self.nodes);
        snap
    }

    fn toggle_group_expand(&mut self, gid: &str) {
        if self.expanded_parallel_groups.contains(gid) {
            self.expanded_parallel_groups.remove(gid);
        } else {
            self.expanded_parallel_groups.insert(gid.to_string());
        }
    }

    fn relayout_visible(&mut self) {
        let cmap = edges_to_children(&self.edges);
        layout_positions_for_roots(
            &self.root_ids,
            &cmap,
            &self.expanded_parallel_groups,
            &self.child_groups,
            &mut self.layout.positions,
        );
    }

    fn navigate_selection(&mut self, delta: isize) {
        if self.navigation_order.is_empty() {
            return;
        }
        let idx = self
            .selected_node
            .as_ref()
            .and_then(|id| self.navigation_order.iter().position(|x| x == id))
            .unwrap_or(0);
        let len = self.navigation_order.len() as isize;
        let next = (idx as isize + delta).rem_euclid(len) as usize;
        let id = self.navigation_order[next].clone();
        self.selected_node = Some(id.clone());
        self.smt_disclosure
            .entry(id)
            .or_insert(DisclosureLevel::Summary);
    }

    fn detail_body_for_selected(&self, level: DisclosureLevel) -> String {
        let Some(id) = self.selected_node.as_ref() else {
            return "Select a node on the graph.".into();
        };
        let Some(node) = self.nodes.get(id) else {
            return "Missing node data.".into();
        };

        let mut lines = Vec::new();
        lines.push(format!("Tier: {}", tier_short_name(node.verification_tier)));
        lines.push(format!("Result: {}", result_symbol(node.result)));
        if let Some(ms) = node.timing_ms {
            lines.push(format!("Time: {:.0}ms", ms));
        }
        let risk = nesy_risk_from_action(&node.action);
        lines.push(match risk {
            NesyRiskLevel::Low => "Risk: 🟢 Low".into(),
            NesyRiskLevel::Medium => "Risk: 🟡 Medium".into(),
            NesyRiskLevel::High => "Risk: 🟠 High".into(),
            NesyRiskLevel::Critical => "Risk: 🔴 Critical".into(),
        });

        let smt = node.smt_data.clone().unwrap_or(SmtData {
            summary: "No SMT summary supplied.".into(),
            constraint_code: None,
            full_script: None,
            model: None,
        });

        match level {
            DisclosureLevel::Summary => {
                lines.push(format!("Summary: {}", smt.summary));
                if let Some(m) = smt.model {
                    lines.push(format!("Model: {}", m));
                }
            }
            DisclosureLevel::Code => {
                lines.push("— Nickel constraint preview —".into());
                lines.push(
                    smt.constraint_code
                        .clone()
                        .unwrap_or_else(|| "(no constraint fragment)".into()),
                );
            }
            DisclosureLevel::Full => {
                lines.push("— Full SMT-LIB —".into());
                lines.push(smt.full_script.unwrap_or_else(|| "(no script)".into()));
            }
        }

        lines.join("\n")
    }

    pub fn update_internal(&mut self, msg: DecisionTreeMsg) {
        match msg {
            DecisionTreeMsg::Graph(g) => match g {
                DecisionGraphMsg::Pan(v) => self.layout.pan_offset = v,
                DecisionGraphMsg::Zoom {
                    cursor_in_canvas,
                    delta_steps,
                } => self.apply_zoom(cursor_in_canvas, delta_steps),
                DecisionGraphMsg::ClickNode(id) => {
                    self.selected_node = Some(id.clone());
                    self.smt_disclosure
                        .entry(id)
                        .or_insert(DisclosureLevel::Summary);
                }
                DecisionGraphMsg::DoubleClickNode(id) => {
                    if self.group_members.contains_key(&id) {
                        self.toggle_group_expand(&id);
                        self.relayout_visible();
                    }
                }
            },
            DecisionTreeMsg::ToggleDisclosure(level) => {
                if let Some(sel) = self.selected_node.clone() {
                    self.smt_disclosure.insert(sel, level);
                }
            }
            DecisionTreeMsg::Keyboard(ev) => self.handle_keyboard(ev),
            DecisionTreeMsg::WsEvent(ws_ev) => self.handle_ws_event(ws_ev),
        }
    }

    fn handle_ws_event(&mut self, ev: super::canvas_ws::CanvasWsEvent) {
        use super::canvas_ws::{CanvasType as WsCanvasType, CanvasWsEvent};

        match ev {
            CanvasWsEvent::Connected => {
                log::info!(
                    "[decision_tree] WebSocket connected, canvas_id={}",
                    self.canvas_id
                );
                // TODO: Send subscribe message via handle (requires refactoring subscription)
            }
            CanvasWsEvent::Disconnected => {
                log::warn!(
                    "[decision_tree] WebSocket disconnected, canvas_id={}",
                    self.canvas_id
                );
                self.ws_subscription_id = None;
            }
            CanvasWsEvent::Subscribed {
                subscription_id,
                canvas_id,
                canvas_type,
            } => {
                if canvas_id == self.canvas_id && canvas_type == WsCanvasType::DecisionTree {
                    log::info!(
                        "[decision_tree] Subscribed to canvas: sub_id={}",
                        subscription_id
                    );
                    self.ws_subscription_id = Some(subscription_id);
                }
            }
            CanvasWsEvent::Unsubscribed { subscription_id } => {
                if self.ws_subscription_id.as_deref() == Some(&subscription_id) {
                    log::info!("[decision_tree] Unsubscribed: {}", subscription_id);
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
                // Payload should contain a "tree" field with DecisionNode
                if let Some(tree_val) = payload.get("tree") {
                    if let Ok(root) = serde_json::from_value::<DecisionNode>(tree_val.clone()) {
                        let vp = Size::new(self.layout.viewport.width, self.layout.viewport.height);
                        self.load_tree(root, vp);
                        log::debug!(
                            "[decision_tree] Live update applied, nodes={}",
                            self.nodes.len()
                        );
                    }
                }
            }
            CanvasWsEvent::ActionReceived { .. } => {
                // Action acknowledgment - no action needed
            }
            CanvasWsEvent::Error { code, message } => {
                log::warn!(
                    "[decision_tree] WebSocket error: code={}, msg={}",
                    code,
                    message
                );
            }
            CanvasWsEvent::Heartbeat => {
                // Heartbeat - connection alive
            }
        }
    }

    fn handle_keyboard(&mut self, ev: keyboard::Event) {
        match ev {
            keyboard::Event::KeyPressed { key, .. } => match key {
                Key::Named(Named::ArrowDown) => self.navigate_selection(1),
                Key::Named(Named::ArrowUp) => self.navigate_selection(-1),
                Key::Named(Named::ArrowRight) => self.navigate_selection(1),
                Key::Named(Named::ArrowLeft) => self.navigate_selection(-1),
                Key::Named(Named::Enter) => {
                    if let Some(sel) = self.selected_node.clone() {
                        if self.group_members.contains_key(&sel) {
                            self.toggle_group_expand(&sel);
                            self.relayout_visible();
                        }
                    }
                }
                _ => {}
            },
            _ => {}
        }
    }

    pub fn view<'a>(&'a self) -> Element<'a, DecisionTreeMsg> {
        let vp = self.layout.viewport.size();
        let snap = self.graph_snapshot(vp);
        let program = DecisionGraphProgram { snapshot: snap };

        let canvas_widget = IcedCanvas::new(program)
            .width(Length::Fill)
            .height(Length::Fixed(self.layout.viewport.height.max(360.0)));

        let level = self
            .selected_node
            .as_ref()
            .and_then(|id| self.smt_disclosure.get(id).copied())
            .unwrap_or_default();

        let toggles = row![
            disclosure_btn("Summary", DisclosureLevel::Summary, level),
            disclosure_btn("Code", DisclosureLevel::Code, level),
            disclosure_btn("Full SMT", DisclosureLevel::Full, level),
        ]
        .spacing(8)
        .align_y(Alignment::Center);

        let body = self.detail_body_for_selected(level);
        let panel = column![
            text("SMT disclosure").size(16),
            toggles,
            scrollable(
                text(body)
                    .size(13)
                    .font(Font::MONOSPACE)
                    .width(Length::Fill),
            )
            .height(Length::Fixed(220.0)),
        ]
        .spacing(10)
        .padding(12);

        column![canvas_widget, panel].spacing(4).into()
    }

    pub fn subscription(&self) -> Subscription<DecisionTreeMsg> {
        use iced::futures::StreamExt;
        let kb = keyboard::listen().map(DecisionTreeMsg::Keyboard);
        if self.live_updates_enabled {
            let ws = Subscription::run(|| {
                super::canvas_ws::canvas_ws_worker(None).map(DecisionTreeMsg::WsEvent)
            });
            Subscription::batch([kb, ws])
        } else {
            kb
        }
    }
}

fn disclosure_btn<'a>(
    label: &'static str,
    target: DisclosureLevel,
    current: DisclosureLevel,
) -> Element<'a, DecisionTreeMsg> {
    let base = button(text(label).size(13));
    let _ = current;
    base.on_press(DecisionTreeMsg::ToggleDisclosure(target))
        .into()
}

impl Canvas for DecisionTreeCanvas {
    type Message = DecisionTreeMsg;

    fn id(&self) -> &str {
        self.canvas_id.as_str()
    }

    fn canvas_type(&self) -> CanvasType {
        CanvasType::DecisionTree
    }

    fn title(&self) -> String {
        match &self.session_id {
            Some(s) => format!("Decision tree · {s}"),
            None => "Decision tree".into(),
        }
    }

    fn update(&mut self, message: Self::Message) -> Task<Self::Message> {
        self.update_internal(message);
        Task::none()
    }

    fn view<'a>(&'a self) -> Element<'a, Self::Message> {
        DecisionTreeCanvas::view(self)
    }

    fn subscription(&self) -> Subscription<Self::Message> {
        DecisionTreeCanvas::subscription(self)
    }

    fn serialize_state(&self) -> Value {
        let disclosure_map: serde_json::Map<String, Value> = self
            .smt_disclosure
            .iter()
            .map(|(k, v)| (k.clone(), Value::String(disclosure_level_to_str(*v).into())))
            .collect();
        let expanded: Vec<&String> = self.expanded_parallel_groups.iter().collect();
        json!({
            "canvas_id": self.canvas_id,
            "session_id": self.session_id,
            "tree": self.cached_root,
            "zoom": self.layout.zoom,
            "pan_x": self.layout.pan_offset.x,
            "pan_y": self.layout.pan_offset.y,
            "viewport_w": self.layout.viewport.width,
            "viewport_h": self.layout.viewport.height,
            "selected": self.selected_node,
            "disclosure": Value::Object(disclosure_map),
            "expanded_parallel": expanded,
        })
    }

    fn deserialize_state(&mut self, data: &Value) -> Result<(), CanvasError> {
        if let Some(s) = data.get("session_id").and_then(|v| v.as_str()) {
            self.session_id = Some(s.to_string());
        }
        if let Some(id) = data.get("canvas_id").and_then(|v| v.as_str()) {
            self.canvas_id = id.to_string();
        }
        if let Some(z) = data.get("zoom").and_then(|v| v.as_f64()) {
            self.layout.zoom = (z as f32).clamp(ZOOM_MIN, ZOOM_MAX);
        }
        if let Some(x) = data.get("pan_x").and_then(|v| v.as_f64()) {
            self.layout.pan_offset.x = x as f32;
        }
        if let Some(y) = data.get("pan_y").and_then(|v| v.as_f64()) {
            self.layout.pan_offset.y = y as f32;
        }
        if let Some(w) = data.get("viewport_w").and_then(|v| v.as_f64()) {
            self.layout.viewport.width = w as f32;
        }
        if let Some(h) = data.get("viewport_h").and_then(|v| v.as_f64()) {
            self.layout.viewport.height = h as f32;
        }
        match data.get("selected") {
            Some(v) if v.is_null() => self.selected_node = None,
            Some(v) => {
                if let Some(s) = v.as_str() {
                    self.selected_node = Some(s.to_string());
                }
            }
            None => {}
        }

        self.smt_disclosure.clear();
        if let Some(map) = data.get("disclosure").and_then(|v| v.as_object()) {
            for (k, v) in map {
                if let Some(s) = v.as_str() {
                    if let Some(dl) = disclosure_level_from_str(s) {
                        self.smt_disclosure.insert(k.clone(), dl);
                    }
                }
            }
        }

        self.expanded_parallel_groups.clear();
        if let Some(arr) = data.get("expanded_parallel").and_then(|v| v.as_array()) {
            for x in arr {
                if let Some(s) = x.as_str() {
                    self.expanded_parallel_groups.insert(s.to_string());
                }
            }
        }

        if let Some(tree_val) = data.get("tree") {
            if !tree_val.is_null() {
                let root: DecisionNode = serde_json::from_value(tree_val.clone())
                    .map_err(|e| CanvasError::Deserialize(e.to_string()))?;
                let vp = Size::new(self.layout.viewport.width, self.layout.viewport.height);
                self.load_tree(root, vp);
            }
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn flatten_and_layout_basic_tree() {
        let root = DecisionNode {
            id: "session".into(),
            action: Some(
                json!({"label": "Session Start"})
                    .as_object()
                    .unwrap()
                    .clone(),
            ),
            verification_tier: None,
            result: None,
            timing_ms: None,
            timestamp: Some("2026-04-29T12:00:00Z".into()),
            smt_data: None,
            children: vec![
                DecisionNode {
                    id: "a".into(),
                    action: Some(
                        json!({"tool":"file_read","risk":"low"})
                            .as_object()
                            .unwrap()
                            .clone(),
                    ),
                    verification_tier: Some(VerificationTier::FastCheck),
                    result: Some(VerificationResult::Approved),
                    timing_ms: Some(12.0),
                    timestamp: None,
                    smt_data: None,
                    children: vec![],
                },
                DecisionNode {
                    id: "b".into(),
                    action: Some(
                        json!({"tool":"file_edit","risk":"medium"})
                            .as_object()
                            .unwrap()
                            .clone(),
                    ),
                    verification_tier: Some(VerificationTier::ConstraintCheck),
                    result: Some(VerificationResult::Approved),
                    timing_ms: Some(45.0),
                    timestamp: None,
                    smt_data: Some(SmtData {
                        summary: "Passed T2".into(),
                        constraint_code: Some("{ foo | Num }".into()),
                        full_script: Some("(assert true)".into()),
                        model: None,
                    }),
                    children: vec![],
                },
            ],
        };

        let mut canvas = DecisionTreeCanvas::new("dt-test", Some("s1".into()));
        canvas.load_tree(root, Size::new(640.0, 480.0));
        assert!(canvas.layout.positions.contains_key("session"));
        assert!(canvas.layout.positions.contains_key("a"));
        assert!(canvas.layout.positions.contains_key("b"));
        assert_eq!(
            nesy_risk_from_action(&canvas.nodes["a"].action),
            NesyRiskLevel::Low
        );
        assert_eq!(
            nesy_risk_from_action(&canvas.nodes["b"].action),
            NesyRiskLevel::Medium
        );
    }

    #[test]
    fn zoom_respects_bounds() {
        let mut canvas = DecisionTreeCanvas::new("dt-zoom-test", None);
        canvas.layout.zoom = ZOOM_MAX;
        canvas.apply_zoom(Point::new(50.0, 50.0), 10.0);
        assert!(canvas.layout.zoom <= ZOOM_MAX + 1e-4);
        canvas.layout.zoom = ZOOM_MIN;
        canvas.apply_zoom(Point::new(50.0, 50.0), -10.0);
        assert!(canvas.layout.zoom >= ZOOM_MIN - 1e-4);
    }
}
