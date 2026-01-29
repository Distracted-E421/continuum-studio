//! Code View Widget
//!
//! Syntax-highlighted code display widget with line numbers,
//! scrolling, and selection support.

use eframe::egui::{self, Color32, FontId, Pos2, Rect, Sense, Vec2};
use anyhow::Result;
use super::{Widget, WidgetEvent};

/// Language for syntax highlighting
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Language {
    Rust,
    Nix,
    Elixir,
    Python,
    TypeScript,
    Markdown,
    Json,
    Yaml,
    Shell,
    D2,
    Plain,
}

impl Language {
    pub fn from_extension(ext: &str) -> Self {
        match ext.to_lowercase().as_str() {
            "rs" => Language::Rust,
            "nix" => Language::Nix,
            "ex" | "exs" => Language::Elixir,
            "py" => Language::Python,
            "ts" | "tsx" | "js" | "jsx" => Language::TypeScript,
            "md" | "markdown" => Language::Markdown,
            "json" => Language::Json,
            "yaml" | "yml" => Language::Yaml,
            "sh" | "bash" | "nu" => Language::Shell,
            "d2" => Language::D2,
            _ => Language::Plain,
        }
    }
}

/// Theme colors for code display
#[derive(Clone, Copy)]
pub struct CodeTheme {
    pub background: Color32,
    pub line_number_bg: Color32,
    pub line_number_fg: Color32,
    pub text: Color32,
    pub keyword: Color32,
    pub string: Color32,
    pub comment: Color32,
    pub number: Color32,
    pub function: Color32,
    pub type_name: Color32,
    pub operator: Color32,
    pub selection_bg: Color32,
    pub current_line_bg: Color32,
}

impl Default for CodeTheme {
    fn default() -> Self {
        Self::dark()
    }
}

impl CodeTheme {
    pub fn dark() -> Self {
        Self {
            background: Color32::from_rgb(30, 30, 30),
            line_number_bg: Color32::from_rgb(35, 35, 35),
            line_number_fg: Color32::from_rgb(133, 133, 133),
            text: Color32::from_rgb(212, 212, 212),
            keyword: Color32::from_rgb(86, 156, 214),    // blue
            string: Color32::from_rgb(206, 145, 120),    // orange
            comment: Color32::from_rgb(106, 153, 85),    // green
            number: Color32::from_rgb(181, 206, 168),    // light green
            function: Color32::from_rgb(220, 220, 170),  // yellow
            type_name: Color32::from_rgb(78, 201, 176),  // teal
            operator: Color32::from_rgb(212, 212, 212),  // white
            selection_bg: Color32::from_rgba_unmultiplied(38, 79, 120, 200),
            current_line_bg: Color32::from_rgba_unmultiplied(255, 255, 255, 10),
        }
    }
}

/// Token type for syntax highlighting
#[derive(Debug, Clone, Copy)]
pub enum TokenType {
    Plain,
    Keyword,
    String,
    Comment,
    Number,
    Function,
    TypeName,
    Operator,
}

/// A highlighted token
#[derive(Debug, Clone)]
pub struct Token {
    pub text: String,
    pub token_type: TokenType,
}

/// Simple syntax highlighter
pub struct SyntaxHighlighter {
    language: Language,
}

impl SyntaxHighlighter {
    pub fn new(language: Language) -> Self {
        Self { language }
    }

