#!/usr/bin/env bash
# Run the Android Auto Desktop Head Unit on NixOS.
#
# The prebuilt binary links libc++/libc++abi, which are not in the default
# library path here, so it fails with "libc++.so.1: cannot open shared object
# file". Weaker than a full FHS environment and enough in practice: point
# LD_LIBRARY_PATH at libcxx from the store plus the SDK's bundled libusb.
#
# Usage:
#   ./dhu.sh                 # USB transport, first attached device
#   ./dhu.sh --headless      # no window
#   ./dhu.sh --adb           # use adb over tcp:5277
#
# Before running:
#   1. Phone: Android Auto -> overflow menu -> "Start head unit server"
#   2. adb forward tcp:5277 tcp:5277
set -euo pipefail

SDK_AUTO="${ANDROID_HOME:-$HOME/Android/Sdk}/extras/google/auto"

if [[ ! -x "$SDK_AUTO/desktop-head-unit" ]]; then
  echo "desktop-head-unit not found at $SDK_AUTO" >&2
  echo "Install via: sdkmanager 'extras;google;auto'" >&2
  exit 1
fi

LIBCXX_DIR="$(dirname "$(ls /nix/store/*libcxx*/lib/libc++.so.1 2>/dev/null | head -1)")"
if [[ -z "$LIBCXX_DIR" || ! -d "$LIBCXX_DIR" ]]; then
  echo "libc++.so.1 not found in /nix/store; is libcxx installed?" >&2
  exit 1
fi

export LD_LIBRARY_PATH="$LIBCXX_DIR:$SDK_AUTO${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# Default to USB transport unless the caller passed an explicit mode.
if [[ $# -eq 0 ]]; then
  exec "$SDK_AUTO/desktop-head-unit" --usb
else
  exec "$SDK_AUTO/desktop-head-unit" "$@"
fi
