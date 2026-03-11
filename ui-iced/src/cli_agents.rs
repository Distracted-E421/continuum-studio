//! CLI Agents Module
//!
//! Provides UI for orchestrating headless Cursor CLI agents through Synapsix.
//! Supports single agent spawning, batch processing, and real-time event monitoring.

use chrono::{DateTime, Utc};
use iced::widget::{
    button, column, container, pick_list, row, scrollable, text, text_input, Space,
};
use iced::{Alignment, Element, Length};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

// =============================================================================
// Preset Types
// =============================================================================

/// Prompt preset for behavioral reinforcement
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Preset {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub category: String,
    pub is_builtin: bool,
    pub prefix: Option<String>,
    pub suffix: Option<String>,
    pub created_at: Option<String>,
    pub updated_at: Option<String>,
}

impl Preset {
    pub fn display_name(&self) -> String {
        if self.is_builtin {
            format!("📦 {}", self.name)
        } else {
            format!("✏️ {}", self.name)
        }
    }
}

impl std::fmt::Display for Preset {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.display_name())
    }
}

impl PartialEq for Preset {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
    }
}

impl Eq for Preset {}

/// Text snippet for composable prompt fragments
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Snippet {
    pub id: String,
    pub name: String,
    pub description: Option<String>,
    pub content: String,
    pub position: String, // "prefix" or "suffix"
    pub created_at: Option<String>,
    pub updated_at: Option<String>,
}

impl std::fmt::Display for Snippet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.name)
    }
}

impl PartialEq for Snippet {
    fn eq(&self, other: &Self) -> bool {
        self.id == other.id
    }
}

impl Eq for Snippet {}

/// Workspace to preset mapping
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct WorkspaceOverrides {
    #[serde(flatten)]
    pub overrides: HashMap<String, String>,
}

/// State for CLI agents tab
#[derive(Debug, Clone, Default)]
pub struct CLIAgentsState {
    /// All known agents (keyed by ID)
    pub agents: HashMap<String, CLIAgent>,
    /// Currently selected agent (for detail view)
    pub selected_agent: Option<String>,
    /// Launch form state
    pub launch_form: LaunchForm,
    /// Batch launch form state
    pub batch_form: BatchForm,
    /// Current sub-view
    pub view: CLIAgentsView,
    /// Connection status
    pub connected: bool,
    /// Error message if any
    pub error: Option<String>,

    // Preset system
    /// Available presets
    pub presets: Vec<Preset>,
    /// Currently selected preset for launch form
    pub selected_preset: Option<String>,
    /// Available snippets
    pub snippets: Vec<Snippet>,
    /// Show preset editor modal
    pub show_preset_editor: bool,
    /// Preset being edited (None = new preset)
    pub editing_preset: Option<EditingPreset>,
    /// Show snippet editor modal
    pub show_snippet_editor: bool,
    /// Snippet being edited
    pub editing_snippet: Option<EditingSnippet>,
    /// Show prompt preview
    pub show_prompt_preview: bool,
    /// Workspace-to-preset overrides
    pub workspace_overrides: HashMap<String, String>,
    /// Show workspace overrides modal
    pub show_workspace_overrides_editor: bool,
    /// Editing workspace override (workspace, preset_id)
    pub editing_workspace_override: Option<(String, Option<String>)>,

    // Orchestration (Dialog Inbox)
    /// Pending dialogs from worker agents
    pub pending_dialogs: Vec<PendingDialog>,
    /// Currently selected dialog for responding
    pub selected_dialog: Option<String>,
    /// Response being composed
    pub dialog_response: DialogResponse,
    /// Show dialog response modal
    pub show_dialog_response_modal: bool,
}

/// Editing state for preset
#[derive(Debug, Clone, Default)]
pub struct EditingPreset {
    pub id: Option<String>,
    pub name: String,
    pub description: String,
    pub category: String,
    pub prefix: String,
    pub suffix: String,
}

impl EditingPreset {
    pub fn from_preset(preset: &Preset) -> Self {
        Self {
            id: Some(preset.id.clone()),
            name: preset.name.clone(),
            description: preset.description.clone().unwrap_or_default(),
            category: preset.category.clone(),
            prefix: preset.prefix.clone().unwrap_or_default(),
            suffix: preset.suffix.clone().unwrap_or_default(),
        }
    }

    pub fn new() -> Self {
        Self {
            id: None,
            name: String::new(),
            description: String::new(),
            category: "custom".to_string(),
            prefix: String::new(),
            suffix: String::new(),
        }
    }
}

/// Editing state for snippet
#[derive(Debug, Clone, Default)]
pub struct EditingSnippet {
    pub id: Option<String>,
    pub name: String,
    pub description: String,
    pub content: String,
    pub position: String,
}

impl EditingSnippet {
    pub fn from_snippet(snippet: &Snippet) -> Self {
        Self {
            id: Some(snippet.id.clone()),
            name: snippet.name.clone(),
            description: snippet.description.clone().unwrap_or_default(),
            content: snippet.content.clone(),
            position: snippet.position.clone(),
        }
    }

    pub fn new() -> Self {
        Self {
            id: None,
            name: String::new(),
            description: String::new(),
            content: String::new(),
            position: "suffix".to_string(),
        }
    }
}

/// Sub-views within CLI Agents tab
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CLIAgentsView {
    #[default]
    List,
    Detail,
    Launch,
    Batch,
    /// Dialog inbox for orchestrator mode - see and respond to worker dialogs
    Dialogs,
}

// =============================================================================
// Orchestration Types (Dialog Inbox)
// =============================================================================

/// Source of a dialog - where it originated
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DialogSource {
    #[default]
    Orchestrator,
    SessionAgent,
    SubAgent,
    External,
}

impl std::fmt::Display for DialogSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DialogSource::Orchestrator => write!(f, "Orchestrator"),
            DialogSource::SessionAgent => write!(f, "Session Agent"),
            DialogSource::SubAgent => write!(f, "Sub-Agent"),
            DialogSource::External => write!(f, "External"),
        }
    }
}

/// Priority level for dialog routing
#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DialogPriority {
    Low,
    #[default]
    Normal,
    High,
    Critical,
}

impl DialogPriority {
    pub fn emoji(&self) -> &'static str {
        match self {
            DialogPriority::Low => "⬇️",
            DialogPriority::Normal => "➡️",
            DialogPriority::High => "⬆️",
            DialogPriority::Critical => "🔴",
        }
    }
}

/// A pending dialog from a worker agent
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PendingDialog {
    pub id: String,
    pub title: String,
    pub prompt: String,
    pub dialog_type: String, // "choice", "confirmation", "text", "slider"
    pub options: Option<Vec<DialogOption>>,
    pub created_at: Option<String>,
    /// Agent ID - identifies which agent sent this dialog
    #[serde(default)]
    pub agent_id: Option<String>,
    /// Source of the dialog (orchestrator, session_agent, sub_agent, external)
    #[serde(default)]
    pub source: DialogSource,
    /// Priority level for routing decisions
    #[serde(default)]
    pub priority: DialogPriority,
    /// Workspace the agent is operating in
    #[serde(default)]
    pub workspace: Option<String>,
    /// Orchestrator ID if this came from a session agent
    #[serde(default)]
    pub orchestrator_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DialogOption {
    pub value: String,
    pub label: String,
    pub description: Option<String>,
}

/// State for responding to a dialog
#[derive(Debug, Clone, Default)]
pub struct DialogResponse {
    pub dialog_id: String,
    pub selection: String,
    pub comment: String,
}

/// Single CLI agent state
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CLIAgent {
    pub id: String,
    pub workspace: String,
    pub prompt: String,
    pub status: CLIAgentStatus,
    pub mode: AgentMode,
    pub model: Option<String>,
    pub started_at: Option<DateTime<Utc>>,
    pub completed_at: Option<DateTime<Utc>>,
    pub events: Vec<CLIAgentEvent>,
    pub result: Option<String>,
    pub error: Option<String>,
}

/// Agent status
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum CLIAgentStatus {
    #[default]
    Pending,
    Running,
    Completed,
    Failed,
    Timeout,
}

impl CLIAgentStatus {
    pub fn emoji(&self) -> &'static str {
        match self {
            Self::Pending => "⏳",
            Self::Running => "🟢",
            Self::Completed => "✅",
            Self::Failed => "❌",
            Self::Timeout => "⏰",
        }
    }

    pub fn label(&self) -> &'static str {
        match self {
            Self::Pending => "Pending",
            Self::Running => "Running",
            Self::Completed => "Completed",
            Self::Failed => "Failed",
            Self::Timeout => "Timeout",
        }
    }
}

/// Agent execution mode
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum AgentMode {
    #[default]
    Agent,
    Plan,
    Ask,
}

impl AgentMode {
    pub fn label(&self) -> &'static str {
        match self {
            Self::Agent => "Agent",
            Self::Plan => "Plan",
            Self::Ask => "Ask",
        }
    }
}

/// Agent event from CLI stream
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CLIAgentEvent {
    pub event_type: CLIEventType,
    pub timestamp: DateTime<Utc>,
    pub content: Option<String>,
    pub tool_name: Option<String>,
    pub tool_args: Option<serde_json::Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CLIEventType {
    Init,
    Thinking,
    ToolStarted,
    ToolCompleted,
    Response,
    Result,
    Error,
}

impl CLIEventType {
    pub fn emoji(&self) -> &'static str {
        match self {
            Self::Init => "🚀",
            Self::Thinking => "💭",
            Self::ToolStarted => "🔧",
            Self::ToolCompleted => "✓",
            Self::Response => "💬",
            Self::Result => "📋",
            Self::Error => "⚠️",
        }
    }
}

