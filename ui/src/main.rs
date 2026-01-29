//! Continuum Studio - AI Orchestration Interface
//!
//! A modular, widget-based desktop application for orchestrating
//! AI agents across multiple applications via Synapsix harnesses.

use continuum_studio_ui::{
    theme::Theme,
    widgets::{
        AgentStreamWidget, HarnessPanelWidget, Widget,
        TabBarWidget, Tab, TabType,
        DiagramWidget, DiagramNode, DiagramEdge, NodeShape, NodeStatus,
        CodeViewWidget, Language,
        TerminalWidget, AnsiColor,
    },
};
use eframe::egui::{self, Color32, RichText};
use tracing::info;
use tracing_subscriber::EnvFilter;

/// Application state
struct ContinuumStudio {
    /// Current theme
    theme: Theme,
    
    /// Tab bar for managing views
    tab_bar: TabBarWidget,
    
    /// Harness panel widget (left sidebar)
    harness_panel: HarnessPanelWidget,
    
    /// Agent stream widget
    agent_stream: AgentStreamWidget,
    
    /// Demo diagram widget
    diagram_widget: DiagramWidget,
    
    /// Demo code widget
    code_widget: CodeViewWidget,
    
    /// Demo terminal widget  
    terminal_widget: TerminalWidget,
    
    /// IPC connection state (simulated)
    ipc_connected: bool,
    
    /// Frame counter for demo
    frame_count: u64,
    
    /// Whether left sidebar is visible
    show_sidebar: bool,
}

impl ContinuumStudio {
    fn new(_cc: &eframe::CreationContext<'_>) -> Self {
        info!("Initializing Continuum Studio");
        
        // Create tab bar with initial tabs
        let mut tab_bar = TabBarWidget::new();
        tab_bar.add_tab(Tab::agent_stream());
        tab_bar.add_tab(Tab::diagram("architecture"));
        tab_bar.add_tab(Tab::code_view("demo.rs"));
        tab_bar.add_tab(Tab::terminal("1"));
        
        // Create demo diagram
        let mut diagram_widget = DiagramWidget::new();
        
        // Add nodes representing the Continuum Studio architecture
        diagram_widget.add_node(
            DiagramNode::new("studio_ui", "Studio UI")
                .with_position(0.0, 0.0)
                .with_size(120.0, 50.0)
                .with_shape(NodeShape::Rectangle)
                .with_status(NodeStatus::Running)
        );
        diagram_widget.add_node(
            DiagramNode::new("studio_core", "Studio Core")
                .with_position(0.0, 100.0)
                .with_size(120.0, 50.0)
                .with_shape(NodeShape::Rectangle)
                .with_status(NodeStatus::Ready)
        );
        diagram_widget.add_node(
            DiagramNode::new("synapsix", "Synapsix")
                .with_position(150.0, 100.0)
                .with_size(120.0, 50.0)
                .with_shape(NodeShape::Hexagon)
                .with_status(NodeStatus::Running)
        );
        diagram_widget.add_node(
            DiagramNode::new("agent_bridge", "Agent Bridge")
                .with_position(300.0, 100.0)
                .with_size(120.0, 50.0)
                .with_shape(NodeShape::Rectangle)
                .with_status(NodeStatus::Pending)
        );
        diagram_widget.add_node(
            DiagramNode::new("dialog_daemon", "Dialog Daemon")
                .with_position(150.0, 200.0)
                .with_size(120.0, 50.0)
                .with_shape(NodeShape::Cylinder)
                .with_status(NodeStatus::Running)
        );
        
        // Add edges
        diagram_widget.add_edge(DiagramEdge::new("studio_ui", "studio_core").with_label("IPC"));
        diagram_widget.add_edge(DiagramEdge::new("studio_core", "synapsix").with_label("ETF"));
        diagram_widget.add_edge(DiagramEdge::new("synapsix", "agent_bridge").with_label("gRPC"));
        diagram_widget.add_edge(DiagramEdge::new("synapsix", "dialog_daemon").with_label("D-Bus"));
        
        // Create demo code widget
        let mut code_widget = CodeViewWidget::new().with_language(Language::Rust);
        code_widget.set_content(r#"//! Continuum Studio - Demo Code

use synapsix::Harness;
use studio_core::IpcClient;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    // Initialize the harness connection
    let harness = Harness::connect("cursor").await?;
    
    // Send a command to the IDE
    harness.execute_action(Action::OpenFile {
        path: "/home/user/project/main.rs".into(),
        line: Some(42),
    }).await?;
    
    // Wait for completion
    let result = harness.wait_for_response().await?;
    println!("Result: {:?}", result);
    
    Ok(())
}
"#);
        
        // Create demo terminal widget
        let mut terminal_widget = TerminalWidget::new();
        terminal_widget.write_info("Welcome to Continuum Studio Terminal");
        terminal_widget.write_plain("$ cargo build --release");
        terminal_widget.write_success("   Compiling continuum-studio v0.1.0");
        terminal_widget.write_success("   Finished release [optimized] target");
        terminal_widget.write_plain("$ ");
        
        Self {
            theme: Theme::dark(),
            tab_bar,
            harness_panel: HarnessPanelWidget::new(),
            agent_stream: AgentStreamWidget::new(),
            diagram_widget,
            code_widget,
            terminal_widget,
            ipc_connected: false,
            frame_count: 0,
            show_sidebar: true,
        }
    }
    
