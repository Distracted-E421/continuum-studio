Synapsix:# IPC & Distribution Research: Synapsix + Continuum Studio Architecture

> **Research Date**: January 2026  
> **Purpose**: Evaluate communication mechanisms for distributed AI harness orchestration  
> **Scope**: BEAM distribution, D-Bus, hybrid approaches, and Zig integration

---

## Executive Summary

This document analyzes three primary approaches for inter-process and inter-machine communication in the Continuum Studio + Synapsix ecosystem:

| Approach | Best For | Limitations |
|----------|----------|-------------|
| **BEAM Distribution** | Multi-machine orchestration, fault tolerance, dynamic clusters | Erlang-only, requires cookie management |
| **D-Bus** | Local desktop integration, Linux services, low-latency IPC | Single-machine only, Linux-specific |
| **Hybrid** | Real-world production systems | Complexity, multiple protocols to maintain |

**Recommendation**: **Hybrid approach** with BEAM as the backbone for multi-machine orchestration and D-Bus/local IPC for desktop integration and high-frequency events.

---

## Part 1: BEAM Distribution (Erlang/Elixir Native)

### Overview

BEAM (Erlang VM) has built-in distributed computing capabilities that form the foundation of Elixir's "let it crash" philosophy. Nodes can transparently communicate across machine boundaries.

### Core Concepts

#### Nodes and Naming

```elixir
# Start node with name
iex --sname studio@obsidian

# Or with full name for cross-network
iex --name studio@192.168.1.10

# Nodes are atoms
:studio@obsidian
:"studio@obsidian.local"
```

**Node Types**:
- **Named nodes** (`--sname`): Simple names, same network segment
- **Fully qualified nodes** (`--name`): IP/hostname, cross-network
- **Hidden nodes** (`--hidden`): Don't appear in `Node.list/0`, for infrastructure services

#### epmd (Erlang Port Mapper Daemon)

- Runs on port 4369 by default
- Maps node names to TCP ports
- Each BEAM node gets a random port (or configurable)
- Required for node discovery

```bash
# Check registered nodes
epmd -names
# name studio at port 45123
# name synapsix at port 45456
```

#### Security: Magic Cookies

```elixir
# All communicating nodes must share the same cookie
# Set via:
# 1. ~/.erlang.cookie file (chmod 400)
# 2. --setcookie flag
# 3. Node.set_cookie/2

iex --sname studio --setcookie "continuum_secret_2026"
```

### Distributed Primitives

#### Message Passing

```elixir
# Send to named process on remote node
send({:harness_manager, :"synapsix@neon-laptop"}, {:focus_window, "cursor"})

# Or via GenServer
GenServer.call({MyServer, :"synapsix@neon-laptop"}, :get_state)

# Spawn on remote node
Node.spawn(:"synapsix@neon-laptop", fn -> 
  Synapsix.Harness.Cursor.focus()
end)
```

#### Process Groups (pg)

```elixir
# Join a group (replaces :pg2 from older Erlang)
:pg.join(:harnesses, self())

# Get all members
:pg.get_members(:harnesses)
# => [#PID<0.123.0>, #PID<17456.89.0>]  # PIDs from different nodes

# Broadcast to all
for pid <- :pg.get_members(:harnesses) do
  send(pid, {:broadcast, "shutdown_requested"})
end
```

#### Global Registration

```elixir
# Register process globally (cluster-wide unique name)
:global.register_name(:dialog_manager, self())

# Find it from any node
:global.whereis_name(:dialog_manager)
# => #PID<17456.123.0>
```

### C Nodes (Non-Erlang Integration)

For Zig/Rust components that need to participate in BEAM distribution:

```c
// C Node API (ei library)
#include "ei.h"
#include "erl_interface.h"

// Initialize node
erl_init(NULL, 0);
erl_connect_init(1, "continuum_secret_2026", 0);

// Connect to Erlang node
int fd = erl_connect("studio@obsidian");

// Send/receive messages
erl_send(fd, to_pid, msg);
```

**Key Points**:
- C nodes appear as regular BEAM nodes
- Use same cookie authentication
- Can receive/send Erlang terms
- Zig can link against ei/erl_interface libraries

