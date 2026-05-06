//! HTTP client for Parked Agents API (Synapsix)
//!
//! Fetches parked agents from GET /api/parking/agents and unparks with task assignment.
//! Stubs empty responses when the endpoint is not yet available.

use chrono::{DateTime, Utc};
use reqwest::Client;
use serde::Deserialize;

use crate::parked_agents::ParkedAgent;

/// Default Synapsix API base URL (dialog daemon, not CLI backend)
pub const DEFAULT_API_BASE: &str = "http://localhost:8080";

// =============================================================================
// API Response Types
// =============================================================================

/// Response from GET /api/parking/agents
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParkedAgentsResponse {
    #[serde(default)]
    pub agents: Vec<ParkedAgentJson>,
}

/// JSON representation of a parked agent (API format)
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ParkedAgentJson {
    pub agent_id: String,
    pub workspace: String,
    #[serde(with = "chrono::serde::ts_seconds")]
    pub parked_at: DateTime<Utc>,
    #[serde(default)]
    pub capabilities: Vec<String>,
    #[serde(default, with = "chrono::serde::ts_seconds_option")]
    pub last_heartbeat: Option<DateTime<Utc>>,
}

impl From<ParkedAgentJson> for ParkedAgent {
    fn from(j: ParkedAgentJson) -> Self {
        ParkedAgent {
            agent_id: j.agent_id,
            workspace: j.workspace,
            parked_at: j.parked_at,
            capabilities: j.capabilities,
            last_heartbeat: j.last_heartbeat.unwrap_or(j.parked_at),
        }
    }
}

// =============================================================================
// HTTP Client
// =============================================================================

/// HTTP client for parked agents API
#[derive(Debug, Clone)]
pub struct ParkedAgentsHttpClient {
    client: Client,
    base_url: String,
}

impl Default for ParkedAgentsHttpClient {
    fn default() -> Self {
        Self::new()
    }
}

impl ParkedAgentsHttpClient {
    pub fn new() -> Self {
        Self {
            client: Client::new(),
            base_url: DEFAULT_API_BASE.to_string(),
        }
    }

    pub fn with_base_url(base_url: &str) -> Self {
        Self {
            client: Client::new(),
            base_url: base_url.to_string(),
        }
    }

    /// Fetch list of parked agents.
    /// Returns empty vec when endpoint is not available (404, connection error, etc.)
    pub async fn fetch_parked_agents(&self) -> Result<Vec<ParkedAgent>, String> {
        let url = format!("{}/api/parking/agents", self.base_url);

        let resp = match self.client.get(&url).send().await {
            Ok(r) => r,
            Err(e) => {
                log::debug!("Parked agents API unreachable: {}", e);
                return Ok(Vec::new());
            }
        };

        if !resp.status().is_success() {
            let status = resp.status();
            if status.as_u16() == 404 {
                log::debug!("Parked agents endpoint not implemented (404)");
                return Ok(Vec::new());
            }
            let body = resp.text().await.unwrap_or_default();
            log::debug!("Parked agents API error ({}): {}", status, body);
            return Ok(Vec::new());
        }

        let parsed: ParkedAgentsResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(parsed.agents.into_iter().map(ParkedAgent::from).collect())
    }

    /// Unpark an agent and assign it a task.
    /// Calls POST /api/parking/agents/{id}/unpark with task body.
    /// Stubs success when endpoint is not available (404).
    pub async fn unpark_and_assign(
        &self,
        agent_id: &str,
        task_description: &str,
    ) -> Result<(), String> {
        let url = format!("{}/api/parking/agents/{}/unpark", self.base_url, agent_id);

        let body = serde_json::json!({
            "task": task_description
        });

        let resp = match self.client.post(&url).json(&body).send().await {
            Ok(r) => r,
            Err(e) => {
                return Err(format!("Request failed: {}", e));
            }
        };

        if !resp.status().is_success() {
            let status = resp.status();
            let body_text = resp.text().await.unwrap_or_default();
            if status.as_u16() == 404 {
                log::debug!("Unpark endpoint not implemented (404), treating as success");
                return Ok(());
            }
            return Err(format!("API error ({}): {}", status, body_text));
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_api_base() {
        assert_eq!(DEFAULT_API_BASE, "http://localhost:8080");
    }

    #[test]
    fn test_client_default() {
        let client = ParkedAgentsHttpClient::default();
        assert_eq!(client.base_url, DEFAULT_API_BASE);
    }

    #[test]
    fn test_client_with_base_url() {
        let client = ParkedAgentsHttpClient::with_base_url("http://custom:9000");
        assert_eq!(client.base_url, "http://custom:9000");
    }

    #[test]
    fn test_parked_agents_response_deserialization() {
        let json = r#"{
            "agents": [
                {
                    "agentId": "agent-123",
                    "workspace": "/home/user/project",
                    "parkedAt": 1712491200,
                    "capabilities": ["fast_shell", "fast_dialog"],
                    "lastHeartbeat": 1712491300
                }
            ]
        }"#;

        let response: ParkedAgentsResponse = serde_json::from_str(json).unwrap();
        assert_eq!(response.agents.len(), 1);
        assert_eq!(response.agents[0].agent_id, "agent-123");
        assert_eq!(response.agents[0].workspace, "/home/user/project");
        assert_eq!(response.agents[0].capabilities.len(), 2);
    }

    #[test]
    fn test_parked_agents_response_empty() {
        let json = r#"{"agents": []}"#;
        let response: ParkedAgentsResponse = serde_json::from_str(json).unwrap();
        assert!(response.agents.is_empty());
    }

    #[test]
    fn test_parked_agent_json_to_parked_agent() {
        use chrono::TimeZone;

        let json_agent = ParkedAgentJson {
            agent_id: "agent-456".to_string(),
            workspace: "/home/dev/code".to_string(),
            parked_at: Utc.with_ymd_and_hms(2026, 4, 7, 12, 0, 0).unwrap(),
            capabilities: vec!["fast_shell".to_string()],
            last_heartbeat: Some(Utc.with_ymd_and_hms(2026, 4, 7, 12, 30, 0).unwrap()),
        };

        let parked: ParkedAgent = json_agent.into();
        assert_eq!(parked.agent_id, "agent-456");
        assert_eq!(parked.workspace, "/home/dev/code");
        assert_eq!(parked.capabilities.len(), 1);
    }

    #[test]
    fn test_parked_agent_json_without_heartbeat() {
        use chrono::TimeZone;

        let parked_time = Utc.with_ymd_and_hms(2026, 4, 7, 12, 0, 0).unwrap();
        let json_agent = ParkedAgentJson {
            agent_id: "agent-789".to_string(),
            workspace: "/home/dev/project".to_string(),
            parked_at: parked_time,
            capabilities: vec![],
            last_heartbeat: None,
        };

        let parked: ParkedAgent = json_agent.into();
        assert_eq!(parked.last_heartbeat, parked_time);
    }
}
