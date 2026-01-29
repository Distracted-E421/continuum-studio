defmodule StudioCore.State do
  @moduledoc """
  Global state management for Studio Core.

  Uses ETS for fast, concurrent access to application state.
  State changes are broadcast via the EventBus.

  ## State Structure

  The state is organized as a nested map:

      %{
        harnesses: %{
          "cursor" => %{status: :running, pid: #PID<0.123.0>},
          "godot" => %{status: :stopped, pid: nil}
        },
        ui: %{
          theme: "dark",
          layout: %{...}
        },
        agents: %{
          "cursor_agent" => %{provider: :cursor, status: :idle}
        }
      }
  """
  use GenServer
  require Logger

  @table :studio_core_state
  @initial_state %{
    harnesses: %{},
    ui: %{theme: "dark"},
    agents: %{}
  }

  # Client API

  def start_link(_opts) do
    GenServer.start_link(__MODULE__, [], name: __MODULE__)
  end

  @doc """
  Get the entire state tree.
  """
  def get_all do
    case :ets.lookup(@table, :state) do
      [{:state, state}] -> state
      [] -> @initial_state
    end
  end

  @doc """
  Get a value at a specific path.

  ## Examples

      State.get([:harnesses, "cursor", :status])
      #=> :running
  """
  def get(path) when is_list(path) do
    get_in(get_all(), path)
  end

  @doc """
  Set a value at a specific path and broadcast the change.

  ## Examples

      State.set([:harnesses, "cursor", :status], :running)
  """
  def set(path, value) when is_list(path) do
    GenServer.call(__MODULE__, {:set, path, value})
  end

  @doc """
  Update a value at a specific path using a function.
  """
  def update(path, fun) when is_list(path) and is_function(fun, 1) do
    GenServer.call(__MODULE__, {:update, path, fun})
  end

  @doc """
  Delete a value at a specific path.
  """
  def delete(path) when is_list(path) do
    GenServer.call(__MODULE__, {:delete, path})
  end

  @doc """
  Register a harness in the state.
  """
  def register_harness(harness_id, opts \\ %{}) do
    set([:harnesses, harness_id], Map.merge(%{status: :registered, pid: nil}, opts))
  end

  @doc """
  Update harness status.
  """
  def set_harness_status(harness_id, status) do
    set([:harnesses, harness_id, :status], status)
  end

  # Server callbacks

  @impl true
  def init([]) do
    # Create ETS table
    :ets.new(@table, [:named_table, :public, read_concurrency: true])
    :ets.insert(@table, {:state, @initial_state})

    Logger.info("State manager initialized")
    {:ok, %{}}
  end

  @impl true
  def handle_call({:set, path, value}, _from, state) do
    current = get_all()
    new_state = put_in_path(current, path, value)
    :ets.insert(@table, {:state, new_state})

    # Broadcast the change
    StudioCore.EventBus.broadcast({:state_changed, path, value})

    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:update, path, fun}, _from, state) do
    current = get_all()
    old_value = get_in(current, path)
    new_value = fun.(old_value)
    new_state = put_in_path(current, path, new_value)
    :ets.insert(@table, {:state, new_state})

    # Broadcast the change
    StudioCore.EventBus.broadcast({:state_changed, path, new_value})

    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:delete, path}, _from, state) do
    current = get_all()
    new_state = delete_in_path(current, path)
    :ets.insert(@table, {:state, new_state})

    # Broadcast the change
    StudioCore.EventBus.broadcast({:state_deleted, path})

    {:reply, :ok, state}
  end

  # Helper to put a value in a nested path, creating intermediate maps
  defp put_in_path(map, [key], value) do
    Map.put(map || %{}, key, value)
  end

  defp put_in_path(map, [key | rest], value) do
    inner = Map.get(map || %{}, key, %{})
    Map.put(map || %{}, key, put_in_path(inner, rest, value))
  end

  # Helper to delete a value at a nested path
  defp delete_in_path(map, [key]) do
    Map.delete(map, key)
  end

  defp delete_in_path(map, [key | rest]) do
    case Map.get(map, key) do
      nil -> map
      inner -> Map.put(map, key, delete_in_path(inner, rest))
    end
  end
end
