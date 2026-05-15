# Continuum Studio NixOS and Home-Manager Modules
# Part of the Dendrite flake-parts architecture
#
# Exports:
#   - nixosModules.continuum-studio - System-level Continuum Studio
#   - homeManagerModules.continuum-studio - User-level Continuum Studio
{ inputs, self, ... }:
{
  flake = {
    # ═══════════════════════════════════════════════════════════════
    # NIXOS MODULES
    # ═══════════════════════════════════════════════════════════════

    nixosModules = {
      continuum-studio =
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
              defaultText = lib.literalExpression "continuum-studio.packages.\${system}.default";
              description = "The Continuum Studio package to use.";
            };

            autoStart = lib.mkOption {
              type = lib.types.bool;
              default = false;
              description = "Whether to start the core service automatically.";
            };

            updateChannel = lib.mkOption {
              type = lib.types.enum [
                "stable"
                "beta"
                "nightly"
              ];
              default = "stable";
              description = "Update channel for automatic updates.";
            };
          };

          config = lib.mkIf cfg.enable {
            environment.systemPackages = [ cfg.package ];
            xdg.mime.enable = true;
          };
        };

      default = self.nixosModules.continuum-studio;
    };

    # ═══════════════════════════════════════════════════════════════
    # HOME-MANAGER MODULES
    # ═══════════════════════════════════════════════════════════════

    homeManagerModules = {
      continuum-studio =
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
              defaultText = lib.literalExpression "continuum-studio.packages.\${system}.default";
              description = "The Continuum Studio package to use.";
            };

            updateChannel = lib.mkOption {
              type = lib.types.enum [
                "stable"
                "beta"
                "nightly"
              ];
              default = "stable";
              description = "Update channel for automatic updates.";
            };

            autoCheckUpdates = lib.mkOption {
              type = lib.types.bool;
              default = true;
              description = "Automatically check for updates on startup.";
            };

            service = {
              enable = lib.mkOption {
                type = lib.types.bool;
                default = false;
                description = "Enable Continuum Studio as a user service.";
              };
            };
          };

          config = lib.mkIf cfg.enable {
            home.packages = [ cfg.package ];

            xdg.configFile."continuum-studio/update-config.json".text = builtins.toJSON {
              channel = cfg.updateChannel;
              auto_check = cfg.autoCheckUpdates;
            };

            systemd.user.services.continuum-studio = lib.mkIf cfg.service.enable {
              Unit = {
                Description = "Continuum Studio";
                After = [ "graphical-session.target" ];
                PartOf = [ "graphical-session.target" ];
              };

              Service = {
                Type = "simple";
                ExecStart = "${cfg.package}/bin/continuum-studio";
                Restart = "on-failure";
                RestartSec = 5;
              };

              Install = {
                WantedBy = [ "graphical-session.target" ];
              };
            };
          };
        };

      default = self.homeManagerModules.continuum-studio;
    };
  };
}
