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

### Android App (`android/`)

- Widget Bay - Customizable grid dashboard
- Dialog Client - WebSocket to port 8080
- Notification Service - Background dialog alerts
- Auto-reconnect with exponential backoff
- Coordination Dashboard for multi-agent monitoring

### Core (`core/`)

- Elixir/OTP orchestration hub
- Agent Bridge for multi-provider AI abstraction
- Version Registry
- Workspace Tracker

## Development Preferences

- Use Nushell for scripting (not bash)
- Use fast_shell MCP tool for commands when available
- Follow language philosophy: Nix > Nushell > Elixir > Rust > Kotlin

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
| Module exports | `ui-iced/src/lib.rs` |

### March 11, 2026

**Orchestrator Dialog Inbox Integration**

- Extended `PendingDialog` with agent routing fields: `source`, `priority`, `workspace`, `orchestrator_id`
- Added `DialogSource` enum: Orchestrator, SessionAgent, SubAgent, External
- Added `DialogPriority` enum: Low, Normal, High, Critical
- Updated HTTP client:
  - `fetch_pending_dialogs()` → `/api/agent-dialogs`
  - `respond_to_dialog()` → `/api/agent-dialogs/:id/respond`
  - Added `escalate_dialog()` for critical escalation
- Added `OrchestratorWsEvent` types for real-time dialog notifications
- Added `spawn_orchestrator_websocket()` for `/ws/orchestrator` connection
- UI shows source badge and priority emoji on dialog cards
