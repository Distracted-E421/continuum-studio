# Continuum Studio: UI Paradigm Exploration

## 🎯 Vision

Continuum Studio is an **orchestration engine** for multi-machine, multi-agent development workflows. It should:

1. **Not force a single workflow** - Users choose their level of ecosystem integration
2. **Support multi-monitor/multi-window** - Not trapped in a single viewport
3. **Enable real-time collaboration** - Between humans, local models, APIs, and Cursor agents
4. **Be modular** - Components can be rearranged, hidden, or run independently

## 🚫 Anti-Patterns to Avoid

| Pattern | Why It's Bad | Example |
|---------|--------------|---------|
| Single monolithic window | Forces all work into one viewport | VSCode, IntelliJ |
| Fixed layout | Users can't customize to their workflow | Most IDEs |
| Tight coupling | Can't use pieces independently | Electron apps |
| Tab overload | 50+ tabs become unmanageable | Browser-based editors |
| Modal hell | Can't see context while in dialog | Many config tools |

---

## 📐 Paradigm 1: Tiling Widget System

**Inspiration**: i3/Sway window managers, Notion blocks, Dashboard apps

### Concept

The workspace is a **canvas** where widgets can be tiled, stacked, or floated. Each widget is an independent functional unit.

### Widget Types

| Widget | Purpose | Size Options |
|--------|---------|--------------|
| **Agent Stream** | Live AI conversation/activity | 1x1, 1x2, 2x2 |
| **Code Editor** | Standard editor (can embed Monaco, etc.) | Any |
| **Terminal** | Shell access | 1x1, 1x2 |
| **Diagram Canvas** | D2/Mermaid rendering | 2x2, 3x3 |
| **Config Panel** | Settings for any component | 1x1, 1x2 |
| **Data Browser** | SQL/JSON/file explorer | 1x2, 2x2 |
| **Memory/Context** | AI workspace memory view | 1x1, 1x2 |
| **Dialog Queue** | Pending human-in-the-loop items | 1x1 |

### Layout Modes

```
┌─────────────────────────────────────────────────────────────┐
│  [Workspace: Main Dev]  [+]  [Layouts ▼]  [Widgets ▼]       │
├───────────────────────┬─────────────────────────────────────┤
│                       │                                     │
│   Agent Stream        │         Code Editor                 │
│   ────────────────    │         ────────────                │
│   🤖 Claude: Working  │         src/main.rs                 │
│   on feature X...     │         ┌──────────────────────┐    │
│                       │         │ fn main() {          │    │
│   [Context] [Memory]  │         │     // ...           │    │
│                       │         │ }                    │    │
├───────────────────────┼─────────────────────────────────────┤
│  Terminal             │  Diagram Canvas                     │
│  ──────────           │  ───────────────                    │
│  $ cargo build        │  ┌───┐    ┌───┐                     │
│  Compiling...         │  │ A │───▶│ B │                     │
│                       │  └───┘    └───┘                     │
└───────────────────────┴─────────────────────────────────────┘
```

### Pros

- Maximum flexibility
- Users build their own workflow
- Each widget can be developed/tested independently
- Natural multi-monitor support (drag widgets to other monitors)

### Cons

- Complexity for new users
- More state management
- Need good defaults/presets

---

## 📐 Paradigm 2: Tab Bar with Rich Content

**Inspiration**: Notepad++, Firefox containers, Obsidian

### Concept

Standard tab bar interface, but tabs can contain **any content type**, not just editors. Tab groups provide organization.

### Tab Types

| Tab Type | Icon | Content |
|----------|------|---------|
| Editor | 📝 | Code/text editing |
| Agent | 🤖 | AI conversation stream |
| Config | ⚙️ | Settings panel |
| Diagram | 📊 | D2/visual diagrams |
| Data | 🗄️ | Database browser |
| Terminal | 💻 | Shell |
| Dashboard | 📈 | Metrics/status |

### Layout

