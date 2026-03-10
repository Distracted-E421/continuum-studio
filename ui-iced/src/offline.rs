//! Offline mode support for Continuum Studio
//!
//! Provides operation queuing and sync when backend reconnects.

use serde::{Deserialize, Serialize};
use std::collections::VecDeque;
use std::path::PathBuf;
use std::time::SystemTime;

/// Maximum queued operations before oldest are dropped
const MAX_QUEUE_SIZE: usize = 1000;

/// An operation that was performed while offline
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueuedOperation {
    /// Unique operation ID
    pub id: String,
    /// When the operation was created
    pub timestamp: SystemTime,
    /// Type of operation
    pub operation: OperationType,
    /// Number of retry attempts
    pub retry_count: u32,
    /// Last error message (if any)
    pub last_error: Option<String>,
}

/// Types of operations that can be queued
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum OperationType {
    /// Task queue item addition
    TaskAdd {
        title: String,
        description: Option<String>,
        priority: String,
    },
    /// Task status update
    TaskUpdate {
        task_id: String,
        status: String,
    },
    /// Dialog response (cached for later sync)
    DialogResponse {
        dialog_id: String,
        selection: serde_json::Value,
        comment: Option<String>,
    },
    /// Settings sync request
    SettingsSync {
        settings_json: String,
    },
    /// Session action (start, stop, etc.)
    SessionAction {
        session_id: String,
        action: String,
    },
    /// Custom operation (for extensibility)
    Custom {
        name: String,
        payload: serde_json::Value,
    },
}

/// Offline mode state
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum OfflineState {
    /// Connected to all backends
    Online,
    /// Some backends unavailable
    PartiallyOffline,
    /// All backends unavailable
    FullyOffline,
}

/// Offline operation queue manager
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OfflineQueue {
    /// Queued operations
    operations: VecDeque<QueuedOperation>,
    /// Current offline state
    state: OfflineState,
    /// Last sync timestamp
    last_sync: Option<SystemTime>,
    /// Sync in progress
    sync_in_progress: bool,
}

impl Default for OfflineQueue {
    fn default() -> Self {
        Self::new()
    }
}

impl OfflineQueue {
    pub fn new() -> Self {
        Self {
            operations: VecDeque::new(),
            state: OfflineState::Online,
            last_sync: None,
            sync_in_progress: false,
        }
    }

    /// Queue an operation for later sync
    pub fn queue(&mut self, operation: OperationType) -> String {
        // Generate unique ID
        let id = format!(
            "op-{}-{}",
            SystemTime::now()
                .duration_since(SystemTime::UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0),
            self.operations.len()
        );

        let queued = QueuedOperation {
            id: id.clone(),
            timestamp: SystemTime::now(),
            operation,
            retry_count: 0,
            last_error: None,
        };

        // Enforce max queue size
        if self.operations.len() >= MAX_QUEUE_SIZE {
            self.operations.pop_front();
        }

        self.operations.push_back(queued);
        log::info!("Queued offline operation: {}", id);
        id
    }

    /// Get number of pending operations
    pub fn pending_count(&self) -> usize {
        self.operations.len()
    }

    /// Check if queue is empty
    pub fn is_empty(&self) -> bool {
        self.operations.is_empty()
    }

    /// Get current offline state
    pub fn state(&self) -> OfflineState {
        self.state
    }

    /// Update offline state
    pub fn set_state(&mut self, state: OfflineState) {
        if self.state != state {
            log::info!("Offline state changed: {:?} -> {:?}", self.state, state);
            self.state = state;
        }
    }

    /// Get all pending operations
    pub fn pending_operations(&self) -> Vec<&QueuedOperation> {
        self.operations.iter().collect()
    }

    /// Mark an operation as completed and remove it
    pub fn complete(&mut self, id: &str) {
        self.operations.retain(|op| op.id != id);
        self.last_sync = Some(SystemTime::now());
    }

    /// Mark an operation as failed
    pub fn mark_failed(&mut self, id: &str, error: String) {
        if let Some(op) = self.operations.iter_mut().find(|op| op.id == id) {
            op.retry_count += 1;
            op.last_error = Some(error);
        }
    }

    /// Remove operations that have exceeded max retries
    pub fn prune_failed(&mut self, max_retries: u32) {
        let before = self.operations.len();
        self.operations.retain(|op| op.retry_count < max_retries);
        let removed = before - self.operations.len();
        if removed > 0 {
            log::warn!("Pruned {} failed operations from queue", removed);
        }
    }

    /// Get operations ready for sync (ordered by timestamp)
    pub fn get_sync_batch(&self, limit: usize) -> Vec<&QueuedOperation> {
        self.operations.iter().take(limit).collect()
    }

    /// Clear all pending operations
    pub fn clear(&mut self) {
        self.operations.clear();
        log::info!("Cleared offline operation queue");
    }

