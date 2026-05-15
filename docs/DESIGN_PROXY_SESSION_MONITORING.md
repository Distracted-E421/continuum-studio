# Design: Proxy-Based Live Session Monitoring

**Status**: Future Investigation  
**Dependencies**: synapsix-proxy (migrated from nixos-cursor), Continuum Studio Core  
**Priority**: Medium-term (after session tracking and hot restart are stable)

## Motivation

Currently, Continuum Studio can enumerate Cursor processes and windows, but has
limited visibility into what agents are actually *doing*. The existing proxy
infrastructure (synapsix-proxy, originally cursor-proxy) can intercept Cursor's
API traffic to `api2.cursor.sh`, enabling:

- **Live session monitoring**: See agent conversations in real-time
- **Token usage tracking**: Monitor API usage per instance/agent
- **Context injection**: Augment agent prompts with additional context
- **Audit trail**: Record all agent interactions for review
- **Multi-agent coordination**: Detect when agents might conflict

## Existing Infrastructure

### synapsix-proxy (Rust)

Already built and working (migrated Jan 31, 2026):

| Capability | Status |
|---|---|
| TLS interception with dynamic certs | Working |
| HTTP/2 bidirectional gRPC streaming | Working |
| System prompt injection | Working |
| Context file injection | Working |
| Payload capture (request/response) | Working |
| Dashboard with real-time monitoring | Working |
| Network namespace isolation | Working |
| Per-domain certificate generation | Working |

### Certificate Trust

No certificate pinning bypass needed:
- Cursor (Electron/Node.js) accepts custom CAs via `NODE_EXTRA_CA_CERTS`
- Generate CA → set env var → Cursor trusts the proxy's certs
- Works transparently with AppImage launcher

## Integration Path with Continuum Studio

### Phase 1: Managed Proxy Startup

**Goal**: Continuum Studio manages proxy lifecycle

```
Continuum Studio Core
├── ServiceManager
│   ├── synapsix-proxy service (managed)
│   ├── synapsix-dialog service (managed)  ← already done
│   └── ...
└── VersionRegistry
    └── LaunchVersion (passes NODE_EXTRA_CA_CERTS)
```

Changes needed:
1. Add synapsix-proxy as a managed service in `ServiceManager`
2. When launching a Cursor instance, set `NODE_EXTRA_CA_CERTS` to the proxy's CA
3. Configure DNS/iptables to route traffic through proxy
4. IPC from Continuum Core to proxy for configuration

### Phase 2: Real-Time Event Streaming

**Goal**: Proxy sends agent activity events to Continuum Studio

```
Cursor Instance → synapsix-proxy → api2.cursor.sh
                      ↓
                 Event stream
                      ↓
              Continuum Studio Core
                      ↓
                 Continuum Studio UI
                 (live session view)
```

Protocol: The proxy already supports IPC. Extend it to:
1. Emit structured events for each API call (request type, model, token count)
2. Emit streaming events for agent conversations (with optional full content)
3. Route events through EventBus to connected UI clients

Event types:
```json
{"type": "agent_request", "instance_pid": 119349, "model": "claude-4.6-opus", "prompt_tokens": 1234}
{"type": "agent_stream_start", "instance_pid": 119349, "conversation_id": "abc123"}
{"type": "agent_stream_chunk", "instance_pid": 119349, "content": "..."}
{"type": "agent_stream_end", "instance_pid": 119349, "completion_tokens": 567}
```

### Phase 3: Live Dashboard Integration

**Goal**: Display real-time agent activity in the Sessions view

UI additions to the session cards:
- **Token counter**: Running total of tokens used per instance
- **Active agent indicator**: Currently generating, idle, or waiting
- **Conversation preview**: Last few lines of agent output
- **Model badge**: Which model the agent is using
- **Cost estimate**: Approximate API cost based on token usage

### Phase 4: Multi-Agent Coordination

**Goal**: Detect and warn about agent conflicts

When multiple agents are running across instances, the proxy can:
1. Track which files each agent is modifying (from tool call content)
2. Detect when two agents are editing the same file
3. Send conflict warnings to Continuum Studio
4. Optionally inject context about other agents' work

## Technical Challenges

### 1. DNS/Routing Setup

Options for routing Cursor traffic through the proxy:
- **Network namespace** (current approach): Isolate each Cursor instance
- **DNS override**: Point `api2.cursor.sh` to localhost in `/etc/hosts`
- **iptables redirect**: Transparent proxying via NAT rules
- **Proxy env var**: `HTTPS_PROXY` (may not work with all Cursor endpoints)

Recommended: Network namespace per instance (most isolated, already proven).
Continuum Studio's `LaunchVersion` can set up the namespace automatically.

### 2. Performance Impact

Proxy adds ~2-5ms latency per request (measured). For streaming:
- First message: +5-10ms (certificate negotiation)
- Subsequent: +1-2ms (passthrough)
- Context injection: +5-20ms (payload modification)

Acceptable for development use. Can be toggled per-instance.

### 3. Multi-Instance Proxying

With multiple Cursor instances, the proxy needs:
- Per-instance identification (via namespace or source port)
- Separate capture directories per instance
- Independent injection configs per instance

### 4. gRPC Streaming Reliability

Known issues to investigate:
- `FRAME_SIZE_ERROR` on some non-chat endpoints (telemetry, analytics)
- These can be passed through without interception
- Chat endpoints (primary interest) work reliably

## Implementation Order

1. **ServiceManager integration** - Add proxy as managed service
2. **Launch with proxy** - Pass env vars when launching Cursor
3. **Event streaming** - Proxy → Core → UI pipeline
4. **Dashboard widgets** - Token counter, agent status, conversation preview
5. **Multi-agent awareness** - Conflict detection and context sharing

## Dependencies

- synapsix-proxy binary (built from nixos-cursor repo or synapsix)
- Certificate generation (rcgen, already in proxy)
- Network namespace tools (ip netns, already used in testing)
- Protobuf parsing (prost, already in proxy for request/response manipulation)

## Security Considerations

- CA private key must be protected (stored in `~/.cursor-proxy/` with 0600 perms)
- Captured payloads may contain sensitive code/conversations
- Proxy should only run locally (bind to 127.0.0.1)
- Consider encrypting capture storage
- Clear captures on session end (configurable)

## Relation to Other Features

| Feature | Dependency |
|---|---|
| Session tracking | Provides PID → instance mapping for proxy events |
| Color-coded instances | Proxy events tagged with instance color |
| Hot restart | Proxy should survive Core restarts independently |
| Build automation | Proxy binary included in nightly builds |
| Dialog routing | Proxy can inject dialog preference context |
