//! Synapsix Theme - COSMIC-inspired theme system
//!
//! This crate provides a framework-agnostic theme system inspired by System76's
//! COSMIC desktop. It supports both iced and egui through optional features.
//!
//! ## Features
//!
//! - `iced` - Enable iced::Color conversion methods
//! - `egui` - Enable egui::Color32 conversion methods
//! - `serde` - Enable serialization support
//!
//! ## Usage
//!
//! ```rust
//! use synapsix_theme::{CosmicPalette, CosmicPreset};
//!
//! // Get a preset palette
//! let palette = CosmicPreset::Dark.palette();
//!
//! // Use raw RGB values (works anywhere)
//! let bg = palette.bg_base;
//! println!("Background: rgb({}, {}, {})", bg[0], bg[1], bg[2]);
//!
//! // With iced feature:
//! #[cfg(feature = "iced")]
//! let iced_color = palette.bg_base_iced();
//!
//! // With egui feature:
//! #[cfg(feature = "egui")]
//! let egui_color = palette.bg_base_egui();
//! ```

#[cfg(feature = "serde")]
use serde::{Deserialize, Serialize};

// ============================================================================
// Color Type
// ============================================================================

/// RGB color as raw bytes (framework-agnostic)
pub type Rgb = [u8; 3];

/// RGBA color as raw bytes
pub type Rgba = [u8; 4];

// ============================================================================
// Design Tokens
// ============================================================================

/// Spacing values in pixels
#[derive(Debug, Clone, Copy)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct Spacing {
    /// Extra small: 4px
    pub xs: f32,
    /// Small: 8px
    pub sm: f32,
    /// Medium: 12px
    pub md: f32,
    /// Large: 16px
    pub lg: f32,
    /// Extra large: 24px
    pub xl: f32,
    /// Extra extra large: 32px
    pub xxl: f32,
}

impl Default for Spacing {
    fn default() -> Self {
        Self {
            xs: 4.0,
            sm: 8.0,
            md: 12.0,
            lg: 16.0,
            xl: 24.0,
            xxl: 32.0,
        }
    }
}

/// Border radius values in pixels
#[derive(Debug, Clone, Copy)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct Radii {
    /// Small: 6px (buttons, inputs)
    pub sm: f32,
    /// Medium: 10px (cards, panels)
    pub md: f32,
    /// Large: 14px (dialogs, modals)
    pub lg: f32,
    /// Extra large: 20px (full-rounded)
    pub xl: f32,
}

impl Default for Radii {
    fn default() -> Self {
        Self {
            sm: 6.0,
            md: 10.0,
            lg: 14.0,
            xl: 20.0,
        }
    }
}

/// Typography scale
#[derive(Debug, Clone, Copy)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct Typography {
    /// Small text: 11px
    pub xs: f32,
    /// Body small: 12px
    pub sm: f32,
    /// Body: 14px
    pub md: f32,
    /// Large: 16px
    pub lg: f32,
    /// Heading: 20px
    pub xl: f32,
    /// Display: 24px
    pub xxl: f32,
}

impl Default for Typography {
    fn default() -> Self {
        Self {
            xs: 11.0,
            sm: 12.0,
            md: 14.0,
            lg: 16.0,
            xl: 20.0,
            xxl: 24.0,
        }
    }
}

/// Complete design tokens
#[derive(Debug, Clone, Copy, Default)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct DesignTokens {
    pub spacing: Spacing,
    pub radii: Radii,
    pub typography: Typography,
}

// ============================================================================
// Color Palette
// ============================================================================

