# TaskQueue and Dialog Widgets Design

**Created**: February 7, 2026  
**Status**: Design Draft  
**Related**: [WIDGET_BAY_DESIGN.md](./WIDGET_BAY_DESIGN.md)

## Overview

This document designs two new widgets for the Continuum Studio widget bay:

1. **TaskQueue Widget** - Displays and manages the persistent task queue
2. **Dialog Widget** - Embeds synapsix dialogs as a widget (alternative to floating window)

Both widgets will use the same iced/COSMIC theming as the main Continuum Studio UI.

## Migration Context

### Current State *(Updated Feb 7, 2026)*
- **Dialog Daemon**: egui-based (`synapsix/dialog/src/gui.rs`) - **Migration planned**
- **Continuum Studio**: iced-based (`continuum-studio/ui-iced/`) - **Using shared theme**
- **Theme**: ✅ **UNIFIED** via `synapsix-theme` crate

### Completed Work
- ✅ Created `synapsix-theme` shared crate (`synapsix/crates/synapsix-theme/`)
- ✅ Integrated shared theme into Continuum Studio
- ✅ Created TaskQueue widget skeleton (`ui-iced/src/widgets/task_queue.rs`)
- ✅ Created Dialog Migration Plan (`synapsix/docs/DIALOG_ICED_MIGRATION_PLAN.md`)

### Target State
- Unified iced-based rendering
- ✅ Shared COSMIC theme module (`synapsix-theme`)
- Widgets can be:
  - Standalone windows
  - Embedded in widget bay (tiled)
  - Floating over other widgets

---

## TaskQueue Widget

### Purpose

Display and interact with the Synapsix persistent task queue, allowing users to:
- View pending/in-progress tasks
- Quick-start, complete, or switch tasks
- See agent assignment and reminders

### Data Source

WebSocket: `ws://localhost:4001/ws/tasks`

### Widget Sizes

| Size | Columns | Rows | Content |
|------|---------|------|---------|
| 1x1 | 1 | 1 | Compact: current task + count badge |
| 1x2 | 1 | 2 | Current task + next 3 pending |
| 2x2 | 2 | 2 | Full list with actions |
| 2x3 | 2 | 3 | Full list + stats + quick actions |

### UI Components

#### Compact View (1x1)

```
┌─────────────────────────┐
│ 📋 Tasks        [3 🔴]  │  <- Title + pending count
├─────────────────────────┤
│ ● Fix auth bug          │  <- Current task
│   in_progress · 45m     │  <- Status + elapsed
└─────────────────────────┘
```

#### Standard View (1x2)

```
┌─────────────────────────┐
│ 📋 Task Queue           │
├─────────────────────────┤
│ ▶ Fix auth bug          │  <- Current (in_progress)
│   cursor-agent · 45m    │
├─────────────────────────┤
│   Update docs        🟠 │  <- Next (high priority)
│   Add unit tests     🟡 │
│   Refactor logging   🟢 │
├─────────────────────────┤
│        [+ Add Task]     │
└─────────────────────────┘
```

#### Full View (2x2)

```
┌───────────────────────────────────────────────────┐
│ 📋 Task Queue                        [🔄] [+ Add] │
├───────────────────────────────────────────────────┤
│ ▶ CURRENT                                         │
│ ┌─────────────────────────────────────────────┐   │
│ │ Fix authentication bug                      │   │
│ │ 🟠 high · cursor-agent · 45m elapsed        │   │
│ │ [Complete ✓]  [Switch ↻]  [Pause ⏸]         │   │
│ └─────────────────────────────────────────────┘   │
├───────────────────────────────────────────────────┤
│   PENDING (5)                                     │
│ ┌─────────────────────────────────────────────┐   │
│ │ 🟠 Update API documentation                 │   │
│ │ 🟡 Add unit tests for new endpoints         │   │
│ │ 🟡 Refactor logging module                  │   │
│ │ 🟢 Clean up deprecated functions            │   │
│ │ ⚪ Research new framework                   │   │
│ └─────────────────────────────────────────────┘   │
├───────────────────────────────────────────────────┤
│ 📊 5 pending · 1 in progress · 12 completed today │
└───────────────────────────────────────────────────┘
```

