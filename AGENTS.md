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

- **April 18, 2026**: `cargo build --release`, `cargo clippy --all-targets --all-features -- -D warnings`, and `cargo test` (139 tests) all pass on `iced-migration`. Overnight AFK session re-verified clippy/tests; removed dead `build_preview_section` helper in `cli_agents.rs` (only `build_preview_section_with_tokens` is used). Crate-local notes: `ui-iced/AGENTS.md`. Intentional `#[allow(dead_code)]` remains for planned UI phases (e.g. `main.rs` CoreRequest variants, `updater.rs` helpers, feed WS serde fields). Earlier same day: `SpawnAgentWithPresetParams` in `cli_agents_client.rs` so `spawn_agent_with_preset` satisfies `clippy::too_many_arguments` under `-D warnings`.
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

## Module Reference

### Core Modules

| Module | File | Description |
|--------|------|-------------|
| **Decision Engine** | `decision_engine.rs` | Automated dialog handling by mode (UserActive/Delegate/Spectator/Autonomous) |
| **Orchestrator Panel** | `orchestrator_panel.rs` | UI for mode control, triage queue, decision history |
| **CLI Agents** | `cli_agents.rs` | Agent management with presets, batch launch, token estimation |
| **Subagents Panel** | `subagents.rs` | Real-time monitoring via synapsix-terminal-monitor D-Bus |
| **Zones** | `zones.rs` | Window positioning with ZoneManager, layouts, export |
| **Task Queue Client** | `task_queue_client.rs` | HTTP/WebSocket for Synapsix task queue |
| **Coordinator Client** | `coordinator_client.rs` | Multi-agent conflict resolution |
| **Feed Client** | `feed_client.rs` | Activity feed HTTP/WebSocket |
| **Parked Agents** | `parked_agents.rs` | Agent parking UI with assign modal |
| **Activity Feed** | `activity_feed.rs` | Event display with filters |
| **Dialog Client** | `dialog_client.rs` | D-Bus dialogs, orchestrator modes |
| **Core IPC** | `core.rs` | Unix socket to Elixir backend |
| **Monitoring** | `monitoring.rs` | Session metrics via /proc |
| **Chat Pipeline** | `chat_pipeline.rs` | Message history, search, export |
| **Services** | `services.rs` | Service lifecycle management |
| **Profiling** | `profiling.rs` | Performance timing spans |
| **Diagram Widget** | `widgets/diagram.rs` | Mermaid/D2 rendering |
| **TaskQueue Widget** | `widgets/task_queue.rs` | Task management UI |
| **Offline Mode** | `offline.rs` | Operation queue, connection tracking |
| **Theme** | `theme/` | COSMIC/VS Code integration |

### Orchestrator Modes

| Mode | Low/Normal | High | Critical |
|------|-----------|------|----------|
| UserActive | → User | → User | → User |
| UserDelegate | Auto-handle | → User | → User |
| Spectator | Auto + claim timeout | Auto + claim | → User (timeout) |
| Autonomous | Auto-handle | Auto-handle | Queued for review |

### API Endpoints Used

| Endpoint | Purpose |
|----------|---------|
| `/api/cli-agents/*` | Agent management |
| `/api/presets/*` | Prompt presets |
| `/api/agent-dialogs/*` | Dialog inbox |
| `/api/orchestrator/mode` | Mode control |
| `/ws/cli-agents` | Agent events |
| `/ws/orchestrator` | Dialog stream |

### D-Bus Integration

| Service | Interface |
|---------|-----------|
| `sh.synapsix.Dialog` | `sh.synapsix.Dialog1` |
| `sh.synapsix.TerminalMonitor` | `sh.synapsix.TerminalMonitor1` |

### Test Coverage

- **139 Rust tests** (ui-iced)
- 40 tests in `harness/types.rs`
- 17 tests in `harness/server.rs`
- 14 tests in `settings.rs`, `errors.rs`
- 12 tests in `graph.rs`
- 10 tests in `orchestrator_panel.rs`
- 11 tests for token estimation/clipboard

## Development History

### March 2026

- **March 10**: Diagram rendering (Mermaid/D2), offline mode with operation queue
- **March 11**: Orchestrator dialog inbox, decision engine, CLI agent presets
- **March 12**: Parked agents UI, activity feed with WebSocket
- **March 17**: Enhanced preset selector, orchestrator panel improvements, keyboard shortcuts

### April 2026

- **April 7**: Documentation audit (7 modules documented), code quality fixes
- **April 18**: Clippy/warnings-as-errors clean, `SpawnAgentWithPresetParams` fix