### BEAM Distribution: Wins & Flaws

#### ✅ Wins

1. **Location Transparency**: Code doesn't change whether process is local or remote
2. **Built-in Fault Tolerance**: Supervisors work across nodes, links/monitors cross boundaries
3. **Hot Code Loading**: Update running nodes without downtime
4. **Process Groups**: Natural way to organize distributed harnesses
5. **No External Dependencies**: Built into the runtime
6. **Dynamic Clustering**: Nodes can join/leave at runtime

#### ❌ Flaws

1. **Erlang-only Ecosystem**: Non-BEAM components need C Nodes or Ports
2. **Security Model**: Cookie-based, no fine-grained permissions
3. **Network Assumptions**: Designed for reliable networks (LAN), can struggle with high latency
4. **Message Copying**: Large messages are serialized/copied (no zero-copy)
5. **No Built-in Discovery**: Need to manually connect nodes or use libraries
6. **epmd Dependency**: Another daemon to manage

### Multi-Machine Scenario: Studio on Obsidian, Harness on neon-laptop

```elixir
# On Obsidian (Studio)
iex --name studio@100.125.197.80 --setcookie synapsix_prod

# On neon-laptop (Synapsix)  
iex --name synapsix@100.125.197.86 --setcookie synapsix_prod

# Connect (from either side)
Node.connect(:"synapsix@100.125.197.86")

# Now direct communication works
GenServer.call(
  {Synapsix.Harness.Cursor, :"synapsix@100.125.197.86"},
  {:screenshot, [format: :png]}
)
```

---

## Part 2: D-Bus (Desktop Bus)

### Overview

D-Bus is the standard IPC mechanism for Linux desktop environments. It provides both system-level (root services) and session-level (user services) communication.

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Application                       │
├─────────────────────────────────────────────────────┤
│                   D-Bus Library                      │
│              (libdbus, sd-bus, zbus)                │
├─────────────────────────────────────────────────────┤
│                 D-Bus Daemon                         │
│         (dbus-daemon / dbus-broker)                 │
├─────────────────────────────────────────────────────┤
│          Unix Domain Sockets / TCP                   │
└─────────────────────────────────────────────────────┘
```

### Core Concepts

#### Bus Types

- **System Bus**: System-wide services (NetworkManager, udev, systemd)
- **Session Bus**: Per-user desktop services (KDE, dialogs, notifications)

```bash
# List session bus services
busctl --user list

# Our dialog daemon
# sh.cursor.studio.Dialog
```

#### Object Model

```
Service Name:     sh.cursor.studio.Dialog
Object Path:      /sh/cursor/studio/Dialog
Interface:        sh.cursor.studio.Dialog1
Methods:          ShowChoice, ShowConfirm, ShowTextInput, ShowToast
Signals:          DialogClosed, HoldModeChanged
Properties:       Version, HoldMode
```

#### Current Dialog Daemon (Rust/zbus)

```rust
// From cursor-dialog-daemon/src/dbus_interface.rs
#[interface(name = "sh.cursor.studio.Dialog1")]
impl DialogInterface {
    async fn show_choice(
        &self,
        title: String,
        prompt: String,
        options: String,  // JSON array
        default_value: String,
        allow_multiple: bool,
        timeout_ms: u32,
    ) -> String {  // JSON response
        // Creates dialog request, sends to UI thread
        // Waits for user response
        // Returns JSON with selection
    }
}
```

### D-Bus: Wins & Flaws

#### ✅ Wins

1. **Desktop Integration**: Native to Linux desktop (KDE, GNOME, etc.)
2. **Well-Established**: Mature, stable, widely supported
3. **Language Agnostic**: Bindings for Rust, Python, C, Go, etc.
4. **Activation**: Services start on-demand (systemd integration)
5. **Low Latency**: Unix sockets for local, very fast
6. **Type System**: Structured message types, introspection

#### ❌ Flaws

1. **Single Machine Only**: No native network distribution
2. **Linux-Specific**: No Windows/macOS (though alternatives exist)
3. **Daemon Dependency**: Requires dbus-daemon running
4. **Complexity**: Object paths, interfaces, method signatures
5. **No Fault Tolerance**: If daemon dies, all connections break
6. **Security Concerns**: Default policies can be permissive

### High-Frequency Events Concern

D-Bus is **not ideal** for streaming data:

```bash
# Screenshot stream example
# ❌ Each frame as D-Bus method call = overhead

