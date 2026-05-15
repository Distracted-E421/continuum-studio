//! StatCard schemas (`schema/scp/statcard.ncl`).

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use super::types::{NesyRiskLevel, Priority, PromoteCanvasType};

/// Stat row value on the wire: string or number (`statcard.StatRow`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum StatRowValue {
    String(String),
    Number(serde_json::Number),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum StatTone {
    Normal,
    Success,
    Warning,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StatRow {
    pub label: String,
    pub value: StatRowValue,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tone: Option<StatTone>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub delta: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StatContent {
    pub stats: Vec<StatRow>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ProgressContent {
    pub percent: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub current_item: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub elapsed_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub estimated_remaining_ms: Option<f64>,
    pub cancelable: bool,
    pub backgroundable: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConfirmationFileChange {
    pub path: String,
    pub additions: f64,
    pub deletions: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ConfirmationContent {
    pub files: Vec<ConfirmationFileChange>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub summary: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DecisionContent {
    pub action_summary: String,
    pub risk_level: NesyRiskLevel,
    pub tier_label: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub constraints_checked: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub constraints_failed: Option<Vec<String>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub constraints_passed: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SparklinePeak {
    pub value: f64,
    pub label: String,
}

/// `total` is number or string on the wire.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(untagged)]
pub enum SparklineTotal {
    Number(serde_json::Number),
    String(String),
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SparklineContent {
    pub values: Vec<f64>,
    pub total: SparklineTotal,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub peak: Option<SparklinePeak>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub avg: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivitySummaryLine {
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub time: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ActivitySummaryContent {
    pub lines: Vec<ActivitySummaryLine>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ErrorContent {
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub file_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snippet: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ThinkingPeekContent {
    pub excerpt: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confidence: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub options_considered: Option<f64>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum StatCardActionStyle {
    Primary,
    Secondary,
    Danger,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum StatCardActionType {
    Dismiss,
    Confirm,
    Reject,
    Promote,
    Custom,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StatCardAction {
    pub id: String,
    pub label: String,
    pub style: StatCardActionStyle,
    pub action_type: StatCardActionType,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub custom_action: Option<Map<String, Value>>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct PromoteTarget {
    pub canvas_type: PromoteCanvasType,
    pub canvas_params: Map<String, Value>,
    pub label: String,
}

/// Fields shared by every StatCard variant (`statcard.StatCardCommon`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StatCardCommon {
    pub id: String,
    pub title: String,
    pub timestamp: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub agent_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    pub priority: Priority,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub auto_dismiss_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub promote_to: Option<PromoteTarget>,
    pub actions: Vec<StatCardAction>,
}

/// Content discriminator + payload (`type` + `content` at card root).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(tag = "type", content = "content", rename_all = "snake_case")]
pub enum StatCardVariant {
    Stat(StatContent),
    Progress(ProgressContent),
    Confirmation(ConfirmationContent),
    Sparkline(SparklineContent),
    Decision(DecisionContent),
    ActivitySummary(ActivitySummaryContent),
    Error(ErrorContent),
    ThinkingPeek(ThinkingPeekContent),
}

/// Full StatCard matching Nickel `StatCard` (`flatten` merges common + variant keys).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct StatCard {
    #[serde(flatten)]
    pub common: StatCardCommon,
    #[serde(flatten)]
    pub variant: StatCardVariant,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn statcard_progress_round_trip() {
        let j = json!({
            "id": "550e8400-e29b-41d4-a716-446655440001",
            "type": "progress",
            "title": "Building Project",
            "timestamp": "2026-04-29T15:42:31Z",
            "agent_id": "agent-cli-1",
            "session_id": "sess-abc",
            "priority": "normal",
            "promote_to": {
                "canvas_type": "activity_stream",
                "canvas_params": {"filter": "build"},
                "label": "View Full Build Log"
            },
            "content": {
                "percent": 60.0,
                "current_item": "Compiling nesy/verifier",
                "elapsed_ms": 135000.0,
                "estimated_remaining_ms": 90000.0,
                "cancelable": true,
                "backgroundable": true
            },
            "actions": [{
                "id": "cancel",
                "label": "Cancel",
                "style": "secondary",
                "action_type": "custom",
                "custom_action": {}
            }]
        });

        let card: StatCard = serde_json::from_value(j.clone()).expect("deserialize");
        let out = serde_json::to_value(&card).expect("serialize");
        let card2: StatCard = serde_json::from_value(out).expect("round-trip");
        assert_eq!(card, card2);
        assert!(matches!(card.variant, StatCardVariant::Progress(_)));
    }

    #[test]
    fn stat_row_string_or_number() {
        let j = json!({
            "id": "u1",
            "type": "stat",
            "title": "t",
            "timestamp": "2026-04-29T15:42:31Z",
            "priority": "low",
            "content": {"stats": [{"label": "n", "value": 42}]},
            "actions": []
        });
        let card: StatCard = serde_json::from_value(j).expect("deserialize");
        match &card.variant {
            StatCardVariant::Stat(c) => match &c.stats[0].value {
                StatRowValue::Number(n) => assert_eq!(n.as_u64(), Some(42)),
                _ => panic!("expected number"),
            },
            _ => panic!("wrong variant"),
        }
    }
}
