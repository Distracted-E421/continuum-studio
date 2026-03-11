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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub prefix: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub suffix: Option<String>,
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
        self.spawn_agent_with_preset(prompt, workspace, mode, force, approve_mcps, None, None)
            .await
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

        let resp = self
            .client
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
        let resp = self
            .client
            .get(&self.base_url)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        let list: ListAgentsResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(list.agents)
    }

    /// Get details for a specific agent
    pub async fn get_agent(&self, agent_id: &str) -> Result<AgentDetails, String> {
        let resp = self
            .client
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
        let resp = self
            .client
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

    /// Spawn a single CLI agent with custom prefix/suffix
    pub async fn spawn_agent_with_preset(
        &self,
        prompt: &str,
        workspace: &str,
        mode: Option<AgentMode>,
        force: Option<bool>,
        approve_mcps: Option<bool>,
        prefix: Option<String>,
        suffix: Option<String>,
    ) -> Result<SpawnResponse, String> {
        let req = SpawnAgentRequest {
            prompt: prompt.to_string(),
            workspace: workspace.to_string(),
            mode: mode.map(|m| m.label().to_string()),
            force,
            approve_mcps,
            prefix,
            suffix,
        };

        let resp = self
            .client
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

    // =========================================================================
    // Presets API
    // =========================================================================

    /// List all presets
    pub async fn list_presets(&self) -> Result<Vec<crate::cli_agents::Preset>, String> {
        let resp = self
            .client
            .get("http://localhost:4001/api/presets")
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct PresetsResponse {
            presets: Vec<crate::cli_agents::Preset>,
        }

        let list: PresetsResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(list.presets)
    }

    /// Create a new preset
    pub async fn create_preset(
        &self,
        name: &str,
        description: Option<String>,
        category: &str,
        prefix: Option<String>,
        suffix: Option<String>,
    ) -> Result<crate::cli_agents::Preset, String> {
        #[derive(Serialize)]
        struct CreatePresetRequest {
            name: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            description: Option<String>,
            category: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            prefix: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            suffix: Option<String>,
        }

        let req = CreatePresetRequest {
            name: name.to_string(),
            description,
            category: category.to_string(),
            prefix,
            suffix,
        };

        let resp = self
            .client
            .post("http://localhost:4001/api/presets")
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct PresetResponse {
            preset: crate::cli_agents::Preset,
        }

        let resp: PresetResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(resp.preset)
    }

    /// Update an existing preset
    pub async fn update_preset(
        &self,
        id: &str,
        name: &str,
        description: Option<String>,
        category: &str,
        prefix: Option<String>,
        suffix: Option<String>,
    ) -> Result<crate::cli_agents::Preset, String> {
        #[derive(Serialize)]
        struct UpdatePresetRequest {
            name: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            description: Option<String>,
            category: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            prefix: Option<String>,
            #[serde(skip_serializing_if = "Option::is_none")]
            suffix: Option<String>,
        }

        let req = UpdatePresetRequest {
            name: name.to_string(),
            description,
            category: category.to_string(),
            prefix,
            suffix,
        };

        let resp = self
            .client
            .put(format!("http://localhost:4001/api/presets/{}", id))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct PresetResponse {
            preset: crate::cli_agents::Preset,
        }

        let resp: PresetResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(resp.preset)
    }

    /// Delete a preset
    pub async fn delete_preset(&self, id: &str) -> Result<(), String> {
        let resp = self
            .client
            .delete(format!("http://localhost:4001/api/presets/{}", id))
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

    /// List all snippets
    pub async fn list_snippets(&self) -> Result<Vec<crate::cli_agents::Snippet>, String> {
        let resp = self
            .client
            .get("http://localhost:4001/api/presets/snippets")
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct SnippetsResponse {
            snippets: Vec<crate::cli_agents::Snippet>,
        }

        let list: SnippetsResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(list.snippets)
    }

    /// Create a new snippet
    pub async fn create_snippet(
        &self,
        name: &str,
        description: Option<String>,
        content: &str,
        position: &str,
    ) -> Result<crate::cli_agents::Snippet, String> {
        #[derive(Serialize)]
        struct CreateSnippetRequest {
            name: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            description: Option<String>,
            content: String,
            position: String,
        }

        let req = CreateSnippetRequest {
            name: name.to_string(),
            description,
            content: content.to_string(),
            position: position.to_string(),
        };

        let resp = self
            .client
            .post("http://localhost:4001/api/presets/snippets")
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct SnippetResponse {
            snippet: crate::cli_agents::Snippet,
        }

        let resp: SnippetResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(resp.snippet)
    }

    /// Update an existing snippet
    pub async fn update_snippet(
        &self,
        id: &str,
        name: &str,
        description: Option<String>,
        content: &str,
        position: &str,
    ) -> Result<crate::cli_agents::Snippet, String> {
        #[derive(Serialize)]
        struct UpdateSnippetRequest {
            name: String,
            #[serde(skip_serializing_if = "Option::is_none")]
            description: Option<String>,
            content: String,
            position: String,
        }

        let req = UpdateSnippetRequest {
            name: name.to_string(),
            description,
            content: content.to_string(),
            position: position.to_string(),
        };

        let resp = self
            .client
            .put(format!("http://localhost:4001/api/presets/snippets/{}", id))
            .json(&req)
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct SnippetResponse {
            snippet: crate::cli_agents::Snippet,
        }

        let resp: SnippetResponse = resp
            .json()
            .await
            .map_err(|e| format!("Failed to parse response: {}", e))?;

        Ok(resp.snippet)
    }

    /// Delete a snippet
    pub async fn delete_snippet(&self, id: &str) -> Result<(), String> {
        let resp = self
            .client
            .delete(format!("http://localhost:4001/api/presets/snippets/{}", id))
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

    // ========================================================================
    // Workspace Overrides
    // ========================================================================

    /// List all workspace overrides
    pub async fn list_workspace_overrides(
        &self,
    ) -> Result<std::collections::HashMap<String, String>, String> {
        let resp = self
            .client
            .get("http://localhost:4001/api/presets/workspace-overrides")
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct Response {
            workspace_overrides: std::collections::HashMap<String, String>,
        }

        let resp: Response = resp
            .json()
            .await
            .map_err(|e| format!("Parse error: {}", e))?;
        Ok(resp.workspace_overrides)
    }

    /// Set a workspace override
    pub async fn set_workspace_override(
        &self,
        workspace: &str,
        preset_id: &str,
    ) -> Result<(), String> {
        #[derive(Serialize)]
        struct Request {
            workspace: String,
            preset_id: String,
        }

        let resp = self
            .client
            .post("http://localhost:4001/api/presets/workspace-override")
            .json(&Request {
                workspace: workspace.to_string(),
                preset_id: preset_id.to_string(),
            })
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

    /// Clear a workspace override
    pub async fn clear_workspace_override(&self, workspace: &str) -> Result<(), String> {
        #[derive(Serialize)]
        struct Request {
            workspace: String,
        }

        let resp = self
            .client
            .delete("http://localhost:4001/api/presets/workspace-override")
            .json(&Request {
                workspace: workspace.to_string(),
            })
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

    // ========================================================================
    // Dialog Orchestration API (for managing worker dialogs)
    // ========================================================================

    /// Fetch pending dialogs from session/sub-agents via the orchestrator endpoint
    pub async fn fetch_pending_dialogs(
        &self,
    ) -> Result<Vec<crate::cli_agents::PendingDialog>, String> {
        // Use the new agent-dialogs endpoint which filters to session_agent/sub_agent sources
        let resp = self
            .client
            .get("http://localhost:8080/api/agent-dialogs")
            .send()
            .await
            .map_err(|e| format!("Request failed: {}", e))?;

        if !resp.status().is_success() {
            let status = resp.status();
            let body = resp.text().await.unwrap_or_default();
            return Err(format!("API error ({}): {}", status, body));
        }

        #[derive(Deserialize)]
        struct AgentDialogsResponse {
            success: bool,
            data: Vec<AgentDialogItem>,
            #[serde(default)]
            error: Option<String>,
        }

        #[derive(Deserialize)]
        struct AgentDialogItem {
            id: String,
            title: String,
            prompt: String,
            dialog_type: String,
            #[serde(default)]
            options: Option<Vec<DialogOptionItem>>,
            #[serde(default)]
            created_at: Option<String>,
            // Agent context fields
            #[serde(default)]
            agent_id: Option<String>,
            #[serde(default)]
            source: Option<String>,
            #[serde(default)]
            priority: Option<String>,
            #[serde(default)]
            workspace: Option<String>,
            #[serde(default)]
            orchestrator_id: Option<String>,
        }

        #[derive(Deserialize)]
        struct DialogOptionItem {
            value: String,
            label: String,
            #[serde(default)]
            description: Option<String>,
        }

        let resp: AgentDialogsResponse = resp
            .json()
            .await
            .map_err(|e| format!("Parse error: {}", e))?;

        if !resp.success {
            return Err(resp.error.unwrap_or_else(|| "Unknown error".to_string()));
        }

        use crate::cli_agents::{DialogPriority, DialogSource};

        let dialogs: Vec<crate::cli_agents::PendingDialog> = resp
            .data
            .into_iter()
            .map(|d| {
                // Match both snake_case and PascalCase from API
                let source = match d.source.as_deref().map(|s| s.to_lowercase()).as_deref() {
                    Some("session_agent") | Some("sessionagent") => DialogSource::SessionAgent,
                    Some("sub_agent") | Some("subagent") => DialogSource::SubAgent,
                    Some("external") => DialogSource::External,
                    _ => DialogSource::Orchestrator,
                };
                let priority = match d.priority.as_deref().map(|s| s.to_lowercase()).as_deref() {
                    Some("low") => DialogPriority::Low,
                    Some("high") => DialogPriority::High,
                    Some("critical") => DialogPriority::Critical,
                    _ => DialogPriority::Normal,
                };

                crate::cli_agents::PendingDialog {
                    id: d.id,
                    title: d.title,
                    prompt: d.prompt,
                    dialog_type: d.dialog_type,
                    options: d.options.map(|opts| {
                        opts.into_iter()
                            .map(|o| crate::cli_agents::DialogOption {
                                value: o.value,
                                label: o.label,
                                description: o.description,
                            })
                            .collect()
                    }),
                    created_at: d.created_at,
                    agent_id: d.agent_id,
                    source,
                    priority,
                    workspace: d.workspace,
                    orchestrator_id: d.orchestrator_id,
                }
            })
            .collect();

        Ok(dialogs)
    }

    /// Respond to a pending agent dialog via the orchestrator endpoint
    pub async fn respond_to_dialog(
        &self,
        dialog_id: &str,
        selection: &str,
        comment: Option<String>,
    ) -> Result<(), String> {
        #[derive(Serialize)]
        struct RespondRequest {
            selection: serde_json::Value,
            #[serde(skip_serializing_if = "Option::is_none")]
            comment: Option<String>,
        }

        // Try to parse selection as bool for confirmation dialogs
        let selection_value = if selection == "true" {
            serde_json::Value::Bool(true)
        } else if selection == "false" {
            serde_json::Value::Bool(false)
        } else if let Ok(num) = selection.parse::<f64>() {
            serde_json::Value::Number(serde_json::Number::from_f64(num).unwrap_or_else(|| {
                serde_json::Number::from(0)
            }))
        } else {
            serde_json::Value::String(selection.to_string())
        };

        let req = RespondRequest {
            selection: selection_value,
            comment,
        };

        // Use the new agent-dialogs/:id/respond endpoint
        let url = format!("http://localhost:8080/api/agent-dialogs/{}/respond", dialog_id);
        let resp = self
            .client
            .post(&url)
            .json(&req)
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

    /// Escalate a dialog to critical priority (brings to user attention)
    pub async fn escalate_dialog(&self, dialog_id: &str) -> Result<(), String> {
        let url = format!("http://localhost:8080/api/agent-dialogs/{}/escalate", dialog_id);
        let resp = self
            .client
            .post(&url)
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

        let events: Vec<CLIAgentEvent> = self
            .events
            .into_iter()
            .filter_map(|e| e.into_cli_agent_event())
            .collect();

        let started_at = self
            .started_at
            .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
            .map(|dt| dt.with_timezone(&chrono::Utc));

        let completed_at = self
            .completed_at
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
    Event { agent_id: String, event: EventJson },
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
pub async fn spawn_cli_agents_websocket(ws_url: Option<String>) -> mpsc::Receiver<CLIAgentWsEvent> {
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
            Ok(WsMessage::Text(text)) => match serde_json::from_str::<CLIAgentWsEvent>(&text) {
                Ok(event) => {
                    if tx.send(event).await.is_err() {
                        log::warn!("CLI agents event receiver dropped");
                        break;
                    }
                }
                Err(e) => {
                    log::debug!("Failed to parse WebSocket message: {} - {}", e, text);
                }
            },
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

// ============================================================================
// Orchestrator WebSocket (for agent dialog notifications)
// ============================================================================

/// Default WebSocket URL for orchestrator dialog monitoring
pub const ORCHESTRATOR_WS_URL: &str = "ws://localhost:8080/ws/orchestrator";

/// Events received over orchestrator WebSocket
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum OrchestratorWsEvent {
    /// New dialog from agent
    DialogCreated {
        dialog: OrchestratorDialogInfo,
    },
    /// Dialog was answered
    DialogAnswered {
        dialog_id: String,
    },
    /// Dialog priority escalated
    DialogEscalated {
        dialog_id: String,
    },
    /// Connection established
    Connected,
    /// Heartbeat
    Ping,
}

/// Dialog info as sent over WebSocket
#[derive(Debug, Clone, Deserialize)]
pub struct OrchestratorDialogInfo {
    pub id: String,
    pub title: String,
    pub prompt: String,
    pub dialog_type: String,
    #[serde(default)]
    pub options: Option<Vec<crate::cli_agents::DialogOption>>,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub source: Option<String>,
    #[serde(default)]
    pub priority: Option<String>,
    #[serde(default)]
    pub workspace: Option<String>,
    #[serde(default)]
    pub created_at: Option<String>,
}

impl OrchestratorDialogInfo {
    /// Convert to PendingDialog
    pub fn into_pending_dialog(self) -> crate::cli_agents::PendingDialog {
        use crate::cli_agents::{DialogPriority, DialogSource};

        let source = match self.source.as_deref() {
            Some("session_agent") => DialogSource::SessionAgent,
            Some("sub_agent") => DialogSource::SubAgent,
            Some("external") => DialogSource::External,
            _ => DialogSource::Orchestrator,
        };
        let priority = match self.priority.as_deref() {
            Some("low") => DialogPriority::Low,
            Some("high") => DialogPriority::High,
            Some("critical") => DialogPriority::Critical,
            _ => DialogPriority::Normal,
        };

        crate::cli_agents::PendingDialog {
            id: self.id,
            title: self.title,
            prompt: self.prompt,
            dialog_type: self.dialog_type,
            options: self.options,
            created_at: self.created_at,
            agent_id: self.agent_id,
            source,
            priority,
            workspace: self.workspace,
            orchestrator_id: None,
        }
    }
}

/// Spawn an orchestrator WebSocket connection for real-time dialog notifications
pub async fn spawn_orchestrator_websocket(
    ws_url: Option<String>,
) -> mpsc::Receiver<OrchestratorWsEvent> {
    let (tx, rx) = mpsc::channel(100);

    tokio::spawn(async move {
        let url = ws_url.unwrap_or_else(|| ORCHESTRATOR_WS_URL.to_string());
        orchestrator_websocket_loop(url, tx).await;
    });

    rx
}

/// Internal WebSocket loop with reconnection logic
async fn orchestrator_websocket_loop(url: String, tx: mpsc::Sender<OrchestratorWsEvent>) {
    loop {
        match connect_to_orchestrator_ws(&url, &tx).await {
            Ok(()) => {
                log::info!("Orchestrator WebSocket disconnected cleanly");
            }
            Err(e) => {
                log::warn!("Orchestrator WebSocket error: {}", e);
            }
        }

        // Wait before reconnecting
        tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        log::info!("Attempting to reconnect to orchestrator WebSocket...");
    }
}

/// Connect to orchestrator WebSocket and process messages
async fn connect_to_orchestrator_ws(
    url: &str,
    tx: &mpsc::Sender<OrchestratorWsEvent>,
) -> Result<(), String> {
    let (ws_stream, _response) = connect_async(url)
        .await
        .map_err(|e| format!("WebSocket connection failed: {}", e))?;

    log::info!("Orchestrator WebSocket connected to {}", url);

    // Send connected event
    let _ = tx.send(OrchestratorWsEvent::Connected).await;

    let (mut write, mut read) = ws_stream.split();

    while let Some(msg) = read.next().await {
        match msg {
            Ok(WsMessage::Text(text)) => match serde_json::from_str::<OrchestratorWsEvent>(&text) {
                Ok(event) => {
                    if tx.send(event).await.is_err() {
                        log::warn!("Orchestrator event receiver dropped");
                        break;
                    }
                }
                Err(e) => {
                    log::debug!("Failed to parse orchestrator message: {} - {}", e, text);
                }
            },
            Ok(WsMessage::Ping(data)) => {
                if let Err(e) = write.send(WsMessage::Pong(data)).await {
                    log::warn!("Failed to send pong: {}", e);
                    break;
                }
            }
            Ok(WsMessage::Close(_)) => {
                log::info!("Orchestrator WebSocket closed by server");
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