    /// Get file path for persisting queue
    pub fn file_path() -> PathBuf {
        let config_dir =
            dirs::config_dir().unwrap_or_else(|| PathBuf::from("."));
        config_dir
            .join("continuum-studio")
            .join("offline-queue.json")
    }

    /// Load queue from disk
    pub fn load() -> Self {
        let path = Self::file_path();
        if path.exists() {
            match std::fs::read_to_string(&path) {
                Ok(content) => match serde_json::from_str(&content) {
                    Ok(queue) => {
                        log::info!("Loaded offline queue from {:?}", path);
                        return queue;
                    }
                    Err(e) => {
                        log::warn!("Failed to parse offline queue: {}", e);
                    }
                },
                Err(e) => {
                    log::warn!("Failed to read offline queue: {}", e);
                }
            }
        }
        Self::new()
    }

    /// Save queue to disk
    pub fn save(&self) -> Result<(), String> {
        let path = Self::file_path();

        if let Some(parent) = path.parent() {
            if !parent.exists() {
                std::fs::create_dir_all(parent)
                    .map_err(|e| format!("Failed to create config dir: {}", e))?;
            }
        }

        let content = serde_json::to_string_pretty(self)
            .map_err(|e| format!("Failed to serialize queue: {}", e))?;

        std::fs::write(&path, content)
            .map_err(|e| format!("Failed to write queue: {}", e))?;

        log::debug!("Saved offline queue to {:?}", path);
        Ok(())
    }

    /// Get last sync timestamp
    pub fn last_sync(&self) -> Option<SystemTime> {
        self.last_sync
    }

    /// Check if sync is in progress
    pub fn is_syncing(&self) -> bool {
        self.sync_in_progress
    }

    /// Set sync in progress flag
    pub fn set_syncing(&mut self, syncing: bool) {
        self.sync_in_progress = syncing;
    }
}

/// Connection status tracker for multiple backends
#[derive(Debug, Clone, Default)]
pub struct ConnectionTracker {
    /// Core backend connection
    pub core_connected: bool,
    /// Dialog daemon connection
    pub dialog_connected: bool,
    /// Task queue connection
    pub task_queue_connected: bool,
}

impl ConnectionTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Compute overall offline state
    pub fn offline_state(&self) -> OfflineState {
        if self.core_connected && self.dialog_connected && self.task_queue_connected {
            OfflineState::Online
        } else if !self.core_connected && !self.dialog_connected && !self.task_queue_connected {
            OfflineState::FullyOffline
        } else {
            OfflineState::PartiallyOffline
        }
    }

    /// Get human-readable status
    pub fn status_text(&self) -> String {
        match self.offline_state() {
            OfflineState::Online => "All services connected".to_string(),
            OfflineState::FullyOffline => "Offline - operations will be queued".to_string(),
            OfflineState::PartiallyOffline => {
                let mut disconnected = Vec::new();
                if !self.core_connected {
                    disconnected.push("Core");
                }
                if !self.dialog_connected {
                    disconnected.push("Dialog");
                }
                if !self.task_queue_connected {
                    disconnected.push("Task Queue");
                }
                format!("Partial - {} disconnected", disconnected.join(", "))
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_queue_operations() {
        let mut queue = OfflineQueue::new();

        let id = queue.queue(OperationType::TaskAdd {
            title: "Test task".to_string(),
            description: None,
            priority: "normal".to_string(),
        });

        assert_eq!(queue.pending_count(), 1);
        assert!(!queue.is_empty());

        queue.complete(&id);
        assert!(queue.is_empty());
    }

    #[test]
    fn test_offline_state_computation() {
        let mut tracker = ConnectionTracker::new();
        assert_eq!(tracker.offline_state(), OfflineState::FullyOffline);

        tracker.core_connected = true;
        assert_eq!(tracker.offline_state(), OfflineState::PartiallyOffline);

        tracker.dialog_connected = true;
        tracker.task_queue_connected = true;
        assert_eq!(tracker.offline_state(), OfflineState::Online);
    }

    #[test]
    fn test_max_queue_size() {
        let mut queue = OfflineQueue::new();

        // Fill beyond max
        for i in 0..MAX_QUEUE_SIZE + 10 {
            queue.queue(OperationType::Custom {
                name: format!("op-{}", i),
                payload: serde_json::json!({}),
            });
        }

        assert_eq!(queue.pending_count(), MAX_QUEUE_SIZE);
    }

    #[test]
    fn test_retry_tracking() {
        let mut queue = OfflineQueue::new();
        let id = queue.queue(OperationType::SettingsSync {
            settings_json: "{}".to_string(),
        });

        queue.mark_failed(&id, "Network error".to_string());
        queue.mark_failed(&id, "Timeout".to_string());

        let ops = queue.pending_operations();
        assert_eq!(ops[0].retry_count, 2);
        assert_eq!(ops[0].last_error, Some("Timeout".to_string()));

        queue.prune_failed(2);
        assert!(queue.is_empty());
    }
}
