//! Synapsix Canvas Protocol — serde models aligned with `schema/scp/*.ncl`.

pub mod activity;
pub mod decision;
pub mod statcard;
pub mod thinking;
pub mod topology;
pub mod types;

pub use activity::{
    ActivityDiffPreviewMode, ActivityEvent, ActivitySource, ActivitySourceChannel,
    ActivityStreamConfig, ActivityStreamFilters, ActivityStreamViewMode, ActivityTimeRange,
    ActivityType,
};
pub use decision::{DecisionNode, SmtData, VerificationResult, VerificationTier};
pub use statcard::{
    ActivitySummaryContent, ActivitySummaryLine, ConfirmationContent, ConfirmationFileChange,
    DecisionContent, ErrorContent, ProgressContent, PromoteTarget, SparklineContent, SparklinePeak,
    SparklineTotal, StatCard, StatCardAction, StatCardActionStyle, StatCardActionType,
    StatCardCommon, StatCardVariant, StatContent, StatRow, StatRowValue, StatTone,
    ThinkingPeekContent,
};
pub use thinking::{ThinkingCategory, ThinkingChunk, ThinkingNode};
pub use topology::{AgentLayer, TopologyEdge, TopologyEdgeType, TopologyNode, TopologyNodeType};
pub use types::{
    CanvasEventKind, CanvasType, Importance, NesyRiskLevel, Priority, PromoteCanvasType,
};
