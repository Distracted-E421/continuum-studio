package com.continuumstudio.tablet.data.network

import com.continuumstudio.tablet.data.models.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.*
import java.util.concurrent.TimeUnit

sealed class WebSocketEvent {
    data object Connected : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
    data class Error(val message: String) : WebSocketEvent()
    
    data class DialogCreated(val dialog: Dialog) : WebSocketEvent()
    data class DialogAnswered(val dialogId: String) : WebSocketEvent()
    data class DialogEscalated(val dialogId: String) : WebSocketEvent()
    
    data class AgentStarted(val agent: Agent) : WebSocketEvent()
    data class AgentCompleted(val agentId: String, val result: String?) : WebSocketEvent()
    data class AgentFailed(val agentId: String, val error: String) : WebSocketEvent()
    
    data class ActivityEvent(val event: com.continuumstudio.tablet.data.models.ActivityEvent) : WebSocketEvent()
}

class WebSocketManager(
    private val baseUrl: String = "ws://100.109.236.61:4001"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var orchestratorSocket: WebSocket? = null
    private var agentsSocket: WebSocket? = null
    private var activitySocket: WebSocket? = null
    
    private val _events = MutableSharedFlow<WebSocketEvent>(replay = 0, extraBufferCapacity = 64)
    val events: Flow<WebSocketEvent> = _events.asSharedFlow()
    
    private var isConnected = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val baseReconnectDelay = 1000L
    
    fun connect() {
        connectOrchestratorSocket()
        connectAgentsSocket()
        connectActivitySocket()
    }
    
    fun disconnect() {
        isConnected = false
        orchestratorSocket?.close(1000, "User disconnect")
        agentsSocket?.close(1000, "User disconnect")
        activitySocket?.close(1000, "User disconnect")
        orchestratorSocket = null
        agentsSocket = null
        activitySocket = null
    }
    
    private fun connectOrchestratorSocket() {
        val request = Request.Builder()
            .url("$baseUrl/ws/orchestrator")
            .build()
        
        orchestratorSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                reconnectAttempts = 0
                scope.launch { _events.emit(WebSocketEvent.Connected) }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmitEvent(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { 
                    _events.emit(WebSocketEvent.Error(t.message ?: "Connection failed"))
                    scheduleReconnect { connectOrchestratorSocket() }
                }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isConnected) {
                    scope.launch { 
                        _events.emit(WebSocketEvent.Disconnected)
                        scheduleReconnect { connectOrchestratorSocket() }
                    }
                }
            }
        })
    }
    
    private fun connectAgentsSocket() {
        val request = Request.Builder()
            .url("$baseUrl/ws/cli-agents")
            .build()
        
        agentsSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmitAgentEvent(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { scheduleReconnect { connectAgentsSocket() } }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isConnected) {
                    scope.launch { scheduleReconnect { connectAgentsSocket() } }
                }
            }
        })
    }
    
    private fun connectActivitySocket() {
        val request = Request.Builder()
            .url("$baseUrl/ws/activity")
            .build()
        
        activitySocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndEmitActivityEvent(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch { scheduleReconnect { connectActivitySocket() } }
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isConnected) {
                    scope.launch { scheduleReconnect { connectActivitySocket() } }
                }
            }
        })
    }
    
    private suspend fun scheduleReconnect(connectFn: () -> Unit) {
        if (reconnectAttempts < maxReconnectAttempts) {
            val delayMs = baseReconnectDelay * (1 shl reconnectAttempts)
            reconnectAttempts++
            delay(delayMs)
            connectFn()
        }
    }
    
    private fun parseAndEmitEvent(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            
            val event = when (type) {
                "connected" -> WebSocketEvent.Connected
                "dialog_created", "DialogNew" -> {
                    val dialogJson = json.getAsJsonObject("dialog") ?: json.getAsJsonObject("data")
                    val dialogDto = gson.fromJson(dialogJson, DialogDto::class.java)
                    WebSocketEvent.DialogCreated(dialogDto.toDialog())
                }
                "dialog_answered", "DialogResponded" -> {
                    val dialogId = json.get("dialog_id")?.asString 
                        ?: json.get("id")?.asString 
                        ?: return
                    WebSocketEvent.DialogAnswered(dialogId)
                }
                "dialog_escalated" -> {
                    val dialogId = json.get("dialog_id")?.asString ?: return
                    WebSocketEvent.DialogEscalated(dialogId)
                }
                "ping" -> null
                else -> null
            }
            
            event?.let { scope.launch { _events.emit(it) } }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }
    
    private fun parseAndEmitAgentEvent(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            
            val event = when (type) {
                "started" -> {
                    val agentDto = gson.fromJson(json, AgentDto::class.java)
                    WebSocketEvent.AgentStarted(agentDto.toAgent())
                }
                "completed" -> {
                    val agentId = json.get("agent_id")?.asString ?: return
                    val result = json.get("result")?.asString
                    WebSocketEvent.AgentCompleted(agentId, result)
                }
                "failed", "error" -> {
                    val agentId = json.get("agent_id")?.asString ?: return
                    val error = json.get("error")?.asString ?: "Unknown error"
                    WebSocketEvent.AgentFailed(agentId, error)
                }
                else -> null
            }
            
            event?.let { scope.launch { _events.emit(it) } }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }
    
    private fun parseAndEmitActivityEvent(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return
            
            val activityType = when (type) {
                "dialog_sent" -> ActivityType.DialogSent
                "dialog_response" -> ActivityType.DialogResponse
                "agent_started" -> ActivityType.AgentStarted
                "agent_completed" -> ActivityType.AgentCompleted
                "agent_failed" -> ActivityType.AgentFailed
                "file_edit" -> ActivityType.FileEdit
                "command" -> ActivityType.Command
                "tool_call" -> ActivityType.ToolCall
                else -> return
            }
            
            val activityEvent = com.continuumstudio.tablet.data.models.ActivityEvent(
                id = json.get("id")?.asString ?: java.util.UUID.randomUUID().toString(),
                type = activityType,
                title = json.get("title")?.asString ?: type,
                body = json.get("body")?.asString,
                agentId = json.get("agent_id")?.asString
            )
            
            scope.launch { _events.emit(WebSocketEvent.ActivityEvent(activityEvent)) }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }
}
