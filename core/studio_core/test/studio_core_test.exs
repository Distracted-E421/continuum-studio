defmodule StudioCoreTest do
  use ExUnit.Case
  doctest StudioCore

  test "module is loaded" do
    # Basic sanity check - module should be available
    assert Code.ensure_loaded?(StudioCore)
  end

  test "exports expected functions" do
    # Verify key public API functions are exported
    assert function_exported?(StudioCore, :get, 1)
    assert function_exported?(StudioCore, :set, 2)
    assert function_exported?(StudioCore, :subscribe, 0)
    assert function_exported?(StudioCore, :subscribe, 1)
    assert function_exported?(StudioCore, :broadcast, 1)
    assert function_exported?(StudioCore, :list_versions, 0)
    assert function_exported?(StudioCore, :list_versions, 1)
  end
end
