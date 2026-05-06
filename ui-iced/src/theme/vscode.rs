//! VS Code theme JSON parser
//!
//! Parses VS Code theme files and converts them to our SemanticColors format.

use super::{parse_hex_color, SemanticColors};
use serde::Deserialize;
use std::collections::HashMap;

/// VS Code theme JSON structure
#[derive(Debug, Deserialize)]
pub struct VSCodeTheme {
    pub name: Option<String>,
    #[serde(rename = "type")]
    pub theme_type: Option<String>,
    pub colors: Option<HashMap<String, String>>,
    #[serde(rename = "tokenColors")]
    pub token_colors: Option<Vec<TokenColor>>,
}

#[derive(Debug, Deserialize)]
pub struct TokenColor {
    pub scope: Option<VSCodeScope>,
    pub settings: Option<HashMap<String, serde_json::Value>>,
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub enum VSCodeScope {
    String(String),
    Array(Vec<String>),
}

/// Parse a VS Code theme JSON string into SemanticColors
pub fn parse_vscode_theme(json: &str) -> Option<SemanticColors> {
    // Strip comments (VS Code themes often have them)
    let cleaned = strip_json_comments(json);

    let theme_json: VSCodeTheme = serde_json::from_str(&cleaned).ok()?;

    // Determine base theme (dark or light)
    let is_light = theme_json.theme_type.as_deref() == Some("light")
        || theme_json.theme_type.as_deref() == Some("hc-light")
        || theme_json
            .name
            .as_ref()
            .map(|n| n.to_lowercase().contains("light"))
            .unwrap_or(false);

    let mut colors = if is_light {
        SemanticColors::light()
    } else {
        SemanticColors::dark()
    };

    // Parse workbench colors
    if let Some(ref color_map) = theme_json.colors {
        // Editor
        if let Some(c) = color_map
            .get("editor.background")
            .and_then(|s| parse_hex_color(s))
        {
            colors.editor_background = c;
            colors.background = c;
            colors.tab_active_background = c;
        }
        if let Some(c) = color_map
            .get("editor.foreground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.editor_foreground = c;
            colors.foreground = c;
        }
        if let Some(c) = color_map.get("foreground").and_then(|s| parse_hex_color(s)) {
            colors.foreground = c;
        }

        // Sidebar
        if let Some(c) = color_map
            .get("sideBar.background")
            .and_then(|s| parse_hex_color(s))
        {
            colors.sidebar_background = c;
        }
        if let Some(c) = color_map
            .get("sideBar.foreground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.sidebar_foreground = c;
        }

        // Activity bar
        if let Some(c) = color_map
            .get("activityBar.background")
            .and_then(|s| parse_hex_color(s))
        {
            colors.activitybar_background = c;
        }
        if let Some(c) = color_map
            .get("activityBar.foreground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.activitybar_foreground = c;
        }

        // Status bar
        if let Some(c) = color_map
            .get("statusBar.background")
            .and_then(|s| parse_hex_color(s))
        {
            colors.statusbar_background = c;
        }
        if let Some(c) = color_map
            .get("statusBar.foreground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.statusbar_foreground = c;
        }

        // Tabs
        if let Some(c) = color_map
            .get("tab.inactiveBackground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.tab_background = c;
        }
        if let Some(c) = color_map
            .get("tab.activeBackground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.tab_active_background = c;
        }
        if let Some(c) = color_map
            .get("tab.hoverBackground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.tab_hover_background = c;
        }

        // Input
        if let Some(c) = color_map
            .get("input.background")
            .and_then(|s| parse_hex_color(s))
        {
            colors.input_background = c;
        }
        if let Some(c) = color_map
            .get("input.foreground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.input_foreground = c;
        }
        if let Some(c) = color_map
            .get("input.border")
            .and_then(|s| parse_hex_color(s))
        {
            colors.input_border = c;
        }

        // Accent colors (try multiple sources)
        for key in &[
            "button.background",
            "focusBorder",
            "list.activeSelectionBackground",
        ] {
            if let Some(c) = color_map.get(*key).and_then(|s| parse_hex_color(s)) {
                let brightness = (c.r + c.g + c.b) / 3.0;
                if brightness > 0.15 && c.a > 0.5 {
                    colors.accent = c;
                    break;
                }
            }
        }

        // Selection
        if let Some(c) = color_map
            .get("editor.selectionBackground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.selection = c;
        }

        // List hover
        if let Some(c) = color_map
            .get("list.hoverBackground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.list_hover = c;
        }

        // Border
        if let Some(c) = color_map
            .get("panel.border")
            .and_then(|s| parse_hex_color(s))
        {
            colors.border = c;
        }

        // Dim foreground
        if let Some(c) = color_map
            .get("descriptionForeground")
            .and_then(|s| parse_hex_color(s))
        {
            colors.foreground_dim = c;
        }
    }

    // Parse token colors for syntax highlighting
    if let Some(ref token_colors) = theme_json.token_colors {
        for token in token_colors {
            if let (Some(scope), Some(settings)) = (&token.scope, &token.settings) {
                if let Some(fg) = settings.get("foreground").and_then(|v| v.as_str()) {
                    if let Some(color) = parse_hex_color(fg) {
                        let scope_str = match scope {
                            VSCodeScope::String(s) => s.as_str(),
                            VSCodeScope::Array(arr) => {
                                arr.first().map(|s| s.as_str()).unwrap_or("")
                            }
                        };

                        if scope_str.contains("keyword") {
                            colors.syntax_keyword = color;
                        } else if scope_str.contains("string") {
                            colors.syntax_string = color;
                        } else if scope_str.contains("constant.numeric")
                            || scope_str.contains("number")
                        {
                            colors.syntax_number = color;
                        } else if scope_str.contains("comment") {
                            colors.syntax_comment = color;
                        } else if scope_str.contains("entity.name.function")
                            || scope_str.contains("support.function")
                        {
                            colors.syntax_function = color;
                        } else if scope_str.contains("variable") {
                            colors.syntax_variable = color;
                        } else if scope_str.contains("entity.name.type")
                            || scope_str.contains("support.type")
                        {
                            colors.syntax_type = color;
                        }
                    }
                }
            }
        }
    }

    Some(colors)
}

/// Strip C-style comments from JSON
fn strip_json_comments(json: &str) -> String {
    let mut result = String::with_capacity(json.len());
    let mut chars = json.chars().peekable();
    let mut in_string = false;

    while let Some(c) = chars.next() {
        if in_string {
            result.push(c);
            if c == '\\' {
                if let Some(next) = chars.next() {
                    result.push(next);
                }
            } else if c == '"' {
                in_string = false;
            }
        } else if c == '"' {
            in_string = true;
            result.push(c);
        } else if c == '/' {
            if chars.peek() == Some(&'/') {
                // Line comment
                for nc in chars.by_ref() {
                    if nc == '\n' {
                        result.push('\n');
                        break;
                    }
                }
            } else if chars.peek() == Some(&'*') {
                // Block comment
                chars.next();
                while let Some(nc) = chars.next() {
                    if nc == '*' && chars.peek() == Some(&'/') {
                        chars.next();
                        break;
                    }
                }
            } else {
                result.push(c);
            }
        } else {
            result.push(c);
        }
    }

    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_strip_comments() {
        let json = r#"{
            // Line comment
            "key": "value", /* block */
            "key2": "value2"
        }"#;

        let stripped = strip_json_comments(json);
        assert!(!stripped.contains("//"));
        assert!(!stripped.contains("/*"));
    }

    #[test]
    fn test_parse_minimal_theme() {
        let json = r##"{
            "name": "Test Dark",
            "type": "dark",
            "colors": {
                "editor.background": "#1e1e1e"
            }
        }"##;

        let colors = parse_vscode_theme(json).unwrap();
        assert!(!colors.is_light());
    }
}
