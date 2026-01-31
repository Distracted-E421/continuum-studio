defmodule StudioCore do
  @moduledoc """
  Continuum Studio Core

  The Elixir backend for Continuum Studio, providing:

  - **State Management**: Centralized ETS-backed state
  - **Event Bus**: Pub/sub for state changes
  - **Harness Registry**: Tracking connected Synapsix harnesses
  - **Socket Interface**: Unix socket for UI communication

  ## Architecture

      ┌─────────────────────────────────────────────────┐
      │              Studio Core (BEAM)                 │
      │                                                 │
      │  ┌─────────┐  ┌───────────┐  ┌─────────────┐ │
      │  │  State  │  │ EventBus  │  │   Harness   │ │
      │  │  (ETS)  │──│  (PubSub) │──│  Registry   │ │
      │  └────┬────┘  └─────┬─────┘  └──────┬──────┘ │
      │       │             │               │         │
      │       └─────────────┼───────────────┘         │
      │                     │                         │
      │           ┌─────────┴─────────┐               │
      │           │  Socket Acceptor  │               │
      │           │  (Unix Domain)    │               │
      │           └─────────┬─────────┘               │
      └─────────────────────┼─────────────────────────┘
                            │
                   ┌────────┴────────┐
                   │                 │
              ┌────┴────┐      ┌─────┴─────┐
              │ Studio  │      │  Synapsix │
              │   UI    │      │ Harnesses │
              │ (Rust)  │      │ (Elixir)  │
              └─────────┘      └───────────┘

  ## Usage

  Start the application:

      iex -S mix

  Or as a release:

      mix release
      _build/prod/rel/studio_core/bin/studio_core start

  ## API

  The socket protocol uses ETF (Erlang Term Format) with a 4-byte
  length prefix:

      # Commands (UI → Core)
      {:command, :harness_start, %{type: "cursor"}}
      {:command, :state_set, %{path: [:ui, :theme], value: "dark"}}
      {:command, :ping, %{}}

      # Events (Core → UI)
      {:event, :harness_status, %{harness: "cursor", status: :running}}
      {:event, :state_changed, %{path: [:ui, :theme], value: "dark"}}
      {:event, :pong, %{}}
  """

  @doc """
  Get the current state at a path.
  """
  defdelegate get(path), to: StudioCore.State

  @doc """
  Set state at a path.
  """
  defdelegate set(path, value), to: StudioCore.State

  @doc """
  Register a harness.
  """
  defdelegate register_harness(harness_id, opts \\ []), to: StudioCore.HarnessRegistry, as: :register

  @doc """
  List all harnesses.
  """
  defdelegate list_harnesses, to: StudioCore.HarnessRegistry, as: :list

  @doc """
  Subscribe to events.
  """
  defdelegate subscribe(filter \\ :all), to: StudioCore.EventBus

  @doc """
  Broadcast an event.
  """
  defdelegate broadcast(event), to: StudioCore.EventBus

  @doc """
  Broadcast a message to all connected UI clients.
  """
  defdelegate broadcast_to_clients(message), to: StudioCore.Socket.Acceptor, as: :broadcast

  # ==========================================================================
  # Version Management
  # ==========================================================================

  @doc """
  List all available Cursor versions.
  """
  defdelegate list_versions(opts \\ []), to: StudioCore.VersionRegistry

  @doc """
  Get a specific Cursor version.
  """
  defdelegate get_version(version), to: StudioCore.VersionRegistry

  @doc """
  Get the latest Cursor version.
  """
  defdelegate latest_version, to: StudioCore.VersionRegistry, as: :latest

  @doc """
  List installed Cursor versions.
  """
  defdelegate list_installed_versions, to: StudioCore.VersionRegistry, as: :list_installed

  @doc """
  Download a Cursor version.
  """
  defdelegate download_version(version, opts \\ []), to: StudioCore.VersionRegistry, as: :download

  @doc """
  Run a Cursor version.
  """
  defdelegate run_version(version, opts \\ []), to: StudioCore.VersionRegistry, as: :run

  @doc """
  Get version registry statistics.
  """
  defdelegate version_stats, to: StudioCore.VersionRegistry, as: :stats
end
