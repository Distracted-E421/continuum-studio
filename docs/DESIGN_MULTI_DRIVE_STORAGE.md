# Multi-Drive Storage Architecture

**Status**: Design Document (not yet implemented)
**Author**: AI-assisted design
**Date**: 2026-02-05

## Motivation

Users with complex storage setups (RAID arrays, mixed SSD/HDD, encrypted volumes) need Continuum Studio to intelligently manage where Cursor versions, data directories, and build artifacts are stored. Currently everything lives under `~/.cursor-versions/` and `~/.cursor-VERSION/`, which all land on the home directory's filesystem.

## Goals

1. **Transparent storage indirection** -- Cursor versions and data can live on any mounted drive
2. **Policy-driven placement** -- fast storage for active versions, bulk storage for archives
3. **Encryption awareness** -- detect and respect LUKS/dm-crypt volumes
4. **Zero disruption** -- existing installations continue working without migration

## Architecture Overview

```
┌──────────────────────────────────────────────────────┐
│                  Continuum Studio UI                  │
│           (Storage settings, drive picker)            │
└─────────────────────┬────────────────────────────────┘
                      │ IPC
┌─────────────────────▼────────────────────────────────┐
│                  StudioCore (Elixir)                  │
│  ┌─────────────────────────────────────────────────┐ │
│  │            StorageManager (GenServer)            │ │
│  │  - Drive enumeration                            │ │
│  │  - Policy evaluation                            │ │
│  │  - Symlink management                           │ │
│  │  - Space monitoring                             │ │
│  └─────────────────────────────────────────────────┘ │
│  ┌─────────────────────────────────────────────────┐ │
│  │           StorageBackend (Behaviour)             │ │
│  │  - LocalFSBackend (default)                     │ │
│  │  - SymlinkBackend (redirect via symlinks)       │ │
│  │  - Future: NetworkBackend (NFS/CIFS)            │ │
│  └─────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────┘
```

## Key Concepts

### Storage Locations

A **StorageLocation** represents a mounted filesystem where version data can live:

```elixir
%StorageLocation{
  id: "nvme-fast",
  mount_point: "/mnt/fast-ssd",
  filesystem: "ext4",
  total_bytes: 500_000_000_000,
  available_bytes: 350_000_000_000,
  encrypted: false,
  speed_tier: :fast,        # :fast | :standard | :slow
  removable: false,
  label: "NVMe Fast SSD"
}
```

### Storage Policies

A **StoragePolicy** defines where different types of data should be placed:

```elixir
%StoragePolicy{
  active_versions: "nvme-fast",        # Running/recently-used versions
  archived_versions: "hdd-bulk",        # Older versions kept for reference
  build_artifacts: "nvme-fast",         # Build output from build-watcher
  data_directories: "nvme-fast",        # ~/.cursor-VERSION/ equivalent
  extensions_cache: "hdd-bulk",         # Shared extensions (large, slow-changing)
  auth_profiles: "nvme-fast",           # Small, security-sensitive
  max_versions_on_fast: 5,              # Auto-migrate oldest to bulk
  min_free_space_pct: 10,               # Trigger warnings below this
}
```

### Symlink Indirection

The key mechanism is **symlink-based indirection**. The existing paths (`~/.cursor-versions/`, `~/.cursor-VERSION/`) become symlinks:

```
~/.cursor-versions/
  Cursor-2.4.21-x86_64.AppImage -> /mnt/fast-ssd/continuum/versions/Cursor-2.4.21-x86_64.AppImage
  Cursor-2.3.0-x86_64.AppImage  -> /mnt/hdd-bulk/continuum/versions/Cursor-2.3.0-x86_64.AppImage

~/.cursor-2.4.21/ -> /mnt/fast-ssd/continuum/data/2.4.21/
~/.cursor-2.3.0/  -> /mnt/hdd-bulk/continuum/data/2.3.0/
```

This means:
- **No code changes needed** in VersionRegistry for basic operation
- **Cursor itself** doesn't know or care about the indirection
- **StorageManager** handles creating/moving symlinks

## Drive Detection

### Linux

```elixir
def enumerate_drives do
  # Parse /proc/mounts or use `lsblk --json`
  {output, 0} = System.cmd("lsblk", ["--json", "--fs", "--output",
    "NAME,MOUNTPOINT,FSTYPE,SIZE,FSAVAIL,TYPE,ROTA,MODEL"])

  # Detect LUKS
  {luks_output, _} = System.cmd("lsblk", ["--json", "--output",
    "NAME,TYPE"])
  # TYPE=crypt indicates LUKS/dm-crypt

  # Detect RAID
  # Check /proc/mdstat for software RAID
  # Check for LVM via `lvs --json`
end
```

### Speed Tier Classification

