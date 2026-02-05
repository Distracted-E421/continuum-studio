#!/usr/bin/env nu
# Continuum Studio Build Watcher
#
# Monitors the continuum-studio git repo for new commits and automatically
# builds nightly releases. Designed to run as a systemd user service.
#
# Features:
#   - Watches .git/refs/heads/ for commit changes via inotifywait
#   - Builds cargo release on detected changes
#   - Stores artifacts in ~/.continuum/builds/
#   - Maintains build history log
#   - Crash-safe: last known good build always preserved
#
# Usage:
#   nu build-watcher.nu                    # Run with defaults
#   nu build-watcher.nu --repo ~/myrepo    # Custom repo path
#   nu build-watcher.nu --once             # Build once and exit
#   nu build-watcher.nu --channel nightly  # Set build channel

# Configuration
const CONTINUUM_DIR = ($env.HOME | path join ".continuum")
const BUILDS_DIR = ($env.HOME | path join ".continuum" "builds")
const BIN_DIR = ($env.HOME | path join ".continuum" "bin")
const HISTORY_FILE = ($env.HOME | path join ".continuum" "builds" "build-history.json")

def main [
    --repo: string = ""        # Path to continuum-studio repo
    --channel: string = "nightly"  # Build channel (nightly, stable, beta)
    --once                     # Build once and exit (no watch)
    --poll-interval: int = 30  # Poll interval in seconds (fallback if inotifywait unavailable)
    --verbose                  # Verbose logging
] {
    let repo_path = if $repo == "" {
        $env.HOME | path join "continuum-studio"
    } else {
        $repo
    }

    # Validate repo exists
    if not ($repo_path | path exists) {
        print $"ERROR: Repository not found at ($repo_path)"
        exit 1
    }

    if not ($repo_path | path join "ui-iced" "Cargo.toml" | path exists) {
        print $"ERROR: Not a valid continuum-studio repo (missing ui-iced/Cargo.toml)"
        exit 1
    }

    # Ensure directories exist
    mkdir ($BUILDS_DIR | path join $channel)
    mkdir $BIN_DIR

    # Initialize history if it doesn't exist
    if not ($HISTORY_FILE | path exists) {
        "[]" | save $HISTORY_FILE
    }

    print $"Continuum Build Watcher starting..."
    print $"  Repo: ($repo_path)"
    print $"  Channel: ($channel)"
    print $"  Builds dir: ($BUILDS_DIR)"

    if $once {
        # Single build mode
        let result = (do_build $repo_path $channel $verbose)
        if $result.success {
            print $"Build successful: ($result.commit)"
        } else {
            print $"Build failed: ($result.error)"
            exit 1
        }
    } else {
        # Watch mode - monitor for changes
        let last_commit = (get_current_commit $repo_path)
        print $"  Current commit: ($last_commit)"
        print $"  Watching for changes..."

        # Do an initial build if no nightly exists
        let nightly_binary = ($BUILDS_DIR | path join $channel "continuum-studio")
        if not ($nightly_binary | path exists) {
            print "No existing nightly build found, building..."
            let result = (do_build $repo_path $channel $verbose)
            if $result.success {
                print $"Initial build successful: ($result.commit)"
            } else {
                print $"Initial build failed: ($result.error)"
            }
        }

        # Watch loop using inotifywait if available, otherwise poll
        watch_loop $repo_path $channel $poll_interval $verbose
    }
}

# Watch for git changes and trigger builds
def watch_loop [repo_path: string, channel: string, poll_interval: int, verbose: bool] {
    mut last_commit = (get_current_commit $repo_path)

    loop {
        # Wait for changes - try inotifywait first, fall back to polling
        let has_inotify = (which inotifywait | length) > 0

        if $has_inotify {
            # Use inotifywait to watch .git/refs/heads/ for changes
            # Timeout after poll_interval seconds to check anyway
            let git_refs = ($repo_path | path join ".git" "refs" "heads")
            try {
                ^inotifywait -q -t $poll_interval -e modify -e create -e moved_to $git_refs 2>/dev/null
            }
        } else {
            # Fallback: poll
            sleep ($poll_interval * 1sec)
        }

        # Check if commit changed
        let current_commit = (get_current_commit $repo_path)
        if $current_commit != $last_commit {
            print $"\n--- Commit change detected ---"
            print $"  Old: ($last_commit)"
            print $"  New: ($current_commit)"

            let result = (do_build $repo_path $channel $verbose)
            if $result.success {
                print $"Build successful: ($result.version) @ ($result.commit)"
            } else {
                print $"Build FAILED: ($result.error)"
                print "Last known good build preserved."
            }

            $last_commit = $current_commit
        } else if $verbose {
            print $"No changes detected (commit: ($current_commit | str substring 0..8))"
        }
    }
}

