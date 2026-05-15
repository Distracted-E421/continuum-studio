//! Dendrix-backed topology canvas: session files, imports as edges, agent overlays, heat hints.

use std::collections::{HashMap, HashSet};
use std::path::Path as FsPath;
use std::time::{Duration, Instant};

use iced::alignment::Vertical;
use iced::border;
use iced::mouse;
use iced::widget::canvas::{self, Event, Frame, Path as GeoPath, Stroke as CanvasStroke};
use iced::widget::{button, column, container, row, text, toggler, Space};
use iced::{
    Alignment, Color, Element, Length, Padding, Point, Rectangle, Size, Subscription, Task, Theme,
    Vector,
};

use crate::canvas::dendrix_client::{
    DendrixGraphWire, DendrixNodeWire, DendrixRpcConn,
};
use serde_json::json;

use crate::canvas::{Canvas, CanvasError};
use crate::scp::{
    AgentLayer, CanvasType, TopologyEdge, TopologyEdgeType, TopologyNode, TopologyNodeType,
};

const NODE_W: f32 = 168.0;
const NODE_H: f32 = 52.0;

#[derive(Debug, Clone)]
pub enum TopologyMode {
    Discovery { root_files: Vec<String> },
    Static { files: Vec<String> },
    Live { watch_events: bool },
}

