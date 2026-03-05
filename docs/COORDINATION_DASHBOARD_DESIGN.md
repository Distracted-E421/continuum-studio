# Coordination Dashboard Design

**Status**: Design Phase
**Priority**: Medium
**Dependencies**: CoordinationRouter API (Synapsix), Android Compose UI

---

## Overview

The Coordination Dashboard extends Continuum Studio to provide visibility into multi-agent coordination state. This allows users to monitor agent activity, resolve conflicts, manage locks, and observe task handoffs from their mobile device.

## Architecture

### Data Flow

```
                                          ┌──────────────────────────────┐
                                          │     Synapsix Server          │
                                          │                              │
                                          │  ┌──────────────────────┐   │
┌─────────────────────────┐               │  │  CoordinationRouter  │   │
│   Continuum Studio      │   HTTP/WS     │  │                      │   │
│   Android App           │◄─────────────►│  │  GET /locks          │   │
│                         │               │  │  GET /conflicts      │   │
│  ┌─────────────────┐   │               │  │  GET /handoffs       │   │
│  │ CoordinationVM  │   │               │  │  GET /state          │   │
│  │                 │   │               │  │  POST /locks         │   │
│  │ - locks         │   │               │  │  POST /conflicts/... │   │
│  │ - conflicts     │   │               │  └──────────────────────┘   │
│  │ - handoffs      │   │               │                              │
│  │ - sharedState   │   │               └──────────────────────────────┘
│  └─────────────────┘   │
│          │              │
│          ▼              │
│  ┌─────────────────┐   │
│  │ CoordinationUI  │   │
│  │   (Compose)     │   │
│  └─────────────────┘   │
└─────────────────────────┘
```

### API Integration

The dashboard consumes the CoordinationRouter API exposed on the Synapsix ServiceRegistry server (port 4040):

| Endpoint | Purpose |
|----------|---------|
| `GET /api/coord/` | System overview |
| `GET /api/coord/health` | Service health status |
| `GET /api/coord/locks` | List all active locks |
| `GET /api/coord/locks/agent/:id` | Locks for specific agent |
| `GET /api/coord/conflicts` | Active conflicts |
| `GET /api/coord/conflicts/:agent_id` | Conflicts for specific agent |
| `GET /api/coord/conflicts/stats` | Conflict statistics |
| `GET /api/coord/handoffs` | Pending handoffs |
| `GET /api/coord/handoffs/history` | Handoff history |
| `GET /api/coord/state` | Shared state namespaces |

---

## UI Design

### Navigation

Add a new tab or expandable section to the existing UI:

```
┌────────────────────────────────────────────────────────┐
│  Continuum Studio                                      │
├────────────────────────────────────────────────────────┤
│                                                        │
│  ┌──────────┬──────────┬──────────┬──────────┐       │
│  │  Dialog  │  History │ Coord    │ Settings │       │
│  └──────────┴──────────┴──────────┴──────────┘       │
│                                                        │
│  ┌──────────────────────────────────────────────────┐ │
│  │                                                  │ │
│  │     [Tab content based on selection]            │ │
│  │                                                  │ │
│  └──────────────────────────────────────────────────┘ │
│                                                        │
└────────────────────────────────────────────────────────┘
```

### Coordination Tab Sections

#### 1. Overview Section

Display quick stats:
- Active locks count
- Active conflicts count  
- Pending handoffs count
- System health (lock_manager, conflict_detector, shared_state, handoff)

```kotlin
@Composable
fun CoordinationOverview(stats: CoordinationStats) {
    Card {
        Column {
            Row {
                StatBadge("Locks", stats.lockCount, Icons.Lock)
                StatBadge("Conflicts", stats.conflictCount, Icons.Warning)
                StatBadge("Handoffs", stats.handoffCount, Icons.SwapHoriz)
            }
            HealthIndicator(stats.health)
        }
    }
}
```

#### 2. Active Locks List

Show all held locks with:
- Resource type and ID
- Holder agent(s)
- Lock mode (exclusive/shared)
- Acquired time
- Action: Release lock

