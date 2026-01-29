defmodule AgentBridge.Provider do
  @moduledoc """
  Behaviour for AI providers.

  Implement this behaviour to add a new AI provider to the Agent Bridge.
  """

  alias AgentBridge.Message

  @doc """
  Initialize the provider with configuration.
  """
  @callback init(config :: map()) :: {:ok, state :: term()} | {:error, reason :: term()}

  @doc """
  Send a message and get a complete response.
  """
  @callback send_message(state :: term(), message :: Message.t(), opts :: keyword()) ::
    {:ok, Message.t()} | {:error, reason :: term()}

  @doc """
  Send a message and stream the response.

  The callback will be called with each chunk as it arrives.
  """
  @callback stream_message(state :: term(), message :: Message.t(), callback :: (Message.t() -> any()), opts :: keyword()) ::
    :ok | {:error, reason :: term()}

  @doc """
  List available models for this provider.
  """
  @callback list_models(state :: term()) :: {:ok, [map()]} | {:error, reason :: term()}

  @doc """
  Check provider health.
  """
  @callback health_check(state :: term()) :: :ok | {:error, reason :: term()}

  @doc """
  Clean up provider resources.
  """
  @callback terminate(state :: term()) :: :ok

  @optional_callbacks [list_models: 1, health_check: 1, terminate: 1]
end