impl Default for TopologyMode {
    fn default() -> Self {
        TopologyMode::Discovery {
            root_files: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TopologyDepthPreset {
    #[default]
    Relevance,
    Shallow,
    Deep,
}

impl TopologyDepthPreset {
    pub fn expand_depth(self) -> u32 {
        match self {
            TopologyDepthPreset::Relevance => 2,
            TopologyDepthPreset::Shallow => 1,
            TopologyDepthPreset::Deep => 4,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            TopologyDepthPreset::Relevance => "Relevance",
            TopologyDepthPreset::Shallow => "Shallow",
            TopologyDepthPreset::Deep => "Deep",
        }
    }
}

#[derive(Debug, Clone)]
pub struct GraphLayout {
    pub positions: HashMap<String, Point>,
    pub viewport: Rectangle,
    pub zoom: f32,
}

impl Default for GraphLayout {
    fn default() -> Self {
        Self {
            positions: HashMap::new(),
            viewport: Rectangle {
                x: 0.0,
                y: 0.0,
                width: 800.0,
                height: 600.0,
            },
            zoom: 1.0,
        }
    }
}

#[derive(Debug, Clone)]
pub enum DendrixTopologyMessage {
    HarnessConnected,
    HarnessDisconnected(String),
    GraphWireUpdated(DendrixGraphWire),
    HarnessLiveEvent(String),
    HarnessError(String),

    RefreshRequested,
    ToggleLayer(String),
    DepthChanged(TopologyDepthPreset),
    ModeDiscovery,
    ModeStatic,
    ModeLive,

    NodeSelected(Option<String>),
    OpenFile(String),

    /// WebSocket event from the generic SCP canvas WebSocket
    WsEvent(super::canvas_ws::CanvasWsEvent),
}

#[derive(Debug, Clone)]
pub struct DendrixTopologyCanvas {
    canvas_id: String,
    pub session_id: Option<String>,
    pub mode: TopologyMode,
    pub nodes: HashMap<String, TopologyNode>,
    pub edges: Vec<TopologyEdge>,
    pub layers: Vec<AgentLayer>,
    pub active_layers: HashSet<String>,
    pub layout: GraphLayout,
    pub selected_node: Option<String>,
    pub heat_map: HashMap<String, f32>,
    pub depth_preset: TopologyDepthPreset,
    pending_refresh: bool,
    /// Whether to subscribe to the SCP canvas WebSocket for live updates
    pub scp_live_updates: bool,
    /// SCP WebSocket subscription ID
    scp_subscription_id: Option<String>,
}

impl Default for DendrixTopologyCanvas {
    fn default() -> Self {
        Self {
            canvas_id: "dendrix-topology".to_string(),
            session_id: None,
            mode: TopologyMode::default(),
            nodes: HashMap::new(),
            edges: Vec::new(),
            layers: Vec::new(),
            active_layers: HashSet::new(),
            layout: GraphLayout::default(),
            selected_node: None,
            heat_map: HashMap::new(),
            depth_preset: TopologyDepthPreset::default(),
            pending_refresh: false,
            scp_live_updates: false,
            scp_subscription_id: None,
        }
    }
}

impl DendrixTopologyCanvas {
    pub fn new(id: impl Into<String>, session_id: Option<String>) -> Self {
        Self {
            canvas_id: id.into(),
            session_id,
            ..Self::default()
        }
    }

    /// Create with SCP canvas WebSocket updates enabled
    pub fn with_scp_updates(id: impl Into<String>, session_id: Option<String>) -> Self {
        let mut canvas = Self::new(id, session_id);
        canvas.scp_live_updates = true;
        canvas
    }

    /// Enable/disable SCP canvas WebSocket updates
    pub fn set_scp_updates(&mut self, enabled: bool) {
        self.scp_live_updates = enabled;
        if !enabled {
            self.scp_subscription_id = None;
        }
    }

    fn handle_ws_event(&mut self, ev: super::canvas_ws::CanvasWsEvent) {
        use super::canvas_ws::{CanvasType as WsCanvasType, CanvasWsEvent};

        match ev {
            CanvasWsEvent::Connected => {
                log::info!(
                    "[dendrix_topology] SCP WebSocket connected, canvas_id={}",
                    self.canvas_id
                );
            }
            CanvasWsEvent::Disconnected => {
                log::warn!(
                    "[dendrix_topology] SCP WebSocket disconnected, canvas_id={}",
                    self.canvas_id
                );
                self.scp_subscription_id = None;
            }
            CanvasWsEvent::Subscribed {
                subscription_id,
                canvas_id,
                canvas_type,
            } => {
                if canvas_id == self.canvas_id && canvas_type == WsCanvasType::Topology {
                    log::info!(
                        "[dendrix_topology] Subscribed to canvas: sub_id={}",
                        subscription_id
                    );
                    self.scp_subscription_id = Some(subscription_id);
                }
            }
            CanvasWsEvent::Unsubscribed { subscription_id } => {
                if self.scp_subscription_id.as_deref() == Some(&subscription_id) {
                    log::info!("[dendrix_topology] Unsubscribed: {}", subscription_id);
                    self.scp_subscription_id = None;
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
                if self.scp_subscription_id.as_deref() != Some(&subscription_id) {
                    return;
                }
                // Payload can contain nodes and edges for topology update
                if let Some(nodes_val) = payload.get("nodes") {
                    if let Ok(nodes) =
                        serde_json::from_value::<HashMap<String, TopologyNode>>(nodes_val.clone())
                    {
                        self.nodes = nodes;
                    }
                }
                if let Some(edges_val) = payload.get("edges") {
                    if let Ok(edges) =
                        serde_json::from_value::<Vec<TopologyEdge>>(edges_val.clone())
                    {
                        self.edges = edges;
                    }
                }
                if let Some(heat_val) = payload.get("heat_map") {
                    if let Ok(heat) =
                        serde_json::from_value::<HashMap<String, f32>>(heat_val.clone())
                    {
                        self.heat_map = heat;
                    }
                }
                log::debug!(
                    "[dendrix_topology] SCP update applied, nodes={}, edges={}",
                    self.nodes.len(),
                    self.edges.len()
                );
            }
            CanvasWsEvent::ActionReceived { .. } => {}
            CanvasWsEvent::Error { code, message } => {
                log::warn!(
                    "[dendrix_topology] SCP WebSocket error: code={}, msg={}",
                    code,
                    message
                );
            }
            CanvasWsEvent::Heartbeat => {}
        }
    }

    pub fn ingest_graph_wire(&mut self, wire: &DendrixGraphWire) {
        self.nodes.clear();
        self.edges.clear();
        for n in &wire.nodes {
            let tn = wire_node_to_topology(n);
            self.nodes.insert(tn.id.clone(), tn);
        }
        for e in &wire.edges {
            self.edges.push(wire_edge_to_topology(e));
        }
        self.sync_agent_badges();
    }

    pub fn ingest_topology_nodes(&mut self, extra: Vec<TopologyNode>) {
        for tn in extra {
            self.nodes.entry(tn.id.clone()).or_insert(tn);
        }
        self.sync_agent_badges();
    }

    pub fn record_agent_touch(&mut self, agent_id: &str, node_id: &str, color: Option<&str>) {
        let color =
            color.map(|s| s.to_string()).unwrap_or_else(|| palette_color_for_agent(agent_id));
        let touch = self.heat_map.entry(node_id.to_string()).or_insert(0.0);
        *touch += 1.0;

        if !self.layers.iter().any(|l| l.agent_id == agent_id) {
            self.layers.push(AgentLayer {
                agent_id: agent_id.to_string(),
                color: color.clone(),
                visible: true,
            });
        }
        self.active_layers.insert(agent_id.to_string());

        if let Some(node) = self.nodes.get_mut(node_id) {
            if !node.agent_layers.iter().any(|l| l.agent_id == agent_id) {
                node.agent_layers.push(AgentLayer {
                    agent_id: agent_id.to_string(),
                    color,
                    visible: true,
                });
            }
        }
    }

    fn sync_agent_badges(&mut self) {
        for (_id, node) in self.nodes.iter_mut() {
            node.agent_layers.retain(|l| self.active_layers.contains(&l.agent_id));
        }
    }

    pub fn toggle_layer(&mut self, agent_id: &str) {
        if self.active_layers.contains(agent_id) {
            self.active_layers.remove(agent_id);
        } else {
            self.active_layers.insert(agent_id.to_string());
        }
        if let Some(l) = self.layers.iter_mut().find(|l| l.agent_id == agent_id) {
            l.visible = self.active_layers.contains(agent_id);
        }
    }

    pub fn layout_positions(&self, size: Size) -> HashMap<String, Point> {
        hierarchical_layout(&self.nodes, &self.edges, size)
    }

    fn painter(&self, size: Size) -> TopologyPainter {
        TopologyPainter {
            positions: self.layout_positions(size),
            edges: self.edges.clone(),
            nodes: self.nodes.clone(),
            heat_map: self.heat_map.clone(),
            selected: self.selected_node.clone(),
            active_layers: self.active_layers.clone(),
        }
    }

    pub fn layer_toolbar(&self) -> Element<'_, DendrixTopologyMessage> {
        let mut row_layers = row![text("Layers:").size(13)].spacing(8).align_y(Alignment::Center);

        for layer in &self.layers {
            let id = layer.agent_id.clone();
            let checked = self.active_layers.contains(&id);
            let label = format!(
                "{} {}",
                id.chars().take(14).collect::<String>(),
                agent_swatch(&layer.color)
            );
            row_layers = row_layers.push(
                toggler(checked)
                    .label(label)
                    .spacing(8)
                    .on_toggle(move |_v| DendrixTopologyMessage::ToggleLayer(id.clone())),
            );
        }

        if self.layers.is_empty() {
            row_layers = row_layers.push(text("(no agent layers yet)").size(12));
        }

        row_layers.into()
    }

    pub fn controls_row(&self) -> Element<'_, DendrixTopologyMessage> {
        let mode_label = text(format!("Mode: {}", mode_label_str(&self.mode))).size(12);

        let depth_txt = text(format!("Depth: {}", self.depth_preset.label())).size(12);

        row![
            button(text("Discovery")).on_press(DendrixTopologyMessage::ModeDiscovery),
            button(text("Static")).on_press(DendrixTopologyMessage::ModeStatic),
            button(text("Live")).on_press(DendrixTopologyMessage::ModeLive),
            button(text("Refresh")).on_press(DendrixTopologyMessage::RefreshRequested),
            Space::new().width(Length::Fixed(8.0)),
            mode_label,
            Space::new().width(Length::Fixed(12.0)),
            depth_txt,
            button(text("Cycle depth")).on_press(DendrixTopologyMessage::DepthChanged(
                match self.depth_preset {
                    TopologyDepthPreset::Relevance => TopologyDepthPreset::Shallow,
                    TopologyDepthPreset::Shallow => TopologyDepthPreset::Deep,
                    TopologyDepthPreset::Deep => TopologyDepthPreset::Relevance,
                },
            )),
        ]
        .spacing(6)
        .align_y(Alignment::Center)
        .into()
    }
}

impl Canvas for DendrixTopologyCanvas {
    type Message = DendrixTopologyMessage;

    fn id(&self) -> &str {
        &self.canvas_id
    }

    fn canvas_type(&self) -> CanvasType {
        CanvasType::Topology
    }

    fn title(&self) -> String {
        String::from("Dendrix topology")
    }

    fn update(&mut self, message: Self::Message) -> Task<Self::Message> {
        match message {
            DendrixTopologyMessage::GraphWireUpdated(ref w) => self.ingest_graph_wire(w),
            DendrixTopologyMessage::ToggleLayer(id) => self.toggle_layer(&id),
            DendrixTopologyMessage::DepthChanged(d) => self.depth_preset = d,
            DendrixTopologyMessage::ModeDiscovery => {
                let roots = match &self.mode {
                    TopologyMode::Discovery { root_files } => root_files.clone(),
                    _ => Vec::new(),
                };
                self.mode = TopologyMode::Discovery { root_files: roots };
            }
            DendrixTopologyMessage::ModeStatic => {
                let files = match &self.mode {
                    TopologyMode::Static { files } => files.clone(),
                    _ => Vec::new(),
                };
                self.mode = TopologyMode::Static { files };
            }
            DendrixTopologyMessage::ModeLive => {
                self.mode = TopologyMode::Live {
                    watch_events: true,
                };
            }
            DendrixTopologyMessage::NodeSelected(s) => self.selected_node = s,
            DendrixTopologyMessage::HarnessLiveEvent(_) => {
                self.pending_refresh = true;
            }
            DendrixTopologyMessage::WsEvent(ev) => {
                self.handle_ws_event(ev);
            }
            _ => {}
        }
        Task::none()
    }

    fn view<'a>(&'a self) -> Element<'a, Self::Message> {
        let header = column![
            self.layer_toolbar(),
            self.controls_row(),
            Space::new().height(Length::Fixed(6.0)),
        ]
        .spacing(6);

        let painter = self.painter(Size::new(
            self.layout.viewport.width.max(400.0),
            self.layout.viewport.height.max(320.0),
        ));

        let canv = canvas::Canvas::new(painter)
            .width(Length::Fill)
            .height(Length::Fill);

        column![
            header,
            container(canv)
                .width(Length::Fill)
                .height(Length::Fill)
                .padding(Padding::from(4)),
        ]
        .spacing(4)
        .into()
    }

