# Agent Bridge Architecture

> The Agent Bridge connects AI agents (Cursor, Claude, OpenAI, local LLMs) to the Continuum Studio ecosystem, providing unified message routing, context management, and provider abstraction.

## Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Continuum Studio                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────┐     ┌───────────────┐     ┌───────────────────────────┐  │
│  │   Studio UI   │────▶│  Studio Core  │◀───▶│        Synapsix          │  │
│  │   (Rust)      │     │   (Elixir)    │     │   (Harness Control)      │  │
│  └───────────────┘     └───────┬───────┘     └───────────────────────────┘  │
│                                │                                             │
│                                │ EventBus                                    │
│                                ▼                                             │
│                      ┌─────────────────┐                                     │
│                      │  Agent Bridge   │◀── This Document                    │
│                      │   (Elixir)      │                                     │
│                      └────────┬────────┘                                     │
│                               │                                              │
│              ┌────────────────┼────────────────┐                            │
│              ▼                ▼                ▼                            │
│     ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                       │
│     │   Cursor    │  │   Claude    │  │   OpenAI    │  ... more providers   │
│     │  Adapter    │  │  Adapter    │  │  Adapter    │                       │
│     └──────┬──────┘  └──────┬──────┘  └──────┬──────┘                       │
│            │                │                │                              │
└────────────┼────────────────┼────────────────┼──────────────────────────────┘
             │                │                │
             ▼                ▼                ▼
      ┌──────────┐     ┌──────────┐     ┌──────────┐
      │  Cursor  │     │  Claude  │     │  OpenAI  │
      │   API    │     │   API    │     │   API    │
      └──────────┘     └──────────┘     └──────────┘
```

## Core Responsibilities

### 1. Provider Abstraction

The Agent Bridge abstracts different AI providers behind a common interface:

```elixir
defmodule AgentBridge.Provider do
  @callback connect(config :: map()) :: {:ok, state} | {:error, reason}
  @callback send_message(state, message :: map()) :: {:ok, response} | {:error, reason}
  @callback stream_message(state, message :: map()) :: {:ok, stream} | {:error, reason}
  @callback capabilities() :: [atom()]  # [:streaming, :function_calling, :vision, ...]
  @callback disconnect(state) :: :ok
end
```

### 2. Message Routing

Messages flow through the bridge based on:
- **Source**: Which harness or UI component initiated the message
- **Target Provider**: Which AI agent should handle it
- **Context**: Session state, conversation history, tool results
- **Policy**: Rate limiting, cost tracking, fallback rules

### 3. Context Management

The bridge maintains:
- **Session State**: Conversation history per provider
- **Tool Results**: Outputs from harness operations
- **System Prompts**: Provider-specific and global contexts
- **Artifacts**: Code, diagrams, documents generated during sessions

### 4. Event Integration

Integrates with Studio Core's EventBus:
```elixir
# Subscribe to relevant events
EventBus.subscribe([
  :harness_status,      # Harness state changes
  :agent_message,       # Messages from UI
  :tool_result,         # Results from harness operations
])

# Publish agent responses
EventBus.broadcast({:agent_response, %{
  provider: :cursor,
  role: :assistant,
  content: "...",
  tool_calls: [...]
}})
```

## Architecture Components

### AgentBridge.Supervisor

Top-level supervisor managing all bridge components:

```elixir
defmodule AgentBridge.Supervisor do
  use Supervisor

  def start_link(opts) do
    Supervisor.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(_opts) do
    children = [
      # Connection management
      {AgentBridge.ConnectionPool, []},
      
      # Provider adapters (started dynamically)
      {DynamicSupervisor, name: AgentBridge.ProviderSupervisor},
      
      # Message router
      {AgentBridge.Router, []},
      
      # Context manager
      {AgentBridge.ContextManager, []},
      
      # Cost tracker
      {AgentBridge.CostTracker, []},
      
      # Rate limiter
      {AgentBridge.RateLimiter, []},
    ]

    Supervisor.init(children, strategy: :one_for_one)
  end
