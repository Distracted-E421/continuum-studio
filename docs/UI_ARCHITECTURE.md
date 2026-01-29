# Continuum Studio UI Architecture

This document describes the architecture of the Continuum Studio User Interface, built with Rust and egui.

## Overview

The UI is designed as a **modular widget system** that communicates with the Studio Core (Elixir) backend via IPC. Key design principles:

1. **Widget Independence**: Each widget is self-contained and communicates via events
2. **Theme Consistency**: VS Code-compatible theming across all components
3. **Platform Native**: Wayland/X11 support via eframe
4. **Test-Driven**: Built-in test harness for UI development

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│  │ TabBar  │ │ Diagram │ │Terminal │ │CodeView │ ...      │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘          │
│       └───────────┴───────────┴───────────┘                │
│                           │                                 │
├───────────────────────────┼─────────────────────────────────┤
│                    Widget Runtime                           │
│  ┌──────────────────────────────────────────────────────┐  │
│  │                  Event Bus                            │  │
│  │  WidgetEvent::AgentMessage, DiagramNodeSelected, ... │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
├───────────────────────────┼─────────────────────────────────┤
│                    IPC Layer                                │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │
│  │ Unix Socket │ │ D-Bus/CLI   │ │ WebSocket   │          │
│  │ (Core)      │ │ (Dialog)    │ │ (Future)    │          │
│  └─────────────┘ └─────────────┘ └─────────────┘          │
│                           │                                 │
└───────────────────────────┼─────────────────────────────────┘
                            ▼
              ┌───────────────────────────┐
              │      Studio Core          │
              │      (Elixir/BEAM)        │
              └───────────────────────────┘
```

## Module Structure

```
continuum-studio-ui/
├── src/
│   ├── main.rs           # Application entry point
│   ├── lib.rs            # Library exports
│   ├── theme/
│   │   └── mod.rs        # VS Code-compatible theming
│   ├── widgets/
│   │   ├── mod.rs        # Widget trait & manager
│   │   ├── tab_bar.rs    # Tabbed interface
│   │   ├── diagram.rs    # D2 diagram viewer
│   │   ├── code_view.rs  # Syntax-highlighted code
│   │   └── terminal.rs   # ANSI terminal output
│   ├── ipc/
│   │   ├── mod.rs        # IPC module
│   │   └── dbus.rs       # Dialog daemon client
│   ├── approval/
│   │   └── mod.rs        # User approval workflows
│   └── test_harness.rs   # Mock server for testing
└── Cargo.toml
```

## Widget System

### Widget Trait

All widgets implement the `Widget` trait:

```rust
pub trait Widget {
    /// Render the widget UI
    fn ui(&mut self, ui: &mut Ui) -> Result<Vec<WidgetEvent>>;
    
    /// Get the widget's display title
    fn title(&self) -> String;
    
    /// Get the widget's unique identifier
    fn id(&self) -> String;
}
```

### Available Widgets

| Widget | Description | Key Features |
|--------|-------------|--------------|
| `TabBarWidget` | Tabbed container | Closable tabs, drag-reorder, overflow handling |
| `DiagramWidget` | D2 diagram viewer | Zoom/pan, node selection, live reload |
| `CodeViewWidget` | Code display | Syntax highlighting, line numbers, selection |
| `TerminalWidget` | Terminal output | ANSI colors, auto-scroll, history |
| `AgentStreamWidget` | Agent conversation | Message roles, timestamps, markdown |
| `HarnessPanelWidget` | Harness status | Start/stop, status indicators |

### Widget Events

Widgets communicate via `WidgetEvent`:

```rust
pub enum WidgetEvent {
    NavigateTo { file: String, line: Option<usize>, column: Option<usize> },
    DiagramNodeSelected(String),
    DiagramNodeStatusChanged { node_id: String, status: NodeStatus },
    TerminalCommand(String),
    TerminalOutput(String),
    CodeSelectionChanged { file: String, start_line: usize, end_line: usize },
    OpenWidget { widget_type: String, config: serde_json::Value },
    CloseWidget(String),
    HarnessStatusUpdate { harness_id: String, status: String },
    AgentMessage { role: String, content: String },
}
```

## IPC Architecture

### Studio Core Communication

The UI communicates with Studio Core via Unix sockets using ETF-framed messages:

```
┌────────┬────────────────┐
│ length │ ETF payload    │
│ 4 bytes│ variable       │
└────────┴────────────────┘
```

**Commands (UI → Core):**
- `HarnessStart { harness_type: String }`
- `HarnessStop { harness_type: String }`
- `StateSet { path: Vec<String>, value: Value }`
- `AgentMessage { text: String, provider: String }`

**Events (Core → UI):**
- `HarnessStatus { harness: String, status: String }`
- `StateChanged { path: Vec<String>, value: Value }`
- `AgentResponse { content: String, role: String }`

### Dialog Daemon Integration

For user dialogs, the UI uses `continuum-dialog-cli`:

```rust
let mut client = DialogClient::new();
client.connect()?;