# ✅ Better: D-Bus for control, separate channel for data
#    - Shared memory for frames
#    - Unix socket stream
#    - File descriptor passing
```

---

## Part 3: Hybrid Architecture (Recommended)

### The Case for Hybrid

Neither BEAM nor D-Bus alone solves all our problems:

| Requirement | BEAM | D-Bus | Hybrid |
|-------------|------|-------|--------|
| Multi-machine | ✅ | ❌ | ✅ |
| Desktop integration | ❌ | ✅ | ✅ |
| Fault tolerance | ✅ | ❌ | ✅ |
| High-frequency local | ⚠️ | ✅ | ✅ |
| Language agnostic | ⚠️ | ✅ | ✅ |

### Proposed Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        CONTINUUM STUDIO                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    Presentation Layer                        │    │
│  │              (Zig/egui/Slint - TBD)                         │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                   Event Bus / State                          │    │
│  │              (Local process communication)                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│              │                              │                        │
│              ▼                              ▼                        │
│  ┌───────────────────┐          ┌───────────────────┐              │
│  │   D-Bus Bridge    │          │   BEAM Bridge     │              │
│  │ (Local harnesses) │          │ (Distribution)    │              │
│  └───────────────────┘          └───────────────────┘              │
└──────────│───────────────────────────────│──────────────────────────┘
           │                               │
           ▼                               ▼
┌─────────────────────┐        ┌──────────────────────────────────────┐
│   Dialog Daemon     │        │           SYNAPSIX CLUSTER            │
│   (D-Bus service)   │        │  ┌────────────────────────────────┐  │
│   - User dialogs    │        │  │     Orchestrator (Elixir)      │  │
│   - Toast notifs    │        │  │  - Harness supervision         │  │
│   - Hold mode       │        │  │  - Cross-machine routing       │  │
└─────────────────────┘        │  │  - Failure recovery            │  │
                               │  └────────────────────────────────┘  │
                               │              │                        │
                               │   ┌──────────┴──────────┐            │
                               │   ▼                     ▼            │
                               │ ┌─────────┐      ┌─────────────┐     │
                               │ │ Cursor  │      │ Android     │     │
                               │ │ Harness │      │ Studio      │     │
                               │ │ (local) │      │ Harness     │     │
                               │ └─────────┘      │ (remote)    │     │
                               │                  └─────────────┘     │
                               └──────────────────────────────────────┘
```

### Communication Patterns

#### Pattern 1: Local High-Frequency (D-Bus + Shared Memory)

```
Studio UI  ──D-Bus──>  Dialog Daemon
                       (control messages)

Studio UI  <──shm───>  Screenshot Service  
                       (frame data, zero-copy)
```

#### Pattern 2: Cross-Machine Orchestration (BEAM)

```elixir
# Studio sends to Synapsix orchestrator
defmodule Studio.BEAMBridge do
  def execute_on_harness(harness_id, command) do
    node = Synapsix.Registry.find_harness_node(harness_id)
    GenServer.call({Synapsix.Router, node}, {:execute, harness_id, command})
  end
end
```

#### Pattern 3: Failure Recovery

```elixir
# BEAM handles this naturally
defmodule Synapsix.HarnessSupervisor do
  use Supervisor
  
  # If harness crashes, restart it
  # If node disconnects, :nodedown message triggers reconnect
  def init(_) do
    children = [
      {Synapsix.Harness.Cursor, []},
      {Synapsix.Harness.AndroidStudio, []},
    ]
    Supervisor.init(children, strategy: :one_for_one)
  end
end
```

### Migration Path

