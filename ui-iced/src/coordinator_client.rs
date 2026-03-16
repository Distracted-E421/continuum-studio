//! Agent Coordinator Client for Synapsix Integration
//!
//! HTTP client for the Agent Coordinator service.
//! No WebSocket yet — uses polling for status updates.

use serde::{Deserialize, Serialize};

/// Default Synapsix API base URL (Coordination API for locks, conflicts, claims)
pub const DEFAULT_API_URL: &str = "http://localhost:4001/api/coordination/agents";

// ============================================================================
// Data Types (matching Synapsix AgentCoordinator)
// ============================================================================

/// Agent type
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentType {
    SessionAgent,
    SubAgent,
    #[serde(other)]
    Unknown,
}

impl AgentType {
    pub fn icon(&self) -> &'static str {
        match self {
            AgentType::SessionAgent => "🖥️",
            AgentType::SubAgent => "🔧",
            AgentType::Unknown => "❓",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            AgentType::SessionAgent => "Session",
            AgentType::SubAgent => "Sub-agent",
            AgentType::Unknown => "Unknown",
        }
    }
}

/// Agent status
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AgentStatus {
    Active,
    Idle,
    Waiting,
    Completed,
    Disconnected,
    #[serde(other)]
    Unknown,
}

impl AgentStatus {
    pub fn icon(&self) -> &'static str {
        match self {
            AgentStatus::Active => "🟢",
            AgentStatus::Idle => "🟡",
            AgentStatus::Waiting => "🔵",
            AgentStatus::Completed => "✅",
            AgentStatus::Disconnected => "🔴",
            AgentStatus::Unknown => "⚪",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            AgentStatus::Active => "active",
            AgentStatus::Idle => "idle",
            AgentStatus::Waiting => "waiting",
            AgentStatus::Completed => "completed",
            AgentStatus::Disconnected => "disconnected",
            AgentStatus::Unknown => "unknown",
        }
    }
}

/// Agent focus area
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct AgentFocus {
    #[serde(default)]
    pub repos: Vec<String>,
    #[serde(default)]
    pub files: Vec<String>,
    #[serde(default)]
    pub area: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
}

/// A registered agent
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Agent {
    pub id: String,
    #[serde(rename = "type")]
    pub agent_type: AgentType,
    #[serde(default)]
    pub parent_id: Option<String>,
    pub status: AgentStatus,
    #[serde(default)]
    pub current_task_id: Option<String>,
    #[serde(default)]
    pub focus: AgentFocus,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default)]
    pub registered_at: Option<String>,
    #[serde(default)]
    pub last_activity: Option<String>,
    #[serde(default)]
    pub file_claims: Vec<String>,
}

/// A detected conflict
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Conflict {
    pub id: String,
    #[serde(rename = "type")]
    pub conflict_type: String,
    pub agents: Vec<String>,
    pub resource: String,
    pub detected_at: String,
    pub resolved: bool,
    #[serde(default)]
    pub resolution: Option<String>,
}

/// Events from the coordinator (for UI updates)
#[derive(Debug, Clone)]
pub enum CoordinatorEvent {
    AgentsUpdated(Vec<Agent>),
    ConflictsUpdated(Vec<Conflict>),
    Error(String),
}

// ============================================================================
// HTTP Client
// ============================================================================

/// HTTP client for the Agent Coordinator
#[derive(Debug, Clone)]
pub struct CoordinatorHttpClient {
    client: reqwest::Client,
    base_url: String,
}

impl CoordinatorHttpClient {
    pub fn new() -> Self {
        Self {
            client: reqwest::Client::new(),
            base_url: DEFAULT_API_URL.to_string(),
        }
    }

    /// List all registered agents
    pub async fn list_agents(&self) -> Result<Vec<Agent>, String> {
        #[derive(Deserialize)]
        struct AgentsResponse {
            agents: Vec<Agent>,
        }

        let resp = self.client
            .get(&self.base_url)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let data = resp.json::<AgentsResponse>()
            .await
            .map_err(|e| e.to_string())?;

        Ok(data.agents)
    }

    /// Get a specific agent
    pub async fn get_agent(&self, agent_id: &str) -> Result<Agent, String> {
        let resp = self.client
            .get(format!("{}/{}", self.base_url, agent_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        resp.json::<Agent>()
            .await
            .map_err(|e| e.to_string())
    }

    /// Register a new agent
    pub async fn register_agent(
        &self,
        agent_id: &str,
        agent_type: &str,
        workspace: Option<&str>,
        session_id: Option<&str>,
    ) -> Result<Agent, String> {
        let payload = serde_json::json!({
            "agent_id": agent_id,
            "type": agent_type,
            "workspace": workspace,
            "session_id": session_id,
        });

        let resp = self.client
            .post(format!("{}/register", self.base_url))
            .json(&payload)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        resp.json::<Agent>()
            .await
            .map_err(|e| e.to_string())
    }

    /// Update agent focus
    pub async fn set_focus(&self, agent_id: &str, focus: &AgentFocus) -> Result<(), String> {
        self.client
            .patch(format!("{}/{}/focus", self.base_url, agent_id))
            .json(focus)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Send heartbeat
    pub async fn heartbeat(&self, agent_id: &str) -> Result<(), String> {
        self.client
            .post(format!("{}/{}/heartbeat", self.base_url, agent_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Get active conflicts
    pub async fn get_conflicts(&self) -> Result<Vec<Conflict>, String> {
        #[derive(Deserialize)]
        struct ConflictsResponse {
            conflicts: Vec<Conflict>,
        }

        let resp = self.client
            .get(format!("{}/conflicts", self.base_url))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let data = resp.json::<ConflictsResponse>()
            .await
            .map_err(|e| e.to_string())?;

        Ok(data.conflicts)
    }

    /// Resolve a conflict
    pub async fn resolve_conflict(&self, conflict_id: &str, resolution: &str) -> Result<(), String> {
        let payload = serde_json::json!({
            "resolution": resolution,
        });

        self.client
            .post(format!("{}/conflicts/{}/resolve", self.base_url, conflict_id))
            .json(&payload)
            .send()
            .await
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Get coordination context for an agent
    pub async fn get_context(&self, agent_id: &str) -> Result<String, String> {
        #[derive(Deserialize)]
        struct ContextResponse {
            context: String,
        }

        let resp = self.client
            .get(format!("{}/{}/context", self.base_url, agent_id))
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let data = resp.json::<ContextResponse>()
            .await
            .map_err(|e| e.to_string())?;

        Ok(data.context)
    }

    /// Suggest an agent for a task
    pub async fn suggest_agent(&self, project: Option<&str>, repo: Option<&str>) -> Result<String, String> {
        let payload = serde_json::json!({
            "project": project,
            "repo": repo,
        });

        #[derive(Deserialize)]
        struct SuggestResponse {
            suggested_agent: String,
        }

        let resp = self.client
            .post(format!("{}/suggest", self.base_url))
            .json(&payload)
            .send()
            .await
            .map_err(|e| e.to_string())?;

        let data = resp.json::<SuggestResponse>()
            .await
            .map_err(|e| e.to_string())?;

        Ok(data.suggested_agent)
    }
}

impl Default for CoordinatorHttpClient {
    fn default() -> Self {
        Self::new()
    }
}
