//! D2 Diagram Viewer Widget
//!
//! Interactive diagram rendering with pan/zoom, node selection,
//! and theme-aware colors. Extracted and adapted from cursor-studio-egui.

use eframe::egui::{self, Color32, Painter, Pos2, Rect, Sense, Stroke, Vec2, FontId, Align2};
use std::collections::HashMap;
use std::time::Instant;
use anyhow::Result;
use super::{Widget, WidgetEvent};

/// Diagram node shape types
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum NodeShape {
    Rectangle,
    Circle,
    Diamond,
    Hexagon,
    Cylinder,
    Cloud,
    Document,
}

impl Default for NodeShape {
    fn default() -> Self {
        NodeShape::Rectangle
    }
}

/// Node status for visual indication
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum NodeStatus {
    Ready,
    Running,
    Success,
    Error,
    Warning,
    Pending,
}

/// Diagram node
#[derive(Debug, Clone)]
pub struct DiagramNode {
    pub id: String,
    pub label: String,
    pub position: Vec2,
    pub size: Vec2,
    pub shape: NodeShape,
    pub selected: bool,
    pub hovered: bool,
    pub status: Option<NodeStatus>,
    pub fill_color: Option<Color32>,
    pub stroke_color: Option<Color32>,
}

impl DiagramNode {
    pub fn new(id: impl Into<String>, label: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            label: label.into(),
            position: Vec2::ZERO,
            size: Vec2::new(120.0, 60.0),
            shape: NodeShape::Rectangle,
            selected: false,
            hovered: false,
            status: None,
            fill_color: None,
            stroke_color: None,
        }
    }

    pub fn with_position(mut self, x: f32, y: f32) -> Self {
        self.position = Vec2::new(x, y);
        self
    }

    pub fn with_size(mut self, w: f32, h: f32) -> Self {
        self.size = Vec2::new(w, h);
        self
    }

    pub fn with_shape(mut self, shape: NodeShape) -> Self {
        self.shape = shape;
        self
    }

    pub fn with_status(mut self, status: NodeStatus) -> Self {
        self.status = Some(status);
        self
    }

    pub fn rect(&self) -> Rect {
        Rect::from_min_size(Pos2::new(self.position.x, self.position.y), self.size)
    }

    pub fn center(&self) -> Pos2 {
        self.rect().center()
    }

    pub fn contains(&self, pos: Pos2) -> bool {
        self.rect().contains(pos)
    }
}

/// Arrow type for edges
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ArrowType {
    None,
    Arrow,
    Diamond,
    Circle,
}

/// Diagram edge
#[derive(Debug, Clone)]
pub struct DiagramEdge {
    pub from: String,
    pub to: String,
    pub label: Option<String>,
    pub target_arrow: ArrowType,
    pub source_arrow: ArrowType,
    pub dashed: bool,
    pub highlighted: bool,
    pub flow_progress: f32,
}

impl DiagramEdge {
    pub fn new(from: impl Into<String>, to: impl Into<String>) -> Self {
        Self {
            from: from.into(),
            to: to.into(),
            label: None,
            target_arrow: ArrowType::Arrow,
            source_arrow: ArrowType::None,
            dashed: false,
            highlighted: false,
            flow_progress: 0.0,
        }
    }

    pub fn with_label(mut self, label: impl Into<String>) -> Self {
        self.label = Some(label.into());
        self
    }

    pub fn bidirectional(mut self) -> Self {
        self.source_arrow = ArrowType::Arrow;
        self
    }

    pub fn dashed(mut self) -> Self {
        self.dashed = true;
        self
    }
}

/// Theme colors for diagram rendering
#[derive(Clone, Copy)]
pub struct DiagramTheme {
    pub canvas_bg: Color32,
    pub grid_color: Color32,
    pub node_fill: Color32,
    pub node_stroke: Color32,
    pub node_hover: Color32,
    pub node_selected: Color32,
    pub node_text: Color32,
    pub edge_color: Color32,
    pub edge_text: Color32,
    pub edge_flow: Color32,
    pub status_ready: Color32,
    pub status_running: Color32,
    pub status_success: Color32,
    pub status_error: Color32,
    pub status_warning: Color32,
    pub status_pending: Color32,
}

