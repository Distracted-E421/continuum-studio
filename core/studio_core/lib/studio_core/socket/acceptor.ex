defmodule StudioCore.Socket.Acceptor do
  @moduledoc """
  Unix socket acceptor for UI connections.

  Listens on a Unix domain socket and spawns a handler
  for each connecting UI client.

  ## Protocol

  Messages are newline-delimited JSON:

      {"command": "versions_list", "params": {}}\n
      {"event": "versions_list", "data": [...]}\n

  This format is compatible with the Rust iced UI which uses
  read_line() for receiving messages.
  """
  use GenServer
  require Logger

  defmodule State do
    @moduledoc false
    defstruct [:socket_path, :listen_socket, :clients]
  end

  # Client API

  def start_link(socket_path) do
    GenServer.start_link(__MODULE__, socket_path, name: __MODULE__)
  end

  @doc """
  Get the number of connected clients.
  """
  def client_count do
    GenServer.call(__MODULE__, :client_count)
  end

  @doc """
  Broadcast a message to all connected clients.
  """
  def broadcast(message) do
    GenServer.cast(__MODULE__, {:broadcast, message})
  end

  # Server callbacks

  @impl true
  def init(socket_path) do
    # Remove old socket file if it exists
    File.rm(socket_path)

    # Create directory if needed
    socket_path |> Path.dirname() |> File.mkdir_p()

    case :gen_tcp.listen(0, [
      :binary,
      packet: :raw,  # Raw mode - we'll handle line parsing
      active: false,
      reuseaddr: true,
      ifaddr: {:local, String.to_charlist(socket_path)}
    ]) do
      {:ok, listen_socket} ->
        Logger.info("Socket acceptor listening on #{socket_path}")

        # Start accepting connections
        send(self(), :accept)

        {:ok, %State{
          socket_path: socket_path,
          listen_socket: listen_socket,
          clients: %{}
        }}

      {:error, reason} ->
        Logger.error("Failed to create socket: #{inspect(reason)}")
        {:stop, reason}
    end
  end

  @impl true
  def handle_call(:client_count, _from, state) do
    {:reply, map_size(state.clients), state}
  end

  @impl true
  def handle_cast({:broadcast, message}, state) do
    encoded = :erlang.term_to_binary(message)

    for {_pid, socket} <- state.clients do
      :gen_tcp.send(socket, encoded)
    end

    {:noreply, state}
  end

  @impl true
  def handle_info(:accept, state) do
    case :gen_tcp.accept(state.listen_socket, 100) do
      {:ok, client_socket} ->
        # Spawn a handler for this client
        {:ok, pid} = StudioCore.Socket.Handler.start_link(client_socket)
        :gen_tcp.controlling_process(client_socket, pid)

        # Track the client
        new_clients = Map.put(state.clients, pid, client_socket)
        Process.monitor(pid)

        Logger.info("Client connected (total: #{map_size(new_clients)})")

        # Continue accepting
        send(self(), :accept)
        {:noreply, %{state | clients: new_clients}}

      {:error, :timeout} ->
        # No connection waiting, try again
        send(self(), :accept)
        {:noreply, state}

      {:error, :closed} ->
        Logger.warning("Listen socket closed")
        {:stop, :normal, state}

      {:error, reason} ->
        Logger.error("Accept error: #{inspect(reason)}")
        send(self(), :accept)
        {:noreply, state}
    end
  end

  @impl true
  def handle_info({:DOWN, _ref, :process, pid, _reason}, state) do
    new_clients = Map.delete(state.clients, pid)
    Logger.info("Client disconnected (total: #{map_size(new_clients)})")
    {:noreply, %{state | clients: new_clients}}
  end

  @impl true
  def handle_info({:tcp_closed, socket}, state) do
    # Find and remove the client associated with this socket
    new_clients = state.clients
      |> Enum.reject(fn {_pid, client_socket} -> client_socket == socket end)
      |> Enum.into(%{})
    Logger.debug("TCP socket closed, cleaned up clients (total: #{map_size(new_clients)})")
    {:noreply, %{state | clients: new_clients}}
  end

  @impl true
  def handle_info({:tcp_error, socket, reason}, state) do
    Logger.warning("TCP socket error: #{inspect(reason)}")
    # Handle similar to tcp_closed
    new_clients = state.clients
      |> Enum.reject(fn {_pid, client_socket} -> client_socket == socket end)
      |> Enum.into(%{})
    {:noreply, %{state | clients: new_clients}}
  end

  @impl true
  def terminate(_reason, state) do
    if state.listen_socket do
      :gen_tcp.close(state.listen_socket)
    end
    File.rm(state.socket_path)
    :ok
  end
end