    fn subscription(&self) -> Subscription<Self::Message> {
        use iced::futures::StreamExt;

        let mut subs = Vec::new();

        // Dendrix harness WebSocket for discovery/live mode
        let dendrix_live = matches!(
            self.mode,
            TopologyMode::Live {
                watch_events: true
            }
        );
        if dendrix_live {
            subs.push(dendrix_topology_subscription(
                crate::canvas::dendrix_client::DEFAULT_DENDRIX_HARNESS_WS.to_string(),
                true,
            ));
        }

        // Generic SCP canvas WebSocket for server-pushed updates
        if self.scp_live_updates {
            subs.push(Subscription::run(|| {
                super::canvas_ws::canvas_ws_worker(None).map(DendrixTopologyMessage::WsEvent)
            }));
        }

        if subs.is_empty() {
            Subscription::none()
        } else {
            Subscription::batch(subs)
        }
    }

    fn serialize_state(&self) -> serde_json::Value {
        json!({
            "session_id": self.session_id,
            "depth_preset": self.depth_preset.label(),
            "selected_node": self.selected_node,
        })
    }

    fn deserialize_state(&mut self, data: &serde_json::Value) -> Result<(), CanvasError> {
        if let Some(s) = data.get("session_id").and_then(|v| v.as_str()) {
            self.session_id = Some(s.to_string());
        }
        if let Some(s) = data.get("selected_node").and_then(|v| v.as_str()) {
            self.selected_node = Some(s.to_string());
        }
        Ok(())
    }
}

