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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WatcherStatus {
    pub watcher: WatcherInfo,
    pub pipeline: PipelineInfo,
    #[serde(default)]
    pub databases: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WatcherInfo {
    #[serde(default)]
    pub watching: bool,
    #[serde(default)]
    pub known_databases: u64,
    #[serde(default)]
    pub pending_changes: u64,
    #[serde(default)]
    pub cursor_base: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PipelineInfo {
    #[serde(default)]
    pub processing: bool,
    #[serde(default)]
    pub queue_size: u64,
    #[serde(default)]
    pub stats: PipelineStats,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PipelineStats {
    #[serde(default)]
    pub total_conversations_imported: u64,
    #[serde(default)]
    pub total_messages_imported: u64,
    #[serde(default)]
    pub total_chunks_created: u64,
    #[serde(default)]
    pub total_embeddings_generated: u64,
    #[serde(default)]
    pub last_run_at: Option<String>,
    #[serde(default)]
    pub last_run_duration_ms: Option<u64>,
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
    Export,
    Watcher,
}

/// Export format options
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ExportFormat {
    #[default]
    Markdown,
    Json,
    Html,
    Text,
}

impl ExportFormat {
    pub fn as_str(&self) -> &'static str {
        match self {
            ExportFormat::Markdown => "markdown",
            ExportFormat::Json => "json",
            ExportFormat::Html => "html",
            ExportFormat::Text => "text",
        }
    }

    pub fn extension(&self) -> &'static str {
        match self {
            ExportFormat::Markdown => "md",
            ExportFormat::Json => "json",
            ExportFormat::Html => "html",
            ExportFormat::Text => "txt",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            ExportFormat::Markdown => "Markdown",
            ExportFormat::Json => "JSON",
            ExportFormat::Html => "HTML",
            ExportFormat::Text => "Plain Text",
        }
    }

    pub const ALL: [ExportFormat; 4] = [
        ExportFormat::Markdown,
        ExportFormat::Json,
        ExportFormat::Html,
        ExportFormat::Text,
    ];
}

/// Export result
#[derive(Debug, Clone)]
pub struct ExportResult {
    pub conversation_id: String,
    pub format: ExportFormat,
    pub content: String,
    pub filename: String,
}

// ---------------------------------------------------------------------------
// Training Data Export Types
// ---------------------------------------------------------------------------

/// Training data export format
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrainingFormat {
    #[default]
    Openai,
    Alpaca,
    Sharegpt,
}

impl TrainingFormat {
    pub fn as_str(&self) -> &'static str {
        match self {
            TrainingFormat::Openai => "openai",
            TrainingFormat::Alpaca => "alpaca",
            TrainingFormat::Sharegpt => "sharegpt",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            TrainingFormat::Openai => "OpenAI Chat",
            TrainingFormat::Alpaca => "Alpaca",
            TrainingFormat::Sharegpt => "ShareGPT",
        }
    }

    pub const ALL: [TrainingFormat; 3] = [
        TrainingFormat::Openai,
        TrainingFormat::Alpaca,
        TrainingFormat::Sharegpt,
    ];
}

/// Training export filter options
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TrainingFilters {
    #[serde(default)]
    pub min_turns: Option<u32>,
    #[serde(default)]
    pub max_turns: Option<u32>,
    #[serde(default)]
    pub min_response_length: Option<u32>,
    #[serde(default)]
    pub min_quality_score: Option<f64>,
    #[serde(default)]
    pub has_code: bool,
    #[serde(default)]
    pub agentic_only: bool,
    #[serde(default)]
    pub topic_match: Vec<String>,
    #[serde(default)]
    pub models: Vec<String>,
    #[serde(default)]
    pub deduplicate: bool,
}

/// Sanitization options
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct SanitizationOptions {
    #[serde(default = "default_true")]
    pub redact_paths: bool,
    #[serde(default = "default_true")]
    pub redact_api_keys: bool,
    #[serde(default = "default_true")]
    pub redact_emails: bool,
    #[serde(default = "default_true")]
    pub redact_hostnames: bool,
    #[serde(default)]
    pub custom_patterns: Vec<String>,
}

fn default_true() -> bool { true }