    pub fn highlight_line(&self, line: &str) -> Vec<Token> {
        let mut tokens: Vec<Token> = Vec::new();
        let trimmed = line.trim_start();
        
        // Check for comment
        let comment_start = match self.language {
            Language::Rust | Language::TypeScript | Language::D2 => Some("//"),
            Language::Nix | Language::Python | Language::Shell => Some("#"),
            Language::Elixir => Some("#"),
            _ => None,
        };

        if let Some(prefix) = comment_start {
            if trimmed.starts_with(prefix) {
                return vec![Token {
                    text: line.to_string(),
                    token_type: TokenType::Comment,
                }];
            }
        }

        // Simple tokenization
        let keywords = self.get_keywords();
        let mut current_word = String::new();
        let mut result = Vec::new();
        let mut in_string = false;
        let mut string_char = '"';
        let mut chars = line.chars().peekable();

        while let Some(c) = chars.next() {
            // String handling
            if (c == '"' || c == '\'') && !in_string {
                // Flush current word
                if !current_word.is_empty() {
                    result.push(self.classify_word(&current_word, &keywords));
                    current_word.clear();
                }
                in_string = true;
                string_char = c;
                current_word.push(c);
                continue;
            }

            if in_string {
                current_word.push(c);
                if c == string_char {
                    result.push(Token {
                        text: current_word.clone(),
                        token_type: TokenType::String,
                    });
                    current_word.clear();
                    in_string = false;
                }
                continue;
            }

            // Regular tokenization
            if c.is_whitespace() || c == '(' || c == ')' || c == '{' || c == '}' 
               || c == '[' || c == ']' || c == ',' || c == ';' || c == ':' {
                if !current_word.is_empty() {
                    result.push(self.classify_word(&current_word, &keywords));
                    current_word.clear();
                }
                result.push(Token {
                    text: c.to_string(),
                    token_type: if c.is_whitespace() { TokenType::Plain } else { TokenType::Operator },
                });
            } else if c == '=' || c == '+' || c == '-' || c == '*' || c == '/' 
                     || c == '<' || c == '>' || c == '!' || c == '&' || c == '|' {
                if !current_word.is_empty() {
                    result.push(self.classify_word(&current_word, &keywords));
                    current_word.clear();
                }
                result.push(Token {
                    text: c.to_string(),
                    token_type: TokenType::Operator,
                });
            } else {
                current_word.push(c);
            }
        }

        // Don't forget remaining word
        if !current_word.is_empty() {
            if in_string {
                result.push(Token {
                    text: current_word,
                    token_type: TokenType::String,
                });
            } else {
                result.push(self.classify_word(&current_word, &keywords));
            }
        }

        if result.is_empty() {
            result.push(Token {
                text: line.to_string(),
                token_type: TokenType::Plain,
            });
        }

        result
    }

    fn classify_word(&self, word: &str, keywords: &[&str]) -> Token {
        // Check if it's a keyword
        if keywords.contains(&word) {
            return Token {
                text: word.to_string(),
                token_type: TokenType::Keyword,
            };
        }

        // Check if it's a number
        if word.chars().all(|c| c.is_ascii_digit() || c == '.' || c == '_') {
            if word.chars().any(|c| c.is_ascii_digit()) {
                return Token {
                    text: word.to_string(),
                    token_type: TokenType::Number,
                };
            }
        }

        // Check if it starts with uppercase (likely type)
        if word.chars().next().map(|c| c.is_uppercase()).unwrap_or(false) {
            return Token {
                text: word.to_string(),
                token_type: TokenType::TypeName,
            };
        }

        // Check if followed by ( (likely function)
        if word.contains('!') { // macro
            return Token {
                text: word.to_string(),
                token_type: TokenType::Function,
            };
        }

        Token {
            text: word.to_string(),
            token_type: TokenType::Plain,
        }
    }

    fn get_keywords(&self) -> Vec<&'static str> {
        match self.language {
            Language::Rust => vec![
                "fn", "let", "mut", "const", "static", "struct", "enum", "impl", "trait",
                "pub", "mod", "use", "crate", "self", "super", "if", "else", "match",
                "for", "while", "loop", "break", "continue", "return", "async", "await",
                "move", "ref", "where", "type", "dyn", "unsafe", "extern", "as", "in",
                "true", "false", "Self", "None", "Some", "Ok", "Err", "Result", "Option",
            ],
            Language::Nix => vec![
                "let", "in", "with", "rec", "if", "then", "else", "inherit", "import",
                "true", "false", "null", "or", "and", "builtins", "pkgs", "lib",
            ],
            Language::Elixir => vec![
                "def", "defp", "defmodule", "do", "end", "if", "else", "unless", "case",
                "cond", "when", "fn", "use", "import", "require", "alias", "with",
                "true", "false", "nil", "and", "or", "not", "in",
            ],
            Language::Python => vec![
                "def", "class", "if", "elif", "else", "for", "while", "try", "except",
                "finally", "with", "as", "import", "from", "return", "yield", "raise",
                "pass", "break", "continue", "lambda", "and", "or", "not", "in", "is",
                "True", "False", "None", "self", "async", "await",
            ],
            Language::TypeScript => vec![
                "function", "const", "let", "var", "if", "else", "for", "while", "do",
                "switch", "case", "break", "continue", "return", "throw", "try", "catch",
                "finally", "class", "extends", "implements", "interface", "type", "enum",
                "import", "export", "from", "as", "default", "async", "await", "new",
                "this", "super", "true", "false", "null", "undefined", "void",
            ],
            Language::Shell => vec![
                "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
                "case", "esac", "function", "return", "exit", "export", "local",
                "true", "false", "in",
            ],
            _ => vec![],
        }
    }
}

