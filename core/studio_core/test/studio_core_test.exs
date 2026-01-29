defmodule StudioCoreTest do
  use ExUnit.Case
  doctest StudioCore

  test "greets the world" do
    assert StudioCore.hello() == :world
  end
end
