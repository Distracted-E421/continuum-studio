# CLI Agents UI Design

## Status: Phase 3 Complete (Full Stack)

**Frontend (March 2026):**

- Rust UI module (`src/cli_agents.rs`) with full state management
- State types: `CLIAgentsState`, `CLIAgent`, `CLIAgentEvent`, `LaunchForm`, `BatchForm`
- Message types: `CLIAgentMessage` with all UI actions + WebSocket events
- Task types: `CLIAgentTask` for backend communication
- View components: List, Detail, Launch Form, Batch Form
- Main app integration: Message routing, state initialization, task handling
- HTTP Client (`src/cli_agents_client.rs`):
  - `CLIAgentsHttpClient` with spawn_agent, spawn_batch, list_agents, get_agent, stop_agent
  - Request/Response types matching planned API
  - WebSocket connection with auto-reconnect (`spawn_cli_agents_websocket`)
  - Event conversion helpers (`EventJson::into_cli_agent_event`)
- WebSocket Subscription in main.rs:
  - `cli_agents_subscription()` - only active when on CLI Agents tab
  - `cli_agents_websocket_worker()` - handles real-time events
  - Events: Connected, AgentStarted, AgentEvent, AgentCompleted
- Compiles cleanly

**Backend (March 2026):**

- Plug.Router API (`synapsix/lib/synapsix/harnesses/cursor/cli_api.ex`):
  - `GET /api/cli-agents` - List agents
  - `GET /api/cli-agents/:id` - Get agent details
  - `POST /api/cli-agents/spawn` - Spawn single agent
  - `POST /api/cli-agents/batch` - Spawn batch
  - `POST /api/cli-agents/:id/stop` - Stop agent
- WebSocket handler (`synapsix/lib/synapsix/harnesses/cursor/cli_websocket.ex`):
  - Real-time event streaming
  - Heartbeat ping/pong
  - Subscribe/unsubscribe to specific agents
- CLI.Registry (`synapsix/lib/synapsix/harnesses/cursor/cli_registry.ex`):
  - ETS-based agent tracking
  - Process monitor for cleanup
- Router integration (`synapsix/lib/synapsix/service_registry/router.ex`):
  - `/api/cli-agents/*` forwarded to CLI.API
  - `/ws/cli-agents` forwarded to CLI.WebSocket.Plug
- Application supervision (`synapsix/lib/synapsix/application.ex`):
  - CLI.Registry added to supervision tree

**Next Steps:**

- Test end-to-end with actual Cursor CLI agents
- Add CLIBackend event emission to WebSocket broadcasts

## Overview

The CLI Agents tab in Continuum Studio provides a UI for orchestrating headless Cursor CLI agents. This allows users to:

- Spawn agents across local repositories (no GitHub required)
- Monitor real-time events
- Run batch operations
- Leverage Synapsix MCP tools (fast_dialog, etc.)

## Backend Integration

### Synapsix Modules

| Module | Path | Purpose |
|--------|------|---------|
| `CLIBackend` | `synapsix/lib/synapsix/harnesses/cursor/cli_backend.ex` | Single agent management |
| `CLIBatch` | `synapsix/lib/synapsix/harnesses/cursor/cli_batch.ex` | Multi-repo orchestration |

### Communication

The UI should communicate with Synapsix via:

1. **HTTP API** - For spawning agents, getting status
2. **WebSocket** - For real-time event streaming

### Endpoints Needed

```
POST /api/cli-agents/spawn
  body: { prompt, workspace, opts }
  response: { agent_id }

GET /api/cli-agents
  response: [{ id, status, workspace, ... }]

GET /api/cli-agents/:id
  response: { id, status, events, result }

POST /api/cli-agents/:id/stop
  response: { ok }

WS /ws/cli-agents
  events: { type: "started" | "event" | "completed", ... }
```

## UI Components

### 1. Agent Launch Panel

```
┌─────────────────────────────────────────────────┐
│ Launch CLI Agent                                │
├─────────────────────────────────────────────────┤
│ Workspace: [/home/e421/synapsix ▼]             │
│ Prompt: ┌────────────────────────────────────┐ │
│         │ Analyze this codebase              │ │
│         └────────────────────────────────────┘ │
│ Mode: [◉ Agent ○ Plan ○ Ask]                   │
│ [✓] Auto-approve MCPs  [✓] Force trust        │
│                              [▶ Launch Agent]  │
└─────────────────────────────────────────────────┘
```

### 2. Active Agents List

