# Continuum Studio

[![License](https://img.shields.io/badge/license-SSPL--1.0-blue.svg)](LICENSE)

**AI Orchestration Platform** for multi-agent control, monitoring, and verification.

![Continuum Studio](continuum-studio.png)

---

## Overview

Continuum Studio is a modular platform for orchestrating AI agents across multiple applications and machines. It combines:

- **Desktop UI** (Rust/iced with COSMIC styling) - Dashboard for harness management and monitoring
- **Mobile App** (Kotlin/Jetpack Compose) - Remote dialog and widget bay
- **Studio Core** (Elixir/OTP) - Central orchestration hub
- **Agent Bridge** (Elixir) - Multi-provider AI abstraction layer
- **Synapsix** - Harness orchestrator, dialog daemon, terminal monitoring

## Architecture

```
                    CONTINUUM STUDIO
 ┌───────────────────────────────────────────────┐
 │                                               │
 │  ┌─────────────┐  ┌────────────┐              │
 │  │ Desktop UI  │  │ Mobile App │              │
 │  │ (Rust/iced) │  │ (Kotlin)   │              │
 │  └──────┬──────┘  └──────┬─────┘              │
 │         │   Unix Socket   │  WebSocket         │
 │         └────────┬────────┘                    │
 │                  ▼                             │
 │         ┌────────────────┐                     │
 │         │  Studio Core   │                     │
 │         │  (Elixir/OTP)  │                     │
 │         └───────┬────────┘                     │
 │                 │                              │
 │    ┌────────────┼────────────┐                 │
 │    ▼            ▼            ▼                 │
 │ ┌────────┐ ┌────────┐ ┌──────────────┐        │
 │ │ Agent  │ │Version │ │  Workspace   │        │
 │ │ Bridge │ │Registry│ │  Tracker     │        │
 │ └────────┘ └────────┘ └──────────────┘        │
 │                                               │
 └───────────────────┬───────────────────────────┘
                     │ CoreClient IPC
                     ▼
 ┌───────────────────────────────────────────────┐
 │                  SYNAPSIX                      │
 │                                               │
 │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │
 │  │ Terminal  │ │ Dialog   │ │  Harnesses   │  │
 │  │ Monitor  │ │ Daemon   │ │  (Cursor,    │  │
 │  │          │ │          │ │   Android,   │  │
 │  │          │ │          │ │   Godot)     │  │
 │  └──────────┘ └──────────┘ └──────────────┘  │
 │                                               │
 │  ┌──────────┐ ┌──────────┐ ┌──────────────┐  │
 │  │ NeSy     │ │ Service  │ │ Chat         │  │
 │  │ (Z3/SMT) │ │ Registry │ │ Pipeline     │  │
 │  └──────────┘ └──────────┘ └──────────────┘  │
 └───────────────────────────────────────────────┘
```

## Components

### Desktop UI (`ui-iced/`) - Current

**Framework**: Rust + iced 0.14 with COSMIC desktop styling

Features:
- Session management for multiple Cursor instances
- Service discovery and monitoring dashboard
- Chat message pipeline display
- VS Code theme compatibility
- Self-update system
- Settings management
- **System theme detection** - XDG Desktop Portal integration for automatic dark/light mode
- **Diagram rendering** - Mermaid and D2 via CLI tools
- **Full offline mode** - Operation queue with auto-sync
- **Orchestrator mode** - Decision engine for automated dialog handling
- **CLI Agents** - Spawn, monitor, and respond to headless Cursor agents
  - Preset/snippet system for prompt templates
  - Batch launch across workspaces
  - Dialog inbox for worker agent responses
- **Parked Agents** - Monitor and assign tasks to idle agents
- **Subagents Panel** - Terminal command monitoring via D-Bus
- **Task Queue** - Synapsix integration for persistent task management
- **Agent Coordination** - Multi-agent conflict resolution and context sharing
- **XX-Zones** - Deterministic window positioning (Wayland protocol prep)
- **Profiling** - Performance timing spans with threshold logging
- **Activity Feed** - Real-time event streaming from agents

```bash
cd ui-iced && cargo run --release
# Or with NixOS library paths:
./run-gui.sh
```

### Desktop UI (`ui/`) - Legacy

**Framework**: Rust + egui 0.29

Legacy implementation with D2 diagram viewer, tab system, and ETF IPC. Being replaced by iced UI.

### Android App (`android/`)

**Framework**: Kotlin + Jetpack Compose + Material 3

Features:
- **Widget Bay** - Customizable grid dashboard for monitoring
- **Dialog Client** - WebSocket connection to dialog daemon (port 8080)
- **Notification Service** - Background dialog alerts with type-specific icons
- **Auto-reconnect** - Resilient WebSocket with exponential backoff
- **Settings Screen** - Server URL, notifications, hold mode, about info
- **Cloudflare Access** - Service token authentication for tunnel access
- **History View** - Browse past dialog responses with reinvoke support
- **Pull-to-Refresh** - Manual refresh for dialogs and history
- **DataStore Persistence** - Settings survive app restarts
- **Multi-Endpoint Configuration** - Multiple server endpoints with auto-fallback
- **Context-aware FAB** - Floating action button with context-sensitive menu
- **WorkManager** - Periodic widget updates in background
- **Coordination Dashboard** - Multi-agent monitoring and coordination

### Studio Core (`core/studio_core/`)

**Framework**: Elixir/OTP

Central orchestration hub:
- **State Management** - ETS-backed with hot restart resilience
- **Event Bus** - Pub/sub broadcasting to all connected clients
- **Unix Socket IPC** - JSON-framed messages (`/tmp/continuum-studio.sock`)
- **Harness Registry** - Tracks connected Synapsix harnesses
- **Version Registry** - Cursor version management
- **Auth Manager** - Cursor authentication handling
- **Workspace Tracker** - Projects across Cursor versions

### Agent Bridge (`core/agent_bridge/`)

**Framework**: Elixir/OTP

Multi-provider AI abstraction:
- **Providers**: Claude (Anthropic), Ollama (local), Cursor (via harness)
- **Context Manager** - Session-based context tracking
- **Cost Tracker** - Token counting per provider
- **Rate Limiter** - Per-provider rate limiting
- **Middleware Pipeline** - Logger, Sanitizer, Validator, Telemetry, Content Filter

### Archive (`archive/`)

`cursor-docs-REBUILD-TARGET/` - Archived documentation indexing system (planned rebuild as separate service)

## Key Features

### Terminal Monitoring (New)

Deep integration with Cursor's process monitoring via Synapsix:
- CPU/memory tracking for all Cursor processes
- Agent terminal output capture
- Structured log parsing (v2.4.27+)
- Process tree visualization in dashboard

### Neurosymbolic AI (NeSy)

Agent action verification via SMT solvers:
- Constraint DSL for safety rules
- Formal verification before execution
- Complete audit trail with proofs

### Dialog System

Interactive AI agent feedback without wasting API requests:
- Native desktop dialogs (egui) and iced panel
- Mobile dialogs (WebSocket + web UI)
- Priority queue, decision memory, approval workflows
- Hold mode for complex decisions
- Rich context (code diffs, file previews, progress)
- **Orchestrator mode** - Four modes for varying autonomy levels:
  - User Active (all dialogs to user)
  - Delegated (auto-handle routine, escalate critical)
  - Spectator (auto-handle with claim timeout)
  - Autonomous (full auto, critical queued)
- **Decision engine** - Critical keyword detection, session continuation

### Service Discovery

DNS-SD with CoreDNS integration:
- Automatic harness registration and heartbeat
- Multi-machine discovery via mDNS
- HTTP API on port 4001

## Quick Start

```bash
# 1. Start Synapsix (required for harness control)
cd ~/synapsix && iex --sname synapsix -S mix

# 2. Start Studio Core (required for UI)
cd ~/continuum-studio/core/studio_core && iex -S mix

# 3. Start Desktop UI
cd ~/continuum-studio && ./run-gui.sh
```

### NixOS Integration

The dialog daemon and terminal monitor are managed as systemd user services via Home Manager:

```nix
homelab.synapsix = {
  enable = true;
  dialog.enable = true;
  dialog.autostart = true;
  terminalMonitor.enable = true;
};
```

## Documentation

| Document | Description |
|----------|-------------|
| `docs/ARCHITECTURE.md` | System architecture overview |
| `docs/UI_ARCHITECTURE.md` | UI component design |
| `docs/AGENT_BRIDGE_ARCHITECTURE.md` | Agent Bridge design |
| `docs/MODULE_ARCHITECTURE.md` | Module boundaries |
| `docs/ETF_PROTOCOL.md` | IPC protocol specification |
| `docs/WIDGET_BAY_DESIGN.md` | Widget bay design |
| `docs/DESIGN_PROXY_SESSION_MONITORING.md` | Session monitoring design |
| `docs/UI_PARADIGM_EXPLORATION.md` | UI paradigm rationale |

## Related Repositories

- **[synapsix](https://github.com/Distracted-E421/synapsix)** - Harness orchestrator, dialog daemon, terminal monitoring
- **[nixos-cursor](https://github.com/Distracted-E421/nixos-cursor)** - NixOS packaging for Cursor IDE
- **[homelab](https://github.com/Distracted-E421/homelab)** - NixOS infrastructure and configuration
- **[phosphor](https://github.com/Distracted-E421/phosphor)** - Wayland-native screen capture with smart storage

## License

**SSPL-1.0** (Server Side Public License) for core orchestration platform.

This license means:
- ✅ **Self-hosting**: Free for personal or organizational AI orchestration
- ✅ **Modification**: Fork and customize for your workflow
- ✅ **Internal use**: Deploy within your organization at no cost
- ⚠️ **SaaS restriction**: Offering Continuum Studio as a managed service requires releasing your entire stack

The SSPL protects against cloud providers offering AI-orchestration-as-a-service without contributing. For users deploying their own AI infrastructure, this works like a permissive open-source license.

See [LICENSE](LICENSE) for full details and [LICENSING_FAQ.md](../cortex/docs/LICENSING_FAQ.md) for common questions.
