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
pub use core::{CoreRequest, CoreResponse, CursorVersion, VersionStatus, Session, InstalledVersion, VersionStats, Workspace, GitStats, WorkspaceVersion};
pub use monitoring::{SessionMetrics, SessionMonitor, SessionHistory, HealthStatus, DashboardData};
pub use services::{ServiceConfig, ServiceManager, ServiceInfo, ServiceStatus};
pub use sessions::{CursorSession, SessionTracker, SharedCursorSettings, CursorProcessType, ProcessInfo, WindowInfo};
pub use settings::{Settings, ThemePreference, CosmicPreset};
pub use subagents::{SubagentPanelState, SubagentMessage, CommandRecord, ErrorDetection, MonitorStats, AgentCodeName, generate_codename, CodeNameRegistry};
pub use theme::{AppColors, SemanticColors, CosmicPalette, CosmicThemePreset};
pub use updater::{
    UpdateChannel, UpdateSettings, UpdateInfo, UpdateChecker, ForgeType, InstallationType,
    SelfUpdater, ReleaseProvider, GitHubProvider, ForgejoProvider, LocalBuildProvider,
};
pub use task_queue_client::{
    Task as SynapsixTask, TaskStatus as SynapsixTaskStatus, Priority, QueueStats,
    TaskQueueEvent, TaskQueueHttpClient, spawn_websocket_connection, Creator,
};
pub use dialog_client::{
    DialogClient, DialogClientMessage, DialogRequest as DialogPanelRequest,
    DialogType as DialogPanelType, ChoiceOption as DialogChoiceOption,
    spawn_dialog_monitor,
};
pub use feed_client::{
    FeedEntry, FeedSource, FeedLink, FeedStats, FeedEvent,
    FeedHttpClient, spawn_feed_websocket,
};
pub use coordinator_client::{
    Agent as CoordinatorAgent, AgentType, AgentStatus, AgentFocus,
    Conflict, CoordinatorEvent, CoordinatorHttpClient,
};
pub use zones::{
    ZoneManager, ZoneLayout, ZoneSnapshot, WindowPlacementConfig,
    LayoutApplication, ZONE_SNAPSHOT_PATH,
};