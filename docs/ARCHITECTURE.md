# Continuum Studio Architecture

## Overview

Continuum Studio is a modular AI orchestration platform built with a clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Continuum Studio                                │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐    │
│  │  Studio UI  │  │ Studio Core │  │Agent Bridge │  │  Synapsix   │    │
│  │   (Rust)    │  │  (Elixir)   │  │  (Elixir)   │  │  (Elixir)   │    │
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

### 1. Studio UI (Rust/egui)

**Path**: `continuum-studio/ui/`

The graphical user interface built with Rust and egui/eframe.

**Key Features**:
- Widget-based architecture (AgentStream, HarnessPanel, Diagram, Code, Terminal)
- Tiling and tabbed layouts
- VS Code theme compatibility
- KDE/Wayland native support

**IPC**: JSON-framed messages over Unix socket to Studio Core

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

**Path**: `synapsix/`

Distributed AI harness orchestrator for controlling external applications.

**Key Features**:
- Multi-application control (Cursor, Android Studio, Godot)
- Wayland/X11 window automation
- Fault-tolerant supervision
- Dialog daemon integration

**Harnesses**:
- **Cursor IDE** - Full AI IDE control
- **Android Studio** - Android development automation
- **Godot Editor** - Game development automation

### 5. Dialog Daemon (Rust)

**Path**: `synapsix/priv/dialog-daemon/`

D-Bus service for interactive AI agent dialogs.

**Key Features**:
- Native dialog rendering
- Non-blocking user input
- Multiple dialog types (choice, confirm, text, slider)
- CLI tool for testing

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

**Service**: `sh.continuum.studio.Dialog`
**Interface**: `sh.continuum.studio.Dialog1`

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
├── ui/                          # Studio UI (Rust/egui)
│   ├── src/
│   │   ├── main.rs              # Entry point
│   │   ├── lib.rs               # Library exports
│   │   ├── theme/               # VS Code theme support
│   │   ├── widgets/             # UI widgets
│   │   ├── ipc/                 # IPC client
│   │   └── approval/            # Approval workflows
│   └── Cargo.toml
│
├── core/
│   ├── studio_core/             # Studio Core (Elixir)
│   │   ├── lib/
│   │   │   ├── studio_core/
│   │   │   │   ├── application.ex
│   │   │   │   ├── state.ex
│   │   │   │   ├── event_bus.ex
│   │   │   │   ├── harness_registry.ex
│   │   │   │   └── socket/
│   │   │   └── studio_core.ex
│   │   └── mix.exs
│   │
│   └── agent_bridge/            # Agent Bridge (Elixir)
│       ├── lib/
│       │   ├── agent_bridge/
│       │   │   ├── application.ex
│       │   │   ├── router.ex
│       │   │   ├── provider_registry.ex
│       │   │   ├── context_manager.ex
│       │   │   ├── cost_tracker.ex
│       │   │   ├── rate_limiter.ex
│       │   │   ├── middleware.ex
│       │   │   ├── message.ex
│       │   │   ├── provider.ex
│       │   │   └── providers/
│       │   │       ├── claude.ex
│       │   │       ├── ollama.ex
│       │   │       └── cursor.ex
│       │   └── agent_bridge.ex
│       └── mix.exs
│
├── docs/
│   ├── diagrams/                # D2 architecture diagrams
│   └── research/                # Research documents
│
synapsix/                        # Separate repo
├── lib/
│   ├── synapsix/
│   │   ├── application.ex
│   │   ├── harnesses/
│   │   │   ├── cursor.ex
│   │   │   ├── android_studio.ex
│   │   │   └── godot.ex
│   │   ├── dialog_manager.ex
│   │   └── core_client.ex
│   └── synapsix.ex
├── priv/
│   └── dialog-daemon/           # Dialog Daemon (Rust)
│       ├── src/
│       │   ├── main.rs
│       │   ├── cli.rs
│       │   └── dbus_interface.rs
│       └── Cargo.toml
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
| Studio UI | Rust/egui | Native performance, Wayland support, single binary |
| Studio Core | Elixir | Fault tolerance, hot reloading, BEAM distribution |
| Agent Bridge | Elixir | BEAM benefits, same VM as Core |
| Synapsix | Elixir | Fault tolerance, process supervision |
| Dialog Daemon | Rust | D-Bus integration, native dialogs |

## Future Considerations

1. **BEAM Distribution** - Connect multiple nodes for distributed harnesses
2. **gRPC for Agent Bridge** - Alternative protocol for external integrations
3. **WebSocket** - Browser-based UI option
4. **Plugin System** - Third-party widget and harness support
5. **Mobile Client** - Continuum Studio companion app

## License

AGPL-3.0 for core components, SSPL for infrastructure services.

See [LICENSING_STRATEGY.md](./research/LICENSING_STRATEGY.md) for details.

