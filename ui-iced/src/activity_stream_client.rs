//! Activity Stream Client - Dialog Daemon WebSocket
//!
//! Connects to ws://localhost:8080/ws/activity for real-time agent activity
//! (commands, dialogs) from the synapsix-dialog daemon.

use chrono::{DateTime, Utc};
use crate::activity_feed::ActivityEvent;
use futures_util::StreamExt;
use serde::Deserialize;
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message as WsMessage;
use tokio_tungstenite::connect_async;
use url::Url;

/// Default dialog daemon WebSocket URL
pub const DEFAULT_ACTIVITY_WS_URL: &str = "ws://localhost:8080/ws/activity";

/// Raw JSON from daemon (event type + payload)
#[derive(Debug, Deserialize)]
struct ActivityPayload {
    event: String,
    #[serde(default)]
    agent_id: Option<String>,
    #[serde(default)]
    dialog_id: Option<String>,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    selection: Option<String>,
    #[serde(default)]
    command: Option<String>,
    #[serde(default)]
    exit_code: Option<i32>,
    #[serde(default)]
    duration_ms: Option<u64>,
    #[serde(default)]
    timestamp: Option<String>,
}

/// Parse RFC3339 or use Utc::now on failure
fn parse_timestamp(s: Option<&str>) -> DateTime<Utc> {
    s.and_then(|s| DateTime::parse_from_rfc3339(s).ok())
        .map(|dt| dt.with_timezone(&Utc))
        .unwrap_or_else(Utc::now)
}

/// Spawn WebSocket connection to dialog daemon's /ws/activity.
/// Emits ActivityEvent for command, dialog_sent, dialog_response.
/// Reconnects with 5s backoff on disconnect.
pub async fn spawn_activity_stream() -> mpsc::UnboundedReceiver<ActivityEvent> {
    let (tx, rx) = mpsc::unbounded_channel();
    let url = Url::parse(DEFAULT_ACTIVITY_WS_URL).expect("valid URL");

    tokio::spawn(async move {
        loop {
            if let Err(e) = connect_and_handle(&url, tx.clone()).await {
                log::warn!("[activity_stream] disconnected: {}; reconnect in 5s", e);
            }
            tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
        }
    });

    rx
}

async fn connect_and_handle(
    url: &Url,
    tx: mpsc::UnboundedSender<ActivityEvent>,
) -> Result<(), String> {
    let (ws_stream, _) = connect_async(url.as_str())
        .await
        .map_err(|e| format!("Activity stream connect failed: {}", e))?;

    let (_write, mut read) = ws_stream.split();

    while let Some(msg_result) = read.next().await {
        match msg_result {
            Ok(WsMessage::Text(text)) => {
                if let Ok(payload) = serde_json::from_str::<ActivityPayload>(&text) {
                    if let Some(ev) = payload_to_activity_event(payload) {
                        let _ = tx.send(ev);
                    }
                }
            }
            Ok(WsMessage::Close(_)) => return Ok(()),
            Err(e) => return Err(format!("read error: {}", e)),
            _ => {}
        }
    }

    Ok(())
}

fn payload_to_activity_event(p: ActivityPayload) -> Option<ActivityEvent> {
    let ts = parse_timestamp(p.timestamp.as_deref());
    match p.event.as_str() {
        "command" => {
            let agent_id = p.agent_id.unwrap_or_else(|| "terminal".to_string());
            let command = p.command.unwrap_or_default();
            Some(ActivityEvent::Command {
                agent_id,
                command,
                exit_code: p.exit_code,
                duration_ms: p.duration_ms,
                timestamp: ts,
            })
        }
        "dialog_sent" => {
            let agent_id = p.agent_id.unwrap_or_else(|| "orchestrator".to_string());
            let dialog_id = p.dialog_id.unwrap_or_default();
            let title = p.title.unwrap_or_default();
            Some(ActivityEvent::DialogSent {
                agent_id,
                dialog_id,
                title,
                timestamp: ts,
            })
        }
        "dialog_response" => {
            let dialog_id = p.dialog_id.unwrap_or_default();
            let selection = p.selection.unwrap_or_default();
            Some(ActivityEvent::DialogResponse {
                dialog_id,
                selection,
                timestamp: ts,
            })
        }
        _ => None,
    }
}
