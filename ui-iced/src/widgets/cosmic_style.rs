//! COSMIC-inspired widget styles for iced
//!
//! This module provides custom styling that matches the COSMIC desktop
//! design language: rounded corners, subtle backgrounds, and clean spacing.

use iced::widget::{button, container};
use iced::{Background, Border, Color, Shadow, Vector};

use crate::theme::cosmic::CosmicPalette;

/// COSMIC-inspired button style
#[derive(Debug, Clone, Copy)]
pub struct CosmicButton {
    pub palette: CosmicPalette,
    pub is_primary: bool,
    pub is_destructive: bool,
}

impl CosmicButton {
    pub fn new(palette: CosmicPalette) -> Self {
        Self {
            palette,
            is_primary: false,
            is_destructive: false,
        }
    }

    pub fn primary(palette: CosmicPalette) -> Self {
        Self {
            palette,
            is_primary: true,
            is_destructive: false,
        }
    }

    pub fn destructive(palette: CosmicPalette) -> Self {
        Self {
            palette,
            is_primary: false,
            is_destructive: true,
        }
    }
}

impl button::Catalog for CosmicButton {
    type Class<'a> = ();
    
    fn default<'a>() -> Self::Class<'a> {
        ()
    }
    
    fn style(&self, _class: &Self::Class<'_>, status: button::Status) -> button::Style {
        let (bg, text_color) = if self.is_destructive {
            match status {
                button::Status::Active => (
                    self.palette.destructive,
                    Color::WHITE,
                ),
                button::Status::Hovered => (
                    lighten(self.palette.destructive, 0.1),
                    Color::WHITE,
                ),
                button::Status::Pressed => (
                    darken(self.palette.destructive, 0.1),
                    Color::WHITE,
                ),
                button::Status::Disabled => (
                    with_alpha(self.palette.destructive, 0.5),
                    with_alpha(Color::WHITE, 0.5),
                ),
            }
        } else if self.is_primary {
            match status {
                button::Status::Active => (
                    self.palette.accent,
                    self.palette.on_accent,
                ),
                button::Status::Hovered => (
                    lighten(self.palette.accent, 0.1),
                    self.palette.on_accent,
                ),
                button::Status::Pressed => (
                    darken(self.palette.accent, 0.1),
                    self.palette.on_accent,
                ),
                button::Status::Disabled => (
                    with_alpha(self.palette.accent, 0.5),
                    with_alpha(self.palette.on_accent, 0.5),
                ),
            }
        } else {
            // Standard button
            match status {
                button::Status::Active => (
                    self.palette.button_bg,
                    self.palette.on_bg_color,
                ),
                button::Status::Hovered => (
                    lighten(self.palette.button_bg, 0.1),
                    self.palette.on_bg_color,
                ),
                button::Status::Pressed => (
                    darken(self.palette.button_bg, 0.1),
                    self.palette.on_bg_color,
                ),
                button::Status::Disabled => (
                    with_alpha(self.palette.button_bg, 0.5),
                    with_alpha(self.palette.on_bg_color, 0.5),
                ),
            }
        };
        
        button::Style {
            background: Some(Background::Color(bg)),
            text_color,
            border: Border {
                radius: 8.0.into(), // COSMIC's rounded corners
                width: 0.0,
                color: Color::TRANSPARENT,
            },
            shadow: Shadow {
                color: Color::BLACK,
                offset: Vector::new(0.0, 1.0),
                blur_radius: 2.0,
            },
            snap: false,
        }
    }
}

/// COSMIC-inspired container style for cards/panels
#[derive(Debug, Clone, Copy)]
pub struct CosmicContainer {
    pub palette: CosmicPalette,
    pub variant: ContainerVariant,
}

#[derive(Debug, Clone, Copy, Default)]
pub enum ContainerVariant {
    /// Transparent, no background
    #[default]
    Transparent,
    /// Primary container (slightly elevated)
    Primary,
    /// Secondary container (more elevated)
    Secondary,
    /// Sidebar/panel container
    Sidebar,
    /// Card with border
    Card,
}

