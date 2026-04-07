# Continuum Studio UI Architecture

This document describes the architecture of the Continuum Studio User Interface, built with Rust and iced 0.14.

> **Note:** The UI migrated from egui to iced in early 2026. Legacy egui code is archived in `ui/`.

## Overview

The UI follows iced's **Elm architecture** (Model-View-Update) with COSMIC desktop styling. Key design principles:

1. **Component Independence**: Each component manages its own state via messages
2. **Theme Consistency**: COSMIC-inspired theming with VS Code compatibility
3. **Platform Native**: Wayland-first with GPU acceleration via wgpu
4. **Subscription-Based Updates**: Async operations via iced subscriptions

## Architecture Layers

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                       │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐          │
│  │Sessions │ │Orchestr.│ │CLI Agents│ │Settings │ ...      │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘          │
│       └───────────┴───────────┴───────────┘                │
│                           │                                 │
├───────────────────────────┼─────────────────────────────────┤
│                    iced Runtime                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │   Message Pipeline (update → view → subscription)    │  │
│  │   ContinuumStudio struct + Message enum              │  │
│  └──────────────────────────────────────────────────────┘  │
│                           │                                 │
├───────────────────────────┼─────────────────────────────────┤
│                    Communication Layer                      │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐          │
│  │ Unix Socket │ │ D-Bus       │ │ WebSocket   │          │
│  │ (Core IPC)  │ │ (Dialog)    │ │ (Real-time) │          │
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
ui-iced/src/
├── main.rs               # Application entry, ContinuumStudio struct
├── lib.rs                # Library exports
├── theme/                # COSMIC/VS Code theming
│   ├── mod.rs            # AppColors, SemanticColors
│   ├── cosmic.rs         # COSMIC presets
│   └── vscode.rs         # VS Code theme parsing
├── widgets/              # Reusable components
│   ├── mod.rs            # Widget exports
│   ├── diagram.rs        # Mermaid/D2 rendering
│   ├── task_queue.rs     # Task management widget
│   ├── cosmic_style.rs   # COSMIC button/container styles
│   └── helpers.rs        # Layout utilities
├── core.rs               # Elixir IPC client
├── dialog_client.rs      # D-Bus dialog integration
├── cli_agents.rs         # CLI agent management UI
├── cli_agents_client.rs  # Agent HTTP/WebSocket client
├── orchestrator_panel.rs # Mode control UI
├── decision_engine.rs    # Auto dialog handling
├── task_queue_client.rs  # Task queue HTTP/WebSocket
├── activity_feed.rs      # Real-time event display
├── monitoring.rs         # Session metrics
├── offline.rs            # Offline operation queue
├── profiling.rs          # Performance timing
├── sessions.rs           # Cursor session tracking
├── settings.rs           # App configuration
├── subagents.rs          # Sub-agent monitoring
├── zones.rs              # Window positioning
└── updater.rs            # Self-update system
```

## Elm Architecture

### Model (ContinuumStudio)

The main application state:

```rust
struct ContinuumStudio {
    // Navigation
    current_tab: CursorTab,
    
    // Theme
    theme: Theme,
    colors: AppColors,
    
    // Core connection
    core_state: ConnectionState,
    core_tx: Option<mpsc::Sender<CoreRequest>>,
    
    // Component states
    sessions: Vec<CursorSession>,
    cli_agents_state: CLIAgentsState,
    orchestrator_state: OrchestratorPanelState,
    activity_feed_state: ActivityFeedState,
    // ... more state
    
    // Offline support
    offline_queue: OfflineQueue,
    connection_tracker: ConnectionTracker,
}
```

### Messages

The central message type:

```rust
enum Message {
    // Navigation
    TabSelected(CursorTab),
    
    // Core events
    CoreConnected,
    CoreDisconnected,
    CoreResponse(CoreResponse),
    
    // Component messages
    CLIAgent(CLIAgentMessage),
    Orchestrator(OrchestratorMessage),
    ActivityFeed(ActivityMessage),
    // ... more variants
    
