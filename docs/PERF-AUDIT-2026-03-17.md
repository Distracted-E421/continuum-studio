# Desktop Performance Audit - March 17, 2026

## Summary

Quick audit of Continuum Studio desktop (iced) performance characteristics.

## Metrics Baseline

| Metric | Target | Status |
|--------|--------|--------|
| Frame time | < 8.3ms (120 FPS) | Not measured |
| WebSocket latency | < 100ms | Not measured |
| Memory | Stable | ✅ Bounded |

## Memory Tracking

**New:** `MemoryStats` struct added for runtime monitoring:

```rust
struct MemoryStats {
    agents_tracked: usize,
    dialogs_cached: usize,
    activity_items: usize,
    decision_history: usize,
    triage_queue: usize,
    updated_at: u64,
}
```

Logged every 60 seconds via `memory_stats_subscription()`.
View with: `RUST_LOG=continuum_studio_iced=debug`

## Memory Management

### ✅ Good Patterns

**Decision Engine History:**
- Limited to 1000 entries via `max_history_size`
- Uses `VecDeque` with `pop_front()` for FIFO trimming
- Location: `ui-iced/src/decision_engine.rs:379-381`

**Activity Feed:**
- Ring buffer with `MAX_EVENTS = 500`
- Uses `VecDeque` with `pop_front()` when at capacity
- Location: `ui-iced/src/activity_feed.rs:15, 311-312`

**Session Metrics:**
- Replaced entirely on update (not appended)
- Location: `ui-iced/src/main.rs:2569, 2578, 2582`

### ✅ Fixed

**Triage Queue:**
- ~~Unbounded `Vec<TriageItem>`~~ → **Limited to 100 items** via `max_triage_size`
- Drops oldest item when at capacity (sorts by `added_at`)
- Location: `ui-iced/src/decision_engine.rs:404-413`

### ⚠️ Minor Concerns

**Undoable Decisions:**
- Unbounded `Vec<(DecisionRecord, Instant)>`
- Mitigated by 10s undo window with `cleanup_undoable()`

## Subscription Efficiency

### Active Subscriptions (12 total)

| Subscription | Interval | Active When |
|-------------|----------|-------------|
| core_subscription | Continuous | Always |
| subagent_subscription | 2s | SubAgents tab |
| task_queue_subscription | Continuous | Always (multi-window) |
| dialog_daemon_subscription | Continuous | Always |
| activity_feed_subscription | Continuous | **AgentActivity tab** ✅ |
| activity_stream_subscription | Continuous | **AgentActivity tab** ✅ |
| coordinator_poll_subscription | 10s | **Dashboard view** ✅ |
| cli_agents_subscription | Continuous | CLIAgents tab |
| orchestrator_ws_subscription | Continuous | Orchestrator tab |
| keyboard_shortcut_subscription | Event-driven | Always |
| triage_timeout_subscription | 1s | Orchestrator tab |
| dialog_polling_subscription | 5s | CLI/Orchestrator tabs |

**Lazy subscriptions (✅):** 3 connections now only active when viewing relevant content.

### ✅ Fixed

**1-Second Triage Timeout:**
- ~~Polls every 1 second when on Orchestrator tab~~ → **Only polls when queue non-empty**
- `triage_timeout_subscription(active, has_triage_items)` checks both conditions
- Location: `ui-iced/src/main.rs:274-276, 670-679`

## Clone Usage

**Total .clone() calls in main.rs:** 183

This is typical for iced applications where messages require owned data.
No obvious optimization opportunities without significant refactoring.

## Rendering

### View Function Count
- 30+ view functions in main.rs
- Each potentially called on every redraw

### Not Measured
- Need tracing/profiling for:
  - Unnecessary redraws
  - Canvas widget efficiency
  - Theme switching cost

## Recommendations

### ✅ P1 - Implemented

1. **Conditional triage polling:** ✅
   - `triage_timeout_subscription` now takes `has_triage_items` parameter
   - Only polls when queue is non-empty

2. **Triage queue limit:** ✅
   - `max_triage_size: 100` added to `DecisionEngineConfig`
   - `add_to_triage()` drops oldest items when at capacity

### ✅ P2 - Complete

3. **Profile spans:** ✅
   - `ui-iced/src/profiling.rs` - Lightweight span utility
   - `profile_span!` macro for view/update functions
   - Logs to existing log infrastructure
   - Usage: `RUST_LOG=continuum_studio_iced::profiling=debug`
   - Thresholds: 16ms (frame), 8ms (fast), 0ms (always)
   - Instrumented functions:
     - `update()` - Main update loop
     - `view_dashboard()` - Dashboard rendering
     - `view_sessions()` - Sessions tab
     - `view_cli_agents()` - CLI agents tab
     - `view_orchestrator()` - Orchestrator panel
     - `process_triage_timeouts()` - Triage processing

4. **Lazy WebSocket connections:** ✅
   - `activity_feed_subscription(active)` - Only connects when on AgentActivity tab
   - `activity_stream_subscription(active)` - Only connects when on AgentActivity tab
   - `coordinator_poll_subscription(active)` - Only polls when on Dashboard view
   - Saves 3 background connections when not on relevant views
   - Location: `ui-iced/src/main.rs:244-263, 437-451, 456-463, 509-516`

### P3 - Future

5. **Incremental rendering:**
   - iced supports incremental diffs
   - May already be enabled by default

6. **Consider state splitting:**
   - Large ContinuumStudio struct (50+ fields)
   - Could split into sub-states for partial updates

## Files Reviewed

- `ui-iced/src/main.rs` - Main loop, subscriptions
- `ui-iced/src/decision_engine.rs` - Memory limits
- `ui-iced/src/activity_feed.rs` - Ring buffer
- `ui-iced/src/orchestrator_panel.rs` - Triage handling

## Next Steps

1. Run with `RUST_LOG=debug` to see message frequency
2. Profile with `perf` or `flamegraph` for hot paths
3. Measure actual FPS with loaded UI
