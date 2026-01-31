defmodule StudioCore.MixProject do
  use Mix.Project

  def project do
    [
      app: :studio_core,
      version: "0.1.0",
      elixir: "~> 1.17",
      start_permanent: Mix.env() == :prod,
      deps: deps(),
      releases: releases()
    ]
  end

  def application do
    [
      extra_applications: [:logger],
      mod: {StudioCore.Application, []}
    ]
  end

  defp deps do
    [
      # JSON encoding/decoding (for protocol fallback and config)
      {:jason, "~> 1.4"},

      # Telemetry for observability
      {:telemetry, "~> 1.2"},

      # Ranch for TCP acceptor pool (optional, for higher concurrency)
      # {:ranch, "~> 2.1"},
    ]
  end

  defp releases do
    [
      studio_core: [
        include_executables_for: [:unix],
        applications: [runtime_tools: :permanent]
      ]
    ]
  end
end
