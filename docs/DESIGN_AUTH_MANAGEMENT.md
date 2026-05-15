# Auth Management Design

## Overview

This document describes the design for centralized authentication management in Continuum Studio, allowing users to:
- Transfer auth state between Cursor versions
- Maintain a single login across all versions
- Keep versions isolated when desired
- View and manage auth status across all versions

## Current State Analysis

### Cursor Auth Storage

Each Cursor version stores authentication data **independently** in its own data directory:

```
~/.cursor-{version}/
└── User/
    └── globalStorage/
        ├── state.vscdb          # SQLite database with auth data
        └── storage.json         # General settings (telemetry IDs, theme)
```

### Auth Data Structure

The `state.vscdb` SQLite database contains an `ItemTable` with key-value pairs:

| Key | Description | Example Value |
|-----|-------------|---------------|
| `cursorAuth/accessToken` | JWT access token (~415 bytes) | `eyJhbGc...` |
| `cursorAuth/refreshToken` | JWT refresh token (~415 bytes) | `eyJhbGc...` |
| `cursorAuth/cachedEmail` | User email | `user@example.com` |
| `cursorAuth/cachedSignUpType` | Auth provider | `Github`, `Google`, `Email` |
| `cursorAuth/stripeMembershipType` | Subscription tier | `free`, `pro`, `business` |
| `cursorAuth/stripeSubscriptionStatus` | Subscription status | `active`, `canceled` |

### Privacy & Feature Settings

Additional keys that affect user experience:

| Key | Description |
|-----|-------------|
| `cursorai/donotchange/privacyMode` | Legacy privacy toggle |
| `cursorai/donotchange/newPrivacyMode2` | Current privacy setting (JSON) |
| `cursorai/donotchange/partnerDataShare` | Partner data sharing preference |
| `cursorai/featureConfigCache` | Feature flags from server |
| `cursorai/serverConfig` | Server configuration cache |

### Current Pain Points

1. **Manual Re-authentication**: When launching a new/different version, users must log in again
2. **No Visibility**: No easy way to see which versions are authenticated
3. **Token Staleness**: Old versions may have expired tokens
4. **Privacy Inconsistency**: Privacy settings must be configured per-version

## Proposed Solution

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                    Continuum Studio UI                        │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ Auth Profiles Tab                                        ││
│  │ ┌────────────────┐ ┌────────────────┐ ┌───────────────┐ ││
│  │ │ Primary        │ │ + New Profile  │ │ Import from   │ ││
│  │ │ user@email.com │ │                │ │ Version...    │ ││
│  │ │ Pro ✓ Active   │ │                │ │               │ ││
│  │ └────────────────┘ └────────────────┘ └───────────────┘ ││
│  └──────────────────────────────────────────────────────────┘│
│                                                              │
│  ┌──────────────────────────────────────────────────────────┐│
│  │ Version Auth Status                                      ││
│  │ ┌─────────┬───────────────────┬──────────┬────────────┐ ││
│  │ │ Version │ Email             │ Status   │ Actions    │ ││
│  │ ├─────────┼───────────────────┼──────────┼────────────┤ ││
│  │ │ 2.4.27  │ user@email.com    │ ✓ Active │ [Refresh]  │ ││
│  │ │ 2.4.21  │ user@email.com    │ ✓ Active │ [Refresh]  │ ││
│  │ │ 2.3.10  │ (not logged in)   │ —        │ [Apply]    │ ││
│  │ │ 1.7.54  │ user@email.com    │ ⚠ Stale  │ [Refresh]  │ ││
│  │ └─────────┴───────────────────┴──────────┴────────────┘ ││
│  └──────────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
                              │
                              ▼
              ┌───────────────────────────────┐
              │    StudioCore (Elixir)        │
              │  ┌─────────────────────────┐  │
              │  │ AuthManager GenServer   │  │
              │  │ - list_profiles/0       │  │
              │  │ - create_profile/1      │  │
              │  │ - apply_to_version/2    │  │
              │  │ - extract_from_version/1│  │
              │  │ - version_auth_status/1 │  │
              │  │ - sync_all_versions/1   │  │
              │  └─────────────────────────┘  │
              │              │                │
              │              ▼                │
              │  ┌─────────────────────────┐  │
              │  │ Auth Profile Storage    │  │
              │  │ ~/.continuum/auth/      │  │
              │  │ - profiles.json         │  │
              │  │ - {profile_id}.enc      │  │
              │  └─────────────────────────┘  │
              └───────────────────────────────┘
