# Socket IPC Roadmap

**Priority**: High
**Complexity**: Medium
**Dependencies**: Unix sockets, JSON/ETF protocol
**Estimated Agent Sessions**: 1-2 focused sessions

---

## Overview

The Continuum Studio UI (Rust/iced) needs to communicate with Studio Core (Elixir) via Unix sockets. The socket infrastructure exists but needs testing and integration.

### Current State (Updated 2026-02-21)

**Studio Core (Elixir) - ~3,700 lines:**
- ✅ `socket/acceptor.ex` (166 lines) - Unix socket server
- ✅ `socket/handler.ex` (708 lines) - Message handling
- ✅ `event_bus.ex` (139 lines) - Event distribution
- ✅ `state.ex` (174 lines) - State management
- ✅ `state_snapshot.ex` (213 lines) - State serialization
- ✅ `harness_registry.ex` (241 lines) - Harness management
- ✅ `version_registry.ex` (732 lines) - Version tracking
- ✅ `workspace_tracker.ex` (594 lines) - Workspace monitoring
- ✅ `auth_manager.ex` (632 lines) - Authentication

**UI Clients (Rust) - Multiple IPC channels:**
- ✅ `coordinator_client.rs` (328 lines) - Agent Coordinator IPC
- ✅ `task_queue_client.rs` (740 lines) - Task Queue IPC
- ✅ `dialog_client.rs` (299 lines) - Synapsix Dialog
- ✅ `feed_client.rs` (326 lines) - Activity feed

**Status:**
- Phase 1 (Verify Core Socket): ✅ Complete
- Phase 2 (UI Client Implementation): ✅ Complete (multiple clients)
- Phase 3 (Message Protocol): ✅ JSON protocol working
- Phase 4 (Integration Testing): ⚠️ Needs more test coverage

---

## Phase 1: Verify Core Socket

**Goal**: Ensure Studio Core's socket acceptor works

### Tasks

1. **Test socket creation**
   ```elixir
   # In iex
   StudioCore.SocketAcceptor.start_link([])
   # Check socket exists
   File.exists?("/tmp/continuum-studio.sock")
   ```

2. **Test connection acceptance**
   - Create simple test client
   - Verify connection accepted
   - Check message parsing

3. **Review message format**
   ```elixir
   # Message structure
   %{
     type: :request | :response | :event,
     id: "uuid",
     payload: %{...}
   }
   ```

### Success Criteria

- [ ] Socket file created on Core startup
- [ ] Test client can connect
- [ ] Messages parsed correctly

### Files to Check

```
core/studio_core/lib/studio_core/socket_acceptor.ex
core/studio_core/lib/studio_core/protocol.ex
```

---

## Phase 2: UI Client Implementation

**Goal**: Implement robust socket client in Rust UI

### Tasks

1. **Review existing client code**
   - File: `ui-iced/src/client.rs` (or similar)
   - Check connection logic
   - Check message serialization

2. **Add connection management**
   ```rust
   pub struct CoreClient {
       socket: Option<UnixStream>,
       state: ConnectionState,
       pending_requests: HashMap<String, PendingRequest>,
   }
   
   pub enum ConnectionState {
       Disconnected,
       Connecting,
       Connected,
       Reconnecting { attempt: u32 },
   }
   ```

3. **Implement reconnection**
   - Exponential backoff
   - Max retry limit
   - State notification to UI

### Success Criteria

- [ ] Client connects on startup
- [ ] Reconnects after disconnection
- [ ] UI shows connection status

---

## Phase 3: Message Protocol

**Goal**: Complete bidirectional messaging

### Tasks

1. **Define message types**
   ```rust
   pub enum CoreMessage {
       // Requests (UI → Core)
       GetState,
       StartService { name: String },
       StopService { name: String },
       ProviderRequest { provider: String, params: Value },
       
       // Responses (Core → UI)
       StateUpdate(StateSnapshot),
       ServiceStatus { name: String, status: ServiceStatus },
       ProviderResponse { id: String, result: Value },
       
       // Events (Core → UI)
       AgentRegistered { agent_id: String },
       AgentHeartbeat { agent_id: String },
       CostUpdate { total: f64 },
   }
   ```

2. **Implement serialization**
   - JSON for simplicity
   - Or ETF for Elixir efficiency

3. **Add request/response pairing**
   - UUID-based request tracking
   - Timeout handling
   - Error responses

### Success Criteria

- [ ] All message types defined
- [ ] Round-trip communication works
- [ ] Errors handled gracefully

---

## Phase 4: Integration Testing

**Goal**: End-to-end communication verified

### Tasks

1. **Create integration test suite**
   - Start Core
   - Connect UI
   - Send test messages
   - Verify responses

2. **Test edge cases**
   - Core restart
   - Socket permissions
   - Large messages
   - Rapid reconnection

3. **Performance testing**
   - Message throughput
   - Latency measurement
   - Memory usage

### Success Criteria

- [ ] Integration tests pass
- [ ] Performance acceptable (<50ms latency)
- [ ] No memory leaks

---

## Testing Strategy

### Manual Testing

```bash
# Terminal 1: Start Core
cd /home/e421/continuum-studio/core/studio_core
iex -S mix

# Terminal 2: Test connection
nc -U /tmp/continuum-studio.sock
# Send: {"type":"request","id":"test-1","payload":{"action":"ping"}}

# Terminal 3: Start UI
cd /home/e421/continuum-studio/ui-iced
cargo run --release
```

### Automated Testing

```bash
# Core tests
cd core/studio_core && mix test

# UI tests (if any)
cd ui-iced && cargo test
```

---

## Notes for Agent

### Socket Path

Default: `/tmp/continuum-studio.sock`

Or via XDG: `$XDG_RUNTIME_DIR/continuum-studio.sock`

### Protocol Format

JSON is simpler for debugging:
```json
{
  "type": "request",
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "payload": {
    "action": "get_state"
  }
}
```

ETF (Erlang Term Format) is more efficient but harder to debug.

### Key Elixir Files

- `lib/studio_core/socket_acceptor.ex` - Socket server
- `lib/studio_core/protocol.ex` - Message handling
- `lib/studio_core/event_bus.ex` - Event distribution

### Key Rust Files

- `src/client.rs` - Socket client
- `src/state.rs` - State management
- `src/message.rs` - Message types

---

**Last Updated**: 2026-02-21 (Marked substantially complete after multi-agent session review)
