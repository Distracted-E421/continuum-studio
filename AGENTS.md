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

**Branch**: `iced-migration` (4 commits ahead of origin)

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