end
```

### AgentBridge.Router

Routes messages to appropriate providers:

```elixir
defmodule AgentBridge.Router do
  use GenServer

  @doc """
  Route a message to the appropriate provider.
  
  Options:
    - :provider - Specific provider to use (default: auto-select)
    - :session - Session ID for context continuity
    - :stream - Whether to stream the response
    - :tools - Available tools for function calling
  """
  def route(message, opts \\ []) do
    GenServer.call(__MODULE__, {:route, message, opts})
  end

  @impl true
  def handle_call({:route, message, opts}, _from, state) do
    provider = select_provider(message, opts, state)
    
    # Add context from ContextManager
    enriched_message = enrich_with_context(message, opts[:session])
    
    # Check rate limits
    case check_rate_limit(provider) do
      :ok ->
        result = dispatch_to_provider(provider, enriched_message, opts)
        track_usage(provider, result)
        {:reply, result, state}
      
      {:rate_limited, retry_after} ->
        {:reply, {:error, {:rate_limited, retry_after}}, state}
    end
  end

  defp select_provider(message, opts, state) do
    case opts[:provider] do
      nil -> auto_select_provider(message, state)
      provider -> provider
    end
  end

  defp auto_select_provider(message, state) do
    # Selection logic based on:
    # - Message type (code, conversation, vision)
    # - Provider availability
    # - Cost preferences
    # - Performance history
    :cursor  # Default for now
  end
end
```

### AgentBridge.ContextManager

Manages conversation context and system prompts:

```elixir
defmodule AgentBridge.ContextManager do
  use GenServer

  @doc """
  Context structure for a session:
  
  - system_prompt: Base instructions
  - conversation_history: List of {role, content} messages
  - tool_results: Results from recent tool calls
  - artifacts: Generated files, code, diagrams
  - metadata: Session info, timestamps, etc.
  """
  
  defmodule SessionContext do
    defstruct [
      :session_id,
      :provider,
      system_prompt: "",
      conversation_history: [],
      tool_results: [],
      artifacts: [],
      metadata: %{},
      created_at: nil,
      updated_at: nil,
    ]
  end

  def get_context(session_id) do
    GenServer.call(__MODULE__, {:get_context, session_id})
  end

  def update_context(session_id, updates) do
    GenServer.call(__MODULE__, {:update_context, session_id, updates})
  end

  def add_message(session_id, role, content) do
    GenServer.call(__MODULE__, {:add_message, session_id, role, content})
  end

  def add_tool_result(session_id, tool_name, result) do
    GenServer.call(__MODULE__, {:add_tool_result, session_id, tool_name, result})
  end
end
```

## Provider Adapters

### Cursor Adapter

Special adapter for Cursor IDE's AI features:

```elixir
defmodule AgentBridge.Providers.Cursor do
  @behaviour AgentBridge.Provider
  
  @moduledoc """
  Adapter for Cursor IDE's AI capabilities.
  
  Cursor provides:
  - Code completion (Copilot-style)
  - Chat interface (Claude/GPT backend)
  - Edit suggestions
  - Codebase-aware context
  
  Integration method:
  - Monitor Cursor window via Synapsix harness
  - Inject prompts via keyboard simulation
  - Extract responses via screen parsing (advanced)
  - Or direct API if available
  """

  @impl true
  def capabilities do
    [:streaming, :code_completion, :chat, :codebase_aware]
  end

  @impl true
  def connect(config) do
    # Check if Cursor harness is available
    case Synapsix.get_harness(:cursor) do
      {:ok, harness} ->
        {:ok, %{harness: harness, config: config}}
      error ->
        error
    end
  end

  @impl true
  def send_message(state, message) do
    harness = state.harness
    
    # Focus Cursor window
    Synapsix.focus(harness)
    
    # Open chat panel (Ctrl+Shift+I or Ctrl+L depending on version)
    Synapsix.send_keys(harness, [:ctrl, :shift, "i"])
    Process.sleep(500)
    
    # Type the message
    Synapsix.type_text(harness, message.content)
    
    # Submit (Enter)
    Synapsix.send_keys(harness, [:enter])
    
    # Wait for response (monitor window or use clipboard)
    await_response(harness, message)
  end

  defp await_response(harness, message) do
    # Strategy 1: Monitor clipboard for @response marker
    # Strategy 2: Screen parsing (OCR)
    # Strategy 3: Watch for file changes in .cursor/
    # For now, return placeholder
    {:ok, %{
      role: :assistant,
      content: "[Response from Cursor - integration pending]",
      provider: :cursor,
    }}
  end