/// COSMIC-inspired color palette
/// 
/// Colors are stored as raw RGB bytes for framework independence.
/// Use the conversion methods to get framework-specific types.
#[derive(Debug, Clone, Copy)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct CosmicPalette {
    // Backgrounds
    /// Base background (deepest)
    pub bg_base: Rgb,
    /// Component background (cards, panels)
    pub bg_component: Rgb,
    /// Elevated background (modals, dropdowns)
    pub bg_elevated: Rgb,
    
    // Foreground / Text
    /// Primary text
    pub fg_primary: Rgb,
    /// Secondary text (muted)
    pub fg_secondary: Rgb,
    /// Muted text (disabled, hints)
    pub fg_muted: Rgb,
    
    // Accent colors
    /// Primary accent
    pub accent: Rgb,
    /// Accent hover state
    pub accent_hover: Rgb,
    /// Subtle accent (for backgrounds)
    pub accent_subtle: Rgb,
    /// Text on accent background
    pub on_accent: Rgb,
    
    // Semantic colors
    /// Success (green)
    pub success: Rgb,
    /// Success subtle (background)
    pub success_subtle: Rgb,
    /// Warning (amber/yellow)
    pub warning: Rgb,
    /// Warning subtle (background)
    pub warning_subtle: Rgb,
    /// Danger/Error (red)
    pub danger: Rgb,
    /// Danger subtle (background)
    pub danger_subtle: Rgb,
    
    // Borders
    /// Visible border
    pub border: Rgb,
    /// Subtle border
    pub border_subtle: Rgb,
    
    // Buttons
    /// Default button background
    pub button_bg: Rgb,
    /// Button hover
    pub button_hover: Rgb,
}

impl CosmicPalette {
    /// COSMIC Dark theme
    pub fn dark() -> Self {
        Self {
            // Backgrounds (deep grays)
            bg_base: [18, 18, 20],           // #121214
            bg_component: [28, 28, 32],       // #1c1c20
            bg_elevated: [38, 38, 44],        // #26262c
            
            // Foreground (light grays)
            fg_primary: [245, 245, 247],      // #f5f5f7
            fg_secondary: [155, 155, 165],    // #9b9ba5
            fg_muted: [100, 100, 110],        // #64646e
            
            // Accent (COSMIC blue)
            accent: [77, 136, 230],           // #4d88e6
            accent_hover: [100, 155, 240],    // #649bf0
            accent_subtle: [35, 55, 90],      // #23375a
            on_accent: [255, 255, 255],       // #ffffff
            
            // Semantic
            success: [72, 187, 120],          // #48bb78
            success_subtle: [30, 55, 40],     // #1e3728
            warning: [245, 158, 11],          // #f59e0b
            warning_subtle: [60, 45, 15],     // #3c2d0f
            danger: [235, 87, 87],            // #eb5757
            danger_subtle: [60, 30, 30],      // #3c1e1e
            
            // Borders
            border: [55, 55, 62],             // #37373e
            border_subtle: [40, 40, 46],      // #28282e
            
            // Buttons
            button_bg: [50, 50, 56],          // #323238
            button_hover: [60, 60, 68],       // #3c3c44
        }
    }

    /// COSMIC Light theme
    pub fn light() -> Self {
        Self {
            // Backgrounds (light grays)
            bg_base: [250, 250, 252],         // #fafafc
            bg_component: [255, 255, 255],    // #ffffff
            bg_elevated: [240, 240, 244],     // #f0f0f4
            
            // Foreground (dark grays)
            fg_primary: [36, 36, 42],         // #24242a
            fg_secondary: [100, 100, 110],    // #64646e
            fg_muted: [150, 150, 160],        // #9696a0
            
            // Accent (darker blue for contrast)
            accent: [50, 110, 200],           // #326ec8
            accent_hover: [40, 95, 180],      // #285fb4
            accent_subtle: [220, 235, 255],   // #dcebff
            on_accent: [255, 255, 255],       // #ffffff
            
            // Semantic
            success: [46, 160, 67],           // #2ea043
            success_subtle: [220, 250, 225],  // #dcfae1
            warning: [191, 135, 0],           // #bf8700
            warning_subtle: [255, 245, 210],  // #fff5d2
            danger: [207, 34, 46],            // #cf222e
            danger_subtle: [255, 220, 220],   // #ffdcdc
            
            // Borders
            border: [216, 216, 220],          // #d8d8dc
            border_subtle: [230, 230, 235],   // #e6e6eb
            
            // Buttons
            button_bg: [230, 230, 235],       // #e6e6eb
            button_hover: [220, 220, 225],    // #dcdce1
        }
    }

