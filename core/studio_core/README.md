# Studio Core

Central orchestration hub for Continuum Studio, built with Elixir/OTP.

## Overview

Studio Core provides the backend services for the Continuum Studio platform:

- **State Management** - ETS-backed with hot restart resilience
- **Event Bus** - Pub/sub broadcasting to all connected clients
- **Unix Socket IPC** - JSON-framed messages (`/tmp/continuum-studio.sock`)
- **Harness Registry** - Tracks connected Synapsix harnesses
- **Version Registry** - Cursor version management
- **Auth Manager** - Cursor authentication handling
- **Workspace Tracker** - Projects across Cursor versions

## Quick Start

```bash
# Start Studio Core
cd core/studio_core
iex -S mix

# Or start with a named node (for distributed features)
iex --sname studio -S mix
```

## Architecture

```
┌─────────────────────────────────────────────┐
│              Studio Core                     │
├─────────────────────────────────────────────┤
│  ┌───────────┐  ┌──────────┐  ┌──────────┐ │
│  │  State    │  │  Event   │  │  IPC     │ │
│  │  Manager  │  │   Bus    │  │  Server  │ │
│  └─────┬─────┘  └────┬─────┘  └────┬─────┘ │
│        │             │              │       │
│        └─────────────┴──────────────┘       │
│                      │                      │
│  ┌───────────────────┴───────────────────┐ │
│  │           Service Registry             │ │
│  └───────────────────┬───────────────────┘ │
│        ┌─────────────┼─────────────┐       │
│  ┌─────┴─────┐ ┌─────┴─────┐ ┌────┴────┐  │
│  │  Harness  │ │  Version  │ │  Auth   │  │
│  │  Registry │ │  Registry │ │ Manager │  │
│  └───────────┘ └───────────┘ └─────────┘  │
└─────────────────────────────────────────────┘
```

## IPC Protocol

Studio Core communicates via Unix socket at `/tmp/continuum-studio.sock`.

**Message Format:**
```
<4 bytes big-endian length><JSON payload>
```

**Request Types:**
- `list_sessions` - Get all Cursor sessions
- `list_workspaces` - Get workspaces across versions
- `get_versions` - Get installed Cursor versions
- `subscribe` - Subscribe to event stream
- `get_health` - Health check

## Configuration

```elixir
config :studio_core,
  socket_path: "/tmp/continuum-studio.sock",
  ets_persist: true,
  synapsix_url: "http://localhost:4001"
```

## Integration

Studio Core integrates with:

- **Synapsix** - Harness orchestration, dialog daemon
- **Desktop UI** (Rust/iced) - Via Unix socket IPC
- **Agent Bridge** - Provider management

## Development

```bash
# Run tests
mix test

# Start IEx with dependencies
iex -S mix

# Generate docs
mix docs
```

## License

AGPL-3.0 - See LICENSE file for details.
