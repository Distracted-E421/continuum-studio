# Continuum Studio UI Stabilization Plan
**Date**: 2026-02-07  
**Status**: Historical planning — many checkboxes below were completed by **April 2026** (orchestrator, CLI agents, activity feed, lazy WebSockets, etc.). See **`docs/STATUS_APRIL_2026.md`** for what shipped; keep this file for the original milestone breakdown.  
**Priority**: Medium (stabilize before adding features)

---

## Executive Summary

The Continuum Studio desktop UI (iced-based) is functional but incomplete. This document outlines the plan to **stabilize the current implementation** before adding new features. The goal is a solid foundation that reliably displays agent state, conversations, and basic controls.

**Philosophy**: Stabilize → Validate → Extend

---

## Current State

### Working
- ✅ iced framework integrated
- ✅ Basic window rendering
- ✅ Core module structure
- ✅ Message/Update architecture

### Incomplete/Unstable
- ⚠️ Chat display (partial)
- ⚠️ Agent state visualization
- ⚠️ Error handling
- ⚠️ Theme consistency
- ❌ Synapsix connection
- ❌ Settings persistence
- ❌ Keyboard shortcuts

### Files Requiring Attention
```
ui-iced/
├── src/
│   ├── main.rs          # App initialization (needs cleanup)
│   ├── lib.rs           # Module structure (OK)
│   ├── core.rs          # Core state (needs error handling)
│   ├── updater.rs       # Update logic (incomplete)
│   ├── chat_pipeline.rs # New file (needs integration)
│   └── subagents.rs     # New file (needs integration)
└── Cargo.toml           # Dependencies (review)
```

---

## Stabilization Goals

### Goal 1: Reliable Startup
- Application starts without errors
- Graceful handling of missing Synapsix connection
- Proper window positioning and sizing
- Clean shutdown without resource leaks

### Goal 2: Consistent UI
- All views render correctly
- Theme applied uniformly
- No visual glitches or layout breaks
- Responsive to window resize

### Goal 3: Core Functionality
- Display agent conversations (read-only first)
- Show agent status (connected/disconnected)
- Basic settings (theme, font size)
- Error messages displayed clearly

### Goal 4: Developer Experience
- Clear module boundaries
- Documented public APIs
- Example usage patterns
- Easy to extend

---

## Phase 1: Code Audit & Cleanup

### 1.1 Module Audit

**main.rs**
```rust
// Current issues:
// - Initialization order unclear
// - Error handling mixed with setup
// - No configuration loading

// Target structure:
fn main() -> iced::Result {
    // 1. Load configuration
    let config = Config::load_or_default();
    
    // 2. Initialize logging
    init_logging(&config);
    
    // 3. Create application
    let app = ContinuumStudio::new(config);
    
    // 4. Run with settings
    iced::application(app.title(), app.update, app.view)
        .settings(app.settings())
        .run()
}
```

**core.rs**
```rust
// Current issues:
// - State management inconsistent
// - No separation of concerns
// - Error states not modeled

// Target state model:
pub struct AppState {
    // Connection state
    pub synapsix: SynapsixConnection,
    
    // UI state
    pub view: View,
    pub theme: Theme,
    pub settings: Settings,
    
    // Data state
    pub conversations: ConversationList,
    pub active_conversation: Option<ConversationId>,
    
    // Error state
    pub errors: Vec<AppError>,
}

pub enum SynapsixConnection {
    Disconnected,
    Connecting,
    Connected(SynapsixClient),
    Error(ConnectionError),
}

pub enum View {
    Conversations,
    Settings,
    AgentDetails(AgentId),
    Error(AppError),
}
```

**updater.rs**
```rust
// Current issues:
// - Incomplete message handling
// - No async command patterns
// - Missing error propagation

// Target message types:
pub enum Message {
    // UI events
    ViewChanged(View),
    ThemeChanged(Theme),
    WindowResized(Size),
    
    // Synapsix events
    SynapsixConnected,
    SynapsixDisconnected(Option<ConnectionError>),
    SynapsixMessage(SynapsixEvent),
    
    // Data events
    ConversationsLoaded(Vec<Conversation>),
    ConversationSelected(ConversationId),
    MessageReceived(ConversationId, ChatMessage),
    
    // Commands
    Connect,
    Disconnect,
    RefreshConversations,
    
    // Errors
    Error(AppError),
    DismissError(usize),
}
```

### 1.2 Dependency Review

