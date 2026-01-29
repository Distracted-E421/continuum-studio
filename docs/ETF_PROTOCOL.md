# ETF Protocol Design - Continuum Studio IPC

## Overview

Continuum Studio uses **Erlang Term Format (ETF)** for communication between the Rust UI and Elixir Core. ETF is the native binary serialization format used by the BEAM VM, making it ideal for interoperability with Elixir.

## Protocol Stack

```
┌─────────────────────────────────────┐
│         Studio UI (Rust)            │
├─────────────────────────────────────┤
│    ETF Encoding (erlang_rs crate)   │
├─────────────────────────────────────┤
│   Length-Prefixed Framing (4 bytes) │
├─────────────────────────────────────┤
│         Unix Domain Socket          │
├─────────────────────────────────────┤
│   Length-Prefixed Framing (4 bytes) │
├─────────────────────────────────────┤
│    ETF Decoding (:erlang module)    │
├─────────────────────────────────────┤
│        Studio Core (Elixir)         │
└─────────────────────────────────────┘
```

## Frame Format

All messages are wrapped in a length-prefixed frame:

```
+----------------+------------------+
| Length (4B BE) | ETF Payload      |
+----------------+------------------+
```

- **Length**: 4-byte big-endian unsigned integer
- **Payload**: ETF-encoded Erlang term

## Message Structures

### Commands (UI → Core)

Commands follow the tuple structure: `{:command, :command_name, %{params}}`

#### Ping

```elixir
{:command, :ping, %{}}
```

Used for health checks and connection verification.

#### Harness Start

```elixir
{:command, :harness_start, %{type: "cursor"}}
```

Parameters:
- `type` (binary): Harness type identifier ("cursor", "vscode", etc.)

#### Harness Stop

```elixir
{:command, :harness_stop, %{type: "cursor"}}
```

#### State Set

```elixir
{:command, :state_set, %{path: ["ui", "theme"], value: "dark"}}
```

Parameters:
- `path` (list of binaries): State path
- `value` (any term): New value

#### Agent Message

```elixir
{:command, :agent_message, %{text: "Hello", provider: "cursor"}}
```

Parameters:
- `text` (binary): Message text
- `provider` (binary): Agent provider identifier

### Events (Core → UI)

Events follow the tuple structure: `{:event, :event_type, %{data}}`

#### Pong

```elixir
{:event, :pong, %{}}
```

Response to ping command.

#### Harness Status

```elixir
{:event, :harness_status, %{harness: "cursor", status: "running"}}
```

Data:
- `harness` (binary): Harness identifier
- `status` (binary): Status string ("starting", "running", "stopping", "stopped", "error")

#### State Changed

```elixir
{:event, :state_changed, %{path: ["ui", "theme"], value: "dark"}}
```

Data:
- `path` (list of binaries): Changed state path
- `value` (any term): New value

#### Agent Response

```elixir
{:event, :agent_response, %{content: "Response text", role: "assistant"}}
```

Data:
- `content` (binary): Response content
- `role` (binary): "assistant" or "user"

#### Error

```elixir
{:event, :error, %{message: "Error description"}}
```

Data:
- `message` (binary): Human-readable error message

## Type Mappings

### Rust → Elixir

| Rust Type | ETF Term | Elixir Type |
|-----------|----------|-------------|
| `String` | `OtpErlangBinary` | `binary` |
| `&str` | `OtpErlangAtomUTF8` | `atom` |
| `i32` | `OtpErlangInteger` | `integer` |
| `f64` | `OtpErlangFloat` | `float` |
| `bool` | `OtpErlangAtomBool` | `boolean` |
| `Vec<T>` | `OtpErlangList` | `list` |
| `BTreeMap<K,V>` | `OtpErlangMap` | `map` |
| `(A, B, C)` | `OtpErlangTuple` | `tuple` |

### Elixir → Rust

| Elixir Type | ETF Term | Rust Type |
|-------------|----------|-----------|
| `binary` | `OtpErlangBinary` | `String` |
| `atom` | `OtpErlangAtom(UTF8)` | `String` |
| `integer` | `OtpErlangInteger` | `i32` |
| `float` | `OtpErlangFloat` | `f64` |
| `true/false` | `OtpErlangAtomBool` | `bool` |
| `list` | `OtpErlangList` | `Vec<T>` |
| `map` | `OtpErlangMap` | `BTreeMap` → JSON |
| `tuple` | `OtpErlangTuple` | Context-dependent |

## JSON Fallback

The system supports JSON as a fallback encoding for debugging and compatibility. When ETF decoding fails, the handler attempts JSON decoding.

### JSON Command Format

```json
{
  "command": "harness_start",
  "params": {
    "type": "cursor"
  }
}
```

### JSON Event Format

```json
{
  "event": "harness_status",
  "data": {
    "harness": "cursor",
    "status": "running"
  }
}
```

## Implementation Details

### Rust (UI Side)

Located in `ui/src/ipc/etf.rs`:

```rust
// Encode command to ETF
pub fn encode_command(cmd: &Command) -> Result<Vec<u8>, IpcError>;

// Decode event from ETF (with JSON fallback)
pub fn decode_event(payload: &[u8]) -> Result<Option<Event>, IpcError>;

// JSON fallback encoding
pub fn encode_command_json(cmd: &Command) -> Result<Vec<u8>, IpcError>;
```

Dependencies:
- `erlang_rs = "2.0"` - ETF encoding/decoding

### Elixir (Core Side)

Located in `core/studio_core/lib/studio_core/socket/handler.ex`:

```elixir
# Decode incoming message (JSON or ETF)
defp decode_message(data)

# Send event to UI
defp send_event(socket, event)
```

The handler uses:
- `Jason` for JSON encoding/decoding
- `:erlang.binary_to_term/2` for ETF decoding
- `:erlang.term_to_binary/1` for ETF encoding

## Error Handling

### Connection Errors

- Socket connection failures trigger reconnection with exponential backoff
- Default reconnect interval: 5 seconds

### Decode Errors

1. Try ETF decoding first
2. If ETF fails, try JSON decoding
3. If both fail, log warning and send error event

### Invalid Messages

Invalid message formats result in:
- Error logged on server
- `{:event, :error, %{message: "Invalid message format"}}` sent to client

## Security Considerations

- Unix domain sockets provide OS-level access control
- ETF's `:safe` mode prevents atom table exhaustion
- No authentication required (local socket only)
- Future: TLS for remote connections

## Performance Characteristics

| Encoding | Avg Size | Encode Time | Decode Time |
|----------|----------|-------------|-------------|
| ETF | Smaller | Faster | Faster |
| JSON | Larger | Slower | Slower |

ETF is approximately 2-3x more efficient than JSON for typical messages.

## Testing

Run ETF tests:

```bash
cd ui && cargo test etf -- --nocapture
```

Test cases:
1. `test_encode_ping_etf` - Verify ping command encoding
2. `test_encode_harness_start_etf` - Verify harness_start with params
3. `test_decode_harness_status_json` - JSON event decoding
4. `test_decode_pong_json` - Pong event decoding
5. `test_roundtrip_etf` - Full ETF encode/decode cycle
6. `test_json_to_term_conversion` - JSON → ETF term conversion

## Future Enhancements

1. **Streaming Support**: Large responses streamed as multiple events
2. **Compression**: Optional gzip for large payloads
3. **Binary Protocol Version**: Version field for backwards compatibility
4. **Metrics**: Telemetry for message counts and sizes