### Interactions

| Action | Trigger | Result |
|--------|---------|--------|
| Click task | Left-click | Select/expand task details |
| Start task | Click [▶] or double-click | Start working on task |
| Complete | Click [✓] | Mark done, show next |
| Quick add | Click [+] or Ctrl+N | Open quick-add input |
| Switch | Click [↻] | Pause current, start selected |
| Refresh | Click [🔄] | Force refresh from server |

### State Management

```rust
/// TaskQueue widget state
pub struct TaskQueueWidget {
    pub id: String,
    pub size: WidgetSize,
    pub tasks: Vec<Task>,
    pub current_task: Option<Task>,
    pub stats: QueueStats,
    pub connection: WebSocketConnection,
    pub quick_add_input: String,
    pub selected_task: Option<String>,
    pub filter: TaskFilter,
}

#[derive(Clone)]
pub struct Task {
    pub id: String,
    pub content: String,
    pub priority: Priority,
    pub status: TaskStatus,
    pub assigned_to: Option<String>,
    pub project: Option<String>,
    pub started_at: Option<DateTime<Utc>>,
    pub elapsed: Option<Duration>,
}

#[derive(Clone)]
pub struct QueueStats {
    pub pending: usize,
    pub in_progress: usize,
    pub completed_today: usize,
}

pub enum TaskFilter {
    All,
    Project(String),
    Priority(Priority),
    Agent(String),
}
```

### Message Types

```rust
#[derive(Debug, Clone)]
pub enum TaskQueueMessage {
    // WebSocket events
    Connected,
    Disconnected,
    TasksReceived(Vec<Task>),
    TaskAdded(Task),
    TaskUpdated(Task),
    TaskCompleted(Task),
    StatsUpdated(QueueStats),
    
    // User actions
    SelectTask(String),
    StartTask(String),
    CompleteTask(String),
    SwitchToTask(String),
    QuickAddChanged(String),
    QuickAddSubmit,
    ToggleFilter(TaskFilter),
    Refresh,
}
```

---

## Dialog Widget

### Purpose

Embed synapsix dialogs as a widget instead of (or alongside) floating windows. Benefits:
- Persistent visibility in widget bay
- Queue management visible at all times
- Consistent theming with other widgets
- Touch-friendly for tablet mode

### Design Philosophy

The Dialog widget acts as an **embedded dialog center**:
- Shows current active dialog (if any)
- Shows queue of pending dialogs
- Allows responding without window switching

### Widget Sizes

| Size | Layout | Content |
|------|--------|---------|
| 1x1 | Compact | Badge with pending count, click to expand |
| 1x2 | Queue | List of pending dialogs, click to respond |
| 2x2 | Full | Active dialog + queue sidebar |
| 2x3 | Extended | Full dialog rendering with context |

### UI Components

#### Compact Badge (1x1)

```
┌─────────────────────────┐
│ 💬 Dialogs      [2 ⏳]  │
├─────────────────────────┤
│   "Fix auth bug?"       │  <- Preview of next
│   Choice · 45s left     │
└─────────────────────────┘
```

#### Queue View (1x2)

```
┌─────────────────────────┐
│ 💬 Dialog Queue    [2]  │
├─────────────────────────┤
│ ● Summary Level?        │  <- Current/active
│   Choice · 30s left     │
│   [Minimal] [Standard]  │  <- Inline options
├─────────────────────────┤
│ ○ Confirm Deploy?       │  <- Next in queue
│   Confirm · waiting     │
│ ○ Enter API Key         │
│   Text · waiting        │
└─────────────────────────┘
```

#### Full View (2x2)

