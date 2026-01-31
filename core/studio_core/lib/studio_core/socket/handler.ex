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

  defmodule State do
    @moduledoc false
    defstruct [:socket, :subscribed]
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
    # Try JSON first (Rust UI), then fall back to ETF (Elixir clients)
    case decode_message(data) do
      {:ok, {:command, command, params}} ->
        handle_command(command, params, state)

      {:ok, {:event, event, data}} ->
        # Event from Synapsix (harness events)
        handle_synapsix_event(event, data, state)

      {:error, reason} ->
        Logger.warning("Failed to decode message: #{inspect(reason)}")
        send_error(state.socket, "Invalid message format")
        {:noreply, state}
    end
  end

  # Decode message - try JSON first, then ETF
  defp decode_message(data) do
    case Jason.decode(data) do
      {:ok, %{"command" => cmd, "params" => params}} ->
        command = String.to_existing_atom(cmd)
        params_atoms = atomize_keys(params)
        {:ok, {:command, command, params_atoms}}

      {:ok, %{"event" => event, "data" => event_data}} ->
        # Event from Synapsix
        event_atom = String.to_existing_atom(event)
        data_atoms = atomize_keys(event_data)
        {:ok, {:event, event_atom, data_atoms}}

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

  defp handle_command(:agent_message, %{text: text, provider: provider}, state) do
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
        send_event(state.socket, {:version_running, info})
      {:error, reason} ->
        send_error(state.socket, "Failed to run version: #{inspect(reason)}")
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
    StudioCore.EventBus.broadcast({:agent_response_full, harness, content, role, streaming, complete})

    {:noreply, state}
  end

  defp handle_synapsix_event(event, data, state) do
    Logger.warning("Unknown Synapsix event: #{inspect(event)} with #{inspect(data)}")
    {:noreply, state}
  end

  # Helpers

  defp send_event(socket, event) do
    # Send as JSON for Rust UI compatibility
    message = %{
      "event" => Atom.to_string(event_type(event)),
      "data" => event_data(event)
    }
    encoded = Jason.encode!(message)
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
  defp event_data({:harness_disconnected, harness, reason}), do: %{harness: harness, reason: inspect(reason)}
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
  defp event_data({_, data}) when is_map(data), do: data
  defp event_data(_), do: %{}
end