# Perform a build
def do_build [repo_path: string, channel: string, verbose: bool]: nothing -> record {
    let start_time = (date now)
    let commit = (get_current_commit $repo_path)
    let short_commit = ($commit | str substring 0..8)
    let branch = (get_current_branch $repo_path)

    print $"Building from commit ($short_commit) on branch ($branch)..."

    # Read version from Cargo.toml
    let cargo_toml = ($repo_path | path join "ui-iced" "Cargo.toml" | open --raw)
    let version = ($cargo_toml | parse --regex 'version = "(?P<ver>[^"]+)"' | get 0?.ver? | default "0.0.0")

    let build_dir = ($BUILDS_DIR | path join $channel)
    let metadata_path = ($build_dir | path join "metadata.json")
    let binary_dest = ($build_dir | path join "continuum-studio")

    # Build
    let build_result = try {
        if $verbose {
            print "Running: cargo build --release"
        }
        let output = (^cargo build --release 2>&1 | complete)

        if $output.exit_code != 0 {
            { success: false, error: $output.stdout }
        } else {
            { success: true, error: "" }
        }
    } catch {|e|
        { success: false, error: ($e | get msg? | default "Unknown build error") }
    }

    if not $build_result.success {
        # Log failed build
        let entry = {
            timestamp: (date now | format date "%Y-%m-%dT%H:%M:%S%z")
            commit: $commit
            branch: $branch
            channel: $channel
            version: $version
            success: false
            error: $build_result.error
            duration_secs: ((date now) - $start_time | into int) / 1_000_000_000
        }
        append_history $entry

        return {
            success: false
            commit: $commit
            version: $version
            error: $build_result.error
        }
    }

    # Copy binary to build directory
    let source_binary = ($repo_path | path join "ui-iced" "target" "release" "continuum-studio-iced")
    if ($source_binary | path exists) {
        cp $source_binary $binary_dest
        chmod 755 $binary_dest

        # Write metadata
        let metadata = {
            version: $version
            channel: $channel
            commit: $commit
            branch: $branch
            build_date: (date now | format date "%Y-%m-%dT%H:%M:%S%z")
            binary_size: (ls $binary_dest | get 0.size)
        }
        $metadata | to json | save -f $metadata_path

        # Update symlink in bin directory
        let bin_link = ($BIN_DIR | path join "continuum-studio")
        rm -f $bin_link
        # Use absolute path for symlink
        ^ln -sf $binary_dest $bin_link

        # Log successful build
        let duration = ((date now) - $start_time | into int) / 1_000_000_000
        let entry = {
            timestamp: (date now | format date "%Y-%m-%dT%H:%M:%S%z")
            commit: $commit
            branch: $branch
            channel: $channel
            version: $version
            success: true
            error: null
            duration_secs: $duration
        }
        append_history $entry

        print $"  Binary: ($binary_dest)"
        print $"  Size: (ls $binary_dest | get 0.size)"
        print $"  Duration: ($duration | math round --precision 1)s"

        return {
            success: true
            commit: $commit
            version: $version
            error: ""
        }
    } else {
        let error = $"Binary not found at ($source_binary)"
        let entry = {
            timestamp: (date now | format date "%Y-%m-%dT%H:%M:%S%z")
            commit: $commit
            branch: $branch
            channel: $channel
            version: $version
            success: false
            error: $error
            duration_secs: ((date now) - $start_time | into int) / 1_000_000_000
        }
        append_history $entry

        return {
            success: false
            commit: $commit
            version: $version
            error: $error
        }
    }
}

# Get the current HEAD commit hash
def get_current_commit [repo_path: string]: nothing -> string {
    try {
        ^git -C $repo_path rev-parse HEAD | str trim
    } catch {
        "unknown"
    }
}

# Get the current branch name
def get_current_branch [repo_path: string]: nothing -> string {
    try {
        ^git -C $repo_path rev-parse --abbrev-ref HEAD | str trim
    } catch {
        "unknown"
    }
}

# Append an entry to the build history
def append_history [entry: record] {
    try {
        mut history = if ($HISTORY_FILE | path exists) {
            open $HISTORY_FILE
        } else {
            []
        }

        $history = ($history | append $entry)

        # Keep last 100 entries
        if ($history | length) > 100 {
            $history = ($history | last 100)
        }

        $history | to json | save -f $HISTORY_FILE
    } catch {|e|
        print $"Warning: Failed to update build history: ($e | get msg? | default 'unknown')"
    }
}

# Helper: make file executable
def chmod [mode: string, path: string] {
    ^chmod $mode $path
}
