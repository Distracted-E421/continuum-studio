# Module Architecture: Continuum Studio & Synapsix

> **Document Purpose**: Define clear module boundaries and responsibilities  
> **Date**: January 2026  
> **Status**: Architecture Definition

---

## Overview

The Continuum ecosystem consists of **four primary modules** with clear boundaries:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         CONTINUUM ECOSYSTEM                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      CONTINUUM STUDIO (UI)                           │   │
│  │                                                                      │   │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │   │
│  │   │ Widget System│  │ Presentation │  │ User Interaction Layer   │ │   │
│  │   └──────────────┘  └──────────────┘  └──────────────────────────┘ │   │
│  │                                                                      │   │
│  └───────────────────────────────┬──────────────────────────────────────┘   │
│                                  │ Events, Commands                         │
│                                  ▼                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                       STUDIO CORE (Runtime)                          │   │
│  │                                                                      │   │
│  │   ┌──────────────┐  ┌──────────────┐  ┌──────────────────────────┐ │   │
│  │   │  Event Bus   │  │State Manager │  │    IPC Gateway           │ │   │
│  │   └──────────────┘  └──────────────┘  └──────────────────────────┘ │   │
│  │                                                                      │   │
│  └─────────────┬─────────────────────────────────────┬──────────────────┘   │
│                │                                     │                      │
│                ▼                                     ▼                      │
│  ┌─────────────────────────────┐   ┌─────────────────────────────────────┐ │
│  │      AGENT BRIDGE           │   │            SYNAPSIX                  │ │
│  │                             │   │     (Harness Orchestration)          │ │
│  │  ┌───────────────────────┐  │   │                                      │ │
│  │  │ Provider Adapters     │  │   │  ┌─────────────┐ ┌────────────────┐ │ │
│  │  │ • Cursor              │  │   │  │ Harness     │ │ Dialog Manager │ │ │
│  │  │ • Local Ollama        │  │   │  │ Runtime     │ │                │ │ │
│  │  │ • Claude API          │  │   │  └─────────────┘ └────────────────┘ │ │
│  │  │ • OpenAI API          │  │   │                                      │ │
│  │  └───────────────────────┘  │   │  ┌─────────────┐ ┌────────────────┐ │ │
│  │                             │   │  │ Service     │ │ Cross-Machine  │ │ │
│  │  ┌───────────────────────┐  │   │  │ Registry    │ │ Coordination   │ │ │
│  │  │ Unified Context API   │  │   │  └─────────────┘ └────────────────┘ │ │
│  │  └───────────────────────┘  │   │                                      │ │
│  │                             │   └──────────────────────────────────────┘ │
│  └─────────────────────────────┘                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Module 1: SYNAPSIX (Harness Orchestration)

### Repository: `/home/e421/synapsix`

### Purpose
Distributed AI harness orchestrator - the "muscle" that controls applications and services for AI agents.

### License: AGPL-3.0

### Responsibilities

| Responsibility | Description |
|----------------|-------------|
| **Harness Runtime** | Execute and manage application harnesses (Cursor, Godot, Android Studio, etc.) |
| **Dialog Management** | User interaction via cursor-dialog-daemon |
| **Window Control** | Focus, screenshot, input simulation (kdotool, ydotool) |
| **Service Registry** | Track available harnesses across machines |
| **BEAM Distribution** | Cross-machine harness coordination |
| **Health Monitoring** | Harness status, recovery, failover |

