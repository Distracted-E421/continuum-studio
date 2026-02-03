#!/usr/bin/env bash
# Wrapper to run cargo-built binary with NixOS library paths

# Copy LD_LIBRARY_PATH setup from nix wrapper
export LD_LIBRARY_PATH="/nix/store/lhzl3zl3mfl46grkmxda6j5921bki81j-vulkan-headers-1.4.335.0/lib"
export LD_LIBRARY_PATH="/nix/store/p571ddsdkd75dilqibr5ly79yb6v88n3-vulkan-loader-1.4.335.0/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/mjj7524kagv013g0kh0wbw0da62yfr1i-freetype-2.13.3/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/cmpddlfv1l4g1y77z7g8pxmdhsqjz0w1-fontconfig-2.17.1-lib/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/bafh2k71bs9ls9vjzkkvpzfzilqqis8c-libglvnd-1.7.0/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/2jpi8ynpyjya1agxgc0d4bj1cy1q6gv5-libxi-1.8.2/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/64c50fvn61nfnz21gjh81qq216nacbgr-libxrandr-1.5.4/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/7li7v2a2ny1b84avw8wzcsbcgmabfnqr-libxcursor-1.2.3/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/0iwk3dvbzgdhd05zxys60n0ba413kcxn-libx11-1.8.12/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/ni6pwnn5cg4mwm2fkmqrm2bzjvj16b64-libxkbcommon-1.11.0/lib:$LD_LIBRARY_PATH"
export LD_LIBRARY_PATH="/nix/store/v252clrvl9pxhq7f7iway7m9r3i1z0vq-wayland-1.24.0/lib:$LD_LIBRARY_PATH"

# Run the cargo-built binary
exec /home/e421/continuum-studio/ui-iced/target/release/continuum-studio-iced "$@"
