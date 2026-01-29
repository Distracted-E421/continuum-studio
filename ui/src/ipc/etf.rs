//! ETF (Erlang Term Format) Encoding/Decoding
//!
//! Currently uses JSON as a fallback protocol for simpler implementation.
//! The Elixir Core supports both ETF and JSON.
//!
//! ## Protocol Format
//!
//! Commands from UI to Core (JSON):
//! ```json
//! {"command": "ping", "params": {}}
//! {"command": "harness_start", "params": {"type": "cursor"}}
//! ```
//!
//! Events from Core to UI (JSON):
//! ```json
//! {"event": "pong", "data": {}}
//! {"event": "harness_status", "data": {"harness": "cursor", "status": "running"}}
//! ```

use super::{Command, Event, IpcError};

/// Encode a command to binary (JSON for now)
pub fn encode_command(cmd: &Command) -> Result<Vec<u8>, IpcError> {
    let json = match cmd {
        Command::HarnessStart { harness_type } => {
            serde_json::json!({
                "command": "harness_start",
                "params": { "type": harness_type }
            })
        }
        Command::HarnessStop { harness_type } => {
            serde_json::json!({
                "command": "harness_stop",
                "params": { "type": harness_type }
            })
        }
        Command::StateSet { path, value } => {
            serde_json::json!({
                "command": "state_set",
                "params": { "path": path, "value": value }
            })
        }
        Command::AgentMessage { text, provider } => {
            serde_json::json!({
                "command": "agent_message",
                "params": { "text": text, "provider": provider }
            })
        }
        Command::Ping => {
            serde_json::json!({
                "command": "ping",
                "params": {}
            })
        }
    };
    
    serde_json::to_vec(&json)
        .map_err(|e| IpcError::Encoding(format!("JSON encode error: {}", e)))
}

/// Decode a binary payload to an Event (JSON for now)
pub fn decode_event(payload: &[u8]) -> Result<Option<Event>, IpcError> {
    let json: serde_json::Value = serde_json::from_slice(payload)
        .map_err(|e| IpcError::Decoding(format!("JSON decode error: {}", e)))?;
    
    let event_type = match json.get("event").and_then(|v| v.as_str()) {
        Some(t) => t,
        None => return Ok(None),
    };
    
    let data = json.get("data").cloned().unwrap_or(serde_json::json!({}));
    
    let event = match event_type {
        "harness_status" => Some(Event::HarnessStatus {
            harness: data.get("harness").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            status: data.get("status").and_then(|v| v.as_str()).unwrap_or("unknown").to_string(),
        }),
        
        "state_changed" => Some(Event::StateChanged {
            path: data.get("path")
                .and_then(|v| v.as_array())
                .map(|arr| arr.iter().filter_map(|v| v.as_str().map(String::from)).collect())
                .unwrap_or_default(),
            value: data.get("value").cloned().unwrap_or(serde_json::Value::Null),
        }),
        
        "agent_response" => Some(Event::AgentResponse {
            content: data.get("content").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            role: data.get("role").and_then(|v| v.as_str()).unwrap_or("assistant").to_string(),
        }),
        
        "pong" => Some(Event::Pong),
        
        "error" => Some(Event::Error {
            message: data.get("message").and_then(|v| v.as_str()).unwrap_or("Unknown error").to_string(),
        }),
        
        _ => None,
    };
    
    Ok(event)
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_encode_ping() {
        let cmd = Command::Ping;
        let bytes = encode_command(&cmd).unwrap();
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["command"], "ping");
    }
    
    #[test]
    fn test_encode_harness_start() {
        let cmd = Command::HarnessStart { harness_type: "cursor".to_string() };
        let bytes = encode_command(&cmd).unwrap();
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["command"], "harness_start");
        assert_eq!(json["params"]["type"], "cursor");
    }
    
    #[test]
    fn test_decode_harness_status() {
        let payload = br#"{"event": "harness_status", "data": {"harness": "cursor", "status": "running"}}"#;
        let event = decode_event(payload).unwrap().unwrap();
        match event {
            Event::HarnessStatus { harness, status } => {
                assert_eq!(harness, "cursor");
                assert_eq!(status, "running");
            }
            _ => panic!("Wrong event type"),
        }
    }
    
    #[test]
    fn test_decode_pong() {
        let payload = br#"{"event": "pong", "data": {}}"#;
        let event = decode_event(payload).unwrap().unwrap();
        assert!(matches!(event, Event::Pong));
    }
}
