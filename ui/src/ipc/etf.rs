//! ETF (Erlang Term Format) Encoding/Decoding
//!
//! This module provides both ETF and JSON encoding for IPC communication
//! with the Elixir Studio Core. ETF is the native binary format used by
//! Erlang/Elixir for inter-node communication.
//!
//! ## Protocol Format
//!
//! Messages are framed with a 4-byte big-endian length prefix:
//! ```text
//! +--------+----------------+
//! | length | ETF payload    |
//! | 4 bytes| variable       |
//! +--------+----------------+
//! ```
//!
//! ## ETF Term Structure
//!
//! Commands from UI to Core:
//! ```erlang
//! {:command, :ping, %{}}
//! {:command, :harness_start, %{type: "cursor"}}
//! {:command, :agent_message, %{text: "...", provider: "cursor"}}
//! ```
//!
//! Events from Core to UI:
//! ```erlang
//! {:event, :pong, %{}}
//! {:event, :harness_status, %{harness: "cursor", status: "running"}}
//! {:event, :agent_response, %{content: "...", role: "assistant"}}
//! ```
//!
//! ## Encoding Strategy
//!
//! The module supports two encoding modes:
//! - **ETF mode** (default): Uses Erlang External Term Format for efficient
//!   communication with Elixir backend. Atoms are properly encoded.
//! - **JSON mode** (fallback): Uses JSON for debugging and compatibility.

use erlang::{OtpErlangTerm, term_to_binary, binary_to_term, Float};
use super::{Command, Event, IpcError};
use std::collections::BTreeMap;
use tracing::{debug, warn};

/// Encoding format for IPC messages
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum EncodingFormat {
    /// Erlang Term Format - native binary encoding
    #[default]
    Etf,
    /// JSON fallback for debugging
    Json,
}

// ============================================================================
// ETF Encoding (Commands: UI → Core)
// ============================================================================

/// Encode a command to ETF binary format
pub fn encode_command(cmd: &Command) -> Result<Vec<u8>, IpcError> {
    let term = command_to_term(cmd);
    term_to_binary(&term)
        .map_err(|e| IpcError::Encoding(format!("ETF encode error: {}", e)))
}

/// Encode a command to JSON (fallback format)
pub fn encode_command_json(cmd: &Command) -> Result<Vec<u8>, IpcError> {
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

/// Convert a Command to an OtpErlangTerm
fn command_to_term(cmd: &Command) -> OtpErlangTerm {
    match cmd {
        Command::HarnessStart { harness_type } => {
            make_command_tuple("harness_start", vec![
                ("type", OtpErlangTerm::OtpErlangBinary(harness_type.as_bytes().to_vec())),
            ])
        }
        Command::HarnessStop { harness_type } => {
            make_command_tuple("harness_stop", vec![
                ("type", OtpErlangTerm::OtpErlangBinary(harness_type.as_bytes().to_vec())),
            ])
        }
        Command::StateSet { path, value } => {
            make_command_tuple("state_set", vec![
                ("path", list_from_strings(path)),
                ("value", json_to_term(value)),
            ])
        }
        Command::AgentMessage { text, provider } => {
            make_command_tuple("agent_message", vec![
                ("text", OtpErlangTerm::OtpErlangBinary(text.as_bytes().to_vec())),
                ("provider", OtpErlangTerm::OtpErlangBinary(provider.as_bytes().to_vec())),
            ])
        }
        Command::Ping => {
            make_command_tuple("ping", vec![])
        }
    }
}

/// Create a command tuple: {:command, :name, %{params}}
fn make_command_tuple(name: &str, params: Vec<(&str, OtpErlangTerm)>) -> OtpErlangTerm {
    let mut map: BTreeMap<OtpErlangTerm, OtpErlangTerm> = BTreeMap::new();
    for (k, v) in params {
        map.insert(atom(k), v);
    }
    
    OtpErlangTerm::OtpErlangTuple(vec![
        atom("command"),
        atom(name),
        OtpErlangTerm::OtpErlangMap(map),
    ])
}

/// Create an atom from a string
fn atom(s: &str) -> OtpErlangTerm {
    OtpErlangTerm::OtpErlangAtomUTF8(s.as_bytes().to_vec())
}

/// Convert a list of strings to an Erlang list
fn list_from_strings(strings: &[String]) -> OtpErlangTerm {
    let terms: Vec<OtpErlangTerm> = strings
        .iter()
        .map(|s| OtpErlangTerm::OtpErlangBinary(s.as_bytes().to_vec()))
        .collect();
    OtpErlangTerm::OtpErlangList(terms)
}

/// Convert a serde_json::Value to an OtpErlangTerm
fn json_to_term(value: &serde_json::Value) -> OtpErlangTerm {
    match value {
        serde_json::Value::Null => atom("nil"),
        serde_json::Value::Bool(b) => OtpErlangTerm::OtpErlangAtomBool(*b),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                if i >= i32::MIN as i64 && i <= i32::MAX as i64 {
                    OtpErlangTerm::OtpErlangInteger(i as i32)
                } else {
                    // For larger integers, use binary encoding
                    OtpErlangTerm::OtpErlangBinary(i.to_string().as_bytes().to_vec())
                }
            } else if let Some(f) = n.as_f64() {
                OtpErlangTerm::OtpErlangFloat(Float::from(f))
            } else {
                atom("undefined")
            }
        }
        serde_json::Value::String(s) => {
            OtpErlangTerm::OtpErlangBinary(s.as_bytes().to_vec())
        }
        serde_json::Value::Array(arr) => {
            let terms: Vec<OtpErlangTerm> = arr.iter().map(json_to_term).collect();
            OtpErlangTerm::OtpErlangList(terms)
        }
        serde_json::Value::Object(obj) => {
            let mut map: BTreeMap<OtpErlangTerm, OtpErlangTerm> = BTreeMap::new();
            for (k, v) in obj {
                map.insert(atom(k), json_to_term(v));
            }
            OtpErlangTerm::OtpErlangMap(map)
        }
    }
}

