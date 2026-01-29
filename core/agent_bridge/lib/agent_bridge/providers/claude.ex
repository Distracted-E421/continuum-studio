defmodule AgentBridge.Providers.Claude do
  @moduledoc """
  Anthropic Claude provider adapter.
  
  Supports:
  - Claude 4 Opus, Sonnet
  - Claude 3.5 Sonnet
  - Tool/function calling
  - Streaming responses
  
  ## Configuration
  
      config :agent_bridge, :providers,
        claude: [
          module: AgentBridge.Providers.Claude,
          api_key: System.get_env("ANTHROPIC_API_KEY"),
          model: "claude-sonnet-4-20250514",
          max_tokens: 4096,
        ]
  """

  @behaviour AgentBridge.Provider

  require Logger
  alias AgentBridge.{Message, HTTP}

  @api_url "https://api.anthropic.com/v1/messages"
  @api_version "2023-06-01"

  defstruct [:api_key, :model, :max_tokens, :default_system, :client]

  # Provider behaviour implementation

  @impl true
  def init(config) do
    api_key = config[:api_key] || System.get_env("ANTHROPIC_API_KEY")
    
    if is_nil(api_key) or api_key == "" do
      {:error, :missing_api_key}
    else
      # Create a Req client with Claude-specific configuration
      client = HTTP.new_client(@api_url,
        headers: [
          {"x-api-key", api_key},
          {"anthropic-version", @api_version},
          {"content-type", "application/json"},
        ],
        timeout: 120_000
      )
      
      state = %__MODULE__{
        api_key: api_key,
        model: config[:model] || "claude-sonnet-4-20250514",
        max_tokens: config[:max_tokens] || 4096,
        default_system: config[:system],
        client: client,
      }
      {:ok, state}
    end
  end

  @impl true
  def send_message(state, message, opts) do
    messages = opts[:messages] || [message]
    
    body = build_request_body(state, messages, opts)
    
    case Req.post(state.client, json: body) do
      {:ok, %Req.Response{status: 200, body: response}} ->
        {:ok, Message.from_provider_response(response, :claude)}
      
      {:ok, %Req.Response{status: status, body: error}} ->
        Logger.error("Claude API error (#{status}): #{inspect(error)}")
        {:error, {:api_error, status, error}}
      
      {:error, reason} ->
        Logger.error("Claude HTTP error: #{inspect(reason)}")
        {:error, {:http_error, reason}}
    end
  end

  @impl true
  def stream_message(state, message, callback, opts) do
    messages = opts[:messages] || [message]
    
    body = build_request_body(state, messages, opts)
    |> Map.put(:stream, true)
    
    stream_callback = fn chunk ->
      case parse_sse_chunk(chunk) do
        {:ok, data} ->
          msg = parse_stream_data(data)
          if msg, do: callback.(msg)
        
        :ignore ->
          :ok
        
        {:error, reason} ->
          Logger.warning("Failed to parse stream chunk: #{inspect(reason)}")
      end
    end
    
    case HTTP.stream_post(@api_url, body, stream_callback, headers: build_headers(state)) do
      :ok -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  @impl true
  def list_models(_state) do
    # Anthropic doesn't have a models endpoint; return known models
    models = [
      %{id: "claude-opus-4-20250514", name: "Claude 4 Opus", max_tokens: 8192},
      %{id: "claude-sonnet-4-20250514", name: "Claude 4 Sonnet", max_tokens: 8192},
      %{id: "claude-3-5-sonnet-20241022", name: "Claude 3.5 Sonnet", max_tokens: 8192},
    ]
    {:ok, models}
  end

  @impl true
  def health_check(state) do
    # Send a minimal request to verify API key works
    body = %{
      model: state.model,
      max_tokens: 10,
      messages: [%{role: "user", content: "Hi"}],
    }
    
    case Req.post(state.client, json: body) do
      {:ok, %Req.Response{status: 200}} -> :ok
      {:ok, %Req.Response{status: status}} -> {:error, {:api_error, status}}
      {:error, reason} -> {:error, reason}
    end
  end

  @impl true
  def terminate(_state) do
    :ok
  end

  # Private functions

  defp build_request_body(state, messages, opts) do
    # Separate system message from conversation
    {system_messages, conversation} = Enum.split_with(messages, &(&1.role == :system))
    
    system_prompt = 
      case system_messages do
        [%{content: content} | _] -> content
        [] -> state.default_system
      end
    
    # Convert messages to Claude format
    claude_messages = Enum.map(conversation, &Message.to_provider_format(&1, :claude))
    
    body = %{
      model: opts[:model] || state.model,
      max_tokens: opts[:max_tokens] || state.max_tokens,
      messages: claude_messages,
    }
    
    # Add system prompt if present
    body = if system_prompt, do: Map.put(body, :system, system_prompt), else: body
    
    # Add tools if present
    body = if opts[:tools], do: Map.put(body, :tools, format_tools(opts[:tools])), else: body
    
    # Add tool_choice if specified
    body = if opts[:tool_choice], do: Map.put(body, :tool_choice, opts[:tool_choice]), else: body
    
    body
  end

  defp build_headers(state) do
    [
      {"Content-Type", "application/json"},
      {"x-api-key", state.api_key},
      {"anthropic-version", @api_version},
    ]
  end

  defp format_tools(tools) do
    Enum.map(tools, fn tool ->
      %{
        name: tool[:name],
        description: tool[:description],
        input_schema: tool[:parameters] || tool[:input_schema],
      }
    end)
  end

  defp parse_sse_chunk(chunk) do
    cond do
      String.starts_with?(chunk, "data: ") ->
        data = String.trim_leading(chunk, "data: ") |> String.trim()
        if data == "[DONE]" do
          :ignore
        else
          case Jason.decode(data) do
            {:ok, parsed} -> {:ok, parsed}
            {:error, reason} -> {:error, reason}
          end
        end
      
      String.starts_with?(chunk, "event:") ->
        :ignore
      
      String.trim(chunk) == "" ->
        :ignore
      
      true ->
        :ignore
    end
  end

  defp parse_stream_data(%{"type" => "content_block_delta", "delta" => %{"type" => "text_delta", "text" => text}}) do
    %Message{
      role: :assistant,
      content: text,
      provider: :claude,
    }
  end

  defp parse_stream_data(%{"type" => "message_start", "message" => msg}) do
    %Message{
      id: msg["id"],
      role: :assistant,
      provider: :claude,
      model: msg["model"],
      metadata: %{type: :message_start},
    }
  end

  defp parse_stream_data(%{"type" => "message_stop"}) do
    %Message{
      role: :assistant,
      provider: :claude,
      metadata: %{type: :message_stop},
    }
  end

  defp parse_stream_data(%{"type" => "content_block_delta", "delta" => %{"type" => "tool_use", "id" => id, "name" => name}}) do
    %Message{
      role: :assistant,
      provider: :claude,
      tool_calls: [%{id: id, type: :function, function: %{name: name, arguments: ""}}],
    }
  end

  defp parse_stream_data(%{"type" => "content_block_delta", "delta" => %{"type" => "input_json_delta", "partial_json" => json}}) do
    %Message{
      role: :assistant,
      provider: :claude,
      metadata: %{partial_tool_args: json},
    }
  end

  defp parse_stream_data(_), do: nil
end

