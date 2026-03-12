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
    inputs@{
      self,
      nixpkgs,
      flake-parts,
      rust-overlay,
    }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      perSystem =
        {
          config,
          self',
          inputs',
          pkgs,
          system,
          lib,
          ...
        }:
        let
          # Apply rust overlay
          pkgsWithOverlay = import nixpkgs {
            inherit system;
            overlays = [ (import rust-overlay) ];
          };

          # Rust toolchain
          rustToolchain = pkgsWithOverlay.rust-bin.stable.latest.default.override {
            extensions = [
              "rust-src"
              "rust-analyzer"
            ];
          };

          # Common build inputs for iced GUI
          guiBuildInputs = with pkgsWithOverlay; [
            # Wayland
            wayland
            libxkbcommon

            # X11 (fallback)
            xorg.libX11
            xorg.libXcursor
            xorg.libXrandr
            xorg.libXi

            # OpenGL
            libGL

            # Font rendering
            fontconfig
            freetype

            # Vulkan (for wgpu)
            vulkan-loader
            vulkan-headers
          ];

          # Source includes ui-iced, local crates, and scripts
          srcRoot = pkgsWithOverlay.runCommand "continuum-studio-src" { } ''
            mkdir -p $out
            cp -r ${./ui-iced} $out/ui-iced
            cp -r ${./crates} $out/crates
            cp -r ${./scripts} $out/scripts
          '';

          # The Rust GUI application
          continuum-studio-gui = pkgsWithOverlay.rustPlatform.buildRustPackage {
            pname = "continuum-studio";
            version = "0.1.0";

            src = srcRoot;
            sourceRoot = "continuum-studio-src/ui-iced";

            cargoLock = {
              lockFile = ./ui-iced/Cargo.lock;
            };

            nativeBuildInputs = with pkgsWithOverlay; [
              pkg-config
              makeWrapper
            ];

            buildInputs = guiBuildInputs;

            # Set runtime library paths (Wayland envs are inherited from user session)
            postFixup = ''
              wrapProgram $out/bin/continuum-studio-iced \
                --prefix LD_LIBRARY_PATH : "${lib.makeLibraryPath guiBuildInputs}"
            '';

            meta = with lib; {
              description = "Continuum Studio GUI - AI Development Companion";
              license = licenses.agpl3Only;
              platforms = platforms.linux;
              mainProgram = "continuum-studio-iced";
            };
          };

          # Elixir core service (built as release)
          elixirCore = pkgsWithOverlay.beamPackages.mixRelease {
            pname = "studio_core";
            version = "0.1.0";

            src = ./core/studio_core;

            mixFodDeps = pkgsWithOverlay.beamPackages.fetchMixDeps {
              pname = "studio_core-deps";
              version = "0.1.0";
              src = ./core/studio_core;
              hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; # Will need to update
            };

            # Copy version registry data
            postInstall = ''
              mkdir -p $out/share/continuum-studio
              cp ${./core/studio_core/priv/cursor-versions.json} $out/share/continuum-studio/
            '';
          };

          # Combined package with desktop integration
          continuum-studio = pkgsWithOverlay.symlinkJoin {
            name = "continuum-studio";
            paths = [ continuum-studio-gui ];

            nativeBuildInputs = [ pkgsWithOverlay.makeWrapper ];

            postBuild = ''
              # Create bin directory if needed
              mkdir -p $out/bin

              # Rename binary for cleaner command
              if [ -f $out/bin/continuum-studio-iced ]; then
                mv $out/bin/continuum-studio-iced $out/bin/continuum-studio || true
              fi

              # Install desktop file
              mkdir -p $out/share/applications
              cp ${./continuum-studio.desktop} $out/share/applications/continuum-studio.desktop

              # Patch desktop file with correct path
              substituteInPlace $out/share/applications/continuum-studio.desktop \
                --replace "Exec=continuum-studio" "Exec=$out/bin/continuum-studio"

              # Install icon (multiple sizes would be better, but we use the main one)
              mkdir -p $out/share/icons/hicolor/256x256/apps
              cp ${./continuum-studio.png} $out/share/icons/hicolor/256x256/apps/continuum-studio.png

              # Also install a scalable reference
              mkdir -p $out/share/pixmaps
              cp ${./continuum-studio.png} $out/share/pixmaps/continuum-studio.png
            '';

            meta = with lib; {
              description = "Continuum Studio - AI Development Companion & Cursor Version Manager";
              license = licenses.agpl3Only;
              platforms = platforms.linux;
              mainProgram = "continuum-studio";
            };
          };

          # cursor-versions CLI tool (standalone)
          cursor-versions-cli = pkgsWithOverlay.writeShellScriptBin "cursor-versions" ''
            #!/usr/bin/env bash
            # cursor-versions CLI - wrapper for Elixir escript

            STUDIO_CORE_PATH="${./core/studio_core}"

            # Check if we have the escript built
            if [ -f "$STUDIO_CORE_PATH/bin/cursor-versions" ]; then
              exec "$STUDIO_CORE_PATH/bin/cursor-versions" "$@"
            else
              echo "Error: cursor-versions escript not built"
              echo "Run: cd $STUDIO_CORE_PATH && mix escript.build"
              exit 1
            fi
          '';
        in
        {
          # Packages
          packages = {
            default = continuum-studio;
            gui = continuum-studio-gui;
            # cli = cursor-versions-cli;  # Uncomment when Elixir deps are fixed
          };

          # Development shell
          devShells.default = pkgsWithOverlay.mkShell {
            nativeBuildInputs = with pkgsWithOverlay; [
              # Rust
              rustToolchain
              pkg-config

              # Elixir
              elixir_1_18
              erlang_27

              # GUI dependencies
              wayland
              libxkbcommon
              xorg.libX11
              xorg.libXcursor
              xorg.libXrandr
              xorg.libXi
              libGL
              fontconfig
              freetype
              vulkan-loader

              # Dev tools
              cargo-watch
              rust-analyzer
            ];

            buildInputs = guiBuildInputs;

            shellHook = ''
              export LD_LIBRARY_PATH="${lib.makeLibraryPath guiBuildInputs}:$LD_LIBRARY_PATH"

              echo "Continuum Studio Development Environment"
              echo "========================================="
              echo "Rust: $(rustc --version)"
              echo "Elixir: $(elixir --version | head -1)"
              echo ""
              echo "Commands:"
              echo "  GUI:  cd ui-iced && cargo run --release"
              echo "  Core: cd core/studio_core && iex -S mix"
              echo "  CLI:  cd core/studio_core && mix escript.build"
            '';
          };
        };

      # Flake-wide configuration
      flake = {
        # NixOS module for system integration
        nixosModules.default =
          {
            config,
            lib,
            pkgs,
            ...
          }:
          let
            cfg = config.programs.continuum-studio;
          in
          {
            options.programs.continuum-studio = {
              enable = lib.mkEnableOption "Continuum Studio";

              package = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${pkgs.system}.default;
                description = "The Continuum Studio package to use";
              };

              autoStart = lib.mkOption {
                type = lib.types.bool;
                default = false;
                description = "Whether to start the core service automatically";
              };

              updateChannel = lib.mkOption {
                type = lib.types.enum [
                  "stable"
                  "beta"
                  "nightly"
                ];
                default = "stable";
                description = "Update channel for automatic updates";
              };
            };

            config = lib.mkIf cfg.enable {
              environment.systemPackages = [ cfg.package ];

              # XDG desktop integration
              xdg.mime.enable = true;
            };
          };

        # Home Manager module
        homeManagerModules.default =
          {
            config,
            lib,
            pkgs,
            ...
          }:
          let
            cfg = config.programs.continuum-studio;
          in
          {
            options.programs.continuum-studio = {
              enable = lib.mkEnableOption "Continuum Studio";

              package = lib.mkOption {
                type = lib.types.package;
                default = self.packages.${pkgs.system}.default;
                description = "The Continuum Studio package to use";
              };

              updateChannel = lib.mkOption {
                type = lib.types.enum [
                  "stable"
                  "beta"
                  "nightly"
                ];
                default = "stable";
                description = "Update channel for automatic updates";
              };

              autoCheckUpdates = lib.mkOption {
                type = lib.types.bool;
                default = true;
                description = "Automatically check for updates on startup";
              };
            };

            config = lib.mkIf cfg.enable {
              home.packages = [ cfg.package ];

              # Create config file with update settings
              xdg.configFile."continuum-studio/update-config.json".text = builtins.toJSON {
                channel = cfg.updateChannel;
                auto_check = cfg.autoCheckUpdates;
              };
            };
          };
      };
    };
}