/// Augmentation options
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AugmentationOptions {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub strategies: Vec<String>,
    #[serde(default = "default_multiplier")]
    pub multiplier: u32,
    #[serde(default)]
    pub model: Option<String>,
}

fn default_multiplier() -> u32 { 2 }

/// Training export request body
#[derive(Debug, Clone, Default, Serialize)]
pub struct TrainingExportRequest {
    pub format: String,
    pub filters: TrainingFilters,
    pub sanitization: SanitizationOptions,
    pub augmentation: AugmentationOptions,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub val_ratio: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_dir: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub system_prompt: Option<String>,
}

/// Training export stats response
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingStatsResponse {
    pub status: String,
    pub stats: TrainingStats,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingStats {
    #[serde(default)]
    pub total_conversations: u64,
    #[serde(default)]
    pub after_filter: u64,
    #[serde(default)]
    pub after_sanitization: u64,
    #[serde(default)]
    pub estimated_tokens: u64,
    #[serde(default)]
    pub train_examples: u64,
    #[serde(default)]
    pub val_examples: u64,
}

/// Training preview response
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingPreviewResponse {
    pub status: String,
    pub stats: TrainingStats,
    pub dry_run: bool,
}

/// Training sample conversation
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingSampleConversation {
    pub id: String,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub message_count: u64,
    #[serde(default)]
    pub is_agentic: bool,
    #[serde(default)]
    pub models_used: serde_json::Value,
    #[serde(default)]
    pub quality_score: f64,
}

/// Training sample response
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingSampleResponse {
    pub status: String,
    pub count: usize,
    pub samples: Vec<TrainingSampleConversation>,
}

/// Training export result
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingExportResponse {
    pub status: String,
    pub output_files: TrainingOutputFiles,
    pub stats: TrainingStats,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingOutputFiles {
    #[serde(default)]
    pub train: Option<String>,
    #[serde(default)]
    pub val: Option<String>,
}

/// Training options schema (from /training/options)
#[derive(Debug, Clone, Default, Deserialize)]
pub struct TrainingOptionsResponse {
    pub formats: Vec<String>,
    #[serde(default)]
    pub filters: serde_json::Value,
    #[serde(default)]
    pub sanitization: serde_json::Value,
    #[serde(default)]
    pub augmentation: serde_json::Value,
    #[serde(default)]
    pub defaults: serde_json::Value,
}

/// Export mode: single conversation vs bulk training
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ExportMode {
    #[default]
    Single,
    Training,
}

/// Training export state (held in ChatPipelineState)
#[derive(Debug, Clone, Default)]
pub struct TrainingExportState {
    /// Current export mode (single vs training)
    pub mode: ExportMode,
    pub format: TrainingFormat,
    pub filters: TrainingFilters,
    pub sanitization: SanitizationOptions,
    pub augmentation: AugmentationOptions,
    pub val_ratio: f64,
    pub system_prompt: String,
    pub preview_stats: Option<TrainingStats>,
    pub samples: Vec<TrainingSampleConversation>,
    pub last_export: Option<TrainingExportResponse>,
    pub exporting: bool,
    pub loading_preview: bool,
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
    /// Selected export format
    pub export_format: ExportFormat,
    /// Conversations selected for export (IDs)
    pub export_selected: std::collections::HashSet<String>,
    /// Last export result (for preview/download)
    pub export_result: Option<ExportResult>,
    /// Export in progress
    pub exporting: bool,
    /// Real-time watcher status
    pub watcher_status: Option<WatcherStatus>,
    /// Training data export state
    pub training_export: TrainingExportState,
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

    pub async fn export_conversation(id: String, format: ExportFormat) -> Result<ExportResult, String> {
        tokio::task::spawn_blocking(move || {
            let url = format!("{}/export/{}?format={}", CHAT_API_BASE, id, format.as_str());
            let agent = ureq::AgentBuilder::new()
                .timeout(std::time::Duration::from_secs(60))
                .build();
            let response = agent
                .get(&url)
                .call()
                .map_err(|e| format!("Export request failed: {}", e))?;
            let content = response
                .into_string()
                .map_err(|e| format!("Failed to read export: {}", e))?;
            let filename = format!("conversation_{}.{}", &id[..8.min(id.len())], format.extension());
            Ok(ExportResult {
                conversation_id: id,
                format,
                content,
                filename,
            })
        })
        .await
        .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn fetch_watcher_status() -> Result<WatcherStatus, String> {
        tokio::task::spawn_blocking(|| Self::get::<WatcherStatus>("/watcher"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn trigger_watcher_scan() -> Result<serde_json::Value, String> {
        tokio::task::spawn_blocking(|| Self::post::<serde_json::Value>("/watcher/scan"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    pub async fn trigger_pipeline_process_all() -> Result<serde_json::Value, String> {
        tokio::task::spawn_blocking(|| Self::post::<serde_json::Value>("/pipeline/process-all"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    // -------------------------------------------------------------------------
    // Training Data Export API
    // -------------------------------------------------------------------------

    fn post_json<T: serde::de::DeserializeOwned, B: Serialize>(path: &str, body: &B) -> Result<T, String> {
        let url = format!("{}{}", CHAT_API_BASE, path);
        let body_json = serde_json::to_string(body).map_err(|e| format!("Serialize error: {}", e))?;
        let agent = ureq::AgentBuilder::new()
            .timeout(std::time::Duration::from_secs(300)) // Long timeout for exports
            .build();
        let response = agent
            .post(&url)
            .set("Content-Type", "application/json")
            .send_string(&body_json)
            .map_err(|e| format!("Request failed: {}", e))?;
        response
            .into_json::<T>()
            .map_err(|e| format!("Parse error: {}", e))
    }

    /// Fetch training export options schema
    pub async fn fetch_training_options() -> Result<TrainingOptionsResponse, String> {
        tokio::task::spawn_blocking(|| Self::get::<TrainingOptionsResponse>("/training/options"))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    /// Fetch training export stats with optional filters
    pub async fn fetch_training_stats(filters: TrainingFilters) -> Result<TrainingStatsResponse, String> {
        let mut params = vec![];
        if let Some(min) = filters.min_turns {
            params.push(format!("min_turns={}", min));
        }
        if let Some(max) = filters.max_turns {
            params.push(format!("max_turns={}", max));
        }
        if let Some(score) = filters.min_quality_score {
            params.push(format!("min_quality={}", score));
        }
        if filters.has_code {
            params.push("has_code=true".to_string());
        }
        if filters.agentic_only {
            params.push("agentic_only=true".to_string());
        }
        if !filters.topic_match.is_empty() {
            params.push(format!("topics={}", filters.topic_match.join(",")));
        }

        let path = if params.is_empty() {
            "/training/stats".to_string()
        } else {
            format!("/training/stats?{}", params.join("&"))
        };

        tokio::task::spawn_blocking(move || Self::get::<TrainingStatsResponse>(&path))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    /// Fetch sample conversations for preview
    pub async fn fetch_training_sample(n: u32, filters: TrainingFilters) -> Result<TrainingSampleResponse, String> {
        let mut params = vec![format!("n={}", n)];
        if let Some(min) = filters.min_turns {
            params.push(format!("min_turns={}", min));
        }
        if let Some(score) = filters.min_quality_score {
            params.push(format!("min_quality={}", score));
        }
        if filters.has_code {
            params.push("has_code=true".to_string());
        }
        if filters.agentic_only {
            params.push("agentic_only=true".to_string());
        }

        let path = format!("/training/sample?{}", params.join("&"));

        tokio::task::spawn_blocking(move || Self::get::<TrainingSampleResponse>(&path))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    /// Preview training export (dry run, no files written)
    pub async fn training_preview(request: TrainingExportRequest) -> Result<TrainingPreviewResponse, String> {
        tokio::task::spawn_blocking(move || Self::post_json::<TrainingPreviewResponse, _>("/training/preview", &request))
            .await
            .map_err(|e| format!("Task failed: {}", e))?
    }

    /// Run full training data export
    pub async fn training_export(request: TrainingExportRequest) -> Result<TrainingExportResponse, String> {
        tokio::task::spawn_blocking(move || Self::post_json::<TrainingExportResponse, _>("/training/export", &request))
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