fn mode_label_str(mode: &TopologyMode) -> &'static str {
    match mode {
        TopologyMode::Discovery { .. } => "Discovery",
        TopologyMode::Static { .. } => "Static",
        TopologyMode::Live { .. } => "Live",
    }
}

fn agent_swatch(hex: &str) -> String {
    format!("● {}", hex)
}

fn palette_color_for_agent(agent_id: &str) -> String {
    let palette = [
        "#e5484d", "#3e63dd", "#30a46c", "#df9300", "#793aaf", "#239eaf", "#bd2864", "#7cb342",
    ];
    let mut h: u32 = 0;
    for b in agent_id.bytes() {
        h = h.wrapping_mul(31).wrapping_add(b as u32);
    }
    palette[h as usize % palette.len()].to_string()
}

fn wire_node_to_topology(n: &DendrixNodeWire) -> TopologyNode {
    let file_path = n
        .metadata
        .get("file_path")
        .and_then(|v| v.as_str())
        .unwrap_or(&n.label)
        .to_string();

    let imports: Vec<String> =
        match n.metadata.get("imports").cloned().unwrap_or(serde_json::Value::Null) {
            serde_json::Value::Array(a) => a
                .into_iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect(),
            serde_json::Value::String(s) => vec![s],
            _ => Vec::new(),
        };

    TopologyNode {
        id: n.id.clone(),
        file_path,
        node_type: map_kind(&n.kind),
        imports,
        agent_layers: Vec::new(),
    }
}

