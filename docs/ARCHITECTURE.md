# Continuum Studio Architecture

**Last Updated**: 2026-04-25  
**Status**: Active Development (desktop UI: `ui-iced/`; legacy egui tree: `ui/`)

## Overview

Continuum Studio is a modular AI orchestration platform built with a clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Continuum Studio                                │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │  Studio UI  │  │ Studio Core │  │Agent Bridge │  │  Synapsix   │    │
│  │(Rust/iced)  │  │  (Elixir)   │  │  (Elixir)   │  │  (Elixir)   │    │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘    │
│         │                │                │                │            │
│         └────────────────┴────────────────┴────────────────┘            │
│                                   │                                     │
│                     ┌─────────────┴─────────────┐                       │
│                     │     Dialog Daemon         │                       │
│                     │        (Rust)             │                       │
│                     └───────────────────────────┘                       │
└─────────────────────────────────────────────────────────────────────────┘
```

## Components

### 1. Studio UI (Rust/iced)

**Path**: `continuum-studio/ui-iced/`

The graphical user interface built with Rust and the **iced** framework (COSMIC-compatible).

**Key Features**:
- COSMIC desktop ecosystem compatibility
- Elm architecture (Model-View-Update pattern)
- First-class Wayland support
- Multiple views: Dashboard, Chat Pipeline, Services, Versions, Settings, Subagents
- VS Code theme compatibility
- Session monitoring and metrics dashboard

**IPC**: JSON-framed messages over Unix socket to Studio Core (`/tmp/continuum-studio.sock`)

### 2. Studio Core (Elixir/BEAM)

**Path**: `continuum-studio/core/studio_core/`

The central orchestration hub that coordinates all components.

**Key Features**:
- ETS-backed state management
- Event bus for broadcasting changes
- Harness registry for Synapsix coordination
- Socket acceptor for UI and Synapsix connections

**Responsibilities**:
- Route UI requests to appropriate handlers
- Manage harness lifecycle
- Broadcast state changes to all connected clients
- Coordinate Agent Bridge queries

### 3. Agent Bridge (Elixir)

**Path**: `continuum-studio/core/agent_bridge/`

Unified interface for AI providers with intelligent routing.

**Key Features**:
- Multi-provider support (Claude, Ollama, Cursor harness)
- Session-based context management
- Token counting and cost tracking
- Rate limiting per provider
- Middleware pipeline for extensibility

**Providers**:
- **Claude** (Anthropic) - Direct API with streaming and tools
- **Ollama** (Local) - Multi-GPU support, local inference
- **Cursor** (Harness) - Via Synapsix for subscription-based AI
- **OpenAI** (Planned) - GPT models

### 4. Synapsix (Elixir)

**Path**: `synapsix/` (separate repository)

AI harness orchestrator with formal verification capabilities.

**Key Features**:
- Multi-application control (Cursor, Android Studio, Godot)
- **NeSy Stack** - Nickel→SMT formal verification
- Chat pipeline with semantic search
- Dialog system for AI agent interaction
- Terminal monitoring
- Z3 SMT solver integration via Rust NIF

**Major Components**:
- **Harnesses** - Cursor IDE, Android Studio, Godot Editor
- **NeSy Orchestrator** - Formal verification of agent actions
- **Chat Pipeline** - 117+ conversations indexed with hybrid search
- **Dialog System** - D-Bus + Web UI for agent interaction

### 5. Dialog Daemon (Rust)

**Path**: `synapsix/dialog/`

D-Bus service for interactive AI agent dialogs with web fallback.

**Key Features**:
- Native dialog rendering (default **Iced** GUI; optional egui feature in crate)
- Web UI on port 8080 (for mobile access, orchestrator WebSocket, activity stream)
- Hold mode for complex decisions
- Multiple dialog types (choice, confirm, text, slider, file picker)
- CLI tool (`synapsix-dialog-cli`)

**D-Bus Service**: `sh.synapsix.Dialog`

## Communication Protocols

### UI ↔ Core (JSON over Unix Socket)

```
┌──────────────┐          ┌──────────────┐
│   Studio UI  │◄────────►│ Studio Core  │
│    (Rust)    │  JSON    │   (Elixir)   │
└──────────────┘  Socket  └──────────────┘
```

**Message Format**:
```json
// Command (UI → Core)
{"command": "harness_start", "params": {"type": "cursor"}}

// Event (Core → UI)
{"event": "harness_status", "data": {"harness": "cursor", "status": "running"}}
```

### Synapsix ↔ Core (JSON over Unix Socket)

Same protocol as UI ↔ Core, allowing Synapsix to register harnesses and receive commands.

### Agent Bridge ↔ Core (In-process/ETF)

Agent Bridge runs alongside Studio Core on the BEAM VM, using native Elixir messaging.

### Dialog Daemon (D-Bus)

```
┌──────────────┐          ┌──────────────┐
│   Synapsix   │◄────────►│Dialog Daemon │
│   (Elixir)   │  D-Bus   │    (Rust)    │
└──────────────┘          └──────────────┘
```

**Service**: `sh.synapsix.Dialog`
**Interface**: `sh.synapsix.Dialog1`

## Data Flow Examples

### 1. User Sends Message to AI

```
User Input → Studio UI → Studio Core → Agent Bridge → Claude API
                                                    ↓
User Sees  ← Studio UI ← Studio Core ← Agent Bridge ← Response
```

### 2. Harness Control

```
User Click → Studio UI → Studio Core → Synapsix → Cursor Harness
     ↓                        ↓             ↓           ↓
  UI Update ← Event Bus ← Registry ← Status Update ← Cursor IDE