```
┌───────────────────────────────────────────────────┐
│ 💬 Dialog Center                    [Hold ⏸] [×]  │
├───────────────────────────────────────────────────┤
│                                     │ Queue (2)   │
│  Summary Detail Level               │ ─────────── │
│  ──────────────────                 │ ○ Confirm   │
│                                     │   Deploy?   │
│  How detailed should the task       │             │
│  summary be?                        │ ○ Enter API │
│                                     │   Key       │
│  ┌─────────┐ ┌─────────┐            │             │
│  │ Minimal │ │Standard │            │             │
│  │  ○      │ │   ●     │            │             │
│  │ Just    │ │ Changes │            │             │
│  │ changes │ │ + brief │            │             │
│  └─────────┘ └─────────┘            │             │
│  ┌─────────┐                        │             │
│  │ Verbose │                        │             │
│  │   ○     │                        │             │
│  │ Full    │                        │             │
│  │ analysis│                        │             │
│  └─────────┘                        │             │
├───────────────────────────────────────────────────┤
│ [Comment: _______________]          [Submit 23s]  │
└───────────────────────────────────────────────────┘
```

### Dialog Types Rendering

| Type | Widget Rendering |
|------|------------------|
| Choice | Radio buttons or cards |
| Confirm | Yes/No buttons with labels |
| Text | Text input field |
| Slider | Slider with value display |
| Toast | Notification banner (auto-dismiss) |

### State Management

```rust
/// Dialog widget state  
pub struct DialogWidget {
    pub id: String,
    pub size: WidgetSize,
    pub active_dialog: Option<ActiveDialog>,
    pub queue: Vec<QueuedDialog>,
    pub hold_mode: bool,
    pub comment_input: String,
    pub connection: DialogConnection,
}

#[derive(Clone)]
pub struct ActiveDialog {
    pub id: String,
    pub dialog_type: DialogType,
    pub title: String,
    pub prompt: String,
    pub options: Vec<DialogOption>,
    pub timeout_remaining: Duration,
    pub context: Option<DialogContext>,
}

#[derive(Clone)]
pub struct QueuedDialog {
    pub id: String,
    pub title: String,
    pub dialog_type: DialogType,
    pub queued_at: DateTime<Utc>,
}

#[derive(Clone)]
pub enum DialogType {
    Choice { multi: bool },
    Confirm { yes_label: String, no_label: String },
    Text { placeholder: String, validation: Option<String> },
    Slider { min: f64, max: f64, step: f64, default: f64 },
    Toast { level: ToastLevel, duration: Duration },
}
```

### Message Types

```rust
#[derive(Debug, Clone)]
pub enum DialogMessage {
    // Connection events
    Connected,
    Disconnected,
    DialogReceived(ActiveDialog),
    DialogCompleted(String),
    QueueUpdated(Vec<QueuedDialog>),
    
    // User actions
    SelectOption(String),          // For choice dialogs
    ConfirmYes,
    ConfirmNo,
    TextInputChanged(String),
    SliderChanged(f64),
    CommentChanged(String),
    Submit,
    ToggleHold,
    Dismiss,
    SelectFromQueue(String),
    
    // Timer
    Tick,  // Update timeout display
}
```

---

## Shared COSMIC Theme Module

### ✅ IMPLEMENTED: `synapsix-theme` Crate

**Location**: `synapsix/crates/synapsix-theme/`

The unified theme crate provides:

- **Framework-agnostic colors** as raw `[u8; 3]` RGB arrays
- **Conditional compilation** for `iced` and `egui` color conversions
- **Design tokens** (spacing, border radii, typography)
- **Theme presets** (Dark, Light, PopOrange, CoolBlue, Mint, WarmAmber)

### Usage

```toml
# In Cargo.toml
synapsix-theme = { path = "../synapsix/crates/synapsix-theme", features = ["iced"] }
```

```rust
use synapsix_theme::{CosmicPalette, CosmicPreset, DesignTokens};

// Get a preset palette
let palette = CosmicPreset::Dark.palette();

// Raw RGB values (framework-agnostic)
let bg = palette.bg_base;  // [u8; 3]

// iced Color (with "iced" feature)
let iced_bg = palette.bg_base_iced();  // iced_core::Color

// Convert to full iced Theme
let theme = CosmicPreset::Dark.to_iced_theme();
```

### API Reference

