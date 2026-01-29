defmodule AgentBridgeTest do
  use ExUnit.Case
  doctest AgentBridge

  test "greets the world" do
    assert AgentBridge.hello() == :world
  end
end
