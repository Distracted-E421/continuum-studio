# Continuum Studio - AI Development Companion & Cursor Version Manager
# Using flake-parts for modular, maintainable configuration
#
# Structure:
#   parts/                    - flake-parts modules
#     packages.nix            - Package definitions (perSystem)
#     modules.nix             - NixOS and Home-Manager modules
#   ui-iced/                  - Desktop GUI (Rust + iced)
#   core/                     - Elixir OTP services
#   android/                  - Mobile app (Kotlin + Compose)
{
  description = "Continuum Studio - AI Development Companion & Cursor Version Manager";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    inputs@{ self, flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      # Supported systems
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      # Import modular parts
      imports = [
        ./parts/packages.nix
        ./parts/modules.nix
      ];
    };
}