fn wire_edge_to_topology(e: &crate::canvas::dendrix_client::DendrixEdgeWire) -> TopologyEdge {
    TopologyEdge {
        from: e.source.clone(),
        to: e.target.clone(),
        edge_type: edge_type_from_kind(&e.kind),
    }
}

fn map_kind(kind: &str) -> TopologyNodeType {
    match kind.to_lowercase().as_str() {
        "package" | "crate" => TopologyNodeType::Package,
        "service" | "otp" => TopologyNodeType::Service,
        "zone" => TopologyNodeType::Zone,
        "component" | "module" => TopologyNodeType::Module,
        "external" => TopologyNodeType::External,
        _ => TopologyNodeType::File,
    }
}

fn edge_type_from_kind(kind: &str) -> TopologyEdgeType {
    match kind.to_lowercase().as_str() {
        "imports" | "import" => TopologyEdgeType::Imports,
        "calls" | "call" => TopologyEdgeType::Calls,
        "contains" => TopologyEdgeType::Contains,
        "reads_from" => TopologyEdgeType::ReadsFrom,
        "writes_to" => TopologyEdgeType::WritesTo,
        _ => TopologyEdgeType::DependsOn,
    }
}

pub fn file_icon_suffix(path: &str) -> &'static str {
    match FsPath::new(path)
        .extension()
        .and_then(|s| s.to_str())
        .map(|s| s.to_lowercase())
    {
        Some(ref e) if e == "ex" || e == "exs" => "💧",
        Some(ref e) if e == "rs" => "🦀",
        Some(ref e) if e == "ts" || e == "tsx" => "📘",
        Some(ref e) if e == "js" || e == "jsx" => "📒",
        Some(ref e) if e == "json" => "{}",
        Some(ref e) if e == "ncl" => "🔷",
        Some(ref e) if e == "md" => "📝",
        Some(ref e) if e == "toml" => "⚙️",
        _ => "📄",
    }
}

fn hierarchical_layout(
    nodes: &HashMap<String, TopologyNode>,
    edges: &[TopologyEdge],
    size: Size,
) -> HashMap<String, Point> {
    let mut depth: HashMap<String, usize> = HashMap::new();
    for id in nodes.keys() {
        depth.insert(id.clone(), 0);
    }
    for _ in 0..nodes.len().saturating_add(3) {
        for e in edges {
            if let Some(&du) = depth.get(&e.from) {
                let dv = depth.entry(e.to.clone()).or_insert(0);
                *dv = (*dv).max(du + 1);
            }
        }
    }

    let mut layers: HashMap<usize, Vec<String>> = HashMap::new();
    for id in nodes.keys() {
        let d = *depth.get(id).unwrap_or(&0);
        layers.entry(d).or_default().push(id.clone());
    }
    for v in layers.values_mut() {
        v.sort();
    }

    let mut pos = HashMap::new();
    let x_gap = 220.0;
    let y_gap = 96.0;
    for (d, ids) in layers.iter() {
        let xf = 80.0 + (*d as f32) * x_gap;
        for (i, id) in ids.iter().enumerate() {
            pos.insert(id.clone(), Point::new(xf, 80.0 + i as f32 * y_gap));
        }
    }

    center_positions(&mut pos, size);
    pos
}