let response = client.show_choice(
    "Select Option",
    "Choose an action:",
    &[
        ChoiceOption::new("save", "Save Changes"),
        ChoiceOption::new("discard", "Discard"),
    ],
    Some("save"),
)?;
```

## Theme System

Themes are VS Code-compatible, supporting both light and dark modes:

```rust
pub struct Theme {
    // Background colors
    pub bg: Color32,
    pub bg_secondary: Color32,
    pub sidebar_bg: Color32,
    pub activitybar_bg: Color32,
    pub statusbar_bg: Color32,
    
    // Foreground colors
    pub fg: Color32,
    pub fg_dim: Color32,
    pub fg_muted: Color32,
    
    // Accent colors
    pub accent: Color32,
    pub accent_hover: Color32,
    pub success: Color32,
    pub warning: Color32,
    pub error: Color32,
    
    // Syntax highlighting
    pub syntax_keyword: Color32,
    pub syntax_string: Color32,
    pub syntax_number: Color32,
    // ... more syntax colors
}
```

Load a VS Code theme:

```rust
let theme = Theme::from_vscode_file(Path::new("mytheme.json"))
    .unwrap_or_else(Theme::dark);
```

## Test Harness

The UI includes a built-in test harness for development:

### Running the Test Harness

```bash
# Terminal 1: Start the mock server
cargo run --bin test-harness

# Terminal 2: Start the UI
cargo run --bin continuum-studio
```

### Mock Events

The test harness simulates backend events:

```rust
harness.send_event(MockEvent::HarnessStatus {
    harness: "cursor".to_string(),
    status: "running".to_string(),
});

harness.send_event(MockEvent::AgentMessage {
    role: "assistant".to_string(),
    content: "Task completed successfully!".to_string(),
});
```

## KDE/Wayland Considerations

The UI is designed for KDE Plasma with Wayland:

| Feature | Status | Notes |
|---------|--------|-------|
| Wayland Native | ✅ | Via eframe with `wayland` feature |
| X11 Fallback | ✅ | Via eframe with `x11` feature |
| HiDPI | ✅ | Automatic scaling |
| Keyboard Shortcuts | ✅ | Standard egui input handling |
| System Theme | 🔄 | Future: detect system preference |

### Complexity by Component

| Component | Complexity | Reason |
|-----------|------------|--------|
| TabBarWidget | Low | Pure egui, no platform calls |
| DiagramWidget | Medium | Custom rendering, coordinate math |
| CodeViewWidget | Medium | Syntax highlighting logic |
| TerminalWidget | Low | ANSI parsing is self-contained |
| IPC/Socket | Low | Standard tokio networking |
| IPC/D-Bus | Low | Via CLI, no direct D-Bus |

## Future Enhancements

1. **Direct D-Bus Integration**: Replace CLI calls with `zbus` proxy
2. **WebSocket Support**: For remote/browser-based connections
3. **Plugin System**: Load custom widgets dynamically
4. **Layout Persistence**: Save/restore widget layouts
5. **Keyboard Navigation**: Full keyboard control for accessibility

## Building

```bash
# Development
cargo build

# Release
cargo build --release

# With specific features
cargo build --features "wayland"
```

## Dependencies

| Crate | Purpose |
|-------|---------|
| eframe | Native window management |
| egui | Immediate-mode GUI |
| tokio | Async runtime |
| serde | Serialization |
| zbus | D-Bus client (future) |
| anyhow | Error handling |
| tracing | Logging |

