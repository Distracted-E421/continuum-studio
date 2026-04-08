package com.continuumstudio.tablet.data.network

import com.continuumstudio.tablet.data.models.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SynapsixClient(
    private val phoenixUrl: String = "http://100.109.236.61:4001",
    private val dialogUrl: String = "http://100.109.236.61:8080"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    // Get pending (actionable) dialogs from multiple sources
    suspend fun getDialogs(): Result<List<Dialog>> = withContext(Dispatchers.IO) {
        val dialogs = mutableListOf<Dialog>()
        
        // 1. Check for current active dialog (shown on desktop GUI)
        try {
            val currentRequest = Request.Builder()
                .url("$dialogUrl/api/current")
                .get()
                .build()
            
            val currentResponse = client.newCall(currentRequest).execute()
            if (currentResponse.isSuccessful) {
                val currentBody = currentResponse.body?.string() ?: "{}"
                val currentDialogResponse: CurrentDialogResponse = gson.fromJson(currentBody, CurrentDialogResponse::class.java)
                if (currentDialogResponse.success && currentDialogResponse.data != null) {
                    dialogs.add(currentDialogResponse.data.toDialog())
                }
            }
        } catch (e: Exception) {
            // Ignore - current might not exist
        }
        
        // 2. Get agent dialogs (dialogs from CLI agents)
        try {
            val agentRequest = Request.Builder()
                .url("$dialogUrl/api/agent-dialogs")
                .get()
                .build()
            
            val agentResponse = client.newCall(agentRequest).execute()
            if (agentResponse.isSuccessful) {
                val body = agentResponse.body?.string() ?: "{}"
                val agentDialogsResponse: AgentDialogsResponse = gson.fromJson(body, AgentDialogsResponse::class.java)
                if (agentDialogsResponse.success && agentDialogsResponse.data != null) {
                    dialogs.addAll(agentDialogsResponse.data.map { it.toDialog() })
                }
            }
        } catch (e: Exception) {
            // Ignore - might not have agent dialogs
        }
        
        Result.success(dialogs.distinctBy { it.id })
    }
    
    // Get dialog history (includes answered dialogs) from /api/history
    suspend fun getDialogHistory(limit: Int = 50): Result<List<Dialog>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$dialogUrl/api/history?limit=$limit")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val dialogResponse: DialogHistoryResponse = gson.fromJson(body, DialogHistoryResponse::class.java)
                if (dialogResponse.success) {
                    Result.success(dialogResponse.data?.map { it.toDialog() } ?: emptyList())
                } else {
                    Result.failure(Exception(dialogResponse.error ?: "Unknown error"))
                }
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun respondToDialog(dialogId: String, selection: String, comment: String? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "id" to dialogId,
                "selection" to selection,
                "comment" to comment
            )).toRequestBody(jsonMediaType)
            
            val request = Request.Builder()
                .url("$dialogUrl/api/answer")
                .post(body)
                .build()
            
            val httpResponse = client.newCall(request).execute()
            if (httpResponse.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${httpResponse.code}: ${httpResponse.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Agents come from Phoenix server on port 4001
    suspend fun getAgents(): Result<List<Agent>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$phoenixUrl/api/cli-agents")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val agentsResponse: AgentsResponse = gson.fromJson(body, AgentsResponse::class.java)
                Result.success(agentsResponse.agents?.map { it.toAgent() } ?: emptyList())
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun spawnAgent(prompt: String, workspace: String? = null, preset: String? = null): Result<Agent> = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(mapOf(
                "prompt" to prompt,
                "workspace" to workspace,
                "preset" to preset
            )).toRequestBody(jsonMediaType)
            
            val request = Request.Builder()
                .url("$phoenixUrl/api/cli-agents/spawn")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: "{}"
                val agentDto: AgentDto = gson.fromJson(responseBody, AgentDto::class.java)
                Result.success(agentDto.toAgent())
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getTasks(): Result<List<Task>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$phoenixUrl/api/tasks")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val tasksResponse: TasksResponse = gson.fromJson(body, TasksResponse::class.java)
                Result.success(tasksResponse.tasks?.map { it.toTask() } ?: emptyList())
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getParkedAgents(): Result<List<Agent>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$phoenixUrl/api/parking/agents")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val agentsResponse: AgentsResponse = gson.fromJson(body, AgentsResponse::class.java)
                Result.success(agentsResponse.agents?.map { it.toAgent() } ?: emptyList())
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun unparkAgent(agentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$phoenixUrl/api/parking/agents/$agentId/unpark")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Ping both services to check connectivity
    suspend fun ping(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val phoenixRequest = Request.Builder()
                .url("$phoenixUrl/health")
                .get()
                .build()
            
            val dialogRequest = Request.Builder()
                .url("$dialogUrl/api/history")
                .head()
                .build()
            
            val phoenixOk = try { client.newCall(phoenixRequest).execute().isSuccessful } catch (e: Exception) { false }
            val dialogOk = try { client.newCall(dialogRequest).execute().isSuccessful } catch (e: Exception) { false }
            
            Result.success(phoenixOk || dialogOk)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    // Update server URLs
    fun updateUrls(phoenixUrl: String, dialogUrl: String): SynapsixClient {
        return SynapsixClient(phoenixUrl, dialogUrl)
    }
}

// Response wrapper for /api/current (current active dialog)
data class CurrentDialogResponse(
    val success: Boolean,
    val data: CurrentDialogDto? = null,
    val error: String? = null
)

// Current active dialog from /api/current
// Note: dialog_type is a nested object with type and options
data class CurrentDialogDto(
    val id: String,
    val dialog_type: CurrentDialogTypeDto,
    val title: String,
    val prompt: String,
    val timeout_ms: Long? = null,
    val is_paused: Boolean? = null,
    val time_remaining_ratio: Double? = null
) {
    fun toDialog(): Dialog {
        val dialogTypeName = dialog_type.type.lowercase()
        return Dialog(
            id = id,
            type = when (dialogTypeName) {
                "confirmation", "confirm" -> DialogType.Confirmation
                "choice" -> DialogType.Choice
                "text" -> DialogType.Text
                "slider" -> DialogType.Slider
                else -> DialogType.Choice
            },
            title = title,
            prompt = prompt,
            options = dialog_type.options?.map { DialogOption(it.value, it.label, it.description) }
                ?: if (dialogTypeName == "confirmation" || dialogTypeName == "confirm") {
                    listOf(
                        DialogOption("true", dialog_type.confirm_text ?: "Yes"),
                        DialogOption("false", dialog_type.cancel_text ?: "No")
                    )
                } else null,
            defaultValue = dialog_type.default,
            source = DialogSource.Orchestrator,
            priority = DialogPriority.High,
            agentId = null,
            workspace = null,
            isAnswered = false,
            answer = null
        )
    }
}

// The nested dialog_type object
data class CurrentDialogTypeDto(
    val type: String,
    val options: List<DialogOptionDto>? = null,
    val allow_multiple: Boolean? = null,
    val confirm_text: String? = null,
    val cancel_text: String? = null,
    val default: String? = null,
    val placeholder: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null
)

// Response wrapper for /api/agent-dialogs (pending dialogs with options)
data class AgentDialogsResponse(
    val success: Boolean,
    val data: List<AgentDialogDto>? = null,
    val error: String? = null
)

// Response wrapper for /api/history (dialog history)
data class DialogHistoryResponse(
    val success: Boolean,
    val data: List<DialogDto>? = null,
    val error: String? = null
)

// Response wrapper for Phoenix CLI agents endpoint
data class AgentsResponse(
    val agents: List<AgentDto>? = null
)

// Response wrapper for Phoenix tasks endpoint
data class TasksResponse(
    val tasks: List<TaskDto>? = null
)

// Pending dialog from /api/agent-dialogs (includes options!)
data class AgentDialogDto(
    val id: String,
    val dialog_type: String,
    val title: String,
    val prompt: String,
    val options: List<DialogOptionDto>? = null,
    val timeout_ms: Long? = null,
    val agent_id: String? = null,
    val source: String? = null,
    val priority: String? = null,
    val workspace: String? = null,
    val orchestrator_id: String? = null
) {
    fun toDialog(): Dialog = Dialog(
        id = id,
        type = when (dialog_type.lowercase()) {
            "confirmation", "confirm" -> DialogType.Confirmation
            "choice" -> DialogType.Choice
            "text" -> DialogType.Text
            "slider" -> DialogType.Slider
            else -> DialogType.Choice
        },
        title = title,
        prompt = prompt,
        options = options?.map { DialogOption(it.value, it.label, it.description) },
        defaultValue = null,
        source = when (source?.lowercase()) {
            "orchestrator" -> DialogSource.Orchestrator
            "session_agent", "sessionagent" -> DialogSource.SessionAgent
            "sub_agent", "subagent" -> DialogSource.SubAgent
            else -> DialogSource.External
        },
        priority = when (priority?.lowercase()) {
            "critical" -> DialogPriority.Critical
            "high" -> DialogPriority.High
            "low" -> DialogPriority.Low
            else -> DialogPriority.Normal
        },
        agentId = agent_id,
        workspace = workspace,
        isAnswered = false,
        answer = null
    )
}

// Dialog from /api/history (may be answered)
data class DialogDto(
    val id: String,
    val dialog_type: String,
    val title: String,
    val prompt: String,
    val options: List<DialogOptionDto>? = null,
    val default: String? = null,
    val selection: String? = null,
    val cancelled: Boolean? = null,
    val comment: String? = null,
    val created_at: String? = null,
    val answered_at: String? = null
) {
    fun toDialog(): Dialog = Dialog(
        id = id,
        type = when (dialog_type.lowercase()) {
            "confirmation", "confirm" -> DialogType.Confirmation
            "choice" -> DialogType.Choice
            "text" -> DialogType.Text
            "slider" -> DialogType.Slider
            else -> DialogType.Choice
        },
        title = title,
        prompt = prompt,
        options = options?.map { DialogOption(it.value, it.label, it.description) },
        defaultValue = default,
        source = DialogSource.External,
        priority = DialogPriority.Normal,
        agentId = null,
        workspace = null,
        isAnswered = answered_at != null,
        answer = selection
    )
}

data class DialogOptionDto(
    val value: String,
    val label: String,
    val description: String? = null
)

data class AgentDto(
    val id: String,
    val status: String,
    val workspace: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val started_at: String? = null,
    val completed_at: String? = null,
    val error: String? = null
) {
    fun toAgent(): Agent = Agent(
        id = id,
        status = when (status.lowercase()) {
            "starting" -> AgentStatus.Starting
            "running" -> AgentStatus.Running
            "waiting" -> AgentStatus.Waiting
            "parked" -> AgentStatus.Parked
            "completed" -> AgentStatus.Completed
            "failed" -> AgentStatus.Failed
            else -> AgentStatus.Running
        },
        workspace = workspace,
        model = model,
        prompt = prompt,
        error = error
    )
}

data class TaskDto(
    val id: String,
    val content: String,
    val priority: String,
    val status: String,
    val created_by: String? = null,
    val agent_id: String? = null,
    val created_at: String? = null
) {
    fun toTask(): Task = Task(
        id = id,
        content = content,
        priority = when (priority.lowercase()) {
            "critical" -> TaskPriority.Critical
            "high" -> TaskPriority.High
            "medium" -> TaskPriority.Medium
            "low" -> TaskPriority.Low
            else -> TaskPriority.Backlog
        },
        status = when (status.lowercase()) {
            "pending" -> TaskStatus.Pending
            "claimed" -> TaskStatus.Claimed
            "in_progress", "inprogress" -> TaskStatus.InProgress
            "completed" -> TaskStatus.Completed
            "cancelled" -> TaskStatus.Cancelled
            else -> TaskStatus.Pending
        },
        createdBy = created_by,
        agentId = agent_id
    )
}
