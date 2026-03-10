//! CLI Agents HTTP Client for Synapsix Integration
//!
//! Handles HTTP API communication and WebSocket subscriptions for 
//! Synapsix CLIBackend/CLIBatch modules.

use futures_util::{SinkExt, StreamExt};
use reqwest::Client;
use serde::{Deserialize, Serialize};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};

use crate::cli_agents::{AgentMode, CLIAgent, CLIAgentEvent, CLIAgentStatus, CLIEventType};

/// Default Synapsix CLI agents API base URL
pub const DEFAULT_API_URL: &str = "http://localhost:4001/api/cli-agents";
/// Default WebSocket URL for real-time events
pub const DEFAULT_WS_URL: &str = "ws://localhost:4001/ws/cli-agents";

// ============================================================================
// Request/Response Types
// ============================================================================

/// Request to spawn a single CLI agent
#[derive(Debug, Clone, Serialize)]
pub struct SpawnAgentRequest {
    pub prompt: String,
    pub workspace: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub mode: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub force: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub approve_mcps: Option<bool>,
}

/// Request to spawn a batch of CLI agents
#[derive(Debug, Clone, Serialize)]
pub struct SpawnBatchRequest {
    pub prompt: String,
    pub workspaces: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_concurrent: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop_on_failure: Option<bool>,
}

/// Response from spawn endpoints
#[derive(Debug, Clone, Deserialize)]
pub struct SpawnResponse {
    pub agent_id: String,
    #[serde(default)]
    pub batch_id: Option<String>,
}

/// Response from list endpoint
#[derive(Debug, Clone, Deserialize)]
pub struct ListAgentsResponse {
    pub agents: Vec<AgentSummary>,
}

/// Summary of an agent (for list view)
#[derive(Debug, Clone, Deserialize)]
pub struct AgentSummary {
    pub id: String,
    pub workspace: String,
    pub status: String,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    #[serde(default)]
    pub event_count: usize,
}

/// Full agent details (from get endpoint)
#[derive(Debug, Clone, Deserialize)]
pub struct AgentDetails {
    pub id: String,
    pub workspace: String,
    pub prompt: String,
    pub status: String,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    pub started_at: Option<String>,
    pub completed_at: Option<String>,
    #[serde(default)]
    pub events: Vec<EventJson>,
    #[serde(default)]
    pub result: Option<String>,
    #[serde(default)]
    pub error: Option<String>,
}

/// Event from API (JSON format)
#[derive(Debug, Clone, Deserialize)]
pub struct EventJson {
    #[serde(rename = "type")]
    pub event_type: String,
    pub timestamp: String,
    #[serde(default)]
    pub content: Option<String>,
    #[serde(default)]
    pub tool_name: Option<String>,
    #[serde(default)]
    pub tool_args: Option<serde_json::Value>,
}

// ============================================================================
// HTTP Client
// ============================================================================

/// HTTP client for CLI agents API
#[derive(Debug, Clone)]
pub struct CLIAgentsHttpClient {
    client: Client,
    base_url: String,
}

impl CLIAgentsHttpClient {
    pub fn new() -> Self {
        Self {
            client: Client::new(),
            base_url: DEFAULT_API_URL.to_string(),
        }
    }

    pub fn with_base_url(base_url: &str) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.to_string(),
        }
    }

    /// Spawn a single CLI agent
    pub async fn spawn_agent(
        &self,
        prompt: &str,
        workspace: &str,
        mode: Option<AgentMode>,
        force: Option<bool>,
        approve_mcps: Option<bool>,
    ) -> Result<SpawnResponse, String> {
        let req = SpawnAgentRequest {
            prompt: prompt.to_string(),
            workspace: workspace.to_string(),
            mode: mode.map(|m| m.label().to_string()),
            force,
            approve_mcps,
        };

        let resp = self.client
            .post(format!("{}/spawn", self.base_url))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        resp.json::<SpawnResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))
    }

    /// Spawn a batch of CLI agents
    pub async fn spawn_batch(
        &self,
        prompt: &str,
        workspaces: Vec<String>,
        max_concurrent: Option<u32>,
        stop_on_failure: Option<bool>,
    ) -> Result<SpawnResponse, String> {
        let req = SpawnBatchRequest {
            prompt: prompt.to_string(),
            workspaces,
            max_concurrent,
            stop_on_failure,
        };

        let resp = self.client
            .post(format!("{}/batch", self.base_url))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        resp.json::<SpawnResponse>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))
    }

    /// List all agents
    pub async fn list_agents(&self) -> Result<Vec<AgentSummary>, String> {
        let resp = self.client
            .get(&self.base_url)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        let list: ListAgentsResponse = resp.json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;
        
        Ok(list.agents)
    }

    /// Get details for a specific agent
    pub async fn get_agent(&self, agent_id: &str) -> Result<AgentDetails, String> {
        let resp = self.client
            .get(format!("{}/{}", self.base_url, agent_id))
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        resp.json::<AgentDetails>()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))
    }

    /// Stop a running agent
    pub async fn stop_agent(&self, agent_id: &str) -> Result<(), String> {
        let resp = self.client
            .post(format!("{}/{}/stop", self.base_url, agent_id))
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        Ok(())
    }
}

