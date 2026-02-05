defmodule StudioCore.Application do
  @moduledoc """
  Continuum Studio Core Application

  The main OTP application that coordinates:
  - UI connections via Unix socket
  - Harness registrations from Synapsix
  - State management via ETS
  - Event broadcasting to connected clients

  ## Hot Restart Resilience

  On shutdown, critical in-memory state is snapshotted to disk.
  On startup, the snapshot is restored so running Cursor instances
  and connected UIs can seamlessly reconnect without losing context.
  """
  use Application
  require Logger

  @impl true
  def start(_type, _args) do
    Logger.info("Starting Continuum Studio Core v#{Application.spec(:studio_core, :vsn)}")

    children = [
      # State manager - ETS-backed global state
      StudioCore.State,

      # Event bus - pub/sub for state changes
      StudioCore.EventBus,

      # Harness registry - tracks connected harnesses
      StudioCore.HarnessRegistry,

      # Version registry - Cursor version management
      StudioCore.VersionRegistry,

      # Auth manager - Cursor authentication management
      StudioCore.AuthManager,

      # Workspace tracker - tracks projects across versions
      StudioCore.WorkspaceTracker,

      # Unix socket acceptor for UI connections
      {StudioCore.Socket.Acceptor, socket_path()},
    ]

    opts = [strategy: :one_for_one, name: StudioCore.Supervisor]

    case Supervisor.start_link(children, opts) do
      {:ok, pid} ->
        # Restore state from snapshot after all GenServers are up
        restore_from_snapshot()
        {:ok, pid}

      error ->
        error
    end
  end

  @impl true
  def prep_stop(_state) do
    # Save state snapshot before shutdown for hot restart resilience
    Logger.info("Preparing for shutdown - saving state snapshot...")
    StudioCore.StateSnapshot.save()

    # Notify connected clients about graceful shutdown
    StudioCore.EventBus.broadcast({:system, :shutting_down})

    # Give clients a moment to process the shutdown notification
    Process.sleep(100)

    :ok
  end

  @impl true
  def stop(_state) do
    Logger.info("Continuum Studio Core stopped")
    :ok
  end

  # -- Private --

  defp socket_path do
    System.get_env("STUDIO_SOCKET_PATH", "/tmp/continuum-studio.sock")
  end

  defp restore_from_snapshot do
    case StudioCore.StateSnapshot.restore() do
      {:ok, snapshot} ->
        StudioCore.StateSnapshot.apply_snapshot(snapshot)

        # Clear the snapshot after successful restore
        # (will be re-saved on next shutdown)
        StudioCore.StateSnapshot.clear()

        Logger.info("Hot restart recovery complete")

      {:error, :not_found} ->
        Logger.debug("Clean start (no snapshot to restore)")

      {:error, reason} ->
        Logger.warning("Failed to restore snapshot: #{inspect(reason)}")
    end
  end
end
