# Migration Assessment: nixos-cursor → continuum-studio

## Overview

This document assesses code in the `nixos-cursor` repository for migration to `continuum-studio`.

**Goal**: Slim down `nixos-cursor` to focus on NixOS integration, move application code to `continuum-studio`.

---

## Repository Structure Assessment

### nixos-cursor Current Structure

```
nixos-cursor/
├── cursor-studio-egui/          # 30k lines - Full desktop app (MIGRATE)
├── tools/
│   ├── cursor-dialog-daemon/    # Working - Dialog system (MIGRATE)
│   ├── cursor-agent-tui/        # Builds with protoc - TUI agent (EVALUATE)
│   ├── cursor-tui/              # Older TUI (ARCHIVE)
│   └── proxy-test/              # Development only (KEEP)
├── modules/                     # NixOS/Home-manager modules (KEEP)
├── scripts/                     # Utility scripts (SPLIT)
├── security/                    # Security tools (KEEP)
└── examples/                    # NixOS examples (KEEP)
```

---

## Migration Decisions

### ✅ MIGRATE to continuum-studio

#### 1. cursor-studio-egui (Priority: HIGH)

**Status**: Builds successfully (256 warnings, no errors)
**Size**: ~30,000 lines of Rust
**Dependencies**: egui 0.29, tokio, libp2p (optional), axum (optional)

**Features**:
- Version management (download, install, verify)
- Chat library viewer
- D2 diagram rendering and editing
- Security scanning
- Workspace tracking
- Vector search for context
- Sync (P2P, server modes)
- Theme system

**Migration Notes**:
- Core application that should become the desktop UI
- Needs refactoring for new widget architecture
- Theme system is reusable
- D2 rendering code is valuable

#### 2. cursor-dialog-daemon (Priority: HIGH)

**Status**: Working, actively developed
**Size**: ~120,000 lines (including dependencies)
**Location**: `tools/cursor-dialog-daemon/`

**Features**:
- D-Bus interface for AI dialogs
- CLI tool for integration
- Web server for mobile
- Rich dialog types
- Queue management

**Migration Notes**:
- Already functional and tested
- Will become a core service in continuum-studio
- Keep as separate daemon, integrate via IPC

#### 3. Theme/UI Components

**Files**:
- `cursor-studio-egui/src/theme.rs`
- `cursor-studio-egui/src/theme_loader.rs`

**Notes**:
- Valuable theming infrastructure
- Should be shared across all continuum-studio components

---

### 🔍 EVALUATE

#### 1. cursor-agent-tui

**Status**: Requires protoc to build (proto-based)
**Size**: ~15,000 lines
**Location**: `tools/cursor-agent-tui/`

**Features**:
- TUI interface for AI agents
- Protocol buffer based communication
- Authentication handling
- Tool execution

**Questions**:
- Is this actively used?
- Does the proto-based approach conflict with our harness design?
- Could be useful for terminal-based orchestration

**Recommendation**: Keep in nixos-cursor for now, revisit when harness system matures

---

### 🗄️ ARCHIVE (Don't migrate, keep for reference)

#### 1. cursor-tui (older)

**Location**: `tools/cursor-tui/`
**Status**: Superseded by cursor-agent-tui
**Action**: Archive in nixos-cursor

#### 2. proxy-test

**Location**: `tools/proxy-test/`
**Status**: Development/debugging tool
**Action**: Keep in nixos-cursor (development infrastructure)

---

### ✅ KEEP in nixos-cursor

#### 1. NixOS Modules

**Location**: `modules/`
**Contents**: 
- `modules/nixos/` - NixOS system modules
- `modules/home-manager/` - Home-manager modules
- `modules/cursor-protection/` - Protection/sandboxing

**Reason**: These are NixOS-specific and should stay with the Nix flake

#### 2. Security Tools

**Location**: `security/`
**Contents**: Blocklists, lockfiles, tests

**Reason**: NixOS security infrastructure

#### 3. Examples

