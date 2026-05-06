//! Activity stream (`schema/scp/activity.ncl`).

use serde::{Deserialize, Serialize};
use serde_json::Value;

use super::types::Importance;

/// Activity event kind (`activity.ActivityType` — wire field `type`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ActivityType {
    FileEdit,
    FileRead,
    FileCreate,
    FileDelete,
    ShellCommand,
    ShellOutput,
    ToolCall,
    ToolResult,
    ThinkingStart,
    ThinkingChunk,
    ThinkingEnd,
    VerificationStart,
    VerificationResult,
    PlanningPhase,
    ExecutionPhase,
    Error,
    Warning,
    Info,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash, Default)]
#[serde(rename_all = "snake_case")]
pub enum ActivitySourceChannel {
    SynapsixFeed,
    DialogDaemon,
    CliBackend,
    #[default]
    Harness,
    CursorHook,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivitySource {
    pub channel: ActivitySourceChannel,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub workspace: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub origin_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub priority: Option<u32>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivityTimeRange {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivityStreamFilters {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agents: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub types: Option<Vec<ActivityType>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time_range: Option<ActivityTimeRange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub workspace: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ActivityStreamViewMode {
    Timeline,
    Grouped,
    Compact,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ActivityDiffPreviewMode {
    Hidden,
    Collapsed,
    Expanded,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivityStreamConfig {
    pub filters: ActivityStreamFilters,
    pub view_mode: ActivityStreamViewMode,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_thinking: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub show_diffs: Option<ActivityDiffPreviewMode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub max_events: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub include_output: Option<bool>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dedupe_window_ms: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivityEvent {
    pub id: String,
    #[serde(rename = "type")]
    pub activity_type: ActivityType,
    pub timestamp: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub parent_id: Option<String>,
    pub content: Value,
    pub importance: Importance,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tags: Option<Vec<String>>,
    pub source: ActivitySource,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn activity_event_round_trip() {
        let j = json!({
            "id": "550e8400-e29b-41d4-a716-446655440002",
            "type": "file_edit",
            "timestamp": "2026-04-29T16:00:00Z",
            "agent_id": "a1",
            "content": {"path": "src/lib.rs", "lines": 3},
            "importance": "normal",
            "tags": ["scp"],
            "source": {"channel": "synapsix_feed", "workspace": "/tmp/w"}
        });

        let ev: ActivityEvent = serde_json::from_value(j.clone()).unwrap();
        let out = serde_json::to_value(&ev).unwrap();
        let ev2: ActivityEvent = serde_json::from_value(out).unwrap();
        assert_eq!(ev, ev2);
    }
}
