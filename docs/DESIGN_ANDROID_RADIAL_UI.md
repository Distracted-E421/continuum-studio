# Android Radial Menu UI Redesign

**Status:** Design Phase  
**Date:** 2026-04-03  
**Target Device:** ZenPhone 10 (small screen, one-handed use)

## Problem Statement

The current Android app UI is difficult to use while driving:

- Text is too small for at-a-glance reading
- Buttons require precise tapping
- Refresh button location is not thumb-friendly
- Quick responses to dialogs are cumbersome

## Design Goals

1. **At-a-glance readability** - Large, high-contrast elements
2. **One-handed operation** - All critical actions reachable by thumb
3. **Quick response** - Respond to dialogs without looking closely
4. **Muscle memory** - Consistent gestures that become automatic

## Core Concept: Radial (Pie) Menus

Inspired by video game weapon wheels (GTA, DOTA chat wheel), radial menus allow:

- Multi-option selection with single gesture
- No precision tapping required
- Works without looking (muscle memory)
- Scalable to 6-8 options easily

### Interaction Pattern

```
Hold FAB → Radial menu appears → Drag toward option → Release to select
```

## Hybrid Architecture (Approved Design)

### 1. Primary FAB (Bottom-Right Thumb Zone)

**Context-Aware Behavior:**

| State | Radial Menu Contents |
|-------|---------------------|
| Normal (connected) | Refresh, Switch Endpoint, Settings, Test All |
| Normal (disconnected) | Reconnect, Switch Endpoint, Settings, View Logs |
| Dialog Pending | Response options (from dialog) |

**Visual:**

- Large FAB (56dp → 72dp for better tap target)
- Subtle pulse animation when dialog pending
- Color indicates connection state (green/red/yellow)

### 2. Status Indicator (Top-Center)

- Large colored circle showing connection state
- Tap expands to mini-dashboard with:
  - Current endpoint name
  - Latency (if available)
  - Last sync time

### 3. Dialog Card (Center Screen)

- **Large, readable text** (18sp minimum body, 24sp titles)
- **High contrast** - Dark background, light text
- **Hold anywhere on card** → Radial menu with response options
- If >6 options: Nested radial (select group → sub-options appear)

### 4. Gesture Shortcuts

| Gesture | Action |
|---------|--------|
| Pull down from top | Refresh connection |
| Long press status indicator | Quick switch endpoint |
| Double-tap FAB | Toggle most recent action |

## Multi-Layer System

Similar to keyboard macro layers, the radial menu supports multiple "layers":

### Layer Switching

- **Context auto-switch**: System switches based on app state
- **Manual override**: Swipe up/down on FAB switches layers

### Proposed Layers

1. **Quick Actions** (default)
   - Refresh
   - Switch Endpoint
   - Settings
   - View Logs
   - Test All Endpoints

2. **Dialog Responses** (when dialog pending)
   - Dynamic based on dialog options
   - "Cancel" always at 6 o'clock position

3. **Advanced/Settings** (swipe up)
   - Clear Cache
   - Force Reconnect
   - Export Logs
   - About

## Visual Design Specifications

### Radial Menu

```
        Refresh
          ↑
   Logs ← ○ → Endpoint
          ↓
       Settings
```

- **Radius:** 120dp from FAB center
- **Segment size:** 60° each (6 options)
- **Dead zone:** 40dp center radius (cancel area)
- **Haptic feedback:** Light vibration on hover, medium on select
- **Animation:** Spring physics for appearance (200ms)

### Colors (Dark Theme)

| Element | Color |
|---------|-------|
| Background | #121212 |
| Card | #1E1E1E |
| Primary text | #FFFFFF |
| Secondary text | #B3B3B3 |
| Accent (connected) | #4CAF50 |
| Accent (disconnected) | #F44336 |
| Accent (connecting) | #FFC107 |
| Radial menu bg | #2D2D2D (80% opacity) |
| Selected segment | Accent color (30% opacity) |

### Typography

| Element | Size | Weight |
|---------|------|--------|
| Dialog title | 24sp | Bold |
| Dialog body | 18sp | Regular |
| Button/menu item | 16sp | Medium |
| Status text | 14sp | Regular |

## Implementation Notes

### Jetpack Compose

```kotlin
// Radial menu composable structure
@Composable
fun RadialMenu(
    isVisible: Boolean,
    options: List<RadialOption>,
    onOptionSelected: (RadialOption) -> Unit,
    onDismiss: () -> Unit
) {
    // Detect drag direction and distance
    // Highlight appropriate segment
    // Haptic feedback on segment change
}

@Composable  
fun ContextAwareFAB(
    connectionState: ConnectionState,
    hasPendingDialog: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    // Show appropriate icon based on state
    // Animate on pending dialog
}
```

### Gesture Detection

- Use `detectDragGestures` for radial selection
- Calculate angle from FAB center: `atan2(dy, dx)`
- Map angle to segment (0-5 for 6 options)
- Minimum drag distance before selection (40dp)

## Accessibility

- All radial options have content descriptions
- TalkBack announces option on hover
- High contrast mode supported
- Minimum touch targets: 48dp

## Future Enhancements

1. **Voice control** - "Hey Synapsix, refresh connection"
2. **Auto-hide** - FAB fades after inactivity, appears on touch
3. **Customizable layouts** - User-defined radial options
4. **Landscape mode** - Dual FABs for two-thumb operation
5. **Tablet layout** - Larger radials, more options per ring

## Migration Plan

1. Phase 1: Implement radial menu component (isolated)
2. Phase 2: Replace action bar with FAB + radial
3. Phase 3: Add context-awareness
4. Phase 4: Dialog card redesign with radial responses
5. Phase 5: Polish, animation refinement, haptics

## References

- Material Design 3 FAB guidelines
- GTA V weapon wheel UX analysis
- DOTA 2 chat wheel implementation
- Android gesture detection documentation
