#!/bin/sh
# Launcher for continuum-studio-iced that preserves the right environment
# This script should be sourced from a graphical session (e.g., Cursor terminal)
# and then called via SSH or other methods.

export LD_LIBRARY_PATH="$1"
export DISPLAY="${2:-:0}"
export XDG_RUNTIME_DIR="${3:-/run/user/1000}"
export RUST_LOG="${RUST_LOG:-error,continuum_studio_iced=info}"

cd /home/e421/continuum-studio/ui-iced
exec ./target/release/continuum-studio-iced
