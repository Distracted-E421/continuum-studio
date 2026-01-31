# Widget Bay Design: Modular Dashboard System

**Created**: January 29, 2026  
**Status**: Design Draft  
**Platforms**: Android, Desktop (Rust/egui)

## Vision

A **Widget Bay** is a customizable dashboard where users can arrange widgets (small functional units) in a flexible grid. This applies to both:

1. **Android App**: Touch-optimized, vertical scrolling, drag-to-reorder
2. **Desktop App**: Tiling/floating, multi-monitor, keyboard shortcuts

## Core Concepts

### Widget Types

| Widget | Purpose | Size Options | Data Source |
|--------|---------|--------------|-------------|
| **Agent Stream** | Live AI conversation | 1x1, 1x2, 2x2 | WebSocket/ETF |
| **Dialog Queue** | Pending dialogs list | 1x1, 1x2 | WebSocket |
| **Harness Status** | List of harnesses | 1x1, 2x1 | Service Registry |
| **Service Discovery** | DNS-SD services | 1x1, 1x2 | HTTP API |
| **Node Health** | BEAM cluster status | 1x1 | RPC |
| **Quick Actions** | Shortcut buttons | 1x1 | Config |
| **Metrics** | CPU/RAM/Network | 1x1 | Local |
| **Terminal** | Shell output | 2x2 | Local |

### Grid System

**Android (Portrait):**

```
┌─────────────────────┐
│    Agent Stream     │  2x2
│                     │
├──────────┬──────────┤
│ Dialog   │ Harness  │  1x1 each
│ Queue    │ Status   │
├──────────┴──────────┤
│  Service Discovery  │  2x1
├─────────────────────┤
│   Quick Actions     │  2x1
└─────────────────────┘
```

**Desktop (Landscape):**

```
┌───────────────────┬─────────────────────┬───────────────┐
│                   │                     │   Node        │
│   Agent Stream    │     Terminal        │   Health      │
│                   │                     ├───────────────┤
│                   │                     │   Metrics     │
├───────────────────┼─────────────────────┼───────────────┤
│   Dialog Queue    │   Harness Status    │ Quick Actions │
└───────────────────┴─────────────────────┴───────────────┘
```

---

## Android Implementation

### Architecture

```kotlin
// Composables
WidgetBay(
    config: BayConfig,
    widgets: List<Widget>,
    onReorder: (from: Int, to: Int) -> Unit,
    onAddWidget: (WidgetType) -> Unit,
    onRemoveWidget: (String) -> Unit
)

@Composable
fun WidgetCard(
    widget: Widget,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(widget.aspectRatio)
    ) {
        when (widget) {
            is AgentStreamWidget -> AgentStreamContent(widget)
            is DialogQueueWidget -> DialogQueueContent(widget)
            is HarnessStatusWidget -> HarnessStatusContent(widget)
            // ...
        }
    }
}
```

### Data Model

```kotlin
@Serializable
data class BayConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val columns: Int = 2,
    val widgets: List<WidgetConfig> = emptyList()
)

@Serializable
data class WidgetConfig(
    val id: String = UUID.randomUUID().toString(),
    val type: WidgetType,
    val span: Int = 1,  // How many columns to span
    val order: Int = 0,
    val settings: Map<String, String> = emptyMap()
)

enum class WidgetType {
    AGENT_STREAM,
    DIALOG_QUEUE,
    HARNESS_STATUS,
    SERVICE_DISCOVERY,
    NODE_HEALTH,
    QUICK_ACTIONS,
    METRICS,
    TERMINAL
}
```

### State Management

```kotlin
class WidgetBayViewModel : ViewModel() {
    private val _config = MutableStateFlow(BayConfig())
    val config: StateFlow<BayConfig> = _config.asStateFlow()
    
    // Widget data streams
    private val _agentMessages = MutableStateFlow<List<AgentMessage>>(emptyList())
    private val _dialogQueue = MutableStateFlow<List<QueuedDialog>>(emptyList())
    private val _harnesses = MutableStateFlow<List<HarnessInfo>>(emptyList())
    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    
    fun loadConfig() {
        // Load from DataStore
    }
    
    fun saveConfig(config: BayConfig) {
        // Persist to DataStore
    }
    
    fun reorderWidgets(from: Int, to: Int) {
        // Update order
    }
    
    fun addWidget(type: WidgetType) {
        // Add to config
    }
    
    fun removeWidget(id: String) {
        // Remove from config
    }
}
```

### UI Components

#### WidgetBayScreen.kt