/// Code view widget for displaying syntax-highlighted code
pub struct CodeViewWidget {
    content: String,
    lines: Vec<String>,
    language: Language,
    theme: CodeTheme,
    highlighter: SyntaxHighlighter,
    
    // View state
    scroll_offset: Vec2,
    current_line: Option<usize>,
    selection_start: Option<(usize, usize)>,
    selection_end: Option<(usize, usize)>,
    
    // Layout
    line_height: f32,
    char_width: f32,
    gutter_width: f32,
    
    // Options
    pub show_line_numbers: bool,
    pub wrap_lines: bool,
    pub font_size: f32,
    
    // Metadata
    title: String,
    file_path: Option<String>,
}

impl Default for CodeViewWidget {
    fn default() -> Self {
        Self::new()
    }
}

impl CodeViewWidget {
    pub fn new() -> Self {
        Self {
            content: String::new(),
            lines: Vec::new(),
            language: Language::Plain,
            theme: CodeTheme::dark(),
            highlighter: SyntaxHighlighter::new(Language::Plain),
            scroll_offset: Vec2::ZERO,
            current_line: None,
            selection_start: None,
            selection_end: None,
            line_height: 20.0,
            char_width: 8.0,
            gutter_width: 50.0,
            show_line_numbers: true,
            wrap_lines: false,
            font_size: 13.0,
            title: "Code".to_string(),
            file_path: None,
        }
    }

    pub fn with_language(mut self, language: Language) -> Self {
        self.language = language;
        self.highlighter = SyntaxHighlighter::new(language);
        self
    }