impl Default for DiagramTheme {
    fn default() -> Self {
        Self::dark()
    }
}

impl DiagramTheme {
    pub fn dark() -> Self {
        Self {
            canvas_bg: Color32::from_rgb(30, 30, 30),
            grid_color: Color32::from_rgba_unmultiplied(80, 80, 80, 50),
            node_fill: Color32::from_rgb(45, 45, 55),
            node_stroke: Color32::from_rgb(100, 100, 120),
            node_hover: Color32::from_rgb(80, 140, 200),
            node_selected: Color32::from_rgb(59, 165, 93),
            node_text: Color32::from_rgb(220, 220, 220),
            edge_color: Color32::from_rgb(120, 120, 140),
            edge_text: Color32::from_rgb(180, 180, 200),
            edge_flow: Color32::from_rgb(100, 180, 255),
            status_ready: Color32::from_rgb(120, 120, 140),
            status_running: Color32::from_rgb(59, 165, 93),
            status_success: Color32::from_rgb(59, 165, 93),
            status_error: Color32::from_rgb(207, 102, 121),
            status_warning: Color32::from_rgb(209, 154, 102),
            status_pending: Color32::from_rgb(180, 180, 180),
        }
    }

    pub fn status_color(&self, status: Option<NodeStatus>) -> Color32 {
        match status {
            Some(NodeStatus::Ready) => self.status_ready,
            Some(NodeStatus::Running) => self.status_running,
            Some(NodeStatus::Success) => self.status_success,
            Some(NodeStatus::Error) => self.status_error,
            Some(NodeStatus::Warning) => self.status_warning,
            Some(NodeStatus::Pending) => self.status_pending,
            None => self.status_ready,
        }
    }
}

/// Interactive D2-style diagram viewer widget
pub struct DiagramWidget {
    pub nodes: HashMap<String, DiagramNode>,
    pub edges: Vec<DiagramEdge>,
    pub theme: DiagramTheme,
    
    // View state
    pub pan: Vec2,
    pub zoom: f32,
    pub min_zoom: f32,
    pub max_zoom: f32,
    pub show_grid: bool,
    pub grid_size: f32,
    
    // Selection
    pub selected_node: Option<String>,
    hovered_node: Option<String>,
    dragging_node: Option<String>,
    
    // Animation
    animate: bool,
    animation_time: f32,
    start_time: Instant,
    
    // UI options
    pub show_toolbar: bool,
    pub show_minimap: bool,
}

impl Default for DiagramWidget {
    fn default() -> Self {
        Self::new()
    }
}

impl DiagramWidget {
    pub fn new() -> Self {
        Self {
            nodes: HashMap::new(),
            edges: Vec::new(),
            theme: DiagramTheme::dark(),
            pan: Vec2::ZERO,
            zoom: 1.0,
            min_zoom: 0.1,
            max_zoom: 5.0,
            show_grid: true,
            grid_size: 20.0,
            selected_node: None,
            hovered_node: None,
            dragging_node: None,
            animate: true,
            animation_time: 0.0,
            start_time: Instant::now(),
            show_toolbar: true,
            show_minimap: true,
        }
    }

    pub fn add_node(&mut self, node: DiagramNode) {
        self.nodes.insert(node.id.clone(), node);
    }

    pub fn add_edge(&mut self, edge: DiagramEdge) {
        self.edges.push(edge);
    }

    pub fn clear(&mut self) {
        self.nodes.clear();
        self.edges.clear();
        self.selected_node = None;
        self.hovered_node = None;
        self.fit_to_view();
    }

    pub fn fit_to_view(&mut self) {
        self.pan = Vec2::ZERO;
        self.zoom = 1.0;
    }

    /// Set status of a node
    pub fn set_node_status(&mut self, node_id: &str, status: NodeStatus) {
        if let Some(node) = self.nodes.get_mut(node_id) {
            node.status = Some(status);
        }
    }

    /// Highlight an edge for flow animation
    pub fn highlight_edge(&mut self, from: &str, to: &str, highlighted: bool) {
        for edge in &mut self.edges {
            if edge.from == from && edge.to == to {
                edge.highlighted = highlighted;
            }
        }
    }

