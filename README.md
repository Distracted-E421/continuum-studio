# Continuum Studio

[![License](https://img.shields.io/badge/license-SSPL--1.0-blue.svg)](LICENSE)

**AI orchestration platform for multi-agent control, monitoring, and verification.**

Continuum Studio coordinates AI agents across desktop and mobile: spawn and monitor headless CLI workers, triage agent dialogs with configurable autonomy, stream activity in real time, and verify actions through Synapsix. Built for self-hosted homelab and workstation workflows—not managed SaaS.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Continuum Studio                            │
├──────────────┬──────────────┬──────────────────┬────────────────┤
│  ui-iced/    │  android/    │  core/           │  crates/       │
│  Rust + iced │  Kotlin +    │  Elixir/OTP      │  Shared Rust   │
│  0.14        │  Compose     │                  │  libraries     │
│  (desktop)   │  (mobile)    │  studio_core +   │                │
│              │              │  agent_bridge    │                │
└──────┬───────┴──────┬───────┴────────┬─────────┴────────────────┘
       │              │                │
       │ Unix socket  │ WebSocket      │ In-process / IPC
       │              │ (:8080)        │
       └──────────────┴────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Synapsix                                │
│  Dialog daemon · Terminal monitor · Harnesses · NeSy · MCP      │
└─────────────────────────────────────────────────────────────────┘
```

| Component | Path | Role |
|-----------|------|------|
| **Desktop UI** | `ui-iced/` | Primary GUI (Rust + iced 0.14, COSMIC styling, Wayland-first) |
| **Mobile app** | `android/` | Remote dialogs, XR glasses overlay, agent camera, widget dashboard |
| **Studio Core** | `core/studio_core/` | Orchestration hub: state, event bus, harness registry |
| **Agent Bridge** | `core/agent_bridge/` | Multi-provider AI abstraction (Claude, Ollama, Cursor) |

## Desktop UI (`ui-iced/`)

Rust + **iced 0.14** with COSMIC desktop integration.

### CLI Agents

Spawn, monitor, and respond to headless Cursor CLI workers:

- Preset/snippet system for prompt templates
- Batch launch across workspaces
- Dialog inbox for worker responses
- HTTP/WebSocket client to Synapsix agents API

### Orchestrator

Automated dialog handling with decision engine:

| Mode | Behavior |
|------|----------|
| **User Active** | All dialogs routed to the user |
| **User Delegate** | Routine dialogs auto-handled; critical items escalate |
| **Spectator** | Orchestrator in charge; user can claim within timeout |
| **Autonomous** | Full auto-handling; critical items queued |

### Additional Features

- **Diagrams** — Inline Mermaid and D2 rendering
- **Offline Mode** — Operation queue with persistence
- **Activity Feed** — Real-time event streaming
- **Task Queue** — Synapsix Workflow integration

**Tests:** 139 unit tests (`cargo test`)

## Android App (`android/`)

Kotlin + Jetpack Compose + Material 3. Targets API 34+ (Samsung S23 Ultra).

### XR Middleware Overlay (RayNeo 3)

Glasses-first UI beyond Dex/mirror:

- **`ZonedGlassesLayout`** — left: dialog content, center: see-through, right: options
- **`DialogControlPanel`** — phone as input device in XR mode

### Agent Camera

Capture images from phone camera and send to agents:

- CameraX preview, capture, and send flow
- Connection indicators for Synapsix and Cognix backends

### Widget Bay

Customizable grid dashboard for monitoring agent and system state.

### Dialog Client

WebSocket connection to Synapsix dialog daemon (port 8080):

- Background notification service
- Multi-endpoint configuration
- Offline reply queue

## Synapsix Integration

| Module | Integration |
|--------|-------------|
| `dialog_client.rs` | D-Bus to `synapsix-dialog-daemon` |
| `cli_agents_client.rs` | HTTP/WebSocket to Synapsix agents API |
| `decision_engine.rs` | Auto-handling keyed to orchestrator mode |
| `task_queue_client.rs` | Synapsix Workflow task queue |

## D-Bus Interfaces

| Service | Interface | Purpose |
|---------|-----------|---------|
| `sh.synapsix.Dialog` | `sh.synapsix.Dialog1` | Dialog requests and responses |
| `sh.synapsix.TerminalMonitor` | `sh.synapsix.TerminalMonitor1` | Sub-agent stats and commands |
| `sh.datapunk.ContinuumStudio` | `sh.datapunk.ContinuumStudio1` | Canvas control |

## Build & Run

### Desktop UI

```bash
cd ui-iced
cargo build --release
cargo run --release
```

### Android

```bash
cd android
./gradlew assembleDebug
```

### Studio Core

```bash
cd core/studio_core
mix deps.get && mix compile
```

## Related Projects

| Project | Role |
|---------|------|
| [Synapsix](https://codeberg.org/datapunk/synapsix) | Harness orchestrator, dialog daemon, MCP servers |
| [Cognix](https://codeberg.org/datapunk/cognix) | Cognitive assistant; visual context API |
| [Phosphor](https://codeberg.org/datapunk/phosphor) | Wayland screen capture |

## License

**SSPL-1.0** (Server Side Public License v1)

Copyright 2024–2026 DataPunk Contributors

See [LICENSE](LICENSE) for the full text.
