# Continuum Studio Packages and Development Shell
# Part of the Dendrite flake-parts architecture
#
# Exports:
#   - packages.default - Combined package with desktop integration
#   - packages.gui - Rust GUI (iced)
#   - packages.elixir-core - Elixir OTP service
#   - devShells.default - Full development environment
{ inputs, self, ... }:
{
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
      pkgsWithOverlay = import inputs.nixpkgs {
        inherit system;
        overlays = [ (import inputs.rust-overlay) ];
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
        cp -r ${../ui-iced} $out/ui-iced
        cp -r ${../crates} $out/crates
        cp -r ${../scripts} $out/scripts
      '';
    in
    {
      # ═══════════════════════════════════════════════════════════════
      # PACKAGES
      # ═══════════════════════════════════════════════════════════════

      packages = {
        # The Rust GUI application
        gui = pkgsWithOverlay.rustPlatform.buildRustPackage {
          pname = "continuum-studio";
          version = "0.1.0";

          src = srcRoot;
          sourceRoot = "continuum-studio-src/ui-iced";

          cargoLock = {
            lockFile = ../ui-iced/Cargo.lock;
          };

          nativeBuildInputs = with pkgsWithOverlay; [
            pkg-config
            makeWrapper
          ];

          buildInputs = guiBuildInputs;

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
        # NOTE: mixFodDeps hash needs to be updated after deps change
        elixir-core = pkgsWithOverlay.beamPackages.mixRelease {
          pname = "studio_core";
          version = "0.1.0";

          src = ../core/studio_core;

          mixFodDeps = pkgsWithOverlay.beamPackages.fetchMixDeps {
            pname = "studio_core-deps";
            version = "0.1.0";
            src = ../core/studio_core;
            hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
          };

          postInstall = ''
            mkdir -p $out/share/continuum-studio
            cp ${../core/studio_core/priv/cursor-versions.json} $out/share/continuum-studio/
          '';
        };

        # Combined package with desktop integration
        default = pkgsWithOverlay.symlinkJoin {
          name = "continuum-studio";
          paths = [ self'.packages.gui ];

          nativeBuildInputs = [ pkgsWithOverlay.makeWrapper ];

          postBuild = ''
            mkdir -p $out/bin

            if [ -f $out/bin/continuum-studio-iced ]; then
              mv $out/bin/continuum-studio-iced $out/bin/continuum-studio || true
            fi

            mkdir -p $out/share/applications
            cp ${../continuum-studio.desktop} $out/share/applications/continuum-studio.desktop

            substituteInPlace $out/share/applications/continuum-studio.desktop \
              --replace "Exec=continuum-studio" "Exec=$out/bin/continuum-studio"

            mkdir -p $out/share/icons/hicolor/256x256/apps
            cp ${../continuum-studio.png} $out/share/icons/hicolor/256x256/apps/continuum-studio.png

            mkdir -p $out/share/pixmaps
            cp ${../continuum-studio.png} $out/share/pixmaps/continuum-studio.png
          '';

          meta = with lib; {
            description = "Continuum Studio - AI Development Companion & Cursor Version Manager";
            license = licenses.agpl3Only;
            platforms = platforms.linux;
            mainProgram = "continuum-studio";
          };
        };
      };

      # ═══════════════════════════════════════════════════════════════
      # DEVELOPMENT SHELL
      # ═══════════════════════════════════════════════════════════════

      devShells.default = pkgsWithOverlay.mkShell {
        nativeBuildInputs = with pkgsWithOverlay; [
          rustToolchain
          pkg-config
          elixir_1_18
          erlang_27
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
}