    fn render_active_tab_content(&mut self, ui: &mut egui::Ui) {
        match self.tab_bar.active_tab_type() {
            Some(TabType::Dashboard) => {
                ui.heading("Dashboard");
                ui.separator();
                ui.label("Welcome to Continuum Studio!");
                ui.add_space(20.0);
                ui.label("Quick actions:");
                ui.horizontal(|ui| {
                    if ui.button("🎛️ Open Harnesses").clicked() {
                        self.tab_bar.add_tab(Tab::harness_panel());
                    }
                    if ui.button("💬 Agent Stream").clicked() {
                        self.tab_bar.add_tab(Tab::agent_stream());
                    }
                    if ui.button("📊 Diagram").clicked() {
                        self.tab_bar.add_tab(Tab::diagram("new"));
                    }
                });
            }
            Some(TabType::AgentStream) => {
                let _ = self.agent_stream.ui(ui);
            }
            Some(TabType::HarnessPanel) => {
                let _ = self.harness_panel.ui(ui);
            }
            Some(TabType::Diagram { .. }) => {
                let _ = self.diagram_widget.ui(ui);
            }
            Some(TabType::CodeView { .. }) => {
                let _ = self.code_widget.ui(ui);
            }
            Some(TabType::Terminal { .. }) => {
                let _ = self.terminal_widget.ui(ui);
            }
            Some(TabType::Settings) => {
                ui.heading("Settings");
                ui.separator();
                ui.checkbox(&mut self.show_sidebar, "Show sidebar");
                ui.add_space(10.0);
                ui.label("Theme:");
                ui.horizontal(|ui| {
                    if ui.button("Dark").clicked() {
                        self.theme = Theme::dark();
                    }
                    if ui.button("Light").clicked() {
                        self.theme = Theme::light();
                    }
                });
            }
            _ => {
                ui.label("Unknown tab type");
            }
        }
    }
}

impl eframe::App for ContinuumStudio {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.frame_count += 1;
        
        // Apply theme
        self.theme.apply_to_ctx(ctx);
        
        // Simulate IPC connection toggle every 120 frames
        if self.frame_count % 120 == 0 {
            self.ipc_connected = !self.ipc_connected;
        }
        
        // Simulate terminal output
        if self.frame_count % 300 == 0 {
            self.terminal_widget.write_plain(&format!("$ echo 'Frame {}'", self.frame_count));
            self.terminal_widget.write_plain(&format!("Frame {}", self.frame_count));
        }
        