```toml
# Cargo.toml - Review each dependency

[dependencies]
# Core framework - keep
iced = { version = "0.13", features = ["canvas", "tokio", "debug"] }

# Async runtime - keep
tokio = { version = "1", features = ["full"] }

# Serialization - keep
serde = { version = "1", features = ["derive"] }
serde_json = "1"

# Logging - add if missing
tracing = "0.1"
tracing-subscriber = "0.3"

# Configuration - add
directories = "5"  # For config paths
toml = "0.8"       # For config files

# Synapsix client - ensure correct version
# (Should be local path or git dependency)

[dev-dependencies]
# Testing
proptest = "1"  # Property testing for state transitions
```

### 1.3 Error Handling Strategy

```rust
// src/error.rs

use thiserror::Error;

#[derive(Error, Debug, Clone)]
pub enum AppError {
    #[error("Connection failed: {0}")]
    Connection(String),
    
    #[error("Synapsix error: {0}")]
    Synapsix(String),
    
    #[error("Configuration error: {0}")]
    Config(String),
    
    #[error("Data error: {0}")]
    Data(String),
    
    #[error("UI error: {0}")]
    Ui(String),
}

impl AppError {
    pub fn severity(&self) -> Severity {
        match self {
            Self::Connection(_) => Severity::Warning,  // Recoverable
            Self::Synapsix(_) => Severity::Error,
            Self::Config(_) => Severity::Warning,
            Self::Data(_) => Severity::Error,
            Self::Ui(_) => Severity::Info,  // Usually cosmetic
        }
    }
    
    pub fn is_dismissible(&self) -> bool {
        matches!(self.severity(), Severity::Info | Severity::Warning)
    }
}

pub enum Severity {
    Info,
    Warning,
    Error,
    Critical,
}
```

---

## Phase 2: View Implementation

### 2.1 Conversation List View

```rust
// src/views/conversation_list.rs

use iced::{Element, Length};
use iced::widget::{column, container, scrollable, text, row};

pub fn view(state: &AppState) -> Element<Message> {
    let header = text("Conversations")
        .size(24)
        .style(state.theme.heading());
    
    let list = if state.conversations.is_empty() {
        empty_state(&state.theme)
    } else {
        conversation_list(&state.conversations, &state.theme)
    };
    
    let content = column![header, list]
        .spacing(16)
        .padding(20);
    
    container(content)
        .width(Length::Fill)
        .height(Length::Fill)
        .into()
}

fn conversation_list(conversations: &[Conversation], theme: &Theme) -> Element<Message> {
    let items: Vec<Element<Message>> = conversations
        .iter()
        .map(|conv| conversation_item(conv, theme))
        .collect();
    
    scrollable(column(items).spacing(8))
        .height(Length::Fill)
        .into()
}

fn conversation_item(conv: &Conversation, theme: &Theme) -> Element<Message> {
    let title = text(&conv.title)
        .size(16)
        .style(theme.text());
    
    let preview = text(&conv.preview)
        .size(14)
        .style(theme.text_secondary());
    
    let timestamp = text(&conv.formatted_time())
        .size(12)
        .style(theme.text_muted());
    
    let content = column![title, preview, timestamp]
        .spacing(4);
    
    container(content)
        .padding(12)
        .style(theme.card())
        .into()
}

fn empty_state(theme: &Theme) -> Element<Message> {
    let icon = text("💬").size(48);
    let message = text("No conversations yet")
        .size(16)
        .style(theme.text_secondary());
    let hint = text("Start chatting with an AI agent to see conversations here")
        .size(14)
        .style(theme.text_muted());
    
    container(
        column![icon, message, hint]
            .spacing(12)
            .align_items(Alignment::Center)
    )
    .width(Length::Fill)
    .height(Length::Fill)
    .center_x()
    .center_y()
    .into()
}
```

### 2.2 Agent Status View

```rust
// src/views/agent_status.rs

pub fn view(connection: &SynapsixConnection, theme: &Theme) -> Element<Message> {
    match connection {
        SynapsixConnection::Disconnected => disconnected_view(theme),
        SynapsixConnection::Connecting => connecting_view(theme),
        SynapsixConnection::Connected(client) => connected_view(client, theme),
        SynapsixConnection::Error(err) => error_view(err, theme),
    }
}

fn connected_view(client: &SynapsixClient, theme: &Theme) -> Element<Message> {
    let status_dot = container(Space::new(8, 8))
        .style(theme.status_connected());
    
    let status_text = text("Connected")
        .size(14)
        .style(theme.text());
    
    let agent_count = text(format!("{} agents active", client.agent_count()))
        .size(12)
        .style(theme.text_secondary());
    
    row![status_dot, column![status_text, agent_count].spacing(2)]
        .spacing(8)
        .align_items(Alignment::Center)
        .into()
}

fn disconnected_view(theme: &Theme) -> Element<Message> {
    let status_dot = container(Space::new(8, 8))
        .style(theme.status_disconnected());
    
    let status_text = text("Disconnected")
        .size(14)
        .style(theme.text_muted());
    
    let connect_button = button("Connect")
        .on_press(Message::Connect)
        .style(theme.button_primary());
    
    row![status_dot, status_text, connect_button]
        .spacing(8)
        .align_items(Alignment::Center)
        .into()
}
```

