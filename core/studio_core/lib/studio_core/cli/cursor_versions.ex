defmodule StudioCore.CLI.CursorVersions do
  @moduledoc """
  Standalone CLI for Cursor Version Management.

  This module provides an escript entry point for managing Cursor versions
  without requiring Mix to be installed.

  ## Usage

      cursor-versions list              # List all available versions
      cursor-versions installed         # List installed versions
      cursor-versions download VER      # Download a specific version
      cursor-versions run VER [PATH]    # Run a version (optionally open folder)
      cursor-versions default VER       # Set default version
      cursor-versions uninstall VER     # Remove an installed version
      cursor-versions stats             # Show version statistics
      cursor-versions latest            # Show latest version

  ## Options

      --era FILTER    Filter by era: latest, custom_modes, classic
      --limit N       Limit number of results
      --force         Force re-download even if installed
  """

  alias StudioCore.VersionRegistry
  require Logger

  @switches [
    era: :string,
    limit: :integer,
    force: :boolean,
    help: :boolean
  ]

  @aliases [
    h: :help,
    e: :era,
    l: :limit,
    f: :force
  ]

  def main(args) do
    # Suppress all logs for clean CLI output
    Application.put_env(:logger, :level, :none)
    Application.ensure_all_started(:logger)
    Logger.configure(level: :none)
    
    # Start Jason for JSON parsing
    Application.ensure_all_started(:jason)
    
    # Start only the version registry (not the full application)
    case Process.whereis(VersionRegistry) do
      nil -> 
        {:ok, _} = VersionRegistry.start_link()
      _pid -> 
        :ok
    end

    {opts, args, _} = OptionParser.parse(args, switches: @switches, aliases: @aliases)

    if opts[:help] do
      help()
    else
      case args do
        ["list" | _] -> list_versions(opts)
        ["ls" | _] -> list_versions(opts)
        ["installed" | _] -> list_installed()
        ["download", version | _] -> download(version, opts)
        ["dl", version | _] -> download(version, opts)
        ["run", version | rest] -> run_version(version, rest, opts)
        ["default", version | _] -> set_default(version)
        ["uninstall", version | _] -> uninstall(version)
        ["stats" | _] -> show_stats()
        ["latest" | _] -> show_latest()
        ["url", version | _] -> show_url(version)
        ["help" | _] -> help()
        [] -> help()
        [unknown | _] -> unknown_command(unknown)
      end
    end
  end

  defp list_versions(opts) do
    filter_opts = build_filter_opts(opts)

    case VersionRegistry.list_versions(filter_opts) do
      {:ok, versions} ->
        puts_color("Cursor Versions", :cyan)
        puts_color(String.duplicate("═", 60), :cyan)
        IO.puts("")

        # Group by era
        versions
        |> Enum.group_by(& &1.era)
        |> Enum.sort_by(fn {era, _} -> era_order(era) end)
        |> Enum.each(fn {era, era_versions} ->
          puts_color("  #{format_era(era)}", :yellow)
          Enum.each(era_versions, &print_version/1)
          IO.puts("")
        end)

        IO.puts("Total: #{length(versions)} versions")

      {:error, reason} ->
        puts_color("Error: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp list_installed do
    case VersionRegistry.list_installed() do
      {:ok, versions} ->
        puts_color("Installed Cursor Versions", :cyan)
        puts_color(String.duplicate("═", 60), :cyan)

        if Enum.empty?(versions) do
          IO.puts("  (no versions installed)")
          IO.puts("")
          IO.puts("  Use: cursor-versions download <version>")
        else
          Enum.each(versions, fn v ->
            IO.puts("  #{v.version} (#{v.size_human})")
          end)

          total = Enum.reduce(versions, 0, & &1.size + &2)
          IO.puts("")
          IO.puts("  Total: #{format_bytes(total)}")
        end

      {:error, reason} ->
        puts_color("Error: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp download(version, opts) do
    IO.puts("Downloading Cursor #{version}...")

    case VersionRegistry.download(version, force: opts[:force]) do
      {:ok, :already_installed} ->
        puts_color("✓ Version #{version} already installed", :green)

      {:ok, path} ->
        puts_color("✓ Installed: #{path}", :green)

      {:error, :version_not_found} ->
        puts_color("✗ Version #{version} not found", :red)
        IO.puts("  Use: cursor-versions list")
        System.halt(1)

      {:error, :platform_not_available} ->
        puts_color("✗ Version #{version} not available for this platform", :red)
        System.halt(1)

      {:error, reason} ->
        puts_color("✗ Download failed: #{inspect(reason)}", :red)
        System.halt(1)
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

    case VersionRegistry.run(version, run_opts) do
      {:ok, info} ->
        puts_color("✓ Running Cursor #{info.version}", :green)
        IO.puts("  Data dir: #{info.data_dir}")
        if folder, do: IO.puts("  Workspace: #{folder}")

      {:error, :not_installed} ->
        puts_color("✗ Version #{version} not installed", :red)
        IO.puts("  Run: cursor-versions download #{version}")
        System.halt(1)

      {:error, reason} ->
        puts_color("✗ Failed to run: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp set_default(version) do
    case VersionRegistry.set_default(version) do
      {:ok, _} ->
        puts_color("✓ Default set to #{version}", :green)
        IO.puts("  Use 'cursor-default' to run")

      {:error, :not_installed} ->
        puts_color("✗ Version #{version} not installed", :red)
        System.halt(1)

      {:error, reason} ->
        puts_color("✗ Failed: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp uninstall(version) do
    case VersionRegistry.uninstall(version) do
      {:ok, :uninstalled} ->
        puts_color("✓ Uninstalled #{version}", :green)

      {:error, :not_installed} ->
        puts_color("✗ Version #{version} not installed", :red)
        System.halt(1)

      {:error, reason} ->
        puts_color("✗ Failed: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp show_stats do
    case VersionRegistry.stats() do
      {:ok, stats} ->
        puts_color("Version Registry Statistics", :cyan)
        puts_color(String.duplicate("═", 60), :cyan)
        IO.puts("  Total versions available: #{stats.total_versions}")
        IO.puts("  Versions installed: #{stats.installed_count}")
        IO.puts("  Disk usage: #{stats.total_disk_usage_human}")
        IO.puts("  Platform: #{stats.platform}")
        IO.puts("  Versions directory: #{stats.versions_dir}")

      {:error, reason} ->
        puts_color("Error: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp show_latest do
    case VersionRegistry.latest() do
      {:ok, version} ->
        puts_color("Latest: #{version.version}", :green)
        IO.puts("  Date: #{version.date}")
        IO.puts("  Era: #{format_era(version.era)}")
        if version.installed do
          puts_color("  Status: Installed ✓", :green)
        else
          IO.puts("  Status: Not installed")
          IO.puts("  Run: cursor-versions download #{version.version}")
        end

      {:error, reason} ->
        puts_color("Error: #{inspect(reason)}", :red)
        System.halt(1)
    end
  end

  defp show_url(version) do
    case VersionRegistry.download_url(version) do
      {:ok, url} ->
        IO.puts(url)

      {:error, :not_found} ->
        puts_color("Version #{version} not found", :red)
        System.halt(1)
    end
  end

  defp unknown_command(cmd) do
    puts_color("Unknown command: #{cmd}", :red)
    IO.puts("")
    help()
    System.halt(1)
  end

  defp help do
    IO.puts("""
    cursor-versions - Manage Cursor IDE versions

    #{IO.ANSI.cyan()}USAGE:#{IO.ANSI.reset()}
        cursor-versions <command> [options]

    #{IO.ANSI.cyan()}COMMANDS:#{IO.ANSI.reset()}
        list, ls              List all available versions
        installed             List installed versions
        download, dl <ver>    Download a specific version
        run <ver> [path]      Run a version with optional workspace
        default <ver>         Set default version
        uninstall <ver>       Remove an installed version
        stats                 Show registry statistics
        latest                Show latest version
        url <ver>             Print download URL for a version
        help                  Show this help

    #{IO.ANSI.cyan()}OPTIONS:#{IO.ANSI.reset()}
        -e, --era <era>       Filter by era: latest, custom_modes, classic
        -l, --limit <n>       Limit number of results
        -f, --force           Force re-download even if installed
        -h, --help            Show help

    #{IO.ANSI.cyan()}EXAMPLES:#{IO.ANSI.reset()}
        cursor-versions list --era custom_modes
        cursor-versions download 2.0.77
        cursor-versions run 2.0.77 /home/user/myproject
        cursor-versions default 2.0.77

    #{IO.ANSI.cyan()}ERAS:#{IO.ANSI.reset()}
        latest        2.2.x - 2.4.x (no custom modes)
        custom_modes  2.0.x (HAS custom modes - important for some features)
        classic       1.x (pre-2.0)

    #{IO.ANSI.yellow()}NOTE:#{IO.ANSI.reset()} Version 2.0.77 is the last version with custom modes support.
    """)
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
    installed = if v.installed, do: " #{IO.ANSI.green()}✓#{IO.ANSI.reset()}", else: ""
    notes = if v.notes, do: " #{IO.ANSI.yellow()}(#{v.notes})#{IO.ANSI.reset()}", else: ""
    IO.puts("    #{v.version}#{installed}#{notes}")
  end

  defp format_era(:latest), do: "Latest (2.2.x - 2.4.x)"
  defp format_era(:post_custom_modes), do: "Post-Custom Modes (2.1.x)"
  defp format_era(:custom_modes), do: "Custom Modes Era (2.0.x)"
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

  defp puts_color(text, :red), do: IO.puts(IO.ANSI.red() <> text <> IO.ANSI.reset())
  defp puts_color(text, :green), do: IO.puts(IO.ANSI.green() <> text <> IO.ANSI.reset())
  defp puts_color(text, :yellow), do: IO.puts(IO.ANSI.yellow() <> text <> IO.ANSI.reset())
  defp puts_color(text, :cyan), do: IO.puts(IO.ANSI.cyan() <> text <> IO.ANSI.reset())
end