### Does NOT Include
- UI/Presentation (that's Studio UI)
- AI Provider connections (that's Agent Bridge)
- Application-level state management (that's Studio Core)

### Module Boundaries

```elixir
# synapsix/lib/synapsix.ex
defmodule Synapsix do
  @moduledoc """
  Synapsix: Distributed AI Harness Orchestration
  
  This module provides:
  - Harness.Behaviour - Interface for implementing harnesses
  - HarnessRegistry - Service discovery for harnesses
  - DialogManager - User dialog interactions
  - CrossMachine - BEAM distribution coordination
  
  Synapsix does NOT provide:
  - GUI components (use Continuum Studio)
  - AI model integration (use Agent Bridge)
  - Application state (use Studio Core)
  """
end
```

### Public API

```elixir
# Harness operations
Synapsix.Harness.start(harness_type, config)
Synapsix.Harness.stop(harness_pid)
Synapsix.Harness.status(harness_pid)
Synapsix.Harness.execute(harness_pid, action, args)

# Dialog operations
Synapsix.Dialog.confirm(prompt, opts)
Synapsix.Dialog.choice(prompt, options, opts)
Synapsix.Dialog.text_input(prompt, opts)
Synapsix.Dialog.toast(message, opts)

# Registry operations
Synapsix.Registry.list_harnesses()
Synapsix.Registry.find_harness(type: :cursor, node: :any)
Synapsix.Registry.register_harness(harness_spec)

# Cross-machine
Synapsix.Cluster.nodes()
Synapsix.Cluster.connect(node)
Synapsix.Cluster.execute_on(node, fun)
```

### Internal Components

```
synapsix/
├── lib/
│   ├── synapsix.ex                 # Main API facade
│   ├── synapsix/
│   │   ├── harness.ex              # Harness behaviour
│   │   ├── harness_supervisor.ex   # Dynamic harness supervision
│   │   ├── harnesses/
│   │   │   ├── cursor.ex           # Cursor IDE harness
│   │   │   ├── godot.ex            # Godot Editor harness
│   │   │   ├── android_studio.ex   # Android Studio harness
│   │   │   └── generic.ex          # Generic X11/Wayland harness
│   │   ├── dialog_manager.ex       # Dialog daemon interface
│   │   ├── registry.ex             # Service registry
│   │   ├── cluster.ex              # BEAM distribution
│   │   └── window/
│   │       ├── wayland.ex          # Wayland window ops (kdotool)
│   │       ├── x11.ex              # X11 window ops (xdotool)
│   │       └── screenshot.ex       # Screenshot capture
│   └── mix.exs
├── priv/
│   └── dialog-daemon/              # Rust dialog daemon (to be migrated from nixos-cursor)
└── test/
```

---

## Module 2: STUDIO CORE (Application Runtime)

### Repository: `/home/e421/continuum-studio` (subdirectory: `core/`)

### Purpose
The application runtime that ties everything together - event routing, state management, and IPC coordination.

### License: AGPL-3.0

### Responsibilities

| Responsibility | Description |
|----------------|-------------|
| **Event Bus** | Pub/sub for application events |
| **State Management** | Centralized application state |
| **IPC Gateway** | Bridge between Rust UI and Elixir backend |
| **Session Management** | User sessions, persistence |
| **Configuration** | Application settings, preferences |
| **Plugin System** | Load/unload runtime extensions |

### Does NOT Include
- Rendering or UI components (that's Studio UI)
- Harness execution (that's Synapsix)
- AI model calls (that's Agent Bridge)

### Module Boundaries

```elixir
# studio_core/lib/studio_core.ex
defmodule StudioCore do
  @moduledoc """
  Studio Core: Application Runtime for Continuum Studio
  
  This module provides:
  - EventBus - Publish/subscribe event routing
  - State - Centralized state management
  - IPC - Communication with Rust UI
  - Session - User session management
  
  Studio Core does NOT provide:
  - UI rendering (use Studio UI)
  - Harness control (use Synapsix via IPC)
  - AI provider integration (use Agent Bridge)
  """
end
```

### Public API

```elixir
# Event operations
StudioCore.EventBus.subscribe(topic, handler)
StudioCore.EventBus.publish(topic, event)
StudioCore.EventBus.unsubscribe(subscription_id)

# State operations
StudioCore.State.get(path)
StudioCore.State.set(path, value)
StudioCore.State.watch(path, callback)
StudioCore.State.transaction(fun)

# IPC operations
StudioCore.IPC.send_to_ui(message)
StudioCore.IPC.register_handler(message_type, handler)

# Session operations
StudioCore.Session.start(user_id)
StudioCore.Session.save()
StudioCore.Session.restore()
```

### IPC Protocol

Communication between Elixir backend and Rust UI uses Erlang Term Format (ETF) over local sockets:

```
┌─────────────────────────────────────────────────────────────────┐
│                    IPC Message Format                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│   Direction: Rust → Elixir (Commands)                          │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  {:command, type, payload}                               │  │
│   │                                                          │  │
│   │  Examples:                                               │  │
│   │  {:command, :harness_start, %{type: :cursor}}           │  │
│   │  {:command, :state_set, %{path: [:prefs], value: ...}}  │  │
│   │  {:command, :agent_message, %{provider: :cursor, ...}}  │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
│   Direction: Elixir → Rust (Events)                            │
│   ┌─────────────────────────────────────────────────────────┐  │
│   │  {:event, type, payload}                                 │  │
│   │                                                          │  │
│   │  Examples:                                               │  │
│   │  {:event, :harness_status, %{id: "...", status: ...}}   │  │
│   │  {:event, :state_changed, %{path: [...], value: ...}}   │  │
│   │  {:event, :agent_response, %{text: "...", ...}}         │  │
│   └─────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Internal Components

```
continuum-studio/
├── core/                           # Elixir application
│   ├── lib/
│   │   ├── studio_core.ex          # Main API facade
│   │   ├── studio_core/
│   │   │   ├── event_bus.ex        # Event pub/sub
│   │   │   ├── state.ex            # State management
│   │   │   ├── ipc/
│   │   │   │   ├── server.ex       # IPC socket server
│   │   │   │   ├── protocol.ex     # ETF encoding/decoding
│   │   │   │   └── router.ex       # Message routing
│   │   │   ├── session.ex          # Session management
│   │   │   └── config.ex           # Configuration
│   │   └── mix.exs
│   └── test/
```

---

## Module 3: STUDIO UI (Presentation Layer)

### Repository: `/home/e421/continuum-studio` (subdirectory: `ui/`)

### Purpose
The visual interface - widget rendering, user interaction, and presentation logic.

### Language: Rust (egui/cosmic)

### License: AGPL-3.0

### Responsibilities

| Responsibility | Description |
|----------------|-------------|
| **Widget Rendering** | Draw UI components |
| **Layout Management** | Tiling, tabs, floating panels |
| **Input Handling** | Keyboard, mouse, touch |
| **Theme Management** | Visual styling, dark/light mode |
| **Accessibility** | Screen reader support, keyboard navigation |
| **IPC Client** | Send commands to Studio Core |

### Does NOT Include
- Business logic (that's Studio Core)
- Harness control (that's Synapsix via Core)
- AI provider connections (that's Agent Bridge via Core)

### Architecture

```rust
// ui/src/lib.rs
//! Studio UI: Continuum Presentation Layer
//! 
//! This crate provides:
//! - Widget components (Agent Stream, Code View, Terminal, etc.)
//! - Layout system (tiling, tabs, compositor)
//! - Theme and styling
//! - IPC client for communicating with Studio Core
//! 
//! Studio UI does NOT provide:
//! - Business logic (handled by Studio Core)
//! - Harness operations (handled by Synapsix)
//! - AI model calls (handled by Agent Bridge)
```

### Widget System

```rust
// ui/src/widgets/mod.rs
pub mod agent_stream;    // AI conversation display
pub mod code_view;       // Code editing/viewing
pub mod terminal;        // Terminal emulator
pub mod file_browser;    // File tree navigation
pub mod harness_panel;   // Harness status/control
pub mod dialog_queue;    // Pending dialog display
pub mod settings;        // Configuration UI

/// All widgets implement this trait
pub trait Widget {
    /// Unique identifier for this widget instance
    fn id(&self) -> WidgetId;
    
    /// Render the widget
    fn ui(&mut self, ui: &mut egui::Ui, state: &AppState);
    
    /// Handle events from Studio Core
    fn handle_event(&mut self, event: &Event);
    
    /// Minimum size constraints
    fn min_size(&self) -> egui::Vec2;
}
```

### Layout Modes

```rust
// ui/src/layout/mod.rs
pub enum LayoutMode {
    /// Automatic tiling (like i3/Hyprland)
    Tiling(TilingConfig),
    
    /// Tab-based with split views
    Tabs(TabsConfig),
    
    /// Floating windows
    Floating(FloatingConfig),
    
    /// Hybrid: Tiling base with floating overlays
    Compositor(CompositorConfig),
}

pub struct Layout {
    mode: LayoutMode,
    widgets: Vec<WidgetContainer>,
    focus: Option<WidgetId>,
}
```

### Internal Components

```
continuum-studio/
├── ui/                             # Rust application
│   ├── src/
│   │   ├── lib.rs                  # Library root
│   │   ├── main.rs                 # Application entry
│   │   ├── app.rs                  # Main application state
│   │   ├── ipc/
│   │   │   ├── mod.rs
│   │   │   ├── client.rs           # IPC connection to Core
│   │   │   └── messages.rs         # Message types
│   │   ├── widgets/
│   │   │   ├── mod.rs
│   │   │   ├── agent_stream.rs
│   │   │   ├── code_view.rs
│   │   │   ├── terminal.rs
│   │   │   └── ...
│   │   ├── layout/
│   │   │   ├── mod.rs
│   │   │   ├── tiling.rs
│   │   │   ├── tabs.rs
│   │   │   └── compositor.rs
│   │   └── theme/
│   │       ├── mod.rs
│   │       ├── colors.rs
│   │       └── fonts.rs
│   ├── Cargo.toml
│   └── assets/
│       ├── fonts/
│       └── icons/
```

---

## Module 4: AGENT BRIDGE (AI Provider Integration)

### Repository: `/home/e421/continuum-studio` (subdirectory: `agent-bridge/`)

### Purpose
Unified interface for connecting to various AI providers - local models, APIs, and IDE-embedded agents.

### License: AGPL-3.0

### Responsibilities

| Responsibility | Description |
|----------------|-------------|
| **Provider Adapters** | Connect to Cursor, Ollama, Claude API, OpenAI API |
| **Protocol Translation** | Normalize different AI API formats |
| **Context Management** | Build and manage context windows |
| **Tool Execution** | Handle tool calls from AI |
| **Rate Limiting** | Respect provider limits |
| **Cost Tracking** | Monitor API usage costs |

### Does NOT Include
- UI rendering (that's Studio UI)
- State persistence (that's Studio Core)
- Application harness control (that's Synapsix, exposed via tools)

### Architecture

```elixir
# agent_bridge/lib/agent_bridge.ex
defmodule AgentBridge do
  @moduledoc """
  Agent Bridge: Unified AI Provider Interface
  
  This module provides:
  - Provider behaviour for implementing adapters
  - Context building and management
  - Tool execution framework
  - Usage tracking and rate limiting
  
  Agent Bridge does NOT provide:
  - UI components (use Studio UI)
  - Harness control (expose Synapsix as tools)
  - State management (use Studio Core)
  """
end
```

### Public API

```elixir
# Provider operations
AgentBridge.list_providers()
AgentBridge.connect(provider_id, config)
AgentBridge.disconnect(provider_id)

# Conversation operations
AgentBridge.send_message(provider_id, message, context)
AgentBridge.stream_response(provider_id, message, context, callback)
AgentBridge.cancel(provider_id, request_id)

# Context operations
AgentBridge.Context.new()
AgentBridge.Context.add_file(context, path, content)
AgentBridge.Context.add_conversation(context, messages)
AgentBridge.Context.truncate(context, max_tokens)

# Tool operations
AgentBridge.Tools.register(tool_spec)
AgentBridge.Tools.execute(tool_name, args)
AgentBridge.Tools.list_available()

# Usage tracking
AgentBridge.Usage.get_stats(provider_id)
AgentBridge.Usage.get_cost(provider_id, period)
```

### Provider Adapters

```elixir
# agent_bridge/lib/agent_bridge/providers/
defmodule AgentBridge.Provider do
  @callback connect(config :: map()) :: {:ok, state} | {:error, reason}
  @callback disconnect(state) :: :ok
  @callback send_message(state, message, context) :: {:ok, response} | {:error, reason}
  @callback stream_message(state, message, context, callback) :: {:ok, stream_id} | {:error, reason}
  @callback supports_tools?() :: boolean()
  @callback model_info() :: %{context_window: integer(), ...}
end

# Implementations
defmodule AgentBridge.Providers.Cursor do
  @behaviour AgentBridge.Provider
  # Connects to Cursor's embedded AI via harness
end

defmodule AgentBridge.Providers.Ollama do
  @behaviour AgentBridge.Provider
  # Connects to local Ollama instance
end

defmodule AgentBridge.Providers.ClaudeAPI do
  @behaviour AgentBridge.Provider
  # Connects to Anthropic's Claude API
end

defmodule AgentBridge.Providers.OpenAI do
  @behaviour AgentBridge.Provider
  # Connects to OpenAI API
end
```

### Tool Integration

```elixir
# Synapsix harnesses exposed as tools
defmodule AgentBridge.Tools.HarnessTools do
  use AgentBridge.Tool
  
  @tool_spec %{
    name: "control_cursor",
    description: "Control the Cursor IDE",
    parameters: %{
      action: %{type: :string, enum: ["type", "click", "scroll", "screenshot"]},
      args: %{type: :map}
    }
  }
  
  def execute("control_cursor", %{action: action, args: args}) do
    case Synapsix.Harness.execute(:cursor, action, args) do
      {:ok, result} -> {:ok, result}
      {:error, reason} -> {:error, reason}
    end
  end
end
```

### Internal Components

```
continuum-studio/
├── agent-bridge/                   # Elixir application
│   ├── lib/
│   │   ├── agent_bridge.ex         # Main API facade
│   │   ├── agent_bridge/
│   │   │   ├── provider.ex         # Provider behaviour
│   │   │   ├── providers/
│   │   │   │   ├── cursor.ex
│   │   │   │   ├── ollama.ex
│   │   │   │   ├── claude_api.ex
│   │   │   │   └── openai.ex
│   │   │   ├── context.ex          # Context management
│   │   │   ├── tools/
│   │   │   │   ├── registry.ex
│   │   │   │   ├── executor.ex
│   │   │   │   └── harness_tools.ex
│   │   │   ├── usage.ex            # Usage tracking
│   │   │   └── rate_limiter.ex     # Rate limiting
│   │   └── mix.exs
│   └── test/
```

---

## Module Communication

### Communication Patterns

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Module Communication Patterns                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Studio UI (Rust)                                                          │
│        │                                                                    │
│        │ IPC (ETF over Unix socket)                                         │
│        ▼                                                                    │
│   Studio Core (Elixir) ◄────────────────┐                                  │
│        │                                │                                  │
│        │ Function calls                 │ Function calls                   │
│        ▼                                ▼                                  │
│   Agent Bridge (Elixir)            Synapsix (Elixir)                       │
│        │                                │                                  │
│        │ HTTP/gRPC                      │ Window APIs, CLI                 │
│        ▼                                ▼                                  │
│   External APIs                    Applications                            │
│   (Claude, OpenAI, Ollama)         (Cursor, Godot, etc.)                   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Message Flow Example: User Sends AI Message

```
1. User types message in Studio UI (Rust)
   └─► {:command, :agent_message, %{text: "...", provider: :cursor}}

2. Studio Core receives command
   └─► EventBus publishes {:agent_request, ...}
   └─► Agent Bridge picks up event

3. Agent Bridge processes
   └─► Context.build() with current state
   └─► Provider.send_message(:cursor, ...)

4. Cursor Provider (via Synapsix harness)
   └─► Synapsix.Harness.execute(:cursor, :type_text, text)
   └─► Wait for response

5. Response flows back
   └─► Agent Bridge: {:ok, response}
   └─► EventBus publishes {:agent_response, ...}
   └─► Studio Core: IPC.send_to_ui({:event, :agent_response, ...})
   └─► Studio UI: Updates agent_stream widget
```

---

## Repository Structure

### Final Layout

```
/home/e421/synapsix/                 # AGPL - Harness orchestration
├── lib/synapsix/
├── priv/dialog-daemon/             # Rust daemon (migrated from nixos-cursor)
└── mix.exs

/home/e421/continuum-studio/        # AGPL - Studio application
├── core/                           # Elixir - Application runtime
│   ├── lib/studio_core/
│   └── mix.exs
├── ui/                             # Rust - Presentation layer
│   ├── src/
│   └── Cargo.toml
├── agent-bridge/                   # Elixir - AI provider integration
│   ├── lib/agent_bridge/
│   └── mix.exs
├── docs/                           # Documentation
│   ├── MODULE_ARCHITECTURE.md      # This file
│   ├── IPC_DISTRIBUTION_RESEARCH.md
│   ├── SECURITY_RESEARCH.md
│   └── ...
└── flake.nix                       # NixOS packaging

/home/e421/continuum-dns/           # SSPL - Service discovery (future)
├── plugins/                        # CoreDNS plugins
└── registry/                       # Elixir service registry
```

---

## Migration Plan

### From nixos-cursor

| Source | Destination | Action |
|--------|-------------|--------|
| `cursor-dialog-daemon/` | `synapsix/priv/dialog-daemon/` | Move, update paths |
| `cursor-studio-egui/` | `continuum-studio/ui/` | Refactor, rename |
| D-Bus interfaces | Keep in Synapsix | Already appropriate |

### Dependencies

```
continuum-studio (umbrella)
├── studio_core (local)
│   ├── depends on: synapsix, agent_bridge
│   └── provides: IPC, EventBus, State
├── agent_bridge (local)
│   ├── depends on: synapsix (for harness tools)
│   └── provides: Provider API, Context, Tools
└── ui (Rust, communicates via IPC)
    └── depends on: studio_core (via socket)

synapsix (separate repo)
├── depends on: nothing external except stdlib
└── provides: Harness API, Dialog API, Registry
```

---

## Summary

| Module | Language | License | Responsibility |
|--------|----------|---------|----------------|
| **Synapsix** | Elixir | AGPL | Harness control, dialogs, cross-machine |
| **Studio Core** | Elixir | AGPL | Event bus, state, IPC, sessions |
| **Studio UI** | Rust | AGPL | Widgets, layout, rendering |
| **Agent Bridge** | Elixir | AGPL | AI providers, context, tools |
| **Continuum DNS** | Go/Elixir | SSPL | Service discovery (future) |

**Key Design Principles**:

1. **Clear boundaries**: Each module has explicit responsibilities
2. **Loose coupling**: Modules communicate via well-defined APIs
3. **Single source of truth**: State lives in Studio Core
4. **Language appropriateness**: Rust for UI, Elixir for distributed systems
5. **Independent deployment**: Synapsix can be used without Studio

---

*This architecture document defines the module boundaries for Continuum Studio and Synapsix.*

