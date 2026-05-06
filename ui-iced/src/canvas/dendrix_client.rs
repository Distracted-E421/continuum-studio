//! JSON-RPC WebSocket client for the Dendrix harness (`ws://host:9080/harness`).
//!
//! Aligns with [`HARNESS_PROTOCOL`](https://github.com/datapunk/dendrix): synchronous reads use
//! `graph.get_state`, `graph.get_node`, etc. The task brief mentioned `graph.get` /
//! `node.expand_imports`; those are **not** implemented on the harness today — we map discovery to
//! `file.load` + `graph.get_state` and treat expand as `graph.get_node` + optional metadata imports.

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use thiserror::Error;
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::{connect_async, tungstenite::Message as WsMessage};
use url::Url;

/// Default harness WebSocket URL (Dendrix GUI must be running with WS server).
pub const DEFAULT_DENDRIX_HARNESS_WS: &str = "ws://127.0.0.1:9080/harness";

#[derive(Debug, Error)]
pub enum DendrixClientError {
    #[error("websocket: {0}")]
    WebSocket(String),
    #[error("json-rpc: {0}")]
    Rpc(String),
    #[error("parse: {0}")]
    Parse(String),
}

/// Minimal wire shape for `graph.get_state` (extra fields ignored).
#[derive(Debug, Clone, Deserialize)]
pub struct DendrixGraphWire {
    #[serde(default)]
    pub nodes: Vec<DendrixNodeWire>,
    #[serde(default)]
    pub edges: Vec<DendrixEdgeWire>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DendrixNodeWire {
    pub id: String,
    #[serde(default)]
    pub kind: String,
    pub label: String,
    #[serde(default)]
    pub position: Option<DendrixPositionWire>,
    #[serde(default)]
    pub metadata: HashMap<String, Value>,
    #[serde(default)]
    pub layer: Option<String>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
pub struct DendrixPositionWire {
    pub x: f32,
    pub y: f32,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DendrixEdgeWire {
    #[serde(alias = "from")]
    pub source: String,
    #[serde(alias = "to")]
    pub target: String,
    #[serde(default)]
    pub kind: String,
}

#[derive(Debug, Serialize)]
struct JsonRpcRequest<'a> {
    jsonrpc: &'static str,
    id: u64,
    method: &'a str,
    #[serde(skip_serializing_if = "Option::is_none")]
    params: Option<Value>,
}

#[derive(Debug, Deserialize)]
struct JsonRpcResponse {
    #[serde(default)]
    id: Option<Value>,
    result: Option<Value>,
    error: Option<JsonRpcErrorBody>,
}

#[derive(Debug, Deserialize)]
struct JsonRpcErrorBody {
    code: i32,
    message: String,
}

#[derive(Debug, Clone)]
pub enum DendrixNotification {
    Event { method: String, params: Value },
}

/// Lightweight RPC helper used by the subscription worker (owns multiplex state).
pub struct DendrixRpcConn {
    write_tx: mpsc::Sender<String>,
    pending: std::sync::Arc<
        tokio::sync::Mutex<HashMap<u64, oneshot::Sender<Result<Value, DendrixClientError>>>>,
    >,
}

impl DendrixRpcConn {
    pub async fn connect(
        ws_url: &str,
    ) -> Result<(Self, mpsc::Receiver<DendrixNotification>), DendrixClientError> {
        let url = Url::parse(ws_url).map_err(|e| DendrixClientError::WebSocket(e.to_string()))?;
        let (ws_stream, _) = connect_async(url.as_str())
            .await
            .map_err(|e| DendrixClientError::WebSocket(e.to_string()))?;
        let (mut write, mut read) = ws_stream.split();

        let (write_tx, mut write_rx) = mpsc::channel::<String>(64);
        let (notify_tx, notify_rx) = mpsc::channel::<DendrixNotification>(256);
        let pending = std::sync::Arc::new(tokio::sync::Mutex::new(HashMap::<
            u64,
            oneshot::Sender<Result<Value, DendrixClientError>>,
        >::new()));

        let pending_reader = pending.clone();
        tokio::spawn(async move {
            while let Some(msg) = read.next().await {
                match msg {
                    Ok(WsMessage::Text(text)) => {
                        let v: Value = match serde_json::from_str(&text) {
                            Ok(v) => v,
                            Err(_) => continue,
                        };
                        if v.get("method").and_then(|m| m.as_str()).is_some()
                            && v.get("id").is_none()
                        {
                            let method = v
                                .get("method")
                                .and_then(|m| m.as_str())
                                .unwrap_or("")
                                .to_string();
                            let params = v.get("params").cloned().unwrap_or(Value::Null);
                            let _ = notify_tx
                                .send(DendrixNotification::Event { method, params })
                                .await;
                            continue;
                        }
                        let Some(rid) = v.get("id").and_then(|id| id.as_u64()) else {
                            continue;
                        };
                        let mut map = pending_reader.lock().await;
                        if let Some(tx) = map.remove(&rid) {
                            let resp: JsonRpcResponse = match serde_json::from_value(v.clone()) {
                                Ok(r) => r,
                                Err(e) => {
                                    let _ = tx.send(Err(DendrixClientError::Parse(e.to_string())));
                                    continue;
                                }
                            };
                            let out = if let Some(err) = resp.error {
                                Err(DendrixClientError::Rpc(format!(
                                    "{} ({})",
                                    err.message, err.code
                                )))
                            } else {
                                Ok(resp.result.unwrap_or(Value::Null))
                            };
                            let _ = tx.send(out);
                        }
                    }
                    Ok(WsMessage::Close(_)) => break,
                    Err(_) => break,
                    _ => {}
                }
            }
        });

        tokio::spawn(async move {
            while let Some(txt) = write_rx.recv().await {
                if write.send(WsMessage::Text(txt)).await.is_err() {
                    break;
                }
            }
        });

        Ok((Self { write_tx, pending }, notify_rx))
    }

    fn next_id() -> u64 {
        static ID: AtomicU64 = AtomicU64::new(1);
        ID.fetch_add(1, Ordering::Relaxed)
    }

    pub async fn request(
        &self,
        method: &str,
        params: Option<Value>,
    ) -> Result<Value, DendrixClientError> {
        let id = Self::next_id();
        let (tx, rx) = oneshot::channel();
        {
            let mut guard = self.pending.lock().await;
            guard.insert(id, tx);
        }
        let req = JsonRpcRequest {
            jsonrpc: "2.0",
            id,
            method,
            params,
        };
        let body =
            serde_json::to_string(&req).map_err(|e| DendrixClientError::Parse(e.to_string()))?;
        self.write_tx
            .send(body)
            .await
            .map_err(|_| DendrixClientError::WebSocket("write channel closed".into()))?;
        rx.await
            .map_err(|_| DendrixClientError::Rpc("RPC cancelled".into()))?
    }

    pub async fn graph_get_state(&self) -> Result<DendrixGraphWire, DendrixClientError> {
        let v = self.request("graph.get_state", None).await?;
        serde_json::from_value(v).map_err(|e| DendrixClientError::Parse(e.to_string()))
    }

    pub async fn graph_get_node(&self, id: &str) -> Result<DendrixNodeWire, DendrixClientError> {
        let v = self
            .request("graph.get_node", Some(serde_json::json!({ "id": id })))
            .await?;
        serde_json::from_value(v).map_err(|e| DendrixClientError::Parse(e.to_string()))
    }

    pub async fn file_load(&self, path: &str) -> Result<(), DendrixClientError> {
        let _ = self
            .request("file.load", Some(serde_json::json!({ "path": path })))
            .await?;
        Ok(())
    }

    pub async fn meta_ping(&self) -> Result<(), DendrixClientError> {
        let _ = self.request("meta.ping", None).await?;
        Ok(())
    }
}

/// High-level facade (URL holder). Prefer [`DendrixRpcConn`] for multiplexed JSON-RPC.
#[derive(Debug, Clone)]
pub struct DendrixClient {
    pub ws_url: String,
}

impl Default for DendrixClient {
    fn default() -> Self {
        Self {
            ws_url: DEFAULT_DENDRIX_HARNESS_WS.to_string(),
        }
    }
}

impl DendrixClient {
    pub fn new(ws_url: impl Into<String>) -> Self {
        Self {
            ws_url: ws_url.into(),
        }
    }

    /// One-shot fetch (opens connection, calls `graph.get_state`, closes).
    pub async fn fetch_graph_state(&self) -> Result<DendrixGraphWire, DendrixClientError> {
        let (conn, mut _notifications) = DendrixRpcConn::connect(&self.ws_url).await?;
        conn.graph_get_state().await
    }

    /// Expand imports for a node: prefers `metadata.imports` from `graph.get_node`.
    pub async fn expand_imports(
        &self,
        node_id: &str,
        depth: u32,
    ) -> Result<Vec<DendrixNodeWire>, DendrixClientError> {
        let _ = depth;
        let (conn, mut _notifications) = DendrixRpcConn::connect(&self.ws_url).await?;
        let node = conn.graph_get_node(node_id).await?;
        let imports_val = node.metadata.get("imports").cloned().unwrap_or(Value::Null);
        let paths: Vec<String> = match imports_val {
            Value::Array(arr) => arr
                .into_iter()
                .filter_map(|v| v.as_str().map(String::from))
                .collect(),
            Value::String(s) => vec![s],
            _ => Vec::new(),
        };
        let mut out = Vec::new();
        for p in paths {
            let id = format!("imp:{}:{}", node_id, p);
            out.push(DendrixNodeWire {
                id,
                kind: "module".into(),
                label: p.clone(),
                position: None,
                metadata: HashMap::from([("file_path".into(), Value::String(p))]),
                layer: None,
            });
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_sample_graph_state() {
        let j = serde_json::json!({
            "id": "g",
            "name": "t",
            "nodes": [{"id":"a","kind":"service","label":"svc.ex","metadata":{"file_path":"lib/svc.ex"}}],
            "edges": [{"source":"a","target":"b","kind":"imports"}]
        });
        let g: DendrixGraphWire = serde_json::from_value(j).unwrap();
        assert_eq!(g.nodes.len(), 1);
        assert_eq!(g.edges.len(), 1);
    }
}
