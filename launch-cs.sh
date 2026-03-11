#!/usr/bin/env bash
# Launch Continuum Studio reliably on COSMIC/Wayland
#
# Usage: ./launch-cs.sh [--rebuild]
#   --rebuild: Rebuild before launching

set -euo pipefail

CS_DIR="/home/e421/continuum-studio/ui-iced"
CS_BIN="${CS_DIR}/target/release/continuum-studio-iced"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m' # No Color

# Check if already running
if pgrep -f "continuum-studio-iced" > /dev/null 2>&1; then
    echo -e "${GREEN}Continuum Studio is already running${NC}"
    echo "PID: $(pgrep -f continuum-studio-iced)"
    exit 0
fi

# Rebuild if requested
if [[ "${1:-}" == "--rebuild" ]]; then
    echo "Building Continuum Studio..."
    cd "$CS_DIR"
    cargo build --release
fi

# Check if binary exists
if [[ ! -x "$CS_BIN" ]]; then
    echo -e "${RED}Binary not found: $CS_BIN${NC}"
    echo "Run: cd $CS_DIR && cargo build --release"
    exit 1
fi

# Launch via systemd-run (works on COSMIC/Wayland)
echo "Launching Continuum Studio..."
systemd-run --user --scope "$CS_BIN" &

# Wait and verify
sleep 2
if pgrep -f "continuum-studio-iced" > /dev/null 2>&1; then
    echo -e "${GREEN}Continuum Studio started successfully${NC}"
    echo "PID: $(pgrep -f continuum-studio-iced)"
else
    echo -e "${RED}Failed to start Continuum Studio${NC}"
    exit 1
fi
