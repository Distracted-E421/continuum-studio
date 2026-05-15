//! Thinking visualization (`schema/scp/thinking.ncl`).

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingCategory {
    Hypothesis,
    Evaluation,
    Decision,
    Planning,
    Uncertainty,
    SelfCorrection,
    Raw,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ThinkingChunk {
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ThinkingNode {
    pub id: String,
    pub category: ThinkingCategory,
    pub content: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub confidence: Option<f64>,
    pub timestamp: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_chunks: Option<Vec<ThinkingChunk>>,
    #[serde(default)]
    pub children: Vec<ThinkingNode>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub linked_action: Option<Map<String, Value>>,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn thinking_node_round_trip() {
        let j = json!({
            "id": "t1",
            "category": "hypothesis",
            "content": "Maybe X",
            "confidence": 0.7,
            "timestamp": "2026-04-29T16:00:00Z",
            "source_chunks": [{"text": "chunk"}],
            "children": []
        });
        let n: ThinkingNode = serde_json::from_value(j.clone()).unwrap();
        let n2: ThinkingNode = serde_json::from_value(serde_json::to_value(&n).unwrap()).unwrap();
        assert_eq!(n, n2);
    }
}