/// Form for launching single agent
#[derive(Debug, Clone, Default)]
pub struct LaunchForm {
    pub workspace: String,
    pub prompt: String,
    pub mode: AgentMode,
    pub force: bool,
    pub approve_mcps: bool,
    pub model: Option<String>,
}

/// Form for batch launch
#[derive(Debug, Clone, Default)]
pub struct BatchForm {
    pub prompt: String,
    pub workspaces: Vec<(String, bool)>, // (path, selected)
    pub max_concurrent: u32,
    pub stop_on_failure: bool,
}

/// Messages for CLI agents tab
#[derive(Debug, Clone)]
pub enum CLIAgentMessage {
    // Navigation
    ChangeView(CLIAgentsView),
    SelectAgent(String),
    DeselectAgent,

    // Launch form
    UpdatePrompt(String),
    UpdateWorkspace(String),
    SetMode(AgentMode),
    ToggleForce,
    ToggleApproveMcps,
    Launch,

    // Batch form
    UpdateBatchPrompt(String),
    ToggleBatchWorkspace(String),
    SetMaxConcurrent(u32),
    ToggleStopOnFailure,
    AddWorkspace(String),
    LaunchBatch,

    // Agent actions
    StopAgent(String),
    RefreshAgents,

    // Updates from backend (WebSocket events)
    Connected,
    AgentStarted {
        id: String,
        workspace: String,
        model: Option<String>,
    },
    AgentCompleted {
        id: String,
        result: Option<String>,
        error: Option<String>,
    },
    AgentSpawned(String, CLIAgent),
    AgentUpdated(String, CLIAgent),
    AgentEvent(String, CLIAgentEvent),
    AgentsLoaded(Vec<String>),
    AgentsFullLoaded(Vec<CLIAgent>),

    // Preset system
    PresetsLoaded(Vec<Preset>),
    SnippetsLoaded(Vec<Snippet>),
    SelectPreset(Option<String>),
    RefreshPresets,

    // Preset editor
    OpenPresetEditor(Option<String>), // None = new, Some(id) = edit
    ClosePresetEditor,
    UpdatePresetName(String),
    UpdatePresetDescription(String),
    UpdatePresetCategory(String),
    UpdatePresetPrefix(String),
    UpdatePresetSuffix(String),
    SavePreset,
    DeletePreset(String),
    PresetSaved(Preset),
    PresetDeleted(String),

    // Snippet editor
    OpenSnippetEditor(Option<String>),
    CloseSnippetEditor,
    UpdateSnippetName(String),
    UpdateSnippetDescription(String),
    UpdateSnippetContent(String),
    UpdateSnippetPosition(String),
    SaveSnippet,
    DeleteSnippet(String),
    SnippetSaved(Snippet),
    SnippetDeleted(String),

    // Prompt preview
    TogglePromptPreview,

    // Workspace overrides
    WorkspaceOverridesLoaded(HashMap<String, String>),
    OpenWorkspaceOverridesEditor,
    CloseWorkspaceOverridesEditor,
    SetWorkspaceOverride {
        workspace: String,
        preset_id: String,
    },
    ClearWorkspaceOverride(String),
    WorkspaceOverrideSet {
        workspace: String,
        preset_id: String,
    },
    WorkspaceOverrideCleared(String),

    // Orchestration (Dialog Inbox)
    RefreshDialogs,
    DialogsLoaded(Vec<PendingDialog>),
    SelectDialog(String),
    DeselectDialog,
    OpenDialogResponseModal(String),
    CloseDialogResponseModal,
    UpdateDialogSelection(String),
    UpdateDialogComment(String),
    SubmitDialogResponse,
    DialogResponseSubmitted(String), // dialog_id that was answered

    // Errors
    Error(String),
    ClearError,
}

impl CLIAgentsState {
    pub fn new() -> Self {
        let mut batch_form = BatchForm::default();
        batch_form.max_concurrent = 3;

        // Pre-populate with common workspaces
        batch_form.workspaces = vec![
            ("/home/e421/synapsix".to_string(), false),
            ("/home/e421/homelab".to_string(), false),
            ("/home/e421/cortex".to_string(), false),
            ("/home/e421/continuum-studio".to_string(), false),
        ];

        Self {
            batch_form,
            presets: Vec::new(),
            snippets: Vec::new(),
            pending_dialogs: Vec::new(),
            selected_dialog: None,
            dialog_response: DialogResponse::default(),
            show_dialog_response_modal: false,
            ..Default::default()
        }
    }

    /// Get the currently selected preset
    pub fn get_selected_preset(&self) -> Option<&Preset> {
        self.selected_preset
            .as_ref()
            .and_then(|id| self.presets.iter().find(|p| &p.id == id))
    }

    /// Build the full prompt with prefix/suffix from selected preset
    pub fn build_full_prompt(&self) -> String {
        let preset = self.get_selected_preset();
        let prefix = preset
            .and_then(|p| p.prefix.as_deref())
            .unwrap_or("[Default Prefix]");
        let suffix = preset
            .and_then(|p| p.suffix.as_deref())
            .unwrap_or("[Default Suffix]");

        format!(
            "{}\n\n## Your Task\n\n{}\n\n{}",
            prefix, self.launch_form.prompt, suffix
        )
    }