    fn get_transform(&self, rect: Rect) -> Transform {
        Transform {
            offset: rect.center().to_vec2() + self.pan,
            zoom: self.zoom,
        }
    }

    fn screen_to_world(&self, screen_pos: Pos2, rect: Rect) -> Pos2 {
        let transform = self.get_transform(rect);
        transform.to_world(screen_pos)
    }

    fn draw_grid(&self, painter: &Painter, rect: Rect) {
        let grid_size = self.grid_size * self.zoom;
        let offset = Vec2::new(
            self.pan.x.rem_euclid(grid_size),
            self.pan.y.rem_euclid(grid_size),
        );
        
        let start = rect.min + offset;
        
        // Vertical lines
        let mut x = start.x;
        while x < rect.max.x {
            painter.line_segment(
                [Pos2::new(x, rect.min.y), Pos2::new(x, rect.max.y)],
                Stroke::new(1.0, self.theme.grid_color),
            );
            x += grid_size;
        }
        
        // Horizontal lines
        let mut y = start.y;
        while y < rect.max.y {
            painter.line_segment(
                [Pos2::new(rect.min.x, y), Pos2::new(rect.max.x, y)],
                Stroke::new(1.0, self.theme.grid_color),
            );
            y += grid_size;
        }
    }

    fn draw_node(&self, painter: &Painter, node: &DiagramNode, transform: &Transform) {
        let rect = transform.transform_rect(node.rect());
        
        let fill = node.fill_color.unwrap_or(self.theme.node_fill);
        let stroke_color = if node.selected {
            self.theme.node_selected
        } else if node.hovered || self.hovered_node.as_ref() == Some(&node.id) {
            self.theme.node_hover
        } else {
            node.stroke_color.unwrap_or(self.theme.node_stroke)
        };
        let stroke_width = if node.selected { 3.0 } else { 1.5 };

        // Draw shape
        match node.shape {
            NodeShape::Rectangle => {
                painter.rect(rect, 4.0 * self.zoom, fill, Stroke::new(stroke_width, stroke_color));
            }
            NodeShape::Circle => {
                painter.rect(rect, rect.height() / 2.0, fill, Stroke::new(stroke_width, stroke_color));
            }
            NodeShape::Diamond => {
                let center = rect.center();
                let points = vec![
                    Pos2::new(center.x, rect.min.y),
                    Pos2::new(rect.max.x, center.y),
                    Pos2::new(center.x, rect.max.y),
                    Pos2::new(rect.min.x, center.y),
                ];
                painter.add(egui::Shape::convex_polygon(points, fill, Stroke::new(stroke_width, stroke_color)));
            }
            NodeShape::Hexagon => {
                let center = rect.center();
                let w = rect.width() / 2.0;
                let _h = rect.height() / 2.0;
                let points = vec![
                    Pos2::new(center.x - w * 0.5, rect.min.y),
                    Pos2::new(center.x + w * 0.5, rect.min.y),
                    Pos2::new(rect.max.x, center.y),
                    Pos2::new(center.x + w * 0.5, rect.max.y),
                    Pos2::new(center.x - w * 0.5, rect.max.y),
                    Pos2::new(rect.min.x, center.y),
                ];
                painter.add(egui::Shape::convex_polygon(points, fill, Stroke::new(stroke_width, stroke_color)));
            }
            NodeShape::Cylinder => {
                let cap_height = rect.height() * 0.15;
                let body_rect = Rect::from_min_max(
                    Pos2::new(rect.min.x, rect.min.y + cap_height * 0.5),
                    Pos2::new(rect.max.x, rect.max.y - cap_height * 0.5),
                );
                painter.rect_filled(body_rect, 0.0, fill);
                painter.rect_stroke(body_rect, 0.0, Stroke::new(stroke_width, stroke_color));
            }
            NodeShape::Cloud => {
                painter.rect(rect, rect.height() * 0.3, fill, Stroke::new(stroke_width, stroke_color));
            }
            NodeShape::Document => {
                painter.rect(rect, 2.0, fill, Stroke::new(stroke_width, stroke_color));
            }
        }

        // Status indicator
        if let Some(status) = node.status {
            let indicator_pos = Pos2::new(rect.max.x - 8.0, rect.min.y + 8.0);
            let status_color = self.theme.status_color(Some(status));
            painter.circle_filled(indicator_pos, 5.0, status_color);
        }

        // Label
        let font_size = 14.0 * self.zoom;
        painter.text(
            rect.center(),
            Align2::CENTER_CENTER,
            &node.label,
            FontId::proportional(font_size),
            self.theme.node_text,
        );
    }