```kotlin
@Composable
fun WidgetBayScreen(
    viewModel: WidgetBayViewModel = viewModel(),
    onNavigateToDialog: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    var showAddWidget by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard") },
                actions = {
                    IconButton(onClick = { showAddWidget = true }) {
                        Icon(Icons.Default.Add, "Add Widget")
                    }
                    IconButton(onClick = onNavigateToDialog) {
                        Icon(Icons.Default.Chat, "Dialog")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(config.columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(config.widgets.sortedBy { it.order }) { widgetConfig ->
                WidgetCard(
                    config = widgetConfig,
                    viewModel = viewModel,
                    modifier = Modifier.span(widgetConfig.span)
                )
            }
        }
    }
    
    if (showAddWidget) {
        AddWidgetDialog(
            onDismiss = { showAddWidget = false },
            onAdd = { type ->
                viewModel.addWidget(type)
                showAddWidget = false
            }
        )
    }
}
```

#### Individual Widget Composables

```kotlin
@Composable
fun AgentStreamContent(messages: List<AgentMessage>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        reverseLayout = true
    ) {
        items(messages.takeLast(10)) { message ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Icon(
                    if (message.role == "assistant") Icons.Default.SmartToy 
                    else Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun DialogQueueContent(queue: List<QueuedDialog>) {
    if (queue.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No pending dialogs", color = Color.Gray)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
            items(queue) { dialog ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(dialog.title, fontWeight = FontWeight.Bold)
                        Text(dialog.type, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun HarnessStatusContent(harnesses: List<HarnessInfo>) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(harnesses) { harness ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = Color(android.graphics.Color.parseColor(harness.color)),
                            shape = CircleShape
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(harness.name, fontWeight = FontWeight.Medium)
                    Text(
                        harness.type,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Icon(
                    if (harness.status == "running") Icons.Default.CheckCircle
                    else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (harness.status == "running") Color.Green else Color.Red
                )
            }
        }
    }
}

@Composable
fun ServiceDiscoveryContent(services: List<ServiceInfo>) {
    LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
        items(services) { service ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(service.displayName, fontWeight = FontWeight.Medium)
                    Text(
                        "${service.host}:${service.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Chip(
                    label = service.health,
                    color = if (service.health == "healthy") Color.Green else Color.Red
                )
            }
        }
    }
}
```

---

## Desktop Implementation (Rust/egui)

### Architecture

```rust
/// Widget Bay manager for tiled dashboard
pub struct WidgetBay {
    pub id: String,
    pub layout: BayLayout,
    pub widgets: Vec<WidgetInstance>,
    pub drag_state: Option<DragState>,
}

#[derive(Clone)]
pub enum BayLayout {
    /// Fixed grid (columns x rows)
    Grid { columns: usize, rows: usize },
    /// Auto-flowing grid
    Masonry { columns: usize },
    /// Tiling window manager style
    Tiling { root: TilingNode },
}

#[derive(Clone)]
pub struct WidgetInstance {
    pub id: String,
    pub widget_type: WidgetType,
    pub position: GridPosition,
    pub size: GridSize,
    pub state: WidgetState,
}

#[derive(Clone)]
pub struct GridPosition {
    pub col: usize,
    pub row: usize,
}

#[derive(Clone)]
pub struct GridSize {
    pub cols: usize,
    pub rows: usize,
}
```

### Tiling Support

```rust
/// i3/Sway-style tiling
#[derive(Clone)]
pub enum TilingNode {
    /// Leaf node - contains a widget
    Widget(String),
    /// Horizontal split
    HSplit { ratio: f32, left: Box<TilingNode>, right: Box<TilingNode> },
    /// Vertical split
    VSplit { ratio: f32, top: Box<TilingNode>, bottom: Box<TilingNode> },
    /// Tabbed container
    Tabbed { active: usize, children: Vec<TilingNode> },
}

impl WidgetBay {
    pub fn render_tiling(&mut self, ui: &mut Ui, node: &TilingNode, rect: Rect) {
        match node {
            TilingNode::Widget(id) => {
                if let Some(widget) = self.widgets.iter_mut().find(|w| &w.id == id) {
                    widget.render(ui, rect);
                }
            }
            TilingNode::HSplit { ratio, left, right } => {
                let split_x = rect.left() + rect.width() * ratio;
                let left_rect = Rect::from_min_max(rect.min, pos2(split_x - 2.0, rect.max.y));
                let right_rect = Rect::from_min_max(pos2(split_x + 2.0, rect.min.y), rect.max);
                
                self.render_tiling(ui, left, left_rect);
                self.render_tiling(ui, right, right_rect);
                
                // Render resize handle
                self.render_split_handle(ui, split_x, rect.min.y, rect.max.y, true);
            }
            TilingNode::VSplit { ratio, top, bottom } => {
                let split_y = rect.top() + rect.height() * ratio;
                let top_rect = Rect::from_min_max(rect.min, pos2(rect.max.x, split_y - 2.0));
                let bottom_rect = Rect::from_min_max(pos2(rect.min.x, split_y + 2.0), rect.max);
                
                self.render_tiling(ui, top, top_rect);
                self.render_tiling(ui, bottom, bottom_rect);
                
                // Render resize handle
                self.render_split_handle(ui, rect.min.x, split_y, rect.max.x, false);
            }
            TilingNode::Tabbed { active, children } => {
                // Render tab bar
                // Render active child
            }
        }
    }
}
```

