//! Continuum Studio UI Library - iced/COSMIC Edition
//!
//! This library provides the UI components for Continuum Studio,
//! built with iced and designed for COSMIC desktop integration.

pub mod chat_pipeline;
pub mod core;
pub mod log_capture;
pub mod monitoring;
pub mod services;
pub mod sessions;
pub mod settings;
pub mod subagents;
pub mod theme;
pub mod updater;
pub mod widgets;

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
