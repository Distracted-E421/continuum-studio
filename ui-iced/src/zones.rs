//! XX-Zones Window Positioning Client
//!
//! Provides deterministic window positioning using the xx-zones protocol concept.
//! Currently implements positioning via Iced's native window APIs (Phase 1).
//! Designed for seamless upgrade to the xx-zones Wayland protocol (Phase 2).
//!
//! ## Architecture
//!
//! A "zone" is a logical region of screen space where windows are organized.
//! Each zone has a coordinate system relative to its output (monitor).
//!
//! The `ZoneManager` tracks all windows and their positions, and can:
//! - Place windows at specific positions within zones
//! - Calculate layouts (side-by-side, stacked, etc.)
//! - Report positions for external tools (Phosphor capture)
//! - Persist layout preferences
//!
//! ## Usage from Phosphor
//!
//! Phosphor can query window positions via:
//! - KWin D-Bus (getWindowInfo) for actual compositor positions
//! - HTTP API at `/api/zones` for zone-aware layout info
//!
//! ## Future: xx-zones Protocol
//!
//! When compositor support (KWin or Parallax) is available, this module will
//! use the xx_zone_manager_v1 Wayland protocol for native positioning.

use iced::window;
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

/// Zone layout presets
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub enum ZoneLayout {
    /// Main window fills most of the screen, side panels on the right
    #[default]
    MainWithSidePanel,
    /// Two equal columns
    SplitHorizontal,
    /// Two equal rows
    SplitVertical,
    /// Three columns (sidebar, main, panel)
    ThreeColumn,
    /// Free-form positioning (manual coordinates)
    FreeForm,
    /// Dashboard: main centered, tools arrayed around
    Dashboard,
}

/// A zone definition (logical screen region)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Zone {
    /// Unique zone handle (matches xx-zones protocol handle concept)
    pub handle: String,
    /// Zone origin relative to output
    pub x: i32,
    pub y: i32,
    /// Zone dimensions
    pub width: i32,
    pub height: i32,
    /// Which output this zone is on (monitor name)
    pub output: Option<String>,
}

/// A window placement within a zone
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WindowPlacement {
    /// Iced window ID (not serializable, tracked at runtime)
    #[serde(skip)]
    pub window_id: Option<window::Id>,
    /// Window type identifier
    pub window_type: String,
    /// Target position within zone coordinates
    pub x: i32,
    pub y: i32,
    /// Target size
    pub width: i32,
    pub height: i32,
    /// Zone this window belongs to
    pub zone_handle: String,
}

/// The zone manager tracks all windows and their desired positions
#[derive(Debug, Clone)]
pub struct ZoneManager {
    /// Available zones
    zones: BTreeMap<String, Zone>,
    /// Window placements
    placements: Vec<WindowPlacement>,
    /// Current layout
    layout: ZoneLayout,
    /// Screen dimensions (for layout calculations)
    screen_width: i32,
    screen_height: i32,
}

impl ZoneManager {
    /// Create a new zone manager with screen dimensions
    pub fn new(screen_width: i32, screen_height: i32) -> Self {
        let mut manager = Self {
            zones: BTreeMap::new(),
            placements: Vec::new(),
            layout: ZoneLayout::default(),
            screen_width,
            screen_height,
        };

        // Create default zone covering the whole screen
        manager.zones.insert(
            "primary".to_string(),
            Zone {
                handle: "primary".to_string(),
                x: 0,
                y: 0,
                width: screen_width,
                height: screen_height,
                output: None,
            },
        );

        manager
    }

    /// Set the current layout and recalculate placements
    pub fn set_layout(&mut self, layout: ZoneLayout) {
        self.layout = layout;
    }

    /// Get the current layout
    pub fn layout(&self) -> ZoneLayout {
        self.layout
    }

