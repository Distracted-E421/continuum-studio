defmodule StudioCore.HarnessRegistry do
  @moduledoc """
  Registry for connected harnesses from Synapsix.

  Harnesses register themselves when they connect and can:
  - Receive commands from the UI
  - Send status updates
  - Forward agent messages

  ## Harness Types

  - `:cursor` - Cursor IDE harness
  - `:android_studio` - Android Studio harness
  - `:godot` - Godot Editor harness
  - `:custom` - Custom/plugin harnesses
  """
  use GenServer
  require Logger

  @table :harness_registry

  defmodule Harness do
    @moduledoc "Harness metadata"
    defstruct [
      :id,
      :type,
      :pid,
      :node,
      :status,
      :capabilities,
      :registered_at,
      :last_seen
    ]
  end

  # Client API

  def start_link(_opts) do
    GenServer.start_link(__MODULE__, [], name: __MODULE__)
  end

  @doc """
  Register a harness.

  ## Options

  - `:type` - Harness type (required)
  - `:capabilities` - List of capabilities (optional)
  - `:node` - Remote node name for distributed harnesses (optional)
  """
  def register(harness_id, opts \\ []) do
    GenServer.call(__MODULE__, {:register, harness_id, self(), opts})
  end

  @doc """
  Unregister a harness.
  """
  def unregister(harness_id) do
    GenServer.call(__MODULE__, {:unregister, harness_id})
  end

  @doc """
  Get harness info.
  """
  def get(harness_id) do
    case :ets.lookup(@table, harness_id) do
      [{^harness_id, harness}] -> {:ok, harness}
      [] -> {:error, :not_found}
    end
  end

  @doc """
  List all registered harnesses.
  """
  def list do
    :ets.tab2list(@table)
    |> Enum.map(fn {_id, harness} -> harness end)
  end

  @doc """
  List harnesses by type.
  """
  def list_by_type(type) do
    list() |> Enum.filter(&(&1.type == type))
  end

  @doc """
  Update harness status.
  """
  def update_status(harness_id, status) do
    GenServer.call(__MODULE__, {:update_status, harness_id, status})
  end

  @doc """
  Send a command to a harness.
  """
  def send_command(harness_id, command) do
    case get(harness_id) do
      {:ok, %Harness{pid: pid}} when is_pid(pid) ->
        send(pid, {:harness_command, command})
        :ok
      {:ok, _} ->
        {:error, :harness_not_running}
      {:error, _} = error ->
        error
    end
  end

  @doc """
  Heartbeat from a harness to update last_seen.
  """
  def heartbeat(harness_id) do
    GenServer.cast(__MODULE__, {:heartbeat, harness_id})
  end

  # Server callbacks

  @impl true
  def init([]) do
    :ets.new(@table, [:named_table, :public, read_concurrency: true])

    # Schedule periodic cleanup of dead harnesses
    schedule_cleanup()

    Logger.info("Harness registry initialized")
    {:ok, %{}}
  end

  @impl true
  def handle_call({:register, harness_id, pid, opts}, _from, state) do
    type = Keyword.get(opts, :type, :custom)
    capabilities = Keyword.get(opts, :capabilities, [])
    node = Keyword.get(opts, :node, node())

    harness = %Harness{
      id: harness_id,
      type: type,
      pid: pid,
      node: node,
      status: :registered,
      capabilities: capabilities,
      registered_at: DateTime.utc_now(),
      last_seen: DateTime.utc_now()
    }

    # Monitor the harness process
    Process.monitor(pid)

    :ets.insert(@table, {harness_id, harness})

    # Update global state
    StudioCore.State.register_harness(harness_id, %{
      type: type,
      status: :registered,
      capabilities: capabilities
    })

    # Broadcast the event
    StudioCore.EventBus.broadcast({:harness_registered, harness_id, type})

    Logger.info("Harness registered: #{harness_id} (#{type})")
    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:unregister, harness_id}, _from, state) do
    case :ets.lookup(@table, harness_id) do
      [{^harness_id, _harness}] ->
        :ets.delete(@table, harness_id)
        StudioCore.State.delete([:harnesses, harness_id])
        StudioCore.EventBus.broadcast({:harness_unregistered, harness_id})
        Logger.info("Harness unregistered: #{harness_id}")
        {:reply, :ok, state}
      [] ->
        {:reply, {:error, :not_found}, state}
    end
  end

  @impl true
  def handle_call({:update_status, harness_id, status}, _from, state) do
    case :ets.lookup(@table, harness_id) do
      [{^harness_id, harness}] ->
        updated = %{harness | status: status, last_seen: DateTime.utc_now()}
        :ets.insert(@table, {harness_id, updated})
        StudioCore.State.set_harness_status(harness_id, status)
        StudioCore.EventBus.broadcast({:harness_status, harness_id, status})
        {:reply, :ok, state}
      [] ->
        {:reply, {:error, :not_found}, state}
    end
  end

  @impl true
  def handle_cast({:heartbeat, harness_id}, state) do
    case :ets.lookup(@table, harness_id) do
      [{^harness_id, harness}] ->
        updated = %{harness | last_seen: DateTime.utc_now()}
        :ets.insert(@table, {harness_id, updated})
      [] ->
        :ok
    end
    {:noreply, state}
  end

  @impl true
  def handle_info({:DOWN, _ref, :process, pid, reason}, state) do
    # Find and remove the dead harness
    case :ets.match_object(@table, {:_, %Harness{pid: pid}}) do
      [{harness_id, _harness}] ->
        :ets.delete(@table, harness_id)
        StudioCore.State.set_harness_status(harness_id, :disconnected)
        StudioCore.EventBus.broadcast({:harness_disconnected, harness_id, reason})
        Logger.warning("Harness #{harness_id} disconnected: #{inspect(reason)}")
      [] ->
        :ok
    end
    {:noreply, state}
  end

  @impl true
  def handle_info(:cleanup, state) do
    # Remove harnesses that haven't sent a heartbeat in 30 seconds
    cutoff = DateTime.add(DateTime.utc_now(), -30, :second)

    for {harness_id, harness} <- :ets.tab2list(@table) do
      if DateTime.compare(harness.last_seen, cutoff) == :lt do
        Logger.warning("Harness #{harness_id} timed out")
        :ets.delete(@table, harness_id)
        StudioCore.State.set_harness_status(harness_id, :timeout)
        StudioCore.EventBus.broadcast({:harness_timeout, harness_id})
      end
    end

    schedule_cleanup()
    {:noreply, state}
  end

  defp schedule_cleanup do
    Process.send_after(self(), :cleanup, 10_000)
  end
end
