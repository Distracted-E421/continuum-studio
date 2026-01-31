//! Tab Bar Widget
//!
//! A tabbed interface for managing multiple widget views.

use eframe::egui::{self, Color32, CursorIcon, Pos2, Rect, RichText, Rounding, Stroke, Vec2};
use anyhow::Result;
use super::{Widget, WidgetEvent};

/// A tab in the tab bar
#[derive(Debug, Clone)]
pub struct Tab {
    /// Unique identifier
    pub id: String,
    /// Display title
    pub title: String,
    /// Icon (optional emoji or symbol)
    pub icon: Option<String>,
    /// Whether the tab can be closed
    pub closable: bool,
    /// Whether the tab has unsaved changes
    pub modified: bool,
    /// Tab type for routing
    pub tab_type: TabType,
}

/// Types of tabs that can be opened
#[derive(Debug, Clone, PartialEq)]
pub enum TabType {
    /// Dashboard/home view
    Dashboard,
    /// Agent conversation stream
    AgentStream,
    /// Harness control panel
    HarnessPanel,
    /// Code editor/viewer
    CodeView { file_path: String },
    /// Terminal output
    Terminal { session_id: String },
    /// D2 Diagram viewer
    Diagram { diagram_id: String },
    /// Cursor Orchestrator - multi-instance management
    Orchestrator,
    /// Settings page
    Settings,
    /// Custom widget
    Custom { widget_type: String },
}

impl Tab {
    pub fn dashboard() -> Self {
        Self {
            id: "dashboard".to_string(),
            title: "Dashboard".to_string(),
            icon: Some("🏠".to_string()),
            closable: false,
            modified: false,
            tab_type: TabType::Dashboard,
        }
    }

