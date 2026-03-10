//! Widget components for Continuum Studio iced UI
//!
//! This module contains reusable widget components following iced's
//! Elm-inspired architecture.

// Widget modules will be added here as we port them from egui:
// pub mod tab_bar;
pub mod diagram;
// pub mod terminal;
// pub mod code_view;

// Shared widget utilities
pub mod helpers;

// COSMIC-inspired styling
pub mod cosmic_style;

// TaskQueue widget for persistent task management
pub mod task_queue;

// Re-export commonly used items
pub use cosmic_style::{ContainerVariant, CosmicButton, CosmicContainer};
pub use task_queue::{
    TaskQueueWidget, TaskQueueMessage, TaskQueueAction, 
    WidgetSize as TaskQueueSize, TaskQueueColors,
    Task, TaskStatus, Priority, QueueStats,
    // Agent types
    Agent, AgentType, AgentStatus,
    // History types
    TaskHistoryEntry,
    // Layout types
    PanelLayout, ActivePanel,
};
pub use diagram::{
    DiagramType, DiagramState, DiagramRenderer, DiagramWidget,
    DiagramMessage, extract_diagrams,
};