end
```

### Claude Direct API Adapter

```elixir
defmodule AgentBridge.Providers.Claude do
  @behaviour AgentBridge.Provider
  
  @impl true
  def capabilities do
    [:streaming, :function_calling, :vision, :long_context]
  end

  @impl true
  def connect(config) do
    api_key = config[:api_key] || System.get_env("ANTHROPIC_API_KEY")
    
    if api_key do
      {:ok, %{
        api_key: api_key,
        model: config[:model] || "claude-sonnet-4-20250514",
        base_url: "https://api.anthropic.com/v1",
      }}
    else
      {:error, :missing_api_key}
    end
  end

  @impl true
  def send_message(state, message) do
    # Build request
    request = %{
      model: state.model,
      max_tokens: message[:max_tokens] || 4096,
      messages: build_messages(message),
      system: message[:system_prompt],
    }
    
    # Add tools if provided
    request = if message[:tools] do
      Map.put(request, :tools, format_tools(message[:tools]))
    else
      request
    end
    
    # Make API call
    case Req.post("#{state.base_url}/messages", 
      json: request,
      headers: [
        {"x-api-key", state.api_key},
        {"anthropic-version", "2023-06-01"},
      ]
    ) do
      {:ok, %{status: 200, body: body}} ->
        {:ok, parse_response(body)}
      {:ok, %{status: status, body: body}} ->
        {:error, {:api_error, status, body}}
      {:error, reason} ->
        {:error, reason}
    end
  end

  @impl true
  def stream_message(state, message) do
    # Similar to send_message but with stream: true
    # Returns a Stream that yields response chunks
    {:ok, Stream.resource(
      fn -> start_stream(state, message) end,
      fn state -> read_chunk(state) end,
      fn state -> close_stream(state) end
    )}
  end
end
```

### OpenAI Adapter

```elixir
defmodule AgentBridge.Providers.OpenAI do
  @behaviour AgentBridge.Provider
  
  @impl true
  def capabilities do
    [:streaming, :function_calling, :vision, :embeddings]
  end

  @impl true
  def connect(config) do
    api_key = config[:api_key] || System.get_env("OPENAI_API_KEY")
    
    {:ok, %{
      api_key: api_key,
      model: config[:model] || "gpt-4o",
      base_url: config[:base_url] || "https://api.openai.com/v1",
    }}
  end

  @impl true
  def send_message(state, message) do
    request = %{
      model: state.model,
      messages: build_messages(message),
    }
    
    # OpenAI-style API call
    case Req.post("#{state.base_url}/chat/completions",
      json: request,
      headers: [{"Authorization", "Bearer #{state.api_key}"}]
    ) do
      {:ok, %{status: 200, body: body}} ->
        {:ok, parse_response(body)}
      error ->
        error
    end
  end
end
```

### Local LLM Adapter (Ollama)

```elixir
defmodule AgentBridge.Providers.Ollama do
  @behaviour AgentBridge.Provider
  
  @impl true
  def capabilities do
    [:streaming, :local, :private]
  end

  @impl true
  def connect(config) do
    base_url = config[:base_url] || "http://localhost:11434"
    
    # Verify Ollama is running
    case Req.get("#{base_url}/api/tags") do
      {:ok, %{status: 200}} ->
        {:ok, %{
          base_url: base_url,
          model: config[:model] || "qwen2.5-coder:14b",
        }}
      _ ->
        {:error, :ollama_not_running}
    end
  end

  @impl true
  def send_message(state, message) do
    request = %{
      model: state.model,
      prompt: message.content,
      system: message[:system_prompt],
      stream: false,
    }
    
    case Req.post("#{state.base_url}/api/generate", json: request) do
      {:ok, %{status: 200, body: body}} ->
        {:ok, %{
          role: :assistant,
          content: body["response"],
          provider: :ollama,
        }}
      error ->
        error
    end
  end
end
```

## Message Flow

### User → Agent

```
1. User types in Studio UI
2. UI sends {:agent_message, text, provider} to Core
3. Core routes to AgentBridge.Router
4. Router enriches message with context
5. Router dispatches to selected Provider
6. Provider sends to external API
7. Response flows back:
   - Provider → Router → EventBus → Core → UI
