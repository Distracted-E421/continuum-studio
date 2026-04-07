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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_agent_type_icon() {
        assert_eq!(AgentType::SessionAgent.icon(), "🖥️");
        assert_eq!(AgentType::SubAgent.icon(), "🔧");
        assert_eq!(AgentType::Unknown.icon(), "❓");
    }

    #[test]
    fn test_agent_type_label() {
        assert_eq!(AgentType::SessionAgent.label(), "Session");
        assert_eq!(AgentType::SubAgent.label(), "Sub-agent");
        assert_eq!(AgentType::Unknown.label(), "Unknown");
    }

    #[test]
    fn test_agent_status_icon() {
        assert_eq!(AgentStatus::Active.icon(), "🟢");
        assert_eq!(AgentStatus::Idle.icon(), "🟡");
        assert_eq!(AgentStatus::Waiting.icon(), "🔵");
        assert_eq!(AgentStatus::Completed.icon(), "✅");
        assert_eq!(AgentStatus::Disconnected.icon(), "🔴");
        assert_eq!(AgentStatus::Unknown.icon(), "⚪");
    }

    #[test]
    fn test_agent_status_label() {
        assert_eq!(AgentStatus::Active.label(), "active");
        assert_eq!(AgentStatus::Idle.label(), "idle");
        assert_eq!(AgentStatus::Waiting.label(), "waiting");
        assert_eq!(AgentStatus::Completed.label(), "completed");
        assert_eq!(AgentStatus::Disconnected.label(), "disconnected");
        assert_eq!(AgentStatus::Unknown.label(), "unknown");
    }

    #[test]
    fn test_agent_type_serialization() {
        let json = serde_json::to_string(&AgentType::SessionAgent).unwrap();
        assert_eq!(json, "\"session_agent\"");
        
        let json = serde_json::to_string(&AgentType::SubAgent).unwrap();
        assert_eq!(json, "\"sub_agent\"");
    }

    #[test]
    fn test_agent_type_deserialization() {
        let t: AgentType = serde_json::from_str("\"session_agent\"").unwrap();
        assert_eq!(t, AgentType::SessionAgent);
        
        let t: AgentType = serde_json::from_str("\"sub_agent\"").unwrap();
        assert_eq!(t, AgentType::SubAgent);
        
        // Unknown values should deserialize to Unknown
        let t: AgentType = serde_json::from_str("\"something_else\"").unwrap();
        assert_eq!(t, AgentType::Unknown);
    }

    #[test]
    fn test_agent_focus_default() {
        let focus = AgentFocus::default();
        assert!(focus.repos.is_empty());
        assert!(focus.files.is_empty());
        assert!(focus.area.is_none());
        assert!(focus.description.is_none());
    }

    #[test]
    fn test_agent_serialization() {
        let agent = Agent {
            id: "test-agent-123".to_string(),
            agent_type: AgentType::SessionAgent,
            parent_id: None,
            status: AgentStatus::Active,
            current_task_id: Some("task-456".to_string()),
            focus: AgentFocus {
                repos: vec!["continuum-studio".to_string()],
                files: vec!["main.rs".to_string()],
                area: Some("UI".to_string()),
                description: Some("Working on tests".to_string()),
            },
            workspace: Some("/home/user/project".to_string()),
            session_id: Some("session-789".to_string()),
            capabilities: vec!["fast_shell".to_string()],
            registered_at: Some("2026-04-07T12:00:00Z".to_string()),
            last_activity: Some("2026-04-07T12:30:00Z".to_string()),
            file_claims: vec!["src/main.rs".to_string()],
        };
        
        let json = serde_json::to_string(&agent);
        assert!(json.is_ok());
        
        let deserialized: Result<Agent, _> = serde_json::from_str(&json.unwrap());
        assert!(deserialized.is_ok());
        
        let restored = deserialized.unwrap();
        assert_eq!(restored.id, "test-agent-123");
        assert_eq!(restored.agent_type, AgentType::SessionAgent);
        assert_eq!(restored.status, AgentStatus::Active);
    }

    #[test]
    fn test_conflict_serialization() {
        let conflict = Conflict {
            id: "conflict-001".to_string(),
            conflict_type: "file_claim".to_string(),
            agents: vec!["agent-1".to_string(), "agent-2".to_string()],
            resource: "src/main.rs".to_string(),
            detected_at: "2026-04-07T12:00:00Z".to_string(),
            resolved: false,
            resolution: None,
        };
        
        let json = serde_json::to_string(&conflict);
        assert!(json.is_ok());
        
        let deserialized: Result<Conflict, _> = serde_json::from_str(&json.unwrap());
        assert!(deserialized.is_ok());
        
        let restored = deserialized.unwrap();
        assert_eq!(restored.id, "conflict-001");
        assert_eq!(restored.agents.len(), 2);
        assert!(!restored.resolved);
    }

    #[test]
    fn test_coordinator_http_client_default() {
        let client = CoordinatorHttpClient::default();
        assert!(!client.base_url.is_empty());
    }
}
