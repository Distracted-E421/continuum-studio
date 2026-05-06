//! Generic WebSocket client for Synapsix SCP Canvas subscriptions.
//!
//! Connects to `ws://localhost:4001/scp/canvas` and handles multiple canvas subscriptions.

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::{connect_async, tungstenite};
use url::Url;

pub const DEFAULT_SCP_CANVAS_WS_URL: &str = "ws://localhost:4001/scp/canvas";

/// Canvas types supported by SCP
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CanvasType {
    DecisionTree,
    ThinkingVis,
    Topology,
    ActivityStream,
    Statcard,
}

impl std::fmt::Display for CanvasType {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CanvasType::DecisionTree => write!(f, "decision_tree"),
            CanvasType::ThinkingVis => write!(f, "thinking_vis"),
            CanvasType::Topology => write!(f, "topology"),
            CanvasType::ActivityStream => write!(f, "activity_stream"),
            CanvasType::Statcard => write!(f, "statcard"),
        }
    }
}

/// Events emitted by the canvas WebSocket client
#[derive(Debug, Clone)]
pub enum CanvasWsEvent {
    Connected,
    Disconnected,
    Subscribed {
        subscription_id: String,
        canvas_id: String,
        canvas_type: CanvasType,
    },
    Unsubscribed {
        subscription_id: String,
    },
    Update {
        subscription_id: String,
        canvas_id: String,
        seq: u64,
        payload: serde_json::Value,
    },
    ActionReceived {
        canvas_id: String,
    },
    Error {
        code: String,
        message: String,
    },
    Heartbeat,
}

/// Commands sent to the canvas WebSocket
#[derive(Debug, Clone)]
pub enum CanvasWsCommand {
    Subscribe {
        subscription_id: String,
        canvas_id: String,
        canvas_type: CanvasType,
        params: Option<serde_json::Value>,
    },
    Unsubscribe {
        subscription_id: String,
    },
    Action {
        canvas_id: String,
        action: serde_json::Value,
    },
    Ping,
}

