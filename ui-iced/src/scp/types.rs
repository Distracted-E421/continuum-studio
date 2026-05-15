//! SCP base enums and primitives (Synapsix Canvas Protocol).

use serde::{Deserialize, Serialize};

/// StatCard / canvas ordering (`base.Priority`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum Priority {
    Low,
    Normal,
    High,
    Critical,
}

/// Activity / notification importance (`base.Importance`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash, Default)]
#[serde(rename_all = "snake_case")]
pub enum Importance {
    Debug,
    Low,
    #[default]
    Normal,
    /// Legacy alias for Normal - some events use "medium"
    #[serde(alias = "medium")]
    Medium,
    High,
    Critical,
}

/// NeSy decision / classifier risk tier (`base.NesyRiskLevel`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum NesyRiskLevel {
    Low,
    Medium,
    High,
    Critical,
}

/// High-level canvas lifecycle events (`base.CanvasEventKind`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum CanvasEventKind {
    Create,
    Update,
    Action,
    Subscribe,
    Unsubscribe,
    Verify,
    Promote,
    Dismiss,
}

/// StatCard promotion targets (`base.PromoteCanvasType`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum PromoteCanvasType {
    ActivityStream,
    DecisionTree,
    ThinkingVis,
    Topology,
}

/// Canvas kinds on the wire (`protocol.CanvasType`).
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum CanvasType {
    #[serde(rename = "stat_card")]
    StatCard,
    ActivityStream,
    DecisionTree,
    ThinkingVis,
    Topology,
}