    fn draw_edge(&self, painter: &Painter, edge: &DiagramEdge, transform: &Transform) {
        let from_node = match self.nodes.get(&edge.from) {
            Some(n) => n,
            None => return,
        };
        let to_node = match self.nodes.get(&edge.to) {
            Some(n) => n,
            None => return,
        };

        let from_rect = transform.transform_rect(from_node.rect());
        let to_rect = transform.transform_rect(to_node.rect());

        let (start, end) = self.calculate_edge_points(&from_rect, &to_rect);

        let color = if edge.highlighted {
            self.theme.edge_flow
        } else {
            self.theme.edge_color
        };
        let stroke_width = if edge.highlighted { 2.5 } else { 1.5 };

        if edge.dashed {
            self.draw_dashed_line(painter, start, end, stroke_width, color, 8.0 * self.zoom);
        } else {
            painter.line_segment([start, end], Stroke::new(stroke_width, color));
        }

        // Arrows
        if edge.target_arrow != ArrowType::None {
            self.draw_arrow(painter, start, end, color);
        }
        if edge.source_arrow != ArrowType::None {
            self.draw_arrow(painter, end, start, color);
        }

        // Label
        if let Some(ref label) = edge.label {
            let mid = Pos2::new((start.x + end.x) / 2.0, (start.y + end.y) / 2.0);
            let font = FontId::proportional(12.0 * self.zoom);
            
            let galley = painter.layout_no_wrap(label.clone(), font.clone(), self.theme.edge_text);
            let label_rect = Rect::from_center_size(mid, galley.size() + Vec2::splat(4.0));
            painter.rect_filled(label_rect, 2.0, self.theme.canvas_bg);
            painter.text(mid, Align2::CENTER_CENTER, label, font, self.theme.edge_text);
        }

        // Flow animation
        if edge.highlighted && self.animate {
            let t = (self.animation_time * 0.5 + edge.flow_progress).fract();
            let dir = end - start;
            let pos = start + dir * t;
            painter.circle_filled(pos, 4.0 * self.zoom, self.theme.edge_flow);
        }
    }

    fn calculate_edge_points(&self, from: &Rect, to: &Rect) -> (Pos2, Pos2) {
        let from_center = from.center();
        let to_center = to.center();
        
        let start = self.rect_intersection(from, from_center, to_center);
        let end = self.rect_intersection(to, to_center, from_center);
        
        (start, end)
    }

    fn rect_intersection(&self, rect: &Rect, inside: Pos2, outside: Pos2) -> Pos2 {
        let dir = outside - inside;
        let mut t = f32::MAX;
        
        if dir.x != 0.0 {
            let t_left = (rect.left() - inside.x) / dir.x;
            let t_right = (rect.right() - inside.x) / dir.x;
            if t_left > 0.0 { t = t.min(t_left); }
            if t_right > 0.0 { t = t.min(t_right); }
        }
        if dir.y != 0.0 {
            let t_top = (rect.top() - inside.y) / dir.y;
            let t_bottom = (rect.bottom() - inside.y) / dir.y;
            if t_top > 0.0 { t = t.min(t_top); }
            if t_bottom > 0.0 { t = t.min(t_bottom); }
        }
        
        if t == f32::MAX { inside } else { inside + dir * t }
    }

