defmodule AgentBridge.RateLimiter do
  @moduledoc """
  Rate limiting for AI provider API calls.

  Prevents excessive API usage and respects provider limits.
  Uses a sliding window algorithm.
  """

  use GenServer
  require Logger

  @table :agent_bridge_rate_limits

  # Default rate limits (requests per minute)
  @default_limits %{
    claude: 60,
    openai: 60,
    ollama: :unlimited,
    cursor: 30,
  }

  # Client API

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Check if a request can proceed.

  Returns:
  - `:ok` - Request can proceed
  - `{:rate_limited, retry_after}` - Must wait
  """
  def check(provider) do
    GenServer.call(__MODULE__, {:check, provider})
  end

  @doc """
  Record a request for rate limiting.
  """
  def record(provider) do
    GenServer.cast(__MODULE__, {:record, provider})
  end

  @doc """
  Check and record in one operation.
  """
  def acquire(provider) do
    case check(provider) do
      :ok ->
        record(provider)
        :ok
      error ->
        error
    end
  end

  @doc """
  Set custom rate limit for a provider.
  """
  def set_limit(provider, requests_per_minute) do
    GenServer.call(__MODULE__, {:set_limit, provider, requests_per_minute})
  end

  @doc """
  Get current rate limit status.
  """
  def status(provider) do
    GenServer.call(__MODULE__, {:status, provider})
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    :ets.new(@table, [:named_table, :public, :bag])

    # Load configured limits
    configured = Application.get_env(:agent_bridge, :rate_limits, [])
    limits = Map.merge(@default_limits, Enum.into(configured, %{}))

    Logger.info("Rate limiter initialized")
    {:ok, %{limits: limits}}
  end

  @impl true
  def handle_call({:check, provider}, _from, state) do
    limit = Map.get(state.limits, provider, 60)

    if limit == :unlimited do
      {:reply, :ok, state}
    else
      # Get requests in the last minute
      cutoff = System.monotonic_time(:second) - 60
      requests = get_requests_since(provider, cutoff)

      if length(requests) >= limit do
        # Calculate retry after
        oldest = List.last(requests)
        retry_after = max(0, oldest + 60 - System.monotonic_time(:second))
        {:reply, {:rate_limited, retry_after}, state}
      else
        {:reply, :ok, state}
      end
    end
  end

  @impl true
  def handle_call({:set_limit, provider, limit}, _from, state) do
    new_limits = Map.put(state.limits, provider, limit)
    {:reply, :ok, %{state | limits: new_limits}}
  end

  @impl true
  def handle_call({:status, provider}, _from, state) do
    limit = Map.get(state.limits, provider, 60)
    cutoff = System.monotonic_time(:second) - 60
    requests = get_requests_since(provider, cutoff)

    status = %{
      provider: provider,
      limit: limit,
      current: length(requests),
      available: if(limit == :unlimited, do: :unlimited, else: max(0, limit - length(requests))),
    }

    {:reply, status, state}
  end

  @impl true
  def handle_cast({:record, provider}, state) do
    timestamp = System.monotonic_time(:second)
    :ets.insert(@table, {provider, timestamp})

    # Cleanup old entries (older than 2 minutes)
    cleanup_old_entries(provider)

    {:noreply, state}
  end

  # Private functions

  defp get_requests_since(provider, cutoff) do
    :ets.lookup(@table, provider)
    |> Enum.map(&elem(&1, 1))
    |> Enum.filter(&(&1 >= cutoff))
    |> Enum.sort()
  end

  defp cleanup_old_entries(provider) do
    cutoff = System.monotonic_time(:second) - 120

    :ets.lookup(@table, provider)
    |> Enum.filter(fn {_, ts} -> ts < cutoff end)
    |> Enum.each(fn entry -> :ets.delete_object(@table, entry) end)
  end
end
