defmodule AgentBridge.ContextManager do
  @moduledoc """
  Manages conversation context and history for sessions.
  
  Stores:
  - Conversation history per session
  - System prompts
  - Tool results
  - Artifacts generated during sessions
  """

  use GenServer
  require Logger

  @table :agent_bridge_context
  @max_history_size 100

  # Client API

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Get conversation history for a session.
  """
  def get_history(session_id) do
    case :ets.lookup(@table, {:history, session_id}) do
      [{_, history}] -> history
      [] -> []
    end
  end

  @doc """
  Add a message to session history.
  """
  def add_message(session_id, message) do
    GenServer.cast(__MODULE__, {:add_message, session_id, message})
  end

  @doc """
  Set system prompt for a session.
  """
  def set_system_prompt(session_id, prompt) do
    GenServer.call(__MODULE__, {:set_system_prompt, session_id, prompt})
  end

  @doc """
  Get system prompt for a session.
  """
  def get_system_prompt(session_id) do
    case :ets.lookup(@table, {:system_prompt, session_id}) do
      [{_, prompt}] -> prompt
      [] -> nil
    end
  end

  @doc """
  Add a tool result to session context.
  """
  def add_tool_result(session_id, tool_call_id, result) do
    GenServer.cast(__MODULE__, {:add_tool_result, session_id, tool_call_id, result})
  end

  @doc """
  Get tool results for a session.
  """
  def get_tool_results(session_id) do
    case :ets.lookup(@table, {:tool_results, session_id}) do
      [{_, results}] -> results
      [] -> []
    end
  end

  @doc """
  Clear history for a session.
  """
  def clear_history(session_id) do
    GenServer.call(__MODULE__, {:clear_history, session_id})
  end

  @doc """
  Clear all data for a session.
  """
  def clear_session(session_id) do
    GenServer.call(__MODULE__, {:clear_session, session_id})
  end

  @doc """
  List all active sessions.
  """
  def list_sessions do
    :ets.match(@table, {{:history, :"$1"}, :_})
    |> List.flatten()
    |> Enum.uniq()
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    :ets.new(@table, [:named_table, :public, read_concurrency: true])
    Logger.info("Context manager initialized")
    {:ok, %{}}
  end

  @impl true
  def handle_cast({:add_message, session_id, message}, state) do
    history = get_history(session_id)
    
    # Add message and trim to max size
    new_history = Enum.take([message | history], @max_history_size)
    |> Enum.reverse()
    
    :ets.insert(@table, {{:history, session_id}, new_history})
    
    {:noreply, state}
  end

  @impl true
  def handle_cast({:add_tool_result, session_id, tool_call_id, result}, state) do
    results = get_tool_results(session_id)
    new_results = [{tool_call_id, result, DateTime.utc_now()} | results]
    :ets.insert(@table, {{:tool_results, session_id}, new_results})
    
    {:noreply, state}
  end

  @impl true
  def handle_call({:set_system_prompt, session_id, prompt}, _from, state) do
    :ets.insert(@table, {{:system_prompt, session_id}, prompt})
    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:clear_history, session_id}, _from, state) do
    :ets.delete(@table, {:history, session_id})
    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:clear_session, session_id}, _from, state) do
    :ets.delete(@table, {:history, session_id})
    :ets.delete(@table, {:system_prompt, session_id})
    :ets.delete(@table, {:tool_results, session_id})
    {:reply, :ok, state}
  end
end