// ============================================================================
// ETF Decoding (Events: Core → UI)
// ============================================================================

/// Decode a binary payload to an Event
/// 
/// Tries ETF first, then falls back to JSON
pub fn decode_event(payload: &[u8]) -> Result<Option<Event>, IpcError> {
    // Try ETF first
    match binary_to_term(payload) {
        Ok(term) => {
            debug!("Decoded ETF term: {:?}", term);
            return term_to_event(&term);
        }
        Err(e) => {
            debug!("ETF decode failed (trying JSON): {}", e);
        }
    }
    
    // Fall back to JSON
    decode_event_json(payload)
}

/// Decode a JSON payload to an Event
pub fn decode_event_json(payload: &[u8]) -> Result<Option<Event>, IpcError> {
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
        
        "harness_registered" => Some(Event::HarnessRegistered {
            harness: data.get("harness").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            harness_type: data.get("type").and_then(|v| v.as_str()).unwrap_or("unknown").to_string(),
        }),
        
        "harness_disconnected" => Some(Event::HarnessDisconnected {
            harness: data.get("harness").and_then(|v| v.as_str()).unwrap_or("").to_string(),
            reason: data.get("reason").and_then(|v| v.as_str()).unwrap_or("unknown").to_string(),
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
        
        _ => {
            warn!("Unknown event type: {}", event_type);
            None
        }
    };
    
    Ok(event)
}

/// Convert an OtpErlangTerm to an Event
fn term_to_event(term: &OtpErlangTerm) -> Result<Option<Event>, IpcError> {
    // Expected format: {:event, :event_type, %{data}}
    if let OtpErlangTerm::OtpErlangTuple(elements) = term {
        if elements.len() >= 3 {
            // Check for :event atom
            if !is_atom_matching(&elements[0], "event") {
                return Ok(None);
            }
            
            // Get event type
            let event_type = match get_atom_string(&elements[1]) {
                Some(s) => s,
                None => return Ok(None),
            };
            
            // Get data map
            let data = &elements[2];
            
            return match event_type.as_str() {
                "harness_status" => {
                    let harness = get_map_string(data, "harness").unwrap_or_default();
                    let status = get_map_string(data, "status").unwrap_or_else(|| "unknown".to_string());
                    Ok(Some(Event::HarnessStatus { harness, status }))
                }
                
                "state_changed" => {
                    let path = get_map_string_list(data, "path").unwrap_or_default();
                    let value = get_map_json_value(data, "value");
                    Ok(Some(Event::StateChanged { path, value }))
                }
                
                "agent_response" => {
                    let content = get_map_string(data, "content").unwrap_or_default();
                    let role = get_map_string(data, "role").unwrap_or_else(|| "assistant".to_string());
                    Ok(Some(Event::AgentResponse { content, role }))
                }
                
                "pong" => Ok(Some(Event::Pong)),
                
                "error" => {
                    let message = get_map_string(data, "message")
                        .unwrap_or_else(|| "Unknown error".to_string());
                    Ok(Some(Event::Error { message }))
                }
                
                _ => {
                    warn!("Unknown ETF event type: {}", event_type);
                    Ok(None)
                }
            };
        }
    }
    
    Ok(None)
}

// ============================================================================
// Helper Functions for ETF Term Access
// ============================================================================

/// Check if a term is an atom matching the given string
fn is_atom_matching(term: &OtpErlangTerm, expected: &str) -> bool {
    get_atom_string(term).map(|s| s == expected).unwrap_or(false)
}

/// Get the string value of an atom
fn get_atom_string(term: &OtpErlangTerm) -> Option<String> {
    match term {
        OtpErlangTerm::OtpErlangAtomUTF8(b) | OtpErlangTerm::OtpErlangAtom(b) => {
            String::from_utf8(b.clone()).ok()
        }
        _ => None,
    }
}

/// Get a string value from an ETF map by key
fn get_map_string(term: &OtpErlangTerm, key: &str) -> Option<String> {
    if let OtpErlangTerm::OtpErlangMap(entries) = term {
        for (k, v) in entries {
            if matches_key(k, key) {
                return term_to_string(v);
            }
        }
    }
    None
}

/// Get a list of strings from an ETF map by key
fn get_map_string_list(term: &OtpErlangTerm, key: &str) -> Option<Vec<String>> {
    if let OtpErlangTerm::OtpErlangMap(entries) = term {
        for (k, v) in entries {
            if matches_key(k, key) {
                if let OtpErlangTerm::OtpErlangList(items) = v {
                    return Some(items.iter().filter_map(term_to_string).collect());
                }
            }
        }
    }
    None
}

/// Get a JSON value from an ETF map by key
fn get_map_json_value(term: &OtpErlangTerm, key: &str) -> serde_json::Value {
    if let OtpErlangTerm::OtpErlangMap(entries) = term {
        for (k, v) in entries {
            if matches_key(k, key) {
                return term_to_json(v);
            }
        }
    }
    serde_json::Value::Null
}

/// Check if a term matches a key (atom or binary)
fn matches_key(term: &OtpErlangTerm, key: &str) -> bool {
    match term {
        OtpErlangTerm::OtpErlangAtomUTF8(b) | OtpErlangTerm::OtpErlangAtom(b) => {
            b == key.as_bytes()
        }
        OtpErlangTerm::OtpErlangBinary(b) | OtpErlangTerm::OtpErlangString(b) => {
            b == key.as_bytes()
        }
        _ => false,
    }
}

/// Convert an ETF term to a String
fn term_to_string(term: &OtpErlangTerm) -> Option<String> {
    match term {
        OtpErlangTerm::OtpErlangBinary(b) | OtpErlangTerm::OtpErlangString(b) => {
            String::from_utf8(b.clone()).ok()
        }
        OtpErlangTerm::OtpErlangAtomUTF8(b) | OtpErlangTerm::OtpErlangAtom(b) => {
            String::from_utf8(b.clone()).ok()
        }
        _ => None,
    }
}

/// Convert an ETF term to a serde_json::Value
fn term_to_json(term: &OtpErlangTerm) -> serde_json::Value {
    match term {
        OtpErlangTerm::OtpErlangAtomUTF8(b) | OtpErlangTerm::OtpErlangAtom(b) => {
            match String::from_utf8(b.clone()) {
                Ok(s) => match s.as_str() {
                    "nil" | "null" => serde_json::Value::Null,
                    "true" => serde_json::Value::Bool(true),
                    "false" => serde_json::Value::Bool(false),
                    _ => serde_json::Value::String(s),
                },
                Err(_) => serde_json::Value::Null,
            }
        }
        OtpErlangTerm::OtpErlangAtomBool(b) => serde_json::Value::Bool(*b),
        OtpErlangTerm::OtpErlangBinary(b) | OtpErlangTerm::OtpErlangString(b) => {
            match String::from_utf8(b.clone()) {
                Ok(s) => serde_json::Value::String(s),
                Err(_) => serde_json::Value::Array(
                    b.iter().map(|&byte| serde_json::Value::Number(byte.into())).collect()
                ),
            }
        }
        OtpErlangTerm::OtpErlangInteger(i) => serde_json::Value::Number((*i as i64).into()),
        OtpErlangTerm::OtpErlangFloat(f) => {
            serde_json::Number::from_f64(f.value())
                .map(serde_json::Value::Number)
                .unwrap_or(serde_json::Value::Null)
        }
        OtpErlangTerm::OtpErlangList(items) => {
            serde_json::Value::Array(items.iter().map(term_to_json).collect())
        }
        OtpErlangTerm::OtpErlangMap(entries) => {
            let obj: serde_json::Map<String, serde_json::Value> = entries
                .iter()
                .filter_map(|(k, v)| {
                    term_to_string(k).map(|key| (key, term_to_json(v)))
                })
                .collect();
            serde_json::Value::Object(obj)
        }
        OtpErlangTerm::OtpErlangTuple(items) => {
            // Represent tuples as arrays with a type marker
            let mut arr: Vec<serde_json::Value> = vec![
                serde_json::Value::String("__tuple__".into())
            ];
            arr.extend(items.iter().map(term_to_json));
            serde_json::Value::Array(arr)
        }
        _ => serde_json::Value::Null,
    }
}

// ============================================================================
// Tests
// ============================================================================

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_encode_ping_etf() {
        let cmd = Command::Ping;
        let bytes = encode_command(&cmd).unwrap();
        
        // Decode and verify
        let decoded = binary_to_term(&bytes).unwrap();
        if let OtpErlangTerm::OtpErlangTuple(elements) = decoded {
            assert_eq!(elements.len(), 3);
            assert!(is_atom_matching(&elements[0], "command"));
            assert!(is_atom_matching(&elements[1], "ping"));
        } else {
            panic!("Expected tuple, got {:?}", decoded);
        }
    }
    
    #[test]
    fn test_encode_harness_start_etf() {
        let cmd = Command::HarnessStart { harness_type: "cursor".to_string() };
        let bytes = encode_command(&cmd).unwrap();
        
        let decoded = binary_to_term(&bytes).unwrap();
        if let OtpErlangTerm::OtpErlangTuple(elements) = decoded {
            assert_eq!(elements.len(), 3);
            assert!(is_atom_matching(&elements[1], "harness_start"));
            
            // Check params map
            if let OtpErlangTerm::OtpErlangMap(entries) = &elements[2] {
                assert_eq!(entries.len(), 1);
            }
        } else {
            panic!("Expected tuple");
        }
    }
    
    #[test]
    fn test_encode_json_fallback() {
        let cmd = Command::Ping;
        let bytes = encode_command_json(&cmd).unwrap();
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["command"], "ping");
    }
    
    #[test]
    fn test_decode_harness_status_json() {
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
    fn test_decode_pong_json() {
        let payload = br#"{"event": "pong", "data": {}}"#;
        let event = decode_event(payload).unwrap().unwrap();
        assert!(matches!(event, Event::Pong));
    }
    
    #[test]
    fn test_roundtrip_etf() {
        // Create an ETF event tuple manually
        let mut data_map: BTreeMap<OtpErlangTerm, OtpErlangTerm> = BTreeMap::new();
        data_map.insert(atom("harness"), OtpErlangTerm::OtpErlangBinary("cursor".as_bytes().to_vec()));
        data_map.insert(atom("status"), OtpErlangTerm::OtpErlangBinary("running".as_bytes().to_vec()));
        
        let event_term = OtpErlangTerm::OtpErlangTuple(vec![
            atom("event"),
            atom("harness_status"),
            OtpErlangTerm::OtpErlangMap(data_map),
        ]);
        
        let bytes = term_to_binary(&event_term).unwrap();
        let event = decode_event(&bytes).unwrap().unwrap();
        
        match event {
            Event::HarnessStatus { harness, status } => {
                assert_eq!(harness, "cursor");
                assert_eq!(status, "running");
            }
            _ => panic!("Wrong event type: {:?}", event),
        }
    }
    
    #[test]
    fn test_json_to_term_conversion() {
        let json = serde_json::json!({
            "name": "test",
            "count": 42,
            "enabled": true,
            "items": ["a", "b", "c"]
        });
        
        let term = json_to_term(&json);
        
        // Verify it's a map
        assert!(matches!(term, OtpErlangTerm::OtpErlangMap(_)));
    }
}
