//! COSMIC Desktop-inspired theme for Continuum Studio
//!
//! This module provides a COSMIC-inspired color palette that captures the look
//! and feel of System76's COSMIC desktop environment, while being compatible
//! with vanilla iced.
//!
//! Note: This is a standalone implementation inspired by COSMIC's design language,
//! not a direct integration with libcosmic (which uses a forked iced version).

use iced::{Color, Theme};

/// COSMIC-inspired color palette
#[derive(Debug, Clone, Copy)]
pub struct CosmicPalette {
    /// Background base color
    pub bg_color: Color,
    /// Primary container background
    pub primary_container_bg: Color,
    /// Secondary container background  
    pub secondary_container_bg: Color,
    /// Text on background
    pub on_bg_color: Color,
    /// Accent/brand color
    pub accent: Color,
    /// Accent on accent (text on accent)
    pub on_accent: Color,
    /// Success color
    pub success: Color,
    /// Warning color  
    pub warning: Color,
    /// Destructive/error color
    pub destructive: Color,
    /// Divider/border color
    pub divider: Color,
    /// Button background
    pub button_bg: Color,
    /// Secondary text color
    pub secondary_text: Color,
}

impl CosmicPalette {
    /// COSMIC Dark theme colors
    /// Based on Pop!_OS COSMIC Dark theme
    pub fn dark() -> Self {
        Self {
            bg_color: Color::from_rgb8(30, 30, 30),           // #1e1e1e
            primary_container_bg: Color::from_rgb8(43, 43, 43), // #2b2b2b
            secondary_container_bg: Color::from_rgb8(53, 53, 53), // #353535
            on_bg_color: Color::from_rgb8(250, 250, 250),     // #fafafa
            accent: Color::from_rgb8(255, 173, 0),            // #ffad00 (Pop orange)
            on_accent: Color::from_rgb8(0, 0, 0),             // #000000
            success: Color::from_rgb8(115, 191, 105),         // #73bf69
            warning: Color::from_rgb8(255, 191, 0),           // #ffbf00
            destructive: Color::from_rgb8(255, 89, 89),       // #ff5959
            divider: Color::from_rgb8(66, 66, 66),            // #424242
            button_bg: Color::from_rgb8(69, 69, 69),          // #454545
            secondary_text: Color::from_rgb8(180, 180, 180),  // #b4b4b4
        }
    }

    /// COSMIC Light theme colors
    /// Based on Pop!_OS COSMIC Light theme
    pub fn light() -> Self {
        Self {
            bg_color: Color::from_rgb8(250, 250, 250),        // #fafafa
            primary_container_bg: Color::from_rgb8(255, 255, 255), // #ffffff
            secondary_container_bg: Color::from_rgb8(240, 240, 240), // #f0f0f0
            on_bg_color: Color::from_rgb8(36, 36, 36),        // #242424
            accent: Color::from_rgb8(210, 120, 0),            // Darker orange for light theme
            on_accent: Color::from_rgb8(255, 255, 255),       // #ffffff
            success: Color::from_rgb8(46, 160, 67),           // #2ea043
            warning: Color::from_rgb8(191, 135, 0),           // #bf8700
            destructive: Color::from_rgb8(207, 34, 46),       // #cf222e
            divider: Color::from_rgb8(216, 216, 216),         // #d8d8d8
            button_bg: Color::from_rgb8(230, 230, 230),       // #e6e6e6
            secondary_text: Color::from_rgb8(100, 100, 100),  // #646464
        }
    }

    /// Convert to iced Theme
    pub fn to_iced_theme(&self, name: &str) -> Theme {
        let palette = iced::theme::Palette {
            background: self.bg_color,
            text: self.on_bg_color,
            primary: self.accent,
            success: self.success,
            warning: self.warning,
            danger: self.destructive,
        };
        Theme::custom(name.to_string(), palette)
    }

    /// Get whether this is a light theme
    pub fn is_light(&self) -> bool {
        // Simple heuristic: if bg is bright, it's light theme
        let luminance = 0.299 * self.bg_color.r + 0.587 * self.bg_color.g + 0.114 * self.bg_color.b;
        luminance > 0.5
    }
}

/// COSMIC theme presets
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CosmicThemePreset {
    /// COSMIC Dark (default)
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
        match self {
            Self::Dark => "COSMIC Dark",
            Self::Light => "COSMIC Light",
            Self::PopOrange => "Pop Orange",
            Self::WarmAmber => "Warm Amber",
            Self::CoolBlue => "Cool Blue",
            Self::Mint => "Mint",
        }
    }

    /// Get the palette for this preset
    pub fn palette(&self) -> CosmicPalette {
        match self {
            Self::Dark => CosmicPalette::dark(),
            Self::Light => CosmicPalette::light(),
            Self::PopOrange => {
                let mut p = CosmicPalette::dark();
                p.accent = Color::from_rgb8(255, 117, 56); // #ff7538
                p
            }
            Self::WarmAmber => {
                let mut p = CosmicPalette::dark();
                p.accent = Color::from_rgb8(255, 183, 77); // #ffb74d
                p
            }
            Self::CoolBlue => {
                let mut p = CosmicPalette::dark();
                p.accent = Color::from_rgb8(100, 181, 246); // #64b5f6
                p
            }
            Self::Mint => {
                let mut p = CosmicPalette::dark();
                p.accent = Color::from_rgb8(129, 199, 132); // #81c784
                p
            }
        }
    }

    /// Get the iced Theme for this preset
    pub fn to_iced_theme(&self) -> Theme {
        self.palette().to_iced_theme(self.name())
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

impl Default for CosmicThemePreset {
    fn default() -> Self {
        Self::Dark
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
}
