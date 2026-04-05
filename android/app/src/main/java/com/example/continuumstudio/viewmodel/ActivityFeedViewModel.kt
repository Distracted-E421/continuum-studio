package com.example.continuumstudio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * ViewModel for Activity Feed.
 * Manages WebSocket connection to Synapsix dialog daemon for real-time events.
 */
class ActivityFeedViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val DEFAULT_WS_URL = "ws://100.102.101.72:8082/ws/activity"
        private const val MAX_EVENTS = 100
        private const val RECONNECT_DELAY_MS = 5000L
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    
    private val _uiState = MutableStateFlow(ActivityFeedUiState())
    val uiState: StateFlow<ActivityFeedUiState> = _uiState.asStateFlow()
    
    private var wsUrl = DEFAULT_WS_URL
    
    fun updateServerUrl(url: String) {
        wsUrl = url.replace("http://", "ws://").replace("https://", "wss://")
        if (!wsUrl.endsWith("/ws/activity")) {
            wsUrl = "$wsUrl/ws/activity"
        }
    }
    
    fun connect() {
        disconnect()
        
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _uiState.update { it.copy(isConnected = true) }
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val payload = json.decodeFromString<ActivityPayload>(text)
                    val event = parseActivityEvent(payload)
                    event?.let { addEvent(it) }
                } catch (e: Exception) {
                    // Ignore parse errors
                }
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _uiState.update { it.copy(isConnected = false) }
                scheduleReconnect()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _uiState.update { it.copy(isConnected = false) }
                scheduleReconnect()
            }
        })
    }
    
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _uiState.update { it.copy(isConnected = false) }
    }
    
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
            connect()
        }
    }
    
    private fun parseActivityEvent(payload: ActivityPayload): ActivityEvent? {
        val timestamp = payload.timestamp ?: java.time.Instant.now().toString()
        
        return when (payload.event) {
            "command" -> ActivityEvent.Command(
                agentId = payload.agentId ?: "terminal",
                command = payload.command ?: "",
                exitCode = payload.exitCode,
                durationMs = payload.durationMs,
                timestamp = timestamp
            )
            "dialog_sent" -> ActivityEvent.DialogSent(
                agentId = payload.agentId ?: "orchestrator",
                dialogId = payload.dialogId ?: "",
                title = payload.title ?: "",
                timestamp = timestamp
            )
            "dialog_response" -> ActivityEvent.DialogResponse(
                dialogId = payload.dialogId ?: "",
                selection = payload.selection ?: "",
                timestamp = timestamp
            )
            else -> null
        }
    }
    
    private fun addEvent(event: ActivityEvent) {
        _uiState.update { state ->
            val events = listOf(event) + state.events.take(MAX_EVENTS - 1)
            state.copy(events = events)
        }
    }
    
    fun toggleFilter(filter: ActivityFilter) {
        _uiState.update { state ->
            val filters = if (filter in state.filters) {
                state.filters - filter
            } else {
                state.filters + filter
            }
            state.copy(filters = filters)
        }
    }
    
    fun expandEvent(eventId: String?) {
        _uiState.update { it.copy(expandedEventId = eventId) }
    }
    
    fun clearEvents() {
        _uiState.update { it.copy(events = emptyList()) }
    }
    
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
