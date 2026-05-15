//! WebSocket client for Synapsix SCP Activity Aggregator (`/scp/activity`).

use futures_util::StreamExt;
use serde::Deserialize;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::{connect_async, tungstenite};
use url::Url;

use crate::scp::activity::ActivityEvent;

pub const DEFAULT_SCP_ACTIVITY_WS_URL: &str = "ws://localhost:4001/scp/activity";

#[derive(Debug, Clone)]
pub enum ScpActivityWsEvent {
    Connected,
    Disconnected,
    Initial { events: Vec<ActivityEvent> },
    Event(ActivityEvent),
    Error(String),
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum WsServerMessage {
    Connected {
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Init {
        events: Vec<ActivityEvent>,
        #[allow(dead_code)]
        timestamp: Option<String>,
    },
    Event {
        event: ActivityEvent,
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

/// Subscribe to SCP activity. Reconnects every 5s after disconnect or failure.
pub fn spawn_scp_activity_websocket(
    url: Option<String>,
) -> mpsc::UnboundedReceiver<ScpActivityWsEvent> {
    let (tx, rx) = mpsc::unbounded_channel();
    let url_s = url.unwrap_or_else(|| DEFAULT_SCP_ACTIVITY_WS_URL.to_string());

    tokio::spawn(async move {
        loop {
            if let Err(e) = connect_loop(&url_s, tx.clone()).await {
                log::warn!("[scp_activity_ws] disconnected: {}; reconnect in 5s", e);
                let _ = tx.send(ScpActivityWsEvent::Disconnected);
            }
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    });

    rx
}

async fn connect_loop(
    url_s: &str,
    tx: mpsc::UnboundedSender<ScpActivityWsEvent>,
) -> Result<(), String> {
    let url = Url::parse(url_s).map_err(|e| format!("invalid url: {}", e))?;
    let (ws_stream, _) = connect_async(url.as_str())
        .await
        .map_err(|e| format!("connect failed: {}", e))?;

    let _ = tx.send(ScpActivityWsEvent::Connected);
    let (_write, mut read) = ws_stream.split();

    while let Some(msg_result) = read.next().await {
        match msg_result {
            Ok(WsMessage::Text(text)) => match serde_json::from_str::<WsServerMessage>(&text) {
                Ok(WsServerMessage::Init { events, .. }) => {
                    let _ = tx.send(ScpActivityWsEvent::Initial { events });
                }
                Ok(WsServerMessage::Event { event, .. }) => {
                    let _ = tx.send(ScpActivityWsEvent::Event(event));
                }
                Ok(WsServerMessage::Connected { .. })
                | Ok(WsServerMessage::Heartbeat { .. })
                | Ok(WsServerMessage::Pong { .. }) => {}
                Err(e) => {
                    log::debug!("[scp_activity_ws] skip frame: {} — {}", e, text);
                }
            },
            Ok(WsMessage::Close(_)) => return Ok(()),
            Err(tungstenite::Error::Protocol(e)) => {
                return Err(format!("protocol: {}", e));
            }
            Err(e) => return Err(format!("read: {}", e)),
            _ => {}
        }
    }

    Ok(())
}

/// Stream adapter for `Subscription::run` (Infallible item type).
pub fn scp_activity_ws_worker(
    url: Option<String>,
) -> impl iced::futures::Stream<Item = ScpActivityWsEvent> {
    iced::stream::channel(
        100,
        move |mut output: iced::futures::channel::mpsc::Sender<ScpActivityWsEvent>| {
            let url = url.clone();
            async move {
                use iced::futures::SinkExt;
                let mut rx = spawn_scp_activity_websocket(url);
                while let Some(ev) = rx.recv().await {
                    let _ = output.send(ev).await;
                }
            }
        },
    )
}