    /// Calculate window positions for "MainWithSidePanel" layout
    ///
    /// Returns (main_window_settings, side_panel_settings)
    pub fn layout_main_with_side_panel(&self) -> (WindowPlacementConfig, WindowPlacementConfig) {
        let panel_width = 500.min(self.screen_width / 3);
        let main_width = self.screen_width - panel_width - 20; // 20px gap

        let main = WindowPlacementConfig {
            x: 10,
            y: 50, // Leave room for panel/taskbar
            width: main_width,
            height: self.screen_height - 100,
        };

        let panel = WindowPlacementConfig {
            x: main_width + 20,
            y: 50,
            width: panel_width,
            height: self.screen_height - 100,
        };

        (main, panel)
    }

    /// Calculate window positions for "Dashboard" layout
    ///
    /// Returns (main centered, task_queue right side)
    pub fn layout_dashboard(&self) -> (WindowPlacementConfig, WindowPlacementConfig) {
        let main_width = (self.screen_width as f32 * 0.65) as i32;
        let main_height = (self.screen_height as f32 * 0.85) as i32;
        let main_x = (self.screen_width - main_width) / 2;
        let main_y = (self.screen_height - main_height) / 2;

        let panel_width = 500.min((self.screen_width - main_width) - 30);
        let panel_x = main_x + main_width + 10;
        let panel_height = main_height;

        let main = WindowPlacementConfig {
            x: main_x,
            y: main_y,
            width: main_width,
            height: main_height,
        };

        let panel = WindowPlacementConfig {
            x: panel_x,
            y: main_y,
            width: panel_width,
            height: panel_height,
        };

        (main, panel)
    }

    /// Get layout positions for applying to windows
    ///
    /// Returns a `LayoutApplication` with the calculated positions.
    /// The caller should use `window::move_to` and `window::resize` with these values.
    pub fn calculate_layout(&self) -> LayoutApplication {
        let (main_config, panel_config) = match self.layout {
            ZoneLayout::MainWithSidePanel => self.layout_main_with_side_panel(),
            ZoneLayout::Dashboard => self.layout_dashboard(),
            _ => self.layout_main_with_side_panel(), // Fallback
        };

        LayoutApplication {
            main: main_config,
            side_panel: panel_config,
        }
    }

    /// Get a JSON-serializable snapshot of all window positions
    /// (for Phosphor and other external tools)
    pub fn snapshot(&self) -> ZoneSnapshot {
        // Calculate current window positions based on layout
        let (main_config, panel_config) = match self.layout {
            ZoneLayout::MainWithSidePanel => self.layout_main_with_side_panel(),
            ZoneLayout::Dashboard => self.layout_dashboard(),
            _ => self.layout_main_with_side_panel(),
        };

        let active_windows = vec![
            ActiveWindow {
                window_type: "Main".to_string(),
                x: main_config.x,
                y: main_config.y,
                width: main_config.width,
                height: main_config.height,
            },
            ActiveWindow {
                window_type: "TaskQueue".to_string(),
                x: panel_config.x,
                y: panel_config.y,
                width: panel_config.width,
                height: panel_config.height,
            },
        ];

        ZoneSnapshot {
            layout: self.layout,
            screen_width: self.screen_width,
            screen_height: self.screen_height,
            zones: self.zones.values().cloned().collect(),
            placements: self.placements.clone(),
            active_windows,
        }
    }

    /// Write current zone snapshot to file for external tools (Phosphor)
    pub fn export_snapshot(&self) {
        let snapshot = self.snapshot();
        if let Err(e) = snapshot.write_to_file() {
            eprintln!("Warning: Failed to write zone snapshot: {}", e);
        }
    }
}

