//! Continuum Studio UI Library - iced/COSMIC Edition
//!
//! This library provides the UI components for Continuum Studio,
//! built with iced and designed for COSMIC desktop integration.

pub mod core;
pub mod settings;
pub mod theme;
pub mod updater;
pub mod widgets;

// Re-export common types
pub use core::{CoreRequest, CoreResponse, CursorVersion, VersionStatus, Session, InstalledVersion, VersionStats};
pub use settings::{Settings, ThemePreference, CosmicPreset};
pub use theme::{SemanticColors, CosmicPalette, CosmicThemePreset};
pub use updater::{UpdateChannel, UpdateSettings, UpdateInfo, UpdateChecker};