1. **Phase 1**: Keep dialog daemon on D-Bus, add BEAM bridge to Synapsix
2. **Phase 2**: Implement Synapsix orchestrator with multi-node support
3. **Phase 3**: Studio connects to Synapsix via BEAM (or gRPC for non-BEAM GUI)
4. **Phase 4**: Evaluate dialog daemon location (currently D-Bus works well)

---

## Part 4: Zig Integration Analysis

### Why Zig?

The user mentioned Zig for:
- Performance-critical parts (not memory-safety critical)
- Possibly GUI (instead of Rust/egui)
- Some planned component

### Zig Capabilities Overview

#### 1. C Interoperability (Exceptional)

```zig
// Direct C header import
const c = @cImport({
    @cInclude("ei.h");           // Erlang interface
    @cInclude("erl_interface.h");
    @cInclude("gtk/gtk.h");      // GTK for GUI
});

// Use C functions directly
pub fn connect_to_erlang(node: [*:0]const u8) !c_int {
    const fd = c.erl_connect(node);
    if (fd < 0) return error.ConnectionFailed;
    return fd;
}
```

**Key Points**:
- `@cImport` works at compile time (no runtime overhead)
- `zig translate-c` can convert C headers to Zig
- No FFI boilerplate like Rust's `extern "C"` blocks
- Can link against any C library

#### 2. Comptime (Compile-Time Execution)

```zig
// Generate lookup tables at compile time
const protocol_handlers = comptime blk: {
    var handlers: [256]HandlerFn = undefined;
    handlers[MSG_PING] = handlePing;
    handlers[MSG_FOCUS] = handleFocus;
    handlers[MSG_SCREENSHOT] = handleScreenshot;
    // ...
    break :blk handlers;
};

// Zero runtime cost dispatch
pub fn dispatch(msg_type: u8, data: []const u8) void {
    protocol_handlers[msg_type](data);
}
```

#### 3. Memory Layout Control

```zig
// Exact memory layout for protocol messages
const HarnessCommand = packed struct {
    magic: u32 = 0x53594E58,  // "SYNX"
    version: u8,
    command_type: CommandType,
    payload_len: u16,
    // Payload follows...
};

// Can cast directly from bytes
const cmd: *const HarnessCommand = @ptrCast(buffer.ptr);
```

#### 4. GUI Options with Zig

##### Option A: Zig + GTK4

```zig
const gtk = @cImport({
    @cInclude("gtk/gtk.h");
});

pub fn main() void {
    const app = gtk.gtk_application_new("studio.continuum", .{});
    _ = gtk.g_signal_connect(app, "activate", @ptrCast(&activate), null);
    _ = gtk.g_application_run(@ptrCast(app), 0, null);
}
```

**Pros**: Native Linux look, mature  
**Cons**: GTK complexity, not cross-platform friendly

##### Option B: Zig + SDL2/3 + Custom UI

```zig
const sdl = @cImport({
    @cInclude("SDL3/SDL.h");
});

// Custom immediate-mode UI on SDL
pub fn renderUI(renderer: *sdl.SDL_Renderer) void {
    // Draw custom widgets
}
```

**Pros**: Full control, cross-platform  
**Cons**: Build everything yourself

##### Option C: Zig + Raylib (Recommended for Prototyping)

```zig
const rl = @cImport({
    @cInclude("raylib.h");
});

pub fn main() void {
    rl.InitWindow(1280, 720, "Continuum Studio");
    defer rl.CloseWindow();
    
    while (!rl.WindowShouldClose()) {
        rl.BeginDrawing();
        rl.ClearBackground(rl.RAYWHITE);
        // Immediate mode UI here
        rl.EndDrawing();
    }
}
```

**Pros**: Simple, fast iteration, good for prototypes  
**Cons**: Not production-grade for complex UIs

##### Option D: Zig GUI Libraries

- **Capy**: Pure Zig cross-platform GUI (experimental)
- **ZigImGui**: Dear ImGui bindings for Zig
- **dvui**: Zig native immediate-mode UI

### Zig Integration Points for Continuum/Synapsix

#### 1. BEAM NIFs (Native Implemented Functions)

