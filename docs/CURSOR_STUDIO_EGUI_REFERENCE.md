# cursor-studio-egui Migration Reference

> **Archived reference** — Describes modules considered when porting from legacy **egui** `cursor-studio-egui`. The **current** desktop app is **`ui-iced/`** (iced 0.14). Legacy egui sources, if present, live under `ui/`.

**Date**: January 31, 2026  
**Source**: `/home/e421/nixos-cursor/cursor-studio-egui/`  
**Destination (historical)**: `/home/e421/continuum-studio/ui/`

## Transferred Modules

These modules were copied directly (may need integration work):

### 1. D2 Diagram System (`diagram_d2/`)
- **Files**: parser.rs, renderer.rs, graph.rs, theme_mapper.rs, layout.rs, dataflow.rs, syntax.rs
- **Features**:
  - Native D2 diagram parsing and rendering
  - Interactive pan/zoom
  - Theme-aware colors from VS Code themes
  - Multiple layout algorithms (Grid, Dagre, Force-directed)
  - Syntax highlighting and auto-complete
  - Real-time data flow visualization
- **Integration**: Add as `pub mod diagram_d2;` in lib.rs

### 2. Vector Search (`vector.rs`)
- **Features**:
  - In-memory vector store for embeddings
  - Cosine similarity search
  - Text chunking with overlap
  - EMBEDDING_DIM = 384 (compatible with all-MiniLM-L6-v2)
- **Dependencies**: Requires embedding generation (from Synapsix or external)

### 3. VS Code Theme System (`theme/vscodetheme.rs`, `theme/loader.rs`)
- **Features**:
  - Load VS Code themes (.json)
  - Map theme colors to egui styles
  - Runtime theme switching
- **Integration**: Update existing `theme/mod.rs` to include these

## NOT Transferred (Reference Only)

These modules were NOT transferred. Use this as reference for reimplementation:

### 1. Chat Module (`chat/`)
**Purpose**: Parse and sync Cursor IDE chat history

```
chat/
├── crdt.rs          - Conflict-free replicated data types for sync
├── cursor_parser.rs - Parse Cursor's SQLite database
├── models.rs        - Data models (Conversation, Message)
├── surreal.rs       - SurrealDB storage backend
├── p2p.rs           - P2P sync via libp2p
├── server.rs        - Sync server implementation
├── sync_service.rs  - Sync orchestration
└── client.rs        - Sync client
```

**Key Features**:
- Read-only access to Cursor's state.vscdb
- CRDT-based conflict resolution for multi-device sync
- Optional SurrealDB storage for advanced queries
- P2P sync without central server

**Why Not Transferred**: Complex dependencies (SurrealDB, libp2p). Needs redesign for:
- Lighter weight (SQLite-only)
- Better integration with Synapsix harnesses
- Distributed across machines (per user request)

### 2. Sync Module (`sync/`)
**Purpose**: Watch Cursor databases and sync to external storage

```
sync/
├── config.rs        - Sync configuration
├── cursor_db.rs     - Read from Cursor SQLite
├── daemon.rs        - Background sync daemon
├── external_db.rs   - Write to external database
├── models.rs        - Sync data models
├── pipe_client.rs   - Named pipe IPC
├── ui.rs            - Sync status UI widgets
└── watcher.rs       - Filesystem watcher
```

**Key Features**:
- Watch Cursor's databases for changes
- Background daemon for continuous sync
- Named pipe for status/control
- Support for multiple sync destinations

**Why Not Transferred**: Overlaps with Synapsix harness functionality. The harness should be the sync controller.

### 3. AI Workspace Module (`ai_workspace/`)
**Purpose**: Context management for AI interactions

```
ai_workspace/
├── context.rs      - Context file parsing
├── environment.rs  - Environment variable handling
├── hints.rs        - Hint file parsing
├── memory.rs       - Context memory/caching
├── plans.rs        - Plan file management
└── scratchpad.rs   - Scratchpad for temporary content
```

**Key Features**:
- Parse .ai-workspace/ directory
- Manage context injection
- Environment-aware configuration
- Plan tracking and execution

**Why Not Transferred**: Already reimplemented in Synapsix's NeSy orchestrator with formal verification.

### 4. Versions Module (`versions.rs`, `version_registry.rs`)
**Purpose**: Cursor version management

**Key Features**:
- Download and verify Cursor versions
- Version registry with 100+ versions
- Checksum verification
- Isolated installations

**Why Not Transferred**: Already reimplemented in:
- `synapsix/tools/cursor-versions/` (Rust, 3ms startup)
- `continuum-studio/core/studio_core/lib/studio_core/version_registry.ex` (Elixir)

### 5. Other Files

| File | Purpose | Status |
|------|---------|--------|
| `workspace.rs` | Workspace tracking | Reimplemented in Studio Core |
| `database.rs` | SQLite database access | Use Synapsix.Docs storage |
| `security.rs` | NPM security scanning | Moved to synapsix/priv/security |
| `approval.rs` | User approval workflows | Moved to Synapsix.Dialog |

## Integration TODOs

- [ ] Add `pub mod diagram_d2;` to lib.rs
- [ ] Add `pub mod vector;` to lib.rs  
- [ ] Update theme/mod.rs to export vscodetheme and loader
- [ ] Fix any import errors (CursorStudio → ContinuumStudio)
- [ ] Test D2 rendering
- [ ] Test vector search
- [ ] Test theme loading

## Rebuild Priorities

Based on user feedback (Jan 31, 2026):

1. **Chat/Sync System** - Rebuild for speed, reliability, distribution
   - Target: Lighter, faster, can be distributed across machines
   - Approach: Use Synapsix harness as controller
   - Storage: SQLite primary, vector optional

2. **AI Workspace** - Already covered by NeSy orchestrator
   - No rebuild needed
   - Use Synapsix.Constraint DSL instead

3. **Versions** - Already done
   - Use synapsix/tools/cursor-versions or Studio Core
