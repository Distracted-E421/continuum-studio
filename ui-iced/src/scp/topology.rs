//! Topology overlay (`schema/scp/topology.ncl`).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum TopologyNodeType {
    File,
    Module,
    Service,
    Package,
    External,
    Zone,
    Other,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Hash)]
#[serde(rename_all = "snake_case")]
pub enum TopologyEdgeType {
    Imports,
    Calls,
    DependsOn,
    Contains,
    ConnectsTo,
    ReadsFrom,
    WritesTo,
    Publishes,
    Subscribes,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct AgentLayer {
    pub agent_id: String,
    pub color: String,
    pub visible: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TopologyNode {
    pub id: String,
    pub file_path: String,
    pub node_type: TopologyNodeType,
    pub imports: Vec<String>,
    pub agent_layers: Vec<AgentLayer>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct TopologyEdge {
    pub from: String,
    pub to: String,
    pub edge_type: TopologyEdgeType,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn topology_round_trip() {
        let node = TopologyNode {
            id: "n1".into(),
            file_path: "src/lib.rs".into(),
            node_type: TopologyNodeType::Module,
            imports: vec!["std".into()],
            agent_layers: vec![AgentLayer {
                agent_id: "a".into(),
                color: "#fff".into(),
                visible: true,
            }],
        };
        let edge = TopologyEdge {
            from: "n1".into(),
            to: "n2".into(),
            edge_type: TopologyEdgeType::Imports,
        };
        let j = json!({ "node": node, "edge": edge });
        let v = j.clone();
        let n: TopologyNode = serde_json::from_value(v["node"].clone()).unwrap();
        let e: TopologyEdge = serde_json::from_value(j["edge"].clone()).unwrap();
        assert_eq!(
            n,
            serde_json::from_value(serde_json::to_value(&n).unwrap()).unwrap()
        );
        assert_eq!(
            e,
            serde_json::from_value(serde_json::to_value(&e).unwrap()).unwrap()
        );
    }
}