fn center_positions(pos: &mut HashMap<String, Point>, size: Size) {
    if pos.is_empty() {
        return;
    }
    let mut min_x = f32::MAX;
    let mut max_x = f32::MIN;
    let mut min_y = f32::MAX;
    let mut max_y = f32::MIN;
    for p in pos.values() {
        min_x = min_x.min(p.x - NODE_W / 2.0);
        max_x = max_x.max(p.x + NODE_W / 2.0);
        min_y = min_y.min(p.y - NODE_H / 2.0);
        max_y = max_y.max(p.y + NODE_H / 2.0);
    }
    let cx = (min_x + max_x) / 2.0;
    let cy = (min_y + max_y) / 2.0;
    let tx = size.width / 2.0 - cx;
    let ty = size.height / 2.0 - cy;
    for p in pos.values_mut() {
        p.x += tx;
        p.y += ty;
    }
}

#[derive(Clone)]
struct TopologyPainter {
    positions: HashMap<String, Point>,
    edges: Vec<TopologyEdge>,
    nodes: HashMap<String, TopologyNode>,
    heat_map: HashMap<String, f32>,
    selected: Option<String>,
    active_layers: HashSet<String>,
}

struct TopoInteractionState {
    pan: Vector,
    zoom: f32,
    dragging: bool,
    drag_last: Option<Point>,
    click_track: Option<(Instant, String)>,
}

impl TopoInteractionState {
    fn new() -> Self {
        Self {
            zoom: 1.0,
            pan: Vector::ZERO,
            dragging: false,
            drag_last: None,
            click_track: None,
        }
    }

    fn to_graph(&self, screen: Point) -> Point {
        Point::new(
            (screen.x - self.pan.x) / self.zoom,
            (screen.y - self.pan.y) / self.zoom,
        )
    }

    fn to_screen(&self, graph: Point) -> Point {
        Point::new(graph.x * self.zoom + self.pan.x, graph.y * self.zoom + self.pan.y)
    }
}

impl Default for TopoInteractionState {
    fn default() -> Self {
        Self::new()
    }
}

impl canvas::Program<DendrixTopologyMessage> for TopologyPainter {
    type State = TopoInteractionState;

    fn update(
        &self,
        state: &mut Self::State,
        event: &Event,
        bounds: Rectangle,
        cursor: mouse::Cursor,
    ) -> Option<canvas::Action<DendrixTopologyMessage>> {
        match event {
            Event::Mouse(mouse::Event::WheelScrolled { delta }) => {
                let dy = match delta {
                    mouse::ScrollDelta::Lines { y, .. } => *y,
                    mouse::ScrollDelta::Pixels { y, .. } => *y / 40.0,
                };
                let factor = (1.0 + dy * 0.12).clamp(0.55, 1.45);
                state.zoom = (state.zoom * factor).clamp(0.35, 4.0);
                return Some(canvas::Action::request_redraw());
            }
            Event::Mouse(mouse::Event::ButtonPressed(mouse::Button::Left)) => {
                if let Some(cur) = cursor.position_in(bounds) {
                    state.dragging = true;
                    state.drag_last = Some(cur);
                    let graph_p = state.to_graph(cur);
                    if let Some((id, _)) = hit_node(&self.positions, graph_p) {
                        let now = Instant::now();
                        let path = self
                            .nodes
                            .get(&id)
                            .map(|n| n.file_path.clone())
                            .unwrap_or_else(|| id.clone());
                        if let Some((t_prev, id_prev)) = &state.click_track {
                            if id_prev == &id && now.duration_since(*t_prev) < Duration::from_millis(420)
                            {
                                state.click_track = None;
                                return Some(canvas::Action::publish(
                                    DendrixTopologyMessage::OpenFile(path),
                                ));
                            }
                        }
                        state.click_track = Some((now, id.clone()));
                        return Some(canvas::Action::publish(
                            DendrixTopologyMessage::NodeSelected(Some(id)),
                        ));
                    }
                    state.click_track = None;
                    return Some(canvas::Action::publish(DendrixTopologyMessage::NodeSelected(
                        None,
                    )));
                }
            }
            Event::Mouse(mouse::Event::ButtonReleased(mouse::Button::Left)) => {
                state.dragging = false;
                state.drag_last = None;
            }
            Event::Mouse(mouse::Event::CursorMoved { .. }) => {
                if state.dragging {
                    if let Some(cur) = cursor.position_in(bounds) {
                        if let Some(last) = state.drag_last {
                            state.pan.x += cur.x - last.x;
                            state.pan.y += cur.y - last.y;
                            state.drag_last = Some(cur);
                            return Some(canvas::Action::request_redraw());
                        }
                    }
                }
            }
            _ => {}
        }
        None
    }