    /// Process a message and return any tasks to run
    pub fn update(&mut self, message: CLIAgentMessage) -> Option<CLIAgentTask> {
        match message {
            CLIAgentMessage::ChangeView(view) => {
                self.view = view;
                None
            }
            CLIAgentMessage::SelectAgent(id) => {
                self.selected_agent = Some(id);
                self.view = CLIAgentsView::Detail;
                None
            }
            CLIAgentMessage::DeselectAgent => {
                self.selected_agent = None;
                self.view = CLIAgentsView::List;
                None
            }

            // Launch form
            CLIAgentMessage::UpdatePrompt(p) => {
                self.launch_form.prompt = p;
                None
            }
            CLIAgentMessage::UpdateWorkspace(w) => {
                self.launch_form.workspace = w.clone();

                // Auto-select preset based on workspace override
                if let Some(preset_id) = self.workspace_overrides.get(&w) {
                    self.selected_preset = Some(preset_id.clone());
                }
                None
            }
            CLIAgentMessage::SetMode(m) => {
                self.launch_form.mode = m;
                None
            }
            CLIAgentMessage::ToggleForce => {
                self.launch_form.force = !self.launch_form.force;
                None
            }
            CLIAgentMessage::ToggleApproveMcps => {
                self.launch_form.approve_mcps = !self.launch_form.approve_mcps;
                None
            }
            CLIAgentMessage::Launch => {
                let preset = self.get_selected_preset();
                Some(CLIAgentTask::SpawnAgent {
                    prompt: self.launch_form.prompt.clone(),
                    workspace: self.launch_form.workspace.clone(),
                    mode: self.launch_form.mode,
                    force: self.launch_form.force,
                    approve_mcps: self.launch_form.approve_mcps,
                    prefix: preset.and_then(|p| p.prefix.clone()),
                    suffix: preset.and_then(|p| p.suffix.clone()),
                })
            }

            // Batch form
            CLIAgentMessage::UpdateBatchPrompt(p) => {
                self.batch_form.prompt = p;
                None
            }
            CLIAgentMessage::ToggleBatchWorkspace(ws) => {
                if let Some((_, selected)) = self
                    .batch_form
                    .workspaces
                    .iter_mut()
                    .find(|(w, _)| w == &ws)
                {
                    *selected = !*selected;
                }
                None
            }
            CLIAgentMessage::SetMaxConcurrent(n) => {
                self.batch_form.max_concurrent = n;
                None
            }
            CLIAgentMessage::ToggleStopOnFailure => {
                self.batch_form.stop_on_failure = !self.batch_form.stop_on_failure;
                None
            }
            CLIAgentMessage::AddWorkspace(ws) => {
                if !ws.is_empty() && !self.batch_form.workspaces.iter().any(|(w, _)| w == &ws) {
                    self.batch_form.workspaces.push((ws, true));
                }
                None
            }
            CLIAgentMessage::LaunchBatch => {
                let workspaces: Vec<String> = self
                    .batch_form
                    .workspaces
                    .iter()
                    .filter(|(_, selected)| *selected)
                    .map(|(ws, _)| ws.clone())
                    .collect();

                if workspaces.is_empty() {
                    self.error = Some("No workspaces selected".to_string());
                    return None;
                }

                Some(CLIAgentTask::SpawnBatch {
                    prompt: self.batch_form.prompt.clone(),
                    workspaces,
                    max_concurrent: self.batch_form.max_concurrent,
                    stop_on_failure: self.batch_form.stop_on_failure,
                })
            }

            // Agent actions
            CLIAgentMessage::StopAgent(id) => Some(CLIAgentTask::StopAgent { id }),
            CLIAgentMessage::RefreshAgents => Some(CLIAgentTask::RefreshAgents),

            // Updates from backend (WebSocket events)
            CLIAgentMessage::Connected => {
                self.connected = true;
                self.error = None;
                None
            }
            CLIAgentMessage::AgentStarted {
                id,
                workspace,
                model,
            } => {
                let agent = CLIAgent {
                    id: id.clone(),
                    workspace,
                    prompt: self.launch_form.prompt.clone(),
                    status: CLIAgentStatus::Running,
                    mode: self.launch_form.mode,
                    model,
                    started_at: Some(chrono::Utc::now()),
                    completed_at: None,
                    events: Vec::new(),
                    result: None,
                    error: None,
                };
                self.agents.insert(id, agent);
                self.view = CLIAgentsView::List;
                self.launch_form = LaunchForm::default();
                None
            }
            CLIAgentMessage::AgentCompleted { id, result, error } => {
                if let Some(agent) = self.agents.get_mut(&id) {
                    agent.status = if error.is_some() {
                        CLIAgentStatus::Failed
                    } else {
                        CLIAgentStatus::Completed
                    };
                    agent.completed_at = Some(chrono::Utc::now());
                    agent.result = result;
                    agent.error = error;
                }
                None
            }
            CLIAgentMessage::AgentSpawned(id, agent) => {
                self.agents.insert(id, agent);
                self.view = CLIAgentsView::List;
                self.launch_form = LaunchForm::default();
                None
            }
            CLIAgentMessage::AgentUpdated(id, agent) => {
                self.agents.insert(id, agent);
                None
            }
            CLIAgentMessage::AgentEvent(id, event) => {
                if let Some(agent) = self.agents.get_mut(&id) {
                    agent.events.push(event);
                }
                None
            }
            CLIAgentMessage::AgentsLoaded(_agent_ids) => {
                // TODO: Fetch full agent details for each ID
                None
            }
            CLIAgentMessage::AgentsFullLoaded(agents) => {
                self.agents = agents.into_iter().map(|a| (a.id.clone(), a)).collect();
                None
            }

            // Preset system
            CLIAgentMessage::PresetsLoaded(presets) => {
                self.presets = presets;
                // Auto-select default if nothing selected
                if self.selected_preset.is_none() {
                    self.selected_preset = self
                        .presets
                        .iter()
                        .find(|p| p.id == "default")
                        .map(|p| p.id.clone());
                }
                None
            }
            CLIAgentMessage::SnippetsLoaded(snippets) => {
                self.snippets = snippets;
                None
            }
            CLIAgentMessage::SelectPreset(preset_id) => {
                self.selected_preset = preset_id;
                None
            }
            CLIAgentMessage::RefreshPresets => Some(CLIAgentTask::FetchPresets),

            // Preset editor
            CLIAgentMessage::OpenPresetEditor(preset_id) => {
                if let Some(id) = preset_id {
                    if let Some(preset) = self.presets.iter().find(|p| p.id == id) {
                        self.editing_preset = Some(EditingPreset::from_preset(preset));
                    }
                } else {
                    self.editing_preset = Some(EditingPreset::new());
                }
                self.show_preset_editor = true;
                None
            }
            CLIAgentMessage::ClosePresetEditor => {
                self.show_preset_editor = false;
                self.editing_preset = None;
                None
            }
            CLIAgentMessage::UpdatePresetName(name) => {
                if let Some(ref mut editing) = self.editing_preset {
                    editing.name = name;
                }
                None
            }
            CLIAgentMessage::UpdatePresetDescription(desc) => {
                if let Some(ref mut editing) = self.editing_preset {
                    editing.description = desc;
                }
                None
            }
            CLIAgentMessage::UpdatePresetCategory(cat) => {
                if let Some(ref mut editing) = self.editing_preset {
                    editing.category = cat;
                }
                None
            }
            CLIAgentMessage::UpdatePresetPrefix(prefix) => {
                if let Some(ref mut editing) = self.editing_preset {
                    editing.prefix = prefix;
                }
                None
            }
            CLIAgentMessage::UpdatePresetSuffix(suffix) => {
                if let Some(ref mut editing) = self.editing_preset {
                    editing.suffix = suffix;
                }
                None
            }
            CLIAgentMessage::SavePreset => {
                if let Some(editing) = &self.editing_preset {
                    let task = if let Some(id) = &editing.id {
                        CLIAgentTask::UpdatePreset {
                            id: id.clone(),
                            name: editing.name.clone(),
                            description: if editing.description.is_empty() {
                                None
                            } else {
                                Some(editing.description.clone())
                            },
                            category: editing.category.clone(),
                            prefix: if editing.prefix.is_empty() {
                                None
                            } else {
                                Some(editing.prefix.clone())
                            },
                            suffix: if editing.suffix.is_empty() {
                                None
                            } else {
                                Some(editing.suffix.clone())
                            },
                        }
                    } else {
                        CLIAgentTask::CreatePreset {
                            name: editing.name.clone(),
                            description: if editing.description.is_empty() {
                                None
                            } else {
                                Some(editing.description.clone())
                            },
                            category: editing.category.clone(),
                            prefix: if editing.prefix.is_empty() {
                                None
                            } else {
                                Some(editing.prefix.clone())
                            },
                            suffix: if editing.suffix.is_empty() {
                                None
                            } else {
                                Some(editing.suffix.clone())
                            },
                        }
                    };
                    self.show_preset_editor = false;
                    self.editing_preset = None;
                    return Some(task);
                }
                None
            }
            CLIAgentMessage::DeletePreset(id) => Some(CLIAgentTask::DeletePreset { id }),
            CLIAgentMessage::PresetSaved(preset) => {
                // Update or add preset in list
                if let Some(existing) = self.presets.iter_mut().find(|p| p.id == preset.id) {
                    *existing = preset;
                } else {
                    self.presets.push(preset);
                }
                None
            }
            CLIAgentMessage::PresetDeleted(id) => {
                self.presets.retain(|p| p.id != id);
                if self.selected_preset.as_ref() == Some(&id) {
                    self.selected_preset = None;
                }
                None
            }

            // Snippet editor
            CLIAgentMessage::OpenSnippetEditor(snippet_id) => {
                if let Some(id) = snippet_id {
                    if let Some(snippet) = self.snippets.iter().find(|s| s.id == id) {
                        self.editing_snippet = Some(EditingSnippet::from_snippet(snippet));
                    }
                } else {
                    self.editing_snippet = Some(EditingSnippet::new());
                }
                self.show_snippet_editor = true;
                None
            }
            CLIAgentMessage::CloseSnippetEditor => {
                self.show_snippet_editor = false;
                self.editing_snippet = None;
                None
            }
            CLIAgentMessage::UpdateSnippetName(name) => {
                if let Some(ref mut editing) = self.editing_snippet {
                    editing.name = name;
                }
                None
            }
            CLIAgentMessage::UpdateSnippetDescription(desc) => {
                if let Some(ref mut editing) = self.editing_snippet {
                    editing.description = desc;
                }
                None
            }
            CLIAgentMessage::UpdateSnippetContent(content) => {
                if let Some(ref mut editing) = self.editing_snippet {
                    editing.content = content;
                }
                None
            }
            CLIAgentMessage::UpdateSnippetPosition(pos) => {
                if let Some(ref mut editing) = self.editing_snippet {
                    editing.position = pos;
                }
                None
            }
            CLIAgentMessage::SaveSnippet => {
                if let Some(editing) = &self.editing_snippet {
                    let task = if let Some(id) = &editing.id {
                        CLIAgentTask::UpdateSnippet {
                            id: id.clone(),
                            name: editing.name.clone(),
                            description: if editing.description.is_empty() {
                                None
                            } else {
                                Some(editing.description.clone())
                            },
                            content: editing.content.clone(),
                            position: editing.position.clone(),
                        }
                    } else {
                        CLIAgentTask::CreateSnippet {
                            name: editing.name.clone(),
                            description: if editing.description.is_empty() {
                                None
                            } else {
                                Some(editing.description.clone())
                            },
                            content: editing.content.clone(),
                            position: editing.position.clone(),
                        }
                    };
                    self.show_snippet_editor = false;
                    self.editing_snippet = None;
                    return Some(task);
                }
                None
            }
            CLIAgentMessage::DeleteSnippet(id) => Some(CLIAgentTask::DeleteSnippet { id }),
            CLIAgentMessage::SnippetSaved(snippet) => {
                if let Some(existing) = self.snippets.iter_mut().find(|s| s.id == snippet.id) {
                    *existing = snippet;
                } else {
                    self.snippets.push(snippet);
                }
                None
            }
            CLIAgentMessage::SnippetDeleted(id) => {
                self.snippets.retain(|s| s.id != id);
                None
            }

            // Prompt preview
            CLIAgentMessage::TogglePromptPreview => {
                self.show_prompt_preview = !self.show_prompt_preview;
                None
            }

            // Workspace overrides
            CLIAgentMessage::WorkspaceOverridesLoaded(overrides) => {
                self.workspace_overrides = overrides;
                None
            }
            CLIAgentMessage::OpenWorkspaceOverridesEditor => {
                self.show_workspace_overrides_editor = true;
                Some(CLIAgentTask::FetchWorkspaceOverrides)
            }
            CLIAgentMessage::CloseWorkspaceOverridesEditor => {
                self.show_workspace_overrides_editor = false;
                self.editing_workspace_override = None;
                None
            }
            CLIAgentMessage::SetWorkspaceOverride {
                workspace,
                preset_id,
            } => Some(CLIAgentTask::SetWorkspaceOverride {
                workspace,
                preset_id,
            }),
            CLIAgentMessage::ClearWorkspaceOverride(workspace) => {
                Some(CLIAgentTask::ClearWorkspaceOverride { workspace })
            }
            CLIAgentMessage::WorkspaceOverrideSet {
                workspace,
                preset_id,
            } => {
                self.workspace_overrides.insert(workspace, preset_id);
                None
            }
            CLIAgentMessage::WorkspaceOverrideCleared(workspace) => {
                self.workspace_overrides.remove(&workspace);
                None
            }

            // Orchestration (Dialog Inbox)
            CLIAgentMessage::RefreshDialogs => Some(CLIAgentTask::FetchPendingDialogs),
            CLIAgentMessage::DialogsLoaded(dialogs) => {
                self.pending_dialogs = dialogs;
                None
            }
            CLIAgentMessage::SelectDialog(id) => {
                self.selected_dialog = Some(id);
                None
            }
            CLIAgentMessage::DeselectDialog => {
                self.selected_dialog = None;
                None
            }
            CLIAgentMessage::OpenDialogResponseModal(dialog_id) => {
                self.dialog_response = DialogResponse {
                    dialog_id: dialog_id.clone(),
                    selection: String::new(),
                    comment: String::new(),
                };
                self.selected_dialog = Some(dialog_id);
                self.show_dialog_response_modal = true;
                None
            }
            CLIAgentMessage::CloseDialogResponseModal => {
                self.show_dialog_response_modal = false;
                self.dialog_response = DialogResponse::default();
                None
            }
            CLIAgentMessage::UpdateDialogSelection(sel) => {
                self.dialog_response.selection = sel;
                None
            }
            CLIAgentMessage::UpdateDialogComment(comment) => {
                self.dialog_response.comment = comment;
                None
            }
            CLIAgentMessage::SubmitDialogResponse => {
                let dialog_id = self.dialog_response.dialog_id.clone();
                let selection = self.dialog_response.selection.clone();
                let comment = if self.dialog_response.comment.is_empty() {
                    None
                } else {
                    Some(self.dialog_response.comment.clone())
                };
                self.show_dialog_response_modal = false;
                Some(CLIAgentTask::RespondToDialog {
                    dialog_id,
                    selection,
                    comment,
                })
            }
            CLIAgentMessage::DialogResponseSubmitted(dialog_id) => {
                // Remove the dialog from pending list
                self.pending_dialogs.retain(|d| d.id != dialog_id);
                self.dialog_response = DialogResponse::default();
                self.selected_dialog = None;
                None
            }

            // Errors
            CLIAgentMessage::Error(e) => {
                self.error = Some(e);
                None
            }
            CLIAgentMessage::ClearError => {
                self.error = None;
                None
            }
        }
    }
}

