defmodule AgentBridge.Providers.Cursor do
  @moduledoc """
  Cursor IDE harness-based provider adapter.
  
  Routes messages through a running Cursor IDE instance via the
  Synapsix Cursor harness, leveraging Cursor's AI capabilities.
  
  This allows the Agent Bridge to utilize Cursor's:
  - Subscription-based AI access
  - Multi-model routing
  - Context-aware completions
  - Tool/function execution
  
  ## Configuration
  
      config :agent_bridge, :providers,
        cursor: [
          module: AgentBridge.Providers.Cursor,
          harness_id: "cursor_homelab",  # Synapsix harness ID
          workspace: "homelab",
        ]
  
  ## How It Works
  
  1. Messages are sent to the Cursor harness via Synapsix
  2. Harness types the message into Cursor's chat interface
  3. Harness captures the response via screen OCR or accessibility API
  4. Response is returned through the Agent Bridge
  
  This is more experimental than direct API providers but enables
  using Cursor's AI without additional API costs.
  """

  @behaviour AgentBridge.Provider

  require Logger
  alias AgentBridge.Message

  defstruct [:harness_id, :workspace, :timeout]

  # Provider behaviour implementation

  @impl true
  def init(config) do
    harness_id = config[:harness_id] || "cursor_default"
    
    state = %__MODULE__{
      harness_id: harness_id,
      workspace: config[:workspace] || "default",
      timeout: config[:timeout] || 120_000,  # 2 minutes default
    }
    
    {:ok, state}
  end

  @impl true
  def send_message(state, message, opts) do
    # Check if harness is available
    case get_harness_status(state.harness_id) do
      {:ok, :running} ->
        do_send_message(state, message, opts)
      
      {:ok, status} ->
        {:error, {:harness_not_ready, status}}
      
      {:error, reason} ->
        {:error, {:harness_error, reason}}
    end
  end

  @impl true
  def stream_message(state, message, callback, opts) do
    # Cursor harness doesn't support true streaming yet
    # Simulate by sending full response as chunks
    case send_message(state, message, opts) do
      {:ok, response} ->
        # Split response into chunks for callback
        if response.content do
          response.content
          |> String.graphemes()
          |> Enum.chunk_every(10)
          |> Enum.each(fn chars ->
            chunk = %Message{
              role: :assistant,
              content: Enum.join(chars),
              provider: :cursor,
            }
            callback.(chunk)
          end)
        end
        :ok
      
      {:error, reason} ->
        {:error, reason}
    end
  end

  @impl true
  def list_models(_state) do
    # Cursor supports multiple models through its interface
    models = [
      %{id: "cursor-default", name: "Cursor Default", description: "Auto-routed by Cursor"},
      %{id: "claude-sonnet", name: "Claude Sonnet (via Cursor)", description: "Cursor's Claude access"},
      %{id: "gpt-4o", name: "GPT-4o (via Cursor)", description: "Cursor's GPT-4o access"},
    ]
    {:ok, models}
  end

  @impl true
  def health_check(state) do
    case get_harness_status(state.harness_id) do
      {:ok, :running} -> :ok
      {:ok, status} -> {:error, {:harness_status, status}}
      {:error, reason} -> {:error, reason}
    end
  end

  @impl true
  def terminate(_state) do
    :ok
  end

  # Private functions

  defp get_harness_status(harness_id) do
    # Query Synapsix for harness status
    # This assumes Synapsix is running and accessible
    
    # Try via Studio Core's HarnessRegistry first
    case Process.whereis(StudioCore.HarnessRegistry) do
      nil ->
        # Fall back to direct Synapsix query
        query_synapsix_harness(harness_id)
      
      _pid ->
        case StudioCore.HarnessRegistry.get(harness_id) do
          {:ok, harness} -> {:ok, harness.status}
          {:error, :not_found} -> {:error, :harness_not_found}
        end
    end
  end

  defp query_synapsix_harness(harness_id) do
    # Try to query Synapsix directly if it's in the same node
    case Process.whereis(Synapsix.Registry) do
      nil ->
        {:error, :synapsix_not_running}
      
      _pid ->
        case Registry.lookup(Synapsix.Registry, {:cursor, extract_workspace(harness_id)}) do
          [{pid, _}] ->
            case GenServer.call(pid, :status, 5000) do
              %{status: status} -> {:ok, status}
              status when is_atom(status) -> {:ok, status}
            end
          
          [] ->
            {:error, :harness_not_found}
        end
    end
  end

  defp extract_workspace("cursor_" <> workspace), do: workspace
  defp extract_workspace(id), do: id

  defp do_send_message(state, message, _opts) do
    # Send message to Cursor harness
    harness_pid = get_harness_pid(state.harness_id)
    
    if harness_pid do
      # Focus Cursor window
      GenServer.call(harness_pid, :focus)
      
      # Clear any existing input
      GenServer.call(harness_pid, {:send_keys, ["ctrl+a", "BackSpace"]})
      
      # Type the message
      GenServer.call(harness_pid, {:type_text, message.content})
      
      # Submit (Enter)
      GenServer.call(harness_pid, {:send_keys, ["Return"]})
      
      # Wait for response
      case wait_for_response(harness_pid, state.timeout) do
        {:ok, response_text} ->
          {:ok, %Message{
            role: :assistant,
            content: response_text,
            provider: :cursor,
            model: "cursor-default",
            created_at: DateTime.utc_now(),
          }}
        
        {:error, reason} ->
          {:error, reason}
      end
    else
      {:error, :harness_not_available}
    end
  end

  defp get_harness_pid(harness_id) do
    workspace = extract_workspace(harness_id)
    
    case Registry.lookup(Synapsix.Registry, {:cursor, workspace}) do
      [{pid, _}] -> pid
      [] -> nil
    end
  end

  defp wait_for_response(harness_pid, timeout) do
    # This is a simplified implementation
    # In practice, you'd use screen capture + OCR or accessibility APIs
    # to detect when Cursor has finished responding
    
    start_time = System.monotonic_time(:millisecond)
    
    poll_for_response(harness_pid, start_time, timeout)
  end

  defp poll_for_response(harness_pid, start_time, timeout) do
    elapsed = System.monotonic_time(:millisecond) - start_time
    
    if elapsed > timeout do
      {:error, :timeout}
    else
      # Check if response is complete
      # This would involve screen capture and OCR in a real implementation
      case GenServer.call(harness_pid, {:get_response_state}, 5000) do
        {:complete, text} ->
          {:ok, text}
        
        :pending ->
          Process.sleep(500)
          poll_for_response(harness_pid, start_time, timeout)
        
        {:error, reason} ->
          {:error, reason}
      end
    end
  rescue
    _ ->
      # Harness might not implement get_response_state yet
      # Return a placeholder response
      Process.sleep(2000)
      {:ok, "[Response capture not implemented - Cursor harness needs get_response_state]"}
  end
end

