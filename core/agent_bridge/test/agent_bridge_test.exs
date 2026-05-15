defmodule AgentBridgeTest do
  use ExUnit.Case
  doctest AgentBridge

  test "module is loaded" do
    assert Code.ensure_loaded?(AgentBridge)
  end

  test "exports expected functions" do
    # Chat functions
    assert function_exported?(AgentBridge, :chat, 1)
    assert function_exported?(AgentBridge, :chat, 2)
    assert function_exported?(AgentBridge, :stream, 2)
    assert function_exported?(AgentBridge, :stream, 3)

    # Session management
    assert function_exported?(AgentBridge, :new_session, 0)
    assert function_exported?(AgentBridge, :get_history, 1)
    assert function_exported?(AgentBridge, :clear_history, 1)
    assert function_exported?(AgentBridge, :end_session, 1)
    assert function_exported?(AgentBridge, :list_sessions, 0)

    # Provider management
    assert function_exported?(AgentBridge, :register_provider, 2)
    assert function_exported?(AgentBridge, :register_provider, 3)
    assert function_exported?(AgentBridge, :list_providers, 0)
    assert function_exported?(AgentBridge, :provider_status, 1)

    # Cost tracking
    assert function_exported?(AgentBridge, :get_usage, 0)
    assert function_exported?(AgentBridge, :get_cost, 1)
    assert function_exported?(AgentBridge, :check_budget, 1)
  end

  test "new_session returns a valid UUID" do
    session = AgentBridge.new_session()
    assert is_binary(session)
    assert String.length(session) == 36
    assert String.match?(session, ~r/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/)
  end
end
