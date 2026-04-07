# Continuum Studio Agent Memory

## Project Context

AI Orchestration Platform for multi-agent control, monitoring, and verification.

**SSPL-1.0 licensed** - core Continuum IP.

## Architecture

```
Continuum Studio
├── ui-iced/    # Desktop UI (Rust + iced 0.14, COSMIC styling)
├── android/    # Mobile app (Kotlin + Jetpack Compose)
├── core/       # Orchestration hub (Elixir/OTP)
└── crates/     # Shared Rust libraries
```

## Current Status

**Branch**: `iced-migration` (9 commits ahead of origin)

### Desktop UI (`ui-iced/`)

- Session management for Cursor instances
- Service discovery and monitoring dashboard
- Chat message pipeline display
- VS Code theme compatibility
- Self-update system
- Settings management
- **Diagram rendering** (Mermaid/D2) via `widgets/diagram.rs` (March 2026)
- **Full offline mode** with operation queue via `offline.rs` (March 2026)
- **Orchestrator mode** with decision engine via `decision_engine.rs` (March 2026)
- **CLI Agents** with presets, batch launch, dialog inbox (March 2026)

### Android App (`android/`)

- Widget Bay - Customizable grid dashboard
- Dialog Client - WebSocket to port 8080
- Notification Service - Background dialog alerts
- Auto-reconnect with exponential backoff
- Coordination Dashboard for multi-agent monitoring
- **Multi-Endpoint Configuration** (March 2026) - Multiple server endpoints with auto-fallback

#### Multi-Endpoint Configuration (March 13, 2026)

**Files Modified:**

- `data/DialogModels.kt` - `ServerEndpoint`, `EndpointType`, `EndpointStatus` data classes
- `viewmodel/DialogViewModel.kt` - Endpoint management state and functions
- `ui/dialog/DialogScreen.kt` - Settings UI for endpoints with `EndpointRow` composable
- `MainActivity.kt` - Wiring state to UI
- `network/DialogWebSocketClient.kt` - `buildHttpUrlForTest` method

**Features:**

- Configure multiple server endpoints (Local, Tailscale, Cloudflare, Remote)
- Enable/disable individual endpoints
- Set active endpoint with one tap
- Auto-fallback to other enabled endpoints on connection failure
- Test individual endpoints with latency display
- Test all endpoints simultaneously
- Quick-add button for Tailscale (Obsidian) endpoint
- Persistent settings via DataStore

**UI Location:** Settings tab → "Server Endpoints" section

#### Overnight Session Verification (March 16, 2026)

- **Parked Agents API**: E2E verified - `GET /api/parking/agents` and `POST /api/parking/agents/:id/unpark` work; Continuum Studio `ParkedAgentsHttpClient` uses `http://localhost:8080`
- **Activity Feed**: `/ws/activity` WebSocket connects, sends `dialog_sent`, `dialog_response`, `command` events; Command events require Cursor integrated terminal (not fast_shell)
- **Android**: `./gradlew assembleDebug` succeeds; Coordination tab ready for manual device test

### Core (`core/`)

- Elixir/OTP orchestration hub
- Agent Bridge for multi-provider AI abstraction
- Version Registry
- Workspace Tracker

## Development Preferences

- Use Nushell for scripting (not bash)
- Use fast_shell MCP tool for commands when available
- Follow language philosophy: Nix > Nushell > Elixir > Rust > Kotlin

## Available MCP Tools

### Synapsix MCP (synapsix-mcp)

Primary tooling for Cursor agents. ~20ms latency via D-Bus.

| Tool | Purpose | When to Use |
|------|---------|-------------|
| `fast_shell` | Execute commands | Build, test, cargo commands |
| `fast_dialog` | GUI dialogs | User interaction, decisions |
| `fast_screenshot` | Screen capture | UI testing, visual debugging |
| `fast_visual_diff` | Compare images | Before/after UI comparisons |
| `handoff_context` | Get past context | Understanding previous work |
| `handoff_share` | Share status | Multi-agent coordination |

### UI Integration Notes

Continuum Studio integrates deeply with Synapsix tooling:

1. **Dialog Client** (`dialog_client.rs`) - D-Bus connection to synapsix-dialog-daemon
2. **CLI Agents Client** (`cli_agents_client.rs`) - HTTP/WebSocket to Synapsix API
3. **Decision Engine** (`decision_engine.rs`) - Auto-handling based on orchestrator mode
4. **Orchestrator Panel** (`orchestrator_panel.rs`) - Mode control and triage queue

### Synapsix API Endpoints Used

| Endpoint | Purpose | Client |
|----------|---------|--------|
| `/api/cli-agents/*` | Agent management | `CLIAgentsHttpClient` |
| `/api/presets/*` | Preset management | `CLIAgentsHttpClient` |
| `/api/agent-dialogs/*` | Dialog inbox | `CLIAgentsHttpClient` |
| `/api/orchestrator/mode` | Mode control | `DialogClient` |
| `/ws/cli-agents` | Agent events | WebSocket |
| `/ws/orchestrator` | Dialog stream | WebSocket |

### D-Bus Integration

| Service | Path | Interface |
|---------|------|-----------|
| `sh.synapsix.Dialog` | `/sh/synapsix/Dialog` | `sh.synapsix.Dialog1` |

Key methods:

- `GetOrchestratorMode` / `SetOrchestratorMode`
- `ShowDialog` - Display dialog via GUI
- `GetModeInfo` - Extended mode info

## Tool Usage Guidelines

### DEPRECATED Approaches

**Do NOT use these approaches:**

