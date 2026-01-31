defmodule AgentBridge.MixProject do
  use Mix.Project

  def project do
    [
      app: :agent_bridge,
      version: "0.1.0",
      elixir: "~> 1.18",
      start_permanent: Mix.env() == :prod,
      deps: deps(),

      # Docs
      name: "Agent Bridge",
      description: "Unified AI provider interface for Continuum Studio",
      docs: [
        main: "AgentBridge",
        extras: ["README.md"],
      ],
    ]
  end

  def application do
    [
      extra_applications: [:logger, :inets, :ssl, :public_key, :crypto],
      mod: {AgentBridge.Application, []}
    ]
  end

  defp deps do
    [
      # JSON encoding/decoding
      {:jason, "~> 1.4"},

      # UUID generation for sessions
      {:uuid, "~> 1.1"},

      # Telemetry for metrics
      {:telemetry, "~> 1.0"},

      # HTTP client - Req is the modern Elixir HTTP client
      {:req, "~> 0.5"},

      # Development/testing
      {:ex_doc, "~> 0.34", only: :dev, runtime: false},
    ]
  end
end