/// Tasks to be executed by the main application
#[derive(Debug, Clone)]
pub enum CLIAgentTask {
    SpawnAgent {
        prompt: String,
        workspace: String,
        mode: AgentMode,
        force: bool,
        approve_mcps: bool,
        prefix: Option<String>,
        suffix: Option<String>,
    },
    SpawnBatch {
        prompt: String,
        workspaces: Vec<String>,
        max_concurrent: u32,
        stop_on_failure: bool,
    },
    StopAgent {
        id: String,
    },
    RefreshAgents,

    // Preset tasks
    FetchPresets,
    FetchSnippets,
    CreatePreset {
        name: String,
        description: Option<String>,
        category: String,
        prefix: Option<String>,
        suffix: Option<String>,
    },
    UpdatePreset {
        id: String,
        name: String,
        description: Option<String>,
        category: String,
        prefix: Option<String>,
        suffix: Option<String>,
    },
    DeletePreset {
        id: String,
    },
    CreateSnippet {
        name: String,
        description: Option<String>,
        content: String,
        position: String,
    },
    UpdateSnippet {
        id: String,
        name: String,
        description: Option<String>,
        content: String,
        position: String,
    },
    DeleteSnippet {
        id: String,
    },

    // Workspace override tasks
    FetchWorkspaceOverrides,
    SetWorkspaceOverride {
        workspace: String,
        preset_id: String,
    },
    ClearWorkspaceOverride {
        workspace: String,
    },

    // Orchestration tasks (Dialog Inbox)
    FetchPendingDialogs,
    RespondToDialog {
        dialog_id: String,
        selection: String,
        comment: Option<String>,
    },
}

// =============================================================================
// View Functions
// =============================================================================

/// Main view router for CLI agents tab
pub fn view_cli_agents_tab<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let header = view_header(state, to_message.clone());

    let content: Element<'a, M> = match state.view {
        CLIAgentsView::List => view_agent_list(state, to_message.clone()),
        CLIAgentsView::Detail => view_agent_detail(state, to_message.clone()),
        CLIAgentsView::Launch => view_launch_form(state, to_message.clone()),
        CLIAgentsView::Batch => view_batch_form(state, to_message.clone()),
        CLIAgentsView::Dialogs => view_dialog_inbox(state, to_message.clone()),
    };

    let error_banner: Element<'a, M> = if let Some(err) = &state.error {
        container(
            row![
                text(format!("⚠️ {}", err)).color(iced::Color::from_rgb(1.0, 0.6, 0.6)),
                Space::new().width(Length::Fill),
                button(text("✕").size(12))
                    .padding(4)
                    .on_press(to_message(CLIAgentMessage::ClearError))
            ]
            .align_y(Alignment::Center),
        )
        .padding(8)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.3, 0.15, 0.15,
            ))),
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Base content layout
    let main_view: Element<'a, M> = column![error_banner, header, Space::new().height(8), content,]
        .spacing(4)
        .padding(16)
        .into();

    // Overlay modals if active
    if state.show_preset_editor {
        if let Some(editing) = &state.editing_preset {
            return iced::widget::stack![main_view, view_preset_editor_modal(editing, to_message),]
                .into();
        }
    }

    if state.show_snippet_editor {
        if let Some(editing) = &state.editing_snippet {
            return iced::widget::stack![
                main_view,
                view_snippet_editor_modal(editing, to_message),
            ].into();
        }
    }

    if state.show_workspace_overrides_editor {
        return iced::widget::stack![main_view, view_workspace_overrides_modal(state, to_message.clone()),]
            .into();
    }

    if state.show_dialog_response_modal {
        if let Some(dialog_id) = &state.selected_dialog {
            if let Some(dialog) = state.pending_dialogs.iter().find(|d| &d.id == dialog_id) {
                return iced::widget::stack![
                    main_view,
                    view_dialog_response_modal(dialog, &state.dialog_response, to_message),
                ]
                .into();
            }
        }
    }

    main_view
}

fn view_header<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let title = text("⚡ CLI Agents").size(20);

    let running_count = state
        .agents
        .values()
        .filter(|a| a.status == CLIAgentStatus::Running)
        .count();

    let status_text = if running_count > 0 {
        text(format!("{} running", running_count))
            .size(12)
            .color(iced::Color::from_rgb(0.4, 0.8, 0.4))
    } else {
        text("").size(12)
    };

    let pending_dialog_count = state.pending_dialogs.len();

    let nav_buttons = row![
        tab_nav_button("List", CLIAgentsView::List, state.view, to_message.clone()),
        tab_nav_button(
            "Launch",
            CLIAgentsView::Launch,
            state.view,
            to_message.clone()
        ),
        tab_nav_button(
            "Batch",
            CLIAgentsView::Batch,
            state.view,
            to_message.clone()
        ),
        tab_nav_button_with_badge(
            "Dialogs",
            pending_dialog_count,
            CLIAgentsView::Dialogs,
            state.view,
            to_message.clone()
        ),
    ]
    .spacing(4);

    let refresh_btn = button(text("⟳").size(14))
        .padding([6, 10])
        .on_press(to_message.clone()(CLIAgentMessage::RefreshAgents));

    // Settings button to open workspace overrides
    let settings_btn = button(text("⚙").size(14))
        .padding([6, 10])
        .on_press(to_message(CLIAgentMessage::OpenWorkspaceOverridesEditor));

    row![
        title,
        Space::new().width(12),
        status_text,
        Space::new().width(Length::Fill),
        nav_buttons,
        Space::new().width(8),
        settings_btn,
        Space::new().width(4),
        refresh_btn,
    ]
    .align_y(Alignment::Center)
    .into()
}

fn tab_nav_button<'a, M>(
    label: &'a str,
    target: CLIAgentsView,
    current: CLIAgentsView,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = target == current;

    button(text(label).size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::ChangeView(target)))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.45, 0.65)
            } else {
                iced::Color::from_rgb(0.2, 0.2, 0.25)
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        })
        .into()
}

fn tab_nav_button_with_badge<'a, M>(
    label: &'static str,
    badge_count: usize,
    target: CLIAgentsView,
    current: CLIAgentsView,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = target == current;
    let has_notification = badge_count > 0;

    let label_text = if badge_count > 0 {
        format!("{} ({})", label, badge_count)
    } else {
        label.to_string()
    };

    button(text(label_text).size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::ChangeView(target)))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.45, 0.65)
            } else if has_notification {
                // Orange/amber highlight for pending items
                iced::Color::from_rgb(0.5, 0.35, 0.15)
            } else {
                iced::Color::from_rgb(0.2, 0.2, 0.25)
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: if has_notification && !is_active {
                    iced::Color::from_rgb(1.0, 0.85, 0.4) // Yellow text for notification
                } else {
                    iced::Color::WHITE
                },
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        })
        .into()
}