```
┌─────────────────────────────────────────────────────────────┐
│ [📝 main.rs] [🤖 Agent-1] [⚙️ Settings] [📊 Arch] [+]      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  Tab Content Area                                           │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  (Current tab's full content)                       │   │
│  │                                                     │   │
│  │  For Agent tab:                                     │   │
│  │  ┌─────────────────────────────────────────────┐   │   │
│  │  │ 🤖 Agent-1 (Claude Opus)        [Pause] [⚙️]│   │   │
│  │  ├─────────────────────────────────────────────┤   │   │
│  │  │ Working on: Implementing auth module        │   │   │
│  │  │                                             │   │   │
│  │  │ 📂 Reading: src/auth/mod.rs                 │   │   │
│  │  │ ✏️ Writing: src/auth/jwt.rs                 │   │   │
│  │  │ 🔍 Searching: "token validation"            │   │   │
│  │  │                                             │   │   │
│  │  │ [View Changes] [Approve All] [Reject]       │   │   │
│  │  └─────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│ Status: Connected to 3 agents │ Sync: ✓ │ Memory: 2.1GB    │
└─────────────────────────────────────────────────────────────┘
```

### Split Views

Tabs can be split horizontally or vertically:

```
┌─────────────────────────────────────────────────────────────┐
│ [📝 main.rs] [🤖 Agent-1] [Split ▼]                        │
├────────────────────────────┬────────────────────────────────┤
│  📝 main.rs                │  🤖 Agent-1                    │
│  ─────────────             │  ──────────                    │
│  Code editor               │  Agent activity                │
│                            │                                │
└────────────────────────────┴────────────────────────────────┘
```

### Pros

- Familiar to most users
- Low learning curve
- Tab groups for organization
- Split views for comparison

### Cons

- Still fundamentally single-window
- Tab overflow with many items
- Less flexible than tiles

---

## 📐 Paradigm 3: Hub and Spoke

**Inspiration**: macOS Dock, KDE Activities, tmux

### Concept

A **central hub** (command center) manages and spawns **spoke windows**. Each spoke is a focused tool. The hub provides overview and orchestration.

### Hub (Command Center)

```
┌─────────────────────────────────────────┐
│        CONTINUUM STUDIO HUB             │
│  ═══════════════════════════════════    │
│                                         │
│  ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐       │
│  │ 🤖  │ │ 📝  │ │ 🗄️  │ │ 📊  │       │
│  │Agent│ │Code │ │Data │ │Diag │       │
│  │  3  │ │  5  │ │  1  │ │  2  │       │
│  └─────┘ └─────┘ └─────┘ └─────┘       │
│                                         │
│  Active Agents:                         │
│  ├─ 🤖 Claude-1: Working on auth       │
│  ├─ 🤖 Claude-2: Writing tests         │
│  └─ 🤖 Local-7B: Code review           │
│                                         │
│  Pending Actions: 3                     │
│  ├─ ⚠️ Confirm file deletion            │
│  ├─ ❓ Choose implementation approach   │
│  └─ 📋 Review generated code            │
│                                         │
│  [Quick Launch ▼] [Settings] [Sync]     │
└─────────────────────────────────────────┘
```

### Spoke Windows

Each spoke is an independent window that can:

- Be moved to any monitor
- Be minimized/hidden
- Communicate back to hub

```
┌─── Agent Spoke ──────────────────┐    ┌─── Editor Spoke ───────────────┐
│ 🤖 Claude-1                 [_][X]│    │ 📝 src/auth/mod.rs        [_][X]│
├──────────────────────────────────┤    ├────────────────────────────────┤
│                                  │    │                                │
│ Current: Implementing JWT auth   │    │ pub mod jwt;                   │
│                                  │    │ pub mod session;               │
│ Files touched:                   │    │                                │
│ • src/auth/jwt.rs (new)          │    │ pub fn validate() {            │
│ • src/auth/mod.rs (modified)     │    │     // ...                     │
│                                  │    │ }                              │
│ [Show Diff] [Approve] [Chat]     │    │                                │
└──────────────────────────────────┘    └────────────────────────────────┘
```

### Widget Bank (Alternative)

Instead of separate windows, a "widget bank" window with configurable bays:

```
┌─── Widget Bank ─────────────────────────────────────────────┐
│ Layout: [2x2 ▼]  [Add Widget ▼]  [Save Layout]         [_][X]│
├─────────────────────────────┬───────────────────────────────┤
│                             │                               │
│  Bay 1: Agent Stream        │  Bay 2: Dialog Queue          │
│  ────────────────────       │  ──────────────────           │
│  🤖 Claude working...       │  📋 3 pending items           │
│                             │                               │
├─────────────────────────────┼───────────────────────────────┤
│                             │                               │
│  Bay 3: Terminal            │  Bay 4: Metrics               │
│  ──────────────             │  ────────                     │
│  $ cargo test               │  CPU: 45%  RAM: 2.1GB         │
│                             │  Agents: 3  Sync: ✓           │
└─────────────────────────────┴───────────────────────────────┘
```

### Pros

- Multi-monitor native
- Clean separation of concerns
- Hub provides overview without clutter
- Each spoke can be fullscreen on its own monitor

### Cons

- Window management overhead
- Need good window placement logic
- Hub can become a bottleneck

---

## 📐 Paradigm 4: Compositor/Overlay Layer

**Inspiration**: Game overlays (Steam, Discord), OBS, Electron always-on-top

### Concept

Continuum runs as a **compositor layer** that can render on top of any application. It's not a window, it's a layer.

### Modes

1. **Overlay Mode**: Transparent layer over desktop, shows notifications/status
2. **Focus Mode**: Takes over screen for intensive work
3. **Sidebar Mode**: Slides in from edge when needed
4. **Floating Mode**: Small always-visible control widget

### Overlay Mode

```
╔═══════════════════════════════════════════════════════════════╗
║                    DESKTOP (any app visible)                   ║
║                                                                ║
║  ┌──────────────────┐                                         ║
║  │ 🤖 Agent Active   │  <- Floating status                     ║
║  │ Working: auth.rs │                                         ║
║  └──────────────────┘                                         ║
║                                                                ║
║                              ┌────────────────────────────┐   ║
║                              │ ⚠️ Action Required          │   ║
║                              │ Confirm code changes?      │   ║
║                              │ [Yes] [No] [View]          │   ║
║                              └────────────────────────────┘   ║
║                                                                ║
╚═══════════════════════════════════════════════════════════════╝
```

### Sidebar Mode

```
╔════════════════════════════════════════════╤═══════════════════╗
║                                            │                   ║
║  (Desktop / Other App)                     │  CONTINUUM        ║
║                                            │  ═════════        ║
║                                            │                   ║
║  User working in Cursor IDE                │  Agents: 3        ║
║  or any other app...                       │  ├─ Claude-1      ║
║                                            │  ├─ Claude-2      ║
║                                            │  └─ Local         ║
║                                            │                   ║
║                                            │  Queue: 2         ║
║                                            │  ├─ Confirm       ║
║                                            │  └─ Review        ║
║                                            │                   ║
║                                            │  [Expand]         ║
╚════════════════════════════════════════════╧═══════════════════╝
```

### Technical Requirements

- Wayland: Use layer-shell protocol (like waybar)
- X11: Always-on-top + input passthrough
- Custom compositor integration (wlroots, smithay)
- Or: Run as a lightweight DE itself

### Pros

- True multi-app integration
- Doesn't compete for window space
- Can augment any workflow
- Most flexible for power users

### Cons

- Highest technical complexity
- Platform-specific implementations
- May conflict with existing compositors
- Accessibility concerns

---

## 🔀 Hybrid Approach: Layered Architecture

Rather than picking one paradigm, we can build a **layered system** where users choose their preferred interaction model.

### Architecture Layers

