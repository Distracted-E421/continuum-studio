//! Theme system for Continuum Studio iced UI
//!
//! This module provides VS Code theme compatibility for iced applications.
//! It parses VS Code theme JSON files and converts them to iced themes.

use iced::Color;
use std::path::Path;

pub mod vscode;

/// Semantic color tokens matching VS Code's color system
/// These can be translated to both iced's built-in themes and COSMIC themes
#[derive(Debug, Clone)]
pub struct SemanticColors {
    // Base colors
    pub background: Color,
    pub foreground: Color,
    pub foreground_dim: Color,
    
    // Editor
    pub editor_background: Color,
    pub editor_foreground: Color,
    
    // Sidebar
    pub sidebar_background: Color,
    pub sidebar_foreground: Color,
    
    // Activity bar
    pub activitybar_background: Color,
    pub activitybar_foreground: Color,
    
    // Status bar
    pub statusbar_background: Color,
    pub statusbar_foreground: Color,
    
    // Tabs
    pub tab_background: Color,
    pub tab_active_background: Color,
    pub tab_hover_background: Color,
    
    // Input
    pub input_background: Color,
    pub input_foreground: Color,
    pub input_border: Color,
    
    // Accent
    pub accent: Color,
    pub accent_hover: Color,
    
    // Semantic
    pub success: Color,
    pub warning: Color,
    pub error: Color,
    
    // Selection
    pub selection: Color,
    pub list_hover: Color,
    
    // Border
    pub border: Color,
    
    // Syntax highlighting
    pub syntax_keyword: Color,
    pub syntax_string: Color,
    pub syntax_number: Color,
    pub syntax_comment: Color,
    pub syntax_function: Color,
    pub syntax_variable: Color,
    pub syntax_type: Color,
    pub syntax_operator: Color,
}

impl Default for SemanticColors {
    fn default() -> Self {
        Self::dark()
    }
}

impl SemanticColors {
    /// VS Code Dark+ inspired defaults
    pub fn dark() -> Self {
        Self {
            background: Color::from_rgb8(30, 30, 30),
            foreground: Color::from_rgb8(204, 204, 204),
            foreground_dim: Color::from_rgb8(128, 128, 128),
            
            editor_background: Color::from_rgb8(30, 30, 30),
            editor_foreground: Color::from_rgb8(204, 204, 204),
            
            sidebar_background: Color::from_rgb8(37, 37, 38),
            sidebar_foreground: Color::from_rgb8(204, 204, 204),
            
            activitybar_background: Color::from_rgb8(51, 51, 51),
            activitybar_foreground: Color::from_rgb8(255, 255, 255),
            
            statusbar_background: Color::from_rgb8(0, 122, 204),
            statusbar_foreground: Color::from_rgb8(255, 255, 255),
            
            tab_background: Color::from_rgb8(45, 45, 45),
            tab_active_background: Color::from_rgb8(30, 30, 30),
            tab_hover_background: Color::from_rgb8(55, 55, 55),
            
            input_background: Color::from_rgb8(60, 60, 60),
            input_foreground: Color::from_rgb8(204, 204, 204),
            input_border: Color::from_rgb8(60, 60, 60),
            
            accent: Color::from_rgb8(0, 120, 212),
            accent_hover: Color::from_rgb8(26, 140, 255),
            
            success: Color::from_rgb8(63, 185, 80),
            warning: Color::from_rgb8(204, 167, 0),
            error: Color::from_rgb8(248, 81, 73),
            
            selection: Color::from_rgb8(38, 79, 120),
            list_hover: Color::from_rgb8(42, 45, 46),
            
            border: Color::from_rgb8(60, 60, 60),
            
            syntax_keyword: Color::from_rgb8(86, 156, 214),
            syntax_string: Color::from_rgb8(206, 145, 120),
            syntax_number: Color::from_rgb8(181, 206, 168),
            syntax_comment: Color::from_rgb8(106, 153, 85),
            syntax_function: Color::from_rgb8(220, 220, 170),
            syntax_variable: Color::from_rgb8(156, 220, 254),
            syntax_type: Color::from_rgb8(78, 201, 176),
            syntax_operator: Color::from_rgb8(212, 212, 212),
        }
    }
    
