defmodule AgentBridge.Message do
  @moduledoc """
  Standard message format for AI provider communication.

  All providers use this common message format, with provider-specific
  details in the metadata field.
  """

  @type role :: :system | :user | :assistant | :tool

  @type t :: %__MODULE__{
    id: String.t() | nil,
    role: role(),
    content: String.t() | nil,
    name: String.t() | nil,
    tool_calls: [tool_call()] | nil,
    tool_call_id: String.t() | nil,
    provider: atom() | nil,
    model: String.t() | nil,
    metadata: map(),
    created_at: DateTime.t() | nil,
  }

  @type tool_call :: %{
    id: String.t(),
    type: :function,
    function: %{
      name: String.t(),
      arguments: String.t(),
    }
  }

  defstruct [
    :id,
    :role,
    :content,
    :name,
    :tool_calls,
    :tool_call_id,
    :provider,
    :model,
    metadata: %{},
    created_at: nil,
  ]

  @doc """
  Create a system message.
  """
  def system(content) do
    %__MODULE__{
      role: :system,
      content: content,
      created_at: DateTime.utc_now(),
    }
  end

  @doc """
  Create a user message.
  """
  def user(content, opts \\ []) do
    %__MODULE__{
      role: :user,
      content: content,
      name: opts[:name],
      metadata: opts[:metadata] || %{},
      created_at: DateTime.utc_now(),
    }
  end

  @doc """
  Create an assistant message.
  """
  def assistant(content, opts \\ []) do
    %__MODULE__{
      role: :assistant,
      content: content,
      name: opts[:name],
      tool_calls: opts[:tool_calls],
      provider: opts[:provider],
      model: opts[:model],
      metadata: opts[:metadata] || %{},
      created_at: DateTime.utc_now(),
    }
  end

  @doc """
  Create a tool response message.
  """
  def tool(tool_call_id, content) do
    %__MODULE__{
      role: :tool,
      tool_call_id: tool_call_id,
      content: content,
      created_at: DateTime.utc_now(),
    }
  end

  @doc """
  Convert message to provider-specific format.
  """
  def to_provider_format(%__MODULE__{} = msg, :claude) do
    base = %{role: to_string(msg.role), content: msg.content}

    case msg.role do
      :tool ->
        %{role: "user", content: [
          %{type: "tool_result", tool_use_id: msg.tool_call_id, content: msg.content}
        ]}

      :assistant when msg.tool_calls != nil ->
        tool_use = Enum.map(msg.tool_calls, fn tc ->
          %{type: "tool_use", id: tc.id, name: tc.function.name, input: Jason.decode!(tc.function.arguments)}
        end)
        content_blocks = if msg.content, do: [%{type: "text", text: msg.content}], else: []
        %{role: "assistant", content: content_blocks ++ tool_use}

      _ ->
        base
    end
  end

  def to_provider_format(%__MODULE__{} = msg, :openai) do
    base = %{role: to_string(msg.role), content: msg.content}

    case msg.role do
      :tool ->
        Map.merge(base, %{tool_call_id: msg.tool_call_id})

      :assistant when msg.tool_calls != nil ->
        Map.merge(base, %{tool_calls: Enum.map(msg.tool_calls, fn tc ->
          %{id: tc.id, type: "function", function: %{name: tc.function.name, arguments: tc.function.arguments}}
        end)})

      _ ->
        if msg.name, do: Map.put(base, :name, msg.name), else: base
    end
  end

  def to_provider_format(%__MODULE__{} = msg, :ollama) do
    # Ollama uses OpenAI-compatible format
    to_provider_format(msg, :openai)
  end

  @doc """
  Parse provider response into Message.
  """
  def from_provider_response(response, :claude) do
    content = case response["content"] do
      [%{"type" => "text", "text" => text} | _] -> text
      text when is_binary(text) -> text
      _ -> nil
    end

    tool_calls = case response["content"] do
      blocks when is_list(blocks) ->
        blocks
        |> Enum.filter(&(&1["type"] == "tool_use"))
        |> Enum.map(fn tc ->
          %{
            id: tc["id"],
            type: :function,
            function: %{
              name: tc["name"],
              arguments: Jason.encode!(tc["input"]),
            }
          }
        end)
      _ -> nil
    end

    %__MODULE__{
      id: response["id"],
      role: :assistant,
      content: content,
      tool_calls: if(tool_calls == [], do: nil, else: tool_calls),
      provider: :claude,
      model: response["model"],
      metadata: %{
        stop_reason: response["stop_reason"],
        usage: response["usage"],
      },
      created_at: DateTime.utc_now(),
    }
  end

  def from_provider_response(response, :openai) do
    choice = List.first(response["choices"]) || %{}
    message = choice["message"] || %{}

    tool_calls = case message["tool_calls"] do
      nil -> nil
      calls ->
        Enum.map(calls, fn tc ->
          %{
            id: tc["id"],
            type: :function,
            function: %{
              name: tc["function"]["name"],
              arguments: tc["function"]["arguments"],
            }
          }
        end)
    end

    %__MODULE__{
      id: response["id"],
      role: :assistant,
      content: message["content"],
      tool_calls: tool_calls,
      provider: :openai,
      model: response["model"],
      metadata: %{
        finish_reason: choice["finish_reason"],
        usage: response["usage"],
      },
      created_at: DateTime.utc_now(),
    }
  end

  def from_provider_response(response, :ollama) do
    # Ollama uses similar format to OpenAI
    message = response["message"] || %{}

    %__MODULE__{
      role: :assistant,
      content: message["content"],
      provider: :ollama,
      model: response["model"],
      metadata: %{
        done: response["done"],
        total_duration: response["total_duration"],
        eval_count: response["eval_count"],
      },
      created_at: DateTime.utc_now(),
    }
  end
end
