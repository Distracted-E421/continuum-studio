# ContinuumTablet App Design Specification

**Status:** Design Phase
**Target Device:** TABWEE T90 (Android 16)
**License:** SSPL-1.0

## Overview

ContinuumTablet is a dedicated tablet application for the Continuum Studio ecosystem, providing full orchestration capabilities with two distinct interaction modes optimized for different use cases.

## Interaction Modes

### 1. Info-Dense Mode (Default)

Productivity-focused mode leveraging the tablet's screen size.

**Layout:**
- Two-pane split (resizable)
- Navigation drawer (swipe-in)
- Full feature parity with desktop

**Features:**
- All desktop sections available
- Detailed agent monitoring
- Full dialog inbox with options
- Task queue management

### 2. 5-Foot UI Mode (Accessibility/Car Mode)

Large-element mode for myopic users or hands-free scenarios.

**Visual Design:**
- High contrast colors
- Configurable font size (slider, 32sp-64sp range)
- Large touch targets (minimum 72dp)
- Simplified layouts with essential controls only

**TTS (Text-to-Speech):**
- Auto-read incoming dialogs
- Auto-read important notifications
- Play/Pause/Reset controls
- Toggle to disable auto-read
- Configurable voice and speed

## Mode Switching

**Primary:** Gesture-based (triple-tap or custom gesture)
**Secondary:** Quick Settings panel toggle

The mode switch should be discoverable but not intrusive.

## Navigation Structure

### Navigation Drawer Sections

1. **📬 Dialog Inbox** - Incoming agent questions
2. **🤖 CLI Agents** - Spawn, monitor, respond to agents
3. **🔧 Orchestrator Mode** - Control agent autonomy levels
4. **📊 Activity Feed** - Real-time event stream
5. **🅿️ Parked Agents** - Idle agents waiting for tasks
6. **⏰ Task Queue** - Pending tasks and priorities
7. **⚙️ Settings** - App configuration

### Section-Specific Layouts

Each section should support both modes:

**Info-Dense:**
- Left pane: List/navigation
- Right pane: Detail/actions

**5-Foot Mode:**
- Single focused content area
- Large cards for each item
- Swipe gestures for navigation

## Connectivity

### Primary: Tailscale Direct

- Tablet connects directly to tailnet
- Uses custom DERP server (derp.datapunk.dev)
- Automatic fallback to relay when direct fails

**Tablet Tailscale IP:** `100.67.87.114`
**Obsidian IP:** `100.109.236.61`

### Future: Phone Proxy (Hotspot Scenario)

For dedicated hotspot device:
- Phone runs Tailscale + proxy service
- Tablet connects via local WiFi only
- Reduces battery impact on tablet
- Single Tailscale connection for multiple devices

## Technical Architecture

### Kotlin + Jetpack Compose

```
app/
├── src/main/java/com/continuumstudio/tablet/
│   ├── MainActivity.kt
│   ├── ui/
│   │   ├── theme/
│   │   │   ├── Theme.kt
│   │   │   └── FiveFootTheme.kt
│   │   ├── navigation/
│   │   │   └── AppNavigation.kt
│   │   ├── components/
│   │   │   ├── ModeSwitch.kt
│   │   │   ├── TtsControls.kt
│   │   │   └── DialogCard.kt
│   │   └── screens/
│   │       ├── DialogInboxScreen.kt
│   │       ├── AgentsScreen.kt
│   │       ├── OrchestratorScreen.kt
│   │       ├── ActivityFeedScreen.kt
│   │       ├── ParkedAgentsScreen.kt
│   │       ├── TaskQueueScreen.kt
│   │       └── SettingsScreen.kt
│   ├── data/
│   │   ├── models/
│   │   ├── repository/
│   │   └── network/
│   │       ├── SynapsixClient.kt
│   │       └── WebSocketManager.kt
│   ├── viewmodel/
│   │   └── MainViewModel.kt
│   └── service/
│       └── TtsService.kt
└── build.gradle.kts
```

### API Endpoints (via Synapsix)

| Endpoint | Purpose |
|----------|---------|
| `GET /api/cli-agents` | List agents |
| `POST /api/cli-agents/spawn` | Create agent |
| `GET /api/agent-dialogs` | Pending dialogs |
| `POST /api/agent-dialogs/:id/respond` | Answer dialog |
| `GET /api/tasks` | Task queue |
| `WS /ws/orchestrator` | Real-time events |
| `WS /ws/cli-agents` | Agent updates |

### State Management

- ViewModel + StateFlow for UI state
- DataStore for settings persistence
- Room for offline caching

## Settings

### Display Settings
- Mode preference (Info-Dense, 5-Foot, Auto)
- Font size slider (32sp - 64sp)
- Theme (Light, Dark, System)
- High contrast toggle

### TTS Settings
- Enable/disable auto-read
- Voice selection
- Speech rate
- Pitch

### Connection Settings
- Server URL (Tailscale IP or hostname)
- Auto-reconnect toggle
- Connection timeout
- Notification preferences

### Mode-Specific Settings
- Gesture to switch modes
- Quick settings panel items
- Widget configuration (5-Foot mode)

## Gestures

| Gesture | Action |
|---------|--------|
| Triple-tap | Toggle mode |
| Swipe from left | Open navigation drawer |
| Swipe down (on dialog) | Dismiss/decline |
| Swipe up (on dialog) | Accept/respond |
| Long press | Context menu / TTS read |
| Pinch | Zoom/resize (Info-Dense) |

## Accessibility

- Full TalkBack support
- Configurable touch target sizes
- High contrast mode
- Reduced motion option
- Screen reader announcements for state changes

## Implementation Phases

### Phase 1: Core Structure
- [ ] Project scaffold with Compose
- [ ] Navigation drawer setup
- [ ] Mode switching infrastructure
- [ ] Theme system (normal + 5-foot)

### Phase 2: Connectivity
- [ ] Synapsix API client
- [ ] WebSocket manager
- [ ] Offline caching with Room
- [ ] Connection status indicator

### Phase 3: Screens
- [ ] Dialog Inbox (both modes)
- [ ] CLI Agents management
- [ ] Activity Feed
- [ ] Settings screen

### Phase 4: TTS Integration
- [ ] Android TTS service wrapper
- [ ] Auto-read logic
- [ ] TTS controls UI
- [ ] Voice configuration

### Phase 5: Polish
- [ ] Gesture recognition
- [ ] Animations
- [ ] Error handling
- [ ] Offline mode
- [ ] Testing

## Device Specifications (T90)

- **Brand:** TABWEE (Blackview)
- **Model:** T90
- **Android:** 16 (API 36)
- **Tailscale IP:** 100.67.87.114
- **Status:** Debloated, Tailscale connected

## Related Documents

- [TABLET_SETUP_GUIDE.md](./TABLET_SETUP_GUIDE.md) - Device setup instructions
- [cli-agents-ui.md](./cli-agents-ui.md) - Desktop CLI agents reference
- [PERF-AUDIT-2026-03-17.md](./PERF-AUDIT-2026-03-17.md) - Performance guidelines
