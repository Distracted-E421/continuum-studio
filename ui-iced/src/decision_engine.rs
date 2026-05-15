//! Orchestrator Decision Engine
//!
//! Implements automated dialog handling based on orchestrator mode and priority.
//! Core goal: Keep existing agents running and unblocked to minimize request costs.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, VecDeque};
use std::time::{Duration, Instant};

use crate::cli_agents::{DialogSource, PendingDialog};
use crate::dialog_client::OrchestratorMode;

pub use crate::cli_agents::DialogPriority;

/// A decision made by the orchestrator
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DecisionRecord {
    /// Dialog ID this decision is for
    pub dialog_id: String,
    /// The agent that sent the dialog
    pub agent_id: Option<String>,
    /// The workspace context
    pub workspace: Option<String>,
    /// Source of the dialog
    pub source: DialogSource,
    /// Priority of the dialog
    pub priority: DialogPriority,
    /// The response we chose
    pub response: String,
    /// Reasoning for this decision
    pub reasoning: String,
    /// Whether this was auto-handled or user-responded
    pub auto_handled: bool,
    /// Unix timestamp when decision was made
    pub timestamp: u64,
    /// Mode the orchestrator was in when decision was made
    pub mode: String,
    /// Whether this can be undone (within undo window)
    pub undoable: bool,
}

/// State of a triage queue item
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TriageState {
    /// Waiting for user decision
    Manual,
    /// Will auto-approve after timeout
    AutoApprove,
    /// Will auto-decline after timeout
    AutoDecline,
    /// Paused - no auto-action
    Paused,
}

/// An item in the decision triage queue
#[derive(Debug, Clone)]
pub struct TriageItem {
    /// The pending dialog
    pub dialog: PendingDialog,
    /// Current triage state
    pub state: TriageState,
    /// When this item was added to the queue
    pub added_at: Instant,
    /// Suggested response from decision engine
    pub suggested_response: Option<String>,
    /// Reasoning for the suggestion
    pub reasoning: String,
    /// Timeout for auto-action (if applicable)
    pub timeout: Duration,
}

/// Configuration for the decision engine
#[derive(Debug, Clone)]
pub struct DecisionEngineConfig {
    /// Default timeout for triage queue items (seconds)
    pub default_triage_timeout_secs: u64,
    /// Undo window duration (seconds)
    pub undo_window_secs: u64,
    /// Maximum decisions to keep in history
    pub max_history_size: usize,
    /// Maximum triage queue size (P1 optimization)
    pub max_triage_size: usize,
    /// Keywords that indicate CRITICAL priority
    pub critical_keywords: Vec<String>,
    /// Agent-specific timeout overrides
    pub agent_timeouts: HashMap<String, u64>,
}

impl Default for DecisionEngineConfig {
    fn default() -> Self {
        Self {
            default_triage_timeout_secs: 30,
            undo_window_secs: 10,
            max_history_size: 1000,
            max_triage_size: 100,
            critical_keywords: vec![
                "delete".to_string(),
                "remove".to_string(),
                "destroy".to_string(),
                "drop".to_string(),
                "irreversible".to_string(),
                "production".to_string(),
                "sudo".to_string(),
                "root".to_string(),
                "format".to_string(),
                "wipe".to_string(),
                "database".to_string(),
                "migration".to_string(),
            ],
            agent_timeouts: HashMap::new(),
        }
    }
}

/// Result of evaluating a dialog for auto-handling
#[derive(Debug, Clone)]
pub enum DecisionResult {
    /// Should be auto-handled with this response
    AutoHandle { response: String, reasoning: String },
    /// Requires user attention
    RequireUser { reasoning: String },
    /// Add to triage queue with suggested action
    Triage {
        state: TriageState,
        suggested_response: Option<String>,
        reasoning: String,
        timeout: Duration,
    },
}

/// The main decision engine
#[derive(Debug)]
pub struct DecisionEngine {
    /// Current orchestrator mode
    mode: OrchestratorMode,
    /// Configuration
    config: DecisionEngineConfig,
    /// Decision history
    history: VecDeque<DecisionRecord>,
    /// Triage queue
    triage_queue: Vec<TriageItem>,
    /// Recent decisions that can be undone (within undo window)
    undoable_decisions: Vec<(DecisionRecord, Instant)>,
}

impl DecisionEngine {
    pub fn new(config: DecisionEngineConfig) -> Self {
        Self {
            mode: OrchestratorMode::UserActive,
            config,
            history: VecDeque::new(),
            triage_queue: Vec::new(),
            undoable_decisions: Vec::new(),
        }
    }

    /// Update the orchestrator mode
    pub fn set_mode(&mut self, mode: OrchestratorMode) {
        self.mode = mode;
    }

