//! Diagram rendering widget for Mermaid and D2 fenced code blocks
//!
//! Renders diagrams inline by calling external renderers (mmdc, d2)
//! and displaying the resulting SVG images.

use iced::widget::{container, image, text, Column, Row};
use iced::{Element, Length};
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::path::PathBuf;
use std::process::Command;
use std::sync::Arc;
use tokio::sync::RwLock;

/// Supported diagram types
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum DiagramType {
    Mermaid,
    D2,
}

impl DiagramType {
    /// Detect diagram type from language tag
    pub fn from_language(lang: &str) -> Option<Self> {
        match lang.to_lowercase().as_str() {
            "mermaid" => Some(Self::Mermaid),
            "d2" => Some(Self::D2),
            _ => None,
        }
    }

    /// Get file extension for this diagram type
    pub fn extension(&self) -> &'static str {
        match self {
            Self::Mermaid => "mmd",
            Self::D2 => "d2",
        }
    }

    /// Get renderer command name
    pub fn renderer(&self) -> &'static str {
        match self {
            Self::Mermaid => "mmdc",
            Self::D2 => "d2",
        }
    }
}

/// State of a rendered diagram
#[derive(Debug, Clone)]
pub enum DiagramState {
    /// Waiting to be rendered
    Pending,
    /// Currently rendering
    Rendering,
    /// Rendered successfully
    Rendered { svg_path: PathBuf },
    /// Rendering failed
    Error { message: String },
}

/// A cached diagram entry
#[derive(Debug)]
struct CachedDiagram {
    #[allow(dead_code)]
    content_hash: u64,
    state: DiagramState,
}

/// Cache directory for rendered diagrams
fn cache_dir() -> PathBuf {
    let cache = dirs::cache_dir().unwrap_or_else(|| PathBuf::from("/tmp"));
    cache.join("continuum-studio").join("diagrams")
}

/// Compute hash of diagram content for caching
fn hash_content(content: &str, diagram_type: DiagramType) -> u64 {
    use std::collections::hash_map::DefaultHasher;
    let mut hasher = DefaultHasher::new();
    content.hash(&mut hasher);
    diagram_type.hash(&mut hasher);
    hasher.finish()
}

/// Diagram renderer with caching
#[derive(Debug, Clone)]
pub struct DiagramRenderer {
    cache: Arc<RwLock<HashMap<u64, CachedDiagram>>>,
}

impl Default for DiagramRenderer {
    fn default() -> Self {
        Self::new()
    }
}

impl DiagramRenderer {
    pub fn new() -> Self {
        // Ensure cache directory exists
        let _ = std::fs::create_dir_all(cache_dir());

        Self {
            cache: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Check if a renderer is available
    pub fn is_renderer_available(diagram_type: DiagramType) -> bool {
        Command::new("which")
            .arg(diagram_type.renderer())
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }

    /// Get current state for a diagram
    pub async fn get_state(&self, content: &str, diagram_type: DiagramType) -> DiagramState {
        let hash = hash_content(content, diagram_type);
        let cache = self.cache.read().await;

        if let Some(cached) = cache.get(&hash) {
            cached.state.clone()
        } else {
            DiagramState::Pending
        }
    }

    /// Render a diagram asynchronously
    pub async fn render(
        &self,
        content: &str,
        diagram_type: DiagramType,
    ) -> Result<PathBuf, String> {
        let hash = hash_content(content, diagram_type);

        // Check cache first
        {
            let cache = self.cache.read().await;
            if let Some(cached) = cache.get(&hash) {
                if let DiagramState::Rendered { svg_path } = &cached.state {
                    if svg_path.exists() {
                        return Ok(svg_path.clone());
                    }
                }
            }
        }

        // Mark as rendering
        {
            let mut cache = self.cache.write().await;
            cache.insert(
                hash,
                CachedDiagram {
                    content_hash: hash,
                    state: DiagramState::Rendering,
                },
            );
        }

        // Render the diagram
        let result = self.do_render(content, diagram_type, hash).await;

        // Update cache with result
        {
            let mut cache = self.cache.write().await;
            let state = match &result {
                Ok(path) => DiagramState::Rendered {
                    svg_path: path.clone(),
                },
                Err(msg) => DiagramState::Error {
                    message: msg.clone(),
                },
            };
            cache.insert(
                hash,
                CachedDiagram {
                    content_hash: hash,
                    state,
                },
            );
        }

        result
    }

    async fn do_render(
        &self,
        content: &str,
        diagram_type: DiagramType,
        hash: u64,
    ) -> Result<PathBuf, String> {
        let cache_path = cache_dir();
        let input_file = cache_path.join(format!("{}.{}", hash, diagram_type.extension()));
        let output_file = cache_path.join(format!("{}.svg", hash));

        // Write input file
        std::fs::write(&input_file, content)
            .map_err(|e| format!("Failed to write input file: {}", e))?;

        // Build command
        let output = match diagram_type {
            DiagramType::Mermaid => Command::new("mmdc")
                .args([
                    "-i",
                    input_file.to_str().unwrap(),
                    "-o",
                    output_file.to_str().unwrap(),
                    "-b",
                    "transparent",
                ])
                .output(),
            DiagramType::D2 => Command::new("d2")
                .args([
                    "--theme",
                    "200", // Dark theme
                    input_file.to_str().unwrap(),
                    output_file.to_str().unwrap(),
                ])
                .output(),
        };

        let output = output.map_err(|e| format!("Failed to run renderer: {}", e))?;

        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            return Err(format!("Renderer failed: {}", stderr));
        }

        // Clean up input file
        let _ = std::fs::remove_file(&input_file);

        if output_file.exists() {
            Ok(output_file)
        } else {
            Err("Output file was not created".to_string())
        }
    }

    /// Clear cached diagrams
    pub async fn clear_cache(&self) {
        let mut cache = self.cache.write().await;
        cache.clear();

        // Also clear disk cache
        if let Ok(entries) = std::fs::read_dir(cache_dir()) {
            for entry in entries.flatten() {
                let _ = std::fs::remove_file(entry.path());
            }
        }
    }
}

/// Message type for diagram widget
#[derive(Debug, Clone)]
pub enum DiagramMessage {
    /// Request to render a diagram
    Render {
        content: String,
        diagram_type: DiagramType,
    },
    /// Rendering completed
    Rendered {
        hash: u64,
        result: Result<PathBuf, String>,
    },
}

/// Widget state for a single diagram
#[derive(Debug, Clone)]
pub struct DiagramWidget {
    pub content: String,
    pub diagram_type: DiagramType,
    pub state: DiagramState,
    pub width: Option<u32>,
    pub height: Option<u32>,
}

impl DiagramWidget {
    pub fn new(content: String, diagram_type: DiagramType) -> Self {
        Self {
            content,
            diagram_type,
            state: DiagramState::Pending,
            width: None,
            height: None,
        }
    }

