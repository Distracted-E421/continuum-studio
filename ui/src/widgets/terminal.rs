//! Terminal Output Widget
//!
//! Displays command output with ANSI color support and scrollback buffer.

use eframe::egui::{self, Color32, FontId, Pos2, Rect, Sense, Stroke, Vec2};
use anyhow::Result;
use std::collections::VecDeque;
use super::{Widget, WidgetEvent};

/// ANSI color codes
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum AnsiColor {
    Black,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    White,
    BrightBlack,
    BrightRed,
    BrightGreen,
    BrightYellow,
    BrightBlue,
    BrightMagenta,
    BrightCyan,
    BrightWhite,
    Default,
}

impl AnsiColor {
    pub fn to_color32(&self) -> Color32 {
        match self {
            AnsiColor::Black => Color32::from_rgb(0, 0, 0),
            AnsiColor::Red => Color32::from_rgb(205, 49, 49),
            AnsiColor::Green => Color32::from_rgb(13, 188, 121),
            AnsiColor::Yellow => Color32::from_rgb(229, 229, 16),
            AnsiColor::Blue => Color32::from_rgb(36, 114, 200),
            AnsiColor::Magenta => Color32::from_rgb(188, 63, 188),
            AnsiColor::Cyan => Color32::from_rgb(17, 168, 205),
            AnsiColor::White => Color32::from_rgb(229, 229, 229),
            AnsiColor::BrightBlack => Color32::from_rgb(102, 102, 102),
            AnsiColor::BrightRed => Color32::from_rgb(241, 76, 76),
            AnsiColor::BrightGreen => Color32::from_rgb(35, 209, 139),
            AnsiColor::BrightYellow => Color32::from_rgb(245, 245, 67),
            AnsiColor::BrightBlue => Color32::from_rgb(59, 142, 234),
            AnsiColor::BrightMagenta => Color32::from_rgb(214, 112, 214),
            AnsiColor::BrightCyan => Color32::from_rgb(41, 184, 219),
            AnsiColor::BrightWhite => Color32::from_rgb(255, 255, 255),
            AnsiColor::Default => Color32::from_rgb(204, 204, 204),
        }
    }

    pub fn from_ansi_code(code: u8) -> Self {
        match code {
            30 => AnsiColor::Black,
            31 => AnsiColor::Red,
            32 => AnsiColor::Green,
            33 => AnsiColor::Yellow,
            34 => AnsiColor::Blue,
            35 => AnsiColor::Magenta,
            36 => AnsiColor::Cyan,
            37 => AnsiColor::White,
            90 => AnsiColor::BrightBlack,
            91 => AnsiColor::BrightRed,
            92 => AnsiColor::BrightGreen,
            93 => AnsiColor::BrightYellow,
            94 => AnsiColor::BrightBlue,
            95 => AnsiColor::BrightMagenta,
            96 => AnsiColor::BrightCyan,
            97 => AnsiColor::BrightWhite,
            _ => AnsiColor::Default,
        }
    }
}

/// A span of text with styling
#[derive(Debug, Clone)]
pub struct StyledSpan {
    pub text: String,
    pub fg_color: AnsiColor,
    pub bg_color: Option<AnsiColor>,
    pub bold: bool,
    pub dim: bool,
    pub italic: bool,
    pub underline: bool,
}

impl Default for StyledSpan {
    fn default() -> Self {
        Self {
            text: String::new(),
            fg_color: AnsiColor::Default,
            bg_color: None,
            bold: false,
            dim: false,
            italic: false,
            underline: false,
        }
    }
}

/// A line of terminal output
#[derive(Debug, Clone)]
pub struct TerminalLine {
    pub spans: Vec<StyledSpan>,
    pub timestamp: Option<chrono::DateTime<chrono::Utc>>,
}

impl TerminalLine {
    pub fn plain(text: impl Into<String>) -> Self {
        Self {
            spans: vec![StyledSpan {
                text: text.into(),
                ..Default::default()
            }],
            timestamp: Some(chrono::Utc::now()),
        }
    }

    pub fn styled(spans: Vec<StyledSpan>) -> Self {
        Self {
            spans,
            timestamp: Some(chrono::Utc::now()),
        }
    }
}

/// Terminal theme
#[derive(Clone, Copy)]
pub struct TerminalTheme {
    pub background: Color32,
    pub foreground: Color32,
    pub cursor: Color32,
    pub selection: Color32,
    pub scrollbar: Color32,
    pub scrollbar_hover: Color32,
}

impl Default for TerminalTheme {
    fn default() -> Self {
        Self::dark()
    }
}

impl TerminalTheme {
    pub fn dark() -> Self {
        Self {
            background: Color32::from_rgb(24, 24, 24),
            foreground: Color32::from_rgb(204, 204, 204),
            cursor: Color32::from_rgb(255, 255, 255),
            selection: Color32::from_rgba_unmultiplied(100, 100, 200, 100),
            scrollbar: Color32::from_rgba_unmultiplied(100, 100, 100, 100),
            scrollbar_hover: Color32::from_rgba_unmultiplied(150, 150, 150, 150),
        }
    }
}

