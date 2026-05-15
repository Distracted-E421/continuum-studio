defmodule StudioCore.Socket.Handler do
  @moduledoc """
  Handler for a single UI client connection.

  Manages the bidirectional communication with a connected
  Continuum Studio UI instance.

  ## Commands (UI → Core)

  - `{:command, :harness_start, %{type: type}}` - Start a harness
  - `{:command, :harness_stop, %{type: type}}` - Stop a harness
  - `{:command, :state_set, %{path: path, value: value}}` - Set state
  - `{:command, :state_get, %{path: path}}` - Get state
  - `{:command, :agent_message, %{text: text, provider: provider}}` - Agent message
  - `{:command, :ping, %{}}` - Health check

  ## Events (Core → UI)

  - `{:event, :harness_status, %{harness: id, status: status}}` - Status change
  - `{:event, :state_changed, %{path: path, value: value}}` - State update
  - `{:event, :agent_response, %{content: content, role: role}}` - Agent response
  - `{:event, :pong, %{}}` - Ping response
  - `{:event, :error, %{message: message}}` - Error occurred
  """
  use GenServer
  require Logger

  # All known commands - ensures atoms exist at compile time
  # IMPORTANT: Every handle_command/3 clause must have its command listed here
  @known_commands ~w(
    ping subscribe harness_start harness_stop state_set state_get agent_message
    versions_list versions_installed versions_download versions_run versions_refresh
    versions_stats versions_uninstall versions_batch_uninstall
    versions_disk_usage versions_disk_usage_all versions_update versions_updater_status
    workspaces_list workspaces_register workspaces_get workspaces_refresh
    workspaces_record_version workspaces_toggle_pinned workspaces_refresh_git
    workspaces_delete
    auth_version_status auth_list_statuses auth_all_statuses
    auth_extract auth_apply auth_list_profiles
  )a

  defmodule State do
    @moduledoc false
    defstruct [:socket, :subscribed, buffer: ""]
  end

  # Client API

  def start_link(socket) do
    GenServer.start_link(__MODULE__, socket)
  end

  # Server callbacks

  @impl true
  def init(socket) do
    # Subscribe to event bus for state changes
    StudioCore.EventBus.subscribe()

    # Set socket to active mode for receiving
    :inet.setopts(socket, active: true)

    Logger.debug("Socket handler started")
    {:ok, %State{socket: socket, subscribed: true}}
  end

  @impl true
  def handle_info({:tcp, _socket, data}, state) do
    # Buffer data and process complete lines
    buffer = state.buffer <> data
    {lines, remaining} = split_lines(buffer)

    # Process each complete line
    new_state =
      Enum.reduce(lines, %{state | buffer: remaining}, fn line, acc ->
        process_line(line, acc)
      end)

    {:noreply, new_state}
  end

  @impl true
  def handle_info({:tcp_closed, _socket}, state) do
    Logger.debug("Client closed connection")
    {:stop, :normal, state}
  end

  @impl true
  def handle_info({:tcp_error, _socket, reason}, state) do
    Logger.warning("Socket error: #{inspect(reason)}")
    {:stop, reason, state}
  end

  @impl true
  def handle_info({:event, event}, state) do
    # Forward events from EventBus to the UI
    send_event(state.socket, event)
    {:noreply, state}
  end

  @impl true
  def terminate(_reason, state) do
    StudioCore.EventBus.unsubscribe()
    if state.socket, do: :gen_tcp.close(state.socket)
    :ok
  end

  # Private helpers for TCP message processing

  # Split buffer into complete lines and remaining partial
  defp split_lines(buffer) do
    case String.split(buffer, "\n") do
      # No complete line yet
      [single] ->
        {[], single}

      parts ->
        {complete, [remaining]} = Enum.split(parts, -1)
        # Filter out empty lines
        complete = Enum.filter(complete, &(&1 != ""))
        {complete, remaining}
    end
  end

  # Process a single complete line
  defp process_line(line, state) do
    case decode_message(line) do
      {:ok, {:command, command, params}} ->
        {_, new_state} = handle_command(command, params, state)
        new_state

      {:ok, {:event, event, data}} ->
        # Event from Synapsix (harness events)
        {_, new_state} = handle_synapsix_event(event, data, state)
        new_state

      {:error, reason} ->
        Logger.warning("Failed to decode message: #{inspect(reason)} - #{inspect(line)}")
        send_error(state.socket, "Invalid message format")
        state
    end
  end

  # Decode message - try JSON first, then ETF
  defp decode_message(data) do
    case Jason.decode(data) do
      {:ok, %{"command" => cmd, "params" => params}} ->
        # Only convert to atom if it's a known command (security)
        cmd_atom = String.to_atom(cmd)

        if cmd_atom in @known_commands do
          params_atoms = atomize_keys(params)
          {:ok, {:command, cmd_atom, params_atoms}}
        else
          {:error, {:unknown_command, cmd}}
        end

      {:ok, %{"event" => event, "data" => event_data}} ->
        # Event from Synapsix - use to_existing_atom for safety
        try do
          event_atom = String.to_existing_atom(event)
          data_atoms = atomize_keys(event_data)
          {:ok, {:event, event_atom, data_atoms}}
        rescue
          ArgumentError -> {:error, {:unknown_event, event}}
        end

      {:ok, _} ->
        {:error, :invalid_json_format}

      {:error, _} ->
        # Try ETF
        try do
          case :erlang.binary_to_term(data, [:safe]) do
            {:command, cmd, params} when is_atom(cmd) ->
              {:ok, {:command, cmd, params}}

            {:event, event, data} when is_atom(event) ->
              {:ok, {:event, event, data}}

            _ ->
              {:error, :invalid_etf_format}
          end
        rescue
          ArgumentError -> {:error, :decode_failed}
        end
    end
  end

  # Convert string keys to atoms
  defp atomize_keys(map) when is_map(map) do
    Map.new(map, fn
      {k, v} when is_binary(k) -> {String.to_atom(k), atomize_keys(v)}
      {k, v} -> {k, atomize_keys(v)}
    end)
  end

  defp atomize_keys(list) when is_list(list), do: Enum.map(list, &atomize_keys/1)
  defp atomize_keys(value), do: value

  # Command handlers

  defp handle_command(:harness_start, %{type: type}, state) do
    Logger.info("UI requested harness start: #{type}")

    # This would trigger harness start via Synapsix
    # For now, just update state
    StudioCore.State.set_harness_status(to_string(type), :starting)

    send_event(state.socket, {:harness_status, to_string(type), :starting})
    {:noreply, state}
  end

  defp handle_command(:harness_stop, %{type: type}, state) do
    Logger.info("UI requested harness stop: #{type}")

    StudioCore.State.set_harness_status(to_string(type), :stopping)

    send_event(state.socket, {:harness_status, to_string(type), :stopping})
    {:noreply, state}
  end

  defp handle_command(:state_set, %{path: path, value: value}, state) do
    StudioCore.State.set(path, value)
    {:noreply, state}
  end

  defp handle_command(:state_get, %{path: path}, state) do
    value = StudioCore.State.get(path)
    send_event(state.socket, {:state_value, path, value})
    {:noreply, state}
  end

  defp handle_command(:agent_message, %{text: text, provider: _provider}, state) do
    Logger.info("Agent message from UI: #{String.slice(text, 0, 50)}...")

    # This would forward to the agent bridge
    # For now, echo back a mock response
    Task.start(fn ->
      Process.sleep(500)
      response = "Echo: #{text}"
      StudioCore.EventBus.broadcast({:agent_response, response, "assistant"})
    end)

    {:noreply, state}
  end

  defp handle_command(:ping, _params, state) do
    send_event(state.socket, {:pong, %{}})
    {:noreply, state}
  end

  defp handle_command(:subscribe, %{events: events}, state) do
    # Already subscribed to all events
    Logger.debug("Client subscribed to events: #{inspect(events)}")
    {:noreply, %{state | subscribed: true}}
  end

  # Version management commands

  defp handle_command(:versions_list, params, state) do
    opts = build_version_opts(params)

    case StudioCore.VersionRegistry.list_versions(opts) do
      {:ok, versions} ->
        send_event(state.socket, {:versions_list, versions})

      {:error, reason} ->
        send_error(state.socket, "Failed to list versions: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_installed, _params, state) do
    case StudioCore.VersionRegistry.list_installed() do
      {:ok, versions} ->
        send_event(state.socket, {:versions_installed, versions})

      {:error, reason} ->
        send_error(state.socket, "Failed to list installed: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_download, %{version: version}, state) do
    # Start download in background task
    Task.start(fn ->
      case StudioCore.VersionRegistry.download(version) do
        {:ok, :already_installed} ->
          StudioCore.EventBus.broadcast({:version_downloaded, version, :already_installed})

        {:ok, path} ->
          StudioCore.EventBus.broadcast({:version_downloaded, version, path})

        {:error, reason} ->
          StudioCore.EventBus.broadcast({:version_download_failed, version, reason})
      end
    end)

    send_event(state.socket, {:version_download_started, version})
    {:noreply, state}
  end

  defp handle_command(:versions_run, %{version: version} = params, state) do
    opts = if params[:folder], do: [folder: params[:folder]], else: []

    case StudioCore.VersionRegistry.run(version, opts) do
      {:ok, info} ->
        # Send both version_running (for status update) and launch_result (for feedback)
        send_event(state.socket, {:version_running, info})

        send_event(
          state.socket,
          {:launch_result, %{success: true, message: "Cursor #{version} launched successfully"}}
        )

      {:error, :not_installed} ->
        send_event(
          state.socket,
          {:launch_result,
           %{
             success: false,
             message: "Version #{version} is not installed. Please download it first."
           }}
        )

      {:error, reason} ->
        send_event(
          state.socket,
          {:launch_result,
           %{success: false, message: "Failed to run version: #{inspect(reason)}"}}
        )
    end

    {:noreply, state}
  end

  defp handle_command(:versions_stats, _params, state) do
    case StudioCore.VersionRegistry.stats() do
      {:ok, stats} ->
        send_event(state.socket, {:versions_stats, stats})

      {:error, reason} ->
        send_error(state.socket, "Failed to get stats: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  # Version cleanup commands

  defp handle_command(:versions_uninstall, %{version: version} = params, state) do
    opts = if params[:remove_data], do: [remove_data: true], else: []

    case StudioCore.VersionRegistry.uninstall(version, opts) do
      {:ok, :uninstalled} ->
        send_event(
          state.socket,
          {:version_uninstalled, %{version: version, remove_data: !!params[:remove_data]}}
        )

      {:error, reason} ->
        send_error(state.socket, "Failed to uninstall #{version}: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_batch_uninstall, %{versions: versions} = params, state) do
    opts = if params[:remove_data], do: [remove_data: true], else: []

    case StudioCore.VersionRegistry.batch_uninstall(versions, opts) do
      {:ok, results} ->
        send_event(state.socket, {:versions_batch_uninstalled, %{results: results}})

      {:error, reason} ->
        send_error(state.socket, "Failed to batch uninstall: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_disk_usage, %{version: version}, state) do
    case StudioCore.VersionRegistry.disk_usage_detailed(version) do
      {:ok, usage} ->
        send_event(state.socket, {:version_disk_usage, usage})

      {:error, reason} ->
        send_error(state.socket, "Failed to get disk usage: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_disk_usage_all, _params, state) do
    case StudioCore.VersionRegistry.disk_usage_all() do
      {:ok, usage} ->
        send_event(state.socket, {:versions_disk_usage_all, usage})

      {:error, reason} ->
        send_error(state.socket, "Failed to get disk usage: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  # Version update checking (uses VersionUpdater)
  defp handle_command(:versions_refresh, _params, state) do
    case StudioCore.VersionUpdater.check_for_updates() do
      {:ok, {current, upstream, new_versions}} ->
        send_event(
          state.socket,
          {:versions_refresh_result,
           %{
             current_latest: current,
             upstream_latest: upstream,
             new_versions: new_versions,
             new_count: length(new_versions)
           }}
        )

      {:error, reason} ->
        send_error(state.socket, "Failed to check for updates: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:versions_update, _params, state) do
    # Update the local manifest from upstream
    Task.start(fn ->
      case StudioCore.VersionUpdater.update_versions() do
        {:ok, count} ->
          StudioCore.EventBus.broadcast({:versions_updated, %{new_count: count}})

        {:error, reason} ->
          StudioCore.EventBus.broadcast({:versions_update_failed, %{reason: inspect(reason)}})
      end
    end)

    send_event(state.socket, {:versions_update_started, %{}})
    {:noreply, state}
  end

  defp handle_command(:versions_updater_status, _params, state) do
    status = StudioCore.VersionUpdater.status()
    send_event(state.socket, {:versions_updater_status, status})
    {:noreply, state}
  end

  # Workspace management commands

  defp handle_command(:workspaces_list, params, state) do
    limit = params[:limit] || 50

    case StudioCore.WorkspaceTracker.list_recent(limit) do
      {:ok, workspaces} ->
        send_event(state.socket, {:workspaces_list, workspaces})

      {:error, reason} ->
        send_error(state.socket, "Failed to list workspaces: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:workspaces_register, %{path: path}, state) do
    case StudioCore.WorkspaceTracker.register(path) do
      {:ok, workspace} ->
        send_event(state.socket, {:workspace_registered, workspace})

      {:error, reason} ->
        send_error(state.socket, "Failed to register workspace: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:workspaces_get, %{id: id}, state) do
    case StudioCore.WorkspaceTracker.get(id) do
      {:ok, workspace} ->
        send_event(state.socket, {:workspace, workspace})

      {:error, :not_found} ->
        send_error(state.socket, "Workspace not found")
    end

    {:noreply, state}
  end

  defp handle_command(
         :workspaces_record_version,
         %{workspace_id: workspace_id, version: version},
         state
       ) do
    case StudioCore.WorkspaceTracker.record_version_open(workspace_id, version) do
      :ok ->
        send_event(
          state.socket,
          {:workspace_version_recorded, %{workspace_id: workspace_id, version: version}}
        )

      {:error, reason} ->
        send_error(state.socket, "Failed to record version: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:workspaces_toggle_pinned, %{id: id}, state) do
    case StudioCore.WorkspaceTracker.toggle_pinned(id) do
      {:ok, pinned} ->
        send_event(state.socket, {:workspace_pinned, %{id: id, pinned: pinned}})

      {:error, reason} ->
        send_error(state.socket, "Failed to toggle pinned: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:workspaces_refresh_git, %{id: id}, state) do
    case StudioCore.WorkspaceTracker.refresh_git_stats(id) do
      {:ok, git_stats} ->
        send_event(state.socket, {:workspace_git_stats, %{id: id, git_stats: git_stats}})

      {:error, reason} ->
        send_error(state.socket, "Failed to refresh git stats: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:workspaces_delete, %{id: id}, state) do
    case StudioCore.WorkspaceTracker.delete(id) do
      :ok ->
        send_event(state.socket, {:workspace_deleted, %{id: id}})

      {:error, reason} ->
        send_error(state.socket, "Failed to delete workspace: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  # Auth management commands

  defp handle_command(:auth_version_status, %{version: version}, state) do
    case StudioCore.AuthManager.version_auth_status(version) do
      {:ok, status} ->
        send_event(state.socket, {:auth_status, status})

      {:error, reason} ->
        send_error(state.socket, "Failed to get auth status: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:auth_list_statuses, _params, state) do
    case StudioCore.AuthManager.list_installed_auth_statuses() do
      {:ok, statuses} ->
        send_event(state.socket, {:auth_statuses, statuses})

      {:error, reason} ->
        send_error(state.socket, "Failed to list auth statuses: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:auth_all_statuses, _params, state) do
    # List auth for ALL discovered versions (not just installed)
    case StudioCore.AuthManager.list_version_auth_statuses() do
      {:ok, statuses} ->
        send_event(state.socket, {:auth_statuses, statuses})

      {:error, reason} ->
        send_error(state.socket, "Failed to list auth statuses: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(:auth_extract, %{version: version}, state) do
    case StudioCore.AuthManager.extract_from_version(version) do
      {:ok, profile} ->
        # Save the profile for later application
        _save_result = StudioCore.AuthManager.save_profile(profile)
        # Return profile metadata (tokens redacted for security)
        safe_profile = Map.drop(profile, [:access_token, :refresh_token])
        send_event(state.socket, {:auth_extracted, Map.put(safe_profile, :has_tokens, true)})

      {:error, reason} ->
        send_event(
          state.socket,
          {:auth_extract_failed, %{version: version, error: inspect(reason)}}
        )
    end

    {:noreply, state}
  end

  defp handle_command(:auth_apply, %{source: source, target: target}, state) do
    case StudioCore.AuthManager.apply_auth(source, target) do
      {:ok, result} ->
        send_event(state.socket, {:auth_applied, result})
        # Also broadcast updated auth status for target
        case StudioCore.AuthManager.version_auth_status(target) do
          {:ok, status} ->
            send_event(state.socket, {:auth_status, status})

          _ ->
            :ok
        end

      {:error, reason} ->
        send_event(
          state.socket,
          {:auth_apply_failed, %{source: source, target: target, error: inspect(reason)}}
        )
    end

    {:noreply, state}
  end

  defp handle_command(:auth_list_profiles, _params, state) do
    case StudioCore.AuthManager.list_profiles() do
      {:ok, profiles} ->
        send_event(state.socket, {:auth_profiles, profiles})

      {:error, reason} ->
        send_error(state.socket, "Failed to list profiles: #{inspect(reason)}")
    end

    {:noreply, state}
  end

  defp handle_command(unknown, params, state) do
    Logger.warning("Unknown command: #{inspect(unknown)} with #{inspect(params)}")
    send_error(state.socket, "Unknown command: #{unknown}")
    {:noreply, state}
  end

  defp build_version_opts(params) do
    []
    |> maybe_add_opt(:era, parse_era(params[:era]))
    |> maybe_add_opt(:limit, params[:limit])
  end

  defp maybe_add_opt(opts, _key, nil), do: opts
  defp maybe_add_opt(opts, key, value), do: Keyword.put(opts, key, value)

  defp parse_era("latest"), do: :latest
  defp parse_era("custom_modes"), do: :custom_modes
  defp parse_era("classic"), do: :classic
  defp parse_era(_), do: nil

  # Synapsix event handlers

  defp handle_synapsix_event(:harness_registered, %{harness: harness, type: type} = data, state) do
    Logger.info("🔗 Harness registered: #{harness} (type: #{type})")

    capabilities = Map.get(data, :capabilities, [])
    type_atom = if is_atom(type), do: type, else: String.to_atom(type)

    # Register with the harness registry (pid is this socket handler for forwarding)
    StudioCore.HarnessRegistry.register(harness, type: type_atom, capabilities: capabilities)

    # Broadcast to all UI clients
    StudioCore.EventBus.broadcast({:harness_registered, harness, type_atom})

    {:noreply, state}
  end

  defp handle_synapsix_event(:harness_unregistered, %{harness: harness}, state) do
    Logger.info("🔌 Harness unregistered: #{harness}")

    StudioCore.HarnessRegistry.unregister(harness)
    StudioCore.EventBus.broadcast({:harness_disconnected, harness, :unregistered})

    {:noreply, state}
  end

  defp handle_synapsix_event(:harness_status, %{harness: harness, status: status}, state) do
    Logger.info("📊 Harness status: #{harness} → #{status}")

    status_atom = if is_atom(status), do: status, else: String.to_atom(status)
    StudioCore.State.set_harness_status(harness, status_atom)
    StudioCore.EventBus.broadcast({:harness_status, harness, status_atom})

    {:noreply, state}
  end

  defp handle_synapsix_event(:harness_reconnected, %{harness: harness}, state) do
    Logger.info("🔄 Harness reconnected: #{harness}")

    # Update status to reconnected/running
    StudioCore.State.set_harness_status(harness, :reconnected)
    StudioCore.EventBus.broadcast({:harness_status, harness, :reconnected})

    {:noreply, state}
  end

  defp handle_synapsix_event(:harness_metadata, data, state) do
    harness = Map.get(data, :harness) || Map.get(data, "harness")
    Logger.info("🎨 Harness metadata: #{harness} → #{inspect(data)}")

    # Broadcast metadata to all UI clients
    StudioCore.EventBus.broadcast({:harness_metadata, harness, data})

    {:noreply, state}
  end

  defp handle_synapsix_event(:window_info, data, state) do
    harness = Map.get(data, :harness) || Map.get(data, "harness")
    window_id = Map.get(data, :window_id) || Map.get(data, "window_id") || ""
    window_name = Map.get(data, :window_name) || Map.get(data, "window_name") || ""
    Logger.info("🪟 Window info: #{harness} → #{window_name}")

    # Broadcast window info to UI
    StudioCore.EventBus.broadcast({:window_info, harness, window_id, window_name})

    {:noreply, state}
  end

  defp handle_synapsix_event(:agent_response, data, state) do
    harness = Map.get(data, :harness) || Map.get(data, "harness")
    content = Map.get(data, :content) || Map.get(data, "content") || ""
    role = Map.get(data, :role) || Map.get(data, "role") || "assistant"
    streaming = Map.get(data, :streaming) || Map.get(data, "streaming") || false
    complete = Map.get(data, :complete) || Map.get(data, "complete") || true
    Logger.debug("💬 Agent response from #{harness}: #{String.slice(content, 0..50)}...")

    # Broadcast to UI with full metadata
    StudioCore.EventBus.broadcast(
      {:agent_response_full, harness, content, role, streaming, complete}
    )

    {:noreply, state}
  end

  defp handle_synapsix_event(event, data, state) do
    Logger.warning("Unknown Synapsix event: #{inspect(event)} with #{inspect(data)}")
    {:noreply, state}
  end

  # Helpers

  defp send_event(socket, event) do
    # Send as JSON for Rust UI compatibility
    # IMPORTANT: Must include newline as Rust reader uses read_line()
    message = %{
      "event" => Atom.to_string(event_type(event)),
      "data" => event_data(event)
    }

    encoded = Jason.encode!(message) <> "\n"
    :gen_tcp.send(socket, encoded)
  end

  defp send_error(socket, message) do
    send_event(socket, {:error, %{message: message}})
  end

  defp event_type({:harness_status, _, _}), do: :harness_status
  defp event_type({:harness_registered, _, _}), do: :harness_registered
  defp event_type({:harness_disconnected, _, _}), do: :harness_disconnected
  defp event_type({:harness_metadata, _, _}), do: :harness_metadata
  defp event_type({:window_info, _, _, _}), do: :window_info
  defp event_type({:agent_response_full, _, _, _, _, _}), do: :agent_response
  defp event_type({:state_changed, _, _}), do: :state_changed
  defp event_type({:state_value, _, _}), do: :state_value
  defp event_type({:agent_response, _, _}), do: :agent_response
  defp event_type({:pong, _}), do: :pong
  defp event_type({:error, _}), do: :error
  defp event_type({type, _}) when is_atom(type), do: type
  defp event_type({type, _, _}) when is_atom(type), do: type
  defp event_type(_), do: :unknown

  defp event_data({:harness_status, harness, status}), do: %{harness: harness, status: status}
  defp event_data({:harness_registered, harness, type}), do: %{harness: harness, type: type}

  defp event_data({:harness_disconnected, harness, reason}),
    do: %{harness: harness, reason: inspect(reason)}

  defp event_data({:harness_metadata, harness, metadata}) do
    %{
      harness: harness,
      color: Map.get(metadata, :color) || Map.get(metadata, "color"),
      custom_name: Map.get(metadata, :custom_name) || Map.get(metadata, "custom_name"),
      workspace: Map.get(metadata, :workspace) || Map.get(metadata, "workspace")
    }
  end

  defp event_data({:window_info, harness, window_id, window_name}) do
    %{harness: harness, window_id: window_id, window_name: window_name}
  end

  defp event_data({:agent_response_full, harness, content, role, streaming, complete}) do
    %{
      harness: harness,
      content: content,
      role: role,
      streaming: streaming,
      complete: complete
    }
  end

  defp event_data({:state_changed, path, value}), do: %{path: path, value: value}
  defp event_data({:state_value, path, value}), do: %{path: path, value: value}
  defp event_data({:agent_response, content, role}), do: %{content: content, role: role}
  defp event_data({:pong, _}), do: %{}
  defp event_data({:error, data}), do: data
  # Version events - data is a list of versions
  defp event_data({:versions_list, versions}) when is_list(versions), do: versions
  defp event_data({:versions_installed, versions}) when is_list(versions), do: versions
  defp event_data({:versions_stats, stats}) when is_map(stats), do: stats
  defp event_data({:version_download_started, version}), do: %{version: version}
  defp event_data({:launch_result, data}) when is_map(data), do: data

  defp event_data({:version_downloaded, version, path_or_status}) do
    %{version: version, path: to_string(path_or_status)}
  end

  defp event_data({:version_download_failed, version, reason}) do
    %{version: version, error: inspect(reason)}
  end

  defp event_data({:version_running, info}) when is_map(info), do: info
  # Version cleanup events
  defp event_data({:version_uninstalled, data}) when is_map(data), do: data
  defp event_data({:versions_batch_uninstalled, data}) when is_map(data), do: data
  defp event_data({:version_disk_usage, data}) when is_map(data), do: data
  defp event_data({:versions_disk_usage_all, data}) when is_map(data), do: data
  # Version update events
  defp event_data({:versions_refresh_result, data}) when is_map(data), do: data
  defp event_data({:versions_update_started, data}) when is_map(data), do: data
  defp event_data({:versions_updated, data}) when is_map(data), do: data
  defp event_data({:versions_update_failed, data}) when is_map(data), do: data
  defp event_data({:versions_updater_status, data}) when is_map(data), do: data
  # Workspace events
  defp event_data({:workspaces_list, workspaces}) when is_list(workspaces), do: workspaces
  defp event_data({:workspace_registered, workspace}) when is_map(workspace), do: workspace
  defp event_data({:workspace, workspace}) when is_map(workspace), do: workspace
  defp event_data({:workspace_version_recorded, data}) when is_map(data), do: data
  defp event_data({:workspace_pinned, data}) when is_map(data), do: data
  defp event_data({:workspace_git_stats, data}) when is_map(data), do: data
  defp event_data({:workspace_deleted, data}) when is_map(data), do: data
  # Auth events
  defp event_data({:auth_status, status}) when is_map(status), do: status
  defp event_data({:auth_statuses, statuses}) when is_list(statuses), do: statuses
  defp event_data({:auth_extracted, profile}) when is_map(profile), do: profile
  defp event_data({:auth_extract_failed, data}) when is_map(data), do: data
  defp event_data({:auth_applied, result}) when is_map(result), do: result
  defp event_data({:auth_apply_failed, data}) when is_map(data), do: data
  defp event_data({:auth_profiles, profiles}) when is_list(profiles), do: profiles
  defp event_data({_, data}) when is_map(data), do: data
  defp event_data(_), do: %{}
end