fn view_agent_list<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    if state.agents.is_empty() {
        return container(
            column![
                text("No CLI agents").size(16),
                Space::new().height(8),
                text("Use the Launch or Batch tabs to spawn agents")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            ]
            .align_x(Alignment::Center),
        )
        .width(Length::Fill)
        .padding(40)
        .center(Length::Fill)
        .into();
    }

    let mut agents: Vec<_> = state.agents.values().collect();
    agents.sort_by(|a, b| b.started_at.cmp(&a.started_at));

    let agent_cards: Vec<Element<'a, M>> = agents
        .into_iter()
        .map(|agent| view_agent_card(agent, to_message.clone()))
        .collect();

    scrollable(column(agent_cards).spacing(8))
        .height(Length::Fill)
        .into()
}

fn view_agent_card<'a, M>(
    agent: &'a CLIAgent,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let status_emoji = agent.status.emoji();
    let status_label = agent.status.label();

    let duration = if let (Some(start), Some(end)) = (agent.started_at, agent.completed_at) {
        let secs = (end - start).num_seconds();
        format!("{}s", secs)
    } else if let Some(start) = agent.started_at {
        let secs = (Utc::now() - start).num_seconds();
        format!("{}s...", secs)
    } else {
        "".to_string()
    };

    let header = row![
        text(format!("{} {}", status_emoji, &agent.id[..8])).size(14),
        Space::new().width(8),
        text(format!(
            "| {}",
            agent
                .workspace
                .split('/')
                .last()
                .unwrap_or(&agent.workspace)
        ))
        .size(12)
        .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
        Space::new().width(Length::Fill),
        text(status_label).size(12),
        Space::new().width(8),
        text(duration)
            .size(12)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .align_y(Alignment::Center);

    let prompt_preview = if agent.prompt.len() > 60 {
        format!("{}...", &agent.prompt[..60])
    } else {
        agent.prompt.clone()
    };

    let details = row![
        text(prompt_preview)
            .size(11)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
        Space::new().width(Length::Fill),
        text(format!("Events: {}", agent.events.len())).size(11),
    ];

    let stop_btn: Element<'a, M> = if agent.status == CLIAgentStatus::Running {
        button(text("Stop").size(11))
            .padding([4, 8])
            .on_press(to_message(CLIAgentMessage::StopAgent(agent.id.clone())))
            .into()
    } else {
        Space::new().width(0).into()
    };

    let actions = row![
        Space::new().width(Length::Fill),
        stop_btn,
        button(text("Details").size(11))
            .padding([4, 8])
            .on_press(to_message(CLIAgentMessage::SelectAgent(agent.id.clone()))),
    ]
    .spacing(8);

    container(column![header, details, actions].spacing(6))
        .padding(12)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.12, 0.12, 0.15,
            ))),
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .into()
}

fn view_agent_detail<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let agent = match &state.selected_agent {
        Some(id) => state.agents.get(id),
        None => None,
    };

    let Some(agent) = agent else {
        return column![
            text("Agent not found").size(16),
            button(text("← Back")).on_press(to_message(CLIAgentMessage::DeselectAgent)),
        ]
        .into();
    };

    let back_btn = button(text("← Back to List").size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::DeselectAgent));

    let header = row![
        back_btn,
        Space::new().width(16),
        text(format!("{} Agent {}", agent.status.emoji(), &agent.id[..8])).size(18),
    ]
    .align_y(Alignment::Center);

    let info = column![
        text(format!("Workspace: {}", agent.workspace)).size(12),
        text(format!("Mode: {}", agent.mode.label())).size(12),
        text(format!("Status: {}", agent.status.label())).size(12),
        if let Some(model) = &agent.model {
            text(format!("Model: {}", model)).size(12)
        } else {
            text("").size(0)
        },
    ]
    .spacing(4);

    let prompt_section = container(
        column![
            text("Prompt:").size(12),
            text(&agent.prompt)
                .size(11)
                .color(iced::Color::from_rgb(0.7, 0.7, 0.7)),
        ]
        .spacing(4),
    )
    .padding(8)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.1, 0.1, 0.12,
        ))),
        ..Default::default()
    });

    // Events timeline
    let events: Vec<Element<'a, M>> = agent
        .events
        .iter()
        .map(|e| {
            let time = e.timestamp.format("%H:%M:%S").to_string();
            let content = e.content.as_deref().unwrap_or("");
            let tool = e.tool_name.as_deref().unwrap_or("");

            let line = match e.event_type {
                CLIEventType::ToolStarted => format!("{} 🔧 Tool: {}", time, tool),
                CLIEventType::ToolCompleted => format!("{} ✓ Tool completed: {}", time, tool),
                CLIEventType::Thinking => {
                    format!("{} 💭 {}", time, &content[..content.len().min(50)])
                }
                CLIEventType::Response => format!("{} 💬 Response received", time),
                _ => format!("{} {} {}", time, e.event_type.emoji(), content),
            };

            text(line).size(11).into()
        })
        .collect();

    let events_section = container(column![
        text("Events Timeline").size(14),
        Space::new().height(8),
        scrollable(column(events).spacing(4)).height(200),
    ])
    .padding(12)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.15,
        ))),
        border: iced::Border {
            radius: 6.0.into(),
            ..Default::default()
        },
        ..Default::default()
    });

    // Result section
    let result_section: Element<'a, M> = if let Some(result) = &agent.result {
        container(column![
            text("Result:").size(14),
            Space::new().height(8),
            scrollable(text(result).size(11)).height(150),
        ])
        .padding(12)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.1, 0.15, 0.1,
            ))),
            border: iced::Border {
                radius: 6.0.into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    scrollable(
        column![
            header,
            Space::new().height(16),
            info,
            Space::new().height(12),
            prompt_section,
            Space::new().height(12),
            events_section,
            Space::new().height(12),
            result_section,
        ]
        .spacing(4),
    )
    .height(Length::Fill)
    .into()
}

fn view_launch_form<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let form = &state.launch_form;
    let to_message1 = to_message.clone();
    let to_message2 = to_message.clone();
    let to_message3 = to_message.clone();

    let workspace_input = column![
        text("Workspace:").size(12),
        text_input("e.g., /home/e421/synapsix", &form.workspace)
            .padding(8)
            .on_input(move |s| to_message1(CLIAgentMessage::UpdateWorkspace(s))),
    ]
    .spacing(4);

    let prompt_input = column![
        text("Prompt:").size(12),
        text_input("What should the agent do?", &form.prompt)
            .padding(8)
            .on_input(move |s| to_message2(CLIAgentMessage::UpdatePrompt(s))),
    ]
    .spacing(4);

    // Preset selector
    let preset_options: Vec<Preset> = state.presets.clone();
    let selected_preset = state
        .selected_preset
        .as_ref()
        .and_then(|id| state.presets.iter().find(|p| &p.id == id))
        .cloned();

    let preset_selector = column![
        row![
            text("Prompt Preset:").size(12),
            Space::new().width(Length::Fill),
            button(text("+ New").size(10))
                .padding([2, 6])
                .on_press(to_message(CLIAgentMessage::OpenPresetEditor(None))),
        ]
        .align_y(Alignment::Center),
        Space::new().height(4),
        pick_list(preset_options, selected_preset, move |preset| to_message3(
            CLIAgentMessage::SelectPreset(Some(preset.id.clone()))
        ))
        .padding(8)
        .width(Length::Fill),
    ]
    .spacing(2);

    // Preset description (if one is selected)
    let preset_desc: Element<'a, M> = if let Some(preset) = state.get_selected_preset() {
        let desc = preset.description.as_deref().unwrap_or("No description");
        let edit_buttons: Element<'a, M> = if !preset.is_builtin {
            let to_msg_edit = to_message.clone();
            let to_msg_del = to_message.clone();
            let preset_id = preset.id.clone();
            let preset_id_del = preset.id.clone();
            row![
                button(text("Edit").size(10))
                    .padding([2, 6])
                    .on_press(to_msg_edit(CLIAgentMessage::OpenPresetEditor(Some(
                        preset_id
                    )))),
                Space::new().width(8),
                button(text("Delete").size(10))
                    .padding([2, 6])
                    .on_press(to_msg_del(CLIAgentMessage::DeletePreset(preset_id_del))),
            ]
            .into()
        } else {
            Space::new().height(0).into()
        };
        column![
            text(desc)
                .size(11)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            edit_buttons,
        ]
        .spacing(4)
        .into()
    } else {
        Space::new().height(0).into()
    };

    // Preview button
    let preview_btn = button(
        text(if state.show_prompt_preview {
            "Hide Preview"
        } else {
            "Show Preview"
        })
        .size(11),
    )
    .padding([4, 8])
    .on_press(to_message(CLIAgentMessage::TogglePromptPreview));

    // Prompt preview (if enabled)
    // Note: We build preview text inline to avoid lifetime issues
    let preview_section: Element<'a, M> = if state.show_prompt_preview {
        let preset = state.get_selected_preset();
        let prefix_preview = preset
            .and_then(|p| p.prefix.as_deref())
            .unwrap_or("[Default Prefix]");
        let suffix_preview = preset
            .and_then(|p| p.suffix.as_deref())
            .unwrap_or("[Default Suffix]");

        container(column![
            text("Full Prompt Preview:").size(12),
            Space::new().height(4),
            container(
                column![
                    text("--- PREFIX ---")
                        .size(9)
                        .color(iced::Color::from_rgb(0.4, 0.6, 0.4)),
                    text(prefix_preview).size(10).font(iced::Font::MONOSPACE),
                    Space::new().height(8),
                    text("--- YOUR TASK ---")
                        .size(9)
                        .color(iced::Color::from_rgb(0.4, 0.6, 0.4)),
                    text(&state.launch_form.prompt)
                        .size(10)
                        .font(iced::Font::MONOSPACE),
                    Space::new().height(8),
                    text("--- SUFFIX ---")
                        .size(9)
                        .color(iced::Color::from_rgb(0.4, 0.6, 0.4)),
                    text(suffix_preview).size(10).font(iced::Font::MONOSPACE),
                ]
                .spacing(2)
            )
            .padding(8)
            .style(|_| container::Style {
                background: Some(iced::Background::Color(iced::Color::from_rgb(
                    0.05, 0.05, 0.07
                ))),
                ..Default::default()
            }),
        ])
        .padding(8)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgb(
                0.08, 0.08, 0.1,
            ))),
            border: iced::Border {
                radius: 4.0.into(),
                ..Default::default()
            },
            ..Default::default()
        })
        .into()
    } else {
        Space::new().height(0).into()
    };

    let mode_selector = row![
        text("Mode:").size(12),
        Space::new().width(12),
        mode_button("Agent", AgentMode::Agent, form.mode, to_message.clone()),
        mode_button("Plan", AgentMode::Plan, form.mode, to_message.clone()),
        mode_button("Ask", AgentMode::Ask, form.mode, to_message.clone()),
    ]
    .spacing(8)
    .align_y(Alignment::Center);

    let options = row![
        checkbox_button(
            "Force trust",
            form.force,
            to_message(CLIAgentMessage::ToggleForce)
        ),
        Space::new().width(16),
        checkbox_button(
            "Auto-approve MCPs",
            form.approve_mcps,
            to_message(CLIAgentMessage::ToggleApproveMcps)
        ),
    ];

    let can_launch = !form.workspace.is_empty() && !form.prompt.is_empty();

    let launch_btn = button(text("▶ Launch Agent").size(14))
        .padding([10, 20])
        .on_press_maybe(if can_launch {
            Some(to_message(CLIAgentMessage::Launch))
        } else {
            None
        })
        .style(|_theme, status| {
            let bg = match status {
                button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
                button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
                _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    container(
        column![
            text("Launch CLI Agent").size(18),
            Space::new().height(16),
            workspace_input,
            Space::new().height(12),
            prompt_input,
            Space::new().height(12),
            preset_selector,
            preset_desc,
            Space::new().height(8),
            row![preview_btn].align_y(Alignment::Center),
            preview_section,
            Space::new().height(12),
            mode_selector,
            Space::new().height(12),
            options,
            Space::new().height(20),
            launch_btn,
        ]
        .width(Length::Fill),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.15,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            ..Default::default()
        },
        ..Default::default()
    })
    .into()
}

