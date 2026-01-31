# Continuum Studio

**AI Orchestration Platform** for multi-agent control and verification.

![Continuum Studio](continuum-studio.png)

---

## Overview

Continuum Studio is a modular platform for orchestrating AI agents across multiple applications. It combines:

- **Desktop UI** (Rust/egui) — Unified interface for harness control
- **Mobile App** (Kotlin/Compose) — Remote monitoring and dialogs
- **Core Backend** (Elixir/OTP) — Fault-tolerant orchestration
- **Synapsix Integration** — NeSy verification and harness management

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONTINUUM STUDIO                             │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────────┐  │
│  │ Desktop UI  │  │ Mobile App  │  │    Studio Core          │  │
│  │ (Rust/egui) │  │ (Kotlin)    │  │    (Elixir/OTP)         │  │
│  └──────┬──────┘  └──────┬──────┘  └────────────┬────────────┘  │
│         │                │                      │               │
│         └────────────────┴──────────────────────┘               │
│                          │                                      │
│                    ETF / WebSocket                              │
│                          │                                      │
├──────────────────────────┼──────────────────────────────────────┤
│                          │                                      │
│         ┌────────────────┴────────────────┐                     │
│         │           SYNAPSIX              │                     │
│         │  ┌─────────┐  ┌─────────────┐   │                     │
│         │  │ NeSy    │  │ Harnesses   │   │                     │
│         │  │ (Z3/SMT)│  │ (Cursor,    │   │                     │
│         │  │         │  │  Android,   │   │                     │
│         │  │         │  │  Godot)     │   │                     │
│         │  └─────────┘  └─────────────┘   │                     │
│         │                                 │                     │
│         │  ┌─────────┐  ┌─────────────┐   │                     │
│         │  │ Dialog  │  │ Service     │   │                     │
│         │  │ Daemon  │  │ Registry    │   │                     │
│         │  └─────────┘  └─────────────┘   │                     │
│         └─────────────────────────────────┘                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

## Components

### `/ui` — Desktop UI (Rust)

Modular egui application with:
- **Harness Panel** — Color-coded status cards for each controlled application
- **Orchestrator Widget** — Multi-instance Cursor management
- **D2 Diagrams** — Interactive architecture visualization
- **IPC** — ETF-encoded communication with Elixir backend

```bash
cd ui && cargo run --release
```

### `/android` — Mobile App (Kotlin)

Jetpack Compose application with:
- **Widget Bay** — Customizable dashboard grid
- **Dialog Client** — WebSocket connection to dialog daemon
- **Service Discovery** — DNS-SD browsing

### `/core` — Studio Core (Elixir)

OTP supervision tree providing:
- **Version Registry** — 100+ Cursor versions with download/run
- **Socket Handler** — IPC events (harness metadata, agent responses)
- **Delegations** — Clean API to Synapsix

```bash
cd core/studio_core && iex -S mix
```

### `/archive` — Migration References

Archived code from nixos-cursor migration:
- `cursor-docs-REBUILD-TARGET/` — Documentation indexing (rebuild planned)

## Related Repositories

- **[synapsix](https://github.com/Distracted-E421/synapsix)** — NeSy orchestrator, harnesses, dialog system
- **[nixos-cursor](https://github.com/Distracted-E421/nixos-cursor)** — NixOS packaging for Cursor IDE
- **[homelab](https://github.com/Distracted-E421/homelab)** — Infrastructure and NixOS configurations

## Quick Start

```bash
# 1. Start Synapsix (required for harness control)
cd ~/synapsix && elixir --sname synapsix -S mix run --no-halt

# 2. Start Studio Core (optional, for UI integration)
cd ~/continuum-studio/core/studio_core && iex -S mix

# 3. Start Desktop UI
cd ~/continuum-studio/ui && cargo run --release
```

## Key Features

### Neurosymbolic AI (NeSy)

All agent actions can be verified via Z3 SMT solver before execution:
- **Constraint DSL** — Define safety constraints in Elixir
- **Formal Verification** — Prove actions satisfy constraints
- **Audit Logging** — Full trail with proofs

### Dialog System

Interactive feedback without API request waste:
- **8 Phases Complete** — Queue, memory, workflows, AFK work
- **Multi-device** — Desktop + mobile sync
- **Rich Context** — Code diffs, file previews, progress bars

### Service Discovery

DNS-SD with CoreDNS integration:
- **Automatic registration** — Harnesses register on start
- **Health monitoring** — Heartbeats and expiry
- **Multi-node** — Cross-machine discovery

## Documentation

- [Session Handoff Summary](docs/SESSION_HANDOFF_SUMMARY.md) — Current state and progress
- [Migration Assessment](docs/MIGRATION_ASSESSMENT.md) — nixos-cursor migration details
- [Architecture](docs/ARCHITECTURE.md) — System design
- [UI Paradigm](docs/UI_PARADIGM_EXPLORATION.md) — Widget-based design rationale

## License

MIT
