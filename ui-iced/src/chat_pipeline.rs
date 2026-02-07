//! Chat Pipeline integration module
//!
//! Provides types and HTTP client for communicating with the
//! Synapsix Chat Pipeline API running on port 4001.

use serde::{Deserialize, Serialize};

/// Base URL for the Synapsix Chat Pipeline API
pub const CHAT_API_BASE: &str = "http://localhost:4001/api/chat";

// ---------------------------------------------------------------------------
// API Response Types
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ChatStats {
    pub store: StoreStats,
    pub summarizer: SummarizerStats,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct StoreStats {
    #[serde(default)]
    pub conversations: u64,
    #[serde(default)]
    pub messages: u64,
    #[serde(default)]
    pub chunks: u64,
    #[serde(default)]
    pub embeddings: u64,
    #[serde(default)]
    pub summaries: u64,
    #[serde(default)]
    pub topics: u64,
    #[serde(default)]
    pub workspace_summaries: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SummarizerStats {
    #[serde(default)]
    pub total_llm_calls: u64,
    #[serde(default)]
    pub conversations_summarized: u64,
    #[serde(default)]
    pub sections_generated: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ChatHealth {
    pub status: String,
    pub service: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationList {
    pub count: usize,
    pub conversations: Vec<Conversation>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Conversation {
    pub id: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub source_db: Option<String>,
    #[serde(default)]
    pub message_count: u64,
    #[serde(default)]
    pub total_input_tokens: u64,
    #[serde(default)]
    pub total_output_tokens: u64,
    #[serde(default)]
    pub is_agentic: bool,
    #[serde(default)]
    pub models_used: serde_json::Value,
    #[serde(default)]
    pub summary: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConversationDetail {
    pub conversation: Conversation,
    pub messages: Vec<ChatMessage>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ChatMessage {
    pub id: String,
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub content: String,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub input_tokens: u64,
    #[serde(default)]
    pub output_tokens: u64,
    #[serde(default)]
    pub sequence: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TopicList {
    pub count: usize,
    pub topics: Vec<Topic>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Topic {
    pub id: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub conversation_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SearchResults {
    pub query: String,
    pub mode: String,
    pub count: usize,
    pub results: Vec<SearchResult>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct SearchResult {
    pub id: String,
    #[serde(default)]
    pub conversation_id: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub chunk_type: Option<String>,
    #[serde(default)]
    pub score: f64,
    #[serde(default)]
    pub source: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScanLocations {
    pub platform: String,
    pub location_count: usize,
    pub locations: Vec<ScanLocation>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ScanLocation {
    pub path: String,
    #[serde(rename = "type")]
    pub loc_type: String,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub exists: bool,
    #[serde(default)]
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BatchIngestResult {
    pub status: String,
    #[serde(default)]
    pub imported: u64,
    #[serde(default)]
    pub summarize_queued: u64,
    #[serde(default)]
    pub topics_created: u64,
}

// ---------------------------------------------------------------------------
// Chat Pipeline State
// ---------------------------------------------------------------------------

/// Sub-view within the chat pipeline
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ChatSubView {
    #[default]
    Dashboard,
    Scanner,
    Conversations,
    Topics,
    Search,
}

/// Holds all chat pipeline state
#[derive(Debug, Clone, Default)]
pub struct ChatPipelineState {
    pub sub_view: ChatSubView,
    pub stats: Option<ChatStats>,
    pub conversations: Vec<Conversation>,
    pub topics: Vec<Topic>,
    pub search_query: String,
    pub search_results: Vec<SearchResult>,
    pub locations: Option<ScanLocations>,
    pub selected_conversation: Option<ConversationDetail>,
    pub loading: bool,
    pub error: Option<String>,
    pub last_action_result: Option<String>,
    pub api_available: bool,
    /// Set of expanded message indices in conversation detail view
    pub expanded_messages: std::collections::HashSet<usize>,
    /// Whether to show full conversation (all messages expanded)
    pub show_full_conversation: bool,
}

// ---------------------------------------------------------------------------
// HTTP Client (blocking via ureq, wrapped in async tasks)
// ---------------------------------------------------------------------------

pub struct ChatApiClient;

impl ChatApiClient {
    fn get<T: serde::de::DeserializeOwned>(path: &str) -> Result<T, String> {
        let url = format!("{}{}", CHAT_API_BASE, path);
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(30))
            .build();
        let response = agent
            .get(&url)
            .call()
            .map_err(|e| format!("Request failed: {}", e))?;
        response
            .into_json::<T>()
            .map_err(|e| format!("Parse error: {}", e))
    }

    fn post<T: serde::de::DeserializeOwned>(path: &str) -> Result<T, String> {
        let url = format!("{}{}", CHAT_API_BASE, path);
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(120))
            .build();
        let response = agent
            .post(&url)
            .set("Content-Type", "application/json")
            .send_string("{}")
            .map_err(|e| format!("Request failed: {}", e))?;
        response
            .into_json::<T>()
            .map_err(|e| format!("Parse error: {}", e))
    }

    // Async wrappers (ureq is blocking, so we use spawn_blocking)

    pub async fn fetch_stats() -> Result<ChatStats, String> {
        tokio::task::spawn_blocking(|| Self::get::<ChatStats>("/stats"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_health() -> Result<ChatHealth, String> {
        tokio::task::spawn_blocking(|| Self::get::<ChatHealth>("/health"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_conversations(limit: u32) -> Result<ConversationList, String> {
        let path = format!("/conversations?limit={}", limit);
        tokio::task::spawn_blocking(move || Self::get::<ConversationList>(&path))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_conversation(id: String) -> Result<ConversationDetail, String> {
        tokio::task::spawn_blocking(move || {
            Self::get::<ConversationDetail>(&format!("/conversations/{}", id))
        })
        .await
        .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_topics() -> Result<TopicList, String> {
        tokio::task::spawn_blocking(|| Self::get::<TopicList>("/topics"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn search(query: String, mode: String) -> Result<SearchResults, String> {
        let path = format!(
            "/search?q={}&mode={}&limit=30",
            urlencoding::encode(&query),
            mode
        );
        tokio::task::spawn_blocking(move || Self::get::<SearchResults>(&path))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_locations() -> Result<ScanLocations, String> {
        tokio::task::spawn_blocking(|| Self::get::<ScanLocations>("/scan/locations"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn batch_ingest() -> Result<BatchIngestResult, String> {
        tokio::task::spawn_blocking(|| {
            Self::post::<BatchIngestResult>("/batch-ingest?summarize=true&cluster=true")
        })
        .await
        .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn summarize_pending() -> Result<serde_json::Value, String> {
        tokio::task::spawn_blocking(|| Self::post::<serde_json::Value>("/summarize-pending"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn cluster_topics() -> Result<serde_json::Value, String> {
        tokio::task::spawn_blocking(|| Self::post::<serde_json::Value>("/topics/cluster"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn import_orphaned() -> Result<serde_json::Value, String> {
        tokio::task::spawn_blocking(|| Self::post::<serde_json::Value>("/import-orphaned"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }
}

// Simple URL encoding (avoid adding full url crate dependency)
mod urlencoding {
    pub fn encode(s: &str) -> String {
        let mut encoded = String::new();
        for b in s.bytes() {
            match b {
                b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                    encoded.push(b as char);
                }
                b' ' => encoded.push('+'),
                _ => {
                    encoded.push('%');
                    encoded.push_str(&format!("{:02X}", b));
                }
            }
        }
        encoded
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Format a number with comma separators
pub fn fmt_num(n: u64) -> String {
    if n == 0 {
        return "0".to_string();
    }
    let s = n.to_string();
    let mut result = String::new();
    for (i, c) in s.chars().rev().enumerate() {
        if i > 0 && i % 3 == 0 {
            result.push(',');
        }
        result.push(c);
    }
    result.chars().rev().collect()
}

/// Format bytes to human-readable
pub fn fmt_bytes(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    const GB: u64 = MB * 1024;
    if bytes >= GB {
        format!("{:.1} GB", bytes as f64 / GB as f64)
    } else if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{:.1} KB", bytes as f64 / KB as f64)
    } else {
        format!("{} B", bytes)
    }
}

/// Truncate a string
pub fn truncate(s: &str, max_len: usize) -> String {
    if s.len() <= max_len {
        s.to_string()
    } else {
        format!("{}...", &s[..max_len.min(s.len())])
    }
}
