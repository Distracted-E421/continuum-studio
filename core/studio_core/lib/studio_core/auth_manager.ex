defmodule StudioCore.AuthManager do
  @moduledoc """
  Cursor Authentication Manager

  Manages auth profiles and version auth state for Continuum Studio.
  Enables users to:
  - View auth status across all Cursor versions
  - Extract auth from authenticated versions
  - Apply auth to other versions

  ## Phase 1 (Current): Read-only status
  - Read auth status from any version's state.vscdb
  - List auth status across all installed versions

  ## Phase 2 (Planned): Extract & Apply
  - Create profiles from authenticated versions
  - Apply profiles to unauthenticated versions

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

  @auth_keys [
    "cursorAuth/accessToken",
    "cursorAuth/refreshToken",
    "cursorAuth/cachedEmail",
    "cursorAuth/cachedSignUpType",
    "cursorAuth/stripeMembershipType",
    "cursorAuth/stripeSubscriptionStatus"
  ]

  @privacy_keys [
    "cursorai/donotchange/privacyMode",
    "cursorai/donotchange/newPrivacyMode2"
  ]

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

  # ============================================================================
  # GenServer Callbacks
  # ============================================================================

  @impl true
  def init(_opts) do
    Logger.info("AuthManager started")
    {:ok, %{}}
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
end
