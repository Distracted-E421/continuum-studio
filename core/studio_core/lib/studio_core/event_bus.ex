defmodule StudioCore.EventBus do
  @moduledoc """
  Pub/Sub event bus for Studio Core.

  Allows components to subscribe to events and receive broadcasts.
  Used primarily for:

  - State change notifications
  - Harness status updates
  - Agent messages
  - UI events

  ## Example

      # Subscribe to all events
      EventBus.subscribe()

      # Subscribe to specific event types
      EventBus.subscribe(:harness_status)

      # Broadcast an event
      EventBus.broadcast({:harness_status, "cursor", :running})

      # In your GenServer:
      def handle_info({:event, event}, state) do
        # Handle the event
      end
  """
  use GenServer
  require Logger

  @table :event_bus_subscribers

  # Client API

  def start_link(_opts) do
    GenServer.start_link(__MODULE__, [], name: __MODULE__)
  end

  @doc """
  Subscribe the calling process to events.

  Optionally specify a filter to only receive certain event types.
  """
  def subscribe(filter \\ :all) do
    GenServer.call(__MODULE__, {:subscribe, self(), filter})
  end

  @doc """
  Unsubscribe the calling process from events.
  """
  def unsubscribe do
    GenServer.call(__MODULE__, {:unsubscribe, self()})
  end

  @doc """
  Broadcast an event to all subscribers.
  """
  def broadcast(event) do
    GenServer.cast(__MODULE__, {:broadcast, event})
  end

  @doc """
  Get the count of current subscribers.
  """
  def subscriber_count do
    :ets.info(@table, :size)
  end

  # Server callbacks

  @impl true
  def init([]) do
    :ets.new(@table, [:named_table, :public, :bag])
    Logger.info("Event bus initialized")
    {:ok, %{}}
  end

  @impl true
  def handle_call({:subscribe, pid, filter}, _from, state) do
    ref = Process.monitor(pid)
    :ets.insert(@table, {pid, filter, ref})
    Logger.debug("Process #{inspect(pid)} subscribed with filter #{inspect(filter)}")
    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:unsubscribe, pid}, _from, state) do
    case :ets.lookup(@table, pid) do
      [{^pid, _filter, ref}] ->
        Process.demonitor(ref, [:flush])
        :ets.delete(@table, pid)
        Logger.debug("Process #{inspect(pid)} unsubscribed")
      [] ->
        :ok
    end
    {:reply, :ok, state}
  end

  @impl true
  def handle_cast({:broadcast, event}, state) do
    event_type = event_type(event)

    subscribers = :ets.tab2list(@table)

    for {pid, filter, _ref} <- subscribers do
      if matches_filter?(event_type, filter) do
        send(pid, {:event, event})
      end
    end

    {:noreply, state}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, pid, _reason}, state) do
    # Clean up when a subscriber dies
    case :ets.match_object(@table, {pid, :_, ref}) do
      [{^pid, _filter, ^ref}] ->
        :ets.delete(@table, pid)
        Logger.debug("Removed dead subscriber #{inspect(pid)}")
      [] ->
        :ok
    end
    {:noreply, state}
  end

  # Extract event type from event tuple
  defp event_type({type, _}) when is_atom(type), do: type
  defp event_type({type, _, _}) when is_atom(type), do: type
  defp event_type({type, _, _, _}) when is_atom(type), do: type
  defp event_type(_), do: :unknown

  # Check if event type matches filter
  defp matches_filter?(_type, :all), do: true
  defp matches_filter?(type, type), do: true
  defp matches_filter?(type, filters) when is_list(filters), do: type in filters
  defp matches_filter?(_, _), do: false
end
