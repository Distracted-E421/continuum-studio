defmodule AgentBridge.CoreClient do
  @moduledoc """
  Client for communicating with Studio Core.
  
  Connects Agent Bridge to the main Studio Core application for:
  - Broadcasting agent responses to UI
  - Receiving requests from UI
  - State synchronization
  - Event coordination with harnesses
  
  ## Protocol
  
  Uses JSON-framed messages over Unix socket (same as UI ↔ Core):
  
      # Events (Agent Bridge → Core)
      {"event": "agent_response", "data": {"content": "...", "role": "assistant"}}
      {"event": "provider_status", "data": {"provider": "claude", "status": "ready"}}
      
      # Commands (Core → Agent Bridge)
      {"command": "agent_message", "params": {"text": "...", "provider": "claude"}}
      {"command": "health_check", "params": {}}
  """

  use GenServer
  require Logger

  @socket_path "/tmp/continuum-studio.sock"
  @reconnect_interval 5_000

  defmodule State do
    @moduledoc false
    defstruct [:socket, :buffer, connected: false]
  end

  # Client API

  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc "Check if connected to Core"
  def connected? do
    GenServer.call(__MODULE__, :connected?)
  end

  @doc "Send an agent response to Core (for UI broadcast)"
  def send_agent_response(content, role, opts \\ []) do
    GenServer.cast(__MODULE__, {:send_event, :agent_response, %{
      content: content,
      role: to_string(role),
      provider: opts[:provider],
      model: opts[:model],
    }})
  end

  @doc "Send provider status update"
  def send_provider_status(provider_id, status) do
    GenServer.cast(__MODULE__, {:send_event, :provider_status, %{
      provider: to_string(provider_id),
      status: to_string(status),
    }})
  end

  @doc "Send a generic event"
  def send_event(event_type, data) do
    GenServer.cast(__MODULE__, {:send_event, event_type, data})
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    Logger.info("AgentBridge CoreClient starting, will connect to #{@socket_path}")
    send(self(), :connect)
    {:ok, %State{buffer: <<>>}}
  end

  @impl true
  def handle_call(:connected?, _from, state) do
    {:reply, state.connected, state}
  end

  @impl true
  def handle_cast({:send_event, event_type, data}, state) do
    if state.connected do
      message = %{
        "event" => to_string(event_type),
        "data" => data
      }
      send_frame(state.socket, message)
    end
    {:noreply, state}
  end

  @impl true
  def handle_info(:connect, state) do
    case :gen_tcp.connect({:local, @socket_path}, 0, [
      :binary,
      packet: 4,
      active: true
    ]) do
      {:ok, socket} ->
        Logger.info("AgentBridge connected to Studio Core at #{@socket_path}")
        
        # Register ourselves with Core
        send_frame(socket, %{
          "event" => "agent_bridge_connected",
          "data" => %{
            "providers" => list_providers()
          }
        })
        
        {:noreply, %{state | socket: socket, connected: true}}
        
      {:error, reason} ->
        Logger.warning("AgentBridge failed to connect to Core: #{inspect(reason)}, retrying in #{@reconnect_interval}ms")
        Process.send_after(self(), :connect, @reconnect_interval)
        {:noreply, %{state | connected: false}}
    end
  end

  @impl true
  def handle_info({:tcp, _socket, data}, state) do
    case Jason.decode(data) do
      {:ok, %{"command" => command, "params" => params}} ->
        handle_command(command, params, state)
        
      {:ok, other} ->
        Logger.warning("Unknown message from Core: #{inspect(other)}")
        {:noreply, state}
        
      {:error, reason} ->
        Logger.error("Failed to decode message from Core: #{inspect(reason)}")
        {:noreply, state}
    end
  end

  @impl true
  def handle_info({:tcp_closed, _socket}, state) do
    Logger.warning("Connection to Core closed, reconnecting...")
    Process.send_after(self(), :connect, @reconnect_interval)
    {:noreply, %{state | socket: nil, connected: false}}
  end

  @impl true
  def handle_info({:tcp_error, _socket, reason}, state) do
    Logger.error("Socket error: #{inspect(reason)}, reconnecting...")
    Process.send_after(self(), :connect, @reconnect_interval)
    {:noreply, %{state | socket: nil, connected: false}}
  end

  # Command handlers

  defp handle_command("agent_message", %{"text" => text} = params, state) do
    provider = params["provider"]
    session = params["session"]
    
    Logger.info("Received agent message request from Core: #{String.slice(text, 0, 50)}...")
    
    # Route through AgentBridge
    Task.start(fn ->
      opts = []
      opts = if provider, do: [{:provider, String.to_atom(provider)} | opts], else: opts
      opts = if session, do: [{:session, session} | opts], else: opts
      
      case AgentBridge.chat(text, opts) do
        {:ok, response} ->
          send_agent_response(response.content, response.role,
            provider: response.provider,
            model: response.model
          )
          
        {:error, reason} ->
          send_event(:agent_error, %{
            message: inspect(reason),
            provider: provider,
          })
      end
    end)
    
    {:noreply, state}
  end

  defp handle_command("stream_message", %{"text" => text} = params, state) do
    provider = params["provider"]
    session = params["session"]
    
    Logger.info("Received streaming message request from Core: #{String.slice(text, 0, 50)}...")
    
    Task.start(fn ->
      opts = []
      opts = if provider, do: [{:provider, String.to_atom(provider)} | opts], else: opts
      opts = if session, do: [{:session, session} | opts], else: opts
      
      AgentBridge.stream(text, fn chunk ->
        send_event(:agent_chunk, %{
          content: chunk.content,
          role: "assistant",
          provider: to_string(chunk.provider),
          done: false,
        })
      end, opts)
      
      # Send completion marker
      send_event(:agent_chunk, %{done: true})
    end)
    
    {:noreply, state}
  end

  defp handle_command("health_check", _params, state) do
    providers = AgentBridge.list_providers()
    |> Enum.map(fn p -> %{id: p.id, status: p.status} end)
    
    send_event(:health_status, %{
      status: "ok",
      providers: providers,
    })
    
    {:noreply, state}
  end

  defp handle_command("list_providers", _params, state) do
    providers = AgentBridge.list_providers()
    |> Enum.map(fn p ->
      %{
        id: p.id,
        status: p.status,
        failures: p.failures,
      }
    end)
    
    send_event(:provider_list, %{providers: providers})
    {:noreply, state}
  end

  defp handle_command(command, params, state) do
    Logger.warning("Unknown command from Core: #{command} with #{inspect(params)}")
    {:noreply, state}
  end

  # Helpers

  defp send_frame(socket, message) do
    json = Jason.encode!(message)
    len = byte_size(json)
    :gen_tcp.send(socket, <<len::big-unsigned-integer-size(32), json::binary>>)
  end

  defp list_providers do
    AgentBridge.list_providers()
    |> Enum.map(fn p -> to_string(p.id) end)
  rescue
    _ -> []
  end
end