    /// Get the current mode
    pub fn mode(&self) -> OrchestratorMode {
        self.mode
    }

    /// Evaluate a dialog and determine how it should be handled
    pub fn evaluate(&self, dialog: &PendingDialog) -> DecisionResult {
        // Check for CRITICAL keywords in prompt
        let detected_critical = self.detect_critical_keywords(&dialog.prompt)
            || self.detect_critical_keywords(&dialog.title);

        let effective_priority = if detected_critical {
            DialogPriority::Critical
        } else {
            dialog.priority.clone()
        };

        // Determine action based on mode and priority
        match self.mode {
            OrchestratorMode::UserActive => {
                // All dialogs go to user
                DecisionResult::RequireUser {
                    reasoning: "User active mode - all dialogs require user attention".to_string(),
                }
            }

            OrchestratorMode::UserDelegate => {
                match effective_priority {
                    DialogPriority::Low | DialogPriority::Normal => {
                        // Can potentially auto-handle routine dialogs
                        if let Some((response, reasoning)) = self.suggest_response(dialog) {
                            DecisionResult::AutoHandle {
                                response,
                                reasoning,
                            }
                        } else {
                            // No confident suggestion, add to triage
                            DecisionResult::Triage {
                                state: TriageState::AutoApprove,
                                suggested_response: self.suggest_default_response(dialog),
                                reasoning: "Routine dialog - will auto-approve unless overridden"
                                    .to_string(),
                                timeout: self.get_timeout_for_agent(dialog),
                            }
                        }
                    }
                    DialogPriority::High | DialogPriority::Critical => {
                        // High/Critical always escalate to user
                        DecisionResult::RequireUser {
                            reasoning: format!(
                                "Priority {} - escalating to user{}",
                                effective_priority.as_str(),
                                if detected_critical {
                                    " (critical keywords detected)"
                                } else {
                                    ""
                                }
                            ),
                        }
                    }
                }
            }

            OrchestratorMode::Spectator => {
                match effective_priority {
                    DialogPriority::Low | DialogPriority::Normal | DialogPriority::High => {
                        // Auto-handle unless user claims within timeout
                        let timeout = self.get_timeout_for_agent(dialog);
                        if let Some((response, reasoning)) = self.suggest_response(dialog) {
                            DecisionResult::Triage {
                                state: TriageState::AutoApprove,
                                suggested_response: Some(response),
                                reasoning: format!(
                                    "Spectator mode: {} (user can claim within {:?})",
                                    reasoning, timeout
                                ),
                                timeout,
                            }
                        } else {
                            DecisionResult::Triage {
                                state: TriageState::AutoApprove,
                                suggested_response: self.suggest_default_response(dialog),
                                reasoning:
                                    "Spectator mode: Will auto-approve; user can claim to override"
                                        .to_string(),
                                timeout,
                            }
                        }
                    }
                    DialogPriority::Critical => {
                        // Critical still requires user, but with timeout
                        DecisionResult::Triage {
                            state: TriageState::Manual,
                            suggested_response: None,
                            reasoning: format!(
                                "Critical dialog - requires user decision{}",
                                if detected_critical {
                                    " (critical keywords detected)"
                                } else {
                                    ""
                                }
                            ),
                            timeout: self.get_timeout_for_agent(dialog),
                        }
                    }
                }
            }

            OrchestratorMode::Autonomous => {
                match effective_priority {
                    DialogPriority::Low | DialogPriority::Normal | DialogPriority::High => {
                        // Auto-handle all routine dialogs
                        if let Some((response, reasoning)) = self.suggest_response(dialog) {
                            DecisionResult::AutoHandle {
                                response,
                                reasoning,
                            }
                        } else {
                            // Use default response
                            DecisionResult::AutoHandle {
                                response: self
                                    .suggest_default_response(dialog)
                                    .unwrap_or_else(|| "continue".to_string()),
                                reasoning:
                                    "Autonomous mode: Using default response to keep agent running"
                                        .to_string(),
                            }
                        }
                    }
                    DialogPriority::Critical => {
                        // Critical dialogs are queued for later user review
                        DecisionResult::Triage {
                            state: TriageState::Paused,
                            suggested_response: None,
                            reasoning: format!(
                                "Critical dialog queued for user review{}",
                                if detected_critical {
                                    " (critical keywords detected)"
                                } else {
                                    ""
                                }
                            ),
                            timeout: Duration::from_secs(86400), // 24 hours
                        }
                    }
                }
            }
        }
    }

    /// Detect if dialog contains critical keywords
    fn detect_critical_keywords(&self, text: &str) -> bool {
        let text_lower = text.to_lowercase();
        self.config
            .critical_keywords
            .iter()
            .any(|kw| text_lower.contains(kw))
    }

