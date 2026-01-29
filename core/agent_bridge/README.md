# Agent Bridge

Unified AI provider interface for Continuum Studio.

## Overview

Agent Bridge provides a consistent API for communicating with multiple AI providers:

- **Claude (Anthropic)** - Direct API access
- **OpenAI** - GPT models (planned)
- **Ollama** - Local LLM instances
- **Cursor** - Via Synapsix harness

## Features

- 📝 **Unified Message Format** - Single `Message` struct for all providers
- 💬 **Context Management** - Session-based conversation history
- 💰 **Cost Tracking** - Token counting and budget enforcement
- ⏱️ **Rate Limiting** - Respect provider limits
- 📋 **Provider Registry** - Dynamic provider management
- 🌊 **Streaming Support** - First-class streaming responses
- 🔌 **Middleware Pipeline** - Extensible message processing

## Quick Start

```elixir
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
# r2.content => "Your name is Alice"
```

## Configuration

```elixir
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
    cursor: [
      module: AgentBridge.Providers.Cursor,
      harness_id: "cursor_homelab",
    ],
  ],
  rate_limits: [
    claude: 60,    # requests per minute
    ollama: :unlimited,
  ]
```

## Architecture

```
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
```

## Provider API

All providers implement the `AgentBridge.Provider` behaviour:

```elixir
@callback init(config :: map()) :: {:ok, state} | {:error, reason}
@callback send_message(state, message, opts) :: {:ok, Message.t()} | {:error, reason}
@callback stream_message(state, message, callback, opts) :: :ok | {:error, reason}
@callback list_models(state) :: {:ok, [map()]} | {:error, reason}
@callback health_check(state) :: :ok | {:error, reason}
```

## Middleware

Extend message processing with middleware:

```elixir
defmodule MyMiddleware do
  @behaviour AgentBridge.Middleware
  
  @impl true
  def call(message, opts, next) do
    # Pre-processing
    modified = transform(message)
    
    # Call next middleware
    case next.(modified, opts) do
      {:ok, response} -> {:ok, transform(response)}
      error -> error
    end
  end
end
```

Built-in middleware:
- `Logger` - Log all messages
- `Sanitizer` - Remove sensitive data
- `Validator` - Validate message content
- `Telemetry` - Emit telemetry events
- `ContentFilter` - Filter based on rules

## Cost Tracking

```elixir
# Get usage statistics
AgentBridge.get_usage(:claude)
# => %{total_input_tokens: 1234, total_output_tokens: 5678, total_cost: 0.05, ...}

# Set a budget
AgentBridge.set_budget(:claude, 10.00, :monthly)

# Check budget
AgentBridge.check_budget(:claude)
# => {:ok, 0.05, 10.00}  # current, limit
```

## Integration with Continuum Studio

Agent Bridge connects to Studio Core for:
- Harness-based providers (Cursor via Synapsix)
- Event broadcasting
- State synchronization

```elixir
# Register with Studio Core
StudioCore.EventBus.subscribe(self())

# Forward agent responses to UI
def handle_info({:event, {:agent_response, content, role}}, state) do
  # Update UI via IPC
end
```

## Multi-GPU Ollama Setup

For homelab setups with multiple GPUs:

```elixir
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
```

## License

AGPL-3.0 - See LICENSE file for details.
