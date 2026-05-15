package com.continuumstudio.tablet.data.models

import java.time.Instant

data class Dialog(
    val id: String,
    val type: DialogType,
    val title: String,
    val prompt: String,
    val options: List<DialogOption>? = null,
    val defaultValue: String? = null,
    val source: DialogSource = DialogSource.External,
    val priority: DialogPriority = DialogPriority.Normal,
    val agentId: String? = null,
    val workspace: String? = null,
    val createdAt: Instant = Instant.now(),
    val isAnswered: Boolean = false,
    val answer: String? = null,
)

enum class DialogType {
    Confirmation,
    Choice,
    Text,
    Slider,
}

data class DialogOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

enum class DialogSource {
    Orchestrator,
    SessionAgent,
    SubAgent,
    External,
}

enum class DialogPriority {
    Low,
    Normal,
    High,
    Critical,
}

data class Agent(
    val id: String,
    val status: AgentStatus,
    val workspace: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val error: String? = null,
)

enum class AgentStatus {
    Starting,
    Running,
    Waiting,
    Parked,
    Completed,
    Failed,
}

data class ActivityEvent(
    val id: String,
    val type: ActivityType,
    val title: String,
    val body: String? = null,
    val agentId: String? = null,
    val timestamp: Instant = Instant.now(),
    val eventType: String? = null,
    val source: String? = null,
)

enum class ActivityType {
    DialogSent,
    DialogResponse,
    AgentStarted,
    AgentCompleted,
    AgentFailed,
    FileEdit,
    Command,
    ToolCall,
}

data class Task(
    val id: String,
    val content: String,
    val priority: TaskPriority,
    val status: TaskStatus,
    val createdBy: String? = null,
    val agentId: String? = null,
    val createdAt: Instant = Instant.now(),
)

enum class TaskPriority {
    Critical,
    High,
    Medium,
    Low,
    Backlog,
}

enum class TaskStatus {
    Pending,
    Claimed,
    InProgress,
    Completed,
    Cancelled,
}