    /// Pop!_OS Orange accent variant (dark base)
    pub fn pop_orange() -> Self {
        let mut p = Self::dark();
        p.accent = [255, 173, 0];             // #ffad00
        p.accent_hover = [255, 190, 40];      // #ffbe28
        p.accent_subtle = [60, 45, 10];       // #3c2d0a
        p.on_accent = [0, 0, 0];              // Black text on orange
        p
    }

    /// Cool Blue accent variant (dark base)
    pub fn cool_blue() -> Self {
        let mut p = Self::dark();
        p.accent = [100, 181, 246];           // #64b5f6
        p.accent_hover = [130, 200, 255];     // #82c8ff
        p.accent_subtle = [25, 50, 75];       // #19324b
        p
    }

    /// Mint accent variant (dark base)
    pub fn mint() -> Self {
        let mut p = Self::dark();
        p.accent = [129, 199, 132];           // #81c784
        p.accent_hover = [150, 220, 155];     // #96dc9b
        p.accent_subtle = [25, 50, 35];       // #193223
        p
    }

    /// Warm Amber accent variant (dark base)
    pub fn warm_amber() -> Self {
        let mut p = Self::dark();
        p.accent = [255, 183, 77];            // #ffb74d
        p.accent_hover = [255, 200, 100];     // #ffc864
        p.accent_subtle = [55, 40, 15];       // #37280f
        p
    }

    /// Check if this is a light theme
    pub fn is_light(&self) -> bool {
        // Calculate luminance of background
        let [r, g, b] = self.bg_base;
        let luminance = 0.299 * (r as f32) + 0.587 * (g as f32) + 0.114 * (b as f32);
        luminance > 127.5
    }
}

// ============================================================================
// Framework Conversions - iced
// ============================================================================

#[cfg(feature = "iced")]
impl CosmicPalette {
    /// Convert RGB to iced Color
    fn rgb_to_iced(rgb: Rgb) -> iced_core::Color {
        iced_core::Color::from_rgb8(rgb[0], rgb[1], rgb[2])
    }

    pub fn bg_base_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.bg_base) }
    pub fn bg_component_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.bg_component) }
    pub fn bg_elevated_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.bg_elevated) }
    pub fn fg_primary_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.fg_primary) }
    pub fn fg_secondary_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.fg_secondary) }
    pub fn fg_muted_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.fg_muted) }
    pub fn accent_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.accent) }
    pub fn accent_hover_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.accent_hover) }
    pub fn accent_subtle_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.accent_subtle) }
    pub fn on_accent_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.on_accent) }
    pub fn success_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.success) }
    pub fn success_subtle_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.success_subtle) }
    pub fn warning_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.warning) }
    pub fn warning_subtle_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.warning_subtle) }
    pub fn danger_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.danger) }
    pub fn danger_subtle_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.danger_subtle) }
    pub fn border_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.border) }
    pub fn border_subtle_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.border_subtle) }
    pub fn button_bg_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.button_bg) }
    pub fn button_hover_iced(&self) -> iced_core::Color { Self::rgb_to_iced(self.button_hover) }

    /// Convert palette to iced Theme
    pub fn to_iced_theme(&self, name: &str) -> iced_core::Theme {
        let palette = iced_core::theme::Palette {
            background: self.bg_base_iced(),
            text: self.fg_primary_iced(),
            primary: self.accent_iced(),
            success: self.success_iced(),
            warning: self.warning_iced(),
            danger: self.danger_iced(),
        };
        iced_core::Theme::custom(name.to_string(), palette)
    }
}

// ============================================================================
// Framework Conversions - egui
// ============================================================================

#[cfg(feature = "egui")]
impl CosmicPalette {
    /// Convert RGB to egui Color32
    fn rgb_to_egui(rgb: Rgb) -> egui::Color32 {
        egui::Color32::from_rgb(rgb[0], rgb[1], rgb[2])
    }

