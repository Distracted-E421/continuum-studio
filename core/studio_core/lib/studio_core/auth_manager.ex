defmodule StudioCore.AuthManager do
  @moduledoc """
  Cursor Authentication Manager

  Manages auth profiles and version auth state for Continuum Studio.
  Enables users to:
  - View auth status across all Cursor versions
  - Extract auth from authenticated versions
  - Apply auth to other versions

  ## Phase 1: Read-only status
  - Read auth status from any version's state.vscdb
  - List auth status across all installed versions

  ## Phase 2: Extract & Apply
  - Create profiles from authenticated versions
  - Apply profiles to unauthenticated versions
  - Profile metadata storage (no tokens persisted by default)

  ## Auth Storage Location

  Each Cursor version stores auth in:
    ~/.cursor-{version}/User/globalStorage/state.vscdb

  SQLite ItemTable keys:
  - cursorAuth/accessToken
  - cursorAuth/refreshToken
  - cursorAuth/cachedEmail
  - cursorAuth/cachedSignUpType
  - cursorAuth/stripeMembershipType
  - cursorAuth/stripeSubscriptionStatus
  """

  use GenServer
  require Logger

  # Auth keys used by Cursor in state.vscdb:
  # - cursorAuth/accessToken
  # - cursorAuth/refreshToken
  # - cursorAuth/cachedEmail
  # - cursorAuth/cachedSignUpType
  # - cursorAuth/stripeMembershipType
  # - cursorAuth/stripeSubscriptionStatus
  #
  # Privacy keys:
  # - cursorai/donotchange/privacyMode
  # - cursorai/donotchange/newPrivacyMode2

  @profiles_dir Path.expand("~/.continuum/auth")
  @profiles_file "profiles.json"

  # ============================================================================
  # Types
  # ============================================================================

  @type auth_status :: %{
          version: String.t(),
          email: String.t() | nil,
          provider: String.t() | nil,
          membership: String.t(),
          subscription_status: String.t() | nil,
          has_token: boolean(),
          status: :authenticated | :not_logged_in | :stale | :unknown,
          privacy_mode: String.t() | nil
        }

  @type auth_profile :: %{
          id: String.t(),
          name: String.t(),
          email: String.t(),
          provider: String.t(),
          membership: String.t(),
          subscription_status: String.t() | nil,
          extracted_from: String.t(),
          extracted_at: String.t(),
          # Tokens are ephemeral - only in memory during extract->apply
          access_token: String.t() | nil,
          refresh_token: String.t() | nil
        }

  # ============================================================================
  # Client API
  # ============================================================================

  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Get auth status for a specific Cursor version.

  Returns {:ok, auth_status} or {:error, reason}
  """
  def version_auth_status(version) when is_binary(version) do
    GenServer.call(__MODULE__, {:version_status, version})
  end

  @doc """
  List auth status for all installed Cursor versions.

  Returns {:ok, [auth_status]}
  """
  def list_version_auth_statuses do
    GenServer.call(__MODULE__, :list_statuses)
  end

  @doc """
  List auth status only for versions that are installed.

  Returns {:ok, [auth_status]}
  """
  def list_installed_auth_statuses do
    GenServer.call(__MODULE__, :list_installed_statuses)
  end

  @doc """
  Extract auth profile from a version.
  Returns the full profile with tokens (in memory only).

  Returns {:ok, auth_profile} or {:error, reason}
  """
  def extract_from_version(version) when is_binary(version) do
    GenServer.call(__MODULE__, {:extract_profile, version})
  end

  @doc """
  Apply auth to a target version from a source version.
  Extracts from source and writes to target in one operation.

  Returns {:ok, %{source: version, target: version}} or {:error, reason}
  """
  def apply_auth(source_version, target_version) when is_binary(source_version) and is_binary(target_version) do
    GenServer.call(__MODULE__, {:apply_auth, source_version, target_version})
  end

  @doc """
  List saved profiles (metadata only, no tokens).

  Returns {:ok, [profile_metadata]}
  """
  def list_profiles do
    GenServer.call(__MODULE__, :list_profiles)
  end

  @doc """
  Save a profile to disk (metadata only, tokens are ephemeral).

  Returns {:ok, profile_id} or {:error, reason}
  """
  def save_profile(profile) do
    GenServer.call(__MODULE__, {:save_profile, profile})
  end

  # ============================================================================
  # GenServer Callbacks
  # ============================================================================

  @impl true
  def init(_opts) do
    # Ensure profiles directory exists
    File.mkdir_p!(@profiles_dir)
    Logger.info("AuthManager started")
    {:ok, %{profiles: load_profiles()}}
  end

  @impl true
  def handle_call({:version_status, version}, _from, state) do
    result = read_auth_status(version)
    {:reply, result, state}
  end

  @impl true
  def handle_call(:list_statuses, _from, state) do
    # Get all cursor version directories
    versions = discover_cursor_versions()

    statuses =
      versions
      |> Enum.map(fn version ->
        case read_auth_status(version) do
          {:ok, status} -> status
          {:error, _} -> nil
        end
      end)
      |> Enum.reject(&is_nil/1)
      |> Enum.sort_by(& &1.version, :desc)

    {:reply, {:ok, statuses}, state}
  end

  @impl true
  def handle_call(:list_installed_statuses, _from, state) do
    # Get only versions that have actual AppImages installed
    case StudioCore.VersionRegistry.list_installed() do
      {:ok, installed} ->
        statuses =
          installed
          |> Enum.map(fn %{version: version} ->
            case read_auth_status(version) do
              {:ok, status} -> status
              {:error, _} -> nil
            end
          end)
          |> Enum.reject(&is_nil/1)
          |> Enum.sort_by(& &1.version, :desc)

        {:reply, {:ok, statuses}, state}

      {:error, _} = error ->
        {:reply, error, state}
    end
  end

  @impl true
  def handle_call({:extract_profile, version}, _from, state) do
    result = do_extract_profile(version)
    {:reply, result, state}
  end

  @impl true
  def handle_call({:apply_auth, source_version, target_version}, _from, state) do
    result = do_apply_auth(source_version, target_version)
    {:reply, result, state}
  end

  @impl true
  def handle_call(:list_profiles, _from, state) do
    # Return profiles without tokens (metadata only)
    profiles_metadata = Enum.map(state.profiles, fn p ->
      Map.drop(p, [:access_token, :refresh_token])
    end)
    {:reply, {:ok, profiles_metadata}, state}
  end

  @impl true
  def handle_call({:save_profile, profile}, _from, state) do
    # Save profile metadata (without tokens)
    profile_meta = Map.drop(profile, [:access_token, :refresh_token])
    
    # Update or add profile
    profiles = 
      case Enum.find_index(state.profiles, &(&1.id == profile.id)) do
        nil -> [profile_meta | state.profiles]
        idx -> List.replace_at(state.profiles, idx, profile_meta)
      end
    
    # Persist to disk
    case persist_profiles(profiles) do
      :ok ->
        {:reply, {:ok, profile.id}, %{state | profiles: profiles}}
      {:error, reason} ->
        {:reply, {:error, reason}, state}
    end
  end

  # ============================================================================
  # Private Functions
  # ============================================================================

  defp discover_cursor_versions do
    home = System.get_env("HOME")

    case File.ls(home) do
      {:ok, entries} ->
        entries
        |> Enum.filter(&String.starts_with?(&1, ".cursor-"))
        |> Enum.map(&String.replace_prefix(&1, ".cursor-", ""))
        |> Enum.filter(&version_string?/1)

      {:error, _} ->
        []
    end
  end

  defp version_string?(str) do
    # Match version patterns like "2.4.27", "1.7.54"
    Regex.match?(~r/^\d+\.\d+\.\d+$/, str)
  end

  defp read_auth_status(version) do
    db_path = state_db_path(version)

    cond do
      not File.exists?(db_path) ->
        {:error, :no_state_db}

      true ->
        read_auth_from_db(db_path, version)
    end
  end

  defp state_db_path(version) do
    Path.expand("~/.cursor-#{version}/User/globalStorage/state.vscdb")
  end

  defp read_auth_from_db(db_path, version) do
    # Open the SQLite database
    case Exqlite.Sqlite3.open(db_path) do
      {:ok, conn} ->
        try do
          result = query_auth_data(conn, version)
          Exqlite.Sqlite3.close(conn)
          result
        rescue
          e ->
            Exqlite.Sqlite3.close(conn)
            Logger.error("Failed to read auth from #{version}: #{inspect(e)}")
            {:error, :query_failed}
        end

      {:error, reason} ->
        Logger.warning("Cannot open state.vscdb for #{version}: #{inspect(reason)}")
        {:error, :db_open_failed}
    end
  end

  defp query_auth_data(conn, version) do
    # Build query for all auth and privacy keys using LIKE patterns
    # This avoids complex parameter binding issues
    query = """
    SELECT key, value FROM ItemTable 
    WHERE key LIKE 'cursorAuth/%' OR key LIKE 'cursorai/donotchange/%'
    """

    case Exqlite.Sqlite3.prepare(conn, query) do
      {:ok, stmt} ->
        # Fetch results
        rows = fetch_all_rows(conn, stmt)
        Exqlite.Sqlite3.release(conn, stmt)

        # Parse into auth status map
        auth_map = Map.new(rows, fn [key, value] -> {key, value} end)
        {:ok, parse_auth_map(auth_map, version)}

      {:error, reason} ->
        {:error, {:prepare_failed, reason}}
    end
  end

  defp fetch_all_rows(conn, stmt) do
    fetch_all_rows(conn, stmt, [])
  end

  defp fetch_all_rows(conn, stmt, acc) do
    case Exqlite.Sqlite3.step(conn, stmt) do
      {:row, row} ->
        fetch_all_rows(conn, stmt, [row | acc])

      :done ->
        Enum.reverse(acc)

      {:error, _} ->
        Enum.reverse(acc)
    end
  end

  defp parse_auth_map(auth_map, version) do
    email = Map.get(auth_map, "cursorAuth/cachedEmail")
    provider = Map.get(auth_map, "cursorAuth/cachedSignUpType")
    membership = Map.get(auth_map, "cursorAuth/stripeMembershipType", "free")
    subscription_status = Map.get(auth_map, "cursorAuth/stripeSubscriptionStatus")
    access_token = Map.get(auth_map, "cursorAuth/accessToken")
    privacy_mode_raw = Map.get(auth_map, "cursorai/donotchange/newPrivacyMode2")

    # Determine overall status
    status =
      cond do
        is_nil(email) or email == "" ->
          :not_logged_in

        is_nil(access_token) or access_token == "" ->
          :not_logged_in

        # Could add token expiration check here in the future
        true ->
          :authenticated
      end

    # Parse privacy mode
    privacy_mode =
      case privacy_mode_raw do
        nil ->
          nil

        json_str when is_binary(json_str) ->
          case Jason.decode(json_str) do
            {:ok, %{"privacyMode" => mode}} -> mode
            _ -> nil
          end

        _ ->
          nil
      end

    %{
      version: version,
      email: if(email == "", do: nil, else: email),
      provider: if(provider == "", do: nil, else: provider),
      membership: membership || "free",
      subscription_status: subscription_status,
      has_token: not (is_nil(access_token) or access_token == ""),
      status: status,
      privacy_mode: privacy_mode
    }
  end

  # ============================================================================
  # Phase 2: Extract & Apply
  # ============================================================================

  defp do_extract_profile(version) do
    db_path = state_db_path(version)

    if not File.exists?(db_path) do
      {:error, :version_not_found}
    else
      case Exqlite.Sqlite3.open(db_path) do
        {:ok, conn} ->
          try do
            result = extract_full_auth(conn, version)
            Exqlite.Sqlite3.close(conn)
            result
          rescue
            e ->
              Exqlite.Sqlite3.close(conn)
              Logger.error("Failed to extract auth from #{version}: #{inspect(e)}")
              {:error, :extraction_failed}
          end

        {:error, reason} ->
          Logger.warning("Cannot open state.vscdb for #{version}: #{inspect(reason)}")
          {:error, :db_open_failed}
      end
    end
  end

  defp extract_full_auth(conn, version) do
    query = """
    SELECT key, value FROM ItemTable 
    WHERE key LIKE 'cursorAuth/%'
    """

    case Exqlite.Sqlite3.prepare(conn, query) do
      {:ok, stmt} ->
        rows = fetch_all_rows(conn, stmt)
        Exqlite.Sqlite3.release(conn, stmt)

        auth_map = Map.new(rows, fn [key, value] -> {key, value} end)
        
        email = Map.get(auth_map, "cursorAuth/cachedEmail")
        access_token = Map.get(auth_map, "cursorAuth/accessToken")
        
        if is_nil(email) or email == "" or is_nil(access_token) or access_token == "" do
          {:error, :not_authenticated}
        else
          profile = %{
            id: generate_profile_id(),
            name: "Profile from #{version}",
            email: email,
            provider: Map.get(auth_map, "cursorAuth/cachedSignUpType", "unknown"),
            membership: Map.get(auth_map, "cursorAuth/stripeMembershipType", "free"),
            subscription_status: Map.get(auth_map, "cursorAuth/stripeSubscriptionStatus"),
            extracted_from: version,
            extracted_at: DateTime.utc_now() |> DateTime.to_iso8601(),
            access_token: access_token,
            refresh_token: Map.get(auth_map, "cursorAuth/refreshToken")
          }
          {:ok, profile}
        end

      {:error, reason} ->
        {:error, {:prepare_failed, reason}}
    end
  end

  defp do_apply_auth(source_version, target_version) do
    # First extract from source
    case do_extract_profile(source_version) do
      {:ok, profile} ->
        # Then apply to target
        case write_auth_to_version(profile, target_version) do
          :ok ->
            Logger.info("Applied auth from #{source_version} to #{target_version}")
            {:ok, %{source: source_version, target: target_version, email: profile.email}}
          {:error, reason} ->
            {:error, {:apply_failed, reason}}
        end
      {:error, reason} ->
        {:error, {:extract_failed, reason}}
    end
  end

  defp write_auth_to_version(profile, version) do
    db_path = state_db_path(version)
    
    # Ensure the globalStorage directory exists
    global_storage = Path.dirname(db_path)
    
    unless File.exists?(global_storage) do
      File.mkdir_p!(global_storage)
    end

    case Exqlite.Sqlite3.open(db_path) do
      {:ok, conn} ->
        try do
          # Ensure ItemTable exists
          ensure_item_table(conn)
          
          # Write auth keys
          auth_pairs = [
            {"cursorAuth/accessToken", profile.access_token},
            {"cursorAuth/refreshToken", profile.refresh_token},
            {"cursorAuth/cachedEmail", profile.email},
            {"cursorAuth/cachedSignUpType", profile.provider},
            {"cursorAuth/stripeMembershipType", profile.membership},
            {"cursorAuth/stripeSubscriptionStatus", profile.subscription_status}
          ]
          
          for {key, value} <- auth_pairs, not is_nil(value) do
            upsert_key(conn, key, value)
          end
          
          Exqlite.Sqlite3.close(conn)
          :ok
        rescue
          e ->
            Exqlite.Sqlite3.close(conn)
            Logger.error("Failed to write auth to #{version}: #{inspect(e)}")
            {:error, :write_failed}
        end

      {:error, reason} ->
        Logger.warning("Cannot open state.vscdb for #{version}: #{inspect(reason)}")
        {:error, :db_open_failed}
    end
  end

  defp ensure_item_table(conn) do
    query = """
    CREATE TABLE IF NOT EXISTS ItemTable (
      key TEXT UNIQUE ON CONFLICT REPLACE,
      value BLOB
    )
    """
    case Exqlite.Sqlite3.prepare(conn, query) do
      {:ok, stmt} ->
        Exqlite.Sqlite3.step(conn, stmt)
        Exqlite.Sqlite3.release(conn, stmt)
      _ -> :ok
    end
  end

  defp upsert_key(conn, key, value) do
    query = "INSERT OR REPLACE INTO ItemTable (key, value) VALUES (?, ?)"
    case Exqlite.Sqlite3.prepare(conn, query) do
      {:ok, stmt} ->
        :ok = Exqlite.Sqlite3.bind(stmt, [key, value])
        Exqlite.Sqlite3.step(conn, stmt)
        Exqlite.Sqlite3.release(conn, stmt)
      {:error, reason} ->
        Logger.warning("Failed to upsert #{key}: #{inspect(reason)}")
    end
  end

  defp generate_profile_id do
    :crypto.strong_rand_bytes(8) |> Base.encode16(case: :lower)
  end

  # ============================================================================
  # Profile Storage
  # ============================================================================

  defp load_profiles do
    path = Path.join(@profiles_dir, @profiles_file)
    
    case File.read(path) do
      {:ok, content} ->
        case Jason.decode(content) do
          {:ok, %{"profiles" => profiles}} ->
            Enum.map(profiles, &atomize_profile/1)
          _ ->
            Logger.warning("Invalid profiles.json format")
            []
        end
      {:error, :enoent} ->
        []
      {:error, reason} ->
        Logger.warning("Failed to load profiles: #{inspect(reason)}")
        []
    end
  end

  defp persist_profiles(profiles) do
    path = Path.join(@profiles_dir, @profiles_file)
    
    # Convert to JSON-friendly format (string keys, no tokens)
    profiles_data = Enum.map(profiles, fn p ->
      %{
        "id" => p.id,
        "name" => p.name,
        "email" => p.email,
        "provider" => p.provider,
        "membership" => p.membership,
        "subscription_status" => p.subscription_status,
        "extracted_from" => p.extracted_from,
        "extracted_at" => p.extracted_at
      }
    end)
    
    content = Jason.encode!(%{"version" => 1, "profiles" => profiles_data}, pretty: true)
    
    case File.write(path, content) do
      :ok -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  defp atomize_profile(p) when is_map(p) do
    %{
      id: p["id"],
      name: p["name"],
      email: p["email"],
      provider: p["provider"],
      membership: p["membership"],
      subscription_status: p["subscription_status"],
      extracted_from: p["extracted_from"],
      extracted_at: p["extracted_at"],
      access_token: nil,
      refresh_token: nil
    }
  end
end
