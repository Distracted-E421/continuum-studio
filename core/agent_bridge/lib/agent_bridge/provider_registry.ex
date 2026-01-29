defmodule AgentBridge.ProviderRegistry do
  @moduledoc """
  Registry for AI providers.
  
  Manages provider lifecycle:
  - Registration/deregistration
  - Health checking
  - Load balancing
  - Failover
  """

  use GenServer
  require Logger

  @table :agent_bridge_providers

  defmodule Provider do
    @moduledoc "Provider metadata"
    defstruct [
      :id,
      :module,
      :state,
      :config,
      :priority,
      status: :ready,
      failures: 0,
      last_failure: nil,
      last_success: nil,
    ]
  end

  # Client API

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Register a provider.
  """
  def register(id, module, config \\ []) do
    GenServer.call(__MODULE__, {:register, id, module, config})
  end

  @doc """
  Unregister a provider.
  """
  def unregister(id) do
    GenServer.call(__MODULE__, {:unregister, id})
  end

  @doc """
  Get a provider by ID.
  """
  def get(id) do
    case :ets.lookup(@table, id) do
      [{^id, provider}] -> provider
      [] -> nil
    end
  end

  @doc """
  List all providers.
  """
  def list do
    :ets.tab2list(@table)
    |> Enum.map(&elem(&1, 1))
  end

  @doc """
  List providers by status.
  """
  def list_ready do
    list() |> Enum.filter(&(&1.status == :ready))
  end

  @doc """
  Acquire a provider for use.
  """
  def acquire(id) do
    case get(id) do
      nil ->
        {:error, :not_found}
      
      %Provider{status: :disabled} = provider ->
        {:error, {:disabled, provider}}
      
      %Provider{status: :failed} = provider ->
        # Check if enough time has passed for retry
        if can_retry?(provider) do
          {:ok, provider}
        else
          {:error, {:failed, provider}}
        end
      
      provider ->
        {:ok, provider}
    end
  end

  @doc """
  Mark a provider as failed.
  """
  def mark_failed(id, reason) do
    GenServer.call(__MODULE__, {:mark_failed, id, reason})
  end

  @doc """
  Mark a provider as successful.
  """
  def mark_success(id) do
    GenServer.cast(__MODULE__, {:mark_success, id})
  end

  @doc """
  Update provider config.
  """
  def update_config(id, config) do
    GenServer.call(__MODULE__, {:update_config, id, config})
  end

  @doc """
  Enable/disable a provider.
  """
  def set_status(id, status) when status in [:ready, :disabled] do
    GenServer.call(__MODULE__, {:set_status, id, status})
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    :ets.new(@table, [:named_table, :public, read_concurrency: true])
    
    Logger.info("Provider registry initialized")
    {:ok, %{}, {:continue, :load_configured_providers}}
  end

  @impl true
  def handle_continue(:load_configured_providers, state) do
    # Load providers from config
    providers = Application.get_env(:agent_bridge, :providers, [])
    
    for {id, config} <- providers do
      module = config[:module]
      if module do
        do_register(id, module, config)
      end
    end
    
    {:noreply, state}
  end

  @impl true
  def handle_call({:register, id, module, config}, _from, state) do
    result = do_register(id, module, config)
    {:reply, result, state}
  end

  @impl true
  def handle_call({:unregister, id}, _from, state) do
    # Cleanup provider state if needed
    case get(id) do
      %Provider{module: module, state: provider_state} ->
        if function_exported?(module, :terminate, 1) do
          module.terminate(provider_state)
        end
      _ ->
        :ok
    end
    
    :ets.delete(@table, id)
    Logger.info("Provider unregistered: #{id}")
    {:reply, :ok, state}
  end

  @impl true
  def handle_call({:mark_failed, id, reason}, _from, state) do
    case get(id) do
      nil ->
        {:reply, {:error, :not_found}, state}
      
      provider ->
        new_failures = provider.failures + 1
        new_status = if new_failures >= 3, do: :failed, else: provider.status
        
        updated = %{provider |
          failures: new_failures,
          status: new_status,
          last_failure: DateTime.utc_now(),
        }
        
        :ets.insert(@table, {id, updated})
        Logger.warning("Provider #{id} marked failed (#{new_failures} failures): #{inspect(reason)}")
        
        {:reply, :ok, state}
    end
  end

  @impl true
  def handle_call({:update_config, id, new_config}, _from, state) do
    case get(id) do
      nil ->
        {:reply, {:error, :not_found}, state}
      
      provider ->
        merged_config = Map.merge(provider.config, Enum.into(new_config, %{}))
        
        # Re-initialize provider with new config
        case provider.module.init(merged_config) do
          {:ok, new_state} ->
            updated = %{provider |
              config: merged_config,
              state: new_state,
            }
            :ets.insert(@table, {id, updated})
            {:reply, :ok, state}
          
          {:error, reason} ->
            {:reply, {:error, reason}, state}
        end
    end
  end

  @impl true
  def handle_call({:set_status, id, status}, _from, state) do
    case get(id) do
      nil ->
        {:reply, {:error, :not_found}, state}
      
      provider ->
        updated = %{provider | status: status, failures: 0}
        :ets.insert(@table, {id, updated})
        Logger.info("Provider #{id} status set to #{status}")
        {:reply, :ok, state}
    end
  end

  @impl true
  def handle_cast({:mark_success, id}, state) do
    case get(id) do
      nil ->
        :ok
      
      provider ->
        updated = %{provider |
          failures: 0,
          status: :ready,
          last_success: DateTime.utc_now(),
        }
        :ets.insert(@table, {id, updated})
    end
    
    {:noreply, state}
  end

  # Private functions

  defp do_register(id, module, config) do
    config_map = Enum.into(config, %{})
    
    case module.init(config_map) do
      {:ok, provider_state} ->
        provider = %Provider{
          id: id,
          module: module,
          state: provider_state,
          config: config_map,
          priority: config_map[:priority] || 0,
          status: :ready,
        }
        
        :ets.insert(@table, {id, provider})
        Logger.info("Provider registered: #{id} (#{module})")
        {:ok, provider}
      
      {:error, reason} ->
        Logger.error("Failed to initialize provider #{id}: #{inspect(reason)}")
        {:error, reason}
    end
  end

  defp can_retry?(%Provider{last_failure: nil}), do: true
  defp can_retry?(%Provider{last_failure: last_failure}) do
    # Wait 30 seconds between retries
    DateTime.diff(DateTime.utc_now(), last_failure, :second) >= 30
  end
end
