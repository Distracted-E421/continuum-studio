Synapsix:# Protocol Format Research: Synapsix + Continuum Studio

> **Research Date**: January 2026  
> **Purpose**: Evaluate serialization formats for harness communication  
> **Scope**: Erlang ETF, Protocol Buffers, Cap'n Proto, custom binary

---

## Executive Summary

| Format | Size | Speed | Schema | Zero-Copy | BEAM Native | Cross-Lang |
|--------|------|-------|--------|-----------|-------------|------------|
| **Erlang ETF** | Medium | Fast in BEAM | Implicit | No | ✅ Yes | ⚠️ Libraries |
| **Protocol Buffers** | Small | Fast | Required | No | Via library | ✅ Excellent |
| **Cap'n Proto** | Larger | Fastest | Required | ✅ Yes | No | ✅ Good |
| **Custom Binary** | Smallest | Fastest | Required | ✅ Yes | Manual | Manual |

**Recommendation**:

- **Internal BEAM communication**: Erlang ETF (native, zero-effort)
- **External services**: Protocol Buffers (wide support, good tooling)
- **High-frequency data**: Cap'n Proto or custom binary (zero-copy)

---

## Part 1: Erlang External Term Format (ETF)

### Overview

ETF is Erlang/Elixir's native binary serialization format, used automatically for:

- BEAM distribution between nodes
- `term_to_binary/1` / `binary_to_term/1`
- ETS disk persistence

### Encoding Details

```
┌─────────┬─────┬─────────────┐
│ Version │ Tag │    Data     │
│  (131)  │ 1B  │   N bytes   │
└─────────┴─────┴─────────────┘
```

**Key Type Tags:**

| Tag | Type | Description |
|-----|------|-------------|
| 97 | SMALL_INTEGER | 0-255, 1 byte |
| 98 | INTEGER | 32-bit signed |
| 70 | NEW_FLOAT | 64-bit IEEE float |
| 104 | SMALL_TUPLE | Up to 255 elements |
| 108 | LIST | Any length list |
| 109 | BINARY | Raw bytes with length |
| 116 | MAP | Key-value pairs |
| 118 | ATOM_UTF8 | UTF-8 atom |

### Example Encoding

```elixir
# Elixir term
{:harness_command, :focus, "cursor"}

# Encoded (hex): 131,104,3,119,15,...
# - 131: Version
# - 104,3: Small tuple with 3 elements
# - 119,15,"harness_command": Small atom UTF-8
# - 119,5,"focus": Small atom UTF-8  
# - 109,0,0,0,6,"cursor": Binary with length 6
```

### Compression Support

ETF supports zlib compression for large messages:

```elixir
# Compressed format
:erlang.term_to_binary(large_term, [:compressed])
# Returns: <<131, 80, UncompressedSize:32, ZlibData/binary>>
```

### Atom Cache (Distribution Header)

When used in BEAM distribution, ETF includes an **atom cache** to avoid resending frequently-used atoms:

```
Distribution Header:
┌─────────┬────┬─────────────────────┬─────────────────┐
│   131   │ 68 │ NumAtomCacheRefs    │ Flags + Atoms   │
└─────────┴────┴─────────────────────┴─────────────────┘
```

This makes repeated messages with the same atoms very efficient.

### ETF Wins

1. **Zero configuration**: Native to BEAM, just works
2. **Full fidelity**: PIDs, refs, functions all serialize correctly
3. **Atom caching**: Repeated messages are efficient
4. **Compression**: Built-in zlib support
5. **No schema needed**: Self-describing format

### ETF Flaws

1. **No zero-copy**: Data must be decoded before use
2. **BEAM-centric**: Non-BEAM clients need libraries
3. **Atom table bloat**: Deserializing untrusted atoms can fill atom table
4. **Version coupling**: Format changes between OTP versions
5. **Security concerns**: `binary_to_term/1` can create atoms (use `:safe` option)

### When to Use ETF

