//! HTTP client for Parked Agents API (Synapsix)
//!
//! Fetches parked agents from GET /api/agents/parked and unparks with task assignment.
//! Stubs empty responses when the endpoint is not yet available.

use chrono::{DateTime, Utc};
use reqwest::Client;
use serde::Deserialize;

use crate::parked_agents::ParkedAgent;

/// Default Synapsix API base URL
pub const DEFAULT_API_BASE: &str = "http://localhost:4001";

// =============================================================================
// API Response Types
// =============================================================================

/// Response from GET /api/agents/parked
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
        let url = format!("{}/api/agents/parked", self.base_url);

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
    /// Calls POST /api/agents/parked/{id}/unpark with task body.
    /// Stubs success when endpoint is not available (404).
    pub async fn unpark_and_assign(
        &self,
        agent_id: &str,
        task_description: &str,
    ) -> Result<(), String> {
        let url = format!("{}/api/agents/parked/{}/unpark", self.base_url, agent_id);

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