### Widget Rendering

```rust
impl WidgetInstance {
    pub fn render(&mut self, ui: &mut Ui, rect: Rect) {
        let response = ui.allocate_rect(rect, Sense::click_and_drag());
        
        // Widget chrome (title bar, resize handles)
        let title_rect = Rect::from_min_size(rect.min, vec2(rect.width(), 24.0));
        ui.allocate_ui_at_rect(title_rect, |ui| {
            ui.horizontal(|ui| {
                ui.label(&self.widget_type.title());
                ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                    if ui.small_button("×").clicked() {
                        // Close widget
                    }
                });
            });
        });
        
        // Widget content
        let content_rect = Rect::from_min_max(
            pos2(rect.min.x, rect.min.y + 24.0),
            rect.max
        );
        
        ui.allocate_ui_at_rect(content_rect, |ui| {
            match self.widget_type {
                WidgetType::AgentStream => self.render_agent_stream(ui),
                WidgetType::DialogQueue => self.render_dialog_queue(ui),
                WidgetType::HarnessStatus => self.render_harness_status(ui),
                WidgetType::ServiceDiscovery => self.render_service_discovery(ui),
                WidgetType::NodeHealth => self.render_node_health(ui),
                WidgetType::Metrics => self.render_metrics(ui),
                // ...
            }
        });
    }
}
```

---

## Data Integration

### HTTP API Endpoints (from Synapsix)

| Endpoint | Widget(s) | Poll Interval |
|----------|-----------|---------------|
| `/api/dns/services` | Service Discovery | 30s |
| `/api/dns/stats` | Node Health | 10s |
| `/health` | Connection status | 5s |

### WebSocket Events

| Event | Widget(s) |
|-------|-----------|
| `NewDialog` | Dialog Queue |
| `DialogCompleted` | Dialog Queue |
| `QueueUpdate` | Dialog Queue |
| `HarnessStatusUpdate` | Harness Status |
| `AgentMessage` | Agent Stream |

### ETF Protocol (Desktop Only)

| Message | Widget(s) |
|---------|-----------|
| `HarnessMetadata` | Harness Status |
| `WindowInfo` | Harness Status |
| `AgentResponse` | Agent Stream |

---

## Configuration Persistence

### Android: DataStore

```kotlin
// proto/bay_config.proto
message BayConfig {
  string id = 1;
  string name = 2;
  int32 columns = 3;
  repeated WidgetConfig widgets = 4;
}
```

### Desktop: JSON Config

```json
{
  "bays": [
    {
      "id": "main",
      "name": "Main Dashboard",
      "layout": {
        "type": "tiling",
        "root": {
          "type": "hsplit",
          "ratio": 0.7,
          "left": { "type": "widget", "id": "agent-stream" },
          "right": {
            "type": "vsplit",
            "ratio": 0.5,
            "top": { "type": "widget", "id": "harness-status" },
            "bottom": { "type": "widget", "id": "service-discovery" }
          }
        }
      }
    }
  ],
  "widgets": [
    { "id": "agent-stream", "type": "agent_stream", "settings": {} },
    { "id": "harness-status", "type": "harness_status", "settings": {} },
    { "id": "service-discovery", "type": "service_discovery", "settings": {} }
  ]
}
```

---

## Implementation Roadmap

### Phase 1: Android Widget Bay

1. Create `WidgetBay` composables
2. Add `HarnessStatusWidget` (uses Service Registry API)
3. Add `ServiceDiscoveryWidget` (DNS services)
4. Add configuration persistence
5. Add drag-to-reorder

### Phase 2: Desktop Tiling

1. Implement `TilingNode` layout
2. Add split handles and resizing
3. Port widget content from Android
4. Add keyboard shortcuts (Mod+h/v for split)
5. Layout persistence

### Phase 3: Cross-Platform Sync

1. Define shared config format
2. Sync layouts via Synapsix
3. Widget position sync across devices
