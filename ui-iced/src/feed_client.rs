//! Activity Feed Client for Synapsix Integration
//!
//! Connects to the ActivityFeed service via WebSocket for real-time updates
//! and HTTP for queries and posting agent updates.

use futures_util::StreamExt;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};
use url::Url;

/// Default Synapsix API base URLs
pub const DEFAULT_API_URL: &str = "http://localhost:4001/api/feed";
pub const DEFAULT_WS_URL: &str = "ws://localhost:4001/ws/feed";

// ============================================================================
// Data Types (matching Synapsix ActivityFeed.Entry)
// ============================================================================

/// Source of a feed entry
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum FeedSource {
    Git,
    FileChange,
    Agent,
    TaskQueue,
    Nesy,
    System,
    #[serde(other)]
    Unknown,
}

impl FeedSource {
    pub fn icon(&self) -> &'static str {
        match self {
            FeedSource::Git => "🔀",
            FeedSource::FileChange => "📁",
            FeedSource::Agent => "🤖",
            FeedSource::TaskQueue => "📋",
            FeedSource::Nesy => "🧮",
            FeedSource::System => "⚙️",
            FeedSource::Unknown => "❓",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            FeedSource::Git => "git",
            FeedSource::FileChange => "file",
            FeedSource::Agent => "agent",
            FeedSource::TaskQueue => "task",
            FeedSource::Nesy => "nesy",
            FeedSource::System => "system",
            FeedSource::Unknown => "unknown",
        }
    }
}

/// A hyperlink in a feed entry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedLink {
    pub label: String,
    pub url: String,
    #[serde(rename = "type")]
    pub link_type: String,
}

/// A single activity feed entry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedEntry {
    pub id: String,
    pub timestamp: String,
    pub source: FeedSource,
    pub event_type: String,
    #[serde(default)]
    pub agent_id: Option<String>,
    pub title: String,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub links: Vec<FeedLink>,
    #[serde(default)]
    pub metadata: serde_json::Value,
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default)]
    pub repo: Option<String>,
    #[serde(default)]
    pub tags: Vec<String>,
}

/// Feed statistics
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct FeedStats {
    #[serde(default)]
    pub total_entries: usize,
    #[serde(default)]
    pub by_source: serde_json::Value,
    #[serde(default)]
    pub by_event_type: serde_json::Value,
}

