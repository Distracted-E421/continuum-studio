defmodule StudioCore.Application do
  @moduledoc """
  Continuum Studio Core Application

  The main OTP application that coordinates:
  - UI connections via Unix socket
  - Harness registrations from Synapsix
  - State management via ETS
  - Event broadcasting to connected clients
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
    Supervisor.start_link(children, opts)
  end

  defp socket_path do
    System.get_env("STUDIO_SOCKET_PATH", "/tmp/continuum-studio.sock")
  end
end
