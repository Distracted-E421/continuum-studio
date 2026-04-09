#!/usr/bin/env nu

# Cursor Launch Wrapper for Continuum Studio
# Handles NixOS-specific requirements and version management

def main [
    version: string = "latest"  # Version to launch (e.g., "3.0.16" or "latest")
    --folder: string = ""       # Optional folder/workspace to open
    --debug                     # Enable debug mode with verbose logging
    --list                      # List available versions and exit
] {
    let versions_dir = $"($env.HOME)/.cursor-versions"
    let downloads_dir = $"($versions_dir)/downloads"

    # Ensure directories exist
    mkdir $downloads_dir

    if $list {
        list_versions $downloads_dir
        return
    }

    let appimage = find_appimage $downloads_dir $version

    if ($appimage | is-empty) {
        print $"(ansi red)Error:(ansi reset) No AppImage found for version '($version)'"
        print $"Available versions:"
        list_versions $downloads_dir
        exit 1
    }

    print $"(ansi green)Launching:(ansi reset) ($appimage | path basename)"

    # Build version-specific data directory
    let version_tag = ($appimage | path basename | parse "cursor-{version}-{rest}" | get version.0? | default "latest")
    let data_dir = $"($env.HOME)/.cursor-($version_tag)"
    let extensions_dir = $"($data_dir)/extensions"

    # Create directories
    mkdir $data_dir
    mkdir $extensions_dir

    # Build arguments
    let user_data_arg = $"--user-data-dir=($data_dir)"
    let extensions_arg = $"--extensions-dir=($extensions_dir)"

    mut args = [$user_data_arg, $extensions_arg]

    if not ($folder | is-empty) {
        $args = ($args | append $folder)
    }

    if $debug {
        $args = ($args | append "--verbose")
        print $"(ansi blue)Debug:(ansi reset) Data dir: ($data_dir)"
        print $"(ansi blue)Debug:(ansi reset) Extensions: ($extensions_dir)"
        print $"(ansi blue)Debug:(ansi reset) Args: ($args)"
    }

    # Try appimage-run first (NixOS), then direct execution
    let result = try {
        if (which appimage-run | is-not-empty) {
            print $"(ansi blue)Using:(ansi reset) appimage-run"
            ^appimage-run $appimage ...$args &
        } else {
            print $"(ansi yellow)Note:(ansi reset) appimage-run not found, trying direct execution"
            ^$appimage ...$args &
        }
    } catch {
        print $"(ansi red)Launch failed:(ansi reset) ($in)"
        exit 1
    }

    print $"(ansi green)✓(ansi reset) Cursor ($version_tag) launched"
}

def list_versions [downloads_dir: string] {
    let appimages = (ls $downloads_dir 
        | where name =~ 'AppImage$' 
        | sort-by modified --reverse
        | each { |f|
            let name = ($f.name | path basename)
            let version = ($name | parse "cursor-{version}-{rest}" | get version.0? | default "unknown")
            let size_mb = (($f.size | into int) / 1_000_000 | math round --precision 1)
            {
                version: $version
                file: $name
                size: $"($size_mb)MB"
                modified: ($f.modified | format date "%Y-%m-%d %H:%M")
            }
        }
    )

    if ($appimages | is-empty) {
        print "No Cursor AppImages found."
        print $"Download directory: ($downloads_dir)"
    } else {
        print ($appimages | table)
    }
}

def find_appimage [downloads_dir: string, version: string] {
    if $version == "latest" {
        # Find most recently modified AppImage
        let latest = (ls $downloads_dir 
            | where name =~ 'AppImage$'
            | sort-by modified --reverse
            | first
        )
        if ($latest | is-empty) {
            return ""
        }
        return $latest.name
    }

    # Find specific version
    let pattern = $"cursor-($version)"
    let matches = (ls $downloads_dir 
        | where name =~ $pattern
        | where name =~ 'AppImage$'
    )

    if ($matches | is-empty) {
        return ""
    }

    return ($matches | first).name
}