✅ **Good for:**

- Synapsix node-to-node communication
- Internal message passing
- ETS/DETS persistence
- Messages between Elixir processes

❌ **Avoid for:**

- GUI↔Backend communication (unless both are BEAM)
- High-frequency streaming data
- Long-term storage (format may change)
- Untrusted input without `:safe` option

---

## Part 2: Protocol Buffers (Protobuf)

### Overview

Protocol Buffers is Google's language-neutral, platform-neutral serialization format. It requires schema definitions (`.proto` files) compiled to language-specific code.

### Wire Format

Protobuf uses **Tag-Length-Value (TLV)** encoding:

```
┌─────────────────┬─────────────────┬─────────────────┐
│      Tag        │     Length?     │      Value      │
│ (field << 3)|wt │   (for LEN)     │   (payload)     │
└─────────────────┴─────────────────┴─────────────────┘
```

**Wire Types:**

| ID | Name | Used For |
|----|------|----------|
| 0 | VARINT | int32, int64, uint32, uint64, bool, enum |
| 1 | I64 | fixed64, sfixed64, double |
| 2 | LEN | string, bytes, embedded messages, packed repeated |
| 5 | I32 | fixed32, sfixed32, float |

### Varint Encoding

Protobuf's key optimization is **variable-width integers**:

```
Value 150:
  Binary: 10010110 00000001
          ^MSB     ^MSB (continuation bits)
  
  Decoded: 0010110 0000001 (drop MSBs)
         = 0000001 0010110 (big-endian)
         = 150
```

Small values encode compactly:

- 0-127: 1 byte
- 128-16383: 2 bytes
- etc.

### Schema Example

```protobuf
syntax = "proto3";

message HarnessCommand {
  enum CommandType {
    FOCUS = 0;
    TYPE_TEXT = 1;
    SCREENSHOT = 2;
    SEND_KEYS = 3;
  }
  
  string harness_id = 1;
  CommandType command = 2;
  bytes payload = 3;
  int64 timestamp = 4;
}

message HarnessResponse {
  bool success = 1;
  bytes data = 2;
  string error = 3;
}
```

### Size Comparison

For a typical harness command:

```elixir
# Elixir map
%{harness_id: "cursor", command: :focus, timestamp: 1706000000}

# ETF: ~50 bytes (atoms add overhead)
# Protobuf: ~20 bytes (field numbers + varints)
# JSON: ~60 bytes (field names as strings)
```

### Protobuf Wins

1. **Compact**: Small wire size due to varints
2. **Fast**: Efficient encoding/decoding
3. **Schema evolution**: Add fields without breaking compatibility
4. **Wide support**: Official libraries for C++, Java, Python, Go, Rust, etc.
5. **gRPC integration**: Natural pairing with gRPC for RPC
6. **Well-documented**: Extensive documentation and tooling

### Protobuf Flaws

1. **Schema required**: Can't decode without `.proto` definition
2. **No zero-copy**: Data must be deserialized
3. **Compilation step**: Need to run `protoc` compiler
4. **Not self-describing**: Encoded data is opaque without schema
5. **Field number limits**: 1-536,870,911 (29 bits)
6. **Elixir integration**: Need external library (protobuf-elixir)

### Elixir Protobuf Libraries

```elixir
# mix.exs
{:protobuf, "~> 0.11"}

# Usage
defmodule HarnessCommand do
  use Protobuf, syntax: :proto3
  
  field :harness_id, 1, type: :string
  field :command, 2, type: CommandType, enum: true
  field :payload, 3, type: :bytes
  field :timestamp, 4, type: :int64
end

# Encode
cmd = %HarnessCommand{harness_id: "cursor", command: :FOCUS}
binary = HarnessCommand.encode(cmd)

# Decode
HarnessCommand.decode(binary)
```

### When to Use Protobuf

✅ **Good for:**

- Rust↔Elixir communication (via gRPC or raw)
- API definitions with versioning needs
- Storage with forward compatibility
- Cross-language services