// ============================================================================
// WebSocket Protocol (matching Synapsix ActivityFeed.WebSocket)
// ============================================================================

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsServerMessage {
    Connected {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Init {
        entries: Vec<FeedEntry>,
        stats: FeedStats,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    EntryAdded {
        entry: FeedEntry,
    },
    Heartbeat {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Pong {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Error {
        message: String,
    },
}

/// Events emitted by the feed WebSocket client
#[derive(Debug, Clone)]
pub enum FeedEvent {
    Connected,
    Disconnected,
    InitialState {
        entries: Vec<FeedEntry>,
        stats: FeedStats,
    },
    EntryAdded(FeedEntry),
    Error(String),
}

// ============================================================================
// WebSocket Connection
// ============================================================================

/// Spawn a WebSocket connection to the Activity Feed
pub async fn spawn_feed_websocket() -> Result<mpsc::UnboundedReceiver<FeedEvent>, String> {
    let (tx, rx) = mpsc::unbounded_channel();
    let url = Url::parse(DEFAULT_WS_URL).map_err(|e| e.to_string())?;

    tokio::spawn(async move {
        loop {
            match feed_connect_and_handle(&url, tx.clone()).await {
                Ok(()) => {
                    let _ = tx.send(FeedEvent::Disconnected);
                    break;
                }
                Err(e) => {
                    log::error!("[feed_ws] WebSocket error: {}", e);
                    let _ = tx.send(FeedEvent::Error(e.clone()));
                    let _ = tx.send(FeedEvent::Disconnected);
                    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                }
            }
        }
    });

    Ok(rx)
}

async fn feed_connect_and_handle(
    url: &Url,
    tx: mpsc::UnboundedSender<FeedEvent>,
) -> Result<(), String> {
    let (ws_stream, _) = connect_async(url.as_str())
        .await
        .map_err(|e| format!("Feed WebSocket connection failed: {}", e))?;

    let _ = tx.send(FeedEvent::Connected);

    let (_write, mut read) = ws_stream.split();

    while let Some(msg_result) = read.next().await {
        match msg_result {
            Ok(WsMessage::Text(text)) => match serde_json::from_str::<WsServerMessage>(&text) {
                Ok(server_msg) => match server_msg {
                    WsServerMessage::Connected { .. } => {
                        log::info!("[feed_ws] Connected to Activity Feed");
                    }
                    WsServerMessage::Init { entries, stats, .. } => {
                        log::info!("[feed_ws] Received {} initial entries", entries.len());
                        let _ = tx.send(FeedEvent::InitialState { entries, stats });
                    }
                    WsServerMessage::EntryAdded { entry } => {
                        let _ = tx.send(FeedEvent::EntryAdded(entry));
                    }
                    WsServerMessage::Heartbeat { .. } | WsServerMessage::Pong { .. } => {}
                    WsServerMessage::Error { message } => {
                        let _ = tx.send(FeedEvent::Error(message));
                    }
                },
                Err(e) => {
                    log::warn!("[feed_ws] Failed to parse: {}", e);
                }
            },
            Ok(WsMessage::Close(_)) => break,
            Err(e) => return Err(format!("Feed WS read error: {}", e)),
            _ => {}
        }
    }

    Ok(())
}

// ============================================================================
// HTTP Client
// ============================================================================

/// HTTP client for querying and posting to the Activity Feed
#[derive(Debug, Clone)]
pub struct FeedHttpClient {
    client: reqwest::Client,
    base_url: String,
}

impl FeedHttpClient {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
            base_url: DEFAULT_API_URL.to_string(),
        }
    }

    /// Post an agent NL update to the feed
    pub async fn post_update(
        &self,
        agent_id: &str,
        summary: &str,
        body: Option<&str>,
        project: Option<&str>,
        links: Vec<FeedLink>,
    ) -> Result<FeedEntry, String> {
        let payload = serde_json::json!({
            "agent_id": agent_id,
            "summary": summary,
            "body": body,
            "project": project,
            "links": links,
        });

        let resp = self
            .client
            .post(format!("{}/update", self.base_url))
            .json(&payload)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        resp.json::<FeedEntry>().await.map_err(|e| e.to_string())
    }

    /// Get feed summary text (for context injection)
    pub async fn get_summary(&self, limit: Option<usize>) -> Result<String, String> {
        let mut url = format!("{}/summary", self.base_url);
        if let Some(l) = limit {
            url = format!("{}?limit={}", url, l);
        }

        #[derive(Deserialize)]
        struct SummaryResponse {
            summary: String,
        }

        let resp = self
            .client
            .get(&url)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let data = resp
            .json::<SummaryResponse>()
            .await
            .map_err(|e| e.to_string())?;

        Ok(data.summary)
    }

    /// Get feed statistics
    pub async fn get_stats(&self) -> Result<FeedStats, String> {
        let resp = self
            .client
            .get(format!("{}/stats", self.base_url))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        resp.json::<FeedStats>().await.map_err(|e| e.to_string())
    }

    /// Trigger a git poll
    pub async fn trigger_git_poll(&self) -> Result<(), String> {
        self.client
            .post(format!("{}/git/poll", self.base_url))
            .send()
            .await
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}

impl Default for FeedHttpClient {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_feed_source_icon() {
        assert_eq!(FeedSource::Git.icon(), "🔀");
        assert_eq!(FeedSource::FileChange.icon(), "📁");
        assert_eq!(FeedSource::Agent.icon(), "🤖");
        assert_eq!(FeedSource::TaskQueue.icon(), "📋");
        assert_eq!(FeedSource::Nesy.icon(), "🧮");
        assert_eq!(FeedSource::System.icon(), "⚙️");
        assert_eq!(FeedSource::Unknown.icon(), "❓");
    }

    #[test]
    fn test_feed_source_label() {
        assert_eq!(FeedSource::Git.label(), "git");
        assert_eq!(FeedSource::FileChange.label(), "file");
        assert_eq!(FeedSource::Agent.label(), "agent");
        assert_eq!(FeedSource::TaskQueue.label(), "task");
        assert_eq!(FeedSource::Nesy.label(), "nesy");
        assert_eq!(FeedSource::System.label(), "system");
        assert_eq!(FeedSource::Unknown.label(), "unknown");
    }

    #[test]
    fn test_feed_source_serialization() {
        let json = serde_json::to_string(&FeedSource::Git).unwrap();
        assert_eq!(json, "\"git\"");

        let s: FeedSource = serde_json::from_str("\"agent\"").unwrap();
        assert_eq!(s, FeedSource::Agent);

        // Unknown values should deserialize to Unknown
        let s: FeedSource = serde_json::from_str("\"something_else\"").unwrap();
        assert_eq!(s, FeedSource::Unknown);
    }

    #[test]
    fn test_feed_link_serialization() {
        let link = FeedLink {
            label: "View Commit".to_string(),
            url: "https://github.com/user/repo/commit/abc123".to_string(),
            link_type: "commit".to_string(),
        };

        let json = serde_json::to_string(&link);
        assert!(json.is_ok());

        let restored: Result<FeedLink, _> = serde_json::from_str(&json.unwrap());
        assert!(restored.is_ok());

        let restored = restored.unwrap();
        assert_eq!(restored.label, "View Commit");
    }

    #[test]
    fn test_feed_entry_serialization() {
        let entry = FeedEntry {
            id: "entry-123".to_string(),
            timestamp: "2026-04-07T12:00:00Z".to_string(),
            source: FeedSource::Agent,
            event_type: "status_update".to_string(),
            agent_id: Some("agent-456".to_string()),
            title: "Agent started working".to_string(),
            body: Some("Details here".to_string()),
            links: vec![],
            metadata: serde_json::json!({"key": "value"}),
            project: Some("continuum-studio".to_string()),
            repo: None,
            tags: vec!["test".to_string()],
        };

        let json = serde_json::to_string(&entry);
        assert!(json.is_ok());

        let restored: Result<FeedEntry, _> = serde_json::from_str(&json.unwrap());
        assert!(restored.is_ok());

        let restored = restored.unwrap();
        assert_eq!(restored.id, "entry-123");
        assert_eq!(restored.source, FeedSource::Agent);
    }

    #[test]
    fn test_feed_stats_default() {
        let stats = FeedStats::default();
        assert_eq!(stats.total_entries, 0);
    }

    #[test]
    fn test_feed_http_client_default() {
        let client = FeedHttpClient::default();
        assert!(!client.base_url.is_empty());
    }

    #[test]
    fn test_default_urls() {
        assert_eq!(DEFAULT_API_URL, "http://localhost:4001/api/feed");
        assert_eq!(DEFAULT_WS_URL, "ws://localhost:4001/ws/feed");
    }
}
