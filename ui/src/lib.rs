//! Continuum Studio UI
//!
//! A modular, widget-based UI for AI orchestration.
//! Communicates with Studio Core (Elixir) via ETF over Unix sockets.
//!
//! # Architecture
//!
//! - **Widgets**: Independent UI components that communicate via event bus
//! - **IPC**: ETF-encoded messages over Unix socket to Elixir backend
//! - **Theme**: VS Code compatible theming system
//! - **Layout**: Tiling/tabbed widget arrangement
//! - **Test Harness**: Mock server for UI testing

pub mod approval;
pub mod diagram_d2;
pub mod ipc;
pub mod theme;
// pub mod vector;  // Removed: Will reimprove with Synapsix.Docs integration
pub mod widgets;
pub mod test_harness;

// Re-export commonly used types
pub use theme::Theme;
pub use widgets::{Widget, WidgetEvent, WidgetManager};
pub use ipc::{IpcClient, Command, Event, IpcError, DialogClient, DialogResponse, ChoiceOption, ToastLevel};
pub use approval::{ApprovalManager, ApprovalMode, ApprovalResult, OperationType, ApprovalRequest};
pub use test_harness::{TestHarness, TestConfig, MockEvent};

