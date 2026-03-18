# Desktop Performance Audit - March 17, 2026

## Summary

Quick audit of Continuum Studio desktop (iced) performance characteristics.

## Metrics Baseline

| Metric | Target | Status |
|--------|--------|--------|
| Frame time | < 8.3ms (120 FPS) | Not measured |
| WebSocket latency | < 100ms | Not measured |
| Memory | Stable | ✅ Bounded |

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

### ⚠️ Minor Concerns

**Triage Queue:**
- Unbounded `Vec<TriageItem>`
- Mitigated by 30s default timeout (items self-expire)
- Consider adding max_triage_size limit

**Undoable Decisions:**
- Also unbounded `Vec<(DecisionRecord, Instant)>`
- Mitigated by 10s undo window with `cleanup_undoable()`

## Subscription Efficiency

### Active Subscriptions (11 total)

| Subscription | Interval | Active When |
|-------------|----------|-------------|
| core_subscription | Continuous | Always |
| subagent_subscription | 2s | SubAgents tab |
| task_queue_subscription | Continuous | Always |
| dialog_daemon_subscription | Continuous | Always |
| activity_feed_subscription | Continuous | Always |
| activity_stream_subscription | Continuous | Always |
| coordinator_poll_subscription | 10s | Always |
| cli_agents_subscription | Continuous | CLIAgents tab |
| orchestrator_ws_subscription | Continuous | Orchestrator tab |
| keyboard_shortcut_subscription | Event-driven | Always |
| triage_timeout_subscription | 1s | Orchestrator tab |
| dialog_polling_subscription | 5s | CLI/Orchestrator tabs |

### ⚠️ Potential Issues

**1-Second Triage Timeout:**
- Polls every 1 second when on Orchestrator tab
- `process_triage_timeouts()` is O(n) on queue size
- Usually n=0 so O(1) in practice
- Consider: Only poll if queue non-empty

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

### P1 - Quick Wins

1. **Conditional triage polling:**
   ```rust
   fn triage_timeout_subscription(active: bool) -> iced::Subscription<Message> {
       if !active || state.orchestrator_state.engine.triage_queue().is_empty() {
           return iced::Subscription::none();
       }
       iced::time::every(Duration::from_secs(1))...
   }
   ```

2. **Add triage queue limit:**
   ```rust
   const MAX_TRIAGE_QUEUE: usize = 100;
   ```

### P2 - Medium Priority

3. **Profile with `tracing` spans:**
   - Add spans to view functions
   - Identify slow renders

4. **Lazy WebSocket connections:**
   - Only connect when tab becomes active
   - Currently always connecting

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