        // Top panel - title bar / status
        egui::TopBottomPanel::top("top_panel")
            .frame(egui::Frame::none().fill(self.theme.activitybar_bg).inner_margin(8.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.heading(RichText::new("◈ Continuum Studio").color(self.theme.accent));
                    ui.separator();
                    
                    // Connection status indicator
                    let (status_icon, status_text, status_color) = if self.ipc_connected {
                        ("●", "Core Connected", self.theme.success)
                    } else {
                        ("○", "Core Disconnected", self.theme.warning)
                    };
                    ui.colored_label(status_color, format!("{} {}", status_icon, status_text));
                    
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(RichText::new(format!("v{}", env!("CARGO_PKG_VERSION"))).color(self.theme.fg_dim));
                        
                        // Settings button
                        if ui.button("⚙").clicked() {
                            self.tab_bar.add_tab(Tab::settings());
                        }
                    });
                });
            });
        
        // Bottom panel - status bar
        egui::TopBottomPanel::bottom("status_bar")
            .frame(egui::Frame::none().fill(self.theme.statusbar_bg).inner_margin(4.0))
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(RichText::new("Ready").color(Color32::WHITE));
                    ui.separator();
                    ui.label(RichText::new(format!("Frame: {}", self.frame_count)).color(Color32::WHITE));
                    
                    if let Some(tab) = self.tab_bar.active_tab() {
                        ui.separator();
                        ui.label(RichText::new(&tab.title).color(Color32::WHITE));
                    }
                    
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.label(RichText::new("KDE/Wayland").color(Color32::WHITE));
                    });
                });
            });
        
        // Left sidebar - Harnesses (collapsible)
        if self.show_sidebar {
            egui::SidePanel::left("harness_sidebar")
                .default_width(200.0)
                .min_width(150.0)
                .max_width(350.0)
                .frame(egui::Frame::none().fill(self.theme.sidebar_bg).inner_margin(8.0))
                .show(ctx, |ui| {
                    // Header
                    ui.horizontal(|ui| {
                        ui.colored_label(self.theme.accent, "◆");
                        ui.label(RichText::new("Harnesses").strong());
                    });
                    ui.separator();
                    
                    // Content
                    egui::ScrollArea::vertical()
                        .auto_shrink([false, false])
                        .show(ui, |ui| {
                            let _ = self.harness_panel.ui(ui);
                        });
                });
        }
        
        // Main content area with tabs
        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(self.theme.bg).inner_margin(0.0))
            .show(ctx, |ui| {
                // Tab bar at top
                egui::Frame::none()
                    .fill(self.theme.sidebar_bg)
                    .show(ui, |ui| {
                        let _ = self.tab_bar.ui(ui);
                    });
                
                ui.separator();
                
                // Tab content
                egui::Frame::none()
                    .fill(self.theme.bg)
                    .inner_margin(8.0)
                    .show(ui, |ui| {
                        egui::ScrollArea::both()
                            .auto_shrink([false, false])
                            .show(ui, |ui| {
                                self.render_active_tab_content(ui);
                            });
                    });
            });
        
        // Request continuous repaint for demo animations
        ctx.request_repaint();
    }
}

fn main() -> anyhow::Result<()> {
    // Initialize logging
    let filter = EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| EnvFilter::new("info"));
    
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .init();
    
    info!("Starting Continuum Studio v{}", env!("CARGO_PKG_VERSION"));
    
    // Configure native options
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1200.0, 800.0])
            .with_min_inner_size([800.0, 600.0])
            .with_title("Continuum Studio"),
        ..Default::default()
    };
    
    // Run the application
    eframe::run_native(
        "Continuum Studio",
        options,
        Box::new(|cc| Ok(Box::new(ContinuumStudio::new(cc)))),
    ).map_err(|e| anyhow::anyhow!("Failed to run application: {}", e))?;
    
    Ok(())
}
