defmodule StudioCore.WorkspaceTracker do
  @moduledoc """
  Workspace Tracking System for Continuum Studio.

  Tracks workspaces across Cursor instances:
  - Which versions have opened each workspace
  - When workspaces were last accessed
  - Git statistics (branch, uncommitted changes)
  - Associated conversations (future)

  ## Usage

      # Register/update a workspace
      StudioCore.WorkspaceTracker.register("/home/user/myproject")

      # Record version opened workspace
      StudioCore.WorkspaceTracker.record_version_open(workspace_id, "2.4.21")

      # Get recent workspaces
      StudioCore.WorkspaceTracker.list_recent(10)

      # Get workspace by path
      StudioCore.WorkspaceTracker.get_by_path("/home/user/myproject")
  """

  use GenServer
  require Logger

  @db_file "workspaces.db"
  @db_dir Path.expand("~/.continuum-studio")

  # State
  defstruct [:db, :workspaces]

  # ==========================================================================
  # Types
  # ==========================================================================

  @type workspace :: %{
    id: String.t(),
    path: String.t(),
    name: String.t(),
    description: String.t() | nil,
    created_at: DateTime.t(),
    last_opened_at: DateTime.t(),
    open_count: non_neg_integer(),
    pinned: boolean(),
    tags: [String.t()],
    color: String.t() | nil,
    git_stats: git_stats() | nil,
    versions: [workspace_version()]
  }

  @type workspace_version :: %{
    version: String.t(),
    first_opened: DateTime.t(),
    last_opened: DateTime.t(),
    open_count: non_neg_integer()
  }

  @type git_stats :: %{
    branch: String.t(),
    commit_count: non_neg_integer() | nil,
    uncommitted_changes: non_neg_integer(),
    last_commit: DateTime.t() | nil,
    last_commit_message: String.t() | nil,
    total_files: non_neg_integer() | nil,
    updated_at: DateTime.t()
  }

  # ==========================================================================
  # Client API
  # ==========================================================================

  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Register or update a workspace. Returns the workspace.
  """
  @spec register(String.t()) :: {:ok, workspace()} | {:error, term()}
  def register(path) when is_binary(path) do
    GenServer.call(__MODULE__, {:register, path})
  end

  @doc """
  Record that a version opened a workspace.
  """
  @spec record_version_open(String.t(), String.t()) :: :ok | {:error, term()}
  def record_version_open(workspace_id, version) do
    GenServer.call(__MODULE__, {:record_version_open, workspace_id, version})
  end

  @doc """
  List recent workspaces.
  """
  @spec list_recent(non_neg_integer()) :: {:ok, [workspace()]}
  def list_recent(limit \\ 10) do
    GenServer.call(__MODULE__, {:list_recent, limit})
  end

  @doc """
  Get all workspaces.
  """
  @spec list_all() :: {:ok, [workspace()]}
  def list_all do
    GenServer.call(__MODULE__, :list_all)
  end

  @doc """
  Get workspace by ID.
  """
  @spec get(String.t()) :: {:ok, workspace()} | {:error, :not_found}
  def get(workspace_id) do
    GenServer.call(__MODULE__, {:get, workspace_id})
  end

  @doc """
  Get workspace by path.
  """
  @spec get_by_path(String.t()) :: {:ok, workspace()} | {:error, :not_found}
  def get_by_path(path) do
    GenServer.call(__MODULE__, {:get_by_path, path})
  end

  @doc """
  Toggle pinned status.
  """
  @spec toggle_pinned(String.t()) :: {:ok, boolean()} | {:error, term()}
  def toggle_pinned(workspace_id) do
    GenServer.call(__MODULE__, {:toggle_pinned, workspace_id})
  end

  @doc """
  Set workspace name.
  """
  @spec set_name(String.t(), String.t()) :: :ok | {:error, term()}
  def set_name(workspace_id, name) do
    GenServer.call(__MODULE__, {:set_name, workspace_id, name})
  end

  @doc """
  Refresh git stats for a workspace.
  """
  @spec refresh_git_stats(String.t()) :: {:ok, git_stats() | nil} | {:error, term()}
  def refresh_git_stats(workspace_id) do
    GenServer.call(__MODULE__, {:refresh_git_stats, workspace_id})
  end

  @doc """
  Delete a workspace.
  """
  @spec delete(String.t()) :: :ok | {:error, term()}
  def delete(workspace_id) do
    GenServer.call(__MODULE__, {:delete, workspace_id})
  end

  # ==========================================================================
  # GenServer Callbacks
  # ==========================================================================

  @impl true
  def init(_opts) do
    # Ensure directory exists
    File.mkdir_p!(@db_dir)
    db_path = Path.join(@db_dir, @db_file)

    # Open database
    {:ok, db} = Exqlite.Sqlite3.open(db_path)
    init_schema(db)

    # Load workspaces into memory
    workspaces = load_all_workspaces(db)

    Logger.info("WorkspaceTracker started: #{map_size(workspaces)} workspaces")

    {:ok, %__MODULE__{db: db, workspaces: workspaces}}
  end

  @impl true
  def handle_call({:register, path}, _from, state) do
    canonical = canonicalize_path(path)

    case find_by_path(state.workspaces, canonical) do
      {:ok, existing} ->
        # Update existing workspace
        updated = update_workspace_opened(state.db, existing)
        new_workspaces = Map.put(state.workspaces, updated.id, updated)
        {:reply, {:ok, updated}, %{state | workspaces: new_workspaces}}

      :not_found ->
        # Create new workspace
        workspace = create_workspace(state.db, canonical)
        new_workspaces = Map.put(state.workspaces, workspace.id, workspace)
        {:reply, {:ok, workspace}, %{state | workspaces: new_workspaces}}
    end
  end

  def handle_call({:record_version_open, workspace_id, version}, _from, state) do
    case Map.get(state.workspaces, workspace_id) do
      nil ->
        {:reply, {:error, :not_found}, state}

      workspace ->
        updated = record_version(state.db, workspace, version)
        new_workspaces = Map.put(state.workspaces, workspace_id, updated)
        {:reply, :ok, %{state | workspaces: new_workspaces}}
    end
  end

  def handle_call({:list_recent, limit}, _from, state) do
    recent =
      state.workspaces
      |> Map.values()
      |> Enum.sort_by(fn ws ->
        # Pinned first, then by last_opened_at
        {!ws.pinned, DateTime.to_unix(ws.last_opened_at, :millisecond) * -1}
      end)
      |> Enum.take(limit)

    {:reply, {:ok, recent}, state}
  end

  def handle_call(:list_all, _from, state) do
    all =
      state.workspaces
      |> Map.values()
      |> Enum.sort_by(fn ws ->
        {!ws.pinned, DateTime.to_unix(ws.last_opened_at, :millisecond) * -1}
      end)

    {:reply, {:ok, all}, state}
  end

  def handle_call({:get, workspace_id}, _from, state) do
    case Map.get(state.workspaces, workspace_id) do
      nil -> {:reply, {:error, :not_found}, state}
      workspace -> {:reply, {:ok, workspace}, state}
    end
  end

  def handle_call({:get_by_path, path}, _from, state) do
    canonical = canonicalize_path(path)

    case find_by_path(state.workspaces, canonical) do
      {:ok, workspace} -> {:reply, {:ok, workspace}, state}
      :not_found -> {:reply, {:error, :not_found}, state}
    end
  end

  def handle_call({:toggle_pinned, workspace_id}, _from, state) do
    case Map.get(state.workspaces, workspace_id) do
      nil ->
        {:reply, {:error, :not_found}, state}

      workspace ->
        new_pinned = !workspace.pinned
        update_field(state.db, workspace_id, "pinned", if(new_pinned, do: 1, else: 0))
        updated = %{workspace | pinned: new_pinned}
        new_workspaces = Map.put(state.workspaces, workspace_id, updated)
        {:reply, {:ok, new_pinned}, %{state | workspaces: new_workspaces}}
    end
  end

  def handle_call({:set_name, workspace_id, name}, _from, state) do
    case Map.get(state.workspaces, workspace_id) do
      nil ->
        {:reply, {:error, :not_found}, state}

      workspace ->
        update_field(state.db, workspace_id, "name", name)
        updated = %{workspace | name: name}
        new_workspaces = Map.put(state.workspaces, workspace_id, updated)
        {:reply, :ok, %{state | workspaces: new_workspaces}}
    end
  end

  def handle_call({:refresh_git_stats, workspace_id}, _from, state) do
    case Map.get(state.workspaces, workspace_id) do
      nil ->
        {:reply, {:error, :not_found}, state}

      workspace ->
        git_stats = get_git_stats(workspace.path)
        git_stats_json = if git_stats, do: Jason.encode!(git_stats), else: nil
        update_field(state.db, workspace_id, "git_stats", git_stats_json)
        updated = %{workspace | git_stats: git_stats}
        new_workspaces = Map.put(state.workspaces, workspace_id, updated)
        {:reply, {:ok, git_stats}, %{state | workspaces: new_workspaces}}
    end
  end

  def handle_call({:delete, workspace_id}, _from, state) do
    delete_workspace(state.db, workspace_id)
    new_workspaces = Map.delete(state.workspaces, workspace_id)
    {:reply, :ok, %{state | workspaces: new_workspaces}}
  end

  @impl true
  def terminate(_reason, state) do
    if state.db, do: Exqlite.Sqlite3.close(state.db)
    :ok
  end

  # ==========================================================================
  # Private Functions
  # ==========================================================================

  defp init_schema(db) do
    Exqlite.Sqlite3.execute(db, """
    CREATE TABLE IF NOT EXISTS workspaces (
      id TEXT PRIMARY KEY,
      path TEXT NOT NULL UNIQUE,
      name TEXT NOT NULL,
      description TEXT,
      created_at TEXT NOT NULL,
      last_opened_at TEXT NOT NULL,
      open_count INTEGER DEFAULT 0,
      pinned INTEGER DEFAULT 0,
      tags TEXT DEFAULT '[]',
      color TEXT,
      git_stats TEXT
    )
    """)

    Exqlite.Sqlite3.execute(db, """
    CREATE TABLE IF NOT EXISTS workspace_versions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      workspace_id TEXT NOT NULL,
      version TEXT NOT NULL,
      first_opened TEXT NOT NULL,
      last_opened TEXT NOT NULL,
      open_count INTEGER DEFAULT 0,
      FOREIGN KEY (workspace_id) REFERENCES workspaces(id),
      UNIQUE(workspace_id, version)
    )
    """)

    Exqlite.Sqlite3.execute(db, "CREATE INDEX IF NOT EXISTS idx_ws_path ON workspaces(path)")
    Exqlite.Sqlite3.execute(db, "CREATE INDEX IF NOT EXISTS idx_ws_last_opened ON workspaces(last_opened_at)")
    Exqlite.Sqlite3.execute(db, "CREATE INDEX IF NOT EXISTS idx_wsv_workspace ON workspace_versions(workspace_id)")
  end

  defp load_all_workspaces(db) do
    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, """
    SELECT id, path, name, description, created_at, last_opened_at,
           open_count, pinned, tags, color, git_stats
    FROM workspaces
    ORDER BY last_opened_at DESC
    """)

    {:ok, rows} = Exqlite.Sqlite3.fetch_all(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)

    rows
    |> Enum.map(&row_to_workspace/1)
    |> Enum.reduce(%{}, fn ws, acc ->
      # Load versions for each workspace
      ws_with_versions = %{ws | versions: load_workspace_versions(db, ws.id)}
      Map.put(acc, ws.id, ws_with_versions)
    end)
  end

  defp load_workspace_versions(db, workspace_id) do
    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, """
    SELECT version, first_opened, last_opened, open_count
    FROM workspace_versions
    WHERE workspace_id = ?1
    ORDER BY last_opened DESC
    """)

    :ok = Exqlite.Sqlite3.bind(stmt, [workspace_id])
    {:ok, rows} = Exqlite.Sqlite3.fetch_all(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)

    Enum.map(rows, fn [version, first_opened, last_opened, open_count] ->
      %{
        version: version,
        first_opened: parse_datetime(first_opened),
        last_opened: parse_datetime(last_opened),
        open_count: open_count
      }
    end)
  end

  defp row_to_workspace([id, path, name, description, created_at, last_opened_at, open_count, pinned, tags, color, git_stats]) do
    %{
      id: id,
      path: path,
      name: name,
      description: description,
      created_at: parse_datetime(created_at),
      last_opened_at: parse_datetime(last_opened_at),
      open_count: open_count,
      pinned: pinned == 1,
      tags: Jason.decode!(tags || "[]"),
      color: color,
      git_stats: if(git_stats, do: Jason.decode!(git_stats), else: nil),
      versions: []
    }
  end

  defp create_workspace(db, path) do
    id = generate_ulid()
    name = Path.basename(path)
    now = DateTime.utc_now()
    now_str = DateTime.to_iso8601(now)
    git_stats = get_git_stats(path)
    git_stats_json = if git_stats, do: Jason.encode!(git_stats), else: nil

    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, """
    INSERT INTO workspaces (id, path, name, created_at, last_opened_at, open_count, pinned, tags, git_stats)
    VALUES (?1, ?2, ?3, ?4, ?5, 1, 0, '[]', ?6)
    """)

    :ok = Exqlite.Sqlite3.bind(stmt, [id, path, name, now_str, now_str, git_stats_json])
    :done = Exqlite.Sqlite3.step(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)

    Logger.info("Registered workspace: #{path}")

    %{
      id: id,
      path: path,
      name: name,
      description: nil,
      created_at: now,
      last_opened_at: now,
      open_count: 1,
      pinned: false,
      tags: [],
      color: nil,
      git_stats: git_stats,
      versions: []
    }
  end

  defp update_workspace_opened(db, workspace) do
    now = DateTime.utc_now()
    now_str = DateTime.to_iso8601(now)

    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, """
    UPDATE workspaces SET last_opened_at = ?1, open_count = open_count + 1 WHERE id = ?2
    """)

    :ok = Exqlite.Sqlite3.bind(stmt, [now_str, workspace.id])
    :done = Exqlite.Sqlite3.step(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)

    %{workspace | last_opened_at: now, open_count: workspace.open_count + 1}
  end

  defp record_version(db, workspace, version) do
    now = DateTime.utc_now()
    now_str = DateTime.to_iso8601(now)

    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, """
    INSERT INTO workspace_versions (workspace_id, version, first_opened, last_opened, open_count)
    VALUES (?1, ?2, ?3, ?4, 1)
    ON CONFLICT(workspace_id, version) DO UPDATE SET
      last_opened = excluded.last_opened,
      open_count = open_count + 1
    """)

    :ok = Exqlite.Sqlite3.bind(stmt, [workspace.id, version, now_str, now_str])
    :done = Exqlite.Sqlite3.step(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)

    # Update in-memory version list
    updated_versions =
      case Enum.find_index(workspace.versions, &(&1.version == version)) do
        nil ->
          [%{version: version, first_opened: now, last_opened: now, open_count: 1} | workspace.versions]

        idx ->
          List.update_at(workspace.versions, idx, fn v ->
            %{v | last_opened: now, open_count: v.open_count + 1}
          end)
      end

    %{workspace | versions: updated_versions}
  end

  defp update_field(db, workspace_id, field, value) do
    # Note: field is trusted (internal use only)
    {:ok, stmt} = Exqlite.Sqlite3.prepare(db, "UPDATE workspaces SET #{field} = ?1 WHERE id = ?2")
    :ok = Exqlite.Sqlite3.bind(stmt, [value, workspace_id])
    :done = Exqlite.Sqlite3.step(db, stmt)
    Exqlite.Sqlite3.release(db, stmt)
  end

  defp delete_workspace(db, workspace_id) do
    {:ok, stmt1} = Exqlite.Sqlite3.prepare(db, "DELETE FROM workspace_versions WHERE workspace_id = ?1")
    :ok = Exqlite.Sqlite3.bind(stmt1, [workspace_id])
    :done = Exqlite.Sqlite3.step(db, stmt1)
    Exqlite.Sqlite3.release(db, stmt1)

    {:ok, stmt2} = Exqlite.Sqlite3.prepare(db, "DELETE FROM workspaces WHERE id = ?1")
    :ok = Exqlite.Sqlite3.bind(stmt2, [workspace_id])
    :done = Exqlite.Sqlite3.step(db, stmt2)
    Exqlite.Sqlite3.release(db, stmt2)
  end

  defp find_by_path(workspaces, path) do
    case Enum.find(workspaces, fn {_id, ws} -> ws.path == path end) do
      nil -> :not_found
      {_id, workspace} -> {:ok, workspace}
    end
  end

  defp canonicalize_path(path) do
    expanded = Path.expand(path)
    # Resolve symlinks if the path exists
    if File.exists?(expanded) do
      case :file.read_link_all(String.to_charlist(expanded)) do
        {:ok, link_target} ->
          link_str = List.to_string(link_target)
          if Path.type(link_str) == :absolute, do: link_str, else: Path.join(Path.dirname(expanded), link_str)
        {:error, _} -> expanded
      end
    else
      expanded
    end
  end

  defp parse_datetime(str) when is_binary(str) do
    case DateTime.from_iso8601(str) do
      {:ok, dt, _offset} -> dt
      _ -> DateTime.utc_now()
    end
  end

  defp parse_datetime(_), do: DateTime.utc_now()

  defp generate_ulid do
    # Simple ULID-like ID (timestamp + random)
    ts = System.system_time(:millisecond)
    rand = :crypto.strong_rand_bytes(10) |> Base.encode16(case: :lower) |> String.slice(0, 16)
    "#{ts |> Integer.to_string(16) |> String.downcase()}-#{rand}"
  end

  defp get_git_stats(path) do
    git_dir = Path.join(path, ".git")

    if File.dir?(git_dir) do
      branch = run_git(path, ["rev-parse", "--abbrev-ref", "HEAD"]) |> String.trim()

      uncommitted =
        run_git(path, ["status", "--porcelain"])
        |> String.split("\n", trim: true)
        |> length()

      {last_commit, last_commit_message} =
        case run_git(path, ["log", "-1", "--format=%cI|%s"]) |> String.trim() |> String.split("|", parts: 2) do
          [date_str, message] ->
            {parse_datetime(date_str), message}

          _ ->
            {nil, nil}
        end

      total_files =
        run_git(path, ["ls-files"])
        |> String.split("\n", trim: true)
        |> length()

      commit_count =
        case run_git(path, ["rev-list", "--count", "HEAD"]) |> String.trim() |> Integer.parse() do
          {count, _} -> count
          _ -> nil
        end

      %{
        branch: branch,
        commit_count: commit_count,
        uncommitted_changes: uncommitted,
        last_commit: last_commit,
        last_commit_message: last_commit_message,
        total_files: total_files,
        updated_at: DateTime.utc_now()
      }
    else
      nil
    end
  end

  defp run_git(path, args) do
    case System.cmd("git", ["-C", path] ++ args, stderr_to_stdout: true) do
      {output, 0} -> output
      _ -> ""
    end
  end
end