```
┌─────────────────────────────────────────────────────────────────┐
│                     PRESENTATION LAYER                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  Tiling  │  │   Tabs   │  │Hub/Spoke │  │ Overlay  │        │
│  │  Widgets │  │   Mode   │  │   Mode   │  │   Mode   │        │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘        │
│       │             │             │             │               │
│       └─────────────┴──────┬──────┴─────────────┘               │
│                            │                                    │
├────────────────────────────┼────────────────────────────────────┤
│                     WIDGET RUNTIME                              │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Widget Registry │ Layout Engine │ IPC │ State Management │  │
│  └──────────────────────────────────────────────────────────┘  │
│                            │                                    │
├────────────────────────────┼────────────────────────────────────┤
│                     CORE SERVICES                               │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐       │
│  │ Agent  │ │  Sync  │ │ Dialog │ │ Memory │ │ Config │       │
│  │ Mgr    │ │ Engine │ │ Daemon │ │  Store │ │ Loader │       │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘       │
│                            │                                    │
├────────────────────────────┼────────────────────────────────────┤
│                     INTEGRATION LAYER                           │
│  ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐ ┌────────┐       │
│  │ Cursor │ │ Local  │ │  API   │ │ Engram │ │External│       │
│  │  IDE   │ │ Models │ │ (GPT)  │ │(DeepSk)│ │  Tools │       │
│  └────────┘ └────────┘ └────────┘ └────────┘ └────────┘       │
└─────────────────────────────────────────────────────────────────┘
```

### User-Selectable Presentation

```toml
# ~/.config/continuum-studio/config.toml

[presentation]
# Choose: "tiles", "tabs", "hub", "overlay", "hybrid"
mode = "hybrid"

[presentation.hybrid]
# Primary mode when focused
primary = "tiles"
# Secondary mode when backgrounded
background = "overlay"
# Enable hub for orchestration overview
enable_hub = true

[widgets]
# Default widget set
defaults = ["agent-stream", "editor", "terminal", "dialog-queue"]
# Custom widget locations (for multi-monitor)
[widgets.locations]
agent-stream = { monitor = 1, position = "top-right" }
dialog-queue = { monitor = 0, position = "bottom-left" }
```

---

## 🎨 Next Steps: Mockup Creation

To validate these concepts, we should create:

1. **Interactive Mockups** - HTML/CSS prototypes of each paradigm
2. **D2 Diagrams** - Architecture and data flow visualization  
3. **User Flow Maps** - How does a user accomplish common tasks?
4. **Widget Catalog** - Define all widget types and their APIs

### Created Diagrams

- **[architecture-layers.d2](diagrams/architecture-layers.d2)** - Full system architecture from presentation to integration
- **[widget-system.d2](diagrams/widget-system.d2)** - Widget communication via event bus
- **[ui-paradigms.d2](diagrams/ui-paradigms.d2)** - Visual comparison of the four UI paradigms

### Questions to Answer

1. What's the **minimum viable presentation**? (Start simple, add complexity)
2. How do widgets **communicate**? (Events, shared state, IPC)
3. How is **state persisted**? (Layouts, preferences, history)
4. What's the **first user experience**? (Onboarding flow)
5. How do we handle **multi-machine**? (Network discovery, sync)

---

## 📊 Comparison Matrix

| Feature | Tiles | Tabs | Hub/Spoke | Overlay |
|---------|-------|------|-----------|---------|
| Learning curve | Medium | Low | Medium | High |
| Flexibility | High | Medium | High | Very High |
| Multi-monitor | Excellent | Poor | Excellent | Excellent |
| Implementation | Medium | Easy | Medium | Hard |
| Performance | Good | Good | Good | Varies |
| Mobile-friendly | No | Yes | Partial | No |
| Accessibility | Medium | Good | Medium | Poor |

---

## 🗺️ Recommended Path

### Phase 1: Foundation (Weeks 1-4)

- Build widget runtime with simple IPC
- Implement core services (agent mgr, dialog, config)
- Create 3-4 essential widgets

### Phase 2: Tab Mode (Weeks 5-8)

- Easiest presentation layer to build
- Good for initial testing
- Familiar to users

### Phase 3: Tiles (Weeks 9-12)

- Add tiling layout engine
- Widget drag-and-drop
- Layout persistence

### Phase 4: Hub/Spoke (Weeks 13-16)

- Multi-window support
- Hub orchestration view
- Window placement logic

### Phase 5: Overlay (Future)

- Layer-shell integration
- Platform-specific implementations
- Advanced compositor features

---

*Document created: 2026-01-29*
*Status: Design Exploration*
