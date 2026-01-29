defmodule AgentBridge.Providers.Ollama do
  @moduledoc """
  Ollama local LLM provider adapter.
  
  Supports:
  - Any model available in Ollama
  - Streaming responses
  - Local/remote Ollama instances
  
  ## Configuration
  
      config :agent_bridge, :providers,
        ollama: [
          module: AgentBridge.Providers.Ollama,
          base_url: "http://localhost:11434",
          model: "llama3.2",
        ]
  
  ## Multi-GPU Setup
  
  For multi-GPU configurations (like Obsidian with Arc A770 + RTX 2080),
  you can register multiple Ollama instances:
  
      config :agent_bridge, :providers,
        ollama_arc: [
          module: AgentBridge.Providers.Ollama,
          base_url: "http://localhost:11435",  # Arc A770 instance
          model: "qwen2.5:14b",
        ],
        ollama_nvidia: [
          module: AgentBridge.Providers.Ollama,
          base_url: "http://localhost:11434",  # RTX 2080 instance
          model: "qwen2.5:7b",
        ]
  """

  @behaviour AgentBridge.Provider

  require Logger
  alias AgentBridge.{Message, HTTP}

  @default_url "http://localhost:11434"

  defstruct [:base_url, :model, :default_system, :options, :client]

  # Provider behaviour implementation

  @impl true
  def init(config) do
    base_url = config[:base_url] || @default_url
    
    # Create a Req client
    client = HTTP.new_client(base_url,
      headers: [{"content-type", "application/json"}],
      timeout: 300_000  # 5 minutes for local models
    )
    
    state = %__MODULE__{
      base_url: base_url,
      model: config[:model] || "llama3.2",
      default_system: config[:system],
      options: config[:options] || %{},
      client: client,
    }
    {:ok, state}
  end

  @impl true
  def send_message(state, message, opts) do
    messages = opts[:messages] || [message]
    
    body = build_request_body(state, messages, opts)
    
    case Req.post(state.client, url: "/api/chat", json: body) do
      {:ok, %Req.Response{status: 200, body: response}} ->
        {:ok, Message.from_provider_response(response, :ollama)}
      
      {:ok, %Req.Response{status: status, body: error}} ->
        Logger.error("Ollama API error (#{status}): #{inspect(error)}")
        {:error, {:api_error, status, error}}
      
      {:error, reason} ->
        Logger.error("Ollama HTTP error: #{inspect(reason)}")
        {:error, {:http_error, reason}}
    end
  end

  @impl true
  def stream_message(state, message, callback, opts) do
    messages = opts[:messages] || [message]
    
    body = build_request_body(state, messages, opts)
    |> Map.put(:stream, true)
    
    stream_callback = fn line ->
      case Jason.decode(line) do
        {:ok, data} ->
          msg = parse_stream_data(data, state.model)
          if msg, do: callback.(msg)
        
        {:error, _} ->
          :ok
      end
    end
    
    case HTTP.stream_post("#{state.base_url}/api/chat", body, stream_callback) do
      :ok -> :ok
      {:error, reason} -> {:error, reason}
    end
  end

  @impl true
  def list_models(state) do
    case Req.get(state.client, url: "/api/tags") do
      {:ok, %Req.Response{status: 200, body: response}} ->
        models = Enum.map(response["models"] || [], fn m ->
          %{
            id: m["name"],
            name: m["name"],
            size: m["size"],
            modified_at: m["modified_at"],
          }
        end)
        {:ok, models}
      
      {:ok, %Req.Response{status: status}} ->
        {:error, {:api_error, status}}
      
      {:error, reason} ->
        {:error, reason}
    end
  end

  @impl true
  def health_check(state) do
    case Req.get(state.client, url: "/api/tags") do
      {:ok, %Req.Response{status: 200}} -> :ok
      {:ok, %Req.Response{status: status}} -> {:error, {:api_error, status}}
      {:error, reason} -> {:error, reason}
    end
  end

  @impl true
  def terminate(_state) do
    :ok
  end

  # Public API for Ollama-specific operations

  @doc """
  Pull a model from Ollama registry.
  """
  def pull_model(state, model_name) do
    body = %{name: model_name}
    
    case Req.post(state.client, url: "/api/pull", json: body) do
      {:ok, %Req.Response{status: 200}} -> :ok
      {:ok, %Req.Response{status: status, body: error}} -> {:error, {:api_error, status, error}}
      {:error, reason} -> {:error, reason}
    end
  end

  @doc """
  Generate embeddings for text.
  """
  def embed(state, text, model \\ nil) do
    body = %{
      model: model || state.model,
      prompt: text,
    }
    
    case Req.post(state.client, url: "/api/embeddings", json: body) do
      {:ok, %Req.Response{status: 200, body: response}} ->
        {:ok, response["embedding"]}
      
      {:ok, %Req.Response{status: status, body: error}} ->
        {:error, {:api_error, status, error}}
      
      {:error, reason} ->
        {:error, reason}
    end
  end

  # Private functions

  defp build_request_body(state, messages, opts) do
    # Convert messages to Ollama format
    ollama_messages = Enum.map(messages, fn msg ->
      base = %{role: to_string(msg.role), content: msg.content || ""}
      
      # Add images if present in metadata
      case msg.metadata[:images] do
        nil -> base
        images -> Map.put(base, :images, images)
      end
    end)
    
    body = %{
      model: opts[:model] || state.model,
      messages: ollama_messages,
      stream: false,
    }
    
    # Add system prompt if specified
    body = if opts[:system] || state.default_system do
      Map.put(body, :system, opts[:system] || state.default_system)
    else
      body
    end
    
    # Add Ollama-specific options
    options = Map.merge(state.options, opts[:options] || %{})
    if map_size(options) > 0 do
      Map.put(body, :options, options)
    else
      body
    end
  end

  defp parse_stream_data(%{"done" => true} = data, model) do
    %Message{
      role: :assistant,
      provider: :ollama,
      model: model,
      metadata: %{
        done: true,
        total_duration: data["total_duration"],
        eval_count: data["eval_count"],
      },
    }
  end

  defp parse_stream_data(%{"message" => %{"content" => content}}, model) do
    %Message{
      role: :assistant,
      content: content,
      provider: :ollama,
      model: model,
    }
  end

  defp parse_stream_data(_, _), do: nil
end