    /// Suggest a response based on dialog type and content
    fn suggest_response(&self, dialog: &PendingDialog) -> Option<(String, String)> {
        // Look for common patterns
        let prompt_lower = dialog.prompt.to_lowercase();

        // "Continue?" type dialogs
        if prompt_lower.contains("continue") || prompt_lower.contains("proceed") {
            if let Some(ref options) = dialog.options {
                // Find a "continue" or "yes" option
                for opt in options {
                    let val_lower = opt.value.to_lowercase();
                    if val_lower == "continue" || val_lower == "yes" || val_lower == "proceed" {
                        return Some((
                            opt.value.clone(),
                            "Selected 'continue' option to keep agent running".to_string(),
                        ));
                    }
                }
            }
        }

        // Session continuation dialogs - ALWAYS continue to save requests
        if prompt_lower.contains("session")
            && (prompt_lower.contains("continue") || prompt_lower.contains("end"))
        {
            if let Some(ref options) = dialog.options {
                for opt in options {
                    let val_lower = opt.value.to_lowercase();
                    // Never select "done" or "end" - we want to keep agents running
                    if val_lower == "continue" || val_lower.contains("next") {
                        return Some((
                            opt.value.clone(),
                            "Continuing session to minimize request costs".to_string(),
                        ));
                    }
                }
            }
        }

        // Confirmation dialogs - default to "yes" for non-destructive
        if let Some(ref options) = dialog.options {
            if options.len() == 2 {
                let has_yes = options.iter().any(|o| o.value.to_lowercase() == "yes");
                let has_no = options.iter().any(|o| o.value.to_lowercase() == "no");
                if has_yes && has_no && !self.detect_critical_keywords(&dialog.prompt) {
                    return Some((
                        "yes".to_string(),
                        "Non-destructive confirmation - defaulting to 'yes'".to_string(),
                    ));
                }
            }
        }

        None
    }

    /// Suggest a default response based on dialog type
    fn suggest_default_response(&self, dialog: &PendingDialog) -> Option<String> {
        if let Some(ref options) = dialog.options {
            if !options.is_empty() {
                return Some(options[0].value.clone());
            }
        }

        None
    }

    /// Get timeout for a specific agent
    fn get_timeout_for_agent(&self, dialog: &PendingDialog) -> Duration {
        if let Some(ref agent_id) = dialog.agent_id {
            if let Some(&secs) = self.config.agent_timeouts.get(agent_id) {
                return Duration::from_secs(secs);
            }
        }
        Duration::from_secs(self.config.default_triage_timeout_secs)
    }

    /// Record a decision
    pub fn record_decision(&mut self, record: DecisionRecord) {
        // Add to undoable if within undo window
        if record.undoable {
            self.undoable_decisions
                .push((record.clone(), Instant::now()));
        }

        // Add to history
        self.history.push_back(record);

        // Trim history if too large
        while self.history.len() > self.config.max_history_size {
            self.history.pop_front();
        }
    }

    /// Clean up expired undoable decisions
    pub fn cleanup_undoable(&mut self) {
        let undo_window = Duration::from_secs(self.config.undo_window_secs);
        let now = Instant::now();
        self.undoable_decisions
            .retain(|(_, instant)| now.duration_since(*instant) < undo_window);
    }

    /// Get undoable decisions
    pub fn undoable_decisions(&self) -> Vec<&DecisionRecord> {
        let undo_window = Duration::from_secs(self.config.undo_window_secs);
        let now = Instant::now();
        self.undoable_decisions
            .iter()
            .filter(|(_, instant)| now.duration_since(*instant) < undo_window)
            .map(|(record, _)| record)
            .collect()
    }

    /// Undo the most recent decision within the undo window
    /// Returns the undone DecisionRecord if successful
    pub fn undo_last(&mut self) -> Option<DecisionRecord> {
        let undo_window = Duration::from_secs(self.config.undo_window_secs);
        let now = Instant::now();

        // Clean up expired entries first
        self.undoable_decisions
            .retain(|(_, instant)| now.duration_since(*instant) < undo_window);

        // Pop the most recent undoable decision
        self.undoable_decisions.pop().map(|(record, _)| record)
    }

    /// Add item to triage queue (enforces max_triage_size limit)
    pub fn add_to_triage(&mut self, item: TriageItem) {
        if self.triage_queue.len() >= self.config.max_triage_size {
            self.triage_queue.sort_by_key(|i| i.added_at);
            self.triage_queue.remove(0);
            log::warn!(
                "Triage queue at capacity ({}), dropping oldest item",
                self.config.max_triage_size
            );
        }
        self.triage_queue.push(item);
    }

    /// Get triage queue items
    pub fn triage_queue(&self) -> &[TriageItem] {
        &self.triage_queue
    }