    fn draw(
        &self,
        state: &Self::State,
        renderer: &iced::Renderer,
        theme: &Theme,
        bounds: Rectangle,
        _cursor: mouse::Cursor,
    ) -> Vec<canvas::Geometry<iced::Renderer>> {
        let mut frame = Frame::new(renderer, bounds.size());

        let bg = theme.palette().background;
        frame.fill(
            &GeoPath::rectangle(Point::ORIGIN, bounds.size()),
            bg.scale_alpha(0.96),
        );

        let istate = state;

        for e in &self.edges {
            let Some(pa) = self.positions.get(&e.from) else {
                continue;
            };
            let Some(pb) = self.positions.get(&e.to) else {
                continue;
            };
            let sa = istate.to_screen(*pa);
            let sb = istate.to_screen(*pb);

            let path = GeoPath::line(sa, sb);
            frame.stroke(
                &path,
                CanvasStroke::default()
                    .with_color(Color::from_rgb8(120, 140, 190))
                    .with_width(1.5),
            );

            let dir = Vector::new(sb.x - sa.x, sb.y - sa.y);
            let len = (dir.x * dir.x + dir.y * dir.y).sqrt().max(1.0);
            let ux = dir.x / len * 12.0;
            let uy = dir.y / len * 12.0;
            let tip = Point::new(sb.x - ux * 1.4, sb.y - uy * 1.4);
            let left = Point::new(tip.x - uy * 0.35, tip.y + ux * 0.35);
            let right = Point::new(tip.x + uy * 0.35, tip.y - ux * 0.35);
            let head = GeoPath::new(|b| {
                b.move_to(left);
                b.line_to(sb);
                b.line_to(right);
                b.close();
            });
            frame.fill(&head, Color::from_rgb8(140, 170, 230));
        }

        let rad_glow = border::radius(8.0 * istate.zoom);
        let rad_fill = border::radius(6.0 * istate.zoom);

        for (id, center) in &self.positions {
            let Some(node) = self.nodes.get(id) else {
                continue;
            };
            let heat = self.heat_map.get(id).copied().unwrap_or(0.0).min(32.0) / 32.0;
            let glow_a = 0.15 + heat * 0.55;
            let glow = Color::from_rgba(1.0, 0.35, 0.35, glow_a);
            let rect = Rectangle::new(
                istate.to_screen(Point::new(center.x - NODE_W / 2.0, center.y - NODE_H / 2.0)),
                Size::new(NODE_W * istate.zoom, NODE_H * istate.zoom),
            );

            if heat > 0.02 {
                frame.fill(
                    &GeoPath::rounded_rectangle(rect.position(), rect.size(), rad_glow),
                    glow,
                );
            }

            let base = if Some(id) == self.selected.as_ref() {
                Color::from_rgb8(90, 120, 200)
            } else {
                Color::from_rgb8(52, 58, 74)
            };
            frame.fill(
                &GeoPath::rounded_rectangle(rect.position(), rect.size(), rad_fill),
                base,
            );

            let icon = file_icon_suffix(&node.file_path);
            let label = FsPath::new(node.file_path.as_str())
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or(node.file_path.as_str());

            let mut overlay = String::from(icon);
            overlay.push(' ');
            overlay.push_str(label);

            for al in &node.agent_layers {
                if self.active_layers.contains(&al.agent_id) {
                    overlay.push(' ');
                    overlay.push('●');
                }
            }

            use iced::widget::text::Alignment as TextAlign;
            let txt = canvas::Text {
                content: overlay,
                position: rect.center(),
                max_width: f32::INFINITY,
                color: Color::WHITE,
                size: iced::Pixels(13.0 * istate.zoom.max(0.45)),
                align_x: TextAlign::Center,
                align_y: Vertical::Center,
                ..canvas::Text::default()
            };
            frame.fill_text(txt);
        }

        vec![frame.into_geometry()]
    }

