# UI Stabilization Roadmap

**Priority**: High
**Complexity**: Medium
**Dependencies**: iced framework knowledge, Rust
**Estimated Agent Sessions**: 2-3 focused sessions

---

## Overview

The Continuum Studio UI has been migrated from egui to iced framework for COSMIC desktop compatibility. The basic structure is in place but several views are incomplete.

### Current State (Updated 2026-02-21)

**Total Implementation: ~18,600+ lines of Rust across 16 modules**

**Core Application:**
- ✅ `main.rs` (10,985 lines) - Full application with iced framework
- ✅ `core.rs` (824 lines) - Core state management
- ✅ `updater.rs` (1,202 lines) - Update handling

**Views & Modules:**
- ✅ `chat_pipeline.rs` (864 lines) - Chat Pipeline view - functional
- ✅ `sessions.rs` (985 lines) - Session monitoring - working
- ✅ `monitoring.rs` (449 lines) - Resource monitoring
- ✅ `subagents.rs` (470 lines) - Subagent management
- ✅ `services.rs` (359 lines) - Services panel
- ✅ `settings.rs` (251 lines) - Settings view
- ✅ `zones.rs` (300 lines) - Zone management

**Clients/Integrations:**
- ✅ `task_queue_client.rs` (740 lines) - Task Queue IPC
- ✅ `coordinator_client.rs` (328 lines) - Agent Coordinator IPC
- ✅ `dialog_client.rs` (299 lines) - Synapsix Dialog integration
- ✅ `feed_client.rs` (326 lines) - Activity feed client
- ✅ `log_capture.rs` (178 lines) - Log capture

**Widgets:**
- ✅ `widgets/task_queue.rs` (43KB) - Task Queue widget
- ✅ `widgets/cosmic_style.rs` (8.8KB) - COSMIC styling
- ✅ `widgets/helpers.rs` (1.1KB) - Widget helpers

**Status:**
- Phase 1 (Audit): ✅ Complete
- Phase 2 (Core Views): ✅ Mostly Complete
- Phase 3 (Agent Management): ✅ Working
- Phase 4 (Session Monitoring): ⚠️ In Progress (real-time updates need testing)

---

## Phase 1: Audit Current Views

**Goal**: Document what's working and what's missing in each view

### Tasks

1. **Run the UI and test each view**
   ```bash
   cd /home/e421/continuum-studio/ui-iced
   cargo run --release
   ```
   
2. **Document each view's state**
   - What renders correctly
   - What's missing or broken
   - What data sources are needed

3. **Review existing code**
   - `src/views/` directory
   - `src/state.rs` for state management
   - `src/message.rs` for event handling

### Success Criteria

- [ ] All views documented
- [ ] Missing features listed
- [ ] No compilation errors or warnings

---

## Phase 2: Complete Core Views

**Goal**: Finish the primary views users need

### Tasks

1. **Chat Pipeline View**
   - Display indexed conversations
   - Search functionality
   - Conversation preview
   - File: `src/views/chat_pipeline.rs`

2. **Services Panel**
   - Show running services
   - Start/stop controls
   - Status indicators
   - File: `src/views/services.rs`

3. **Settings View**
   - Theme selection
   - Provider configuration
   - Path settings
   - File: `src/views/settings.rs`

### Success Criteria

- [ ] Chat Pipeline shows conversations from Synapsix
- [ ] Services can be started/stopped
- [ ] Settings persist across restarts

---

## Phase 3: Agent Management

**Goal**: Add subagent visibility and control

### Tasks

1. **Subagent List**
   - Show registered agents
   - Status indicators
   - Last heartbeat time

2. **Agent Details**
   - Current task
   - Resource usage
   - Log output

3. **Agent Controls**
   - Start/stop
   - Send commands
   - View history

### Success Criteria

- [ ] Agents visible in UI
- [ ] Can control agents from UI
- [ ] Real-time status updates

---

## Phase 4: Session Monitoring

**Goal**: Show what's happening across all agents

### Tasks

1. **Activity Feed**
   - Real-time event stream
   - Filter by agent/type
   - Timestamp display

2. **Resource Dashboard**
   - Token usage
   - API costs
   - Request counts

3. **Error Tracking**
   - Failed operations
   - Retry status
   - Alert indicators

### Success Criteria

- [ ] Real-time activity visible
- [ ] Costs tracked and displayed
- [ ] Errors surfaced prominently

---

## Testing Strategy

### Manual Testing

```bash
# Build and run
cd /home/e421/continuum-studio/ui-iced
cargo build --release
./target/release/continuum-studio
```

### Check for Warnings

```bash
cargo clippy
```

### Test State Management

Create test scenarios for:
- View switching
- Data loading
- Error handling
- Reconnection

---

## Notes for Agent

### Key Files

- `src/main.rs` - Application entry
- `src/app.rs` - Main application struct
- `src/state.rs` - Application state
- `src/message.rs` - Event/message types
- `src/views/` - Individual view modules
- `src/theme.rs` - Theming

### iced Patterns

```rust
// Message handling pattern
fn update(&mut self, message: Message) -> Command<Message> {
    match message {
        Message::ViewChanged(view) => {
            self.current_view = view;
            Command::none()
        }
        // ...
    }
}

// View rendering pattern
fn view(&self) -> Element<Message> {
    match self.current_view {
        View::Dashboard => self.view_dashboard(),
        View::ChatPipeline => self.view_chat_pipeline(),
        // ...
    }
}
```

### Data Sources

- **Chat data**: Synapsix SQLite database
- **Agent status**: D-Bus or Unix socket
- **Services**: systemctl user services
- **Costs**: Agent Bridge tracking

---

**Last Updated**: 2026-02-21 (Marked substantially complete after multi-agent session review)
