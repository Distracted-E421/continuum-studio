defmodule StudioCore.StateSnapshot do
  @moduledoc """
  State snapshot persistence for hot restart resilience.

  Saves critical in-memory state to disk before shutdown and restores
  it on startup. This enables:

  - Hot restarts without losing UI context
  - Graceful updates where running Cursor instances continue unaffected
  - Recovery from crashes with minimal state loss

  ## What Gets Snapshotted

  - Global state (harness statuses, UI preferences, agent states)
  - Harness registry entries (connected harness metadata, minus PIDs)
  - Active connections metadata (for UI reconnect context)

  ## What Doesn't Need Snapshotting

  - WorkspaceTracker (SQLite-backed, already persistent)
  - AuthManager (profiles.json on disk)
  - VersionRegistry (loaded from filesystem + JSON)
  - EventBus subscriptions (rebuilt on reconnect)
  - Socket connections (rebuilt on reconnect)

  ## Snapshot Location

  `~/.continuum-studio/state-snapshot.etf` (Erlang Term Format for speed)
  """

  require Logger

  @snapshot_dir "~/.continuum-studio"
  @snapshot_file "state-snapshot.etf"
  @snapshot_meta_file "state-snapshot-meta.json"

  @doc """
  Save a snapshot of critical in-memory state to disk.
  Called automatically before shutdown and can be called manually.
  """
  def save do
    snapshot = %{
      timestamp: DateTime.utc_now() |> DateTime.to_iso8601(),
      version: Application.spec(:studio_core, :vsn) |> to_string(),
      state: collect_state(),
      harnesses: collect_harness_state(),
    }

    path = snapshot_path()
    dir = Path.dirname(path)

    with :ok <- File.mkdir_p(dir),
         binary <- :erlang.term_to_binary(snapshot, [:compressed]),
         :ok <- File.write(path, binary) do
      # Also write human-readable metadata
      meta = %{
        saved_at: snapshot.timestamp,
        version: snapshot.version,
        state_keys: Map.keys(snapshot.state),
        harness_count: map_size(snapshot.harnesses),
        size_bytes: byte_size(binary),
      }

      meta_path = meta_path()
      _ = File.write(meta_path, Jason.encode!(meta, pretty: true))

      Logger.info("State snapshot saved (#{byte_size(binary)} bytes) to #{path}")
      :ok
    else
      {:error, reason} ->
        Logger.error("Failed to save state snapshot: #{inspect(reason)}")
        {:error, reason}
    end
  end

  @doc """
  Restore state from the most recent snapshot.
  Returns the snapshot data or {:error, reason}.
  """
  def restore do
    path = snapshot_path()

    case File.read(path) do
      {:ok, binary} ->
        try do
          snapshot = :erlang.binary_to_term(binary, [:safe])

          Logger.info(
            "Restored state snapshot from #{snapshot[:timestamp]} " <>
            "(v#{snapshot[:version]}, #{map_size(snapshot[:harnesses] || %{})} harnesses)"
          )

          {:ok, snapshot}
        rescue
          e ->
            Logger.warning("Failed to decode state snapshot: #{inspect(e)}")
            {:error, :decode_failed}
        end

      {:error, :enoent} ->
        Logger.debug("No state snapshot found (first run or clean start)")
        {:error, :not_found}

      {:error, reason} ->
        Logger.warning("Failed to read state snapshot: #{inspect(reason)}")
        {:error, reason}
    end
  end

  @doc """
  Apply a restored snapshot to the running system.
  Should be called early in startup, after GenServers are up.
  """
  def apply_snapshot(%{state: state_data, harnesses: harness_data}) do
    # Restore global state
    if is_map(state_data) and map_size(state_data) > 0 do
      for {path_key, value} <- flatten_state(state_data) do
        StudioCore.State.set(path_key, value)
      end
      Logger.info("Restored global state from snapshot")
    end

    # Restore harness metadata (without PIDs - they'll re-register)
    if is_map(harness_data) and map_size(harness_data) > 0 do
      for {harness_id, meta} <- harness_data do
        # Mark as disconnected since the actual processes are gone
        cleaned_meta = meta
          |> Map.drop([:pid, :socket])
          |> Map.put(:status, :reconnecting)
          |> Map.put(:restored_from_snapshot, true)

        StudioCore.State.register_harness(harness_id, cleaned_meta)
      end
      Logger.info("Restored #{map_size(harness_data)} harness metadata entries from snapshot")
    end

    :ok
  end

  def apply_snapshot(_), do: :ok

  @doc """
  Delete the snapshot file (after successful startup).
  """
  def clear do
    path = snapshot_path()
    meta = meta_path()
    _ = File.rm(path)
    _ = File.rm(meta)
    Logger.debug("Cleared state snapshot")
    :ok
  end

  @doc """
  Check if a snapshot exists and return its metadata.
  """
  def info do
    meta = meta_path()
    case File.read(meta) do
      {:ok, content} ->
        case Jason.decode(content) do
          {:ok, info} -> {:ok, info}
          _ -> {:error, :invalid_meta}
        end
      _ -> {:error, :not_found}
    end
  end

  # -- Internal --

  defp snapshot_path do
    Path.expand(@snapshot_dir) |> Path.join(@snapshot_file)
  end

  defp meta_path do
    Path.expand(@snapshot_dir) |> Path.join(@snapshot_meta_file)
  end

  defp collect_state do
    try do
      StudioCore.State.get_all()
    rescue
      _ -> %{}
    end
  end

  defp collect_harness_state do
    try do
      StudioCore.HarnessRegistry.list()
      |> Enum.map(fn harness ->
        # Strip non-serializable data (PIDs, sockets)
        {harness.id, Map.drop(harness, [:pid, :socket, :__struct__])}
      end)
      |> Map.new()
    rescue
      _ -> %{}
    end
  end

  # Flatten a nested state map into a list of {path, value} tuples
  # e.g., %{harnesses: %{"cursor" => %{status: :running}}}
  # => [{[:harnesses, "cursor", :status], :running}]
  defp flatten_state(map, prefix \\ []) do
    Enum.flat_map(map, fn {key, value} ->
      path = prefix ++ [key]
      if is_map(value) and not Map.has_key?(value, :__struct__) do
        flatten_state(value, path)
      else
        [{path, value}]
      end
    end)
  end
end