❌ **Avoid for:**

- Pure BEAM communication (ETF is native)
- Rapid prototyping (schema overhead)
- Self-describing messages (use JSON or ETF)

---

## Part 3: Cap'n Proto

### Overview

Cap'n Proto (created by Protobuf's original author) is designed for **zero-copy** access. Data is accessed directly in its encoded form without deserializing.

### Key Concept: Zero-Copy

```
Traditional (Protobuf, ETF):
  Wire Data → Deserialize → Memory Structures → Use

Cap'n Proto:
  Wire Data → Use (directly!)
```

### Wire Format

Cap'n Proto uses **64-bit word alignment**:

```
┌─────────────────────────────────────────────────────────────────┐
│                         Struct Pointer                          │
├─────────┬───────────────────────────┬─────────────┬─────────────┤
│ A (2b)  │       B (30 bits)         │  C (16b)    │  D (16b)    │
│  = 0    │   Offset to data          │ Data words  │ Ptr words   │
└─────────┴───────────────────────────┴─────────────┴─────────────┘
```

Data is stored XOR'd with defaults, so zero-initialized memory represents default values.

### Schema Example

```capnp
struct HarnessCommand {
  harnessId @0 :Text;
  command @1 :CommandType;
  payload @2 :Data;
  timestamp @3 :Int64;
  
  enum CommandType {
    focus @0;
    typeText @1;
    screenshot @2;
    sendKeys @3;
  }
}

struct HarnessResponse {
  success @0 :Bool;
  data @1 :Data;
  error @2 :Text;
}
```

### Packing (Compression)

Cap'n Proto includes a simple compression scheme:

```
Unpacked: 08 00 00 00 03 00 02 00 19 00 00 00 aa 01 00 00
Packed:   51 08 03 02 31 19 aa 01

Tag byte indicates which bytes are non-zero.
Special handling for 0x00 (run of zeros) and 0xff (uncompressed span).
```

### Cap'n Proto Wins

1. **Zero-copy access**: No deserialization needed
2. **Fastest**: Data is directly accessible
3. **Incremental reads**: Access parts of message without full parse
4. **Memory-mapped files**: Can access large files efficiently
5. **Built-in RPC**: Integrated promise-based RPC protocol
6. **No allocation**: Can use message in-place

### Cap'n Proto Flaws

1. **Larger wire size**: Word alignment adds padding
2. **Schema required**: Like Protobuf
3. **Complexity**: More complex than Protobuf
4. **Limited language support**: Fewer implementations than Protobuf
5. **No Elixir library**: Would need to build or use FFI
6. **Learning curve**: Different mental model than traditional serialization

### Zero-Copy Example (C++)

```cpp
// Traditional approach (with copy)
HarnessCommand cmd;
cmd.ParseFromString(data);  // Deserialize
std::cout << cmd.harness_id();

// Cap'n Proto approach (zero-copy)
auto reader = capnp::FlatArrayMessageReader(data);
auto cmd = reader.getRoot<HarnessCommand>();
std::cout << cmd.getHarnessId();  // Reads directly from data!
```

### When to Use Cap'n Proto

✅ **Good for:**

- High-frequency data (screenshots, input events)
- Memory-mapped large files
- Performance-critical paths
- Zig/Rust performance components

❌ **Avoid for:**

- Elixir (no native library)
- Simple messages (overhead not worth it)
- Human debugging (binary format)

---

## Part 4: Custom Binary Protocol

### When Custom Makes Sense

For our specific use case (harness commands), a custom binary protocol could be optimal:

1. **Known, fixed message types**: Focus, TypeText, Screenshot, SendKeys
2. **High frequency**: Screenshots can be 10+ FPS
3. **Single purpose**: Optimized for exactly our needs

### Example Design

