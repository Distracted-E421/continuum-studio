defmodule AgentBridge.CostTracker do
  @moduledoc """
  Tracks usage and costs for AI provider calls.
  
  Features:
  - Token counting per provider
  - Cost estimation based on model pricing
  - Budget enforcement
  - Usage history
  """

  use GenServer
  require Logger

  @table :agent_bridge_costs

  # Pricing per 1M tokens (approximate, update as needed)
  @pricing %{
    claude: %{
      "claude-sonnet-4-20250514" => %{input: 3.0, output: 15.0},
      "claude-opus-4-20250514" => %{input: 15.0, output: 75.0},
      "claude-3-5-sonnet-20241022" => %{input: 3.0, output: 15.0},
      default: %{input: 3.0, output: 15.0},
    },
    openai: %{
      "gpt-4o" => %{input: 5.0, output: 15.0},
      "gpt-4o-mini" => %{input: 0.15, output: 0.60},
      "gpt-4-turbo" => %{input: 10.0, output: 30.0},
      default: %{input: 5.0, output: 15.0},
    },
    ollama: %{
      default: %{input: 0.0, output: 0.0},  # Local, no cost
    },
    cursor: %{
      default: %{input: 0.0, output: 0.0},  # Included in subscription
    },
  }

  # Client API

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Track usage for an API call.
  """
  def track(usage) do
    GenServer.cast(__MODULE__, {:track, usage})
  end

  @doc """
  Get usage statistics.
  
  Options:
  - `:provider` - Filter by provider
  - `:since` - Filter by time (DateTime)
  - `:until` - Filter by time (DateTime)
  """
  def get_usage(provider \\ nil, opts \\ []) do
    GenServer.call(__MODULE__, {:get_usage, provider, opts})
  end

  @doc """
  Get estimated cost for a provider.
  """
  def get_cost(provider, opts \\ []) do
    GenServer.call(__MODULE__, {:get_cost, provider, opts})
  end

  @doc """
  Set a budget limit for a provider.
  """
  def set_budget(provider, amount, period) do
    GenServer.call(__MODULE__, {:set_budget, provider, amount, period})
  end

  @doc """
  Check if within budget.
  """
  def check_budget(provider) do
    GenServer.call(__MODULE__, {:check_budget, provider})
  end

  @doc """
  Reset usage for a provider (e.g., monthly reset).
  """
  def reset_usage(provider) do
    GenServer.call(__MODULE__, {:reset_usage, provider})
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    :ets.new(@table, [:named_table, :public, :bag])
    Logger.info("Cost tracker initialized")
    {:ok, %{budgets: %{}}}
  end

  @impl true
  def handle_cast({:track, usage}, state) do
    # Store usage record
    record = Map.merge(usage, %{
      id: generate_id(),
      cost: calculate_cost(usage),
    })
    
    :ets.insert(@table, {usage.provider, record})
    
    Logger.debug("Tracked usage: #{usage.provider} - #{usage.input_tokens}in/#{usage.output_tokens}out")
    
    {:noreply, state}
  end

  @impl true
  def handle_call({:get_usage, provider, opts}, _from, state) do
    usage = 
      if provider do
        :ets.lookup(@table, provider) |> Enum.map(&elem(&1, 1))
      else
        :ets.tab2list(@table) |> Enum.map(&elem(&1, 1))
      end
    
    # Apply time filters
    usage = filter_by_time(usage, opts)
    
    # Aggregate
    summary = %{
      total_input_tokens: Enum.sum(Enum.map(usage, & &1.input_tokens)),
      total_output_tokens: Enum.sum(Enum.map(usage, & &1.output_tokens)),
      total_cost: Enum.sum(Enum.map(usage, & &1.cost)),
      request_count: length(usage),
      records: usage,
    }
    
    {:reply, summary, state}
  end

  @impl true
  def handle_call({:get_cost, provider, opts}, _from, state) do
    usage = :ets.lookup(@table, provider) |> Enum.map(&elem(&1, 1))
    usage = filter_by_time(usage, opts)
    
    total_cost = Enum.sum(Enum.map(usage, & &1.cost))
    {:reply, total_cost, state}
  end

  @impl true
  def handle_call({:set_budget, provider, amount, period}, _from, state) do
    budget = %{amount: amount, period: period, set_at: DateTime.utc_now()}
    new_budgets = Map.put(state.budgets, provider, budget)
    {:reply, :ok, %{state | budgets: new_budgets}}
  end

  @impl true
  def handle_call({:check_budget, provider}, _from, state) do
    case Map.get(state.budgets, provider) do
      nil ->
        {:reply, :ok, state}
      
      budget ->
        since = calculate_period_start(budget.period)
        current_cost = get_cost_since(provider, since)
        
        if current_cost >= budget.amount do
          {:reply, {:over_budget, current_cost, budget.amount}, state}
        else
          {:reply, {:ok, current_cost, budget.amount}, state}
        end
    end
  end

  @impl true
  def handle_call({:reset_usage, provider}, _from, state) do
    :ets.match_delete(@table, {provider, :_})
    {:reply, :ok, state}
  end

  # Private functions

  defp calculate_cost(%{provider: provider, model: model, input_tokens: input, output_tokens: output}) do
    pricing = get_in(@pricing, [provider, model]) || get_in(@pricing, [provider, :default]) || %{input: 0, output: 0}
    
    input_cost = (input / 1_000_000) * pricing.input
    output_cost = (output / 1_000_000) * pricing.output
    
    input_cost + output_cost
  end

  defp calculate_cost(_), do: 0.0

  defp filter_by_time(usage, opts) do
    since = opts[:since]
    until_time = opts[:until]
    
    usage
    |> Enum.filter(fn record ->
      after_since = is_nil(since) || DateTime.compare(record.timestamp, since) in [:gt, :eq]
      before_until = is_nil(until_time) || DateTime.compare(record.timestamp, until_time) in [:lt, :eq]
      after_since && before_until
    end)
  end

  defp calculate_period_start(:daily) do
    DateTime.utc_now() |> DateTime.add(-1, :day)
  end

  defp calculate_period_start(:weekly) do
    DateTime.utc_now() |> DateTime.add(-7, :day)
  end

  defp calculate_period_start(:monthly) do
    DateTime.utc_now() |> DateTime.add(-30, :day)
  end

  defp calculate_period_start(_), do: ~U[1970-01-01 00:00:00Z]

  defp get_cost_since(provider, since) do
    :ets.lookup(@table, provider)
    |> Enum.map(&elem(&1, 1))
    |> Enum.filter(fn r -> DateTime.compare(r.timestamp, since) in [:gt, :eq] end)
    |> Enum.sum_by(& &1.cost)
  end

  defp generate_id do
    :crypto.strong_rand_bytes(8) |> Base.encode16(case: :lower)
  end
end

