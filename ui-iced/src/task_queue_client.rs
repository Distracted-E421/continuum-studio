//! Task Queue Client for Synapsix Integration
//!
//! Handles WebSocket connection for real-time updates and HTTP for mutations.

use reqwest::Client;
use serde::{Deserialize, Deserializer, Serialize};
use tokio::sync::mpsc;
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};
use futures_util::{SinkExt, StreamExt};
use url::Url;

/// Deserialize a Vec<T> that might be null in JSON to an empty Vec
fn deserialize_null_to_vec<'de, D, T>(deserializer: D) -> Result<Vec<T>, D::Error>
where
    D: Deserializer<'de>,
    T: Deserialize<'de>,
{
    let opt = Option::<Vec<T>>::deserialize(deserializer)?;
    Ok(opt.unwrap_or_default())
}

/// Default Synapsix API base URL
pub const DEFAULT_API_URL: &str = "http://localhost:4001/api/tasks";
pub const DEFAULT_WS_URL: &str = "ws://localhost:4001/ws/tasks";

// ============================================================================
// Data Types (matching Synapsix API)
// ============================================================================

/// Task priority levels
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Priority {
    Critical,
    High,
    #[default]
    Medium,
    Low,
    Backlog,
}

impl Priority {
    pub fn emoji(&self) -> &'static str {
        match self {
            Priority::Critical => "🔴",
            Priority::High => "🟠",
            Priority::Medium => "🟡",
            Priority::Low => "🟢",
            Priority::Backlog => "⚪",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            Priority::Critical => "critical",
            Priority::High => "high",
            Priority::Medium => "medium",
            Priority::Low => "low",
            Priority::Backlog => "backlog",
        }
    }

    pub fn all() -> &'static [Priority] {
        &[
            Priority::Critical,
            Priority::High,
            Priority::Medium,
            Priority::Low,
            Priority::Backlog,
        ]
    }
}

impl std::fmt::Display for Priority {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} {}", self.emoji(), self.label())
    }
}

/// Task status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum TaskStatus {
    #[default]
    Pending,
    Claimed,
    InProgress,
    Completed,
    Cancelled,
}

impl TaskStatus {
    pub fn label(&self) -> &'static str {
        match self {
            TaskStatus::Pending => "pending",
            TaskStatus::Claimed => "claimed",
            TaskStatus::InProgress => "in_progress",
            TaskStatus::Completed => "completed",
            TaskStatus::Cancelled => "cancelled",
        }
    }

    pub fn is_active(&self) -> bool {
        matches!(self, TaskStatus::Pending | TaskStatus::Claimed | TaskStatus::InProgress)
    }
}

/// Who created a task
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Creator {
    #[default]
    User,
    Agent,
    /// Tasks created via CLI tools (synapsix-task, etc.)
    Cli,
    /// Catch-all for unknown creator types
    #[serde(other)]
    Unknown,
}

impl Creator {
    pub fn emoji(&self) -> &'static str {
        match self {
            Creator::User => "👤",
            Creator::Agent | Creator::Cli => "🤖",
            Creator::Unknown => "❓",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            Creator::User => "user",
            Creator::Agent => "agent",
            Creator::Cli => "cli",
            Creator::Unknown => "unknown",
        }
    }
}

/// A task from the queue
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Task {
    pub id: String,
    pub content: String,
    pub priority: Priority,
    pub status: TaskStatus,
    #[serde(default)]
    pub assigned_to: Option<String>,
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default, deserialize_with = "deserialize_null_to_vec")]
    pub tags: Vec<String>,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub parent_id: Option<String>,
    /// Who created this task (user or agent)
    #[serde(default)]
    pub created_by: Creator,
    /// If created by agent, the agent's identifier
    #[serde(default)]
    pub agent_id: Option<String>,
    /// Session identifier for tracking agent sessions
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub estimated_effort: Option<String>,
    #[serde(default, deserialize_with = "deserialize_null_to_vec")]
    pub blocked_by: Vec<String>,
    #[serde(default)]
    pub created_at: Option<String>,
    #[serde(default)]
    pub updated_at: Option<String>,
    #[serde(default)]
    pub claimed_at: Option<String>,
    #[serde(default)]
    pub completed_at: Option<String>,
    #[serde(default)]
    pub result: Option<String>,
}

