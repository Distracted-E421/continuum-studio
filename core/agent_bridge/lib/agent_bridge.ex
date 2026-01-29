defmodule AgentBridge do
  @moduledoc """
  Agent Bridge - Unified AI Provider Interface
  
  The Agent Bridge provides a consistent interface for communicating with
  multiple AI providers (Claude, OpenAI, Ollama, Cursor) with:
  
  - **Unified Message Format**: Single `Message` struct for all providers
  - **Context Management**: Session-based conversation history
  - **Cost Tracking**: Token counting and budget enforcement
  - **Rate Limiting**: Respect provider limits
  - **Provider Registry**: Dynamic provider management
  - **Streaming Support**: First-class streaming responses
  
  ## Quick Start
  
      # Send a simple message
      {:ok, response} = AgentBridge.chat("What is 2+2?")
      
      # With provider selection
      {:ok, response} = AgentBridge.chat("Hello!", provider: :ollama)
      
      # With streaming
      AgentBridge.stream("Tell me a story", fn chunk ->
        IO.write(chunk.content)
      end)
      
      # With session context
      session = AgentBridge.new_session()
      {:ok, r1} = AgentBridge.chat("My name is Alice", session: session)
      {:ok, r2} = AgentBridge.chat("What's my name?", session: session)
  
  ## Configuration
  
      config :agent_bridge,
        default_provider: :claude,
        providers: [
          claude: [
            module: AgentBridge.Providers.Claude,
            api_key: System.get_env("ANTHROPIC_API_KEY"),
            model: "claude-sonnet-4-20250514",
          ],
          ollama: [
            module: AgentBridge.Providers.Ollama,
            base_url: "http://localhost:11434",
            model: "llama3.2",
          ],
        ]
  
  ## Architecture
  
      ┌─────────────────────────────────────────────────────────┐
      │                     Agent Bridge                        │
      ├─────────────────────────────────────────────────────────┤
      │  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  │
      │  │ Router  │  │ Context │  │  Cost   │  │   Rate    │  │
      │  │         │  │ Manager │  │ Tracker │  │  Limiter  │  │
      │  └────┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  │
      │       │            │            │              │        │
      │       └────────────┴────────────┴──────────────┘        │
      │                         │                               │
      │              ┌──────────┴──────────┐                    │
      │              │  Provider Registry   │                    │
      │              └──────────┬──────────┘                    │
      │       ┌────────────┬────┴────┬────────────┐             │
      │  ┌────┴────┐  ┌────┴────┐  ┌─┴──────┐  ┌──┴───┐        │
      │  │ Claude  │  │ OpenAI  │  │ Ollama │  │Cursor│        │
      │  │Provider │  │Provider │  │Provider│  │Harness│       │
      │  └─────────┘  └─────────┘  └────────┘  └──────┘        │
      └─────────────────────────────────────────────────────────┘
  """

  alias AgentBridge.{Message, Router, ContextManager, ProviderRegistry, CostTracker, RateLimiter}

  @doc """
  Send a chat message and get a response.
  
  ## Options
  
  - `:provider` - Provider to use (default: configured default)
  - `:session` - Session ID for context continuity
  - `:system` - System prompt to prepend
  - `:model` - Specific model to use
  - `:max_tokens` - Maximum tokens in response
  - `:tools` - List of tools/functions available
  - `:tool_choice` - Tool selection strategy
  """
  def chat(content, opts \\ []) when is_binary(content) do
    message = Message.user(content, opts)
    Router.route(message, opts)
  end

  @doc """
  Send a chat message and stream the response.
  
  The callback will be called with each chunk as it arrives.
  """
  def stream(content, callback, opts \\ []) when is_binary(content) and is_function(callback, 1) do
    message = Message.user(content, opts)
    Router.stream(message, callback, opts)
  end

  @doc """
  Create a new conversation session.
  """
  def new_session do
    UUID.uuid4()
  end

  @doc """
  Set system prompt for a session.
  """
  def set_system_prompt(session, prompt) do
    ContextManager.set_system_prompt(session, prompt)
  end

  @doc """
  Get conversation history for a session.
  """
  def get_history(session) do
    ContextManager.get_history(session)
  end

  @doc """
  Clear conversation history for a session.
  """
  def clear_history(session) do
    ContextManager.clear_history(session)
  end

  @doc """
  End a session and clean up resources.
  """
  def end_session(session) do
    ContextManager.clear_session(session)
  end

  @doc """
  List all active sessions.
  """
  def list_sessions do
    ContextManager.list_sessions()
  end

  # Provider Management

  @doc """
  Register a new provider.
  """
  def register_provider(id, module, config \\ []) do
    ProviderRegistry.register(id, module, config)
  end

  @doc """
  Unregister a provider.
  """
  def unregister_provider(id) do
    ProviderRegistry.unregister(id)
  end

  @doc """
  List all registered providers.
  """
  def list_providers do
    ProviderRegistry.list()
  end

  @doc """
  Get provider status.
  """
  def provider_status(id) do
    case ProviderRegistry.get(id) do
      nil -> {:error, :not_found}
      provider -> {:ok, %{id: provider.id, status: provider.status, failures: provider.failures}}
    end
  end

  @doc """
  Enable/disable a provider.
  """
  def set_provider_status(id, status) when status in [:ready, :disabled] do
    ProviderRegistry.set_status(id, status)
  end

  # Cost & Rate Limiting

  @doc """
  Get usage statistics.
  """
  def get_usage(provider \\ nil, opts \\ []) do
    CostTracker.get_usage(provider, opts)
  end

  @doc """
  Get estimated cost for a provider.
  """
  def get_cost(provider, opts \\ []) do
    CostTracker.get_cost(provider, opts)
  end

  @doc """
  Set a budget limit for a provider.
  """
  def set_budget(provider, amount, period \\ :monthly) do
    CostTracker.set_budget(provider, amount, period)
  end

  @doc """
  Check if within budget.
  """
  def check_budget(provider) do
    CostTracker.check_budget(provider)
  end

  @doc """
  Get rate limit status for a provider.
  """
  def rate_limit_status(provider) do
    RateLimiter.status(provider)
  end

  # Health & Info

  @doc """
  Health check for a provider.
  """
  def health_check(provider_id) do
    case ProviderRegistry.get(provider_id) do
      nil ->
        {:error, :not_found}
      
      provider ->
        if function_exported?(provider.module, :health_check, 1) do
          provider.module.health_check(provider.state)
        else
          :ok
        end
    end
  end

  @doc """
  List available models for a provider.
  """
  def list_models(provider_id) do
    case ProviderRegistry.get(provider_id) do
      nil ->
        {:error, :not_found}
      
      provider ->
        if function_exported?(provider.module, :list_models, 1) do
          provider.module.list_models(provider.state)
        else
          {:ok, []}
        end
    end
  end
end