fn view_batch_form<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let form = &state.batch_form;
    let to_message1 = to_message.clone();

    let prompt_input = column![
        text("Prompt (applies to all workspaces):").size(12),
        text_input("What should agents do?", &form.prompt)
            .padding(8)
            .on_input(move |s| to_message1(CLIAgentMessage::UpdateBatchPrompt(s))),
    ]
    .spacing(4);

    let workspace_checkboxes: Vec<Element<'a, M>> = form
        .workspaces
        .iter()
        .map(|(ws, selected)| {
            let ws_clone = ws.clone();
            checkbox_button(
                ws.split('/').last().unwrap_or(ws),
                *selected,
                to_message(CLIAgentMessage::ToggleBatchWorkspace(ws_clone)),
            )
        })
        .collect();

    let workspaces_section = column![
        text("Select Workspaces:").size(12),
        Space::new().height(8),
        column(workspace_checkboxes).spacing(6),
    ];

    let selected_count = form.workspaces.iter().filter(|(_, s)| *s).count();

    let options = row![
        text(format!("Max concurrent: {}", form.max_concurrent)).size(12),
        Space::new().width(20),
        checkbox_button(
            "Stop on failure",
            form.stop_on_failure,
            to_message(CLIAgentMessage::ToggleStopOnFailure)
        ),
    ];

    let can_launch = !form.prompt.is_empty() && selected_count > 0;

    let launch_btn = button(text(format!("▶ Launch Batch ({} agents)", selected_count)).size(14))
        .padding([10, 20])
        .on_press_maybe(if can_launch {
            Some(to_message(CLIAgentMessage::LaunchBatch))
        } else {
            None
        })
        .style(|_theme, status| {
            let bg = match status {
                button::Status::Active => iced::Color::from_rgb(0.2, 0.4, 0.5),
                button::Status::Hovered => iced::Color::from_rgb(0.25, 0.5, 0.6),
                _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 6.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    container(
        column![
            text("Batch Launch").size(18),
            Space::new().height(16),
            prompt_input,
            Space::new().height(16),
            workspaces_section,
            Space::new().height(16),
            options,
            Space::new().height(20),
            launch_btn,
        ]
        .width(Length::Fill),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.15,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            ..Default::default()
        },
        ..Default::default()
    })
    .into()
}

fn mode_button<'a, M>(
    label: &'a str,
    target: AgentMode,
    current: AgentMode,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = target == current;
    let indicator = if is_active { "◉" } else { "○" };

    button(text(format!("{} {}", indicator, label)).size(12))
        .padding([4, 8])
        .on_press(to_message(CLIAgentMessage::SetMode(target)))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.35, 0.5)
            } else {
                iced::Color::TRANSPARENT
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::from_rgb(0.8, 0.8, 0.8),
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        })
        .into()
}

fn checkbox_button<'a, M: 'a + Clone>(
    label: &'a str,
    checked: bool,
    on_press: M,
) -> Element<'a, M> {
    let indicator = if checked { "☑" } else { "☐" };

    button(text(format!("{} {}", indicator, label)).size(12))
        .padding([4, 8])
        .on_press(on_press)
        .style(move |_theme, _status| button::Style {
            background: Some(iced::Background::Color(iced::Color::TRANSPARENT)),
            text_color: iced::Color::from_rgb(0.7, 0.7, 0.7),
            ..Default::default()
        })
        .into()
}

// =============================================================================
// Editor Modals
// =============================================================================

/// View for preset editor modal (overlay)
pub fn view_preset_editor_modal<'a, M>(
    editing: &'a EditingPreset,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let to_msg1 = to_message.clone();
    let to_msg2 = to_message.clone();
    let to_msg3 = to_message.clone();
    let to_msg4 = to_message.clone();
    let to_msg5 = to_message.clone();

    let title = if editing.id.is_some() {
        "Edit Preset"
    } else {
        "New Preset"
    };

    let name_input = column![
        text("Name:").size(12),
        text_input("Preset name", &editing.name)
            .padding(8)
            .on_input(move |s| to_msg1(CLIAgentMessage::UpdatePresetName(s))),
    ]
    .spacing(4);

    let desc_input = column![
        text("Description:").size(12),
        text_input("Optional description", &editing.description)
            .padding(8)
            .on_input(move |s| to_msg2(CLIAgentMessage::UpdatePresetDescription(s))),
    ]
    .spacing(4);

    let category_input = column![
        text("Category:").size(12),
        text_input("e.g., coding, research, custom", &editing.category)
            .padding(8)
            .on_input(move |s| to_msg3(CLIAgentMessage::UpdatePresetCategory(s))),
    ]
    .spacing(4);

    let prefix_input = column![
        text("Prefix (prepended before task):").size(12),
        text_input("Instructions at the start...", &editing.prefix)
            .padding(8)
            .on_input(move |s| to_msg4(CLIAgentMessage::UpdatePresetPrefix(s))),
        text("Tip: Use for general setup instructions")
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .spacing(4);

    let suffix_input = column![
        text("Suffix (appended after task):").size(12),
        text_input("Critical reminders at the end...", &editing.suffix)
            .padding(8)
            .on_input(move |s| to_msg5(CLIAgentMessage::UpdatePresetSuffix(s))),
        text("Tip: AI remembers the end best - put reminders here")
            .size(10)
            .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
    ]
    .spacing(4);

    let can_save = !editing.name.is_empty();

    let buttons = row![
        button(text("Cancel").size(12))
            .padding([8, 16])
            .on_press(to_message(CLIAgentMessage::ClosePresetEditor)),
        Space::new().width(Length::Fill),
        button(text("Save").size(12))
            .padding([8, 16])
            .on_press_maybe(if can_save {
                Some(to_message(CLIAgentMessage::SavePreset))
            } else {
                None
            })
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
                    button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
                    _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: iced::Color::WHITE,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            }),
    ];

    let modal_content = container(
        column![
            text(title).size(18),
            Space::new().height(16),
            name_input,
            Space::new().height(12),
            desc_input,
            Space::new().height(12),
            category_input,
            Space::new().height(16),
            prefix_input,
            Space::new().height(12),
            suffix_input,
            Space::new().height(20),
            buttons,
        ]
        .width(Length::Fixed(500.0)),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
        },
        ..Default::default()
    });

    // Backdrop + centered modal
    container(container(modal_content).center(Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(
                0.0, 0.0, 0.0, 0.7,
            ))),
            ..Default::default()
        })
        .into()
}

