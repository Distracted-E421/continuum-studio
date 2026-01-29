defmodule AgentBridge.Middleware do
  @moduledoc """
  Middleware pipeline for Agent Bridge.
  
  Middleware can intercept, modify, or block messages before they're
  sent to providers and responses before they're returned.
  
  ## Implementing Middleware
  
      defmodule MyMiddleware do
        @behaviour AgentBridge.Middleware
        
        @impl true
        def call(message, opts, next) do
          # Pre-processing
          modified_message = transform(message)
          
          # Call next middleware/provider
          case next.(modified_message, opts) do
            {:ok, response} ->
              # Post-processing
              {:ok, transform_response(response)}
            
            error ->
              error
          end
        end
      end
  
  ## Built-in Middleware
  
  - `AgentBridge.Middleware.Logger` - Log all messages
  - `AgentBridge.Middleware.Sanitizer` - Remove sensitive data
  - `AgentBridge.Middleware.Validator` - Validate message content
  - `AgentBridge.Middleware.Telemetry` - Emit telemetry events
  """

  alias AgentBridge.Message

  @type next :: (Message.t(), keyword() -> {:ok, Message.t()} | {:error, term()})

  @callback call(message :: Message.t(), opts :: keyword(), next :: next()) ::
    {:ok, Message.t()} | {:error, term()}

  @doc """
  Run a message through a middleware pipeline.
  """
  def run(message, opts, middlewares, final) do
    pipeline = build_pipeline(middlewares, final)
    pipeline.(message, opts)
  end

  defp build_pipeline([], final) do
    fn message, opts -> final.(message, opts) end
  end

  defp build_pipeline([middleware | rest], final) do
    next = build_pipeline(rest, final)
    fn message, opts -> middleware.call(message, opts, next) end
  end
end

defmodule AgentBridge.Middleware.Logger do
  @moduledoc """
  Logs all messages and responses.
  """

  @behaviour AgentBridge.Middleware

  require Logger

  @impl true
  def call(message, opts, next) do
    Logger.debug("AgentBridge request: #{inspect(message, limit: 200)}")
    start_time = System.monotonic_time(:millisecond)
    
    result = next.(message, opts)
    
    elapsed = System.monotonic_time(:millisecond) - start_time
    
    case result do
      {:ok, response} ->
        Logger.debug("AgentBridge response (#{elapsed}ms): #{inspect(response, limit: 200)}")
        {:ok, response}
      
      {:error, reason} ->
        Logger.warning("AgentBridge error (#{elapsed}ms): #{inspect(reason)}")
        {:error, reason}
    end
  end
end

defmodule AgentBridge.Middleware.Sanitizer do
  @moduledoc """
  Removes or masks sensitive data from messages.
  """

  @behaviour AgentBridge.Middleware

  @sensitive_patterns [
    ~r/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b/,  # Email
    ~r/\b(?:\d{4}[-\s]?){3}\d{4}\b/,  # Credit card
    ~r/\bsk-[A-Za-z0-9]{32,}\b/,  # API key patterns
    ~r/\bghp_[A-Za-z0-9]{36}\b/,  # GitHub token
  ]

  @impl true
  def call(message, opts, next) do
    sanitized = sanitize_content(message)
    
    case next.(sanitized, opts) do
      {:ok, response} ->
        # Also sanitize response
        {:ok, sanitize_content(response)}
      
      error ->
        error
    end
  end

  defp sanitize_content(%{content: nil} = msg), do: msg
  defp sanitize_content(%{content: content} = msg) do
    sanitized = Enum.reduce(@sensitive_patterns, content, fn pattern, acc ->
      Regex.replace(pattern, acc, "[REDACTED]")
    end)
    %{msg | content: sanitized}
  end
end

defmodule AgentBridge.Middleware.Validator do
  @moduledoc """
  Validates messages before sending.
  """

  @behaviour AgentBridge.Middleware

  @max_content_length 100_000

  @impl true
  def call(message, opts, next) do
    with :ok <- validate_content(message),
         :ok <- validate_role(message) do
      next.(message, opts)
    end
  end

  defp validate_content(%{content: nil}), do: :ok
  defp validate_content(%{content: content}) when is_binary(content) do
    if String.length(content) > @max_content_length do
      {:error, {:content_too_long, String.length(content), @max_content_length}}
    else
      :ok
    end
  end
  defp validate_content(_), do: {:error, :invalid_content_type}

  defp validate_role(%{role: role}) when role in [:system, :user, :assistant, :tool], do: :ok
  defp validate_role(%{role: role}), do: {:error, {:invalid_role, role}}
  defp validate_role(_), do: {:error, :missing_role}
end

defmodule AgentBridge.Middleware.Telemetry do
  @moduledoc """
  Emits telemetry events for monitoring.
  """

  @behaviour AgentBridge.Middleware

  @impl true
  def call(message, opts, next) do
    metadata = %{
      provider: opts[:provider],
      session: opts[:session],
      message_role: message.role,
    }
    
    :telemetry.span(
      [:agent_bridge, :request],
      metadata,
      fn ->
        result = next.(message, opts)
        
        case result do
          {:ok, response} ->
            {result, Map.merge(metadata, %{
              status: :ok,
              response_role: response.role,
              model: response.model,
            })}
          
          {:error, reason} ->
            {result, Map.merge(metadata, %{
              status: :error,
              error: reason,
            })}
        end
      end
    )
  end
end

defmodule AgentBridge.Middleware.ContentFilter do
  @moduledoc """
  Filters content based on configurable rules.
  """

  @behaviour AgentBridge.Middleware

  @impl true
  def call(message, opts, next) do
    rules = opts[:content_filter_rules] || []
    
    case check_rules(message.content, rules) do
      :ok ->
        next.(message, opts)
      
      {:blocked, rule} ->
        {:error, {:content_blocked, rule}}
    end
  end

  defp check_rules(nil, _rules), do: :ok
  defp check_rules(content, rules) do
    Enum.find_value(rules, :ok, fn rule ->
      if rule_matches?(content, rule) do
        {:blocked, rule}
      else
        nil
      end
    end)
  end

  defp rule_matches?(content, %{pattern: pattern}) when is_binary(pattern) do
    String.contains?(content, pattern)
  end

  defp rule_matches?(content, %{regex: regex}) do
    Regex.match?(regex, content)
  end

  defp rule_matches?(_, _), do: false
end