### 2.3 Settings View

```rust
// src/views/settings.rs

pub fn view(settings: &Settings, theme: &Theme) -> Element<Message> {
    let header = text("Settings")
        .size(24)
        .style(theme.heading());
    
    let theme_section = section("Appearance", vec![
        setting_row("Theme", theme_picker(&settings.theme)),
        setting_row("Font Size", font_size_slider(&settings.font_size)),
    ], theme);
    
    let connection_section = section("Connection", vec![
        setting_row("Synapsix URL", text_input(&settings.synapsix_url)),
        setting_row("Auto-connect", checkbox(settings.auto_connect)),
    ], theme);
    
    let content = column![header, theme_section, connection_section]
        .spacing(24)
        .padding(20);
    
    scrollable(content)
        .height(Length::Fill)
        .into()
}

fn theme_picker(current: &ThemeChoice) -> Element<Message> {
    pick_list(
        &[ThemeChoice::Light, ThemeChoice::Dark, ThemeChoice::System],
        Some(current.clone()),
        Message::ThemeChanged
    ).into()
}
```

---

## Phase 3: Synapsix Integration

### 3.1 Connection Management

```rust
// src/synapsix/client.rs

use std::sync::Arc;
use tokio::sync::mpsc;

pub struct SynapsixClient {
    sender: mpsc::Sender<ClientCommand>,
    state: Arc<RwLock<ClientState>>,
}

#[derive(Default)]
struct ClientState {
    agents: Vec<AgentInfo>,
    conversations: Vec<Conversation>,
}

pub enum ClientCommand {
    Connect(String),  // URL
    Disconnect,
    Subscribe(Subscription),
    Unsubscribe(SubscriptionId),
}

impl SynapsixClient {
    pub async fn connect(url: &str) -> Result<Self, ConnectionError> {
        // 1. Establish WebSocket/HTTP connection
        // 2. Authenticate if needed
        // 3. Start background event loop
        // 4. Return client handle
        todo!()
    }
    
    pub fn subscribe<F>(&self, event_type: EventType, callback: F)
    where
        F: Fn(SynapsixEvent) + Send + 'static
    {
        // Subscribe to specific event types
        todo!()
    }
    
    pub fn agent_count(&self) -> usize {
        self.state.read().unwrap().agents.len()
    }
    
    pub fn conversations(&self) -> Vec<Conversation> {
        self.state.read().unwrap().conversations.clone()
    }
}
```

### 3.2 Event Handling

```rust
// src/synapsix/events.rs

#[derive(Debug, Clone)]
pub enum SynapsixEvent {
    // Agent events
    AgentStarted(AgentInfo),
    AgentStopped(AgentId),
    AgentStateChanged(AgentId, AgentState),
    
    // Conversation events
    ConversationCreated(Conversation),
    ConversationUpdated(ConversationId, ConversationUpdate),
    MessageAdded(ConversationId, ChatMessage),
    
    // System events
    Heartbeat,
    Error(String),
}

// In updater.rs
fn handle_synapsix_event(state: &mut AppState, event: SynapsixEvent) -> Command<Message> {
    match event {
        SynapsixEvent::ConversationCreated(conv) => {
            state.conversations.push(conv);
            Command::none()
        }
        
        SynapsixEvent::MessageAdded(conv_id, message) => {
            if let Some(conv) = state.conversations.iter_mut()
                .find(|c| c.id == conv_id) 
            {
                conv.messages.push(message);
            }
            Command::none()
        }
        
        SynapsixEvent::AgentStateChanged(agent_id, new_state) => {
            // Update UI to reflect new state
            // Could trigger notifications if important
            Command::none()
        }
        
        SynapsixEvent::Error(msg) => {
            state.errors.push(AppError::Synapsix(msg));
            Command::none()
        }
        
        _ => Command::none()
    }
}
```

---

## Phase 4: Testing & Validation

### 4.1 Unit Tests