```

### 3. Dialog Request

```
Agent needs input → Synapsix.DialogManager → D-Bus → Dialog Daemon
                                                          ↓
Agent continues ← DialogManager ← D-Bus ← User Response ← Native Dialog
```

## Directory Structure

```
continuum-studio/
├── ui-iced/                     # Studio UI (Rust/iced) - ACTIVE
│   ├── src/
│   │   ├── main.rs              # Entry point with tokio runtime
│   │   ├── core.rs              # iced Application implementation
│   │   ├── theme.rs             # VS Code theme support
│   │   ├── monitoring.rs        # Metrics dashboard
│   │   ├── sessions.rs          # Session management
│   │   ├── settings.rs          # Settings view
│   │   ├── services.rs          # Services panel
│   │   ├── subagents.rs         # Sub-agent management
│   │   ├── chat_pipeline.rs     # Chat pipeline view
│   │   ├── updater.rs           # State update logic
│   │   └── log_capture.rs       # Log interception
│   └── Cargo.toml
│
├── ui/                          # OLD egui UI (deprecated, archive candidate)
│
├── core/
│   ├── studio_core/             # Studio Core (Elixir)
│   │   ├── lib/
│   │   │   └── studio_core/
│   │   │       ├── application.ex
│   │   │       ├── state.ex
│   │   │       ├── state_snapshot.ex
│   │   │       ├── event_bus.ex
│   │   │       ├── version_registry.ex
│   │   │       └── socket/
│   │   │           ├── acceptor.ex
│   │   │           └── handler.ex
│   │   └── mix.exs
│   │
│   └── agent_bridge/            # Agent Bridge (Elixir)
│       ├── lib/
│       │   └── agent_bridge/
│       │       ├── application.ex
│       │       ├── router.ex
│       │       ├── provider_registry.ex
│       │       ├── context_manager.ex
│       │       ├── cost_tracker.ex
│       │       ├── rate_limiter.ex
│       │       ├── middleware.ex
│       │       ├── message.ex
│       │       └── providers/
│       │           ├── claude.ex
│       │           ├── ollama.ex
│       │           └── cursor.ex
│       └── mix.exs
│
├── android/                     # Continuum Studio Android app
│
├── archive/                     # Archived components
│
└── docs/
    ├── ARCHITECTURE.md          # This file
    ├── UI_STABILIZATION_PLAN.md # iced UI development plan
    └── diagrams/                # D2 architecture diagrams

synapsix/                        # Separate repo
├── lib/synapsix/
│   ├── application.ex
│   ├── harnesses/               # IDE automation
│   │   ├── cursor.ex
│   │   ├── android_studio.ex
│   │   └── godot.ex
│   ├── chat/                    # Chat pipeline
│   │   ├── pipeline.ex
│   │   ├── store.ex
│   │   ├── search.ex
│   │   └── embeddings.ex
│   ├── nesy/                    # Neurosymbolic AI
│   │   ├── orchestrator.ex
│   │   ├── nickel_smt.ex
│   │   ├── z3.ex
│   │   └── constraint/
│   ├── dialog/                  # Agent dialog system
│   └── terminal/                # Terminal monitoring
├── dialog/                      # Dialog Daemon (Rust)
│   ├── src/
│   │   ├── main.rs
│   │   ├── cli.rs
│   │   └── dbus_interface.rs
│   └── Cargo.toml
├── native/                      # Rust NIFs
│   ├── synapsix_nickel_smt/     # Nickel→SMT compiler
│   └── synapsix_z3/             # Z3 solver bindings
└── mix.exs
```

## Diagrams

See the following D2 diagrams for visual architecture:

- **architecture-layers.d2** - Layered view of all components
- **system-overview.d2** - Detailed component and data flow diagram
- **widget-system.d2** - UI widget architecture
- **ui-paradigms.d2** - UI paradigm comparison

## Technology Choices

| Component | Language | Rationale |
|-----------|----------|-----------|
| Studio UI | Rust/iced | COSMIC compatibility, Elm architecture, first-class Wayland |
| Studio Core | Elixir | Fault tolerance, hot reloading, BEAM distribution |
| Agent Bridge | Elixir | BEAM benefits, same VM as Core |
| Synapsix | Elixir + Rust NIFs | Fault tolerance + performance-critical parsing/solving |
| Dialog Daemon | Rust | D-Bus + Iced/optional egui, web UI on 8080 |
| Chat Pipeline NIFs | Rust | High-performance parsing, embedding operations |
| NeSy Solver | Rust + Z3 | Formal verification with SMT solver |

## Migration Status

| Component | From | To | Status |
|-----------|------|-----|--------|
| Studio UI | egui | iced | ✅ Complete (Feb 2026) |
| Dialog Service | continuum namespace | synapsix namespace | ✅ Complete |
| D-Bus Interface | `sh.continuum.studio.Dialog` | `sh.synapsix.Dialog` | ✅ Complete |

## Future Considerations

1. **BEAM Distribution** - Connect multiple nodes for distributed harnesses
2. **Phosphor Integration** - Screen capture for agent vision
3. **WebSocket** - Browser-based UI option
4. **Mobile Client** - Continuum Studio Android companion (in progress)
5. **Nickel Configuration** - Type-safe configuration with formal verification

## License

This repository is **SSPL-1.0** unless noted otherwise; Synapsix and some dependencies use other licenses. See the repo `LICENSE` and [LICENSING_STRATEGY.md](./LICENSING_STRATEGY.md) for details.

