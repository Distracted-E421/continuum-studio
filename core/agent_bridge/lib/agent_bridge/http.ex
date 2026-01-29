defmodule AgentBridge.HTTP do
  @moduledoc """
  HTTP client abstraction using Req.

  Provides a consistent interface for HTTP requests with:
  - Automatic retries with exponential backoff
  - Timeout handling
  - SSL/TLS configuration
  - Request/response logging
  - Streaming support
  """

  require Logger

  @default_timeout 60_000
  @default_retry_count 3
  @default_retry_delay 1_000

  @doc """
  Make a POST request with JSON body.

  ## Options

  - `:timeout` - Request timeout in milliseconds (default: 60_000)
  - `:retry_count` - Number of retries on failure (default: 3)
  - `:retry_delay` - Base delay between retries in ms (default: 1_000)
  - `:headers` - Additional headers
  """
  def post(url, body, opts \\ []) do
    timeout = opts[:timeout] || @default_timeout
    headers = opts[:headers] || []

    req_opts = [
      url: url,
      method: :post,
      json: body,
      headers: headers,
      receive_timeout: timeout,
      retry: retry_opts(opts),
    ]

    case Req.request(req_opts) do
      {:ok, %Req.Response{status: status, body: response_body}} when status in 200..299 ->
        {:ok, %{status: status, body: response_body}}

      {:ok, %Req.Response{status: status, body: response_body}} ->
        {:error, {:http_error, status, response_body}}

      {:error, %Req.TransportError{reason: reason}} ->
        {:error, {:transport_error, reason}}

      {:error, reason} ->
        {:error, reason}
    end
  end

  @doc """
  Make a GET request.
  """
  def get(url, opts \\ []) do
    timeout = opts[:timeout] || @default_timeout
    headers = opts[:headers] || []

    req_opts = [
      url: url,
      method: :get,
      headers: headers,
      receive_timeout: timeout,
      retry: retry_opts(opts),
    ]

    case Req.request(req_opts) do
      {:ok, %Req.Response{status: status, body: response_body}} when status in 200..299 ->
        {:ok, %{status: status, body: response_body}}

      {:ok, %Req.Response{status: status, body: response_body}} ->
        {:error, {:http_error, status, response_body}}

      {:error, reason} ->
        {:error, reason}
    end
  end

  @doc """
  Make a streaming POST request.

  The callback function receives chunks as they arrive.
  Chunks are strings (one line at a time for SSE).

  ## Example

      HTTP.stream_post(url, body, fn chunk ->
        IO.write(chunk)
      end)
  """
  def stream_post(url, body, callback, opts \\ []) do
    timeout = opts[:timeout] || @default_timeout * 2
    headers = opts[:headers] || []

    # For streaming, we need to use :into option
    req_opts = [
      url: url,
      method: :post,
      json: body,
      headers: headers,
      receive_timeout: timeout,
      into: stream_collector(callback),
    ]

    case Req.request(req_opts) do
      {:ok, %Req.Response{status: status}} when status in 200..299 ->
        :ok

      {:ok, %Req.Response{status: status, body: error_body}} ->
        {:error, {:http_error, status, error_body}}

      {:error, reason} ->
        {:error, reason}
    end
  end

  @doc """
  Create a base Req client with common configuration.

  Useful for providers that need to make multiple requests.
  """
  def new_client(base_url, opts \\ []) do
    headers = opts[:headers] || []
    timeout = opts[:timeout] || @default_timeout

    Req.new(
      base_url: base_url,
      headers: headers,
      receive_timeout: timeout,
      retry: retry_opts(opts)
    )
  end

  # Private functions

  defp retry_opts(opts) do
    retry_count = opts[:retry_count] || @default_retry_count
    retry_delay = opts[:retry_delay] || @default_retry_delay

    [
      max_retries: retry_count,
      delay: retry_delay,
      # Only retry on transient errors
      retry_log_level: :warning,
    ]
  end

  defp stream_collector(callback) do
    fn {:data, data}, acc ->
      # SSE data comes in chunks, process line by line
      lines = String.split(data, "\n")
      Enum.each(lines, fn line ->
        if line != "" do
          callback.(line)
        end
      end)
      {:cont, acc}
    end
  end
end