```rust
/// COSMIC-inspired color palette (framework-agnostic storage)
pub struct CosmicPalette {
    // Backgrounds
    pub bg_base: Rgb,           // [18, 18, 20] for dark
    pub bg_component: Rgb,      // [28, 28, 32]
    pub bg_elevated: Rgb,       // [38, 38, 44]
    
    // Foreground / Text
    pub fg_primary: Rgb,        // [245, 245, 247]
    pub fg_secondary: Rgb,      // [155, 155, 165]
    pub fg_muted: Rgb,          // [100, 100, 110]
    
    // Accent colors
    pub accent: Rgb,            // [77, 136, 230] COSMIC blue
    pub accent_hover: Rgb,
    pub accent_subtle: Rgb,
    pub on_accent: Rgb,
    
    // Semantic colors
    pub success: Rgb,
    pub success_subtle: Rgb,
    pub warning: Rgb,
    pub warning_subtle: Rgb,
    pub danger: Rgb,
    pub danger_subtle: Rgb,
    
    // Borders
    pub border: Rgb,
    pub border_subtle: Rgb,
    
    // Buttons
    pub button_bg: Rgb,
    pub button_hover: Rgb,
}

/// Design tokens (spacing, radii, typography)
pub struct DesignTokens {
    pub spacing: Spacing,    // xs=4, sm=8, md=12, lg=16, xl=24, xxl=32
    pub radii: Radii,        // sm=6, md=10, lg=14, xl=20
    pub typography: Typography,  // xs=11, sm=12, md=14, lg=16, xl=20, xxl=24
}

/// Theme presets
pub enum CosmicPreset {
    Dark,       // Default COSMIC dark
    Light,      // COSMIC light
    PopOrange,  // Pop!_OS orange accent
    CoolBlue,   // Cool blue accent
    Mint,       // Mint green accent
    WarmAmber,  // Warm amber accent
}
```

### Integration Points

| Project | Integration Status |
|---------|-------------------|
| Continuum Studio (ui-iced) | ✅ Integrated |
| Synapsix Dialog Daemon | ⏳ Pending migration (egui→iced) |

---

## Widget Bay Integration

### Tiling Modes

The widget bay supports multiple layout modes:

#### 1. Grid Mode (Default)

```
┌───────┬───────┬───────┐
│ Tasks │ Agent │Dialog │
│  1x2  │ Stream│ 1x2   │
│       │  1x2  │       │
├───────┴───────┼───────┤
│   Harness     │ Quick │
│   Status 2x1  │Actions│
└───────────────┴───────┘
```

#### 2. Tiling Mode (i3/Sway-style)

```
┌─────────────────────┬───────────────┐
│                     │               │
│    Agent Stream     │    Tasks      │
│       (60%)         │    (40%)      │
│                     │               │
├──────────┬──────────┼───────────────┤
│ Harness  │  Dialog  │   Services    │
│  (30%)   │  (30%)   │    (40%)      │
└──────────┴──────────┴───────────────┘
```

#### 3. Tab Mode

```
┌─────────────────────────────────────┐
│ [Agent] [Tasks] [Dialog] [Services] │
├─────────────────────────────────────┤
│                                     │
│         (Active Tab Content)        │
│                                     │
│                                     │
│                                     │
└─────────────────────────────────────┘
```

### Widget Registration

```rust
/// Register TaskQueue widget type
fn register_task_queue_widget(registry: &mut WidgetRegistry) {
    registry.register(WidgetDefinition {
        type_id: "task_queue",
        name: "Task Queue",
        description: "Manage persistent task queue",
        icon: "📋",
        sizes: vec![
            WidgetSize::new(1, 1),
            WidgetSize::new(1, 2),
            WidgetSize::new(2, 2),
            WidgetSize::new(2, 3),
        ],
        default_size: WidgetSize::new(1, 2),
        data_sources: vec![
            DataSource::WebSocket("ws://localhost:4001/ws/tasks"),
        ],
        factory: |id, size| Box::new(TaskQueueWidget::new(id, size)),
    });
}

/// Register Dialog widget type
fn register_dialog_widget(registry: &mut WidgetRegistry) {
    registry.register(WidgetDefinition {
        type_id: "dialog",
        name: "Dialog Center",
        description: "Interactive dialog queue",
        icon: "💬",
        sizes: vec![
            WidgetSize::new(1, 1),
            WidgetSize::new(1, 2),
            WidgetSize::new(2, 2),
            WidgetSize::new(2, 3),
        ],
        default_size: WidgetSize::new(2, 2),
        data_sources: vec![
            DataSource::DBus("sh.synapsix.Dialog"),
            DataSource::WebSocket("ws://localhost:8080/ws"),
        ],
        factory: |id, size| Box::new(DialogWidget::new(id, size)),
    });
}
```