    // System
    Tick,
    KeyboardShortcut(KeyboardShortcut),
}
```

### Update

The central update function:

```rust
fn update(&mut self, message: Message) -> Task<Message> {
    match message {
        Message::TabSelected(tab) => {
            self.current_tab = tab;
            Task::none()
        }
        Message::CLIAgent(msg) => {
            self.cli_agents_state.handle_message(msg)
        }
        // ... dispatch to appropriate handlers
    }
}
```

### View

Tab-based layout:

```rust
fn view(&self) -> Element<'_, Message> {
    let content = match self.current_tab {
        CursorTab::Dashboard => self.view_dashboard(),
        CursorTab::Sessions => self.view_sessions(),
        CursorTab::CLIAgents => view_cli_agents_tab(&self.cli_agents_state),
        CursorTab::Orchestrator => view_orchestrator_panel(&self.orchestrator_state),
        // ... more tabs
    };
    
    column![
        self.view_sidebar(),
        content
    ].into()
}
```

### Subscriptions

Async event sources:

```rust
fn subscription(&self) -> Subscription<Message> {
    Subscription::batch([
        // Core IPC
        core_subscription(self.core_rx.clone())
            .map(Message::CoreResponse),
        
        // CLI Agents WebSocket (lazy - only when viewing tab)
        if self.current_tab == CursorTab::CLIAgents {
            cli_agents_ws_subscription()
        } else {
            Subscription::none()
        },
        
        // Activity feed (always active)
        activity_feed_subscription(),
        
        // Keyboard shortcuts
        keyboard_shortcut_subscription(),
    ])
}
```

## Communication Patterns

### Core IPC (Unix Socket)

```rust
// Send request
core_tx.send(CoreRequest::GetVersions).await?;

// Receive response (via subscription)
fn handle_core_response(&mut self, response: CoreResponse) -> Task<Message> {
    match response {
        CoreResponse::Versions(versions) => {
            self.versions = versions;
            Task::none()
        }
        // ...
    }
}
```

### D-Bus (Dialog Daemon)

```rust
let client = DialogClient::new()?;

// Get orchestrator mode
let mode = client.get_orchestrator_mode().await?;

// Set mode
client.set_orchestrator_mode(OrchestratorMode::Spectator).await?;
```

### WebSocket (Real-time Updates)

```rust
fn spawn_cli_agents_websocket() -> impl Stream<Item = CLIAgentWsEvent> {
    async_stream::stream! {
        let url = "ws://localhost:4001/ws/cli-agents";
        let (ws_stream, _) = connect_async(url).await?;
        
        while let Some(msg) = ws_stream.next().await {
            if let Ok(text) = msg?.into_text() {
                if let Ok(event) = serde_json::from_str(&text) {
                    yield event;
                }
            }
        }
    }
}
```

## Theme System

COSMIC-inspired theming:

```rust
pub struct AppColors {
    // Backgrounds
    pub base: Color,
    pub surface: Color,
    pub surface_elevated: Color,
    
    // Text
    pub text_primary: Color,
    pub text_secondary: Color,
    pub text_disabled: Color,
    
    // Semantic
    pub success: Color,
    pub warning: Color,
    pub danger: Color,
    pub info: Color,
    
    // Accent
    pub accent: Color,
    pub accent_hover: Color,
}

pub enum CosmicThemePreset {
    Dark,
    Light,
    PopOrange,
    WarmAmber,
    CoolBlue,
    Mint,
}
```

## Keyboard Shortcuts

```rust
pub enum KeyboardShortcut {
    SetModeUserActive,    // Ctrl+1
    SetModeDelegate,      // Ctrl+2
    SetModeSpectator,     // Ctrl+3
    SetModeAutonomous,    // Ctrl+4
    ToggleHistory,        // Ctrl+H
    RefreshMode,          // Ctrl+R
    UndoDecision,         // Ctrl+Z
}
```

## Performance Considerations

1. **Lazy WebSocket Connections**: Only connect when viewing relevant tabs
2. **Conditional Polling**: Triage queue polling only when non-empty
3. **Memory Limits**: Decision history capped at 1000, triage at 100
4. **Profiling Spans**: Frame-time aware logging for hot paths

```rust
// Example profiling usage
let _span = ProfileSpan::frame("render_view");
// ... render code
// Logs if > 16ms (one frame at 60 FPS)
```

## Building

```bash
# Development
cd ui-iced && cargo build

# Release
cd ui-iced && cargo build --release

# With NixOS (handles library paths)
./run-gui.sh
```

## Dependencies

| Crate | Purpose |
|-------|---------|
| iced 0.14 | GUI framework (Elm architecture) |
| tokio | Async runtime |
| tokio-tungstenite | WebSocket client |
| zbus | D-Bus integration |
| reqwest | HTTP client |
| serde | Serialization |
| chrono | Time handling |
| synapsix-theme | Shared COSMIC theming |

## Related Documents

- `AGENTS.md` - Agent memory with module details
- `docs/PERF-AUDIT-2026-03-17.md` - Performance optimization audit
- `docs/STATUS_APRIL_2026.md` - Current status
