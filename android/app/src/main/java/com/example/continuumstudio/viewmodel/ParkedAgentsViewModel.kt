package com.example.continuumstudio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ViewModel for Parked Agents management.
 * Handles fetching parked agents and assigning tasks.
 * Uses OkHttp for HTTP requests and kotlinx.serialization for JSON parsing.
 */
class ParkedAgentsViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val DEFAULT_BASE_URL = "http://100.109.236.61:8080"
        private const val POLL_INTERVAL_MS = 10000L
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json".toMediaType()
    
    private var baseUrl = DEFAULT_BASE_URL
    private var pollingJob: Job? = null
    
    private val _uiState = MutableStateFlow(ParkedAgentsUiState())
    val uiState: StateFlow<ParkedAgentsUiState> = _uiState.asStateFlow()
    
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()
    
    fun updateServerUrl(url: String) {
        baseUrl = url
    }
    
    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }
    
    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            try {
                val agents = fetchParkedAgents()
                _uiState.update { it.copy(agents = agents, isLoading = false) }
            } catch (e: Exception) {
                // Endpoint may not be available - that's okay
                _uiState.update { it.copy(agents = emptyList(), isLoading = false) }
            }
        }
    }
    
    private suspend fun fetchParkedAgents(): List<ParkedAgent> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/parking/agents")
            .get()
            .build()
        
        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val parsed = json.decodeFromString<ParkedAgentsResponse>(body)
                    parsed.agents.map { jsonAgent ->
                        ParkedAgent(
                            agentId = jsonAgent.agentId,
                            workspace = jsonAgent.workspace,
                            parkedAt = jsonAgent.parkedAt,
                            capabilities = jsonAgent.capabilities,
                            lastHeartbeat = jsonAgent.lastHeartbeat ?: jsonAgent.parkedAt
                        )
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    fun showAssignDialog(agentId: String) {
        _uiState.update { it.copy(assigningAgentId = agentId, taskDescription = "") }
    }
    
    fun hideAssignDialog() {
        _uiState.update { it.copy(assigningAgentId = null, taskDescription = "") }
    }
    
    fun updateTaskDescription(description: String) {
        _uiState.update { it.copy(taskDescription = description) }
    }
    
    fun assignTask() {
        val agentId = _uiState.value.assigningAgentId ?: return
        val task = _uiState.value.taskDescription
        
        if (task.isBlank()) {
            showToast("Please enter a task description")
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            try {
                val success = unparkAgent(agentId, task)
                if (success) {
                    showToast("Task assigned to agent")
                    hideAssignDialog()
                    refresh()
                } else {
                    _uiState.update { it.copy(error = "Failed to assign task", isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to assign task: ${e.message}", isLoading = false) }
            }
        }
    }
    
    private suspend fun unparkAgent(agentId: String, task: String): Boolean = withContext(Dispatchers.IO) {
        val body = """{"task":"$task"}""".toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url("$baseUrl/api/parking/agents/$agentId/unpark")
            .post(body)
            .build()
        
        try {
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun showToast(message: String) {
        _toastMessage.value = message
    }
    
    fun dismissToast() {
        _toastMessage.value = null
    }
    
    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