```zig
// harness_nif.zig
const beam = @import("beam");  // Would need bindings

export fn screenshot_nif(env: beam.Env, argc: c_int, argv: [*]beam.Term) beam.Term {
    // Take screenshot using efficient system calls
    const data = captureScreen();
    return beam.make_binary(env, data);
}
```

**When to Use**: Hot paths called frequently from Elixir  
**Example**: Screenshot capture, image processing, input simulation

#### 2. BEAM Ports (Separate Process)

```zig
// harness_port.zig - Runs as separate process
pub fn main() void {
    // Read commands from stdin (Erlang sends here)
    while (readCommand()) |cmd| {
        const result = executeCommand(cmd);
        // Write result to stdout (Erlang reads)
        writeResult(result);
    }
}
```

**When to Use**: Crash isolation, long-running operations  
**Example**: Window management daemon, GPU operations

#### 3. C Node Implementation

```zig
const ei = @cImport({
    @cInclude("ei.h");
    @cInclude("erl_interface.h");
});

pub fn main() void {
    // Initialize as Erlang C node
    _ = ei.erl_init(null, 0);
    _ = ei.erl_connect_init(1, "synapsix_cookie", 0);
    
    // Connect to cluster
    const fd = ei.erl_connect("synapsix@obsidian");
    
    // Participate in BEAM distribution
    while (true) {
        var msg: ei.ErlMessage = undefined;
        _ = ei.erl_receive_msg(fd, &buf, bufsize, &msg);
        // Handle messages...
    }
}
```

**When to Use**: Need to appear as BEAM node, complex bidirectional communication  
**Example**: High-performance harness that needs cluster membership

### Zig vs Rust for This Project

| Aspect | Zig | Rust |
|--------|-----|------|
| C Interop | ✅ Seamless | ⚠️ FFI boilerplate |
| Memory Safety | ⚠️ Manual | ✅ Compile-time |
| Build System | ✅ Simple (build.zig) | ✅ Cargo |
| Ecosystem | ⚠️ Young | ✅ Mature |
| Error Handling | ✅ Try/catch style | ✅ Result type |
| GUI Libraries | ⚠️ Limited native | ✅ egui, iced |
| NIF Support | ⚠️ Need to build | ✅ Rustler |

**Recommendation**: 
- **Use Zig for**: NIFs, Ports, C library wrappers, performance experiments
- **Keep Rust for**: Dialog daemon (already works), complex state management
- **Consider Zig GUI**: Only if willing to invest in building/binding

---

## Part 5: Specific Scenario Analysis

### Scenario 1: Multi-Machine Orchestration

**Setup**: Studio on Obsidian controls harness on neon-laptop

**BEAM Approach** (Recommended):
```elixir
# Automatic with BEAM distribution
# 1. Both machines run Synapsix nodes
# 2. Connect via Node.connect/1
# 3. Direct GenServer.call works

# On Obsidian
Studio.Agent.send_command(
  harness: :cursor,
  target: :"synapsix@neon-laptop",
  command: {:type_text, "Hello from Obsidian"}
)
```

**Hybrid Addition**: D-Bus for local operations on each machine

### Scenario 2: High-Frequency Events (Screenshots, Keyboard)

**Problem**: Screenshots at 10+ FPS, keyboard events in milliseconds

**Solution**: Bypass D-Bus for data, use for control

```
┌─────────────────┐     D-Bus      ┌──────────────────┐
│  Studio UI      │ ──────────────>│ Screenshot       │
│                 │   "start"      │ Service          │
│                 │<───────────────│                  │
│                 │   "ready"      │                  │
└────────┬────────┘                └────────┬─────────┘
         │                                  │
         │        Shared Memory             │
         └──────────────────────────────────┘
              /dev/shm/continuum-frames
              (mmap'd ring buffer)
```

**Implementation**:
```zig
// Zig screenshot service
const shm_name = "/continuum-frames";
const frame_buffer = shm.open(shm_name, .{ .create = true });

pub fn captureLoop() void {
    while (running) {
        const frame = captureScreen();
        frame_buffer.write(frame);  // Zero-copy to shared memory
        signal_new_frame();          // D-Bus notification (tiny)
    }
}
```

### Scenario 3: Failure & Recovery

