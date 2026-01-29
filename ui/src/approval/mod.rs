//! Approval System for User Confirmations
//!
//! Manages user approval workflows for potentially dangerous or
//! costly operations. Supports multiple approval modes.
//!
//! # Modes
//!
//! - **Ask**: Always prompt user for confirmation
//! - **Auto**: Automatically approve (for trusted contexts)
//! - **Deny**: Automatically deny (for restricted contexts)

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Approval mode for different operation types
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize, Deserialize)]
pub enum ApprovalMode {
    /// Always ask user for confirmation
    Ask,
    /// Automatically approve
    Auto,
    /// Automatically deny
    Deny,
}

impl Default for ApprovalMode {
    fn default() -> Self {
        Self::Ask
    }
}

/// Types of operations that may require approval
#[derive(Clone, Debug, Eq, Hash, PartialEq, Serialize, Deserialize)]
pub enum OperationType {
    /// File system modifications
    FileWrite,
    /// File deletions
    FileDelete,
    /// Network requests
    NetworkRequest,
    /// Shell command execution
    ShellCommand,
    /// Harness actions
    HarnessAction,
    /// API key usage
    ApiKeyUsage,
    /// Custom operation
    Custom(String),
}

/// An operation requiring approval
#[derive(Clone, Debug)]
pub struct ApprovalRequest {
    /// Unique ID for this request
    pub id: uuid::Uuid,
    /// Type of operation
    pub operation_type: OperationType,
    /// Human-readable description
    pub description: String,
    /// Additional details
    pub details: Option<String>,
    /// Risk level (1-5)
    pub risk_level: u8,
}

impl ApprovalRequest {
    pub fn new(operation_type: OperationType, description: impl Into<String>) -> Self {
        Self {
            id: uuid::Uuid::new_v4(),
            operation_type,
            description: description.into(),
            details: None,
            risk_level: 1,
        }
    }
    
    pub fn with_details(mut self, details: impl Into<String>) -> Self {
        self.details = Some(details.into());
        self
    }
    
    pub fn with_risk_level(mut self, level: u8) -> Self {
        self.risk_level = level.min(5);
        self
    }
}

/// Result of an approval request
#[derive(Clone, Debug)]
pub enum ApprovalResult {
    /// Operation approved
    Approved,
    /// Operation denied
    Denied,
    /// Operation cancelled (dialog closed)
    Cancelled,
}

/// Manages approval workflows
pub struct ApprovalManager {
    /// Mode for each operation type
    modes: HashMap<OperationType, ApprovalMode>,
    /// Default mode for unknown operations
    default_mode: ApprovalMode,
}

impl ApprovalManager {
    pub fn new() -> Self {
        Self {
            modes: HashMap::new(),
            default_mode: ApprovalMode::Ask,
        }
    }
    
    /// Set the approval mode for an operation type
    pub fn set_mode(&mut self, op_type: OperationType, mode: ApprovalMode) {
        self.modes.insert(op_type, mode);
    }
    
    /// Get the approval mode for an operation type
    pub fn get_mode(&self, op_type: &OperationType) -> ApprovalMode {
        self.modes.get(op_type).copied().unwrap_or(self.default_mode)
    }
    
    /// Check if an operation needs explicit approval
    pub fn needs_approval(&self, op_type: &OperationType) -> bool {
        matches!(self.get_mode(op_type), ApprovalMode::Ask)
    }
    
    /// Request approval for an operation
    /// Returns immediately if mode is Auto or Deny
    pub fn request(&self, request: &ApprovalRequest) -> ApprovalResult {
        match self.get_mode(&request.operation_type) {
            ApprovalMode::Auto => ApprovalResult::Approved,
            ApprovalMode::Deny => ApprovalResult::Denied,
            ApprovalMode::Ask => {
                // In async context, this would create a dialog
                // For now, return a placeholder
                ApprovalResult::Approved // TODO: Integrate with dialog system
            }
        }
    }
}

impl Default for ApprovalManager {
    fn default() -> Self {
        Self::new()
    }
}