```

### Core Components

#### 1. Auth Profile Model

```elixir
defmodule StudioCore.Auth.Profile do
  @type t :: %__MODULE__{
    id: String.t(),
    name: String.t(),
    email: String.t(),
    provider: :github | :google | :email | :unknown,
    membership: :free | :pro | :business | :unknown,
    subscription_status: :active | :canceled | :unknown,
    access_token: String.t() | nil,
    refresh_token: String.t() | nil,
    privacy_mode: map(),
    extracted_from: String.t() | nil,   # Source version
    extracted_at: DateTime.t() | nil,
    last_used: DateTime.t() | nil
  }
end
```

#### 2. AuthManager GenServer

```elixir
defmodule StudioCore.AuthManager do
  @moduledoc """
  Manages auth profiles and version auth state.
  
  ## Operations
  
  - Extract auth from a running/installed Cursor version
  - Apply a profile to a Cursor version
  - List all profiles
  - Check auth status across versions
  - Sync auth across multiple versions
  """
  
  use GenServer
  
  # API
  def list_profiles, do: GenServer.call(__MODULE__, :list_profiles)
  def create_profile(params), do: GenServer.call(__MODULE__, {:create_profile, params})
  def extract_from_version(version), do: GenServer.call(__MODULE__, {:extract, version})
  def apply_to_version(profile_id, version), do: GenServer.call(__MODULE__, {:apply, profile_id, version})
  def version_auth_status(version), do: GenServer.call(__MODULE__, {:status, version})
  def list_version_auth_statuses, do: GenServer.call(__MODULE__, :list_statuses)
  def sync_all_versions(profile_id), do: GenServer.call(__MODULE__, {:sync_all, profile_id})
end
```

### Storage Design

#### Profile Storage Location

```
~/.continuum/
└── auth/
    ├── profiles.json      # Profile metadata (no secrets)
    └── tokens/
        ├── {profile_id}.enc   # Encrypted tokens (optional)
        └── keyfile            # Encryption key (user-protected)
```

#### profiles.json Format

```json
{
  "version": 1,
  "profiles": [
    {
      "id": "primary",
      "name": "Primary Account",
      "email": "user@example.com",
      "provider": "github",
      "membership": "pro",
      "subscription_status": "active",
      "extracted_from": "2.4.27",
      "extracted_at": "2026-02-05T12:00:00Z",
      "last_used": "2026-02-05T14:30:00Z"
    }
  ],
  "default_profile": "primary",
  "sync_enabled": true
}
```

### Security Considerations

#### Token Handling

1. **In-Memory Only (Recommended Default)**
   - Tokens are extracted from source version, applied directly to target
   - Never persisted to disk by Continuum Studio
   - Safest approach, but requires re-extraction if source version is removed

2. **Encrypted Storage (Optional)**
   - Tokens encrypted with user-provided passphrase or system keyring
   - Uses AES-256-GCM encryption
   - Key derived from passphrase using Argon2id
   - Enables offline token application

3. **Never Store Plaintext**
   - Tokens should NEVER be written to plaintext files
   - Profile metadata (email, membership) is safe to store

#### Permission Model

- Auth operations require explicit user confirmation via Synapsix Dialog
- Applying auth to a version shows clear warning about what will be modified
- Extracting auth shows confirmation with masked token preview

### IPC Protocol Extensions

New messages for UI ↔ Core communication:

```elixir
# Requests (UI → Core)
%{type: "auth_list_profiles"}
%{type: "auth_extract", version: "2.4.27"}
%{type: "auth_apply", profile_id: "primary", version: "2.3.10"}
%{type: "auth_version_status", version: "2.4.27"}
%{type: "auth_list_statuses"}
%{type: "auth_sync_all", profile_id: "primary"}
%{type: "auth_create_profile", name: "Work Account"}
%{type: "auth_delete_profile", profile_id: "work"}