    pub fn with_size(mut self, width: u32, height: u32) -> Self {
        self.width = Some(width);
        self.height = Some(height);
        self
    }

    /// Create view for this diagram
    pub fn view<'a, Message: Clone + 'a>(&self) -> Element<'a, Message> {
        match &self.state {
            DiagramState::Pending => container(text("Loading diagram...").size(14))
                .padding(10)
                .into(),

            DiagramState::Rendering => container(
                Row::new()
                    .push(text("⟳").size(16))
                    .push(text(" Rendering...").size(14))
                    .spacing(5),
            )
            .padding(10)
            .into(),

            DiagramState::Rendered { svg_path } => {
                // Load SVG as image
                if let Ok(bytes) = std::fs::read(svg_path) {
                    let handle = image::Handle::from_bytes(bytes);
                    let mut img = image::viewer(handle);

                    if let Some(w) = self.width {
                        img = img.width(Length::Fixed(w as f32));
                    }
                    if let Some(h) = self.height {
                        img = img.height(Length::Fixed(h as f32));
                    }

                    container(img).padding(5).into()
                } else {
                    container(text("Failed to load rendered diagram").size(14))
                        .padding(10)
                        .into()
                }
            }

            DiagramState::Error { message } => {
                let msg = message.clone();
                container(
                    Column::new()
                        .push(text("⚠ Diagram Error").size(14))
                        .push(text(msg).size(12))
                        .spacing(5),
                )
                .padding(10)
                .style(|_theme| container::Style {
                    background: Some(iced::Background::Color(iced::Color::from_rgb(
                        0.3, 0.15, 0.15,
                    ))),
                    ..Default::default()
                })
                .into()
            }
        }
    }
}

/// Extract diagrams from markdown content
pub fn extract_diagrams(markdown: &str) -> Vec<(DiagramType, String, usize)> {
    let mut diagrams = Vec::new();
    let mut in_code_block = false;
    let mut current_lang = String::new();
    let mut current_content = String::new();
    let mut block_start = 0;

    for (line_num, line) in markdown.lines().enumerate() {
        if let Some(after_fence) = line.strip_prefix("```") {
            if in_code_block {
                // End of code block
                if let Some(diagram_type) = DiagramType::from_language(&current_lang) {
                    diagrams.push((
                        diagram_type,
                        current_content.trim().to_string(),
                        block_start,
                    ));
                }
                current_content.clear();
                current_lang.clear();
                in_code_block = false;
            } else {
                // Start of code block
                current_lang = after_fence.trim().to_string();
                block_start = line_num;
                in_code_block = true;
            }
        } else if in_code_block {
            current_content.push_str(line);
            current_content.push('\n');
        }
    }

    diagrams
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_diagram_type_detection() {
        assert_eq!(
            DiagramType::from_language("mermaid"),
            Some(DiagramType::Mermaid)
        );
        assert_eq!(
            DiagramType::from_language("Mermaid"),
            Some(DiagramType::Mermaid)
        );
        assert_eq!(DiagramType::from_language("d2"), Some(DiagramType::D2));
        assert_eq!(DiagramType::from_language("rust"), None);
    }

    #[test]
    fn test_extract_diagrams() {
        let markdown = r#"
# Test

```mermaid
graph TD
    A --> B
```

Some text

```d2
x -> y
```

```rust
fn main() {}
```
"#;

        let diagrams = extract_diagrams(markdown);
        assert_eq!(diagrams.len(), 2);
        assert_eq!(diagrams[0].0, DiagramType::Mermaid);
        assert!(diagrams[0].1.contains("graph TD"));
        assert_eq!(diagrams[1].0, DiagramType::D2);
        assert!(diagrams[1].1.contains("x -> y"));
    }

    #[test]
    fn test_hash_consistency() {
        let content = "graph TD\n    A --> B";
        let hash1 = hash_content(content, DiagramType::Mermaid);
        let hash2 = hash_content(content, DiagramType::Mermaid);
        assert_eq!(hash1, hash2);

        // Different type should give different hash
        let hash3 = hash_content(content, DiagramType::D2);
        assert_ne!(hash1, hash3);
    }
}
