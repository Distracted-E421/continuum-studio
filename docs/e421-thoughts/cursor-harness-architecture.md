# Cursor Harness Architecture

## Vision: Elixir as a "Cushion Harness" for Cursor Instances

Rather than isolated Cursor installations, we could orchestrate multiple Cursor instances through an Elixir supervisor that provides:

### Resource Sharing

- **Memory Coordination**: Track and balance memory usage across instances
- **CPU Affinity**: Assign cores/threads to specific instances based on workload
- **Disk I/O**: Coordinate heavy operations (indexing, git) to avoid contention
- **Network Pooling**: Share HTTP connections for AI API calls

### Instance Management

- **Lifecycle Control**: Start, stop, pause, resume instances gracefully
- **State Persistence**: Save/restore session state between runs
- **Hot Swap**: Upgrade Cursor version without losing context
- **Crash Recovery**: Automatically restart failed instances with state

### Control Points

- **Process Supervision**: BEAM's "let it crash" philosophy for Cursor instances
- **Message Interception**: Monitor/modify IPC between Cursor components
- **Plugin Injection**: Load custom extensions at runtime
- **API Proxying**: Route AI calls through custom middleware

## Why Elixir for This?

### Process Isolation

```elixir
defmodule CursorSupervisor do
  use Supervisor
  
  def init(_) do
    children = [
      {CursorInstance, [id: :primary, version: "2.4.21"]},
      {CursorInstance, [id: :secondary, version: "2.0.77"]},  # Custom modes
      {ResourceMonitor, []},
      {IPCProxy, []}
    ]
    Supervisor.init(children, strategy: :one_for_one)
  end
end
```

### Fault Tolerance

- If one Cursor instance crashes, others continue
- Automatic restart with backoff
- State recovery from checkpoints

### Concurrent Control

- Monitor multiple instances simultaneously
- Coordinate actions across instances
- Handle events from all sources concurrently

## Integration with Synapsix

The existing Synapsix harness infrastructure could be extended:

```elixir
defmodule Synapsix.Harnesses.CursorOrchestrator do
  use GenServer
  
  def start_instance(version, opts \\ []) do
    # Download if needed
    path = ensure_version(version)
    
    # Start with custom environment
    env = build_environment(opts)
    
    # Monitor and proxy
    {:ok, pid} = CursorInstance.start(path, env)
    register_instance(pid, version, opts)
  end
  
  def share_resources(instances, resource_type) do
    # Coordinate resource allocation
  end
  
  def hot_swap(instance_id, new_version) do
    # Preserve state, upgrade, restore
  end
end
```

## Rust CLI vs Elixir Orchestrator

| Aspect | Rust CLI | Elixir Orchestrator |
|--------|----------|---------------------|
| Startup | ~3ms | ~320ms |
| Memory overhead | Minimal | BEAM VM |
| Concurrent instances | Manual | Built-in |
| Fault tolerance | None | Supervisors |
| Hot code loading | No | Yes |
| IPC integration | External | Native |

**Conclusion**: Use Rust for quick CLI tasks (list, download), Elixir for runtime orchestration.

## Next Steps

1. Add `run` command to both CLIs
2. Implement basic CursorInstance GenServer
3. Add resource monitoring
4. Create IPC proxy for message interception
5. Integrate with Synapsix dialog for multi-instance UI

## Ideas from User

> "not isolate them from one another, but allow them to efficiently move around the system, memory, harnesses, share cores and resources"

This suggests:

- Dynamic resource reallocation based on workload
- Instance migration (move work between Cursor versions)
- Shared memory regions for common data (model caches?)
- Core affinity that adjusts in real-time
