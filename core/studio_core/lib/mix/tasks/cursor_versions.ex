defmodule Mix.Tasks.Cursor.Versions do
  @shortdoc "Manage Cursor IDE versions"
  @moduledoc """
  Cursor Version Manager CLI

  ## Commands

      mix cursor.versions list            # List all available versions
      mix cursor.versions installed       # List installed versions
      mix cursor.versions download VER    # Download a specific version
      mix cursor.versions run VER [PATH]  # Run a version (optionally open folder)
      mix cursor.versions default VER     # Set default version
      mix cursor.versions uninstall VER   # Remove an installed version
      mix cursor.versions stats           # Show version statistics

  ## Options

      --era FILTER    Filter by era: latest, custom_modes, classic
      --limit N       Limit number of results
      --force         Force re-download even if installed

  ## Examples

      mix cursor.versions list --era custom_modes
      mix cursor.versions download 2.4.21
      mix cursor.versions run 2.0.77 /home/user/myproject
      mix cursor.versions default 2.0.77
  """

  use Mix.Task

  @switches [
    era: :string,
    limit: :integer,
    force: :boolean
  ]

  @impl Mix.Task
  def run(args) do
    # Start the application
    {:ok, _} = Application.ensure_all_started(:studio_core)

    {opts, args, _} = OptionParser.parse(args, switches: @switches)

    case args do
      ["list" | _] -> list_versions(opts)
      ["installed" | _] -> list_installed()
      ["download", version | _] -> download(version, opts)
      ["run", version | rest] -> run_version(version, rest, opts)
      ["default", version | _] -> set_default(version)
      ["uninstall", version | _] -> uninstall(version)
      ["stats" | _] -> show_stats()
      ["latest" | _] -> show_latest()
      [] -> help()
      _ -> help()
    end
  end

  defp list_versions(opts) do
    filter_opts = build_filter_opts(opts)

    case StudioCore.VersionRegistry.list_versions(filter_opts) do
      {:ok, versions} ->
        IO.puts(color("Cursor Versions", :cyan))
        IO.puts(String.duplicate("═", 60))
        IO.puts("")

        # Group by era
        versions
        |> Enum.group_by(& &1.era)
        |> Enum.sort_by(fn {era, _} -> era_order(era) end)
        |> Enum.each(fn {era, era_versions} ->
          IO.puts(color("  #{format_era(era)}", :yellow))
          Enum.each(era_versions, &print_version/1)
          IO.puts("")
        end)

      {:error, reason} ->
        IO.puts(color("Error: #{inspect(reason)}", :red))
    end
  end

  defp list_installed do
    case StudioCore.VersionRegistry.list_installed() do
      {:ok, versions} ->
        IO.puts(color("Installed Cursor Versions", :cyan))
        IO.puts(String.duplicate("═", 60))

        if Enum.empty?(versions) do
          IO.puts("  (no versions installed)")
        else
          Enum.each(versions, fn v ->
            IO.puts("  #{v.version} (#{v.size_human})")
          end)

          total = Enum.reduce(versions, 0, & &1.size + &2)
          IO.puts("")
          IO.puts("  Total: #{format_bytes(total)}")
        end

      {:error, reason} ->
        IO.puts(color("Error: #{inspect(reason)}", :red))
    end
  end

  defp download(version, opts) do
    IO.puts("Downloading Cursor #{version}...")

    case StudioCore.VersionRegistry.download(version, force: opts[:force]) do
      {:ok, :already_installed} ->
        IO.puts(color("✓ Version #{version} already installed", :green))

      {:ok, path} ->
        IO.puts(color("✓ Installed: #{path}", :green))

      {:error, :version_not_found} ->
        IO.puts(color("✗ Version #{version} not found", :red))

      {:error, :platform_not_available} ->
        IO.puts(color("✗ Version #{version} not available for this platform", :red))

      {:error, reason} ->
        IO.puts(color("✗ Download failed: #{inspect(reason)}", :red))
    end
  end

  defp run_version(version, rest, _opts) do
    folder = List.first(rest)

    run_opts =
      if folder do
        [folder: folder]
      else
        []
      end

    case StudioCore.VersionRegistry.run(version, run_opts) do
      {:ok, info} ->
        IO.puts(color("✓ Running Cursor #{info.version}", :green))
        IO.puts("  Data dir: #{info.data_dir}")
        if folder, do: IO.puts("  Workspace: #{folder}")

      {:error, :not_installed} ->
        IO.puts(color("✗ Version #{version} not installed", :red))
        IO.puts("  Run: mix cursor.versions download #{version}")

      {:error, reason} ->
        IO.puts(color("✗ Failed to run: #{inspect(reason)}", :red))
    end
  end

  defp set_default(version) do
    case StudioCore.VersionRegistry.set_default(version) do
      {:ok, _} ->
        IO.puts(color("✓ Default set to #{version}", :green))
        IO.puts("  Use 'cursor-default' to run")

      {:error, :not_installed} ->
        IO.puts(color("✗ Version #{version} not installed", :red))

      {:error, reason} ->
        IO.puts(color("✗ Failed: #{inspect(reason)}", :red))
    end
  end

  defp uninstall(version) do
    case StudioCore.VersionRegistry.uninstall(version) do
      {:ok, :uninstalled} ->
        IO.puts(color("✓ Uninstalled #{version}", :green))

      {:error, :not_installed} ->
        IO.puts(color("✗ Version #{version} not installed", :red))

      {:error, reason} ->
        IO.puts(color("✗ Failed: #{inspect(reason)}", :red))
    end
  end

  defp show_stats do
    case StudioCore.VersionRegistry.stats() do
      {:ok, stats} ->
        IO.puts(color("Version Registry Statistics", :cyan))
        IO.puts(String.duplicate("═", 60))
        IO.puts("  Total versions available: #{stats.total_versions}")
        IO.puts("  Versions installed: #{stats.installed_count}")
        IO.puts("  Disk usage: #{stats.total_disk_usage_human}")
        IO.puts("  Platform: #{stats.platform}")
        IO.puts("  Versions directory: #{stats.versions_dir}")

      {:error, reason} ->
        IO.puts(color("Error: #{inspect(reason)}", :red))
    end
  end

  defp show_latest do
    case StudioCore.VersionRegistry.latest() do
      {:ok, version} ->
        IO.puts("Latest version: #{version.version} (#{version.date})")

      {:error, reason} ->
        IO.puts(color("Error: #{inspect(reason)}", :red))
    end
  end

  defp help do
    IO.puts(@moduledoc)
  end

  # Helpers

  defp build_filter_opts(opts) do
    []
    |> maybe_add(:era, parse_era(opts[:era]))
    |> maybe_add(:limit, opts[:limit])
  end

  defp maybe_add(opts, _key, nil), do: opts
  defp maybe_add(opts, key, value), do: Keyword.put(opts, key, value)

  defp parse_era(nil), do: nil
  defp parse_era("latest"), do: :latest
  defp parse_era("custom_modes"), do: :custom_modes
  defp parse_era("classic"), do: :classic
  defp parse_era(_), do: nil

  defp print_version(v) do
    installed = if v.installed, do: color(" ✓", :green), else: ""
    notes = if v.notes, do: color(" (#{v.notes})", :yellow), else: ""
    IO.puts("    #{v.version}#{installed}#{notes}")
  end

  defp format_era(:latest), do: "Latest (2.2.x - 2.4.x) - No Custom Modes"
  defp format_era(:post_custom_modes), do: "Post-Custom Modes (2.1.x)"
  defp format_era(:custom_modes), do: "Custom Modes Era (2.0.x) - HAS Custom Modes!"
  defp format_era(:classic), do: "Classic (1.x)"
  defp format_era(era), do: "#{era}"

  defp era_order(:latest), do: 0
  defp era_order(:post_custom_modes), do: 1
  defp era_order(:custom_modes), do: 2
  defp era_order(:classic), do: 3
  defp era_order(_), do: 99

  defp format_bytes(bytes) when bytes < 1024, do: "#{bytes} B"
  defp format_bytes(bytes) when bytes < 1024 * 1024, do: "#{Float.round(bytes / 1024, 1)} KB"
  defp format_bytes(bytes) when bytes < 1024 * 1024 * 1024, do: "#{Float.round(bytes / (1024 * 1024), 1)} MB"
  defp format_bytes(bytes), do: "#{Float.round(bytes / (1024 * 1024 * 1024), 1)} GB"

  defp color(text, :red), do: IO.ANSI.red() <> text <> IO.ANSI.reset()
  defp color(text, :green), do: IO.ANSI.green() <> text <> IO.ANSI.reset()
  defp color(text, :yellow), do: IO.ANSI.yellow() <> text <> IO.ANSI.reset()
  defp color(text, :cyan), do: IO.ANSI.cyan() <> text <> IO.ANSI.reset()
end
