# Continuum Studio Development Roadmaps

This directory contains roadmaps for Continuum Studio development. Continuum Studio is the AI orchestration platform, providing unified interfaces for AI providers, harness control, and mobile companion features.

---

## Available Roadmaps

| Roadmap | Focus | Complexity | Priority |
|---------|-------|------------|----------|
| [UI Stabilization](UI_STABILIZATION_ROADMAP.md) | iced UI feature completion | Medium | High |
| [Socket IPC](SOCKET_IPC_ROADMAP.md) | UI ↔ Core communication | Medium | High |
| [Mobile Integration](MOBILE_INTEGRATION_ROADMAP.md) | Android app + sync | High | Medium |

---

## Current State (2026-02-20)

### What Works
- ✅ Basic iced UI rendering (Dashboard, partial views)
- ✅ Studio Core (Elixir) - event bus, state, socket acceptor
- ✅ Agent Bridge - Claude provider, Ollama provider
- ✅ Cost tracking & rate limiting
- ✅ Provider middleware pipeline

### What Needs Work
- ⚠️ UI Socket IPC to Core (needs testing)
- ⚠️ Chat Pipeline view (partial)
- ⚠️ Cursor harness provider (needs Synapsix)
- ❌ Mobile app ↔ Desktop sync
- ❌ OpenAI provider

---

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│              Continuum Studio               │
├─────────────────┬───────────────────────────┤
│   UI (iced/Rust)│      Core (Elixir)        │
│                 │                           │
│  ┌───────────┐  │  ┌───────────────────┐    │
│  │ Dashboard │  │  │   Studio Core     │    │
│  │ Chat View │◄─┼──► (Event Bus, ETS) │    │
│  │ Services  │  │  └───────────────────┘    │
│  │ Settings  │  │           ↓               │
│  └───────────┘  │  ┌───────────────────┐    │
│        ↓        │  │   Agent Bridge    │    │
│   Unix Socket   │  │ (Providers, Cost) │    │
│                 │  └───────────────────┘    │
└─────────────────┴───────────────────────────┘
          │                    │
          ↓                    ↓
    ┌──────────┐        ┌──────────────┐
    │ Android  │        │  AI Providers │
    │   App    │        │ Claude/Ollama │
    └──────────┘        └──────────────┘
```

---

## Parallel Session Guide

### Session A: UI Stabilization
Focus on completing iced UI views:
1. Complete Chat Pipeline view
2. Complete Services panel
3. Add Subagent management UI
4. Test all view transitions

### Session B: Socket IPC
Focus on UI ↔ Core communication:
1. Test Unix socket connection
2. Implement message protocol
3. Add reconnection handling
4. Create integration tests

### Session C: Harness Integration
Focus on Synapsix harness control:
1. Test Cursor harness provider
2. Add harness status display
3. Implement harness commands from UI

### Session D: Mobile Sync
Focus on Android app integration:
1. WebSocket protocol design
2. State synchronization
3. Push notifications

---

## Quick Start for Agents

```bash
# Build UI
cd /home/e421/continuum-studio/ui-iced
cargo build --release

# Test Core apps
cd /home/e421/continuum-studio/core/studio_core
mix test

cd /home/e421/continuum-studio/core/agent_bridge
mix test

# Run Core
cd /home/e421/continuum-studio/core/studio_core
iex -S mix

# Run UI (requires Core running)
cd /home/e421/continuum-studio/ui-iced
cargo run --release
```

---

## Related Documentation

- [Status February 2026](../STATUS_FEBRUARY_2026.md) - Current state
- [Architecture](../ARCHITECTURE.md) - System design
- [UI Stabilization Plan](../UI_STABILIZATION_PLAN.md) - Detailed UI work
- [Agent Bridge Architecture](../AGENT_BRIDGE_ARCHITECTURE.md) - Provider system

---

**Last Updated**: 2026-02-20