```
HarnessCommand Binary Format (variable length):

┌────────┬────────┬────────────┬────────────────────────┐
│ Magic  │ Type   │ Payload Len│       Payload          │
│ 4 bytes│ 1 byte │  2 bytes   │      N bytes           │
└────────┴────────┴────────────┴────────────────────────┘

Magic: "SYNX" (0x53594E58) - identifies Synapsix messages
Type: 0x01 = Focus, 0x02 = TypeText, 0x03 = Screenshot, etc.
Payload Len: Big-endian u16 (max 65535 bytes)
Payload: Type-specific data

Focus Command (Type 0x01):
┌────────────────┬───────────────────────────────────────┐
│ Harness ID Len │           Harness ID (UTF-8)          │
│    1 byte      │            N bytes                    │
└────────────────┴───────────────────────────────────────┘

Screenshot Response:
┌────────┬────────┬────────────┬────────────────────────┐
│ Format │ Width  │  Height    │       Image Data       │
│ 1 byte │ 2 bytes│  2 bytes   │        N bytes         │
└────────┴────────┴────────────┴────────────────────────┘
Format: 0x01 = PNG, 0x02 = JPEG, 0x03 = Raw RGB
```

### Zig Implementation Example

```zig
const HarnessCommand = packed struct {
    magic: u32 = 0x53594E58,  // "SYNX"
    command_type: CommandType,
    payload_len: u16,
    // Payload follows...
    
    pub fn parse(buffer: []const u8) !*const HarnessCommand {
        if (buffer.len < @sizeOf(HarnessCommand)) return error.BufferTooSmall;
        const cmd: *const HarnessCommand = @ptrCast(buffer.ptr);
        if (cmd.magic != 0x53594E58) return error.InvalidMagic;
        return cmd;
    }
};

const CommandType = enum(u8) {
    focus = 0x01,
    type_text = 0x02,
    screenshot = 0x03,
    send_keys = 0x04,
};
```

### Custom Binary Wins

1. **Minimal overhead**: No schema metadata in wire format
2. **Perfect fit**: Optimized for exactly our use case
3. **Zero-copy potential**: With careful design
4. **Full control**: No library dependencies
5. **Zig-friendly**: `packed struct` maps directly

### Custom Binary Flaws

1. **Maintenance burden**: Must implement encoder/decoder for each language
2. **No tooling**: No generic viewers/debuggers
3. **Evolution challenges**: Harder to add fields gracefully
4. **Documentation**: Must document format manually
5. **Testing**: More unit tests needed

### When to Use Custom Binary

✅ **Good for:**

- Fixed, well-known message types
- Extreme performance requirements
- Tight integration with Zig NIFs
- Screenshot/frame streaming

❌ **Avoid for:**

- General-purpose messaging
- Rapidly evolving protocols
- Cross-team/cross-org communication

---

## Part 5: Comparison for Synapsix Use Cases

### Use Case 1: Synapsix Node-to-Node (Multi-Machine)

**Scenario**: Studio on Obsidian communicates with Harness on neon-laptop

**Recommendation**: **Erlang ETF**

**Rationale**:

- Both ends are BEAM nodes
- ETF is native, zero-configuration
- Atom caching makes repeated messages efficient
- BEAM distribution handles transport
- No external dependencies

```elixir
# Just works!
GenServer.call({Harness.Cursor, :"synapsix@neon-laptop"}, {:focus})
```

### Use Case 2: Dialog Daemon Communication

**Scenario**: Elixir DialogManager talks to Rust cursor-dialog-daemon

**Current**: JSON over D-Bus  
**Recommendation**: Keep JSON, or migrate to **Protocol Buffers**

**Rationale for Protobuf**:

- Rust has excellent Protobuf support (prost)
- Schema provides clear contract
- More compact than JSON
- Forward-compatible evolution

```protobuf
// dialog.proto
message DialogRequest {
  string id = 1;
  oneof dialog_type {
    ConfirmDialog confirm = 2;
    ChoiceDialog choice = 3;
    TextDialog text = 4;
  }
  uint32 timeout_ms = 5;
}
```