```kotlin
@Composable
fun LockItem(lock: Lock, onRelease: () -> Unit) {
    ListItem(
        headlineContent = { Text("${lock.resourceType}: ${lock.resourceId}") },
        supportingContent = { Text("Held by: ${lock.holders.joinToString()}") },
        leadingContent = { 
            Icon(
                if (lock.mode == "exclusive") Icons.LockOutline else Icons.LockOpen,
                contentDescription = lock.mode
            )
        },
        trailingContent = {
            IconButton(onClick = onRelease) {
                Icon(Icons.Delete, "Release")
            }
        }
    )
}
```

#### 3. Conflicts View

Display active conflicts:
- Conflict type (file, resource, workspace, deploy)
- Severity (high, medium, low)
- Involved agents
- Suggested resolution
- Action: Resolve conflict

```kotlin
@Composable
fun ConflictItem(conflict: Conflict, onResolve: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (conflict.severity) {
                "high" -> MaterialTheme.colorScheme.errorContainer
                "medium" -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column {
            Text("${conflict.type} conflict", style = MaterialTheme.typography.titleMedium)
            Text("Agents: ${conflict.agents.joinToString()}")
            Text("Reason: ${conflict.reason}")
            
            conflict.suggestions?.let { suggestions ->
                Text("Suggestions:")
                suggestions.forEach { Text("• $it") }
            }
            
            Button(onClick = onResolve) {
                Text("Resolve")
            }
        }
    }
}
```

#### 4. Handoffs View

Show pending and recent handoffs:
- Task description
- From/To agents
- Status (pending, accepted, rejected, cancelled)
- Context preview
- Actions: Accept/Reject (for pending)

```kotlin
@Composable  
fun HandoffItem(handoff: Handoff, onAccept: () -> Unit, onReject: () -> Unit) {
    Card {
        Column {
            Text(handoff.taskDescription, style = MaterialTheme.typography.titleMedium)
            Text("${handoff.fromAgent} → ${handoff.toAgent}")
            
            StatusChip(handoff.status)
            
            if (handoff.status == "pending") {
                Row {
                    OutlinedButton(onClick = onReject) { Text("Reject") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onAccept) { Text("Accept") }
                }
            }
        }
    }
}
```

#### 5. Shared State Browser

Allow browsing shared state by namespace:
- Expandable namespace/scope/key hierarchy
- Value display (JSON formatted)
- Read-only (or admin mode for edit)

```kotlin
@Composable
fun SharedStateBrowser(namespaces: List<Namespace>) {
    LazyColumn {
        namespaces.forEach { namespace ->
            item {
                ExpandableNamespace(namespace)
            }
        }
    }
}
```

---

## Data Models

### Kotlin Data Classes

```kotlin
// CoordinationModels.kt

@Serializable
data class CoordinationOverview(
    val service: String,
    val version: String,
    val endpoints: Map<String, String>
)

@Serializable
data class HealthStatus(
    val status: String,  // "healthy" | "degraded"
    val services: Map<String, ServiceHealth>
)

@Serializable
data class ServiceHealth(
    val status: String,  // "up" | "down" | "degraded"
    val details: Map<String, String>? = null
)

@Serializable
data class Lock(
    val id: String,
    val resourceType: String,
    val resourceId: String,
    val mode: String,  // "exclusive" | "shared"
    val holders: List<String>,
    val acquiredAt: String,
    val expiresAt: String? = null,
    val metadata: Map<String, String>? = null
)

@Serializable
data class Conflict(
    val id: String,
    val type: String,  // "file" | "resource" | "workspace" | "deploy"
    val severity: String,  // "high" | "medium" | "low"
    val agents: List<String>,
    val reason: String,
    val suggestions: List<String>? = null,
    val detectedAt: String
)

@Serializable
data class Handoff(
    val id: String,
    val fromAgent: String,
    val toAgent: String,
    val status: String,  // "pending" | "accepted" | "rejected" | "cancelled"
    val taskDescription: String,
    val context: Map<String, String>? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

@Serializable
data class SharedStateNamespace(
    val namespace: String,
    val scopes: List<String>
)
```

