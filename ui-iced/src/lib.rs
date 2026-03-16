//! Continuum Studio UI Library - iced/COSMIC Edition
//!
//! This library provides the UI components for Continuum Studio,
//! built with iced and designed for COSMIC desktop integration.

pub mod activity_feed;
pub mod activity_stream_client;
pub mod chat_pipeline;
pub mod cli_agents;
pub mod cli_agents_client;
pub mod coordinator_client;
pub mod core;
pub mod decision_engine;
pub mod dialog_client;
pub mod feed_client;
pub mod log_capture;
pub mod monitoring;
pub mod offline;
pub mod orchestrator_panel;
pub mod parked_agents;
pub mod parked_agents_client;
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
pub use activity_feed::{
    view_activity_feed, ActivityEvent, ActivityEventType, ActivityFeedState, ActivityMessage,
    FilterType,
};
pub use activity_stream_client::{spawn_activity_stream, DEFAULT_ACTIVITY_WS_URL};
pub use cli_agents::{
    view_cli_agents_tab, AgentMode as CLIAgentMode, CLIAgent, CLIAgentEvent, CLIAgentMessage,
    CLIAgentStatus, CLIAgentTask, CLIAgentsState, CLIAgentsView, CLIEventType,
};
pub use cli_agents_client::{
    spawn_cli_agents_websocket, CLIAgentWsEvent, CLIAgentsHttpClient, SpawnAgentRequest,
    SpawnBatchRequest,
};
pub use coordinator_client::{
    Agent as CoordinatorAgent, AgentFocus, AgentStatus, AgentType, Conflict, CoordinatorEvent,
    CoordinatorHttpClient,
};
pub use core::{
    CoreRequest, CoreResponse, CursorVersion, GitStats, InstalledVersion, Session, VersionStats,
    VersionStatus, Workspace, WorkspaceVersion,
};
pub use decision_engine::{
    DecisionEngine, DecisionEngineConfig, DecisionRecord, DecisionResult, TriageItem, TriageState,
};
pub use dialog_client::{
    spawn_dialog_monitor, ChoiceOption as DialogChoiceOption, DialogClient, DialogClientMessage,
    DialogRequest as DialogPanelRequest, DialogType as DialogPanelType, OrchestratorMode,
    OrchestratorModeInfo,
};
pub use feed_client::{
    spawn_feed_websocket, FeedEntry, FeedEvent, FeedHttpClient, FeedLink, FeedSource, FeedStats,
};
pub use monitoring::{DashboardData, HealthStatus, SessionHistory, SessionMetrics, SessionMonitor};
pub use offline::{ConnectionTracker, OfflineQueue, OfflineState, OperationType, QueuedOperation};
pub use orchestrator_panel::{
    view_mode_selector, view_orchestrator_panel, view_triage_queue, OrchestratorMessage,
    OrchestratorPanelState,
};
pub use parked_agents::{
    view_parked_agents_panel, ParkedAgent, ParkedAgentTask, ParkedAgentsPanelState, ParkedMessage,
};
pub use parked_agents_client::ParkedAgentsHttpClient;
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