/// Parse ANSI escape sequences from text
pub fn parse_ansi(input: &str) -> Vec<StyledSpan> {
    let mut spans = Vec::new();
    let mut current_span = StyledSpan::default();
    let mut chars = input.chars().peekable();
    
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            // Start of escape sequence
            if chars.peek() == Some(&'[') {
                chars.next(); // consume '['
                
                // Flush current span
                if !current_span.text.is_empty() {
                    spans.push(current_span.clone());
                    current_span.text.clear();
                }
                
                // Parse escape codes
                let mut codes = Vec::new();
                let mut code_str = String::new();
                
                while let Some(&c) = chars.peek() {
                    if c.is_ascii_digit() || c == ';' {
                        chars.next();
                        if c == ';' {
                            if !code_str.is_empty() {
                                codes.push(code_str.parse::<u8>().unwrap_or(0));
                                code_str.clear();
                            }
                        } else {
                            code_str.push(c);
                        }
                    } else {
                        break;
                    }
                }
                
                if !code_str.is_empty() {
                    codes.push(code_str.parse::<u8>().unwrap_or(0));
                }
                
                // Consume the final character (usually 'm')
                chars.next();
                
                // Apply codes
                for code in codes {
                    match code {
                        0 => {
                            // Reset
                            current_span.fg_color = AnsiColor::Default;
                            current_span.bg_color = None;
                            current_span.bold = false;
                            current_span.dim = false;
                            current_span.italic = false;
                            current_span.underline = false;
                        }
                        1 => current_span.bold = true,
                        2 => current_span.dim = true,
                        3 => current_span.italic = true,
                        4 => current_span.underline = true,
                        30..=37 | 90..=97 => {
                            current_span.fg_color = AnsiColor::from_ansi_code(code);
                        }
                        40..=47 => {
                            current_span.bg_color = Some(AnsiColor::from_ansi_code(code - 10));
                        }
                        _ => {}
                    }
                }
            }
        } else {
            current_span.text.push(c);
        }
    }
    
    // Flush final span
    if !current_span.text.is_empty() {
        spans.push(current_span);
    }
    
    spans
}

/// Terminal output widget
pub struct TerminalWidget {
    lines: VecDeque<TerminalLine>,
    max_lines: usize,
    theme: TerminalTheme,
    
    // View state
    scroll_offset: f32,
    auto_scroll: bool,
    
    // Layout
    line_height: f32,
    font_size: f32,
    padding: f32,
    
    // Options
    pub show_timestamps: bool,
    pub wrap_lines: bool,
    
    // Metadata
    title: String,
    working_directory: Option<String>,
    last_exit_code: Option<i32>,
}

impl Default for TerminalWidget {
    fn default() -> Self {
        Self::new()
    }
}

impl TerminalWidget {
    pub fn new() -> Self {
        Self {
            lines: VecDeque::new(),
            max_lines: 10000,
            theme: TerminalTheme::dark(),
            scroll_offset: 0.0,
            auto_scroll: true,
            line_height: 18.0,
            font_size: 13.0,
            padding: 8.0,
            show_timestamps: false,
            wrap_lines: false,
            title: "Terminal".to_string(),
            working_directory: None,
            last_exit_code: None,
        }
    }

    pub fn with_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    pub fn write_line(&mut self, text: impl Into<String>) {
        let text = text.into();
        let spans = parse_ansi(&text);
        self.lines.push_back(TerminalLine::styled(spans));
        
        // Trim old lines
        while self.lines.len() > self.max_lines {
            self.lines.pop_front();
        }
        
        // Auto-scroll to bottom
        if self.auto_scroll {
            self.scroll_to_bottom();
        }
    }

    pub fn write_plain(&mut self, text: impl Into<String>) {
        self.lines.push_back(TerminalLine::plain(text));
        
        while self.lines.len() > self.max_lines {
            self.lines.pop_front();
        }
        
        if self.auto_scroll {
            self.scroll_to_bottom();
        }
    }

    pub fn write_colored(&mut self, text: impl Into<String>, color: AnsiColor) {
        self.lines.push_back(TerminalLine::styled(vec![StyledSpan {
            text: text.into(),
            fg_color: color,
            ..Default::default()
        }]));
        
        while self.lines.len() > self.max_lines {
            self.lines.pop_front();
        }
        
        if self.auto_scroll {
            self.scroll_to_bottom();
        }
    }

    pub fn write_error(&mut self, text: impl Into<String>) {
        self.write_colored(text, AnsiColor::Red);
    }

    pub fn write_success(&mut self, text: impl Into<String>) {
        self.write_colored(text, AnsiColor::Green);
    }

    pub fn write_warning(&mut self, text: impl Into<String>) {
        self.write_colored(text, AnsiColor::Yellow);
    }