### Use Case 3: Screenshot Streaming

**Scenario**: Harness sends screenshots to Studio at 10+ FPS

**Recommendation**: **Custom binary with shared memory**

**Rationale**:

- High frequency = every byte matters
- Fixed format (image data)
- Zero-copy via shared memory possible
- Zig NIF can produce directly

```
Screenshot Protocol:
┌────────┬────────┬────────┬─────────────────────┐
│ SeqNum │ Width  │ Height │  Raw BGRA pixels    │
│ 4 bytes│ 2 bytes│ 2 bytes│   W×H×4 bytes       │
└────────┴────────┴────────┴─────────────────────┘

Delivered via:
- Shared memory (/dev/shm/continuum-frames)
- D-Bus notification for new frame (just sequence number)
```

### Use Case 4: Keyboard/Input Events

**Scenario**: Studio sends keystrokes to harness quickly

**Recommendation**: **Protocol Buffers or Custom Binary**

**Rationale**:

- Lower frequency than screenshots
- Small messages (single keystroke)
- May need cross-language (Zig input injection)

```protobuf
message KeyEvent {
  uint32 keycode = 1;
  uint32 modifiers = 2;  // Bitfield: CTRL=1, ALT=2, SHIFT=4, META=8
  bool pressed = 3;
  int64 timestamp_ns = 4;
}
```

---

## Part 6: Recommendations Summary

### Protocol Selection Matrix

| Communication Path | Format | Why |
|-------------------|--------|-----|
| Elixir ↔ Elixir (same node) | Native terms | No serialization needed |
| Elixir ↔ Elixir (cross node) | ETF | Built-in, efficient |
| Elixir ↔ Rust (control) | Protobuf | Schema, wide support |
| Elixir ↔ Rust (data stream) | Custom binary | Performance |
| Elixir ↔ Zig NIF | Custom binary | Direct memory access |
| Zig ↔ Shared Memory | Custom binary | Zero-copy |

### Implementation Priority

1. **Keep ETF for internal BEAM**: It works, it's native
2. **Add Protobuf for Rust integration**: When migrating dialog daemon
3. **Design custom binary for streams**: Screenshot/frame protocol
4. **Consider Cap'n Proto**: If Zig GUI needs high-perf messaging

### Library Recommendations

**Elixir:**

- ETF: Built-in (`:erlang.term_to_binary/1`)
- Protobuf: `protobuf-elixir` or `protox`

**Rust:**

- Protobuf: `prost` (recommended) or `protobuf`
- Cap'n Proto: `capnp` crate

**Zig:**

- Custom binary: Native `packed struct`
- Protobuf: Would need C library binding

---

## Appendix: Quick Reference

### ETF Type Tags (Common)

| Tag | Type | Size |
|-----|------|------|
| 97 | SMALL_INTEGER | 1 |
| 98 | INTEGER | 4 |
| 70 | NEW_FLOAT | 8 |
| 100 | ATOM (deprecated) | 2+N |
| 104 | SMALL_TUPLE | 1+N |
| 108 | LIST | 4+N |
| 109 | BINARY | 4+N |
| 116 | MAP | 4+N |
| 118 | ATOM_UTF8 | 2+N |
| 119 | SMALL_ATOM_UTF8 | 1+N |

### Protobuf Wire Types

| ID | Name | For |
|----|------|-----|
| 0 | VARINT | int32, int64, bool, enum |
| 1 | I64 | fixed64, double |
| 2 | LEN | string, bytes, messages |
| 5 | I32 | fixed32, float |

### Cap'n Proto Pointer Types

| A bits | Type |
|--------|------|
| 0 | Struct |
| 1 | List |
| 2 | Far (inter-segment) |
| 3 | Other (capability) |

---

*Document compiled from browser research on Erlang ETF documentation, Protocol Buffers encoding guide, and Cap'n Proto encoding specification.*