impl Task {
    /// Get short ID (first 8 characters)
    pub fn short_id(&self) -> &str {
        if self.id.len() >= 8 {
            &self.id[..8]
        } else {
            &self.id
        }
    }

    /// Get creator display string
    pub fn creator_display(&self) -> String {
        match self.created_by {
            Creator::User => "👤 user".to_string(),
            Creator::Agent | Creator::Cli => {
                if let Some(agent) = &self.agent_id {
                    format!("🤖 {}", agent)
                } else if self.created_by == Creator::Cli {
                    "🤖 cli".to_string()
                } else {
                    "🤖 agent".to_string()
                }
            }
            Creator::Unknown => "❓ unknown".to_string(),
        }
    }

    /// Check if this task was created by an agent (or CLI tool)
    pub fn is_agent_created(&self) -> bool {
        matches!(self.created_by, Creator::Agent | Creator::Cli)
    }
}

/// Queue statistics
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct QueueStats {
    #[serde(default)]
    pub total: usize,
    #[serde(default)]
    pub pending: usize,
    #[serde(default)]
    pub in_progress: usize,
    #[serde(default)]
    pub completed: usize,
    #[serde(default)]
    pub cancelled: usize,
}

// ============================================================================
// WebSocket Messages
// ============================================================================

/// Messages from the WebSocket server
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum WsServerMessage {
    Connected {
        agent_id: String,
        timestamp: String,
    },
    Init {
        tasks: Vec<Task>,
        stats: QueueStats,
        current_task: Option<Task>,
        timestamp: String,
    },
    TaskAdded {
        task: Task,
    },
    TaskUpdated {
        task: Task,
        #[serde(default)]
        changes: serde_json::Value,
    },
    TaskStarted {
        task: Task,
    },
    TaskCompleted {
        task: Task,
    },
    TaskCancelled {
        task: Task,
    },
    TaskClaimed {
        task: Task,
        agent_id: String,
    },
    TaskReleased {
        task: Task,
    },
    TaskRemoved {
        task_id: String,
    },
    Heartbeat {
        timestamp: String,
    },
    Pong {
        timestamp: String,
    },
    Error {
        message: String,
    },
}

/// Messages to send to the WebSocket server
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum WsClientMessage {
    SetAgent { agent_id: String },
    Subscribe { events: Vec<String> },
    Unsubscribe { events: Vec<String> },
    Filter { project: String },
    Refresh,
    Ping,
}

// ============================================================================
// Client Events (for UI)
// ============================================================================

/// Events emitted by the task queue client
#[derive(Debug, Clone)]
pub enum TaskQueueEvent {
    Connected,
    Disconnected,
    InitialState {
        tasks: Vec<Task>,
        stats: QueueStats,
        current_task: Option<Task>,
    },
    TaskAdded(Task),
    TaskUpdated(Task),
    TaskStarted(Task),
    TaskCompleted(Task),
    TaskCancelled(Task),
    TaskClaimed { task: Task, agent_id: String },
    TaskReleased(Task),
    TaskRemoved(String),
    Error(String),
}

// ============================================================================
// HTTP Client for Mutations
// ============================================================================

/// HTTP client for task queue API
#[derive(Clone)]
pub struct TaskQueueHttpClient {
    client: Client,
    base_url: String,
}

impl TaskQueueHttpClient {
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

    /// Create a new task
    pub async fn add_task(&self, content: &str, priority: Priority, project: Option<&str>) -> Result<Task, String> {
        self.add_task_with_creator(content, priority, project, Creator::User, None, None).await
    }