    fn draw_arrow(&self, painter: &Painter, from: Pos2, to: Pos2, color: Color32) {
        let dir = (from - to).normalized();
        let size = 10.0 * self.zoom;
        let angle: f32 = 0.4;
        
        let left = to + Vec2::new(
            dir.x * angle.cos() - dir.y * angle.sin(),
            dir.x * angle.sin() + dir.y * angle.cos(),
        ) * size;
        
        let right = to + Vec2::new(
            dir.x * angle.cos() + dir.y * angle.sin(),
            -dir.x * angle.sin() + dir.y * angle.cos(),
        ) * size;
        
        let points = vec![to, left, right];
        painter.add(egui::Shape::convex_polygon(points, color, Stroke::NONE));
    }

    fn draw_dashed_line(&self, painter: &Painter, start: Pos2, end: Pos2, width: f32, color: Color32, dash_len: f32) {
        let dir = end - start;
        let len = dir.length();
        let dir = dir / len;
        
        let mut pos = 0.0;
        let mut drawing = true;
        
        while pos < len {
            let next = (pos + dash_len).min(len);
            if drawing {
                let p1 = start + dir * pos;
                let p2 = start + dir * next;
                painter.line_segment([p1, p2], Stroke::new(width, color));
            }
            pos = next;
            drawing = !drawing;
        }
    }

    fn draw_toolbar(&mut self, ui: &mut egui::Ui, rect: Rect) {
        let toolbar_rect = Rect::from_min_size(
            rect.min + Vec2::new(10.0, 10.0),
            Vec2::new(200.0, 30.0),
        );
        
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(toolbar_rect), |ui| {
            ui.horizontal(|ui| {
                if ui.small_button("⊞").on_hover_text("Fit to view (F)").clicked() {
                    self.fit_to_view();
                }
                if ui.small_button("▦").on_hover_text("Toggle grid (G)").clicked() {
                    self.show_grid = !self.show_grid;
                }
                ui.label(format!("{:.0}%", self.zoom * 100.0));
            });
        });
    }

    fn draw_minimap(&self, painter: &Painter, rect: Rect) {
        let minimap_size = Vec2::new(150.0, 100.0);
        let minimap_rect = Rect::from_min_size(
            Pos2::new(rect.max.x - minimap_size.x - 10.0, rect.max.y - minimap_size.y - 10.0),
            minimap_size,
        );
        
        painter.rect_filled(minimap_rect, 4.0, Color32::from_rgba_unmultiplied(0, 0, 0, 150));
        painter.rect_stroke(minimap_rect, 4.0, Stroke::new(1.0, self.theme.node_stroke));
        
        // Draw nodes in minimap
        let bounds = self.calculate_bounds();
        let scale = if bounds.width() > 0.0 && bounds.height() > 0.0 {
            (minimap_size.x / bounds.width())
                .min(minimap_size.y / bounds.height())
                * 0.8
        } else {
            1.0
        };
        
        for node in self.nodes.values() {
            let node_offset = Vec2::new(
                node.position.x - bounds.min.x,
                node.position.y - bounds.min.y,
            );
            let node_rect = Rect::from_min_size(
                minimap_rect.min + node_offset * scale + Vec2::splat(10.0),
                node.size * scale,
            );
            
            let color = if Some(&node.id) == self.selected_node.as_ref() {
                self.theme.node_selected
            } else {
                self.theme.node_fill
            };
            
            painter.rect_filled(node_rect, 1.0, color);
        }
    }

    fn calculate_bounds(&self) -> Rect {
        let mut min = Pos2::new(f32::MAX, f32::MAX);
        let mut max = Pos2::new(f32::MIN, f32::MIN);
        
        for node in self.nodes.values() {
            let rect = node.rect();
            min.x = min.x.min(rect.min.x);
            min.y = min.y.min(rect.min.y);
            max.x = max.x.max(rect.max.x);
            max.y = max.y.max(rect.max.y);
        }
        
        if min.x == f32::MAX {
            return Rect::from_min_size(Pos2::ZERO, Vec2::new(100.0, 100.0));
        }
        
        Rect::from_min_max(min, max)
    }

    fn handle_input(&mut self, ui: &egui::Ui, response: &egui::Response) {
        let input = ui.input(|i| i.clone());
        
        // Zoom with scroll wheel
        if response.hovered() {
            let scroll_delta = input.smooth_scroll_delta.y;
            if scroll_delta != 0.0 {
                let zoom_delta = 1.0 + scroll_delta * 0.001;
                self.zoom = (self.zoom * zoom_delta).clamp(self.min_zoom, self.max_zoom);
            }
        }
        
        // Pan with middle mouse or right mouse
        if response.dragged_by(egui::PointerButton::Middle) || 
           response.dragged_by(egui::PointerButton::Secondary) {
            self.pan += response.drag_delta();
        }
        
        // Node interaction
        if let Some(pos) = response.interact_pointer_pos() {
            let world_pos = self.screen_to_world(pos, response.rect);
            
            // Update hover state
            self.hovered_node = None;
            for (id, node) in &self.nodes {
                if node.contains(world_pos) {
                    self.hovered_node = Some(id.clone());
                    break;
                }
            }
            
            // Handle click
            if response.clicked() {
                // Clear all selections
                for node in self.nodes.values_mut() {
                    node.selected = false;
                }
                
                if let Some(ref hovered) = self.hovered_node {
                    if let Some(node) = self.nodes.get_mut(hovered) {
                        node.selected = true;
                        self.selected_node = Some(hovered.clone());
                    }
                } else {
                    self.selected_node = None;
                }
            }
            
            // Node dragging
            if response.drag_started_by(egui::PointerButton::Primary) {
                if let Some(ref hovered) = self.hovered_node {
                    self.dragging_node = Some(hovered.clone());
                }
            }
            
            if response.dragged_by(egui::PointerButton::Primary) {
                if let Some(ref dragging) = self.dragging_node.clone() {
                    let delta = response.drag_delta() / self.zoom;
                    if let Some(node) = self.nodes.get_mut(dragging) {
                        node.position += delta;
                    }
                }
            }
            
            if response.drag_stopped() {
                self.dragging_node = None;
            }
        }
        
        // Keyboard shortcuts
        if response.has_focus() || response.hovered() {
            if input.key_pressed(egui::Key::F) {
                self.fit_to_view();
            }
            if input.key_pressed(egui::Key::G) {
                self.show_grid = !self.show_grid;
            }
        }
    }
}