```

### Agent → Tool

```
1. Agent requests tool call (e.g., read_file, execute_command)
2. Router parses tool_calls from response
3. Router dispatches tool calls to appropriate harness via Synapsix
4. Harness executes tool and returns result
5. Router adds tool_result to context
6. Router sends continuation message to provider
7. Repeat until agent stops requesting tools
```

### Streaming Flow

```elixir
# In Router
def route_streaming(message, opts) do
  provider = select_provider(message, opts)
  
  # Start stream
  {:ok, stream} = provider.stream_message(state, message)
  
  # Forward chunks via EventBus
  Task.async(fn ->
    stream
    |> Stream.each(fn chunk ->
      EventBus.broadcast({:agent_stream_chunk, %{
        provider: provider,
        chunk: chunk,
        session: opts[:session],
      }})
    end)
    |> Stream.run()
    
    EventBus.broadcast({:agent_stream_end, %{
      provider: provider,
      session: opts[:session],
    }})
  end)
  
  {:ok, :streaming}
end
```

## Cost Tracking

```elixir
defmodule AgentBridge.CostTracker do
  use GenServer
  
  # Track usage per provider, per session, per time period
  # Costs based on:
  # - Input tokens
  # - Output tokens
  # - Model tier
  # - Special features (vision, etc.)
  
  def track_usage(provider, message, response) do
    GenServer.cast(__MODULE__, {:track, provider, %{
      input_tokens: count_tokens(message),
      output_tokens: count_tokens(response),
      model: response.model,
      timestamp: DateTime.utc_now(),
    }})
  end
  
  def get_usage(provider, opts \\ []) do
    GenServer.call(__MODULE__, {:get_usage, provider, opts})
  end
  
  def set_budget(provider, amount, period) do
    GenServer.call(__MODULE__, {:set_budget, provider, amount, period})
  end
end
```

## Configuration

```elixir
# config/config.exs
config :agent_bridge,
  providers: [
    cursor: [
      enabled: true,
      priority: 1,  # Prefer Cursor when available
    ],
    claude: [
      enabled: true,
      model: "claude-sonnet-4-20250514",
      api_key: {:system, "ANTHROPIC_API_KEY"},
      max_tokens: 8192,
    ],
    openai: [
      enabled: true,
      model: "gpt-4o",
      api_key: {:system, "OPENAI_API_KEY"},
    ],
    ollama: [
      enabled: true,
      base_url: "http://localhost:11434",
      model: "qwen2.5-coder:14b",
      priority: 10,  # Fallback when others unavailable
    ],
  ],
  
  rate_limits: [
    cursor: {60, :per_minute},
    claude: {1000, :per_day},
    openai: {100, :per_minute},
    ollama: :unlimited,
  ],
  
  cost_budgets: [
    claude: {50.0, :usd, :per_month},
    openai: {100.0, :usd, :per_month},
  ]
```

## Integration Points

### With Studio Core

```elixir
# In Studio Core
def handle_agent_message(text, provider \\ nil) do
  AgentBridge.Router.route(%{
    content: text,
    role: :user,
  }, provider: provider)
end

# Subscribe to agent events
EventBus.subscribe([:agent_response, :agent_stream_chunk])
```

### With Synapsix

```elixir
# Tool execution via harness
def execute_tool(:read_file, %{path: path}, session) do
  harness = Synapsix.get_active_harness()
  Synapsix.execute(harness, {:read_file, path})
end

def execute_tool(:execute_command, %{command: cmd}, session) do
  harness = Synapsix.get_active_harness()
  Synapsix.execute(harness, {:run_terminal, cmd})
end
```

### With Dialog Daemon

```elixir
# User approval for sensitive operations
def execute_tool(:shell_command, params, session) do
  case DialogManager.confirm(
    "Execute command: #{params.command}?",
    title: "Agent Tool Request",
    yes: "Execute",
    no: "Deny"
  ) do
    {:ok, %{selection: true}} ->
      # Proceed with execution
      execute_shell(params)
    _ ->
      {:error, :user_denied}
  end
end
```

## Security Considerations

1. **API Key Management**: Use sops-nix for encrypted storage
2. **Tool Approval**: Sensitive tools require user confirmation
3. **Rate Limiting**: Prevent runaway costs
4. **Context Isolation**: Sessions don't leak between providers
5. **Audit Logging**: All API calls logged for review

## Future Extensions

1. **Multi-Agent Orchestration**: Route between multiple agents
2. **Agent Chains**: Sequential agent processing
3. **Custom Providers**: Plugin system for new AI providers
4. **Fine-Tuning Integration**: Use custom/fine-tuned models
5. **Embedding Store**: RAG integration with local embeddings

---

*This architecture enables Continuum Studio to orchestrate multiple AI agents through a unified interface, providing context-aware routing, cost management, and seamless integration with the harness system.*