```
┌─────────────────────────────────────────────────┐
│ Active Agents (3)                    [⟳ Refresh]│
├─────────────────────────────────────────────────┤
│ ┌─────────────────────────────────────────────┐ │
│ │ 🟢 a4b2c8d1 | synapsix | Running 2m 15s    │ │
│ │ Prompt: Analyze code quality...             │ │
│ │ Events: 12 | Tools: 5                       │ │
│ │                           [Stop] [Details]  │ │
│ └─────────────────────────────────────────────┘ │
│ ┌─────────────────────────────────────────────┐ │
│ │ ✅ f3e9a1b2 | homelab | Completed 45s      │ │
│ │ Prompt: Fix linting errors...               │ │
│ │ Result: 3 files modified                    │ │
│ │                                  [Details]  │ │
│ └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

### 3. Agent Detail View

```
┌─────────────────────────────────────────────────┐
│ Agent f3e9a1b2                        [← Back] │
├─────────────────────────────────────────────────┤
│ Status: ✅ Completed                            │
│ Workspace: /home/e421/homelab                   │
│ Duration: 45.2s                                 │
│ Model: Claude 4.6 Opus                          │
├─────────────────────────────────────────────────┤
│ Events Timeline                                 │
│ ├─ 00:00 🚀 Started                            │
│ ├─ 00:02 🔧 Tool: SemanticSearch               │
│ ├─ 00:05 🔧 Tool: Read (3 files)               │
│ ├─ 00:12 💬 fast_dialog: "Confirm fix?"        │
│ ├─ 00:15 ✓ User: Yes                           │
│ ├─ 00:20 🔧 Tool: StrReplace (3 files)         │
│ └─ 00:45 ✅ Completed                           │
├─────────────────────────────────────────────────┤
│ Final Response:                                 │
│ ┌─────────────────────────────────────────────┐ │
│ │ Fixed 3 linting errors:                     │ │
│ │ - flake.nix: unused variable                │ │
│ │ - ...                                       │ │
│ └─────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
```

### 4. Batch Launch Panel

```
┌─────────────────────────────────────────────────┐
│ Batch Launch                                    │
├─────────────────────────────────────────────────┤
│ Prompt: ┌────────────────────────────────────┐ │
│         │ Add @moduledoc to public modules   │ │
│         └────────────────────────────────────┘ │
│                                                 │
│ Workspaces:                                     │
│ [✓] /home/e421/synapsix                        │
│ [✓] /home/e421/homelab                         │
│ [ ] /home/e421/cortex                          │
│ [+] Add workspace...                           │
│                                                 │
│ Max concurrent: [3 ▼]                          │
│ [✓] Stop on failure                            │
│                                                 │
│               [▶ Launch Batch (2 agents)]      │
└─────────────────────────────────────────────────┘
```

## State Management

### Rust State

```rust
// In ContinuumStudio state
pub struct CLIAgentsState {
    agents: Vec<CLIAgent>,
    selected_agent: Option<String>,
    launch_form: CLIAgentLaunchForm,
    batch_form: CLIBatchForm,
    ws_connected: bool,
}

pub struct CLIAgent {
    id: String,
    workspace: String,
    prompt: String,
    status: CLIAgentStatus,
    started_at: DateTime<Utc>,
    completed_at: Option<DateTime<Utc>>,
    events: Vec<CLIAgentEvent>,
    result: Option<String>,
}

pub enum CLIAgentStatus {
    Pending,
    Running,
    Completed,
    Failed,
}
```

### Messages

```rust
pub enum CLIAgentMessage {
    // Launch
    LaunchAgent(String, String), // workspace, prompt
    LaunchBatch(Vec<String>, String), // workspaces, prompt
    
    // Status updates
    AgentStarted(String, String), // id, model
    AgentEvent(String, CLIAgentEvent),
    AgentCompleted(String, CLIAgentResult),
    
    // UI actions
    SelectAgent(String),
    StopAgent(String),
    Refresh,
    
    // Form updates
    UpdateLaunchPrompt(String),
    UpdateLaunchWorkspace(String),
    ToggleBatchWorkspace(String),
}
```

## Implementation Steps

### Phase 1: UI Module (COMPLETE)

1. **Add state to ContinuumStudio** ✅
   - `cli_agents_state: CLIAgentsState`
   - Initialized with `CLIAgentsState::default()`

2. **Implement view functions** ✅
   - `view_cli_agents_tab` - Main tab router
   - `view_agent_list` - Agent cards with status
   - `view_agent_detail` - Full agent info + events
   - `view_launch_form` - Single agent launch
   - `view_batch_form` - Multi-repo batch launch

3. **Add message handling** ✅
   - `Message::CLIAgentAction(CLIAgentMessage)` variant
   - `handle_cli_agent_message()` function
   - Routes to `CLIAgentsState::update()`
   - Returns `CLIAgentTask` for async operations

### Phase 2: Backend Integration (COMPLETE for Frontend)

1. **Create HTTP client module** ✅
   - `src/cli_agents_client.rs`
   - Methods: spawn_agent, spawn_batch, list_agents, get_agent, stop_agent
   - Request types: `SpawnAgentRequest`, `SpawnBatchRequest`
   - Response types: `SpawnResponse`, `ListAgentsResponse`, `AgentDetails`
   - WebSocket event types: `CLIAgentWsEvent`
   - Wired into main.rs `handle_cli_agent_message`

2. **Create WebSocket subscription** ✅
   - `spawn_cli_agents_websocket()` - async WebSocket connection
   - Auto-reconnect on disconnect (5 second backoff)
   - `cli_agents_subscription(active)` - iced subscription
   - `cli_agents_websocket_worker()` - event loop
   - Events mapped to `CLIAgentMessage` variants

3. **Add Synapsix API endpoints** ✅
   - `Synapsix.Harnesses.Cursor.CLI.API` - Plug.Router for HTTP
   - `Synapsix.Harnesses.Cursor.CLI.WebSocket` - WebSock for real-time
   - `Synapsix.Harnesses.Cursor.CLI.Registry` - ETS-based tracking
   - Routes wired in `Synapsix.ServiceRegistry.Router`

## Testing

1. Spawn single agent from UI
2. Watch real-time events
3. Verify MCP tools work (fast_dialog appears)
4. Test batch launch across multiple repos
5. Test stop functionality
6. Verify completed agents show results

## Notes

- Auth is handled automatically (reads ~/.config/cursor/auth.json)
- CLI agents can use ALL Synapsix MCP tools
- Works with local repos (no GitHub required)
- See `cortex/docs/cursor-cli-research.md` for CLI details