/// Window placement configuration (calculated)
#[derive(Debug, Clone)]
pub struct WindowPlacementConfig {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

/// Result of layout calculation (main + side panel positions)
#[derive(Debug, Clone)]
pub struct LayoutApplication {
    pub main: WindowPlacementConfig,
    pub side_panel: WindowPlacementConfig,
}

/// Well-known path for zone snapshot file (Phosphor reads this)
pub const ZONE_SNAPSHOT_PATH: &str = "/tmp/continuum-studio-zones.json";

/// Serializable snapshot of zone state
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ZoneSnapshot {
    pub layout: ZoneLayout,
    pub screen_width: i32,
    pub screen_height: i32,
    pub zones: Vec<Zone>,
    pub placements: Vec<WindowPlacement>,
    /// Active window positions (updated on layout apply)
    #[serde(default)]
    pub active_windows: Vec<ActiveWindow>,
}

/// Tracks an active window's current position (for Phosphor capture)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ActiveWindow {
    pub window_type: String,
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

impl ZoneSnapshot {
    /// Write snapshot to the well-known file path
    pub fn write_to_file(&self) -> Result<(), std::io::Error> {
        let json = serde_json::to_string_pretty(self).map_err(std::io::Error::other)?;
        std::fs::write(ZONE_SNAPSHOT_PATH, json)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_zone_manager_new() {
        let manager = ZoneManager::new(1920, 1080);
        assert_eq!(manager.screen_width, 1920);
        assert_eq!(manager.screen_height, 1080);
        assert_eq!(manager.layout(), ZoneLayout::MainWithSidePanel);
        assert!(manager.zones.contains_key("primary"));
    }

    #[test]
    fn test_zone_manager_set_layout() {
        let mut manager = ZoneManager::new(1920, 1080);
        assert_eq!(manager.layout(), ZoneLayout::MainWithSidePanel);

        manager.set_layout(ZoneLayout::Dashboard);
        assert_eq!(manager.layout(), ZoneLayout::Dashboard);

        manager.set_layout(ZoneLayout::SplitHorizontal);
        assert_eq!(manager.layout(), ZoneLayout::SplitHorizontal);
    }

    #[test]
    fn test_layout_main_with_side_panel() {
        let manager = ZoneManager::new(1920, 1080);
        let (main, panel) = manager.layout_main_with_side_panel();

        // Main should be on the left
        assert!(main.x < panel.x);
        // Panel width should be capped at screen_width / 3
        assert!(panel.width <= 1920 / 3);
        // Main + gap + panel should roughly equal screen width
        assert!(main.width + panel.width < 1920);
        // Heights should be equal
        assert_eq!(main.height, panel.height);
    }

    #[test]
    fn test_layout_dashboard() {
        let manager = ZoneManager::new(1920, 1080);
        let (main, panel) = manager.layout_dashboard();

        // Main should be roughly centered (65% of screen)
        let expected_main_width = (1920.0 * 0.65) as i32;
        assert_eq!(main.width, expected_main_width);

        // Panel should be to the right of main
        assert!(panel.x > main.x);

        // Both should be vertically centered (same y)
        assert_eq!(main.y, panel.y);
    }

    #[test]
    fn test_calculate_layout_returns_correct_type() {
        let manager = ZoneManager::new(1920, 1080);
        let layout = manager.calculate_layout();

        // Should return valid positions
        assert!(layout.main.width > 0);
        assert!(layout.main.height > 0);
        assert!(layout.side_panel.width > 0);
        assert!(layout.side_panel.height > 0);
    }

    #[test]
    fn test_snapshot_serialization() {
        let manager = ZoneManager::new(1920, 1080);
        let snapshot = manager.snapshot();

        // Should serialize to JSON
        let json = serde_json::to_string(&snapshot);
        assert!(json.is_ok());

        // Should deserialize back
        let json_str = json.unwrap();
        let deserialized: Result<ZoneSnapshot, _> = serde_json::from_str(&json_str);
        assert!(deserialized.is_ok());

        let restored = deserialized.unwrap();
        assert_eq!(restored.screen_width, 1920);
        assert_eq!(restored.screen_height, 1080);
        assert_eq!(restored.layout, ZoneLayout::MainWithSidePanel);
    }

    #[test]
    fn test_zone_layout_default() {
        let layout = ZoneLayout::default();
        assert_eq!(layout, ZoneLayout::MainWithSidePanel);
    }

    #[test]
    fn test_small_screen_constraints() {
        let manager = ZoneManager::new(800, 600);
        let (main, panel) = manager.layout_main_with_side_panel();

        // Panel should be capped at screen_width / 3
        assert!(panel.width <= 800 / 3);
        // Main should still have reasonable width
        assert!(main.width > 0);
        // Combined shouldn't exceed screen
        assert!(main.x + main.width <= 800);
    }
}