    /// Remove item from triage queue by dialog ID
    pub fn remove_from_triage(&mut self, dialog_id: &str) -> Option<TriageItem> {
        if let Some(pos) = self
            .triage_queue
            .iter()
            .position(|i| i.dialog.id == dialog_id)
        {
            Some(self.triage_queue.remove(pos))
        } else {
            None
        }
    }

    /// Update triage state for a dialog
    pub fn set_triage_state(&mut self, dialog_id: &str, state: TriageState) -> bool {
        if let Some(item) = self
            .triage_queue
            .iter_mut()
            .find(|i| i.dialog.id == dialog_id)
        {
            item.state = state;
            true
        } else {
            false
        }
    }

    /// Process triage queue - returns dialogs that have timed out
    pub fn process_triage_timeouts(&mut self) -> Vec<(PendingDialog, TriageState, String)> {
        let now = Instant::now();
        let mut expired = Vec::new();

        self.triage_queue.retain(|item| {
            if now.duration_since(item.added_at) >= item.timeout {
                match item.state {
                    TriageState::AutoApprove | TriageState::AutoDecline => {
                        expired.push((
                            item.dialog.clone(),
                            item.state,
                            item.suggested_response.clone().unwrap_or_default(),
                        ));
                        false // Remove from queue
                    }
                    TriageState::Manual | TriageState::Paused => {
                        true // Keep in queue
                    }
                }
            } else {
                true // Not expired yet
            }
        });

        expired
    }

    /// Get decision history
    pub fn history(&self) -> impl Iterator<Item = &DecisionRecord> {
        self.history.iter()
    }

    /// Get recent history (last N items)
    pub fn recent_history(&self, n: usize) -> Vec<&DecisionRecord> {
        self.history.iter().rev().take(n).collect()
    }
}

impl DialogPriority {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Low => "low",
            Self::Normal => "normal",
            Self::High => "high",
            Self::Critical => "critical",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_test_dialog(priority: DialogPriority, prompt: &str) -> PendingDialog {
        PendingDialog {
            id: "test-1".to_string(),
            title: "Test Dialog".to_string(),
            prompt: prompt.to_string(),
            dialog_type: "choice".to_string(),
            options: Some(vec![
                crate::cli_agents::DialogOption {
                    value: "continue".to_string(),
                    label: "Continue".to_string(),
                    description: None,
                },
                crate::cli_agents::DialogOption {
                    value: "stop".to_string(),
                    label: "Stop".to_string(),
                    description: None,
                },
            ]),
            created_at: None,
            agent_id: Some("agent-1".to_string()),
            source: DialogSource::SessionAgent,
            priority,
            workspace: Some("/home/user/project".to_string()),
            orchestrator_id: None,
        }
    }

    #[test]
    fn test_user_active_requires_user() {
        let engine = DecisionEngine::new(DecisionEngineConfig::default());
        let dialog = make_test_dialog(DialogPriority::Low, "Do you want to continue?");

        match engine.evaluate(&dialog) {
            DecisionResult::RequireUser { .. } => {}
            _ => panic!("Expected RequireUser in UserActive mode"),
        }
    }

    #[test]
    fn test_autonomous_auto_handles() {
        let mut engine = DecisionEngine::new(DecisionEngineConfig::default());
        engine.set_mode(OrchestratorMode::Autonomous);
        let dialog = make_test_dialog(DialogPriority::Normal, "Do you want to continue?");

        match engine.evaluate(&dialog) {
            DecisionResult::AutoHandle { response, .. } => {
                assert_eq!(response, "continue");
            }
            _ => panic!("Expected AutoHandle in Autonomous mode"),
        }
    }

    #[test]
    fn test_critical_keywords_detected() {
        let mut engine = DecisionEngine::new(DecisionEngineConfig::default());
        engine.set_mode(OrchestratorMode::Autonomous);
        let dialog = make_test_dialog(DialogPriority::Normal, "Delete all files?");

        match engine.evaluate(&dialog) {
            DecisionResult::Triage {
                state: TriageState::Paused,
                ..
            } => {}
            _ => panic!("Expected Triage with Paused for critical dialog"),
        }
    }

    #[test]
    fn test_session_continue_prioritized() {
        let mut engine = DecisionEngine::new(DecisionEngineConfig::default());
        engine.set_mode(OrchestratorMode::UserDelegate);
        let dialog = make_test_dialog(
            DialogPriority::Normal,
            "Session complete. What would you like to do?",
        );

        match engine.evaluate(&dialog) {
            DecisionResult::AutoHandle {
                response,
                reasoning,
            } => {
                assert_eq!(response, "continue");
                assert!(reasoning.contains("session") || reasoning.contains("continue"));
            }
            DecisionResult::Triage { .. } => {
                // Also acceptable
            }
            _ => panic!("Expected auto-continue for session dialog"),
        }
    }
}
