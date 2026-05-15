defmodule StudioCore.VersionUpdater do
  @moduledoc """
  Automatic Cursor version checker and updater.

  Periodically checks the upstream version tracking repository for new releases
  and updates the local version manifest.

  ## Configuration

      config :studio_core, StudioCore.VersionUpdater,
        check_interval: :timer.hours(1),
        upstream_url: "https://raw.githubusercontent.com/oslook/cursor-ai-downloads/main/version-history.json",
        auto_notify: true

  ## Usage

      # Manual check
      {:ok, {current, latest, new_versions}} = StudioCore.VersionUpdater.check_for_updates()

      # Force update
      {:ok, count} = StudioCore.VersionUpdater.update_versions()

      # Get cached status
      StudioCore.VersionUpdater.status()
  """

  use GenServer
  require Logger

  @default_upstream_url "https://raw.githubusercontent.com/oslook/cursor-ai-downloads/main/version-history.json"
  @default_check_interval :timer.hours(1)
  @versions_file_paths [
    Path.expand("~/.cursor-versions/cursor-versions.json"),
    "priv/cursor-versions.json"
  ]

  defstruct [
    :upstream_url,
    :check_interval,
    :last_check,
    :last_update,
    :current_latest,
    :upstream_latest,
    :new_versions_available,
    :timer_ref
  ]

  # ==========================================================================
  # Client API
  # ==========================================================================

  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @doc """
  Check for new versions without downloading.

  Returns `{:ok, {current_latest, upstream_latest, new_versions}}` where
  `new_versions` is a list of version strings newer than current.
  """
  def check_for_updates do
    GenServer.call(__MODULE__, :check_for_updates, :infinity)
  end

  @doc """
  Download and update the local versions manifest.

  Returns `{:ok, count}` where count is the number of new versions added.
  """
  def update_versions do
    GenServer.call(__MODULE__, :update_versions, :infinity)
  end

  @doc """
  Get the current updater status.

  Returns a map with:
  - `current_latest` - Latest version in local manifest
  - `upstream_latest` - Latest version from upstream (if checked)
  - `new_versions_available` - Count of new versions (if checked)
  - `last_check` - DateTime of last check
  - `last_update` - DateTime of last successful update
  """
  def status do
    GenServer.call(__MODULE__, :status)
  end

  @doc """
  Force an immediate check (ignores interval).
  """
  def force_check do
    GenServer.cast(__MODULE__, :force_check)
  end

  # ==========================================================================
  # GenServer Callbacks
  # ==========================================================================

  @impl true
  def init(opts) do
    config = Application.get_env(:studio_core, __MODULE__, [])

    upstream_url = opts[:upstream_url] || config[:upstream_url] || @default_upstream_url
    check_interval = opts[:check_interval] || config[:check_interval] || @default_check_interval

    state = %__MODULE__{
      upstream_url: upstream_url,
      check_interval: check_interval,
      current_latest: get_current_latest(),
      new_versions_available: 0
    }

    # Schedule first check after a short delay
    timer_ref = Process.send_after(self(), :periodic_check, :timer.seconds(30))

    Logger.info("VersionUpdater started, current latest: #{state.current_latest}")

    {:ok, %{state | timer_ref: timer_ref}}
  end

  @impl true
  def handle_call(:check_for_updates, _from, state) do
    case do_check(state) do
      {:ok, new_state, new_versions} ->
        {:reply, {:ok, {new_state.current_latest, new_state.upstream_latest, new_versions}},
         new_state}

      {:error, reason} ->
        {:reply, {:error, reason}, state}
    end
  end

  def handle_call(:update_versions, _from, state) do
    case do_update(state) do
      {:ok, new_state, count} ->
        {:reply, {:ok, count}, new_state}

      {:error, reason} ->
        {:reply, {:error, reason}, state}
    end
  end

  def handle_call(:status, _from, state) do
    status = %{
      current_latest: state.current_latest,
      upstream_latest: state.upstream_latest,
      new_versions_available: state.new_versions_available,
      last_check: state.last_check,
      last_update: state.last_update,
      check_interval_hours: state.check_interval / :timer.hours(1)
    }

    {:reply, status, state}
  end

  @impl true
  def handle_cast(:force_check, state) do
    case do_check(state) do
      {:ok, new_state, new_versions} ->
        if length(new_versions) > 0 do
          Logger.info(
            "New Cursor versions available: #{Enum.take(new_versions, 3) |> Enum.join(", ")}..."
          )

          # Notify via EventBus if available
          notify_new_versions(new_versions)
        end

        {:noreply, new_state}

      {:error, _reason} ->
        {:noreply, state}
    end
  end

  @impl true
  def handle_info(:periodic_check, state) do
    new_state =
      case do_check(state) do
        {:ok, updated_state, new_versions} ->
          if length(new_versions) > 0 do
            Logger.info("Found #{length(new_versions)} new Cursor versions")
            notify_new_versions(new_versions)
          end

          updated_state

        {:error, reason} ->
          Logger.warning("Version check failed: #{inspect(reason)}")
          state
      end

    # Schedule next check
    timer_ref = Process.send_after(self(), :periodic_check, state.check_interval)

    {:noreply, %{new_state | timer_ref: timer_ref}}
  end

  # ==========================================================================
  # Private Functions
  # ==========================================================================

  defp do_check(state) do
    Logger.debug("Checking for Cursor updates from #{state.upstream_url}")

    case fetch_upstream(state.upstream_url) do
      {:ok, upstream_versions} ->
        upstream_latest = get_first_version(upstream_versions)
        current_versions = get_current_versions()
        current_set = MapSet.new(current_versions)

        new_versions =
          upstream_versions
          |> Enum.map(& &1["version"])
          |> Enum.reject(&MapSet.member?(current_set, &1))

        new_state = %{
          state
          | upstream_latest: upstream_latest,
            new_versions_available: length(new_versions),
            last_check: DateTime.utc_now()
        }

        {:ok, new_state, new_versions}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp do_update(state) do
    Logger.info("Updating Cursor versions manifest...")

    case fetch_upstream(state.upstream_url) do
      {:ok, upstream_versions} ->
        current_count = length(get_current_versions())
        json = Jason.encode!(%{"versions" => upstream_versions}, pretty: true)

        # Write to all version file locations
        Enum.each(@versions_file_paths, fn path ->
          expanded = Path.expand(path)

          if File.exists?(Path.dirname(expanded)) do
            case File.write(expanded, json) do
              :ok ->
                Logger.debug("Updated #{expanded}")

              {:error, reason} ->
                Logger.warning("Failed to update #{expanded}: #{inspect(reason)}")
            end
          end
        end)

        new_count = length(upstream_versions)
        added = new_count - current_count

        # Reload the VersionRegistry if it's running
        reload_version_registry()

        new_state = %{
          state
          | current_latest: get_first_version(upstream_versions),
            upstream_latest: get_first_version(upstream_versions),
            new_versions_available: 0,
            last_update: DateTime.utc_now()
        }

        Logger.info("Version manifest updated: #{new_count} versions (#{added} new)")

        {:ok, new_state, max(added, 0)}

      {:error, reason} ->
        {:error, reason}
    end
  end

  defp fetch_upstream(url) do
    # Use httpc from Erlang stdlib (no extra dependencies)
    :inets.start()
    :ssl.start()

    case :httpc.request(:get, {String.to_charlist(url), []}, [], []) do
      {:ok, {{_, 200, _}, _headers, body}} ->
        case Jason.decode(to_string(body)) do
          {:ok, %{"versions" => versions}} ->
            {:ok, versions}

          {:ok, _other} ->
            {:error, :invalid_format}

          {:error, reason} ->
            {:error, {:json_decode, reason}}
        end

      {:ok, {{_, status, _}, _, _}} ->
        {:error, {:http_status, status}}

      {:error, reason} ->
        {:error, {:http, reason}}
    end
  end

  defp get_current_latest do
    case get_current_versions() do
      [first | _] -> first
      [] -> nil
    end
  end

  defp get_current_versions do
    @versions_file_paths
    |> Enum.map(&Path.expand/1)
    |> Enum.find(&File.exists?/1)
    |> case do
      nil ->
        []

      path ->
        case File.read(path) do
          {:ok, content} ->
            case Jason.decode(content) do
              {:ok, %{"versions" => versions}} ->
                Enum.map(versions, & &1["version"])

              _ ->
                []
            end

          _ ->
            []
        end
    end
  end

  defp get_first_version([%{"version" => v} | _]), do: v
  defp get_first_version(_), do: nil

  defp reload_version_registry do
    case Process.whereis(StudioCore.VersionRegistry) do
      nil ->
        :ok

      _pid ->
        # VersionRegistry will pick up the new file on next list_versions call
        # Could add a reload function to VersionRegistry for hot reload
        :ok
    end
  end

  defp notify_new_versions(versions) do
    # Try to notify via EventBus if available
    if Code.ensure_loaded?(StudioCore.EventBus) and
         function_exported?(StudioCore.EventBus, :broadcast, 2) do
      StudioCore.EventBus.broadcast(:version_update, %{
        type: :new_versions_available,
        versions: versions,
        count: length(versions),
        latest: List.first(versions)
      })
    end
  end
end