/// View for snippet editor modal (overlay)
pub fn view_snippet_editor_modal<'a, M>(
    editing: &'a EditingSnippet,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let to_msg1 = to_message.clone();
    let to_msg2 = to_message.clone();
    let to_msg3 = to_message.clone();
    let to_msg4 = to_message.clone();

    let title = if editing.id.is_some() {
        "Edit Snippet"
    } else {
        "New Snippet"
    };

    let name_input = column![
        text("Name:").size(12),
        text_input("Snippet name", &editing.name)
            .padding(8)
            .on_input(move |s| to_msg1(CLIAgentMessage::UpdateSnippetName(s))),
    ]
    .spacing(4);

    let desc_input = column![
        text("Description:").size(12),
        text_input("Optional description", &editing.description)
            .padding(8)
            .on_input(move |s| to_msg2(CLIAgentMessage::UpdateSnippetDescription(s))),
    ]
    .spacing(4);

    let position_selector = column![
        text("Position:").size(12),
        row![
            position_button("Prefix", "prefix", &editing.position, to_msg3.clone()),
            Space::new().width(8),
            position_button("Suffix", "suffix", &editing.position, to_msg3),
        ]
    ]
    .spacing(4);

    let content_input = column![
        text("Content:").size(12),
        text_input("Snippet text...", &editing.content)
            .padding(8)
            .on_input(move |s| to_msg4(CLIAgentMessage::UpdateSnippetContent(s))),
    ]
    .spacing(4);

    let can_save = !editing.name.is_empty() && !editing.content.is_empty();

    let buttons = row![
        button(text("Cancel").size(12))
            .padding([8, 16])
            .on_press(to_message(CLIAgentMessage::CloseSnippetEditor)),
        Space::new().width(Length::Fill),
        button(text("Save").size(12))
            .padding([8, 16])
            .on_press_maybe(if can_save {
                Some(to_message(CLIAgentMessage::SaveSnippet))
            } else {
                None
            })
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
                    button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
                    _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: iced::Color::WHITE,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            }),
    ];

    let modal_content = container(
        column![
            text(title).size(18),
            Space::new().height(16),
            name_input,
            Space::new().height(12),
            desc_input,
            Space::new().height(12),
            position_selector,
            Space::new().height(12),
            content_input,
            Space::new().height(20),
            buttons,
        ]
        .width(Length::Fixed(500.0)),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
        },
        ..Default::default()
    });

    // Backdrop + centered modal
    container(container(modal_content).center(Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(
                0.0, 0.0, 0.0, 0.7,
            ))),
            ..Default::default()
        })
        .into()
}

fn position_button<'a, M>(
    label: &'a str,
    value: &'a str,
    current: &str,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let is_active = value == current;
    let indicator = if is_active { "◉" } else { "○" };

    button(text(format!("{} {}", indicator, label)).size(12))
        .padding([4, 8])
        .on_press(to_message(CLIAgentMessage::UpdateSnippetPosition(
            value.to_string(),
        )))
        .style(move |_theme, _status| {
            let bg = if is_active {
                iced::Color::from_rgb(0.25, 0.35, 0.5)
            } else {
                iced::Color::TRANSPARENT
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::from_rgb(0.8, 0.8, 0.8),
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        })
        .into()
}

/// Workspace overrides editor modal
pub fn view_workspace_overrides_modal<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    // Build list of current overrides
    let override_items: Vec<Element<'a, M>> = state
        .workspace_overrides
        .iter()
        .map(|(workspace, preset_id)| {
            let to_msg = to_message.clone();
            let ws = workspace.clone();
            let workspace_short = workspace
                .rsplit('/')
                .next()
                .unwrap_or(workspace)
                .to_string();
            let preset_name = state
                .presets
                .iter()
                .find(|p| &p.id == preset_id)
                .map(|p| p.name.clone())
                .unwrap_or_else(|| preset_id.clone());

            row![
                text(workspace_short).size(12),
                Space::new().width(8),
                text("→")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().width(8),
                text(preset_name)
                    .size(12)
                    .color(iced::Color::from_rgb(0.4, 0.8, 0.4)),
                Space::new().width(Length::Fill),
                button(text("✕").size(10))
                    .padding([2, 6])
                    .on_press(to_msg(CLIAgentMessage::ClearWorkspaceOverride(ws)))
                    .style(|_theme, _status| button::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.5, 0.2, 0.2
                        ))),
                        text_color: iced::Color::WHITE,
                        border: iced::Border {
                            radius: 4.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    }),
            ]
            .padding(8)
            .align_y(Alignment::Center)
            .into()
        })
        .collect();

    let overrides_list: Element<'a, M> = if override_items.is_empty() {
        column![
            text("No workspace overrides configured.")
                .size(12)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(8),
            text("Set overrides by typing a workspace path and selecting a preset.")
                .size(11)
                .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
        ]
        .into()
    } else {
        scrollable(column(override_items).spacing(4))
            .height(Length::Fixed(200.0))
            .into()
    };

    let close_btn = button(text("Close").size(12))
        .padding([8, 16])
        .on_press(to_message(CLIAgentMessage::CloseWorkspaceOverridesEditor));

    let modal_content = container(
        column![
            text("Workspace Overrides").size(18),
            Space::new().height(8),
            text("Auto-select presets based on workspace path.")
                .size(11)
                .color(iced::Color::from_rgb(0.6, 0.6, 0.6)),
            Space::new().height(16),
            overrides_list,
            Space::new().height(8),
            text("Tip: When you change workspace in Launch form, the preset will auto-select.")
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
            Space::new().height(16),
            close_btn,
        ]
        .width(Length::Fixed(500.0)),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
        },
        ..Default::default()
    });

    // Backdrop + centered modal
    container(container(modal_content).center(Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(
                0.0, 0.0, 0.0, 0.7,
            ))),
            ..Default::default()
        })
        .into()
}

// =============================================================================
// Dialog Inbox Views (Orchestration)
// =============================================================================

/// View for the dialog inbox - shows pending worker dialogs
fn view_dialog_inbox<'a, M>(
    state: &'a CLIAgentsState,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let refresh_btn = button(text("⟳ Refresh").size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::RefreshDialogs));

    let header = row![
        text("📥 Dialog Inbox").size(18),
        Space::new().width(12),
        text(format!("{} pending", state.pending_dialogs.len()))
            .size(12)
            .color(if state.pending_dialogs.is_empty() {
                iced::Color::from_rgb(0.5, 0.5, 0.5)
            } else {
                iced::Color::from_rgb(1.0, 0.8, 0.3)
            }),
        Space::new().width(Length::Fill),
        refresh_btn,
    ]
    .align_y(Alignment::Center);

    if state.pending_dialogs.is_empty() {
        return container(
            column![
                header,
                Space::new().height(40),
                text("No pending dialogs from workers").size(16),
                Space::new().height(8),
                text("When CLI agents ask questions via synapsix-dialog-cli,")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                text("they'll appear here for you to respond.")
                    .size(12)
                    .color(iced::Color::from_rgb(0.5, 0.5, 0.5)),
                Space::new().height(16),
                text("Tip: Use 'overnight-worker' preset for agents that ask questions")
                    .size(11)
                    .color(iced::Color::from_rgb(0.4, 0.6, 0.4)),
            ]
            .align_x(Alignment::Center),
        )
        .width(Length::Fill)
        .padding(20)
        .into();
    }

    let dialog_cards: Vec<Element<'a, M>> = state
        .pending_dialogs
        .iter()
        .map(|dialog| view_dialog_card(dialog, to_message.clone()))
        .collect();

    container(
        column![
            header,
            Space::new().height(16),
            scrollable(column(dialog_cards).spacing(12)).height(Length::Fill),
        ]
        .spacing(4),
    )
    .padding(20)
    .into()
}

/// Card view for a single pending dialog
fn view_dialog_card<'a, M>(
    dialog: &'a PendingDialog,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let type_emoji = match dialog.dialog_type.as_str() {
        "choice" => "📋",
        "confirmation" => "❓",
        "text" => "✏️",
        "slider" => "🎚️",
        _ => "💬",
    };

    // Source badge
    let source_color = match dialog.source {
        DialogSource::SessionAgent => iced::Color::from_rgb(0.3, 0.6, 0.9),
        DialogSource::SubAgent => iced::Color::from_rgb(0.6, 0.4, 0.8),
        DialogSource::External => iced::Color::from_rgb(0.8, 0.6, 0.3),
        DialogSource::Orchestrator => iced::Color::from_rgb(0.4, 0.4, 0.4),
    };

    let title_row = row![
        text(format!("{} {}", type_emoji, dialog.title)).size(14),
        Space::new().width(8),
        container(text(format!("{}", dialog.source)).size(9))
            .padding([2, 6])
            .style(move |_| container::Style {
                background: Some(iced::Background::Color(source_color)),
                border: iced::Border {
                    radius: 3.0.into(),
                    ..Default::default()
                },
                text_color: Some(iced::Color::WHITE),
                ..Default::default()
            }),
        Space::new().width(4),
        text(dialog.priority.emoji()).size(12),
        Space::new().width(Length::Fill),
        text(&dialog.id[..8.min(dialog.id.len())])
            .size(10)
            .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
    ]
    .align_y(Alignment::Center);

    // Truncate prompt if too long - use owned String to avoid lifetime issues
    let prompt_section = {
        let prompt_text = if dialog.prompt.len() > 200 {
            format!("{}...", &dialog.prompt[..200])
        } else {
            dialog.prompt.clone()
        };
        text(prompt_text)
            .size(12)
            .color(iced::Color::from_rgb(0.7, 0.7, 0.7))
    };

    // Show options if it's a choice dialog
    let options_section: Element<'a, M> = if let Some(options) = &dialog.options {
        let option_chips: Vec<Element<'a, M>> = options
            .iter()
            .take(5) // Limit to 5 visible
            .map(|opt| {
                container(text(&opt.label).size(10))
                    .padding([2, 6])
                    .style(|_| container::Style {
                        background: Some(iced::Background::Color(iced::Color::from_rgb(
                            0.2, 0.25, 0.3,
                        ))),
                        border: iced::Border {
                            radius: 3.0.into(),
                            ..Default::default()
                        },
                        ..Default::default()
                    })
                    .into()
            })
            .collect();

        let more_text: Element<'a, M> = if options.len() > 5 {
            text(format!("+{} more", options.len() - 5))
                .size(10)
                .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                .into()
        } else {
            Space::new().width(0).into()
        };

        row(option_chips)
            .push(more_text)
            .spacing(4)
            .into()
    } else {
        Space::new().height(0).into()
    };

    let respond_btn = button(text("Respond →").size(12))
        .padding([6, 12])
        .on_press(to_message(CLIAgentMessage::OpenDialogResponseModal(
            dialog.id.clone(),
        )))
        .style(|_theme, status| {
            let bg = match status {
                button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
                button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
                _ => iced::Color::from_rgb(0.2, 0.2, 0.25),
            };
            button::Style {
                background: Some(iced::Background::Color(bg)),
                text_color: iced::Color::WHITE,
                border: iced::Border {
                    radius: 4.0.into(),
                    ..Default::default()
                },
                ..Default::default()
            }
        });

    let actions_row = row![Space::new().width(Length::Fill), respond_btn,];

    container(
        column![
            title_row,
            Space::new().height(8),
            prompt_section,
            Space::new().height(8),
            options_section,
            Space::new().height(8),
            actions_row,
        ]
        .spacing(4),
    )
    .padding(16)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.12, 0.12, 0.15,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.25, 0.25, 0.3),
        },
        ..Default::default()
    })
    .into()
}

