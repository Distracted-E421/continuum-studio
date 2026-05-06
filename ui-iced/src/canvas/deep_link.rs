//! Deep links: `synapsix://canvas/<kind>?…`

use crate::canvas::{CanvasPersistence, CanvasRegistry};
use crate::scp::CanvasType;
use serde_json::json;
use std::collections::HashMap;
use thiserror::Error;
use url::Url;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeepLink {
    pub canvas_type: CanvasType,
    pub params: HashMap<String, String>,
}

#[derive(Debug, Error)]
pub enum DeepLinkError {
    #[error("invalid URI: {0}")]
    InvalidUri(String),
    #[error("unknown canvas path: {0}")]
    UnknownCanvasPath(String),
    #[error("persistence: {0}")]
    Persistence(#[from] crate::canvas::CanvasPersistenceError),
    #[error("canvas: {0}")]
    Canvas(#[from] crate::canvas::CanvasError),
}

impl DeepLink {
    pub fn parse(uri: &str) -> Result<Self, DeepLinkError> {
        let u = Url::parse(uri).map_err(|e| DeepLinkError::InvalidUri(e.to_string()))?;

        if u.scheme() != "synapsix" {
            return Err(DeepLinkError::InvalidUri(format!(
                "expected synapsix scheme, got {}",
                u.scheme()
            )));
        }

        let host = u.host_str().unwrap_or("");
        let path = u.path().trim_start_matches('/');

        let segment = if host == "canvas" && !path.is_empty() {
            path.to_string()
        } else if host.is_empty() && path.starts_with("canvas/") {
            path.trim_start_matches("canvas/").to_string()
        } else if !host.is_empty() && path.is_empty() && host != "canvas" {
            // synapsix://activity-stream (non-standard but tolerant)
            host.to_string()
        } else if host == "canvas" && path.is_empty() {
            return Err(DeepLinkError::InvalidUri(
                "missing canvas kind after canvas/".into(),
            ));
        } else {
            path.to_string()
        };

        let canvas_type = match segment.as_str() {
            "activity-stream" | "activity_stream" => CanvasType::ActivityStream,
            "decision-tree" | "decision_tree" => CanvasType::DecisionTree,
            "thinking-vis" | "thinking_vis" | "thinking" => CanvasType::ThinkingVis,
            "dendrix-topology" | "topology" | "dendrix_topology" => CanvasType::Topology,
            "stat-card" | "stat_card" => CanvasType::StatCard,
            other => return Err(DeepLinkError::UnknownCanvasPath(other.into())),
        };

        let mut params = HashMap::new();
        for (k, v) in u.query_pairs() {
            params.insert(k.into_owned(), v.into_owned());
        }

        Ok(Self {
            canvas_type,
            params,
        })
    }

    pub fn to_uri(&self) -> String {
        let path = match self.canvas_type {
            CanvasType::StatCard => "stat-card",
            CanvasType::ActivityStream => "activity-stream",
            CanvasType::DecisionTree => "decision-tree",
            CanvasType::ThinkingVis => "thinking-vis",
            CanvasType::Topology => "dendrix-topology",
        };
        let mut u = Url::parse(&format!("synapsix://canvas/{path}"))
            .unwrap_or_else(|_| Url::parse("synapsix://canvas/unknown").unwrap());
        for (k, v) in &self.params {
            u.query_pairs_mut().append_pair(k, v);
        }
        u.into()
    }
}

pub fn handle_deep_link(
    link: DeepLink,
    registry: &mut CanvasRegistry,
    persistence: &CanvasPersistence,
    tabs: &mut Vec<String>,
    active_canvas: &mut Option<String>,
) -> Result<String, DeepLinkError> {
    let mut params_obj = serde_json::Map::new();
    for (k, v) in &link.params {
        params_obj.insert(k.clone(), json!(v));
    }
    let params = serde_json::Value::Object(params_obj);

    if let Some(existing) = link.params.get("canvas_id") {
        if registry.get(existing).is_some() {
            if !tabs.contains(existing) {
                tabs.push(existing.clone());
            }
            *active_canvas = Some(existing.clone());
            return Ok(existing.clone());
        }
        if let Ok(canvas) = persistence.load(existing) {
            let id = canvas.id().to_string();
            registry.register(canvas);
            if !tabs.contains(&id) {
                tabs.push(id.clone());
            }
            *active_canvas = Some(id.clone());
            return Ok(id);
        }
    }

    let canvas = CanvasRegistry::create(link.canvas_type, params)?;
    let id = canvas.id().to_string();
    persistence.save(&canvas)?;
    registry.register(canvas);
    if !tabs.contains(&id) {
        tabs.push(id.clone());
    }
    *active_canvas = Some(id.clone());
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_activity_stream() {
        let l = DeepLink::parse("synapsix://canvas/activity-stream?session=abc123").unwrap();
        assert_eq!(l.canvas_type, CanvasType::ActivityStream);
        assert_eq!(l.params.get("session").map(String::as_str), Some("abc123"));
    }

    #[test]
    fn parse_decision_tree() {
        let l = DeepLink::parse("synapsix://canvas/decision-tree?verification=ver-456").unwrap();
        assert_eq!(l.canvas_type, CanvasType::DecisionTree);
        assert_eq!(
            l.params.get("verification").map(String::as_str),
            Some("ver-456")
        );
    }

    #[test]
    fn round_trip_uri() {
        let mut p = HashMap::new();
        p.insert("session".into(), "x".into());
        let l = DeepLink {
            canvas_type: CanvasType::ThinkingVis,
            params: p,
        };
        let again = DeepLink::parse(&l.to_uri()).unwrap();
        assert_eq!(again, l);
    }
}
