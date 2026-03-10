//! Continuum Studio UI Library - iced/COSMIC Edition
//!
//! This library provides the UI components for Continuum Studio,
//! built with iced and designed for COSMIC desktop integration.

pub mod chat_pipeline;
pub mod coordinator_client;
pub mod core;
pub mod dialog_client;
pub mod feed_client;
pub mod log_capture;
pub mod monitoring;
pub mod offline;
pub mod services;
pub mod sessions;
pub mod settings;
pub mod subagents;
pub mod task_queue_client;
pub mod theme;
pub mod updater;
pub mod widgets;
pub mod zones;

// Re-export common types
pub use coordinator_client::{
    Agent as CoordinatorAgent, AgentFocus, AgentStatus, AgentType, Conflict, CoordinatorEvent,
    CoordinatorHttpClient,
};
pub use core::{
    CoreRequest, CoreResponse, CursorVersion, GitStats, InstalledVersion, Session, VersionStats,
    VersionStatus, Workspace, WorkspaceVersion,
};
pub use dialog_client::{
    spawn_dialog_monitor, ChoiceOption as DialogChoiceOption, DialogClient, DialogClientMessage,
    DialogRequest as DialogPanelRequest, DialogType as DialogPanelType,
};
pub use feed_client::{
    spawn_feed_websocket, FeedEntry, FeedEvent, FeedHttpClient, FeedLink, FeedSource, FeedStats,
};
pub use monitoring::{DashboardData, HealthStatus, SessionHistory, SessionMetrics, SessionMonitor};
pub use offline::{ConnectionTracker, OfflineQueue, OfflineState, OperationType, QueuedOperation};
pub use services::{ServiceConfig, ServiceInfo, ServiceManager, ServiceStatus};
pub use sessions::{
    CursorProcessType, CursorSession, ProcessInfo, SessionTracker, SharedCursorSettings, WindowInfo,
};
pub use settings::{CosmicPreset, Settings, ThemePreference};
pub use subagents::{
    generate_codename, AgentCodeName, CodeNameRegistry, CommandRecord, ErrorDetection,
    MonitorStats, SubagentMessage, SubagentPanelState,
};
pub use task_queue_client::{
    spawn_websocket_connection, Creator, Priority, QueueStats, Task as SynapsixTask,
    TaskQueueEvent, TaskQueueHttpClient, TaskStatus as SynapsixTaskStatus,
};
pub use theme::{AppColors, CosmicPalette, CosmicThemePreset, SemanticColors};
pub use updater::{
    ForgeType, ForgejoProvider, GitHubProvider, InstallationType, LocalBuildProvider,
    ReleaseProvider, SelfUpdater, UpdateChannel, UpdateChecker, UpdateInfo, UpdateSettings,
};
pub use zones::{
    LayoutApplication, WindowPlacementConfig, ZoneLayout, ZoneManager, ZoneSnapshot,
    ZONE_SNAPSHOT_PATH,
};