    fn mouse_interaction(
        &self,
        _state: &Self::State,
        _bounds: Rectangle,
        _cursor: mouse::Cursor,
    ) -> mouse::Interaction {
        mouse::Interaction::Crosshair
    }
}

fn hit_node(positions: &HashMap<String, Point>, graph_p: Point) -> Option<(String, Rectangle)> {
    let mut best: Option<(String, Rectangle)> = None;
    for (id, c) in positions {
        let r = Rectangle::new(
            Point::new(c.x - NODE_W / 2.0, c.y - NODE_H / 2.0),
            Size::new(NODE_W, NODE_H),
        );
        if r.contains(graph_p) {
            best = Some((id.clone(), r));
        }
    }
    best
}

/// Live harness subscription: 500ms polling when `live_poll`, plus reconnect loop.
pub fn dendrix_topology_subscription(
    ws_url: String,
    live_poll: bool,
) -> Subscription<DendrixTopologyMessage> {
    Subscription::run_with((ws_url, live_poll), dendrix_topology_worker)
}

fn dendrix_topology_worker(
    key: &(String, bool),
) -> impl iced::futures::Stream<Item = DendrixTopologyMessage> {
    let ws_url = key.0.clone();
    let live_poll = key.1;
    iced::stream::channel(
        64,
        move |mut output: iced::futures::channel::mpsc::Sender<DendrixTopologyMessage>| async move {
            use iced::futures::SinkExt;
            loop {
                match DendrixRpcConn::connect(&ws_url).await {
                    Ok((conn, mut notifications)) => {
                        let _ = output.send(DendrixTopologyMessage::HarnessConnected).await;
                        let mut ticker =
                            tokio::time::interval(std::time::Duration::from_millis(500));
                        ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);

                        loop {
                            tokio::select! {
                                _ = ticker.tick(), if live_poll => {
                                    match conn.graph_get_state().await {
                                        Ok(g) => {
                                            let _ = output.send(DendrixTopologyMessage::GraphWireUpdated(g)).await;
                                        }
                                        Err(e) => {
                                            let _ = output.send(DendrixTopologyMessage::HarnessError(e.to_string())).await;
                                        }
                                    }
                                }
                                n = notifications.recv() => {
                                    match n {
                                        Some(crate::canvas::dendrix_client::DendrixNotification::Event { method, .. }) => {
                                            let short = method.strip_prefix("event.").unwrap_or(&method).to_string();
                                            let _ = output.send(DendrixTopologyMessage::HarnessLiveEvent(short)).await;
                                            if live_poll {
                                                continue;
                                            }
                                            match conn.graph_get_state().await {
                                                Ok(g) => {
                                                    let _ = output.send(DendrixTopologyMessage::GraphWireUpdated(g)).await;
                                                }
                                                Err(_) => {}
                                            }
                                        }
                                        None => break,
                                    }
                                }
                            }
                        }
                        let _ = output
                            .send(DendrixTopologyMessage::HarnessDisconnected(
                                "socket closed".into(),
                            ))
                            .await;
                    }
                    Err(e) => {
                        let _ = output
                            .send(DendrixTopologyMessage::HarnessError(e.to_string()))
                            .await;
                        tokio::time::sleep(std::time::Duration::from_secs(3)).await;
                    }
                }
            }
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_keeps_nodes() {
        let mut nodes = HashMap::new();
        nodes.insert(
            "a".into(),
            TopologyNode {
                id: "a".into(),
                file_path: "a.ex".into(),
                node_type: TopologyNodeType::File,
                imports: vec![],
                agent_layers: vec![],
            },
        );
        let edges: Vec<TopologyEdge> = vec![];
        let pos = hierarchical_layout(&nodes, &edges, Size::new(400.0, 400.0));
        assert!(pos.contains_key("a"));
    }
}
