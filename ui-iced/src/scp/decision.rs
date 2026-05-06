//! Decision tree nodes (`schema/scp/decision.ncl`).

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum VerificationTier {
    Engram,
    FastCheck,
    ConstraintCheck,
    Smt,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum VerificationResult {
    Approved,
    Rejected,
    Conditional,
    Timeout,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct SmtData {
    pub summary: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub constraint_code: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub full_script: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub model: Option<String>,
}

/// NeSy verification tree node (`decision.DecisionNode`).
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct DecisionNode {
    pub id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub action: Option<Map<String, Value>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verification_tier: Option<VerificationTier>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<VerificationResult>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timing_ms: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub smt_data: Option<SmtData>,
    #[serde(default)]
    pub children: Vec<DecisionNode>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn decision_node_nested_round_trip() {
        let j = json!({
            "id": "root",
            "verification_tier": "constraint_check",
            "result": "approved",
            "timing_ms": 12.0,
            "timestamp": "2026-04-29T16:00:00Z",
            "children": [{
                "id": "child",
                "result": "conditional",
                "children": []
            }]
        });
        let n: DecisionNode = serde_json::from_value(j.clone()).unwrap();
        let out = serde_json::to_value(&n).unwrap();
        let n2: DecisionNode = serde_json::from_value(out).unwrap();
        assert_eq!(n, n2);
    }
}
