# Mobile Integration Roadmap

**Priority**: Medium
**Complexity**: High
**Dependencies**: Android development, WebSocket, Synapsix CoreClient
**Estimated Agent Sessions**: 3-4 focused sessions

---

## Overview

The Continuum Studio Android app connects to the desktop Synapsix/Studio Core system, enabling mobile dialog responses, task management, and agent monitoring.

### Current State (Updated 2026-03-04)

**Android Project** (`/home/e421/continuum-studio/android/`):
- ✅ Full Gradle project structure (Kotlin-based)
- ✅ `MainActivity.kt` - Main entry point with navigation
- ✅ `DialogWebSocketClient.kt` - WebSocket networking with Cloudflare Access support
- ✅ `DialogViewModel.kt` - Dialog state management with DataStore persistence
- ✅ `WidgetBayViewModel.kt` - Widget state
- ✅ `DialogScreen.kt` - Dialog UI (Compose) with tabs (Dialog, History, Settings)
- ✅ `WidgetContents.kt`, `WidgetBay.kt` - Widget UI
- ✅ `DialogNotificationService.kt` - Background service with notification channels
- ✅ Material You theming (Color.kt, Theme.kt, Type.kt)
- ✅ Data models (`DialogModels.kt`, `WidgetModels.kt`)
- ✅ `NetworkMonitor.kt` - Network connectivity monitoring

**Recent Features (2026-03-04):**
- ✅ **Settings Tab** - Full settings screen with server URL, notifications, about
- ✅ **Cloudflare Access** - Service token authentication (CF-Access-Client-Id/Secret headers)
- ✅ **DataStore Persistence** - Settings persist across app restarts
- ✅ **Pull-to-Refresh** - Refresh dialogs and history by pulling down
- ✅ **Hold Mode** - Toggle hold mode from dialog or settings
- ✅ **System Notifications** - Type-specific icons (info, warning, error, question)
- ✅ **History View** - View past dialog responses with reinvoke
- ✅ **Reconnection Logic** - Exponential backoff with jitter, max attempts handling
- ✅ **Deprecation Cleanup** - Updated to AutoMirrored icons, modern RequestBody API

**Phase Status:**
| Phase | Status |
|-------|--------|
| Phase 1: WebSocket Server | ✅ Dialog daemon serves WebSocket + REST on port 8080 |
| Phase 2: State Sync | ✅ Real-time WebSocket sync with latency tracking |
| Phase 3: Dialog Bridge | ✅ Core implementation done |
| Phase 4: Android App | ✅ Feature-complete app matching web interface |
| Phase 5: Advanced Features | ⚠️ Partial (notifications done, offline/push pending) |

**Synapsix Integration:**
- ✅ `CoreClient` module exists (305 lines)
- ✅ Dialog daemon WebSocket/REST API operational
- ✅ Cloudflare tunnel route (`dialog.datapunk.dev`) with Access protection

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

- [x] WebSocket server running on port 8080 (dialog daemon)
- [x] Mobile can connect and authenticate (Cloudflare Access service tokens)
- [x] Basic messages exchanged (JSON protocol)

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

- [x] Mobile receives initial state (via REST /api/current + WebSocket events)
- [x] Updates push in <500ms (WebSocket real-time, latency tracked)
- [x] State stays synchronized (WebSocket events: new_dialog, dialog_answered, hold_changed)

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

- [x] Dialogs appear on mobile (with full context, Markdown rendering)
- [x] Can respond from mobile (choice, confirmation, text, slider)
- [x] Responses work like desktop (via REST /api/answer)

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

- [x] App builds and runs (Gradle/Kotlin, Material 3)
- [x] Connects to desktop (WebSocket + REST to dialog daemon)
- [x] All core features work (dialogs, history, settings, notifications)

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

**Last Updated**: 2026-03-04 (Updated after Cloudflare Access integration, settings persistence, and deprecation cleanup)