    pub fn with_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }

    pub fn set_content(&mut self, content: impl Into<String>) {
        self.content = content.into();
        self.lines = self.content.lines().map(String::from).collect();
        self.scroll_offset = Vec2::ZERO;
    }

    pub fn set_language(&mut self, language: Language) {
        self.language = language;
        self.highlighter = SyntaxHighlighter::new(language);
    }

    pub fn set_file(&mut self, path: &str, content: &str) {
        self.file_path = Some(path.to_string());
        self.title = path.rsplit('/').next().unwrap_or(path).to_string();
        
        // Auto-detect language from extension
        if let Some(ext) = path.rsplit('.').next() {
            self.set_language(Language::from_extension(ext));
        }
        
        self.set_content(content);
    }

    pub fn goto_line(&mut self, line: usize) {
        if line > 0 && line <= self.lines.len() {
            self.current_line = Some(line - 1);
            // Adjust scroll to show line
            let target_y = (line - 1) as f32 * self.line_height;
            self.scroll_offset.y = target_y;
        }
    }

    fn draw_line_numbers(&self, ui: &mut egui::Ui, rect: Rect, visible_range: std::ops::Range<usize>) {
        let painter = ui.painter_at(rect);
        
        // Background for gutter
        painter.rect_filled(rect, 0.0, self.theme.line_number_bg);
        
        // Draw line numbers
        for (i, line_num) in visible_range.enumerate() {
            let y = rect.min.y + (i as f32) * self.line_height;
            let text = format!("{:>4}", line_num + 1);
            
            let color = if Some(line_num) == self.current_line {
                self.theme.text
            } else {
                self.theme.line_number_fg
            };
            
            painter.text(
                Pos2::new(rect.min.x + 4.0, y + self.line_height / 2.0),
                egui::Align2::LEFT_CENTER,
                text,
                FontId::monospace(self.font_size),
                color,
            );
        }
    }

    fn draw_code(&self, ui: &mut egui::Ui, rect: Rect, visible_range: std::ops::Range<usize>) {
        let painter = ui.painter_at(rect);
        
        // Background
        painter.rect_filled(rect, 0.0, self.theme.background);
        
        for (i, line_idx) in visible_range.enumerate() {
            let y = rect.min.y + (i as f32) * self.line_height;
            
            // Current line highlight
            if Some(line_idx) == self.current_line {
                let line_rect = Rect::from_min_size(
                    Pos2::new(rect.min.x, y),
                    Vec2::new(rect.width(), self.line_height),
                );
                painter.rect_filled(line_rect, 0.0, self.theme.current_line_bg);
            }
            
            // Draw highlighted tokens
            if let Some(line) = self.lines.get(line_idx) {
                let tokens = self.highlighter.highlight_line(line);
                let mut x = rect.min.x + 8.0;
                
                for token in tokens {
                    let color = match token.token_type {
                        TokenType::Keyword => self.theme.keyword,
                        TokenType::String => self.theme.string,
                        TokenType::Comment => self.theme.comment,
                        TokenType::Number => self.theme.number,
                        TokenType::Function => self.theme.function,
                        TokenType::TypeName => self.theme.type_name,
                        TokenType::Operator => self.theme.operator,
                        TokenType::Plain => self.theme.text,
                    };
                    
                    let galley = painter.layout_no_wrap(
                        token.text.clone(),
                        FontId::monospace(self.font_size),
                        color,
                    );
                    
                    painter.galley(Pos2::new(x, y + 2.0), galley.clone(), Color32::TRANSPARENT);
                    x += galley.size().x;
                }
            }
        }
    }
}

impl Widget for CodeViewWidget {
    fn ui(&mut self, ui: &mut egui::Ui) -> Result<Vec<WidgetEvent>> {
        let available = ui.available_size();
        let (response, _painter) = ui.allocate_painter(available, Sense::click_and_drag());
        let rect = response.rect;
        
        // Handle scrolling
        let scroll_delta = ui.input(|i| i.smooth_scroll_delta);
        self.scroll_offset.y = (self.scroll_offset.y - scroll_delta.y).max(0.0);
        
        // Calculate visible lines
        let total_lines = self.lines.len();
        let visible_lines = ((rect.height() / self.line_height).ceil() as usize).min(total_lines);
        let start_line = ((self.scroll_offset.y / self.line_height) as usize).min(total_lines.saturating_sub(1));
        let end_line = (start_line + visible_lines).min(total_lines);
        let visible_range = start_line..end_line;
        
        // Split into gutter and code areas
        let gutter_rect = if self.show_line_numbers {
            Rect::from_min_size(rect.min, Vec2::new(self.gutter_width, rect.height()))
        } else {
            Rect::NOTHING
        };
        
        let code_rect = Rect::from_min_max(
            Pos2::new(rect.min.x + if self.show_line_numbers { self.gutter_width } else { 0.0 }, rect.min.y),
            rect.max,
        );
        
        // Draw line numbers
        if self.show_line_numbers {
            self.draw_line_numbers(ui, gutter_rect, visible_range.clone());
        }
        
        // Draw code
        self.draw_code(ui, code_rect, visible_range);
        
        // Handle click to set current line
        if response.clicked() {
            if let Some(pos) = response.interact_pointer_pos() {
                let relative_y = pos.y - rect.min.y + self.scroll_offset.y;
                let clicked_line = (relative_y / self.line_height) as usize;
                if clicked_line < self.lines.len() {
                    self.current_line = Some(clicked_line);
                }
            }
        }
        
        Ok(vec![])
    }

    fn title(&self) -> String {
        self.title.clone()
    }

    fn id(&self) -> String {
        format!("code_view_{}", self.file_path.as_deref().unwrap_or("unknown"))
    }
}