    pub fn bg_base_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.bg_base) }
    pub fn bg_component_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.bg_component) }
    pub fn bg_elevated_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.bg_elevated) }
    pub fn fg_primary_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.fg_primary) }
    pub fn fg_secondary_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.fg_secondary) }
    pub fn fg_muted_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.fg_muted) }
    pub fn accent_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.accent) }
    pub fn accent_hover_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.accent_hover) }
    pub fn accent_subtle_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.accent_subtle) }
    pub fn on_accent_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.on_accent) }
    pub fn success_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.success) }
    pub fn success_subtle_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.success_subtle) }
    pub fn warning_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.warning) }
    pub fn warning_subtle_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.warning_subtle) }
    pub fn danger_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.danger) }
    pub fn danger_subtle_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.danger_subtle) }
    pub fn border_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.border) }
    pub fn border_subtle_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.border_subtle) }
    pub fn button_bg_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.button_bg) }
    pub fn button_hover_egui(&self) -> egui::Color32 { Self::rgb_to_egui(self.button_hover) }
}

// ============================================================================
// Theme Presets
// ============================================================================

/// Available COSMIC theme presets
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub enum CosmicPreset {
    /// COSMIC Dark (default)
    #[default]
    Dark,
    /// COSMIC Light
    Light,
    /// Pop!_OS Orange accent
    PopOrange,
    /// Cool Blue accent
    CoolBlue,
    /// Mint Green accent
    Mint,
    /// Warm Amber accent
    WarmAmber,
}

impl CosmicPreset {
    /// Get the preset name
    pub fn name(&self) -> &'static str {
        match self {
            Self::Dark => "COSMIC Dark",
            Self::Light => "COSMIC Light",
            Self::PopOrange => "Pop Orange",
            Self::CoolBlue => "Cool Blue",
            Self::Mint => "Mint",
            Self::WarmAmber => "Warm Amber",
        }
    }

    /// Get the color palette for this preset
    pub fn palette(&self) -> CosmicPalette {
        match self {
            Self::Dark => CosmicPalette::dark(),
            Self::Light => CosmicPalette::light(),
            Self::PopOrange => CosmicPalette::pop_orange(),
            Self::CoolBlue => CosmicPalette::cool_blue(),
            Self::Mint => CosmicPalette::mint(),
            Self::WarmAmber => CosmicPalette::warm_amber(),
        }
    }

    /// All available presets
    pub fn all() -> &'static [CosmicPreset] {
        &[
            Self::Dark,
            Self::Light,
            Self::PopOrange,
            Self::CoolBlue,
            Self::Mint,
            Self::WarmAmber,
        ]
    }

    /// Get the iced Theme for this preset (requires `iced` feature)
    #[cfg(feature = "iced")]
    pub fn to_iced_theme(&self) -> iced_core::Theme {
        self.palette().to_iced_theme(self.name())
    }
}

// ============================================================================
// Complete Theme
// ============================================================================

/// Complete theme with colors and design tokens
#[derive(Debug, Clone)]
#[cfg_attr(feature = "serde", derive(Serialize, Deserialize))]
pub struct Theme {
    /// Theme name
    pub name: String,
    /// Color palette
    pub palette: CosmicPalette,
    /// Design tokens
    pub tokens: DesignTokens,
}

impl Theme {
    /// Create a new theme from a preset
    pub fn from_preset(preset: CosmicPreset) -> Self {
        Self {
            name: preset.name().to_string(),
            palette: preset.palette(),
            tokens: DesignTokens::default(),
        }
    }

    /// Dark theme (default)
    pub fn dark() -> Self {
        Self::from_preset(CosmicPreset::Dark)
    }

    /// Light theme
    pub fn light() -> Self {
        Self::from_preset(CosmicPreset::Light)
    }
}

impl Default for Theme {
    fn default() -> Self {
        Self::dark()
    }
}

// ============================================================================
// Tests
// ============================================================================

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
        for preset in CosmicPreset::all() {
            let palette = preset.palette();
            assert!(!preset.name().is_empty());
            // Ensure we can access colors
            let _ = palette.bg_base;
            let _ = palette.accent;
        }
    }

    #[test]
    fn test_theme() {
        let theme = Theme::dark();
        assert_eq!(theme.name, "COSMIC Dark");
        assert!(!theme.palette.is_light());
        assert_eq!(theme.tokens.spacing.md, 12.0);
    }
}
