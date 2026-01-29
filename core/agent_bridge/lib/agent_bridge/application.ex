defmodule AgentBridge.Application do
  @moduledoc """
  Main application supervisor for Agent Bridge.
  
  Starts the supervision tree including:
  - Provider registry
  - Context manager
  - Cost tracker
  - Rate limiter
  - Message router
  """

  use Application

  require Logger

  @impl true
  def start(_type, _args) do
    Logger.info("🌉 Starting Agent Bridge...")

    children = [
      # Provider management
      AgentBridge.ProviderRegistry,
      
      # Session context
      AgentBridge.ContextManager,
      
      # Usage tracking
      AgentBridge.CostTracker,
      
      # Rate limiting
      AgentBridge.RateLimiter,
      
      # Message routing
      AgentBridge.Router,
      
      # Studio Core connection (optional - enabled via config)
      {AgentBridge.CoreClient, Application.get_env(:agent_bridge, :core_client, [])},
    ]

    opts = [strategy: :one_for_one, name: AgentBridge.Supervisor]
    Supervisor.start_link(children, opts)
  end
end