1. **Writing .ncl files to ~/.synapsix/dialogs/** - Use `fast_dialog` MCP or D-Bus
2. **Manual polling for dialog responses** - Use WebSocket or D-Bus signals
3. **synapsix-dialog-cli when MCP available** - CLI is fallback only

### Cross-References

- [MCP Tool Reference](../cortex/docs/mcp-tool-reference.md) - Complete tool documentation
- [Dialog Usage Guide](../cortex/docs/dialog-usage-guide.md) - Dialog best practices
- [Orchestrator Mode Protocol](../cortex/docs/orchestrator-mode-protocol.md) - Mode system spec
- [Synapsix AGENTS.md](../synapsix/AGENTS.md) - Backend implementation

## Build & Test

### Desktop (Rust)

```bash
cd ui-iced && cargo build --release
# Or with NixOS:
./run-gui.sh
```

### Android

```bash
cd android && ./gradlew assembleDebug
# Install:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Core (Elixir)

```bash
cd core && mix deps.get && mix compile
```

## Integration Points

- **Synapsix Dialog** - WebSocket port 8080 for dialog daemon
- **Terminal Monitor** - Cursor terminal insights
- **Phosphor** - Screen capture via D-Bus
- **NeSy (Z3/SMT)** - Verification bridge

## Related Projects

- `~/synapsix` - Dialog daemon, terminal monitor, harnesses
- `~/phosphor` - Screen capture service

## Overnight Session (March 11, 2026)

### Orchestrator Dialog Inbox

Integrated agent dialog routing into CLI Agents tab:

- `PendingDialog` extended with `agent_id`, `source`, `priority`, `workspace`, `orchestrator_id`
- `DialogSource` enum: Orchestrator, SessionAgent, SubAgent, External
- `DialogPriority` enum with emoji display: Low, Normal, High (🔴), Critical (🚨)
- `CLIAgentsHttpClient` updated:
  - `fetch_pending_dialogs` → `/api/agent-dialogs`
  - `respond_to_dialog` → `/api/agent-dialogs/:id/respond`
  - `escalate_dialog` function added
- WebSocket client for `/ws/orchestrator` real-time updates
- `OrchestratorWsEvent` types: DialogNew, DialogResponded, DialogTimeout, ModeChanged, AgentStarted, AgentCompleted
- `spawn_orchestrator_websocket()` for persistent connection with auto-reconnect

### Decision Engine (`ui-iced/src/decision_engine.rs`)

**NEW** - Automated dialog handling based on orchestrator mode and priority.

**Core Types:**

- `DecisionRecord` - Records decisions with reasoning, timestamps, undo tracking
- `TriageState` - Manual, AutoApprove, AutoDecline, Paused
- `TriageItem` - Queue items with suggested responses and timeouts
- `DecisionEngineConfig` - Configurable critical keywords, timeouts, agent-specific overrides
- `DecisionResult` - AutoHandle, RequireUser, or Triage outcomes

**Decision Logic by Mode:**

| Mode | Low/Normal | High | Critical |
|------|-----------|------|----------|
| UserActive | → User | → User | → User |
| UserDelegate | Auto-handle | → User | → User |
| Spectator | Auto + claim timeout | Auto + claim | → User (timeout) |
| Autonomous | Auto-handle | Auto-handle | Queued for review |

**Features:**

- Critical keyword detection (delete, production, sudo, etc.)
- Session continuation prioritization (keeps agents running)
- Undo window for recent decisions
- 4 unit tests

### Orchestrator Panel (`ui-iced/src/orchestrator_panel.rs`)

**NEW** - UI component for orchestrator mode control and decision triage.

**Components:**

- `OrchestratorPanelState` - UI state (mode, engine, daemon connection status)
- `OrchestratorMessage` - SetMode, ApproveTriage, DeclineTriage, ClaimDialog, Undo
- `view_mode_selector()` - 4-button mode bar (🟢 🟡 🟠 🔴)
- `view_triage_queue()` - Pending decisions with approve/decline/claim actions
- `view_triage_item()` - Individual items with countdown timers
- `view_orchestrator_panel()` - Full panel with connection status and undo button

**UI Features:**

- Color-coded mode buttons
- Countdown timers for auto-actions
- Priority emoji indicators
- Source badges (Session, Sub-agent, External)

### CLI Agents Preset System (`ui-iced/src/cli_agents.rs`)

Full preset and snippet system for agent prompts:

**Types:**

- `Preset` - Named prompt configurations with prefix/suffix
- `Snippet` - Reusable prompt fragments
- `WorkspaceOverrides` - Auto-select presets per workspace

**State Fields:**

- `presets`, `snippets` - Available prompt templates
- `selected_preset` - Current preset for launch form
- `workspace_overrides` - Workspace → preset mappings
- `show_preset_editor`, `editing_preset` - Modal state

**Views:**

- `CLIAgentsView::Launch` - Launch form with preset selector
- `CLIAgentsView::Batch` - Multi-workspace batch launch
- `CLIAgentsView::Dialogs` - Dialog inbox for worker responses
- Modal editors for presets, snippets, workspace overrides

**Tasks:**

- `FetchPresets`, `CreatePreset`, `UpdatePreset`, `DeletePreset`
- `FetchSnippets`, `CreateSnippet`, `UpdateSnippet`, `DeleteSnippet`
- `FetchWorkspaceOverrides`, `SetWorkspaceOverride`, `ClearWorkspaceOverride`
- `FetchPendingDialogs`, `RespondToDialog`

### Dialog Client Extensions (`ui-iced/src/dialog_client.rs`)

**OrchestratorMode enum:**

- `UserActive` - User handles all dialogs (default)
- `UserDelegate` - Auto-handle routine, escalate high/critical
- `Spectator` - Auto-handle all, user can claim within timeout
- `Autonomous` - Full auto, critical queued for later

**D-Bus Methods:**

- `get_orchestrator_mode()` / `set_orchestrator_mode()`
- `get_orchestrator_mode_info()` - Extended info with timeouts
- `set_orchestrator_config()` - Configure timeouts

### Offline Mode Integration

- `offline_queue` and `connection_tracker` fields added to `ContinuumStudio` struct
- Dashboard shows offline status indicator with:
  - Online/Partial/Offline state computed from connection bools
  - Pending operation count for sync
  - Color-coded status

**Future work**: Route operations through queue when offline, sync on reconnect.

## Overnight Session (March 10, 2026)

### Completed Features

**1. Diagram Rendering** (`ui-iced/src/widgets/diagram.rs`)

- `DiagramRenderer`: Async diagram rendering with caching
- `DiagramWidget`: Stateful view component
- `DiagramState`: Tracks render state (Pending, Rendered, Error, Loading)
- Supports Mermaid (`mmdc` CLI) and D2 (`d2` CLI) diagram types
- `extract_diagrams()`: Extracts fenced code blocks from markdown
- 3 unit tests

**2. Full Offline Mode** (`ui-iced/src/offline.rs` + `ui-iced/src/main.rs`)

- `OfflineQueue`: Persistent operation queue (max 1000 ops)
- `ConnectionTracker`: Multi-backend connectivity status
- `QueuedOperation`: Task, Dialog, Settings, Session action types
- Retry tracking with exponential backoff
- Automatic sync when connections restore
- 3 unit tests
- **Integration (March 11, 2026)**: Fields added to main app state, dashboard shows offline status indicator

**3. UI Stabilization** (Audit)

- Reviewed: `log_capture.rs`, `sessions.rs`, `dialog_client.rs`, `settings.rs`
- Error handling is solid throughout (`.map_err()` chains)
- Few `.unwrap()` calls, all in safe contexts

### Key Files

| Feature | File |
|---------|------|
| Diagram rendering | `ui-iced/src/widgets/diagram.rs` |
| Offline mode | `ui-iced/src/offline.rs` |
| CLI agents | `ui-iced/src/cli_agents.rs` |
| CLI agents client | `ui-iced/src/cli_agents_client.rs` |
| Decision engine | `ui-iced/src/decision_engine.rs` |
| Orchestrator panel | `ui-iced/src/orchestrator_panel.rs` |
| Dialog client | `ui-iced/src/dialog_client.rs` |
| Module exports | `ui-iced/src/lib.rs` |
| Parked agents panel | `ui-iced/src/parked_agents.rs` |
| Activity feed | `ui-iced/src/activity_feed.rs` |

## March 12, 2026

### Agent Parking System UI

**New UI Components:**

- `parked_agents.rs`: Panel for managing parked agents
  - `ParkedAgentsPanelState` - Parked agent list, loading state, assign modal
  - `ParkedAgent` struct with display helpers (time formatting, capability badges)
  - "Assign Task" button per agent with modal for task assignment
  - HTTP client integration (`/api/agents/parked` endpoints)
  - Added as "Parked" tab in main navigation (`CursorTab::ParkedAgents`)

- `activity_feed.rs`: Real-time agent activity monitoring
  - `ActivityFeedState` - Ring buffer, event filters, expanded items
  - `ActivityEvent` enum: FileEdit, Command, ToolCall, DialogSent, DialogResponse
  - Scrollable, filterable list with expand/collapse per event
  - Subscription system for WebSocket event streaming
  - Integrated into main app (subscription active in `main.rs`)

**Integration:**

- Both modules exported in `lib.rs`
- `ParkedAgents` variant added to `CursorTab` enum (visible in UI)
- Activity feed subscription started at app initialization

**Pending:**

- HTTP API endpoint (`GET /api/agents/parked`) requires daemon refactor to share state
- Full WebSocket event streaming for activity feed

## March 17, 2026

### CLI Agents Preset Selection UI Improvements

**Enhanced preset selector in `cli_agents.rs`:**

- Category grouping with BTreeMap for organized display
- Scrollable preset buttons (150px height) with selection state
- Preset info card showing:
  - Name with checkmark icon
  - Category badge
  - Description
  - Prefix/suffix line counts
  - Edit/Delete buttons for custom presets
  - "Built-in preset" label for system presets
- Enhanced prompt preview with collapsible sections:
  - PREFIX section (green header)
  - YOUR TASK section (blue header)
  - SUFFIX section (orange header)
  - Character and line count totals
  - Truncation for >500 char sections
- `build_preview_section()` helper function for reusable section rendering

### Orchestrator Panel Improvements (`orchestrator_panel.rs`)

**New State Fields:**

- `pending_mode_change: Option<OrchestratorMode>` - Confirmation dialog state
- `stats: OrchestratorStats` - Daily dialog/handling statistics

**New Message Types:**

- `RequestModeChange` - Triggers confirmation for dangerous modes
- `ConfirmModeChange` - Confirms pending mode change
- `CancelModeChange` - Cancels pending mode change
- `SelectDecision` - Selects history item for details

**New View Functions:**

- `view_mode_confirm_dialog()` - Confirmation dialog for Spectator/Autonomous modes
- `view_decision_history()` - Scrollable history panel with last 10 decisions
- Stats row in mode selector (dialogs today, auto/manual counts)

**Features:**

- Mode switching confirmation for dangerous modes (Spectator, Autonomous)
- Warning text specific to each mode
- Decision history toggle button
- History items show: priority emoji, dialog ID, auto/manual badge, timestamp, response preview
- `format_timestamp()` helper for relative time display

**Unit Tests (10 tests):**

- `test_orchestrator_state_default`
- `test_set_mode_updates_engine`
- `test_add_to_triage_basic`
- `test_add_to_triage_full`
- `test_mode_duration`
- `test_stats_default`
- `test_format_duration`
- `test_format_timestamp_recent`
- `test_pending_mode_change_workflow`
- `test_cancel_mode_change`

### Orchestrator WebSocket Integration (`main.rs`)

**New Subscription:**

- `orchestrator_ws_subscription()` - Active on Orchestrator tab
- `orchestrator_ws_worker()` - WebSocket consumer using `spawn_orchestrator_websocket`

**New Handler:**

- `handle_orchestrator_ws_event()` - Processes real-time dialog events

**Event Handling:**

| Event | Action |
|-------|--------|
| `Connected` | Sets `daemon_connected = true` |
| `DialogCreated` | Evaluates via DecisionEngine, auto-handles or triages |
| `DialogAnswered` | Removes from triage queue and pending dialogs |
| `DialogEscalated` | Sets triage state to Manual |
| `Ping` | No-op heartbeat |

**Stats Tracking:**

- `dialogs_today` incremented on new dialogs
- `auto_handled_today` incremented on auto-responses
- `user_handled_today` available for manual responses

### Keyboard Shortcuts (`main.rs`)

**New Types:**

- `KeyboardShortcut` enum with 7 variants

**Subscription:**

- `keyboard_shortcut_subscription()` using `iced::event::listen_with`

**Handler:**

- `handle_keyboard_shortcut()` - Dispatches to appropriate message handlers

**Shortcuts (active on Orchestrator tab):**

| Shortcut | Action |
|----------|--------|
| Ctrl+1 | Set mode to UserActive |
| Ctrl+2 | Set mode to UserDelegate |
| Ctrl+3 | Request Spectator mode (with confirmation) |
| Ctrl+4 | Request Autonomous mode (with confirmation) |
| Ctrl+H | Toggle history panel |
| Ctrl+R | Refresh mode from daemon |
| Ctrl+Z | Undo last decision |

### Files Modified

| File | Changes |
|------|---------|
| `ui-iced/src/cli_agents.rs` | +363 lines - Enhanced preset selector, prompt preview |
| `ui-iced/src/orchestrator_panel.rs` | +200 lines - Confirmation, history, stats, tests |
| `ui-iced/src/main.rs` | +150 lines - WebSocket handler, keyboard shortcuts |

## April 7, 2026 - Documentation Audit

**Agent Session Summary:**

Comprehensive documentation review and update of AGENTS.md. Added documentation for 7 previously undocumented modules, catalogued 4 TODOs, identified technical debt (type duplication), and updated README.md with newer features.

### Recent Commits (March 17 - April 7, 2026)

| Commit | Description |
|--------|-------------|
| `4a97079` | feat(android): Context-aware FAB and radial menu |
| `5003e61` | feat(android): Add WorkManager for periodic widget updates |
| `c60b856` | perf(ui): Add lazy WebSocket connections and memory stats tracking |
| `2318937` | feat(ui): Complete orchestrator panel integration |
| `d1b73fe` | refactor: migrate to flake-parts architecture |
| `1df1463` | perf(ui): Add profiling module for performance monitoring (P2) |
| `bfc6925` | perf(ui): Implement P1 optimizations from performance audit |
| `bfc4c56` | docs: Add desktop performance audit (CS-PERF-001) |

**Performance Audit Reference:** See `docs/PERF-AUDIT-2026-03-17.md` for detailed memory management, subscription efficiency, and profiling instrumentation.

### Undocumented Modules (Now Documented)

#### Subagents Panel (`ui-iced/src/subagents.rs`)

Real-time sub-agent monitoring via synapsix-terminal-monitor D-Bus service.

**Core Types:**

- `SubagentPanelState` - Panel state with service availability, stats, command history
- `CommandRecord` - Terminal command with timing, exit code, error detection
- `MonitorStats` - Aggregate stats (total/active/failed commands, error rate)
- `ErrorDetection` - Detected error patterns with category, suggestion, confidence
- `AgentCodeName` - NATO phonetic + color code names (e.g., "Alpha-Red")
- `CodeNameRegistry` - Hash-based code name assignment for terminals

**D-Bus Integration:**

| Constant | Value |
|----------|-------|
| `DBUS_SERVICE` | `sh.synapsix.TerminalMonitor` |
| `DBUS_PATH` | `/sh/synapsix/TerminalMonitor` |
| `DBUS_INTERFACE` | `sh.synapsix.TerminalMonitor1` |

**D-Bus Methods (via `dbus_client` module):**

- `get_stats()` - Monitor statistics
- `get_recent()` - Recent commands
- `get_subagents()` - Sub-agent commands specifically
- `get_running()` - Currently running commands
- `check_service()` - Service availability

#### XX-Zones Window Positioning (`ui-iced/src/zones.rs`)

Deterministic window positioning using logical zones (Phase 1: Iced native APIs).

**Core Types:**

- `ZoneManager` - Tracks windows, positions, and layouts
- `ZoneLayout` - Presets: MainWithSidePanel, SplitHorizontal/Vertical, ThreeColumn, Dashboard, FreeForm
- `Zone` - Logical screen region with handle, position, dimensions
- `WindowPlacement` - Window position within a zone
- `ZoneSnapshot` - JSON-serializable state for Phosphor capture

**Layout Methods:**

- `layout_main_with_side_panel()` - Main window + 500px side panel
- `layout_dashboard()` - Centered main (65%) + side panel
- `calculate_layout()` → `LayoutApplication` with main/side_panel configs
- `export_snapshot()` - Write to `/tmp/continuum-studio-zones.json`

**Future:** Upgrade to xx-zones Wayland protocol when compositor support available.

#### Task Queue Client (`ui-iced/src/task_queue_client.rs`)

HTTP and WebSocket client for Synapsix persistent task queue.

**API Endpoints:**

| Constant | Value |
|----------|-------|
| `DEFAULT_API_URL` | `http://localhost:4001/api/tasks` |
| `DEFAULT_WS_URL` | `ws://localhost:4001/ws/tasks` |

**Core Types:**

- `Task` - Task with id, content, priority, status, creator info
- `Priority` - Critical, High, Medium, Low, Backlog (with emoji)
- `TaskStatus` - Pending, Claimed, InProgress, Completed, Cancelled
- `Creator` - User, Agent, Cli, Unknown
- `QueueStats` - Count by status

**HTTP Methods (`TaskQueueHttpClient`):**

- `add_task()` / `add_task_with_creator()` - Create task
- `update_priority()`, `update_notes()` - Modify task
- `start_task()`, `complete_task()`, `cancel_task()` - State transitions
- `delete_task()` - Remove task
- `get_subtasks()`, `add_subtask()` - Hierarchical tasks
- `add_blocker()`, `remove_blocker()`, `get_blockers()` - Dependencies

**WebSocket Events (`TaskQueueEvent`):**

- `Connected`, `Disconnected`
- `InitialState { tasks, stats, current_task }`
- `TaskAdded`, `TaskUpdated`, `TaskStarted`, `TaskCompleted`, `TaskCancelled`
- `TaskClaimed { task, agent_id }`, `TaskReleased`, `TaskRemoved`

#### Coordinator Client (`ui-iced/src/coordinator_client.rs`)

HTTP client for Agent Coordinator service (multi-agent conflict resolution).

**API Endpoint:** `http://localhost:4001/api/coordination/agents`

**Core Types:**

- `Agent` - Registered agent with type, status, focus, capabilities, file claims
- `AgentType` - SessionAgent, SubAgent
- `AgentStatus` - Active, Idle, Waiting, Completed, Disconnected
- `AgentFocus` - repos, files, area, description
- `Conflict` - Detected conflict between agents

**HTTP Methods (`CoordinatorHttpClient`):**

- `list_agents()`, `get_agent()` - Query agents
- `register_agent()` - Register new agent
- `set_focus()` - Update agent focus
- `heartbeat()` - Keep-alive
- `get_conflicts()`, `resolve_conflict()` - Conflict management
- `get_context()` - Get coordination context for an agent
- `suggest_agent()` - Suggest agent for a task

#### Feed Client (`ui-iced/src/feed_client.rs`)

Activity Feed HTTP/WebSocket client for real-time updates.

**API Endpoints:**

| Constant | Value |
|----------|-------|
| `DEFAULT_API_URL` | `http://localhost:4001/api/feed` |
| `DEFAULT_WS_URL` | `ws://localhost:4001/ws/feed` |

**Core Types:**

- `FeedEntry` - Activity entry with source, event_type, title, body, links, metadata
- `FeedSource` - Git, FileChange, Agent, TaskQueue, Nesy, System
- `FeedLink` - Hyperlink in entry
- `FeedStats` - Entry counts by source/type

**HTTP Methods (`FeedHttpClient`):**

- `post_update()` - Post agent NL update
- `get_summary()` - Get feed summary for context injection
- `get_stats()` - Feed statistics
- `trigger_git_poll()` - Trigger git repository poll

#### Profiling Module (`ui-iced/src/profiling.rs`)

Lightweight performance profiling for timing spans.

**Usage:**

```rust
use continuum_studio_iced::profiling::ProfileSpan;

// Default 16ms threshold (one frame at 60 FPS)
let _span = ProfileSpan::frame("render_view");

// 8ms threshold (120 FPS)
let _span = ProfileSpan::fast("hot_path");

// Always log
let _span = ProfileSpan::always("debug_timing");

// Custom threshold
let _span = ProfileSpan::new("operation", 50);
```

**Macros:**

- `profile_span!("name")` - Frame threshold (16ms)
- `profile_span!("name", 100)` - Custom threshold
- `profile_always!("name")` - Always log

**Enable:** `RUST_LOG=continuum_studio_iced::profiling=debug`

#### TaskQueue Widget (`ui-iced/src/widgets/task_queue.rs`)

Complete task management widget with multiple panels.

**Widget Sizes:**

- `Compact` - Badge with current task
- `Standard` - Current + pending list
- `Full` / `Extended` - Full features with quick-add

**Panel Layouts:**

- `Single(panel)` - One panel
- `SideBySide(left, right)` - Two columns
- `Stacked(top, bottom)` - Two rows
- `ThreeColumn(left, center, right)` - Three panels

**Active Panels:** Tasks, Agents, History

**Features:**

- Real-time WebSocket updates
- Agent tracking with status icons
- Task history with duration
- Quick-add input
- Priority sorting

### Updated Key Files Table

| Feature | File |
|---------|------|
| Diagram rendering | `ui-iced/src/widgets/diagram.rs` |
| Offline mode | `ui-iced/src/offline.rs` |
| CLI agents | `ui-iced/src/cli_agents.rs` |
| CLI agents client | `ui-iced/src/cli_agents_client.rs` |
| Decision engine | `ui-iced/src/decision_engine.rs` |
| Orchestrator panel | `ui-iced/src/orchestrator_panel.rs` |
| Dialog client | `ui-iced/src/dialog_client.rs` |
| Module exports | `ui-iced/src/lib.rs` |
| Parked agents panel | `ui-iced/src/parked_agents.rs` |
| Parked agents client | `ui-iced/src/parked_agents_client.rs` |
| Activity feed | `ui-iced/src/activity_feed.rs` |
| Activity stream client | `ui-iced/src/activity_stream_client.rs` |
| **Subagents panel** | `ui-iced/src/subagents.rs` |
| **XX-Zones positioning** | `ui-iced/src/zones.rs` |
| **Task queue client** | `ui-iced/src/task_queue_client.rs` |
| **Coordinator client** | `ui-iced/src/coordinator_client.rs` |
| **Feed client** | `ui-iced/src/feed_client.rs` |
| **Profiling** | `ui-iced/src/profiling.rs` |
| **TaskQueue widget** | `ui-iced/src/widgets/task_queue.rs` |

### Known TODOs

| Location | Description | Priority | Effort | Status |
|----------|-------------|----------|--------|--------|
| `main.rs:725` | Detect system theme preference | Low | S | ✅ Resolved - dark-light crate added |
| `main.rs:2767` | Add delete profile to Core API | Medium | M - Elixir + Rust changes | Open |
| `main.rs:4492` | Integrate agent tracking from task queue | High | L - WebSocket state sync | Open |
| `cli_agents.rs:770` | Fetch full agent details for each ID | Medium | S - HTTP call in handler | ✅ Resolved - Added FetchAgentDetails task |
| `DialogScreen.kt:1245` | Handle link click in Android | Low | S | ✅ Resolved - Added LocalUriHandler |

**Size Legend:** S = Small (< 1 hour), M = Medium (1-4 hours), L = Large (> 4 hours)

### Crates Directory

Only one shared crate exists:

- `crates/synapsix-theme/` - Shared theming for COSMIC/VS Code integration

### Technical Debt

**Type Duplication (Priority: Medium)**

`task_queue_client.rs` and `widgets/task_queue.rs` both define:
- `Priority` enum (Critical, High, Medium, Low, Backlog)
- `TaskStatus` enum (Pending, Claimed, InProgress, Completed, Cancelled)
- `Task` struct
- `QueueStats` struct

The widget should import from the client module to avoid drift. Currently they're nearly identical but `task_queue_client.rs` has more fields (e.g., `created_by`, `agent_id`, `session_id`).

**Detailed Fix Plan:**

1. In `widgets/task_queue.rs`:
   ```rust
   // Replace local definitions with:
   pub use crate::task_queue_client::{Priority, TaskStatus, Task, QueueStats};
   ```

2. In `widgets/mod.rs`:
   - Remove `Priority`, `TaskStatus`, `Task`, `QueueStats` from task_queue re-exports
   - Add note that these are now in `task_queue_client`

3. Update `lib.rs` to avoid duplicate exports

4. Test compilation and fix any type mismatches

**Impact:** ~40 lines removed, API surface simplified. Widget-specific types (`Agent`, `AgentType`, `AgentStatus`, `TaskHistoryEntry`) remain in widget.

**System Theme Detection (Priority: Low)**

`derive_theme()` in `main.rs:725` falls back to Dark for `ThemePreference::System`. 

**Recommended fix:** Add `dark-light` crate (https://github.com/rust-dark-light/rust-dark-light)
- Detects via XDG Desktop Portal D-Bus API
- Works in Flatpak sandboxes
- Returns `Mode::Dark`, `Mode::Light`, or `Mode::Unspecified`

```rust
// Cargo.toml
dark-light = "1.0"

// main.rs
ThemePreference::System => {
    match dark_light::detect() {
        Ok(dark_light::Mode::Dark) => Theme::Dark,
        Ok(dark_light::Mode::Light) => Theme::Light,
        _ => Theme::Dark, // Fallback
    }
}
```

## April 7, 2026 - Documentation Continued

### Additional Undocumented Modules (Now Documented)

#### Core IPC Client (`ui-iced/src/core.rs`)

Unix socket client for communicating with the Elixir backend (Studio Core).

**Socket Path:** `/tmp/continuum-studio.sock`

**Core Types:**

- `ReconnectConfig` - Backoff configuration (initial/max delay, heartbeat interval)
- `ConnectionState` - Disconnected, Connecting, Connected, Reconnecting
- `CoreRequest` - Outbound commands to Elixir
- `CoreResponse` - Inbound events from Elixir
- `CoreClient` - High-level client wrapper
- `CursorVersion` - Version info with status, era, notes
- `VersionStatus` - Available, Installed, Running, Downloading
- `AuthStatus` / `AuthState` / `AuthProfile` - Authentication management
- `Workspace` / `GitStats` - Workspace tracking with git info
- `Session` / `VersionStats` / `InstalledVersion` - Version management

**Request Types (`CoreRequest`):**

| Request | Purpose |
|---------|---------|
| `GetVersions` | List all versions |
| `GetInstalled` | List installed only |
| `LaunchVersion` | Run a Cursor version |
| `InstallVersion` / `UninstallVersion` | Version management |
| `BatchUninstallVersions` | Bulk uninstall |
| `GetDiskUsageAll` | Disk usage stats |
| `GetSessions` / `GetStats` | Session and version stats |
| `GetWorkspaces` / `RegisterWorkspace` | Workspace management |
| `GetAuthStatus` / `GetAuthStatuses` | Auth status queries |
| `ExtractAuth` / `ApplyAuth` / `ListProfiles` | Auth profile management |
| `Ping` | Health check |

**Connection Function:**

```rust
let (request_tx, response_rx, state_rx) = spawn_core_connection(socket_path);
```

#### Session Monitoring (`ui-iced/src/monitoring.rs`)

Real-time metrics collection for running Cursor sessions via `/proc` filesystem.

**Core Types:**

- `SessionMetrics` - Point-in-time metrics (CPU, memory, threads, FDs, health)
- `HealthStatus` - Healthy, Warning, Critical, Unknown (with RGB colors)
- `SessionHistory` - Rolling history (60 data points) with VecDeque buffers
- `SessionMonitor` - Collector with CPU delta calculation
- `DashboardData` - Aggregate stats for UI display

**Metrics Collected:**

| Metric | Source | Thresholds |
|--------|--------|------------|
| CPU % | `/proc/[pid]/stat` | >50% Warning, >90% Critical |
| Memory | `/proc/[pid]/statm` | >4GB Warning, >8GB Critical |
| Threads | `/proc/[pid]/stat` | >100 Warning |
| File descriptors | `/proc/[pid]/fd` count | >5000 Warning, >10000 Critical |
| Process state | `/proc/[pid]/stat` | R/S/D/Z |

**Usage:**

```rust
let mut monitor = SessionMonitor::new();
let metrics = monitor.collect_all_metrics(&[pid1, pid2]);
let dashboard = DashboardData::from_metrics(&metrics);
```

#### Chat Pipeline (`ui-iced/src/chat_pipeline.rs`)

HTTP client and types for the Synapsix Chat Pipeline API (message history, search, export).

**API Base:** `http://localhost:4001/api/chat`

**Core Types:**

- `ChatStats` / `StoreStats` / `SummarizerStats` - Pipeline statistics
- `Conversation` / `ConversationDetail` / `ChatMessage` - Conversation data
- `Topic` / `TopicList` - Topic clustering
- `SearchResults` / `SearchResult` - Semantic/keyword search
- `ScanLocations` / `ScanLocation` - Database scan paths
- `WatcherStatus` / `PipelineInfo` - Real-time watcher status
- `ExportFormat` - Markdown, JSON, HTML, Text
- `TrainingFormat` - OpenAI, Alpaca, ShareGPT (for fine-tuning export)
- `TrainingFilters` / `SanitizationOptions` / `AugmentationOptions` - Training export config

**Sub-Views (`ChatSubView`):**

- `Dashboard` - Stats overview
- `Scanner` - Database locations
- `Conversations` - Browse conversations
- `Topics` - Topic clusters
- `Search` - Search interface
- `Export` - Single conversation export
- `Watcher` - Real-time watcher status

**API Methods (`ChatApiClient`):**

| Method | Purpose |
|--------|---------|
| `fetch_stats()` | Pipeline statistics |
| `fetch_conversations(limit)` | List conversations |
| `fetch_conversation(id)` | Get conversation detail |
| `fetch_topics()` | Topic list |
| `search(query, mode)` | Search (keyword/semantic) |
| `batch_ingest()` | Trigger ingestion |
| `export_conversation(id, format)` | Export single |
| `fetch_training_stats(filters)` | Training export stats |
| `training_export(request)` | Full training export |

#### Service Management (`ui-iced/src/services.rs`)

Manages starting, stopping, and monitoring external services.

**Core Types:**

- `ServiceConfig` - Paths and ports for managed services
- `ServiceStatus` - Running, Stopped, Unknown, Starting, Failed
- `ServiceInfo` - Service metadata with start/check commands
- `ServiceManager` - Service lifecycle management

**Managed Services:**

| Service | Description | Default Port |
|---------|-------------|--------------|
| Studio Core | Elixir/OTP backend | Unix socket |
| synapsix-dialog-daemon | Interactive dialogs | 8080 |

**Default Paths:**

```rust
core_path: "/home/e421/continuum-studio/core/studio_core"
core_socket: "/tmp/continuum-studio.sock"
dialog_web_port: 8080
```

#### Theme Module (`ui-iced/src/theme/`)

COSMIC and VS Code theme integration for consistent styling.

**Files:**

| File | Purpose |
|------|---------|
| `mod.rs` | Re-exports, `AppColors`, `SemanticColors` |
| `cosmic.rs` | COSMIC desktop presets (Dark, Light, PopOrange, WarmAmber, CoolBlue, Mint) |
| `vscode.rs` | VS Code theme parsing and conversion |

**Exports:**

- `AppColors` - Primary application color palette
- `CosmicPalette` - COSMIC-specific colors
- `CosmicThemePreset` - Preset configurations
- `SemanticColors` - Semantic color mappings (success, warning, error)

### Updated Key Files Table (Complete)

| Category | File | Purpose |
|----------|------|---------|
| **Core IPC** | `core.rs` | Elixir backend communication |
| **Monitoring** | `monitoring.rs` | Session metrics collection |
| **Chat** | `chat_pipeline.rs` | Chat history and export |
| **Services** | `services.rs` | Service lifecycle management |
| **Theme** | `theme/` | COSMIC/VS Code theming |
| **Diagrams** | `widgets/diagram.rs` | Mermaid/D2 rendering |
| **Offline** | `offline.rs` | Operation queue |
| **CLI Agents** | `cli_agents.rs` | Agent management UI |
| **CLI Client** | `cli_agents_client.rs` | Agent HTTP/WebSocket |
| **Decision** | `decision_engine.rs` | Auto dialog handling |
| **Orchestrator** | `orchestrator_panel.rs` | Mode control UI |
| **Dialog** | `dialog_client.rs` | D-Bus dialogs |
| **Exports** | `lib.rs` | Public API |
| **Parked** | `parked_agents.rs` | Agent parking UI |
| **Parked Client** | `parked_agents_client.rs` | Parking HTTP |
| **Activity** | `activity_feed.rs` | Event display |
| **Activity Client** | `activity_stream_client.rs` | Event streaming |
| **Subagents** | `subagents.rs` | Sub-agent monitoring |
| **Zones** | `zones.rs` | Window positioning |
| **Task Queue** | `task_queue_client.rs` | Task HTTP/WebSocket |
| **Coordinator** | `coordinator_client.rs` | Agent coordination |
| **Feed** | `feed_client.rs` | Activity feed HTTP |
| **Profiling** | `profiling.rs` | Performance spans |
| **TaskQueue Widget** | `widgets/task_queue.rs` | Task management widget |
| **Log Capture** | `log_capture.rs` | In-memory log buffer for UI |
| **Updater** | `updater.rs` | Multi-forge self-update system |
| **Sessions** | `sessions.rs` | Cursor session tracking |
| **Settings** | `settings.rs` | App configuration persistence |

## April 7, 2026 - Code Quality Session

### Changes Made

**1. Type Deduplication (widgets/task_queue.rs)**

Resolved technical debt identified in earlier audit. The widget now imports core types from `task_queue_client.rs` instead of duplicating them:

```rust
// Before: ~95 lines of duplicated type definitions
// After: Single import line
pub use crate::task_queue_client::{Priority, QueueStats, Task, TaskStatus};
```

This prevents drift between API client types and widget types.

**2. System Theme Detection (main.rs)**

Added `dark-light` crate (v2.0.0) for automatic system theme detection via XDG Desktop Portal D-Bus API:

```rust
ThemePreference::System => {
    match dark_light::detect() {
        Ok(dark_light::Mode::Dark) => Theme::Dark,
        Ok(dark_light::Mode::Light) => Theme::Light,
        Ok(dark_light::Mode::Unspecified) | Err(_) => Theme::Dark,
    }
}
```

Works with COSMIC, GNOME, KDE, and other freedesktop-compliant desktops.

**3. Clippy Warning Fixes**

Fixed 4 clippy warnings:

| Warning | Fix | File |
|---------|-----|------|
| `Iterator::last` on `DoubleEndedIterator` | Use `next_back()` | `cli_agents.rs` |
| `field_reassign_with_default` | Use struct literal with spread | `cli_agents.rs` |
| Manual prefix stripping | Use `strip_prefix()` | `widgets/diagram.rs` |

**4. README.md Updates**

Added documentation for recent features:
- System theme detection
- Parked agents panel
- Subagents panel
- Agent coordination

### Remaining Clippy Warnings (Acceptable)

| Warning | Location | Reason |
|---------|----------|--------|
| Too many arguments (8/7) | `cli_agents_client.rs:247` | Would require config struct refactor |
| Large enum variant size | `cli_agents_client.rs:1099` | Would require boxing |
| `from_str` method name | Various | Intentional, not implementing `FromStr` trait |

### Files Modified

| File | Changes |
|------|---------|
| `ui-iced/Cargo.toml` | +1 dep (dark-light) |
| `ui-iced/src/main.rs` | System theme detection |
| `ui-iced/src/widgets/task_queue.rs` | Type deduplication (~80 lines removed) |
| `ui-iced/src/widgets/mod.rs` | Updated re-exports |
| `ui-iced/src/cli_agents.rs` | Clippy fixes |
| `ui-iced/src/widgets/diagram.rs` | `strip_prefix` fix |
| `README.md` | Feature documentation |

## April 7, 2026 - Code Quality Session (Continued)

### Additional Fixes

**1. Eliminated `unwrap()` in `view_for_window` (main.rs)**

Replaced awkward pattern that used `.map()` then `.unwrap()` on the same Option:

```rust
// Before: Used map then unwrap - technically safe but not idiomatic
match window_state.map(|ws| ws.window_type) {
    Some(WindowType::TiledPanel) => {
        let ws = window_state.unwrap(); // redundant unwrap
        view_tiled_panel_window(state, ws.vertical_split, ws.split_ratio)
    }
    ...
}

// After: Direct pattern matching
match state.windows.get(&window_id) {
    Some(ws) => match ws.window_type {
        WindowType::TiledPanel => {
            view_tiled_panel_window(state, ws.vertical_split, ws.split_ratio)
        }
        ...
    },
    None => view_main_window(state),
}
```

**2. Fixed path `unwrap()` in diagram rendering (widgets/diagram.rs)**

Added proper error handling for path-to-string conversion:

```rust
// Before: Could panic on invalid UTF-8 paths
input_file.to_str().unwrap()

// After: Returns error instead of panicking
let input_str = input_file
    .to_str()
    .ok_or_else(|| "Invalid UTF-8 in input path".to_string())?;
```

**3. Fixed awkward `is_err() + unwrap()` pattern (services.rs)**

Replaced confusing pattern with idiomatic `map().unwrap_or()`:

```rust
// Before: Safe but confusing - checks is_err then unwraps
if mix_check.is_err() || !mix_check.unwrap().status.success() { ... }

// After: Clear and idiomatic
let mix_available = std::process::Command::new("which")
    .arg("mix")
    .output()
    .map(|o| o.status.success())
    .unwrap_or(false);

if !mix_available { ... }
```

### Verification

- All 55 tests pass
- No new clippy warnings introduced
- Changes committed in `9cd64b1` and `701368e`

### Android Fix (DialogScreen.kt)

Resolved the link click TODO by adding `LocalUriHandler` integration:

```kotlin
// Before: Empty click handler
.clickable { /* TODO: Handle link click */ }

// After: Opens URL in browser
val uriHandler = LocalUriHandler.current
.clickable { uriHandler.openUri(url) }
```

Updated `QuickLinkRow` composable to accept a `url` parameter and linked the quick access buttons to actual URLs:
- Web Interface → https://dialog.datapunk.dev
- Source Code → https://codeberg.org/Distracted/continuum-studio
- Documentation → https://codeberg.org/Distracted/continuum-studio/wiki

Committed in `d83f245`.