**BEAM Shines Here**:

```elixir
defmodule Synapsix.ClusterMonitor do
  use GenServer
  
  def init(_) do
    # Monitor all connected nodes
    :net_kernel.monitor_nodes(true)
    {:ok, %{}}
  end
  
  def handle_info({:nodedown, node}, state) do
    Logger.warning("Node #{node} disconnected")
    
    # Reassign harnesses to other nodes
    Synapsix.Scheduler.redistribute_harnesses(node)
    
    # Attempt reconnection
    spawn(fn -> reconnect_loop(node) end)
    
    {:noreply, state}
  end
end
```

**D-Bus Failure** (needs manual handling):
```rust
// Must implement reconnection logic ourselves
loop {
    match Connection::session() {
        Ok(conn) => {
            run_service(conn);
        }
        Err(_) => {
            sleep(Duration::from_secs(1));
            // Retry...
        }
    }
}
```

### Scenario 4: Zig Integration Points

**Recommended Zig Uses**:

1. **Screenshot Capture NIF**
   - Hot path: 10+ FPS capture
   - Benefit: Direct syscalls, zero allocation
   
2. **Input Simulation Port**
   - Wayland/X11 input injection
   - Crash isolation from BEAM
   
3. **Protocol Buffer Codec**
   - High-speed message serialization
   - Comptime-generated parsers

**NOT Recommended for Zig** (yet):
- Main GUI (ecosystem too young)
- Core orchestration (BEAM is better)
- Dialog system (Rust/zbus works well)

---

## Part 6: Recommendations & Next Steps

### Architecture Decision

**Adopt Hybrid Architecture**:
1. **BEAM Distribution** for multi-machine orchestration
2. **D-Bus** for local desktop integration (dialogs, notifications)
3. **Shared Memory/Unix Sockets** for high-frequency data
4. **Zig NIFs/Ports** for performance-critical capture/injection

### Immediate Actions

1. **Keep dialog-daemon on D-Bus** (it works, well-integrated)
2. **Move dialog-daemon to synapsix repo** (it's a harness component)
3. **Implement BEAM bridge in Studio** (for Synapsix communication)
4. **Prototype Zig screenshot NIF** (benchmark vs current approach)

### Migration Priority

```
Phase 1: Core Infrastructure
├── Move dialog-daemon to synapsix/services/
├── Add BEAM distribution config to Synapsix
└── Create Studio ↔ Synapsix BEAM bridge

Phase 2: Performance Optimization  
├── Implement Zig screenshot NIF
├── Add shared memory frame buffer
└── Benchmark D-Bus vs direct approaches

Phase 3: Multi-Machine
├── Deploy Synapsix on multiple machines
├── Test cross-machine harness control
└── Implement cluster health monitoring
```

### Open Questions

1. **GUI Technology**: Stick with Rust/egui or explore Zig options?
2. **Protocol Format**: Erlang terms, Protocol Buffers, or custom binary?
3. **Discovery**: Manual node connection vs service discovery (mDNS, Consul)?
4. **Security**: Cookie-based OK, or need TLS between nodes?

---

## Appendix: Reference Materials

### BEAM Distribution
- [Erlang Distributed Programming](https://www.erlang.org/doc/reference_manual/distributed.html)
- [Elixir Node module](https://hexdocs.pm/elixir/Node.html)
- [C Nodes](https://www.erlang.org/doc/tutorial/cnode.html)

### D-Bus
- [D-Bus Specification](https://dbus.freedesktop.org/doc/dbus-specification.html)
- [zbus (Rust)](https://docs.rs/zbus/latest/zbus/)

### Zig
- [Zig Language Reference](https://ziglang.org/documentation/master/)
- [Zig C Interop](https://ziglang.org/documentation/master/#C-Interoperability)

### GUI Options
- [egui (Rust)](https://github.com/emilk/egui) - Current choice
- [Raylib (C/Zig)](https://www.raylib.com/) - Prototype option
- [Capy (Zig)](https://github.com/capy-ui/capy) - Experimental

---

*Document compiled from browser research on BEAM distribution, D-Bus specification, and Zig language reference.*

