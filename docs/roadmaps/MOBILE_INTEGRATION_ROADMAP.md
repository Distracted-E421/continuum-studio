# Mobile Integration Roadmap

**Priority**: Medium
**Complexity**: High
**Dependencies**: Android development, WebSocket, Synapsix CoreClient
**Estimated Agent Sessions**: 3-4 focused sessions

---

## Overview

The Continuum Studio Android app connects to the desktop Synapsix/Studio Core system, enabling mobile dialog responses, task management, and agent monitoring.

### Current State (Updated 2026-02-21)

**Android Project** (`/home/e421/continuum-studio/android/`):
- ✅ Full Gradle project structure (Kotlin-based)
- ✅ `MainActivity.kt` - Main entry point
- ✅ `DialogWebSocketClient.kt` - WebSocket networking
- ✅ `DialogViewModel.kt` - Dialog state management
- ✅ `WidgetBayViewModel.kt` - Widget state
- ✅ `DialogScreen.kt` - Dialog UI (Compose)
- ✅ `WidgetContents.kt`, `WidgetBay.kt` - Widget UI
- ✅ `DialogNotificationService.kt` - Background service
- ✅ Material You theming (Color.kt, Theme.kt, Type.kt)
- ✅ Data models (`DialogModels.kt`, `WidgetModels.kt`)

**Phase Status:**
| Phase | Status |
|-------|--------|
| Phase 1: WebSocket Server | ⚠️ Protocol defined, server needs testing |
| Phase 2: State Sync | ⚠️ Partial implementation |
| Phase 3: Dialog Bridge | ✅ Core implementation done |
| Phase 4: Android App | ✅ Functional app with dialogs |
| Phase 5: Advanced Features | ❌ Pending (push notifications, offline) |

**Synapsix Integration:**
- ✅ `CoreClient` module exists (305 lines)
- ⚠️ WebSocket server in Studio Core needs deployment testing

---

## Phase 1: WebSocket Server

**Goal**: Expose Studio Core via WebSocket for mobile access

### Tasks

1. **Add WebSocket support to Core**
   ```elixir
   # In Studio Core supervision tree
   {Plug.Cowboy, scheme: :http, plug: StudioCore.MobileRouter, options: [port: 4001]}
   ```

2. **Define WebSocket protocol**
   ```elixir
   defmodule StudioCore.MobileSocket do
     use Phoenix.Channel  # or raw cowboy_websocket
     
     def handle_in("sync_state", _, socket) do
       state = StudioCore.State.snapshot()
       push(socket, "state", state)
       {:noreply, socket}
     end
   end
   ```

3. **Add authentication**
   - Token-based auth
   - Device registration
   - Session management

### Success Criteria

- [ ] WebSocket server running on port 4001
- [ ] Mobile can connect and authenticate
- [ ] Basic messages exchanged

---

## Phase 2: State Synchronization

**Goal**: Keep mobile and desktop state consistent

### Tasks

1. **Define sync protocol**
   - Full state sync on connect
   - Incremental updates via events
   - Conflict resolution strategy

2. **Implement state snapshot**
   ```elixir
   defmodule StudioCore.State do
     def snapshot do
       %{
         agents: list_agents(),
         tasks: list_tasks(),
         dialogs: pending_dialogs(),
         services: service_status(),
         costs: cost_summary()
       }
     end
   end
   ```

3. **Push updates on change**
   - Subscribe to event bus
   - Filter relevant events
   - Broadcast to connected clients

### Success Criteria

- [ ] Mobile receives initial state
- [ ] Updates push in <500ms
- [ ] State stays synchronized

---

## Phase 3: Dialog Bridge

**Goal**: Show Synapsix dialogs on mobile

### Tasks

1. **Hook into Synapsix Dialog system**
   - When dialog created, notify mobile clients
   - Format dialog for mobile UI
   - Route responses back

2. **Mobile dialog UI**
   - Choice dialogs
   - Confirmation dialogs
   - Text input dialogs
   - Slider dialogs

3. **Timeout handling**
   - Show remaining time
   - Allow hold mode
   - Handle network latency

### Success Criteria

- [ ] Dialogs appear on mobile
- [ ] Can respond from mobile
- [ ] Responses work like desktop

---

## Phase 4: Android App Development

**Goal**: Functional Android app

### Tasks

1. **Project setup**
   ```bash
   cd /home/e421/continuum-studio/android
   # Review existing project structure
   ```

2. **Core features**
   - Connection management
   - State display
   - Dialog handling
   - Task list

3. **UI design**
   - Material You design
   - Dark/light theme
   - Responsive layout

### Success Criteria

- [ ] App builds and runs
- [ ] Connects to desktop
- [ ] All core features work

---

## Phase 5: Advanced Features

**Goal**: Production-ready mobile experience

### Tasks

1. **Push notifications**
   - FCM integration
   - Background service
   - Notification channels

2. **Offline support**
   - Cache recent state
   - Queue actions
   - Sync on reconnect

3. **Security hardening**
   - Certificate pinning
   - Encrypted storage
   - Biometric auth

### Success Criteria

- [ ] Notifications work in background
- [ ] Works offline with sync
- [ ] Security audit passed

---

## Architecture

```
┌──────────────────────────────────────────────┐
│                  Mobile App                   │
├──────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────────────────┐│
│  │   UI Layer  │  │     State Manager       ││
│  │  (Compose)  │  │  (ViewModel + LiveData) ││
│  └─────────────┘  └─────────────────────────┘│
│          ↓                    ↓              │
│  ┌──────────────────────────────────────────┐│
│  │         WebSocket Client                  ││
│  │    (OkHttp / Ktor + JSON)                ││
│  └──────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
                       │
                 WebSocket
                       │
┌──────────────────────────────────────────────┐
│               Desktop (Studio Core)          │
├──────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────────────────┐│
│  │ Mobile      │  │     Event Bus           ││
│  │ Socket      │←→│  (state changes)        ││
│  └─────────────┘  └─────────────────────────┘│
│          ↓                    ↓              │
│  ┌──────────────────────────────────────────┐│
│  │         Synapsix Integration             ││
│  │    (Dialog, Tasks, Agents)               ││
│  └──────────────────────────────────────────┘│
└──────────────────────────────────────────────┘
```

---

## Testing Strategy

### Local Testing

```bash
# Start Core with WebSocket
cd core/studio_core
MIX_ENV=dev ENABLE_MOBILE=true iex -S mix

# Test with websocat
websocat ws://localhost:4001/mobile/socket
```

### Android Emulator

```bash
cd android
./gradlew installDebug
adb forward tcp:4001 tcp:4001  # Forward port to emulator
```

### Network Testing

- Test over WiFi
- Test over mobile data
- Test with VPN/Tailscale

---

## Notes for Agent

### Android Project Structure

```
android/
├── app/
│   ├── src/main/
│   │   ├── java/  (or kotlin/)
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
└── settings.gradle
```

### Key Dependencies

```kotlin
// Android dependencies
implementation("com.squareup.okhttp3:okhttp:4.x")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.x")
implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.x")
```

### Synapsix Integration

The CoreClient in Synapsix handles the actual connection:
- `lib/synapsix/core_client.ex`
- Mobile WebSocket server should mirror this protocol

### Security Considerations

- Tokens should expire
- Use TLS in production
- Don't trust mobile input
- Rate limit connections

---

**Last Updated**: 2026-02-21 (Updated to reflect functional Android app after multi-agent review)
