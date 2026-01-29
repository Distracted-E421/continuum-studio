# Integration test script for Agent Bridge
# Run with: mix run test/integration_test.exs

require Logger

Logger.configure(level: :info)
Logger.info("🧪 Agent Bridge Integration Test")

# Helper functions
defmodule TestHelper do
  def check(name, result) do
    case result do
      :ok ->
        IO.puts("  ✅ #{name}")
        :ok
      {:ok, _} ->
        IO.puts("  ✅ #{name}")
        :ok
      {:error, reason} ->
        IO.puts("  ❌ #{name}: #{inspect(reason)}")
        :error
      other ->
        IO.puts("  ✅ #{name}: #{inspect(other)}")
        :ok
    end
  end

  def section(name) do
    IO.puts("\n📋 #{name}")
    IO.puts(String.duplicate("-", 40))
  end
end

alias AgentBridge.{Message, ProviderRegistry, ContextManager, CostTracker, RateLimiter, Router}

# ==============================================================================
# Test Suite
# ==============================================================================

TestHelper.section("1. Provider Registry")

# Register a mock provider
defmodule MockProvider do
  @behaviour AgentBridge.Provider

  def init(_config), do: {:ok, %{}}

  def send_message(_state, message, _opts) do
    {:ok, Message.assistant("Mock response to: #{message.content}")}
  end

  def stream_message(_state, message, callback, _opts) do
    callback.(Message.assistant("Streaming: #{String.slice(message.content, 0, 10)}"))
    callback.(Message.assistant("...", metadata: %{done: true}))
    :ok
  end

  def list_models(_state), do: {:ok, [%{id: "mock-model"}]}
  def health_check(_state), do: :ok
  def terminate(_state), do: :ok
end

TestHelper.check("Register mock provider",
  ProviderRegistry.register(:mock, MockProvider, []))

TestHelper.check("List providers",
  (case ProviderRegistry.list() do
    providers when is_list(providers) -> {:ok, providers}
    _ -> {:error, "Not a list"}
  end))

TestHelper.check("Get provider",
  (case ProviderRegistry.get(:mock) do
    nil -> {:error, "Not found"}
    _ -> :ok
  end))

# ==============================================================================

TestHelper.section("2. Message Format")

user_msg = Message.user("Hello, world!")
TestHelper.check("Create user message",
  (if user_msg.role == :user, do: :ok, else: {:error, "Wrong role"}))

assistant_msg = Message.assistant("Hi there!", provider: :mock)
TestHelper.check("Create assistant message",
  (if assistant_msg.role == :assistant and assistant_msg.provider == :mock, do: :ok, else: {:error, "Wrong"}))

system_msg = Message.system("You are a helpful assistant")
TestHelper.check("Create system message",
  (if system_msg.role == :system, do: :ok, else: {:error, "Wrong role"}))

tool_msg = Message.tool("call-123", "Tool result")
TestHelper.check("Create tool message",
  (if tool_msg.role == :tool and tool_msg.tool_call_id == "call-123", do: :ok, else: {:error, "Wrong"}))

# ==============================================================================

TestHelper.section("3. Context Manager")

session = "test-session-123"

TestHelper.check("Set system prompt",
  ContextManager.set_system_prompt(session, "Test system prompt"))

TestHelper.check("Get system prompt",
  (case ContextManager.get_system_prompt(session) do
    "Test system prompt" -> :ok
    other -> {:error, "Got: #{inspect(other)}"}
  end))

ContextManager.add_message(session, Message.user("Hello"))
ContextManager.add_message(session, Message.assistant("Hi"))

Process.sleep(100) # Give time for async cast

TestHelper.check("Get history",
  (case ContextManager.get_history(session) do
    history when length(history) == 2 -> :ok
    history -> {:error, "Wrong history length: #{length(history)}"}
  end))

TestHelper.check("Clear session",
  ContextManager.clear_session(session))

# ==============================================================================

TestHelper.section("4. Rate Limiter")

TestHelper.check("Check rate limit (should pass)",
  RateLimiter.check(:mock))

# Record some requests
for _ <- 1..5, do: RateLimiter.record(:mock)
Process.sleep(100)

TestHelper.check("Status after requests",
  (case RateLimiter.status(:mock) do
    %{current: n} when n > 0 -> :ok
    other -> {:error, "Unexpected: #{inspect(other)}"}
  end))

# ==============================================================================

TestHelper.section("5. Cost Tracker")

CostTracker.track(%{
  provider: :mock,
  input_tokens: 100,
  output_tokens: 50,
  model: "mock-model",
  timestamp: DateTime.utc_now()
})

Process.sleep(100)

TestHelper.check("Get usage",
  (case CostTracker.get_usage(:mock) do
    %{total_input_tokens: n} when n > 0 -> :ok
    other -> {:error, "Unexpected: #{inspect(other)}"}
  end))

# ==============================================================================

TestHelper.section("6. Router (with Mock Provider)")

TestHelper.check("Route message",
  Router.route(Message.user("Test"), provider: :mock))

# ==============================================================================

TestHelper.section("7. Core Client Connection")

TestHelper.check("Core client running",
  (case Process.whereis(AgentBridge.CoreClient) do
    nil -> {:error, "Not running"}
    pid when is_pid(pid) -> :ok
  end))

TestHelper.check("Check connection status",
  # It's expected to not be connected if Core isn't running
  (case AgentBridge.CoreClient.connected?() do
    true -> :ok
    false -> {:ok, "Not connected (expected if Core not running)"}
  end))

# ==============================================================================

IO.puts("\n" <> String.duplicate("=", 40))
IO.puts("🎉 Integration tests complete!")
IO.puts(String.duplicate("=", 40))

# Cleanup
ProviderRegistry.unregister(:mock)
