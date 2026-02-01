//! Widget components for Continuum Studio iced UI
//!
//! This module contains reusable widget components following iced's
//! Elm-inspired architecture.

// Widget modules will be added here as we port them from egui:
// pub mod tab_bar;
// pub mod diagram;
// pub mod terminal;
// pub mod code_view;

// Shared widget utilities
pub mod helpers;

// COSMIC-inspired styling
pub mod cosmic_style;

// Re-export commonly used items
pub use cosmic_style::{CosmicButton, CosmicContainer, ContainerVariant};
