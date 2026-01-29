# Continuum Studio Session Handoff Summary

**Date**: January 29, 2026
**Purpose**: Summary of architectural decisions, implemented components, and current state to facilitate rapid context loading for the next development session.

## 1. High-Level Architecture

We are building **Continuum Studio**, a modular AI orchestration platform.

- **Architecture Style**: Layered, decoupled, message-driven.
- **UI**: Rust (`egui`/`eframe`) for performance and native integration.
- **Core**: Elixir (`OTP`/`BEAM`) for fault-tolerant state management and orchestration.
- **Communication**:
  - **UI ↔ Core**: Unix Domain Sockets using **ETF (Erlang Term Format)** (primary) or JSON (fallback).
  - **Synapsix ↔ Core**: Unix Domain Sockets (JSON/ETF).
  - **Dialogs**: D-Bus (`continuum-dialog-daemon`) for blocking user interaction.

## 2. Component Inventory

### A. Studio UI (`continuum-studio/ui`)

**Status**: Functional Prototype

- **Language**: Rust
- **Key Modules**:
  - `src/main.rs`: Entry point, `eframe` setup, main loop.
  - `src/widgets/`: Modular widget system.
    - `diagram.rs`: Interactive D2 diagram renderer (ported).
    - `code_view.rs`: Syntax-highlighted code editor/viewer.
    - `terminal.rs`: ANSI-capable terminal widget.
    - `harness_panel.rs`: Side panel for controlling harnesses.
    - `agent_stream.rs`: Chat-like interface for agent output.
    - `tab_bar.rs`: Tabbed container for widgets.
  - `src/ipc/`: Inter-process communication.
    - `mod.rs`: `IpcClient` (async, cloneable).
    - `etf.rs`: **New** ETF encoding/decoding implementation (uses `erlang` crate).
    - `dbus.rs`: Client for `continuum-dialog-daemon`.
  - `src/theme/`: VS Code-compatible theming engine.

### B. Studio Core (`continuum-studio/core/studio_core`)

**Status**: Initial Implementation

- **Language**: Elixir
- **Key Modules**:
  - `StudioCore.Application`: Supervisor tree.
  - `StudioCore.State`: ETS-backed global state KV store.
  - `StudioCore.EventBus`: PubSub system for decoupling components.
  - `StudioCore.HarnessRegistry`: Tracks connected Synapsix harnesses.
  - `StudioCore.Socket.Handler`: Handles UI/Synapsix connections. Auto-detects JSON vs ETF.

### C. Agent Bridge (`continuum-studio/core/agent_bridge`)

**Status**: Scaffolded

- **Language**: Elixir
- **Purpose**: Connects Studio Core to LLM providers (OpenAI, Anthropic, Local).
- **Key Modules**:
  - `AgentBridge.AgentManager`: Supervisor for agent processes.
  - `AgentBridge.Providers`: Abstraction for different LLM backends.

### D. Synapsix (`synapsix`)

**Status**: Integrating

- **Language**: Elixir
- **Purpose**: Distributed harness orchestrator (runs apps like Cursor, Android Studio).
- **Changes**:
  - Renamed `cursor-dialog-daemon` → `continuum-dialog-daemon`.
  - `Synapsix.CoreClient`: New client to register harnesses with Studio Core.
  - `Synapsix.Harnesses.Cursor.ResponseCapture`: Improved OCR/screenshot logic for capturing AI output.

## 3. Protocol & Data Flow

### ETF Protocol (`docs/ETF_PROTOCOL.md`)

We established a binary protocol for efficiency:

- **Format**: 4-byte length prefix + ETF payload.
- **Commands (UI→Core)**: `{:command, :name, %{params}}` (e.g., `:harness_start`).
- **Events (Core→UI)**: `{:event, :type, %{data}}` (e.g., `:harness_status`, `:agent_response`).
- **Implementation**: Fully implemented in Rust `ui/src/ipc/etf.rs` and Elixir `Socket.Handler`.

### Workflow

1. **User** interacts with UI (Rust).
2. **UI** sends ETF command to **Core**.
3. **Core** updates state/registry and publishes event via `EventBus`.
4. **Synapsix** (via `CoreClient`) receives command, controls target app (e.g., Cursor).
5. **Synapsix** captures output, sends event back to **Core**.
6. **Core** forwards event to **UI**.
7. **UI** renders update (e.g., new text in `AgentStreamWidget`).

## 4. Key Documents & Diagrams

- `docs/UI_ARCHITECTURE.md`: Detailed breakdown of the UI structure.
- `docs/ETF_PROTOCOL.md`: Specification of the binary protocol.
- `docs/diagrams/architecture-layers.d2`: High-level system layers.
- `docs/diagrams/widget-system.d2`: Widget interaction model.
- `docs/MIGRATION_ASSESSMENT.md`: Plan for moving code from `nixos-cursor`.

## 5. Next Steps for Next Agent

1. **Run the System**:
    - Start Core: `cd core/studio_core && iex -S mix`
    - Start Synapsix: `cd synapsix && iex -S mix`
    - Start UI: `cd ui && cargo run`
    - *Note: Ensure `/tmp/continuum-studio.sock` is managed correctly.*

2. **Verify End-to-End Flow**:
    - Test if clicking "Start" in UI actually triggers Synapsix harness.
    - Verify OCR capture from Synapsix shows up in UI Agent Stream.

3. **Agent Bridge Implementation**:
    - Flesh out `AgentBridge` to actually call LLM APIs.
    - Connect `AgentBridge` to `StudioCore` event bus.

4. **UI Polish**:
    - Improve `CodeViewWidget` rendering (selection, line numbers).
    - Enhance `DiagramWidget` interactivity (drag nodes, edit properties).

## 6. Known Issues / Notes

- The Rust UI uses `erlang` crate (v2.0) for ETF.
- `continuum-dialog-daemon` binary name changed from `cursor-dialog-daemon`.
- Synapsix `ResponseCapture` depends on `spectacle` (KDE), `grim` (Wayland), or `import` (X11) and `tesseract`.
