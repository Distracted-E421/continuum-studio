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
pub use diagram::{
    extract_diagrams, DiagramMessage, DiagramRenderer, DiagramState, DiagramType, DiagramWidget,
};
pub use task_queue::{
    ActivePanel,
    // Agent types
    Agent,
    AgentStatus,
    AgentType,
    // Layout types
    PanelLayout,
    Priority,
    QueueStats,
    Task,
    // History types
    TaskHistoryEntry,
    TaskQueueAction,
    TaskQueueColors,
    TaskQueueMessage,
    TaskQueueWidget,
    TaskStatus,
    WidgetSize as TaskQueueSize,
};
