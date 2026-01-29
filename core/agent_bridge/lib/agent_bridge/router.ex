defmodule AgentBridge.Router do
  @moduledoc """
  Routes messages to appropriate AI providers.

  Handles:
  - Provider selection (explicit or automatic)
  - Context enrichment
  - Rate limiting checks
  - Cost tracking
  - Retry logic
  """

  use GenServer
  require Logger

  alias AgentBridge.{Message, ProviderRegistry, ContextManager, CostTracker, RateLimiter}

  # Client API

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Route a message to a provider.
  """
  def route(message, opts \\ []) do
    GenServer.call(__MODULE__, {:route, message, opts}, 60_000)
  end

  @doc """
  Route a message and stream the response.
  """
  def stream(message, callback, opts \\ []) do
    GenServer.call(__MODULE__, {:stream, message, callback, opts}, 120_000)
  end

  # Server callbacks

  @impl true
  def init(_opts) do
    Logger.info("Router initialized")
    {:ok, %{default_provider: Application.get_env(:agent_bridge, :default_provider, :claude)}}
  end

  @impl true
  def handle_call({:route, message, opts}, _from, state) do
    result = do_route(message, opts, state)
    {:reply, result, state}
  end

  @impl true
  def handle_call({:stream, message, callback, opts}, _from, state) do
    result = do_stream(message, callback, opts, state)
    {:reply, result, state}
  end

  # Private functions

  defp do_route(message, opts, state) do
    with {:ok, provider_id} <- select_provider(opts, state),
         :ok <- check_rate_limit(provider_id),
         {:ok, provider} <- ProviderRegistry.acquire(provider_id),
         enriched_message <- enrich_message(message, opts),
         {:ok, response} <- send_to_provider(provider, enriched_message, opts) do

      # Track usage
      track_usage(provider_id, message, response)

      # Store in context
      if session = opts[:session] do
        ContextManager.add_message(session, message)
        ContextManager.add_message(session, response)
      end

      {:ok, response}
    end
  end

  defp do_stream(message, callback, opts, state) do
    with {:ok, provider_id} <- select_provider(opts, state),
         :ok <- check_rate_limit(provider_id),
         {:ok, provider} <- ProviderRegistry.acquire(provider_id),
         enriched_message <- enrich_message(message, opts) do

      # Store user message in context
      if session = opts[:session] do
        ContextManager.add_message(session, message)
      end

      # Track callback that also accumulates response
      accumulated = []
      tracking_callback = fn chunk ->
        callback.(chunk)
        [chunk | accumulated]
      end

      result = stream_from_provider(provider, enriched_message, tracking_callback, opts)

      # After streaming completes, track usage and store response
      case result do
        :ok ->
          full_response = combine_chunks(Enum.reverse(accumulated))
          track_usage(provider_id, message, full_response)

          if session = opts[:session] do
            ContextManager.add_message(session, full_response)
          end

          :ok

        error ->
          error
      end
    end
  end

  defp select_provider(opts, state) do
    provider_id = opts[:provider] || state.default_provider

    case ProviderRegistry.get(provider_id) do
      nil -> {:error, {:provider_not_found, provider_id}}
      _provider -> {:ok, provider_id}
    end
  end

  defp check_rate_limit(provider_id) do
    case RateLimiter.check(provider_id) do
      :ok -> :ok
      {:rate_limited, retry_after} -> {:error, {:rate_limited, retry_after}}
    end
  end

  defp enrich_message(message, opts) do
    # Add context from session if available
    case opts[:session] do
      nil ->
        message

      session ->
        history = ContextManager.get_history(session)
        %{message | metadata: Map.put(message.metadata, :history, history)}
    end
  end

  defp send_to_provider(provider, message, opts) do
    Logger.debug("Sending message to #{provider.id}")

    # Build messages list including history
    messages = build_messages(message, opts)

    case provider.module.send_message(provider.state, message, Keyword.put(opts, :messages, messages)) do
      {:ok, response} ->
        # Update provider state if needed
        {:ok, response}

      {:error, reason} = error ->
        ProviderRegistry.mark_failed(provider.id, reason)
        error
    end
  end

  defp stream_from_provider(provider, message, callback, opts) do
    Logger.debug("Streaming message from #{provider.id}")

    messages = build_messages(message, opts)

    case provider.module.stream_message(provider.state, message, callback, Keyword.put(opts, :messages, messages)) do
      :ok -> :ok
      {:error, reason} = error ->
        ProviderRegistry.mark_failed(provider.id, reason)
        error
    end
  end

  defp build_messages(message, opts) do
    history = get_in(message.metadata, [:history]) || []
    system_prompt = opts[:system_prompt]

    messages =
      if system_prompt do
        [Message.system(system_prompt) | history]
      else
        history
      end

    messages ++ [message]
  end

  defp track_usage(provider_id, message, response) do
    CostTracker.track(%{
      provider: provider_id,
      input_tokens: estimate_tokens(message.content),
      output_tokens: estimate_tokens(response.content),
      model: response.model,
      timestamp: DateTime.utc_now(),
    })
  end

  defp estimate_tokens(nil), do: 0
  defp estimate_tokens(content) when is_binary(content) do
    # Rough estimate: ~4 chars per token
    div(String.length(content), 4)
  end

  defp combine_chunks(chunks) do
    content = chunks
    |> Enum.map(& &1.content)
    |> Enum.reject(&is_nil/1)
    |> Enum.join("")

    # Take metadata from last chunk
    last = List.last(chunks) || %Message{}

    %Message{
      id: last.id,
      role: :assistant,
      content: content,
      provider: last.provider,
      model: last.model,
      tool_calls: last.tool_calls,
      metadata: last.metadata,
      created_at: DateTime.utc_now(),
    }
  end
end