```elixir
def classify_speed(%{rotational: false, model: model}) when is_binary(model) do
  cond do
    String.contains?(model, "NVMe") -> :fast
    true -> :standard  # SATA SSD
  end
end
def classify_speed(%{rotational: true}), do: :slow
def classify_speed(_), do: :standard
```

## Data Flow: Version Installation

```
1. User requests install of Cursor 2.5.0
2. StorageManager.allocate(:active_version, "2.5.0")
   a. Check active_versions location has space
   b. If not, check if old version can be migrated to bulk
   c. Return allocation: {path: "/mnt/fast-ssd/continuum/versions/", symlink: "~/.cursor-versions/"}
3. VersionRegistry downloads to allocated path
4. StorageManager creates symlink from ~/.cursor-versions/Cursor-2.5.0-x86_64.AppImage
5. VersionRegistry runs version normally (follows symlink transparently)
```

## Data Flow: Automatic Tiering

```
1. StorageManager periodic check (every hour)
2. For each installed version:
   a. Check last_used timestamp
   b. If on fast tier and last_used > 30 days:
      - Move AppImage and data dir to bulk storage
      - Update symlinks
      - Log migration
3. For each recently-used version on bulk:
   a. If last_used < 7 days:
      - Move back to fast storage
      - Update symlinks
```

## Encryption Considerations

- **LUKS volumes**: Detected via `lsblk` TYPE=crypt. Continuum marks these locations as encrypted.
- **Performance impact**: Encrypted volumes may be slower; factor into speed tier.
- **Locked volumes**: If a storage location becomes unavailable (locked LUKS, unmounted drive), Continuum should:
  1. Show warning in UI
  2. Fall back to default storage
  3. Queue a migration when the volume returns
- **Auth data**: Always prefer encrypted storage for auth profiles if available.

## RAID Awareness

- **mdadm (Linux software RAID)**: Read `/proc/mdstat` for array status
- **LVM**: Use `lvs` for volume information
- **ZFS**: Use `zpool list` and `zfs list`
- **Impact**: RAID doesn't change the symlink strategy, but provides useful info:
  - RAID level affects expected performance
  - Degraded arrays should trigger warnings
  - Available space reporting accounts for RAID overhead

## UI Design

### Settings > Storage

```
┌─ Storage Locations ─────────────────────────────────────┐
│                                                         │
│  NVMe Fast SSD (/mnt/fast-ssd)           [350 GB free] │
│  ├── Active versions: 3 (1.2 GB)                       │
│  ├── Data directories: 3 (4.5 GB)                      │
│  └── Build artifacts: 850 MB                            │
│                                                         │
│  HDD Bulk (/mnt/hdd-bulk)                [1.8 TB free] │
│  ├── Archived versions: 12 (4.8 GB)                    │
│  └── Extensions cache: 2.1 GB                          │
│                                                         │
│  Home (~/)                              [45 GB free]    │
│  └── Auth profiles: 12 KB                              │
│                                                         │
├─ Storage Policy ────────────────────────────────────────┤
│                                                         │
│  Active versions on:    [NVMe Fast SSD  ▾]             │
│  Archived versions on:  [HDD Bulk       ▾]             │
│  Auto-tier after:       [30 days        ▾]             │
│  Min free space alert:  [10%            ▾]             │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: Foundation (Future)
- `StorageManager` GenServer with drive enumeration
- `StorageBackend` behaviour with `LocalFSBackend`
- Symlink creation/management utilities
- Basic UI showing drive info

### Phase 2: Policy Engine (Future)
- Storage policy configuration
- Automatic allocation on install
- Migration commands (move version between drives)
- Space monitoring and warnings

### Phase 3: Auto-Tiering (Future)
- Periodic last-used analysis
- Automatic migration between tiers
- UI showing migration activity
- Undo/rollback for migrations

### Phase 4: Advanced (Future)
- Network storage (NFS/CIFS) backend
- RAID health monitoring
- Encryption-aware placement
- Multi-device sync

## Compatibility

- **Backward compatible**: Default behavior (everything in home dir) requires no changes
- **Opt-in**: Users must explicitly configure additional storage locations
- **Gradual migration**: Existing installations can be migrated one version at a time
- **Rollback**: Removing storage config reverts to flat directory structure

## Open Questions

1. **Cross-filesystem atomicity**: Moving files between filesystems isn't atomic. Need copy-then-delete with crash recovery.
2. **Permissions**: Different mount points may have different permission models (especially network mounts).
3. **Cursor data dir locking**: Can we safely move a data dir while Cursor is running? Probably not -- need to check running status.
4. **Nix store interaction**: Nix-managed versions live in `/nix/store/` and can't be moved. Symlink strategy only applies to directly-managed AppImages.