impl CosmicContainer {
    pub fn new(palette: CosmicPalette) -> Self {
        Self {
            palette,
            variant: ContainerVariant::Transparent,
        }
    }

    pub fn primary(palette: CosmicPalette) -> Self {
        Self {
            palette,
            variant: ContainerVariant::Primary,
        }
    }

    pub fn secondary(palette: CosmicPalette) -> Self {
        Self {
            palette,
            variant: ContainerVariant::Secondary,
        }
    }

    pub fn sidebar(palette: CosmicPalette) -> Self {
        Self {
            palette,
            variant: ContainerVariant::Sidebar,
        }
    }

    pub fn card(palette: CosmicPalette) -> Self {
        Self {
            palette,
            variant: ContainerVariant::Card,
        }
    }
}

impl container::Catalog for CosmicContainer {
    type Class<'a> = ();
    
    fn default<'a>() -> Self::Class<'a> {
        ()
    }
    
    fn style(&self, _class: &Self::Class<'_>) -> container::Style {
        match self.variant {
            ContainerVariant::Transparent => container::Style {
                background: None,
                border: Border::default(),
                shadow: Shadow::default(),
                text_color: Some(self.palette.on_bg_color),
                snap: false,
            },
            ContainerVariant::Primary => container::Style {
                background: Some(Background::Color(self.palette.primary_container_bg)),
                border: Border {
                    radius: 12.0.into(),
                    width: 0.0,
                    color: Color::TRANSPARENT,
                },
                shadow: Shadow {
                    color: with_alpha(Color::BLACK, 0.15),
                    offset: Vector::new(0.0, 2.0),
                    blur_radius: 8.0,
                },
                text_color: Some(self.palette.on_bg_color),
                snap: false,
            },
            ContainerVariant::Secondary => container::Style {
                background: Some(Background::Color(self.palette.secondary_container_bg)),
                border: Border {
                    radius: 12.0.into(),
                    width: 0.0,
                    color: Color::TRANSPARENT,
                },
                shadow: Shadow {
                    color: with_alpha(Color::BLACK, 0.1),
                    offset: Vector::new(0.0, 1.0),
                    blur_radius: 4.0,
                },
                text_color: Some(self.palette.on_bg_color),
                snap: false,
            },
            ContainerVariant::Sidebar => container::Style {
                background: Some(Background::Color(self.palette.primary_container_bg)),
                border: Border {
                    radius: 0.0.into(), // No rounding for sidebar
                    width: 1.0,
                    color: self.palette.divider,
                },
                shadow: Shadow::default(),
                text_color: Some(self.palette.on_bg_color),
                snap: false,
            },
            ContainerVariant::Card => container::Style {
                background: Some(Background::Color(self.palette.primary_container_bg)),
                border: Border {
                    radius: 16.0.into(), // Larger rounding for cards
                    width: 1.0,
                    color: self.palette.divider,
                },
                shadow: Shadow {
                    color: with_alpha(Color::BLACK, 0.1),
                    offset: Vector::new(0.0, 2.0),
                    blur_radius: 6.0,
                },
                text_color: Some(self.palette.on_bg_color),
                snap: false,
            },
        }
    }
}

// Helper functions for color manipulation

fn lighten(color: Color, amount: f32) -> Color {
    Color {
        r: (color.r + amount).min(1.0),
        g: (color.g + amount).min(1.0),
        b: (color.b + amount).min(1.0),
        a: color.a,
    }
}

fn darken(color: Color, amount: f32) -> Color {
    Color {
        r: (color.r - amount).max(0.0),
        g: (color.g - amount).max(0.0),
        b: (color.b - amount).max(0.0),
        a: color.a,
    }
}

fn with_alpha(color: Color, alpha: f32) -> Color {
    Color {
        a: alpha,
        ..color
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_lighten() {
        let c = Color::from_rgb(0.5, 0.5, 0.5);
        let l = lighten(c, 0.1);
        assert!(l.r > c.r);
    }

    #[test]
    fn test_darken() {
        let c = Color::from_rgb(0.5, 0.5, 0.5);
        let d = darken(c, 0.1);
        assert!(d.r < c.r);
    }
}