impl Default for CLIAgentsHttpClient {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================================
// Conversion Helpers
// ============================================================================

impl AgentDetails {
    /// Convert API response to domain type
    pub fn into_cli_agent(self) -> CLIAgent {
        let status = match self.status.as_str() {
            "pending" => CLIAgentStatus::Pending,
            "running" => CLIAgentStatus::Running,
            "completed" => CLIAgentStatus::Completed,
            "failed" => CLIAgentStatus::Failed,
            "timeout" => CLIAgentStatus::Timeout,
            _ => CLIAgentStatus::Pending,
        };

        let mode = match self.mode.as_deref() {
            Some("agent") => AgentMode::Agent,
            Some("plan") => AgentMode::Plan,
            Some("ask") => AgentMode::Ask,
            _ => AgentMode::Agent,
        };

        let events: Vec<CLIAgentEvent> = self.events
            .into_iter()
            .filter_map(|e| e.into_cli_agent_event())
            .collect();

        let started_at = self.started_at
            .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
            .map(|dt| dt.with_timezone(&chrono::Utc));

        let completed_at = self.completed_at
            .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
            .map(|dt| dt.with_timezone(&chrono::Utc));

        CLIAgent {
            id: self.id,
            workspace: self.workspace,
            prompt: self.prompt,
            status,
            mode,
            model: self.model,
            started_at,
            completed_at,
            events,
            result: self.result,
            error: self.error,
        }
    }
}

impl EventJson {
    /// Convert API event to domain type
    pub fn into_cli_agent_event(self) -> Option<CLIAgentEvent> {
        let event_type = match self.event_type.as_str() {
            "init" => CLIEventType::Init,
            "thinking" => CLIEventType::Thinking,
            "tool_started" => CLIEventType::ToolStarted,
            "tool_completed" => CLIEventType::ToolCompleted,
            "response" => CLIEventType::Response,
            "result" => CLIEventType::Result,
            "error" => CLIEventType::Error,
            _ => return None,
        };

        let timestamp = chrono::DateTime::parse_from_rfc3339(&self.timestamp)
            .ok()?
            .with_timezone(&chrono::Utc);

        Some(CLIAgentEvent {
            event_type,
            timestamp,
            content: self.content,
            tool_name: self.tool_name,
            tool_args: self.tool_args,
        })
    }
}

// ============================================================================
// WebSocket Event Types (for real-time updates)
// ============================================================================

/// Events received over WebSocket
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum CLIAgentWsEvent {
    /// Agent started
    Started {
        agent_id: String,
        workspace: String,
        model: Option<String>,
    },
    /// Agent emitted an event
    Event {
        agent_id: String,
        event: EventJson,
    },
    /// Agent completed
    Completed {
        agent_id: String,
        result: Option<String>,
        error: Option<String>,
    },
    /// Connection established
    Connected,
    /// Heartbeat/ping
    Ping,
}

// ============================================================================
// WebSocket Connection
// ============================================================================

/// Spawn a WebSocket connection that yields CLIAgentWsEvent messages
/// 
/// This is designed to be used with iced's Subscription system.
/// Returns a channel receiver that yields events as they arrive.
pub async fn spawn_cli_agents_websocket(
    ws_url: Option<String>,
) -> mpsc::Receiver<CLIAgentWsEvent> {
    let (tx, rx) = mpsc::channel(100);
    
    tokio::spawn(async move {
        let url = ws_url.unwrap_or_else(|| DEFAULT_WS_URL.to_string());
        cli_agents_websocket_loop(url, tx).await;
    });
    
    rx
}

/// Internal WebSocket loop with reconnection logic
async fn cli_agents_websocket_loop(url: String, tx: mpsc::Sender<CLIAgentWsEvent>) {
    loop {
        match connect_to_cli_agents_ws(&url, &tx).await {
            Ok(()) => {
                log::info!("CLI agents WebSocket disconnected cleanly");
            }
            Err(e) => {
                log::warn!("CLI agents WebSocket error: {}", e);
            }
        }
        
        // Wait before reconnecting
        tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        log::info!("Attempting to reconnect to CLI agents WebSocket...");
    }
}

/// Connect to WebSocket and process messages
async fn connect_to_cli_agents_ws(
    url: &str,
    tx: &mpsc::Sender<CLIAgentWsEvent>,
) -> Result<(), String> {
    let (ws_stream, _response) = connect_async(url)
        .await
        .map_err(|e| format!("WebSocket connection failed: {}", e))?;
    
    log::info!("CLI agents WebSocket connected");
    
    // Send connected event
    let _ = tx.send(CLIAgentWsEvent::Connected).await;
    
    let (mut write, mut read) = ws_stream.split();
    
    // Spawn ping task
    let ping_tx = tx.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(tokio::time::Duration::from_secs(30)).await;
            let _ = ping_tx.send(CLIAgentWsEvent::Ping).await;
        }
    });
    
    while let Some(msg) = read.next().await {
        match msg {
            Ok(WsMessage::Text(text)) => {
                match serde_json::from_str::<CLIAgentWsEvent>(&text) {
                    Ok(event) => {
                        if tx.send(event).await.is_err() {
                            log::warn!("CLI agents event receiver dropped");
                            break;
                        }
                    }
                    Err(e) => {
                        log::debug!("Failed to parse WebSocket message: {} - {}", e, text);
                    }
                }
            }
            Ok(WsMessage::Ping(data)) => {
                if let Err(e) = write.send(WsMessage::Pong(data)).await {
                    log::warn!("Failed to send pong: {}", e);
                    break;
                }
            }
            Ok(WsMessage::Close(_)) => {
                log::info!("WebSocket closed by server");
                break;
            }
            Err(e) => {
                return Err(format!("WebSocket error: {}", e));
            }
            _ => {}
        }
    }
    
    Ok(())
}