---

## Implementation Phases

### Phase 1: TaskQueue Widget ✅ COMPLETE

1. ✅ Created `widgets/task_queue.rs` in ui-iced
2. ⏳ WebSocket connection (skeleton in place, needs full impl)
3. ✅ Render compact and standard views
4. ⏳ Interactions (start, complete, quick-add) - skeleton in place

### Phase 2: Theme Unification ✅ COMPLETE

1. ✅ Created `synapsix-theme` crate at `synapsix/crates/synapsix-theme/`
2. ✅ Updated ui-iced to use shared theme
3. ✅ Prepared migration plan for dialog

### Phase 3: Dialog Daemon Migration ⏳ PLANNED

**Full plan available at**: `synapsix/docs/DIALOG_ICED_MIGRATION_PLAN.md`

Sub-phases:
1. Infrastructure: App skeleton, subscriptions
2. Basic Dialogs: Choice, Text, Confirm, Slider
3. Chrome: Toolbar, settings, idle state
4. Toasts: Toast system with sidebar
5. Advanced: Queue, comments, rich context
6. Polish: Animations, accessibility, testing

### Phase 4: Dialog Widget (Pending Phase 3)

1. Port dialog rendering from egui to iced
2. Create DialogWidget with queue management
3. Keep egui version for standalone window mode

### Phase 5: Widget Bay System (Pending Design)

1. Implement grid layout manager
2. Add tiling support (split handles, resize)
3. Widget drag-and-drop
4. Layout persistence

---

## Data Flow

```
                                    ┌─────────────────┐
                                    │  Synapsix Core  │
                                    │  (Elixir/BEAM)  │
                                    └────────┬────────┘
                                             │
                     ┌───────────────────────┼───────────────────────┐
                     │                       │                       │
              ┌──────▼──────┐         ┌──────▼──────┐         ┌──────▼──────┐
              │ HTTP API    │         │ WebSocket   │         │  D-Bus      │
              │ :4001/api/* │         │ :4001/ws/*  │         │ sh.synapsix │
              └──────┬──────┘         └──────┬──────┘         └──────┬──────┘
                     │                       │                       │
                     │                       │                       │
              ┌──────▼───────────────────────▼───────────────────────▼──────┐
              │                    Continuum Studio                          │
              │                    (iced UI layer)                           │
              │  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
              │  │ TaskQueue   │  │ Dialog      │  │ Other Widgets       │  │
              │  │ Widget      │  │ Widget      │  │ (Agent, Harness...) │  │
              │  └─────────────┘  └─────────────┘  └─────────────────────┘  │
              └──────────────────────────────────────────────────────────────┘
```

---

## Next Steps

**Immediate priorities:**

1. ⏳ **Complete TaskQueue WebSocket integration** - Connect widget to live data
2. ⏳ **Start Dialog Migration Phase 1** - Infrastructure setup per migration plan
3. ⏳ **Design Widget Bay layout system** - Grid/tiling architecture

**Documentation:**

- ✅ This design document
- ✅ `synapsix/docs/DIALOG_ICED_MIGRATION_PLAN.md` - Full migration plan
- ✅ `continuum-studio/docs/WIDGET_BAY_DESIGN.md` - Widget bay concept

**Code artifacts:**

- ✅ `synapsix/crates/synapsix-theme/` - Shared theme crate
- ✅ `continuum-studio/ui-iced/src/widgets/task_queue.rs` - TaskQueue widget
- ✅ `continuum-studio/ui-iced/src/theme/cosmic.rs` - Updated to use shared theme