    /// Create a new task with full creator information
    pub async fn add_task_with_creator(
        &self,
        content: &str,
        priority: Priority,
        project: Option<&str>,
        created_by: Creator,
        agent_id: Option<&str>,
        session_id: Option<&str>,
    ) -> Result<Task, String> {
        let mut body = serde_json::json!({
            "content": content,
            "priority": priority.label(),
            "created_by": created_by.label(),
        });
        
        if let Some(p) = project {
            body["project"] = serde_json::json!(p);
        }
        if let Some(aid) = agent_id {
            body["agent_id"] = serde_json::json!(aid);
        }
        if let Some(sid) = session_id {
            body["session_id"] = serde_json::json!(sid);
        }

        let resp = self.client
            .post(&self.base_url)
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Unknown error").to_string())
        }
    }

    /// Update task priority
    pub async fn update_priority(&self, task_id: &str, priority: Priority) -> Result<Task, String> {
        let body = serde_json::json!({
            "priority": priority.label(),
        });

        let resp = self.client
            .patch(format!("{}/{}", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Update failed").to_string())
        }
    }

    /// Start a task
    pub async fn start_task(&self, task_id: &str, agent_id: &str) -> Result<Task, String> {
        let body = serde_json::json!({
            "agent_id": agent_id,
        });

        let resp = self.client
            .post(format!("{}/{}/start", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Start failed").to_string())
        }
    }

    /// Complete a task
    pub async fn complete_task(&self, task_id: &str, result: Option<&str>) -> Result<Task, String> {
        let body = if let Some(r) = result {
            serde_json::json!({ "result": r })
        } else {
            serde_json::json!({})
        };

        let resp = self.client
            .post(format!("{}/{}/complete", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Complete failed").to_string())
        }
    }

    /// Cancel a task
    pub async fn cancel_task(&self, task_id: &str, reason: Option<&str>) -> Result<Task, String> {
        let body = if let Some(r) = reason {
            serde_json::json!({ "reason": r })
        } else {
            serde_json::json!({})
        };

        let resp = self.client
            .post(format!("{}/{}/cancel", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Cancel failed").to_string())
        }
    }

    /// Delete a task
    pub async fn delete_task(&self, task_id: &str) -> Result<(), String> {
        let resp = self.client
            .delete(format!("{}/{}", self.base_url, task_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            Ok(())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Delete failed").to_string())
        }
    }

    /// Get subtasks for a task
    pub async fn get_subtasks(&self, task_id: &str) -> Result<Vec<Task>, String> {
        let resp = self.client
            .get(format!("{}/{}/subtasks", self.base_url, task_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Vec<Task>>().await.map_err(|e| e.to_string())
        } else {
            Err("Failed to get subtasks".to_string())
        }
    }

    /// Add a subtask to a parent task
    pub async fn add_subtask(&self, parent_id: &str, content: &str, priority: Priority) -> Result<Task, String> {
        let body = serde_json::json!({
            "content": content,
            "priority": priority.label(),
        });

        let resp = self.client
            .post(format!("{}/{}/subtasks", self.base_url, parent_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Add subtask failed").to_string())
        }
    }

    /// Add a blocker (prerequisite) to a task
    pub async fn add_blocker(&self, task_id: &str, blocker_id: &str) -> Result<(), String> {
        let body = serde_json::json!({ "blocker_id": blocker_id });

        let resp = self.client
            .post(format!("{}/{}/block", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            Ok(())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Add blocker failed").to_string())
        }
    }

    /// Remove a blocker from a task
    pub async fn remove_blocker(&self, task_id: &str, blocker_id: &str) -> Result<(), String> {
        let resp = self.client
            .delete(format!("{}/{}/block/{}", self.base_url, task_id, blocker_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            Ok(())
        } else {
            Err("Failed to remove blocker".to_string())
        }
    }

    /// Get blockers (prerequisites) for a task
    pub async fn get_blockers(&self, task_id: &str) -> Result<Vec<Task>, String> {
        let resp = self.client
            .get(format!("{}/{}/blockers", self.base_url, task_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Vec<Task>>().await.map_err(|e| e.to_string())
        } else {
            Err("Failed to get blockers".to_string())
        }
    }

    /// Update task notes
    pub async fn update_notes(&self, task_id: &str, notes: &str) -> Result<Task, String> {
        let body = serde_json::json!({ "notes": notes });

        let resp = self.client
            .patch(format!("{}/{}", self.base_url, task_id))
            .json(&body)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        if resp.status().is_success() {
            resp.json::<Task>().await.map_err(|e| e.to_string())
        } else {
            let error: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            Err(error.get("message").and_then(|m| m.as_str()).unwrap_or("Update failed").to_string())
        }
    }
}

impl Default for TaskQueueHttpClient {
    fn default() -> Self {
        Self::new()
    }
}

// ============================================================================
// WebSocket Connection
// ============================================================================

/// Spawn a WebSocket connection that sends events to a channel
pub async fn spawn_websocket_connection(
    agent_id: Option<String>,
) -> Result<mpsc::UnboundedReceiver<TaskQueueEvent>, String> {
    let (tx, rx) = mpsc::unbounded_channel();
    
    let url = if let Some(ref id) = agent_id {
        format!("{}?agent_id={}", DEFAULT_WS_URL, id)
    } else {
        DEFAULT_WS_URL.to_string()
    };

    let url = Url::parse(&url).map_err(|e| e.to_string())?;

    tokio::spawn(async move {
        loop {
            match connect_and_handle(&url, tx.clone()).await {
                Ok(()) => {
                    // Clean disconnect
                    let _ = tx.send(TaskQueueEvent::Disconnected);
                    break;
                }
                Err(e) => {
                    log::error!("WebSocket error: {}", e);
                    let _ = tx.send(TaskQueueEvent::Error(e.clone()));
                    let _ = tx.send(TaskQueueEvent::Disconnected);
                    // Wait before reconnecting
                    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                }
            }
        }
    });

    Ok(rx)
}

async fn connect_and_handle(
    url: &Url,
    tx: mpsc::UnboundedSender<TaskQueueEvent>,
) -> Result<(), String> {
    let (ws_stream, _) = connect_async(url.as_str())
        .await
        .map_err(|e| format!("WebSocket connection failed: {}", e))?;

    let _ = tx.send(TaskQueueEvent::Connected);
    
    let (mut write, mut read) = ws_stream.split();

    while let Some(msg_result) = read.next().await {
        match msg_result {
            Ok(WsMessage::Text(text)) => {
                // Use eprintln for guaranteed visibility (bypasses log system)
                eprintln!("[task_queue_ws] Received text ({} bytes)", text.len());
                log::debug!("WS raw text ({} bytes): {}", text.len(), &text[..text.len().min(300)]);
                match serde_json::from_str::<WsServerMessage>(&text) {
                    Ok(server_msg) => { match server_msg {
                        WsServerMessage::Connected { .. } => {
                            eprintln!("[task_queue_ws] Connected message received");
                        }
                        WsServerMessage::Init { tasks, stats, current_task, .. } => {
                            eprintln!("[task_queue_ws] Init: {} tasks, stats={:?}", tasks.len(), stats);
                            log::info!("WS Init received: {} tasks, stats={:?}, current={:?}",
                                tasks.len(),
                                stats,
                                current_task.as_ref().map(|t| &t.content));
                            let _ = tx.send(TaskQueueEvent::InitialState {
                                tasks,
                                stats,
                                current_task,
                            });
                        }
                        WsServerMessage::TaskAdded { task } => {
                            let _ = tx.send(TaskQueueEvent::TaskAdded(task));
                        }
                        WsServerMessage::TaskUpdated { task, .. } => {
                            let _ = tx.send(TaskQueueEvent::TaskUpdated(task));
                        }
                        WsServerMessage::TaskStarted { task } => {
                            let _ = tx.send(TaskQueueEvent::TaskStarted(task));
                        }
                        WsServerMessage::TaskCompleted { task } => {
                            let _ = tx.send(TaskQueueEvent::TaskCompleted(task));
                        }
                        WsServerMessage::TaskCancelled { task } => {
                            let _ = tx.send(TaskQueueEvent::TaskCancelled(task));
                        }
                        WsServerMessage::TaskClaimed { task, agent_id } => {
                            let _ = tx.send(TaskQueueEvent::TaskClaimed { task, agent_id });
                        }
                        WsServerMessage::TaskReleased { task } => {
                            let _ = tx.send(TaskQueueEvent::TaskReleased(task));
                        }
                        WsServerMessage::TaskRemoved { task_id } => {
                            let _ = tx.send(TaskQueueEvent::TaskRemoved(task_id));
                        }
                        WsServerMessage::Heartbeat { .. } => {
                            // Respond with ping to keep alive
                            let ping = serde_json::to_string(&WsClientMessage::Ping).unwrap();
                            let _ = write.send(WsMessage::Text(ping)).await;
                        }
                        WsServerMessage::Pong { .. } => {
                            // Ignore pong
                        }
                        WsServerMessage::Error { message } => {
                            let _ = tx.send(TaskQueueEvent::Error(message));
                        }
                    } }
                    Err(e) => {
                        eprintln!("[task_queue_ws] PARSE ERROR: {} -- raw: {}", e, &text[..text.len().min(500)]);
                        log::warn!("Failed to parse WS message: {} -- raw: {}", e, &text[..text.len().min(200)]);
                    }
                }
            }
            Ok(WsMessage::Close(_)) => {
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