```rust
// src/tests/state_tests.rs

#[test]
fn test_initial_state() {
    let state = AppState::default();
    assert!(matches!(state.synapsix, SynapsixConnection::Disconnected));
    assert!(state.conversations.is_empty());
    assert!(state.errors.is_empty());
}

#[test]
fn test_connection_state_transitions() {
    let mut state = AppState::default();
    
    // Disconnected -> Connecting
    state.synapsix = SynapsixConnection::Connecting;
    assert!(matches!(state.synapsix, SynapsixConnection::Connecting));
    
    // Connecting -> Error
    state.synapsix = SynapsixConnection::Error(ConnectionError::Timeout);
    assert!(matches!(state.synapsix, SynapsixConnection::Error(_)));
}

#[test]
fn test_error_dismissal() {
    let mut state = AppState::default();
    state.errors.push(AppError::Ui("test".into()));
    
    assert_eq!(state.errors.len(), 1);
    state.errors.remove(0);
    assert!(state.errors.is_empty());
}
```

### 4.2 Integration Tests

```rust
// src/tests/integration_tests.rs

#[tokio::test]
async fn test_synapsix_connection() {
    // Start mock Synapsix server
    let mock = MockSynapsix::start().await;
    
    // Connect client
    let client = SynapsixClient::connect(&mock.url()).await.unwrap();
    
    // Verify connected
    assert!(client.is_connected());
    
    // Cleanup
    mock.stop().await;
}

#[tokio::test]
async fn test_conversation_loading() {
    let mock = MockSynapsix::start().await;
    mock.add_conversation(test_conversation());
    
    let client = SynapsixClient::connect(&mock.url()).await.unwrap();
    
    let conversations = client.conversations();
    assert_eq!(conversations.len(), 1);
}
```

### 4.3 Visual Tests

```rust
// Manual verification checklist:
// [ ] App starts without errors
// [ ] Window renders at correct size
// [ ] Theme applies correctly (light/dark)
// [ ] Empty state displays when no conversations
// [ ] Conversation list scrolls smoothly
// [ ] Settings persist across restarts
// [ ] Connection status updates in real-time
// [ ] Errors display and can be dismissed
// [ ] Window resize doesn't break layout
```

---

## Implementation Timeline

### Week 1: Audit & Cleanup
- [ ] Module audit and documentation
- [ ] Dependency review and updates
- [ ] Error handling implementation
- [ ] State model refactoring

### Week 2: Core Views
- [ ] Conversation list view (read-only)
- [ ] Agent status display
- [ ] Settings view (basic)
- [ ] Theme system

### Week 3: Synapsix Integration
- [ ] Connection management
- [ ] Event handling
- [ ] Conversation syncing
- [ ] Error recovery

### Week 4: Testing & Polish
- [ ] Unit tests
- [ ] Integration tests
- [ ] Visual verification
- [ ] Performance profiling

---

## Configuration

### Nickel Config Schema

```nickel
# continuum-studio/config/ui.ncl

let UiConfig = {
  window = {
    width | Number | default = 1200,
    height | Number | default = 800,
    min_width | Number | default = 600,
    min_height | Number | default = 400,
    maximized | Bool | default = false,
  },
  
  theme = {
    mode | [| 'light, 'dark, 'system |] | default = 'system,
    accent_color | String | default = "#0066CC",
    font_family | String | default = "Inter",
    font_size | Number | default = 14,
  },
  
  synapsix = {
    url | String | default = "http://localhost:4000",
    auto_connect | Bool | default = true,
    reconnect_delay_ms | Number | default = 5000,
    max_reconnect_attempts | Number | default = 10,
  },
  
  behavior = {
    confirm_close | Bool | default = false,
    minimize_to_tray | Bool | default = false,
    start_minimized | Bool | default = false,
  },
}

{
  window = {},
  theme = {},
  synapsix = {},
  behavior = {},
} | UiConfig
```

---

## Future Features (Post-Stabilization)

These features are **explicitly deferred** until stabilization is complete:

1. **Strategy Canvas** - Interactive plan visualization
2. **Agent Creation Wizard** - GUI for spawning agents
3. **Diff Viewer** - Visual code change display
4. **Terminal Integration** - Embedded terminal views
5. **Plugin System** - Extensible UI components
6. **Mobile Sync** - Real-time sync with mobile app

---

## Success Criteria

Stabilization is complete when:

- [ ] App starts reliably on all target platforms (Linux, macOS)
- [ ] No panics during normal usage
- [ ] Connection to Synapsix works (with graceful fallback)
- [ ] Conversations display correctly (read-only)
- [ ] Settings persist across sessions
- [ ] Theme switching works
- [ ] All views render without layout breaks
- [ ] Unit tests pass
- [ ] Manual verification checklist complete

---

*Document guides stabilization effort*
