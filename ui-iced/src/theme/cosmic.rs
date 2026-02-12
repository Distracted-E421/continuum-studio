//! COSMIC Desktop-inspired theme for Continuum Studio
//!
//! This module re-exports and extends the shared `synapsix-theme` crate,
//! providing COSMIC-inspired theming compatible with vanilla iced.
//!
//! The theme system is now unified across Synapsix and Continuum Studio,
//! ensuring visual consistency.

use iced::{Color, Theme};

// Re-export from shared theme crate
pub use synapsix_theme::{
    CosmicPalette as SharedPalette,
    CosmicPreset,
    DesignTokens,
    Radii,
    Spacing,
    Theme as SynapsixTheme,
    Typography,
};

/// COSMIC-inspired color palette
/// 
/// This wraps the shared `synapsix_theme::CosmicPalette` and provides
/// iced-specific convenience methods.
#[derive(Debug, Clone, Copy)]
pub struct CosmicPalette {
    inner: SharedPalette,
}

impl CosmicPalette {
    /// Create from a shared palette
    pub fn from_shared(palette: SharedPalette) -> Self {
        Self { inner: palette }
    }

    /// COSMIC Dark theme colors
    pub fn dark() -> Self {
        Self::from_shared(SharedPalette::dark())
    }

    /// COSMIC Light theme colors
    pub fn light() -> Self {
        Self::from_shared(SharedPalette::light())
    }

    /// Pop!_OS Orange accent
    pub fn pop_orange() -> Self {
        Self::from_shared(SharedPalette::pop_orange())
    }

    /// Cool Blue accent
    pub fn cool_blue() -> Self {
        Self::from_shared(SharedPalette::cool_blue())
    }

    /// Mint accent
    pub fn mint() -> Self {
        Self::from_shared(SharedPalette::mint())
    }

    /// Warm Amber accent
    pub fn warm_amber() -> Self {
        Self::from_shared(SharedPalette::warm_amber())
    }

    /// Get the underlying shared palette
    pub fn shared(&self) -> &SharedPalette {
        &self.inner
    }

    // =========================================================================
    // Compatibility API - mirrors the old CosmicPalette interface
    // =========================================================================

    /// Background base color
    pub fn bg_color(&self) -> Color {
        self.inner.bg_base_iced()
    }

    /// Primary container background
    pub fn primary_container_bg(&self) -> Color {
        self.inner.bg_component_iced()
    }

    /// Secondary container background
    pub fn secondary_container_bg(&self) -> Color {
        self.inner.bg_elevated_iced()
    }

    /// Text on background
    pub fn on_bg_color(&self) -> Color {
        self.inner.fg_primary_iced()
    }

    /// Accent/brand color
    pub fn accent(&self) -> Color {
        self.inner.accent_iced()
    }

    /// Accent hover
    pub fn accent_hover(&self) -> Color {
        self.inner.accent_hover_iced()
    }

    /// Text on accent
    pub fn on_accent(&self) -> Color {
        self.inner.on_accent_iced()
    }

    /// Success color
    pub fn success(&self) -> Color {
        self.inner.success_iced()
    }

    /// Warning color
    pub fn warning(&self) -> Color {
        self.inner.warning_iced()
    }

    /// Destructive/error color
    pub fn destructive(&self) -> Color {
        self.inner.danger_iced()
    }

    /// Divider/border color
    pub fn divider(&self) -> Color {
        self.inner.border_iced()
    }

    /// Button background
    pub fn button_bg(&self) -> Color {
        self.inner.button_bg_iced()
    }

    /// Secondary text color
    pub fn secondary_text(&self) -> Color {
        self.inner.fg_secondary_iced()
    }

    /// Muted text color
    pub fn muted_text(&self) -> Color {
        self.inner.fg_muted_iced()
    }

    /// Convert to iced Theme
    pub fn to_iced_theme(&self, name: &str) -> Theme {
        self.inner.to_iced_theme(name)
    }

    /// Get whether this is a light theme
    pub fn is_light(&self) -> bool {
        self.inner.is_light()
    }
}

/// COSMIC theme presets
/// 
/// This wraps `synapsix_theme::CosmicPreset` for compatibility.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CosmicThemePreset {
    /// COSMIC Dark (default)
    #[default]
    Dark,
    /// COSMIC Light
    Light,
    /// Pop Orange accent (dark base)
    PopOrange,
    /// Warm Amber accent
    WarmAmber,
    /// Cool Blue accent
    CoolBlue,
    /// Mint accent
    Mint,
}

impl CosmicThemePreset {
    /// Get the theme name
    pub fn name(&self) -> &'static str {
        self.to_shared().name()
    }

    /// Convert to shared preset
    pub fn to_shared(&self) -> CosmicPreset {
        match self {
            Self::Dark => CosmicPreset::Dark,
            Self::Light => CosmicPreset::Light,
            Self::PopOrange => CosmicPreset::PopOrange,
            Self::WarmAmber => CosmicPreset::WarmAmber,
            Self::CoolBlue => CosmicPreset::CoolBlue,
            Self::Mint => CosmicPreset::Mint,
        }
    }

    /// Get the palette for this preset
    pub fn palette(&self) -> CosmicPalette {
        CosmicPalette::from_shared(self.to_shared().palette())
    }

    /// Get the iced Theme for this preset
    pub fn to_iced_theme(&self) -> Theme {
        self.to_shared().to_iced_theme()
    }

    /// All available presets
    pub fn all() -> &'static [CosmicThemePreset] {
        &[
            Self::Dark,
            Self::Light,
            Self::PopOrange,
            Self::WarmAmber,
            Self::CoolBlue,
            Self::Mint,
        ]
    }
}

/// Try to detect system COSMIC theme (stub for future implementation)
/// 
/// In the future, this could read from:
/// - `~/.config/cosmic/com.system76.CosmicTheme.Dark/v1/`
/// - `~/.config/cosmic/com.system76.CosmicTheme.Light/v1/`
pub fn detect_system_cosmic_theme() -> Option<CosmicPalette> {
    // Check if we're running on COSMIC
    if std::env::var("XDG_CURRENT_DESKTOP").ok()?.contains("COSMIC") {
        // Future: Actually read COSMIC theme files
        // For now, return dark as default COSMIC theme
        Some(CosmicPalette::dark())
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dark_palette() {
        let p = CosmicPalette::dark();
        assert!(!p.is_light());
    }

    #[test]
    fn test_light_palette() {
        let p = CosmicPalette::light();
        assert!(p.is_light());
    }

    #[test]
    fn test_presets() {
        for preset in CosmicThemePreset::all() {
            let theme = preset.to_iced_theme();
            assert!(!preset.name().is_empty());
            let _ = theme; // Just ensure it doesn't panic
        }
    }

    #[test]
    fn test_shared_palette_access() {
        let preset = CosmicThemePreset::Dark;
        let palette = preset.palette();
        let shared = palette.shared();
        
        // Verify we can access raw RGB values
        let bg = shared.bg_base;
        assert!(bg[0] < 50); // Dark theme should have dark background
    }
}