    pub fn write_info(&mut self, text: impl Into<String>) {
        self.write_colored(text, AnsiColor::Cyan);
    }

    pub fn clear(&mut self) {
        self.lines.clear();
        self.scroll_offset = 0.0;
    }

    pub fn scroll_to_bottom(&mut self) {
        let total_height = self.lines.len() as f32 * self.line_height;
        self.scroll_offset = total_height;
    }

    pub fn set_working_directory(&mut self, dir: impl Into<String>) {
        self.working_directory = Some(dir.into());
    }

    pub fn set_exit_code(&mut self, code: i32) {
        self.last_exit_code = Some(code);
    }

    fn draw_line(&self, painter: &egui::Painter, line: &TerminalLine, y: f32, rect: Rect) {
        let mut x = rect.min.x + self.padding;
        
        // Timestamp
        if self.show_timestamps {
            if let Some(ts) = &line.timestamp {
                let time_str = ts.format("%H:%M:%S").to_string();
                let galley = painter.layout_no_wrap(
                    time_str,
                    FontId::monospace(self.font_size - 2.0),
                    Color32::from_rgb(100, 100, 100),
                );
                painter.galley(Pos2::new(x, y), galley.clone(), Color32::TRANSPARENT);
                x += galley.size().x + 8.0;
            }
        }
        
        // Content spans
        for span in &line.spans {
            let mut color = span.fg_color.to_color32();
            
            // Apply dim modifier
            if span.dim {
                color = Color32::from_rgba_unmultiplied(
                    color.r() / 2,
                    color.g() / 2,
                    color.b() / 2,
                    color.a(),
                );
            }
            
            // Draw background if set
            if let Some(bg) = span.bg_color {
                let galley = painter.layout_no_wrap(
                    span.text.clone(),
                    FontId::monospace(self.font_size),
                    color,
                );
                let bg_rect = Rect::from_min_size(
                    Pos2::new(x, y),
                    galley.size(),
                );
                painter.rect_filled(bg_rect, 0.0, bg.to_color32());
            }
            
            let galley = painter.layout_no_wrap(
                span.text.clone(),
                FontId::monospace(self.font_size),
                color,
            );
            
            // Draw underline
            if span.underline {
                let underline_y = y + galley.size().y - 2.0;
                painter.line_segment(
                    [Pos2::new(x, underline_y), Pos2::new(x + galley.size().x, underline_y)],
                    Stroke::new(1.0, color),
                );
            }
            
            painter.galley(Pos2::new(x, y), galley.clone(), Color32::TRANSPARENT);
            x += galley.size().x;
        }
    }
}

impl Widget for TerminalWidget {
    fn ui(&mut self, ui: &mut egui::Ui) -> Result<Vec<WidgetEvent>> {
        let available = ui.available_size();
        let (response, painter) = ui.allocate_painter(available, Sense::click_and_drag());
        let rect = response.rect;
        
        // Background
        painter.rect_filled(rect, 0.0, self.theme.background);
        
        // Handle scrolling
        let scroll_delta = ui.input(|i| i.smooth_scroll_delta.y);
        if scroll_delta != 0.0 {
            self.auto_scroll = false;
            self.scroll_offset = (self.scroll_offset - scroll_delta).max(0.0);
        }
        
        // Calculate visible range
        let total_height = self.lines.len() as f32 * self.line_height;
        let max_scroll = (total_height - rect.height()).max(0.0);
        self.scroll_offset = self.scroll_offset.min(max_scroll);
        
        let start_line = (self.scroll_offset / self.line_height) as usize;
        let visible_lines = ((rect.height() / self.line_height).ceil() as usize) + 1;
        let end_line = (start_line + visible_lines).min(self.lines.len());
        
        // Draw visible lines
        for (i, line_idx) in (start_line..end_line).enumerate() {
            if let Some(line) = self.lines.get(line_idx) {
                let y = rect.min.y + (i as f32) * self.line_height - (self.scroll_offset % self.line_height);
                self.draw_line(&painter, line, y, rect);
            }
        }
        
        // Draw scrollbar if needed
        if total_height > rect.height() {
            let scrollbar_height = (rect.height() / total_height) * rect.height();
            let scrollbar_y = (self.scroll_offset / total_height) * rect.height();
            
            let scrollbar_rect = Rect::from_min_size(
                Pos2::new(rect.max.x - 8.0, rect.min.y + scrollbar_y),
                Vec2::new(6.0, scrollbar_height),
            );
            
            painter.rect_filled(scrollbar_rect, 3.0, self.theme.scrollbar);
        }
        
        // Double-click to enable auto-scroll
        if response.double_clicked() {
            self.auto_scroll = true;
            self.scroll_to_bottom();
        }
        
        Ok(vec![])
    }

    fn title(&self) -> String {
        let mut title = self.title.clone();
        if let Some(code) = self.last_exit_code {
            title.push_str(&format!(" [{}]", code));
        }
        title
    }

    fn id(&self) -> String {
        "terminal_widget".to_string()
    }
}