/// Server → Client messages
#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsServerMessage {
    Connected {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Subscribed {
        subscription_id: String,
        canvas_id: String,
        canvas_type: CanvasType,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Unsubscribed {
        subscription_id: String,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Update {
        subscription_id: String,
        canvas_id: String,
        seq: u64,
        payload: serde_json::Value,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    ActionReceived {
        canvas_id: String,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Error {
        code: String,
        message: String,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Heartbeat {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Pong {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
}

/// Client → Server messages
#[derive(Debug, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsClientMessage {
    Subscribe {
        subscription_id: String,
        canvas_id: String,
        canvas_type: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        params: Option<serde_json::Value>,
    },
    Unsubscribe {
        subscription_id: String,
    },
    Action {
        canvas_id: String,
        action: serde_json::Value,
    },
    Ping,
}

/// Handle for controlling the canvas WebSocket connection
pub struct CanvasWsHandle {
    cmd_tx: mpsc::UnboundedSender<CanvasWsCommand>,
}

impl CanvasWsHandle {
    /// Subscribe to a canvas
    pub fn subscribe(
        &self,
        subscription_id: &str,
        canvas_id: &str,
        canvas_type: CanvasType,
        params: Option<serde_json::Value>,
    ) -> bool {
        self.cmd_tx
            .send(CanvasWsCommand::Subscribe {
                subscription_id: subscription_id.to_string(),
                canvas_id: canvas_id.to_string(),
                canvas_type,
                params,
            })
            .is_ok()
    }

    /// Unsubscribe from a canvas
    pub fn unsubscribe(&self, subscription_id: &str) -> bool {
        self.cmd_tx
            .send(CanvasWsCommand::Unsubscribe {
                subscription_id: subscription_id.to_string(),
            })
            .is_ok()
    }

    /// Send an action to a canvas
    pub fn send_action(&self, canvas_id: &str, action: serde_json::Value) -> bool {
        self.cmd_tx
            .send(CanvasWsCommand::Action {
                canvas_id: canvas_id.to_string(),
                action,
            })
            .is_ok()
    }

    /// Send a ping
    pub fn ping(&self) -> bool {
        self.cmd_tx.send(CanvasWsCommand::Ping).is_ok()
    }
}

/// Spawn the canvas WebSocket client.
///
/// Returns a handle for sending commands and a receiver for events.
/// The handle remains valid across reconnections.
pub fn spawn_canvas_websocket(
    url: Option<String>,
) -> (CanvasWsHandle, mpsc::UnboundedReceiver<CanvasWsEvent>) {
    let (event_tx, event_rx) = mpsc::unbounded_channel();
    let (cmd_tx, mut cmd_rx) = mpsc::unbounded_channel();
    let url_s = url.unwrap_or_else(|| DEFAULT_SCP_CANVAS_WS_URL.to_string());

    tokio::spawn(async move {
        loop {
            if let Err(e) = connect_loop(&url_s, event_tx.clone(), &mut cmd_rx).await {
                log::warn!("[canvas_ws] disconnected: {}; reconnect in 5s", e);
                let _ = event_tx.send(CanvasWsEvent::Disconnected);
            }
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    });

    (CanvasWsHandle { cmd_tx }, event_rx)
}

async fn connect_loop(
    url_s: &str,
    event_tx: mpsc::UnboundedSender<CanvasWsEvent>,
    cmd_rx: &mut mpsc::UnboundedReceiver<CanvasWsCommand>,
) -> Result<(), String> {
    let url = Url::parse(url_s).map_err(|e| format!("invalid url: {}", e))?;
    let (ws_stream, _) = connect_async(url.as_str())
        .await
        .map_err(|e| format!("connect failed: {}", e))?;

    let _ = event_tx.send(CanvasWsEvent::Connected);
    let (mut write, mut read) = ws_stream.split();

    // Track active subscriptions for potential re-subscribe on reconnect
    let _subscriptions: HashMap<String, (String, CanvasType)> = HashMap::new();

    loop {
        tokio::select! {
            // Handle incoming messages from server
            msg_opt = read.next() => {
                match msg_opt {
                    Some(Ok(WsMessage::Text(text))) => {
                        match serde_json::from_str::<WsServerMessage>(&text) {
                            Ok(WsServerMessage::Connected { .. }) => {
                                // Already sent Connected event above
                            }
                            Ok(WsServerMessage::Subscribed { subscription_id, canvas_id, canvas_type, .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::Subscribed {
                                    subscription_id,
                                    canvas_id,
                                    canvas_type,
                                });
                            }
                            Ok(WsServerMessage::Unsubscribed { subscription_id, .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::Unsubscribed {
                                    subscription_id,
                                });
                            }
                            Ok(WsServerMessage::Update { subscription_id, canvas_id, seq, payload, .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::Update {
                                    subscription_id,
                                    canvas_id,
                                    seq,
                                    payload,
                                });
                            }
                            Ok(WsServerMessage::ActionReceived { canvas_id, .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::ActionReceived { canvas_id });
                            }
                            Ok(WsServerMessage::Error { code, message, .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::Error { code, message });
                            }
                            Ok(WsServerMessage::Heartbeat { .. }) => {
                                let _ = event_tx.send(CanvasWsEvent::Heartbeat);
                            }
                            Ok(WsServerMessage::Pong { .. }) => {
                                // Pong received, connection is alive
                            }
                            Err(e) => {
                                log::debug!("[canvas_ws] skip frame: {} — {}", e, text);
                            }
                        }
                    }
                    Some(Ok(WsMessage::Close(_))) => return Ok(()),
                    Some(Err(tungstenite::Error::Protocol(e))) => {
                        return Err(format!("protocol: {}", e));
                    }
                    Some(Err(e)) => return Err(format!("read: {}", e)),
                    None => return Ok(()),
                    _ => {}
                }
            }

            // Handle outgoing commands
            cmd_opt = cmd_rx.recv() => {
                match cmd_opt {
                    Some(CanvasWsCommand::Subscribe { subscription_id, canvas_id, canvas_type, params }) => {
                        let msg = WsClientMessage::Subscribe {
                            subscription_id,
                            canvas_id,
                            canvas_type: canvas_type.to_string(),
                            params,
                        };
                        if let Ok(json) = serde_json::to_string(&msg) {
                            if let Err(e) = write.send(WsMessage::Text(json)).await {
                                log::warn!("[canvas_ws] send failed: {}", e);
                            }
                        }
                    }
                    Some(CanvasWsCommand::Unsubscribe { subscription_id }) => {
                        let msg = WsClientMessage::Unsubscribe { subscription_id };
                        if let Ok(json) = serde_json::to_string(&msg) {
                            if let Err(e) = write.send(WsMessage::Text(json)).await {
                                log::warn!("[canvas_ws] send failed: {}", e);
                            }
                        }
                    }
                    Some(CanvasWsCommand::Action { canvas_id, action }) => {
                        let msg = WsClientMessage::Action { canvas_id, action };
                        if let Ok(json) = serde_json::to_string(&msg) {
                            if let Err(e) = write.send(WsMessage::Text(json)).await {
                                log::warn!("[canvas_ws] send failed: {}", e);
                            }
                        }
                    }
                    Some(CanvasWsCommand::Ping) => {
                        let msg = WsClientMessage::Ping;
                        if let Ok(json) = serde_json::to_string(&msg) {
                            if let Err(e) = write.send(WsMessage::Text(json)).await {
                                log::warn!("[canvas_ws] send failed: {}", e);
                            }
                        }
                    }
                    None => {
                        // Command channel closed
                        return Ok(());
                    }
                }
            }
        }
    }
}

/// Stream adapter for `Subscription::run` (Infallible item type).
pub fn canvas_ws_worker(
    url: Option<String>,
) -> impl iced::futures::Stream<Item = CanvasWsEvent> {
    iced::stream::channel(
        100,
        move |mut output: iced::futures::channel::mpsc::Sender<CanvasWsEvent>| {
            let url = url.clone();
            async move {
                use iced::futures::SinkExt;
                let (_handle, mut rx) = spawn_canvas_websocket(url);
                while let Some(ev) = rx.recv().await {
                    let _ = output.send(ev).await;
                }
            }
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_canvas_type_display() {
        assert_eq!(CanvasType::DecisionTree.to_string(), "decision_tree");
        assert_eq!(CanvasType::ThinkingVis.to_string(), "thinking_vis");
        assert_eq!(CanvasType::Topology.to_string(), "topology");
    }

    #[test]
    fn test_client_message_serialization() {
        let msg = WsClientMessage::Subscribe {
            subscription_id: "sub-1".into(),
            canvas_id: "canvas-123".into(),
            canvas_type: "decision_tree".into(),
            params: None,
        };
        let json = serde_json::to_string(&msg).unwrap();
        assert!(json.contains("\"type\":\"subscribe\""));
        assert!(json.contains("\"subscription_id\":\"sub-1\""));
    }

    #[test]
    fn test_server_message_deserialization() {
        let json = r#"{"type":"subscribed","subscription_id":"sub-1","canvas_id":"canvas-123","canvas_type":"decision_tree","timestamp":"2026-05-05T00:00:00Z"}"#;
        let msg: WsServerMessage = serde_json::from_str(json).unwrap();
        match msg {
            WsServerMessage::Subscribed {
                subscription_id,
                canvas_id,
                canvas_type,
                ..
            } => {
                assert_eq!(subscription_id, "sub-1");
                assert_eq!(canvas_id, "canvas-123");
                assert_eq!(canvas_type, CanvasType::DecisionTree);
            }
            _ => panic!("wrong message type"),
        }
    }
}