---

## Implementation Plan

### Phase 1: API Client (1-2 hours)

1. Add coordination endpoints to `DialogWebSocketClient.kt` or create new `CoordinationApiClient.kt`
2. Create data models in `CoordinationModels.kt`
3. Add error handling for API failures

### Phase 2: ViewModel (1-2 hours)

1. Create `CoordinationViewModel.kt`
2. State management for locks, conflicts, handoffs, shared state
3. Periodic refresh (or WebSocket subscription)
4. Actions: release lock, resolve conflict, accept/reject handoff

### Phase 3: UI Components (2-3 hours)

1. Create `CoordinationScreen.kt` with tabbed sections
2. Implement overview, locks, conflicts, handoffs, shared state components
3. Add to main navigation

### Phase 4: Integration & Testing (1 hour)

1. Wire up to main app navigation
2. Test with real Synapsix server
3. Handle edge cases (no connection, empty states)

---

## API Endpoints Detail

### GET /api/coord/

Returns system overview:

```json
{
  "service": "Synapsix Multi-Agent Coordination",
  "version": "1.0.0",
  "endpoints": {
    "locks": "/locks",
    "conflicts": "/conflicts",
    "state": "/state",
    "handoffs": "/handoffs"
  }
}
```

### GET /api/coord/health

Returns health status:

```json
{
  "status": "healthy",
  "services": {
    "lock_manager": "up",
    "conflict_detector": "up",
    "shared_state": "degraded",
    "handoff": "up"
  }
}
```

### GET /api/coord/locks

Returns all locks:

```json
{
  "locks": [
    {
      "id": "lock-uuid",
      "resource_type": "file",
      "resource_id": "/path/to/file.ex",
      "mode": "exclusive",
      "holders": ["agent-1"],
      "acquired_at": "2026-03-05T21:00:00Z",
      "expires_at": null,
      "metadata": {}
    }
  ],
  "count": 1
}
```

### GET /api/coord/conflicts

Returns active conflicts:

```json
{
  "conflicts": [
    {
      "id": "conflict-uuid",
      "type": "file",
      "severity": "high",
      "agents": ["agent-1", "agent-2"],
      "reason": "Concurrent modification of same file",
      "suggestions": ["wait", "coordinate"],
      "detected_at": "2026-03-05T21:00:00Z"
    }
  ],
  "count": 1
}
```

### GET /api/coord/handoffs

Returns pending handoffs:

```json
{
  "handoffs": [
    {
      "id": "handoff-uuid",
      "from_agent": "agent-1",
      "to_agent": "agent-2",
      "status": "pending",
      "task_description": "Complete unit tests",
      "context": {"focus": "test/"},
      "created_at": "2026-03-05T21:00:00Z"
    }
  ],
  "count": 1
}
```

---

## Notes

### Mounting the CoordinationRouter

**Status**: ✅ Complete

The `CoordinationRouter` is mounted in the Synapsix `ServiceRegistry.Router` at `/api/coord/`:

- **Port**: 4040 (ServiceRegistry server)
- **Base path**: `/api/coord/`
- **File**: `lib/synapsix/service_registry/router.ex`

The router uses `forward "/api/coord", to: Synapsix.AgentCoordinator.CoordinationRouter`.

### Authentication

For production use via Cloudflare tunnel, configure Cloudflare Access service tokens.
For local development, the API is accessible without authentication on `http://localhost:4040/api/coord/`.

### Real-time Updates

Consider WebSocket subscription for real-time coordination state updates, similar to the existing dialog WebSocket. A dedicated `/ws/coord` endpoint could be added for live lock/conflict/handoff notifications.

---

**Created**: 2026-03-05
**Related**: 
- `MOBILE_INTEGRATION_ROADMAP.md`
- `synapsix/docs/roadmaps/MULTI_AGENT_COORDINATION_ROADMAP.md`