    /// VS Code Light+ inspired defaults
    pub fn light() -> Self {
        Self {
            background: Color::from_rgb8(255, 255, 255),
            foreground: Color::from_rgb8(51, 51, 51),
            foreground_dim: Color::from_rgb8(128, 128, 128),
            
            editor_background: Color::from_rgb8(255, 255, 255),
            editor_foreground: Color::from_rgb8(51, 51, 51),
            
            sidebar_background: Color::from_rgb8(243, 243, 243),
            sidebar_foreground: Color::from_rgb8(51, 51, 51),
            
            activitybar_background: Color::from_rgb8(51, 51, 51),
            activitybar_foreground: Color::from_rgb8(255, 255, 255),
            
            statusbar_background: Color::from_rgb8(0, 122, 204),
            statusbar_foreground: Color::from_rgb8(255, 255, 255),
            
            tab_background: Color::from_rgb8(236, 236, 236),
            tab_active_background: Color::from_rgb8(255, 255, 255),
            tab_hover_background: Color::from_rgb8(220, 220, 220),
            
            input_background: Color::from_rgb8(255, 255, 255),
            input_foreground: Color::from_rgb8(51, 51, 51),
            input_border: Color::from_rgb8(200, 200, 200),
            
            accent: Color::from_rgb8(0, 120, 212),
            accent_hover: Color::from_rgb8(26, 140, 255),
            
            success: Color::from_rgb8(40, 160, 40),
            warning: Color::from_rgb8(180, 130, 0),
            error: Color::from_rgb8(200, 50, 50),
            
            selection: Color::from_rgb8(173, 214, 255),
            list_hover: Color::from_rgb8(232, 232, 232),
            
            border: Color::from_rgb8(200, 200, 200),
            
            syntax_keyword: Color::from_rgb8(0, 0, 255),
            syntax_string: Color::from_rgb8(163, 21, 21),
            syntax_number: Color::from_rgb8(9, 136, 90),
            syntax_comment: Color::from_rgb8(0, 128, 0),
            syntax_function: Color::from_rgb8(121, 94, 38),
            syntax_variable: Color::from_rgb8(0, 16, 128),
            syntax_type: Color::from_rgb8(38, 127, 153),
            syntax_operator: Color::from_rgb8(0, 0, 0),
        }
    }
    
    /// Load from VS Code theme JSON file
    pub fn from_vscode_file(path: &Path) -> Option<Self> {
        let content = std::fs::read_to_string(path).ok()?;
        Self::from_vscode_json(&content)
    }
    
    /// Parse from VS Code theme JSON content
    pub fn from_vscode_json(json: &str) -> Option<Self> {
        vscode::parse_vscode_theme(json)
    }
    
    /// Check if this is a light theme based on background brightness
    pub fn is_light(&self) -> bool {
        let brightness = (self.background.r + self.background.g + self.background.b) / 3.0;
        brightness > 0.5
    }
    
    /// Convert to iced Theme
    /// 
    /// Note: iced's built-in Theme doesn't support all our semantic colors,
    /// so this provides basic dark/light theming. For full theming, use
    /// custom styling or COSMIC's theme system.
    pub fn to_iced_theme(&self) -> iced::Theme {
        if self.is_light() {
            iced::Theme::Light
        } else {
            iced::Theme::Dark
        }
    }
}

/// Parse hex color string to iced Color
pub fn parse_hex_color(hex: &str) -> Option<Color> {
    let hex = hex.trim_start_matches('#');
    
    if hex.len() == 6 {
        let r = u8::from_str_radix(&hex[0..2], 16).ok()?;
        let g = u8::from_str_radix(&hex[2..4], 16).ok()?;
        let b = u8::from_str_radix(&hex[4..6], 16).ok()?;
        Some(Color::from_rgb8(r, g, b))
    } else if hex.len() == 8 {
        let r = u8::from_str_radix(&hex[0..2], 16).ok()?;
        let g = u8::from_str_radix(&hex[2..4], 16).ok()?;
        let b = u8::from_str_radix(&hex[4..6], 16).ok()?;
        let a = u8::from_str_radix(&hex[6..8], 16).ok()?;
        Some(Color::from_rgba8(r, g, b, a as f32 / 255.0))
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_parse_hex_color() {
        assert!(parse_hex_color("#ffffff").is_some());
        assert!(parse_hex_color("#000000").is_some());
        assert!(parse_hex_color("#ff000080").is_some());
    }
    
    #[test]
    fn test_dark_theme_is_dark() {
        let theme = SemanticColors::dark();
        assert!(!theme.is_light());
    }
    
    #[test]
    fn test_light_theme_is_light() {
        let theme = SemanticColors::light();
        assert!(theme.is_light());
    }
}