impl Widget for DiagramWidget {
    fn ui(&mut self, ui: &mut egui::Ui) -> Result<Vec<WidgetEvent>> {
        // Update animation
        if self.animate {
            self.animation_time = self.start_time.elapsed().as_secs_f32();
            ui.ctx().request_repaint();
        }
        
        let available_size = ui.available_size();
        let (response, painter) = ui.allocate_painter(available_size, Sense::click_and_drag());
        let rect = response.rect;
        
        // Background
        painter.rect_filled(rect, 0.0, self.theme.canvas_bg);
        
        // Grid
        if self.show_grid {
            self.draw_grid(&painter, rect);
        }
        
        // Handle input
        self.handle_input(ui, &response);
        
        // Transform
        let transform = self.get_transform(rect);
        
        // Draw edges
        for edge in &self.edges {
            self.draw_edge(&painter, edge, &transform);
        }
        
        // Draw nodes
        for node in self.nodes.values() {
            self.draw_node(&painter, node, &transform);
        }
        
        // Toolbar
        if self.show_toolbar {
            self.draw_toolbar(ui, rect);
        }
        
        // Minimap
        if self.show_minimap {
            self.draw_minimap(&painter, rect);
        }
        
        Ok(vec![])
    }

    fn title(&self) -> String {
        "Diagram".to_string()
    }

    fn id(&self) -> String {
        "diagram_widget".to_string()
    }
}

/// Coordinate transformation helper
struct Transform {
    offset: Vec2,
    zoom: f32,
}

impl Transform {
    fn to_screen(&self, world: Pos2) -> Pos2 {
        Pos2::new(
            world.x * self.zoom + self.offset.x,
            world.y * self.zoom + self.offset.y,
        )
    }
    
    fn to_world(&self, screen: Pos2) -> Pos2 {
        Pos2::new(
            (screen.x - self.offset.x) / self.zoom,
            (screen.y - self.offset.y) / self.zoom,
        )
    }
    
    fn transform_rect(&self, rect: Rect) -> Rect {
        Rect::from_min_max(
            self.to_screen(rect.min),
            self.to_screen(rect.max),
        )
    }
}