/// Modal for responding to a dialog
fn view_dialog_response_modal<'a, M>(
    dialog: &'a PendingDialog,
    response: &'a DialogResponse,
    to_message: impl Fn(CLIAgentMessage) -> M + 'a + Clone,
) -> Element<'a, M>
where
    M: 'a + Clone,
{
    let to_msg1 = to_message.clone();
    let to_msg2 = to_message.clone();

    let title = text(format!("📥 {}", dialog.title)).size(18);

    // Show full prompt
    let prompt_section = container(
        scrollable(text(&dialog.prompt).size(12)).height(Length::Fixed(120.0)),
    )
    .padding(12)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.08, 0.08, 0.1,
        ))),
        border: iced::Border {
            radius: 4.0.into(),
            ..Default::default()
        },
        ..Default::default()
    });

    // Response input based on dialog type
    let response_input: Element<'a, M> = match dialog.dialog_type.as_str() {
        "choice" => {
            if let Some(options) = &dialog.options {
                let option_buttons: Vec<Element<'a, M>> = options
                    .iter()
                    .map(|opt| {
                        let is_selected = response.selection == opt.value;
                        let to_msg = to_message.clone();
                        let value = opt.value.clone();

                        button(
                            column![
                                text(&opt.label).size(12),
                                if let Some(desc) = &opt.description {
                                    text(desc)
                                        .size(10)
                                        .color(iced::Color::from_rgb(0.5, 0.5, 0.5))
                                } else {
                                    text("").size(0)
                                },
                            ]
                            .spacing(2),
                        )
                        .padding([8, 12])
                        .width(Length::Fill)
                        .on_press(to_msg(CLIAgentMessage::UpdateDialogSelection(value)))
                        .style(move |_theme, _status| {
                            let bg = if is_selected {
                                iced::Color::from_rgb(0.2, 0.5, 0.3)
                            } else {
                                iced::Color::from_rgb(0.15, 0.15, 0.18)
                            };
                            button::Style {
                                background: Some(iced::Background::Color(bg)),
                                text_color: iced::Color::WHITE,
                                border: iced::Border {
                                    radius: 4.0.into(),
                                    width: if is_selected { 2.0 } else { 1.0 },
                                    color: if is_selected {
                                        iced::Color::from_rgb(0.3, 0.7, 0.4)
                                    } else {
                                        iced::Color::from_rgb(0.25, 0.25, 0.3)
                                    },
                                },
                                ..Default::default()
                            }
                        })
                        .into()
                    })
                    .collect();

                column![
                    text("Select an option:").size(12),
                    Space::new().height(8),
                    column(option_buttons).spacing(6),
                ]
                .into()
            } else {
                text_input("Enter selection value...", &response.selection)
                    .padding(8)
                    .on_input(move |s| to_msg1(CLIAgentMessage::UpdateDialogSelection(s)))
                    .into()
            }
        }
        "confirmation" => {
            let is_yes = response.selection == "true";
            let is_no = response.selection == "false";
            let to_msg_yes = to_message.clone();
            let to_msg_no = to_message.clone();

            row![
                button(text("✓ Yes").size(14))
                    .padding([10, 20])
                    .on_press(to_msg_yes(CLIAgentMessage::UpdateDialogSelection(
                        "true".to_string()
                    )))
                    .style(move |_theme, _status| {
                        let bg = if is_yes {
                            iced::Color::from_rgb(0.2, 0.5, 0.3)
                        } else {
                            iced::Color::from_rgb(0.15, 0.15, 0.18)
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
                Space::new().width(12),
                button(text("✕ No").size(14))
                    .padding([10, 20])
                    .on_press(to_msg_no(CLIAgentMessage::UpdateDialogSelection(
                        "false".to_string()
                    )))
                    .style(move |_theme, _status| {
                        let bg = if is_no {
                            iced::Color::from_rgb(0.5, 0.25, 0.2)
                        } else {
                            iced::Color::from_rgb(0.15, 0.15, 0.18)
                        };
                        button::Style {
                            background: Some(iced::Background::Color(bg)),
                            text_color: iced::Color::WHITE,
                            border: iced::Border {
                                radius: 4.0.into(),
                                ..Default::default()
                            },
                            ..Default::default()
                        }
                    }),
            ]
            .into()
        }
        "text" => {
            column![
                text("Enter your response:").size(12),
                Space::new().height(4),
                text_input("Type your response...", &response.selection)
                    .padding(8)
                    .on_input(move |s| to_msg1(CLIAgentMessage::UpdateDialogSelection(s))),
            ]
            .into()
        }
        "slider" => {
            column![
                text("Enter a numeric value:").size(12),
                Space::new().height(4),
                text_input("Enter number...", &response.selection)
                    .padding(8)
                    .on_input(move |s| to_msg1(CLIAgentMessage::UpdateDialogSelection(s))),
            ]
            .into()
        }
        _ => {
            text_input("Enter response...", &response.selection)
                .padding(8)
                .on_input(move |s| to_msg1(CLIAgentMessage::UpdateDialogSelection(s)))
                .into()
        }
    };

    // Comment input
    let comment_input = column![
        text("Comment (optional):").size(12),
        Space::new().height(4),
        text_input("Add a note for the agent...", &response.comment)
            .padding(8)
            .on_input(move |s| to_msg2(CLIAgentMessage::UpdateDialogComment(s))),
        text("Tip: Include context or follow-up instructions")
            .size(10)
            .color(iced::Color::from_rgb(0.4, 0.4, 0.4)),
    ]
    .spacing(2);

    let can_submit = !response.selection.is_empty();

    let buttons = row![
        button(text("Cancel").size(12))
            .padding([8, 16])
            .on_press(to_message(CLIAgentMessage::CloseDialogResponseModal)),
        Space::new().width(Length::Fill),
        button(text("Submit Response").size(12))
            .padding([8, 16])
            .on_press_maybe(if can_submit {
                Some(to_message(CLIAgentMessage::SubmitDialogResponse))
            } else {
                None
            })
            .style(|_theme, status| {
                let bg = match status {
                    button::Status::Active => iced::Color::from_rgb(0.2, 0.5, 0.3),
                    button::Status::Hovered => iced::Color::from_rgb(0.25, 0.6, 0.35),
                    _ => iced::Color::from_rgb(0.15, 0.15, 0.15),
                };
                button::Style {
                    background: Some(iced::Background::Color(bg)),
                    text_color: iced::Color::WHITE,
                    border: iced::Border {
                        radius: 4.0.into(),
                        ..Default::default()
                    },
                    ..Default::default()
                }
            }),
    ];

    let modal_content = container(
        column![
            title,
            Space::new().height(12),
            prompt_section,
            Space::new().height(16),
            response_input,
            Space::new().height(16),
            comment_input,
            Space::new().height(20),
            buttons,
        ]
        .width(Length::Fixed(550.0)),
    )
    .padding(20)
    .style(|_| container::Style {
        background: Some(iced::Background::Color(iced::Color::from_rgb(
            0.15, 0.15, 0.18,
        ))),
        border: iced::Border {
            radius: 8.0.into(),
            width: 1.0,
            color: iced::Color::from_rgb(0.3, 0.3, 0.35),
        },
        ..Default::default()
    });

    // Backdrop + centered modal
    container(container(modal_content).center(Length::Fill))
        .width(Length::Fill)
        .height(Length::Fill)
        .style(|_| container::Style {
            background: Some(iced::Background::Color(iced::Color::from_rgba(
                0.0, 0.0, 0.0, 0.7,
            ))),
            ..Default::default()
        })
        .into()
}
