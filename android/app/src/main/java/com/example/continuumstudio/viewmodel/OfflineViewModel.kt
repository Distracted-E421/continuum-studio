package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ViewModel for managing offline mode and operation queue.
 * Persists queued operations to disk and syncs when online.
 */
class OfflineViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val QUEUE_FILE = "offline_queue.json"
        private const val MAX_QUEUE_SIZE = 1000
        private const val PING_INTERVAL_MS = 30000L
        private const val MAX_RETRIES = 3
    }
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private var dialogBaseUrl = "http://100.102.101.72:8082"
    private var cliAgentsBaseUrl = "http://100.102.101.72:4001"
    
    private val _uiState = MutableStateFlow(OfflineUiState())
    val uiState: StateFlow<OfflineUiState> = _uiState.asStateFlow()
    
    private val operationQueue = mutableListOf<QueuedOperation>()
    private var pingJob: Job? = null
    
    init {
        loadQueue()
        startPingLoop()
    }
    
    fun updateUrls(dialogUrl: String, cliAgentsUrl: String) {
        dialogBaseUrl = dialogUrl
        cliAgentsBaseUrl = cliAgentsUrl
    }
    
    // ==========================================================================
    // Queue Operations
    // ==========================================================================
    
    fun queue(operation: OperationType): String {
        val id = "op-${System.currentTimeMillis()}-${operationQueue.size}"
        val queued = QueuedOperation(
            id = id,
            timestamp = System.currentTimeMillis(),
            operation = operation
        )
        
        // Enforce max size
        if (operationQueue.size >= MAX_QUEUE_SIZE) {
            operationQueue.removeAt(0)
        }
        
        operationQueue.add(queued)
        updateState()
        saveQueue()
        
        return id
    }
    
    fun pendingCount(): Int = operationQueue.size
    
    fun isEmpty(): Boolean = operationQueue.isEmpty()
    
    fun clearQueue() {
        operationQueue.clear()
        updateState()
        saveQueue()
    }
    
    // ==========================================================================
    // Sync Operations
    // ==========================================================================
    
    fun sync() {
        if (_uiState.value.syncing || operationQueue.isEmpty()) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(syncing = true, error = null) }
            
            val toProcess = operationQueue.toList()
            var processed = 0
            
            for (op in toProcess) {
                try {
                    val success = processOperation(op)
                    if (success) {
                        operationQueue.removeAll { it.id == op.id }
                        processed++
                    } else {
                        markFailed(op.id, "Unknown error")
                    }
                } catch (e: Exception) {
                    markFailed(op.id, e.message ?: "Unknown error")
                }
            }
            
            // Prune failed operations
            pruneFailedOperations()
            
            _uiState.update { 
                it.copy(
                    syncing = false, 
                    lastSync = if (processed > 0) System.currentTimeMillis() else it.lastSync
                ) 
            }
            updateState()
            saveQueue()
        }
    }
    
    private suspend fun processOperation(op: QueuedOperation): Boolean = withContext(Dispatchers.IO) {
        when (val operation = op.operation) {
            is OperationType.DialogResponse -> {
                val body = """{"selection":"${operation.selection}","comment":${operation.comment?.let { "\"$it\"" } ?: "null"}}"""
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$dialogBaseUrl/api/agent-dialogs/${operation.dialogId}/respond")
                    .post(body)
                    .build()
                
                try {
                    val response = client.newCall(request).execute()
                    response.isSuccessful
                } catch (e: Exception) {
                    false
                }
            }
            
            is OperationType.AgentSpawn -> {
                val bodyJson = json.encodeToString(
                    SpawnAgentRequest(
                        prompt = operation.prompt,
                        workspace = operation.workspace
                    )
                )
                val request = Request.Builder()
                    .url("$cliAgentsBaseUrl/api/cli-agents/spawn")
                    .post(bodyJson.toRequestBody("application/json".toMediaType()))
                    .build()
                
                try {
                    val response = client.newCall(request).execute()
                    response.isSuccessful
                } catch (e: Exception) {
                    false
                }
            }
            
            is OperationType.AgentStop -> {
                val request = Request.Builder()
                    .url("$cliAgentsBaseUrl/api/cli-agents/${operation.agentId}/stop")
                    .post("".toRequestBody(null))
                    .build()
                
                try {
                    val response = client.newCall(request).execute()
                    response.isSuccessful
                } catch (e: Exception) {
                    false
                }
            }
            
            // For other operation types, return true to remove them (not implemented yet)
            is OperationType.TaskAdd,
            is OperationType.TaskUpdate,
            is OperationType.SettingsSync,
            is OperationType.Custom -> true
        }
    }
    
    private fun markFailed(id: String, error: String) {
        val index = operationQueue.indexOfFirst { it.id == id }
        if (index >= 0) {
            val op = operationQueue[index]
            operationQueue[index] = op.copy(
                retryCount = op.retryCount + 1,
                lastError = error
            )
        }
    }
    
    private fun pruneFailedOperations() {
        operationQueue.removeAll { it.retryCount >= MAX_RETRIES }
    }
    
    // ==========================================================================
    // Connection Tracking
    // ==========================================================================
    
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = viewModelScope.launch {
            while (true) {
                checkConnections()
                delay(PING_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun checkConnections() = withContext(Dispatchers.IO) {
        val dialogConnected = pingEndpoint("$dialogBaseUrl/api/status")
        val cliAgentsConnected = pingEndpoint("$cliAgentsBaseUrl/api/cli-agents")
        
        val state = when {
            dialogConnected && cliAgentsConnected -> OfflineState.ONLINE
            !dialogConnected && !cliAgentsConnected -> OfflineState.FULLY_OFFLINE
            else -> OfflineState.PARTIALLY_OFFLINE
        }
        
        _uiState.update {
            it.copy(
                state = state,
                dialogConnected = dialogConnected,
                cliAgentsConnected = cliAgentsConnected,
                coreConnected = dialogConnected && cliAgentsConnected
            )
        }
        
        // Auto-sync when coming back online
        if (state == OfflineState.ONLINE && operationQueue.isNotEmpty()) {
            sync()
        }
    }
    
    private fun pingEndpoint(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful || response.code == 404 // 404 means server is up
        } catch (e: Exception) {
            false
        }
    }
    
    // ==========================================================================
    // Persistence
    // ==========================================================================
    
    private fun getQueueFile(): File {
        val context = getApplication<Application>()
        return File(context.filesDir, QUEUE_FILE)
    }
    
    private fun loadQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getQueueFile()
                if (file.exists()) {
                    val content = file.readText()
                    val loaded = json.decodeFromString<List<QueuedOperation>>(content)
                    operationQueue.clear()
                    operationQueue.addAll(loaded)
                    updateState()
                }
            } catch (e: Exception) {
                // Ignore - start with empty queue
            }
        }
    }
    
    private fun saveQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = getQueueFile()
                val content = json.encodeToString(operationQueue.toList())
                file.writeText(content)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
    
    private fun updateState() {
        _uiState.update {
            it.copy(pendingCount = operationQueue.size)
        }
    }
    
    // ==========================================================================
    // Convenience Methods
    // ==========================================================================
    
    /**
     * Queue a dialog response (for when offline).
     */
    fun queueDialogResponse(dialogId: String, selection: String, comment: String? = null): String {
        return queue(OperationType.DialogResponse(dialogId, selection, comment))
    }
    
    /**
     * Queue an agent spawn (for when offline).
     */
    fun queueAgentSpawn(prompt: String, workspace: String, presetId: String? = null): String {
        return queue(OperationType.AgentSpawn(prompt, workspace, presetId))
    }
    
    /**
     * Queue an agent stop (for when offline).
     */
    fun queueAgentStop(agentId: String): String {
        return queue(OperationType.AgentStop(agentId))
    }
    
    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
        saveQueue()
    }
}