# Responses (Core → UI)
%{type: "auth_profiles", profiles: [...]}
%{type: "auth_extracted", profile: %{...}, version: "2.4.27"}
%{type: "auth_applied", profile_id: "primary", version: "2.3.10", success: true}
%{type: "auth_status", version: "2.4.27", status: %{...}}
%{type: "auth_statuses", statuses: [...]}
%{type: "auth_sync_complete", results: [...]}
```

### UI Components

#### 1. Auth Panel (New Tab)

A dedicated panel in Continuum Studio for auth management:

```
┌────────────────────────────────────────────────────────────────┐
│  🔑 Authentication                                              │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  Saved Profiles                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ ★ Primary                                    [Default]   │  │
│  │   distracted.e421@gmail.com                              │  │
│  │   Pro • Active • via GitHub                              │  │
│  │   Last used: 2 hours ago                                 │  │
│  │                        [Apply to All] [Delete]           │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  [+ Create from Version...]  [+ Import Profile...]             │
│                                                                │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  Version Auth Status                                           │
│  ┌────────┬────────────────────┬──────────┬────────────────┐  │
│  │Version │ Account            │ Status   │                │  │
│  ├────────┼────────────────────┼──────────┼────────────────┤  │
│  │ 2.4.27 │ user@email.com     │ ✓ Active │ ▼ Actions      │  │
│  │ 2.4.21 │ user@email.com     │ ✓ Active │                │  │
│  │ 2.3.10 │ —                  │ Not set  │ [Apply Profile]│  │
│  │ 2.0.77 │ user@email.com     │ ⚠ Stale  │ [Refresh]      │  │
│  └────────┴────────────────────┴──────────┴────────────────┘  │
│                                                                │
│  ☐ Auto-sync auth to new versions                             │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

#### 2. Version List Integration

Add auth status indicator to main version list:

```
┌────────────────────────────────────────────────────────────────┐
│  Cursor 2.4.27                                   [▶ Launch]    │
│  Latest • Installed                                            │
│  🔑 user@email.com (Pro)                        [Auth: ✓]     │
└────────────────────────────────────────────────────────────────┘
```

#### 3. Quick Auth Actions

When launching an unauthenticated version:

```
┌────────────────────────────────────────────────────────────────┐
│  ⚠️ Cursor 2.3.10 is not logged in                             │
│                                                                │
│  Would you like to apply your saved auth?                      │
│                                                                │
│  [Apply "Primary" Profile]  [Launch Anyway]  [Cancel]          │
└────────────────────────────────────────────────────────────────┘
```

### Implementation Phases

#### Phase 1: Read-Only Status (Foundation)
- [ ] Implement `version_auth_status/1` to read auth from any version
- [ ] Add `list_version_auth_statuses/0` to show all versions' auth state
- [ ] UI: Display auth status in version list
- [ ] UI: Add basic Auth panel with status table

#### Phase 2: Extract & Apply (Core Feature)
- [ ] Implement `extract_from_version/1` to create profile from version
- [ ] Implement `apply_to_version/2` to inject auth into version
- [ ] Profile metadata storage (`profiles.json`)
- [ ] UI: "Create from Version" workflow
- [ ] UI: "Apply Profile" action per version
- [ ] Synapsix Dialog confirmations