**Location**: `examples/`
**Contents**: Flake examples (basic, with-agenix, with-sops, etc.)

**Reason**: Documentation for NixOS users

---

## Migration Plan

### Phase 1: Foundation (Current)

1. ✅ Create continuum-studio repo structure
2. ✅ Create UI paradigm documentation
3. ⏳ Migrate cursor-dialog-daemon (already integrated via synapsix)
4. [ ] Set up shared dependencies (egui, tokio)

### Phase 2: Core Migration

5. [ ] Copy cursor-studio-egui source to continuum-studio
6. [ ] Refactor for new architecture:
   - Extract widget components
   - Add event bus system
   - Integrate with dialog daemon
7. [ ] Update builds and CI

### Phase 3: Integration

8. [ ] Connect synapsix harnesses to continuum-studio
9. [ ] Implement agent stream widget
10. [ ] Add multi-window support

### Phase 4: Cleanup

11. [ ] Archive unused code in nixos-cursor
12. [ ] Update nixos-cursor README
13. [ ] Remove migrated code from nixos-cursor

---

## File-by-File Migration Guide

### cursor-studio-egui → continuum-studio

| Source File | Destination | Action |
|-------------|-------------|--------|
| `main.rs` | Refactor | Split into widget modules |
| `theme.rs` | `src/theme/` | Direct copy |
| `theme_loader.rs` | `src/theme/` | Direct copy |
| `database.rs` | `src/data/` | Review, simplify |
| `workspace.rs` | `src/workspace/` | Direct copy |
| `vector.rs` | `src/ai/vector/` | Direct copy |
| `diagram/` | `src/widgets/diagram/` | Direct copy |
| `sync/` | `src/sync/` | Review architecture |
| `chat/` | `src/widgets/chat/` | Direct copy |
| `modes/` | `src/modes/` | Evaluate relevance |
| `approval.rs` | `src/approval/` | Direct copy |
| `security.rs` | Keep in nixos-cursor | Security-specific |
| `versions.rs` | `src/versions/` | Direct copy |
| `version_registry.rs` | `src/versions/` | Direct copy |

### cursor-dialog-daemon → continuum-studio

| Source File | Destination | Action |
|-------------|-------------|--------|
| `dialog.rs` | `crates/dialog/` | Create crate |
| `gui.rs` | `crates/dialog-ui/` | Create crate |
| `dbus_interface.rs` | `crates/dialog/` | Part of dialog crate |
| `web.rs` | `crates/dialog-server/` | Create crate |
| `cli.rs` | `crates/dialog-cli/` | Create crate |

---

## Dependencies to Consolidate

### Shared Across Components

```toml
# Core
egui = "0.33"
eframe = "0.33"
tokio = { version = "1", features = ["full"] }

# Serialization
serde = { version = "1", features = ["derive"] }
serde_json = "1"

# IPC
zbus = "4"  # D-Bus

# Logging
tracing = "0.1"
tracing-subscriber = "0.3"
```

### Component-Specific

- **Dialog Daemon**: zbus, egui, axum (web)
- **Main App**: egui, libp2p (optional), axum (optional)
- **Synapsix**: Elixir (separate, communicates via IPC)

---

## Risks and Mitigations

### Risk: Breaking nixos-cursor functionality

**Mitigation**: 
- Keep modules/ and examples/ intact
- Maintain backward compatibility for Nix users
- Phase migration gradually

### Risk: Large codebase complexity

**Mitigation**:
- Refactor incrementally
- Create clear module boundaries
- Document as we go

### Risk: Integration issues between components

**Mitigation**:
- Define clear IPC protocols
- Use event bus pattern
- Test integration early

---

## Success Criteria

1. [ ] continuum-studio builds and runs
2. [ ] All migrated features work as before
3. [ ] nixos-cursor still functions for NixOS users
4. [ ] Clear separation of concerns between repos
5. [ ] Documentation updated in both repos

---

*Last updated: 2026-01-28*

