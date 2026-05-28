# Continuum Studio Agent Memory

## Project Context

AI Orchestration Platform for multi-agent control, monitoring, and verification.

**See `ecosystem.md` for:** dev preferences, language philosophy, Synapsix tooling.

**License:** SSPL-1.0

## Architecture

```
ui-iced/     # Desktop UI (Rust + iced 0.14, COSMIC styling)
android/     # Mobile app (Kotlin + Jetpack Compose)
core/        # Orchestration hub (Elixir/OTP)
crates/      # Shared Rust libraries
```

## Status

**Branch:** `iced-migration` (clean, 139 tests pass)

### Desktop UI (`ui-iced/`)

| Feature | Module |
|---------|--------|
| CLI Agents | presets, batch launch, dialog inbox |
| Orchestrator | decision engine, mode control, triage queue |
| Diagrams | Mermaid/D2 via `widgets/diagram.rs` |
| Offline mode | operation queue via `offline.rs` |
| Activity Feed | real-time events, filters |
| Task Queue | HTTP/WebSocket to Synapsix |

### Android (`android/`)

- **XR middleware overlay** for Samsung S23 Ultra + RayNeo 3 glasses (evolving beyond Dex/mirror)
- `ZonedGlassesLayout`: left=dialog content, center=see-through, right=options; entrance animations (v1.9.0+)
- `DialogControlPanel`: phone becomes input device in XR mode (numbered options, fixed keyboard, voice typing)
- **Agent Camera**: Capture images from phone camera and send directly to agents (Settings → Agent Camera)
  - `camera/AgentCameraClient.kt`: HTTP client for Synapsix/Cognix visual context API
  - `ui/camera/AgentCameraScreen.kt`: CameraX preview + capture + send flow
  - Connection indicators for both Synapsix and Cognix backends
- Widget Bay dashboard, Dialog Client (WS :8080)
- Multi-endpoint config with auto-fallback
- **OTA updates** via GitHub releases (`gh release create`); app checks releases for APK download
- Android Auto integration planned (`docs/ANDROID_AUTO_INTEGRATION_PLAN.md`)

### Core (`core/`)

- Elixir/OTP orchestration hub
- Agent Bridge (multi-provider AI)
- Version Registry, Workspace Tracker

## Synapsix Integration

| Component | Purpose |
|-----------|---------|
| `dialog_client.rs` | D-Bus to synapsix-dialog-daemon |
| `cli_agents_client.rs` | HTTP/WS to Synapsix API |
| `decision_engine.rs` | Auto-handling by orchestrator mode |
| `orchestrator_panel.rs` | Mode control, triage UI |

**D-Bus:** `sh.synapsix.Dialog` + `sh.synapsix.TerminalMonitor`

**Orchestrator Modes:** UserActive, UserDelegate, Spectator, Autonomous

## Build & Test

```bash
# Desktop
cd ui-iced && cargo build --release

# Android  
cd android && ./gradlew assembleDebug

# Core
cd core && mix deps.get && mix compile
```

## Integration Points

- **Synapsix Dialog:** WebSocket port 8080
- **Terminal Monitor:** Cursor terminal insights
- **Phosphor:** Screen capture via D-Bus
- **NeSy:** Verification bridge (Z3/SMT)

## Related Projects

- `~/synapsix` - Dialog daemon, terminal monitor, harnesses
- `~/phosphor` - Screen capture service