#### Phase 3: Sync & Automation
- [ ] Implement `sync_all_versions/1` for bulk apply
- [ ] Auto-sync setting for new version downloads
- [ ] Pre-launch auth check with dialog prompt
- [ ] UI: "Apply to All" action
- [ ] UI: Auto-sync toggle

#### Phase 4: Advanced Features (Optional)
- [ ] Encrypted token storage
- [ ] Token refresh detection and re-auth prompt
- [ ] Multiple account support
- [ ] Privacy settings sync
- [ ] Export/import profiles for machine transfer

### SQLite Operations

#### Reading Auth Status

```elixir
def read_auth_from_version(version) do
  db_path = Path.expand("~/.cursor-#{version}/User/globalStorage/state.vscdb")
  
  with {:ok, conn} <- Exqlite.Sqlite3.open(db_path),
       {:ok, stmt} <- Exqlite.Sqlite3.prepare(conn, """
         SELECT key, value FROM ItemTable 
         WHERE key LIKE 'cursorAuth/%' OR key LIKE 'cursorai/donotchange/%'
       """),
       {:ok, rows} <- Exqlite.Sqlite3.fetch_all(conn, stmt) do
    
    rows
    |> Enum.map(fn [key, value] -> {key, value} end)
    |> Map.new()
    |> parse_auth_data()
  end
end
```

#### Writing Auth to Version

```elixir
def write_auth_to_version(profile, version) do
  db_path = Path.expand("~/.cursor-#{version}/User/globalStorage/state.vscdb")
  
  with {:ok, conn} <- Exqlite.Sqlite3.open(db_path) do
    # Upsert auth keys
    keys = [
      {"cursorAuth/accessToken", profile.access_token},
      {"cursorAuth/refreshToken", profile.refresh_token},
      {"cursorAuth/cachedEmail", profile.email},
      {"cursorAuth/cachedSignUpType", Atom.to_string(profile.provider)},
      {"cursorAuth/stripeMembershipType", Atom.to_string(profile.membership)},
      {"cursorAuth/stripeSubscriptionStatus", Atom.to_string(profile.subscription_status)}
    ]
    
    for {key, value} <- keys, not is_nil(value) do
      Exqlite.Sqlite3.execute(conn, """
        INSERT OR REPLACE INTO ItemTable (key, value) VALUES (?, ?)
      """, [key, value])
    end
    
    :ok
  end
end
```

### Error Handling

| Error | User Message | Recovery |
|-------|--------------|----------|
| Version not installed | "Cannot read auth: Cursor X.X.X is not installed" | Prompt to download |
| No auth in version | "Cursor X.X.X has no saved login" | Suggest logging in manually |
| DB locked | "Cannot access auth: Cursor X.X.X may be running" | Prompt to close Cursor |
| Apply failed | "Failed to apply auth to X.X.X" | Show detailed error, suggest retry |
| Stale token | "Auth for X.X.X may have expired" | Suggest re-extracting or manual login |

### Testing Strategy

1. **Unit Tests**
   - Profile model validation
   - SQLite read/write operations (on test databases)
   - Profile storage serialization

2. **Integration Tests**
   - Full extract → apply workflow
   - IPC message handling
   - Multi-version sync

3. **Manual Testing Checklist**
   - [ ] Extract from authenticated version
   - [ ] Apply to unauthenticated version, launch, verify logged in
   - [ ] Apply to version with different account, verify switch
   - [ ] Sync to all versions
   - [ ] Handle running Cursor (locked DB)
   - [ ] Handle missing/corrupted state.vscdb

---

## Summary

This design enables Continuum Studio to:

1. **Show auth status** across all installed Cursor versions at a glance
2. **Create profiles** by extracting auth from any authenticated version
3. **Apply profiles** to any version, enabling instant login
4. **Sync auth** across all versions with a single action
5. **Auto-apply auth** to newly downloaded versions

The implementation prioritizes safety (confirmation dialogs, no plaintext token storage) while providing a seamless user experience for managing multiple Cursor versions.