    pub fn agent_stream() -> Self {
        Self {
            id: "agent_stream".to_string(),
            title: "Agent Stream".to_string(),
            icon: Some("💬".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::AgentStream,
        }
    }

    pub fn harness_panel() -> Self {
        Self {
            id: "harness_panel".to_string(),
            title: "Harnesses".to_string(),
            icon: Some("🎛️".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::HarnessPanel,
        }
    }

    pub fn code_view(file_path: impl Into<String>) -> Self {
        let path = file_path.into();
        let title = path.rsplit('/').next().unwrap_or(&path).to_string();
        Self {
            id: format!("code_{}", path.replace('/', "_")),
            title,
            icon: Some("📄".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::CodeView { file_path: path },
        }
    }

    pub fn terminal(session_id: impl Into<String>) -> Self {
        let session = session_id.into();
        Self {
            id: format!("term_{}", session),
            title: format!("Terminal {}", session),
            icon: Some("🖥️".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::Terminal { session_id: session },
        }
    }

    pub fn diagram(diagram_id: impl Into<String>) -> Self {
        let id = diagram_id.into();
        Self {
            id: format!("diagram_{}", id),
            title: format!("Diagram: {}", id),
            icon: Some("📊".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::Diagram { diagram_id: id },
        }
    }

    pub fn settings() -> Self {
        Self {
            id: "settings".to_string(),
            title: "Settings".to_string(),
            icon: Some("⚙️".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::Settings,
        }
    }

    pub fn orchestrator() -> Self {
        Self {
            id: "orchestrator".to_string(),
            title: "Orchestrator".to_string(),
            icon: Some("🎯".to_string()),
            closable: true,
            modified: false,
            tab_type: TabType::Orchestrator,
        }
    }
}

/// Theme colors for the tab bar
#[derive(Clone, Copy)]
pub struct TabBarTheme {
    pub background: Color32,
    pub tab_bg: Color32,
    pub tab_active_bg: Color32,
    pub tab_hover_bg: Color32,
    pub tab_text: Color32,
    pub tab_text_dim: Color32,
    pub tab_border: Color32,
    pub accent: Color32,
    pub close_hover: Color32,
    pub modified_dot: Color32,
}

impl Default for TabBarTheme {
    fn default() -> Self {
        Self::dark()
    }
}

impl TabBarTheme {
    pub fn dark() -> Self {
        Self {
            background: Color32::from_rgb(37, 37, 38),
            tab_bg: Color32::from_rgb(45, 45, 46),
            tab_active_bg: Color32::from_rgb(30, 30, 30),
            tab_hover_bg: Color32::from_rgb(50, 50, 51),
            tab_text: Color32::from_rgb(204, 204, 204),
            tab_text_dim: Color32::from_rgb(128, 128, 128),
            tab_border: Color32::from_rgb(60, 60, 60),
            accent: Color32::from_rgb(59, 165, 93),
            close_hover: Color32::from_rgb(207, 102, 121),
            modified_dot: Color32::from_rgb(209, 154, 102),
        }
    }
}

/// Tab bar widget for managing multiple views
pub struct TabBarWidget {
    /// All tabs
    tabs: Vec<Tab>,
    /// Currently active tab index
    active_index: usize,
    /// Tab bar theme
    theme: TabBarTheme,
    /// Tab width
    tab_width: f32,
    /// Tab height
    tab_height: f32,
    /// Whether to show close buttons
    show_close_buttons: bool,
    /// Tab to close (deferred to avoid borrow issues)
    pending_close: Option<usize>,
    /// Tab to switch to
    pending_switch: Option<usize>,
}

impl Default for TabBarWidget {
    fn default() -> Self {
        Self::new()
    }
}

impl TabBarWidget {
    pub fn new() -> Self {
        Self {
            tabs: vec![Tab::dashboard()],
            active_index: 0,
            theme: TabBarTheme::dark(),
            tab_width: 150.0,
            tab_height: 32.0,
            show_close_buttons: true,
            pending_close: None,
            pending_switch: None,
        }
    }

    pub fn with_tabs(mut self, tabs: Vec<Tab>) -> Self {
        self.tabs = tabs;
        if self.tabs.is_empty() {
            self.tabs.push(Tab::dashboard());
        }
        self
    }

    pub fn add_tab(&mut self, tab: Tab) {
        // Check if tab already exists
        if let Some(idx) = self.tabs.iter().position(|t| t.id == tab.id) {
            // Switch to existing tab
            self.active_index = idx;
        } else {
            // Add new tab and switch to it
            self.tabs.push(tab);
            self.active_index = self.tabs.len() - 1;
        }
    }

    pub fn close_tab(&mut self, index: usize) {
        if index < self.tabs.len() && self.tabs[index].closable {
            self.tabs.remove(index);
            // Adjust active index
            if self.active_index >= self.tabs.len() && self.active_index > 0 {
                self.active_index = self.tabs.len() - 1;
            }
        }
    }

    pub fn active_tab(&self) -> Option<&Tab> {
        self.tabs.get(self.active_index)
    }

    pub fn active_tab_type(&self) -> Option<&TabType> {
        self.active_tab().map(|t| &t.tab_type)
    }

    pub fn set_tab_modified(&mut self, tab_id: &str, modified: bool) {
        if let Some(tab) = self.tabs.iter_mut().find(|t| t.id == tab_id) {
            tab.modified = modified;
        }
    }

    pub fn tabs(&self) -> &[Tab] {
        &self.tabs
    }

    fn draw_tab(&self, ui: &mut egui::Ui, tab: &Tab, index: usize) -> (bool, bool) {
        let is_active = index == self.active_index;
        let mut clicked = false;
        let mut close_clicked = false;

        let tab_rect = ui.available_rect_before_wrap();
        let tab_rect = Rect::from_min_size(tab_rect.min, Vec2::new(self.tab_width, self.tab_height));

        // Allocate space
        let (rect, response) = ui.allocate_exact_size(
            Vec2::new(self.tab_width, self.tab_height),
            egui::Sense::click(),
        );

        // Determine background color
        let bg_color = if is_active {
            self.theme.tab_active_bg
        } else if response.hovered() {
            self.theme.tab_hover_bg
        } else {
            self.theme.tab_bg
        };

        // Draw background
        ui.painter().rect_filled(rect, Rounding::ZERO, bg_color);

        // Draw active indicator (top border)
        if is_active {
            ui.painter().hline(
                rect.x_range(),
                rect.top(),
                Stroke::new(2.0, self.theme.accent),
            );
        }

        // Draw tab content
        let text_color = if is_active {
            self.theme.tab_text
        } else {
            self.theme.tab_text_dim
        };

        // Icon + title
        let icon_text = tab.icon.as_deref().unwrap_or("");
        let title_text = if tab.title.len() > 15 {
            format!("{}…", &tab.title[..14])
        } else {
            tab.title.clone()
        };

        let display_text = if icon_text.is_empty() {
            title_text
        } else {
            format!("{} {}", icon_text, title_text)
        };

        let text_pos = Pos2::new(rect.min.x + 8.0, rect.center().y);
        ui.painter().text(
            text_pos,
            egui::Align2::LEFT_CENTER,
            &display_text,
            egui::FontId::proportional(12.0),
            text_color,
        );

        // Modified indicator
        if tab.modified {
            let dot_pos = Pos2::new(rect.max.x - 24.0, rect.center().y);
            ui.painter().circle_filled(dot_pos, 4.0, self.theme.modified_dot);
        }

        // Close button
        if self.show_close_buttons && tab.closable {
            let close_rect = Rect::from_center_size(
                Pos2::new(rect.max.x - 12.0, rect.center().y),
                Vec2::splat(16.0),
            );
            let close_response = ui.interact(
                close_rect,
                ui.id().with(("close", index)),
                egui::Sense::click(),
            );

            let close_color = if close_response.hovered() {
                self.theme.close_hover
            } else {
                self.theme.tab_text_dim
            };

            ui.painter().text(
                close_rect.center(),
                egui::Align2::CENTER_CENTER,
                "×",
                egui::FontId::proportional(14.0),
                close_color,
            );

            if close_response.clicked() {
                close_clicked = true;
            }

            if close_response.hovered() {
                ui.ctx().set_cursor_icon(CursorIcon::PointingHand);
            }
        }

        // Handle tab click
        if response.clicked() && !close_clicked {
            clicked = true;
        }

        if response.hovered() && !close_clicked {
            ui.ctx().set_cursor_icon(CursorIcon::PointingHand);
        }

        (clicked, close_clicked)
    }
}

impl Widget for TabBarWidget {
    fn ui(&mut self, ui: &mut egui::Ui) -> Result<Vec<WidgetEvent>> {
        let mut events = Vec::new();

        // Draw tab bar background
        let bar_rect = ui.available_rect_before_wrap();
        let bar_rect = Rect::from_min_size(bar_rect.min, Vec2::new(bar_rect.width(), self.tab_height));
        ui.painter().rect_filled(bar_rect, Rounding::ZERO, self.theme.background);

        // Draw tabs
        ui.horizontal(|ui| {
            for (index, tab) in self.tabs.iter().enumerate() {
                let (clicked, close_clicked) = self.draw_tab(ui, tab, index);

                if clicked {
                    self.pending_switch = Some(index);
                }

                if close_clicked {
                    self.pending_close = Some(index);
                }
            }

            // Add new tab button
            let add_response = ui.add_sized(
                Vec2::new(28.0, self.tab_height),
                egui::Button::new(RichText::new("+").size(16.0)).frame(false),
            );

            if add_response.clicked() {
                events.push(WidgetEvent::OpenWidget {
                    widget_type: "new_tab".to_string(),
                    config: serde_json::json!({}),
                });
            }
        });

        // Process pending operations
        if let Some(index) = self.pending_switch.take() {
            if index != self.active_index {
                self.active_index = index;
            }
        }

        if let Some(index) = self.pending_close.take() {
            self.close_tab(index);
            events.push(WidgetEvent::CloseWidget(
                self.tabs.get(index).map(|t| t.id.clone()).unwrap_or_default(),
            ));
        }

        // Draw bottom border
        ui.painter().hline(
            bar_rect.x_range(),
            bar_rect.bottom(),
            Stroke::new(1.0, self.theme.tab_border),
        );

        Ok(events)
    }

    fn title(&self) -> String {
        "Tab Bar".to_string()
    }

    fn id(&self) -> String {
        "tab_bar".to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tab_creation() {
        let tab = Tab::dashboard();
        assert_eq!(tab.id, "dashboard");
        assert!(!tab.closable);

        let tab = Tab::code_view("/home/user/test.rs");
        assert!(tab.title.contains("test.rs"));
        assert!(tab.closable);
    }

    #[test]
    fn test_tab_bar_operations() {
        let mut bar = TabBarWidget::new();
        assert_eq!(bar.tabs().len(), 1); // Dashboard

        bar.add_tab(Tab::agent_stream());
        assert_eq!(bar.tabs().len(), 2);
        assert_eq!(bar.active_index, 1);

        bar.close_tab(1);
        assert_eq!(bar.tabs().len(), 1);
        assert_eq!(bar.active_index, 0);
    }
}

