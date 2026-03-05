package com.example.continuumstudio.network

import com.example.continuumstudio.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * WebSocket client for connecting to the Continuum Studio dialog daemon
 * Features:
 * - Exponential backoff with jitter for reconnection (mobile-friendly)
 * - Latency tracking via ping/pong
 * - History API support
 */
class DialogWebSocketClient(
    private val scope: CoroutineScope
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    // Reconnection state
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var shouldReconnect = false
    private var wasEverConnected = false // Track if we successfully connected before
    private val maxInitialAttempts = 3 // Max retries for initial connection
    
    // Cloudflare Access credentials (stored for reconnection)
    private var cfAccessClientId: String = ""
    private var cfAccessClientSecret: String = ""
    
    // Latency tracking
    private var lastPingTime: Long? = null
    private val _latency = MutableStateFlow<Long?>(null)
    val latency: StateFlow<Long?> = _latency.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Dialog state
    private val _dialogState = MutableStateFlow(DialogUiState())
    val dialogState: StateFlow<DialogUiState> = _dialogState.asStateFlow()
    
    // History state
    private val _history = MutableStateFlow<List<HistoryItem>>(emptyList())
    val history: StateFlow<List<HistoryItem>> = _history.asStateFlow()
    
    private val _historyLoading = MutableStateFlow(false)
    val historyLoading: StateFlow<Boolean> = _historyLoading.asStateFlow()

    // Events channel for one-shot events
    private val _events = Channel<DialogEvent>(Channel.BUFFERED)
    val events: Flow<DialogEvent> = _events.receiveAsFlow()
    
    /**
     * Calculate reconnection delay with exponential backoff and jitter
     * Mobile networks benefit from this approach to avoid thundering herd
     */
    private fun getReconnectDelay(): Long {
        val baseDelay = 1000L // 1 second
        val maxDelay = 30000L // 30 seconds cap
        val delay = min(baseDelay * 2.0.pow(reconnectAttempts.toDouble()).toLong(), maxDelay)
        val jitter = (delay * 0.3 * Random.nextDouble()).toLong() // ±30% jitter
        return delay + jitter
    }

    /**
     * Determine if a URL should use secure protocols (HTTPS/WSS)
     * - Explicit https:// or wss:// → secure
     * - Known public domains (no localhost/IP) → secure (assumes Cloudflare tunnel)
     * - Explicit http:// or ws:// → insecure
     * - localhost or private IPs → insecure
     */
    private fun shouldUseSecure(url: String): Boolean {
        val lowered = url.lowercase()
        
        // Explicit protocol specified
        if (lowered.startsWith("https://") || lowered.startsWith("wss://")) return true
        if (lowered.startsWith("http://") || lowered.startsWith("ws://")) return false
        
        // Strip any protocol prefix for host analysis
        val host = lowered
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
            .split("/").first()
            .split(":").first() // Remove port
        
        // Local hosts are insecure
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
        
        // Private IP ranges are insecure
        if (host.startsWith("192.168.") || host.startsWith("10.") || 
            host.startsWith("172.16.") || host.startsWith("172.17.") ||
            host.startsWith("172.18.") || host.startsWith("172.19.") ||
            host.startsWith("172.2") || host.startsWith("172.30.") || host.startsWith("172.31.")) {
            return false
        }
        
        // Tailscale IPs (100.x.x.x) are treated as local/insecure
        if (host.startsWith("100.")) return false
        
        // Public domain names default to secure (Cloudflare tunnel assumption)
        return true
    }
    
    /**
     * Build the WebSocket URL with appropriate protocol
     */
    private fun buildWebSocketUrl(serverUrl: String): String {
        val secure = shouldUseSecure(serverUrl)
        val baseUrl = serverUrl
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        return if (secure) "wss://$baseUrl" else "ws://$baseUrl"
    }
    
    /**
     * Build the HTTP URL with appropriate protocol
     */
    private fun buildHttpUrl(serverUrl: String): String {
        val secure = shouldUseSecure(serverUrl)
        val baseUrl = serverUrl
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        return if (secure) "https://$baseUrl" else "http://$baseUrl"
    }
    
    /**
     * Create a request builder with CF Access headers if credentials are available
     */
    private fun buildRequestWithAuth(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) {
            builder.header("CF-Access-Client-Id", cfAccessClientId)
            builder.header("CF-Access-Client-Secret", cfAccessClientSecret)
        }
        return builder
    }

    /**
     * Connect to the dialog daemon
     */
    fun connect(serverUrl: String, clientId: String = "", clientSecret: String = "") {
        val state = _connectionState.value
        if (state.isConnected || state.isConnecting) {
            return
        }
        
        // If we're in a reconnect loop, don't block - let scheduleReconnect manage attempts
        // But if user manually triggers connect (not in reconnect state), reset counter
        if (!state.isReconnecting) {
            reconnectAttempts = 0 // Reset attempt counter for fresh manual connection
            // Store credentials for reconnection (only update on manual connect)
            cfAccessClientId = clientId
            cfAccessClientSecret = clientSecret
        }
        
        reconnectJob?.cancel()

        _connectionState.update { it.copy(
            isConnecting = true,
            serverUrl = serverUrl,
            errorMessage = null
        ) }

        val wsUrl = buildWebSocketUrl(serverUrl)

        val requestBuilder = Request.Builder()
            .url("$wsUrl/ws")
        
        // Add Cloudflare Access headers if credentials are provided
        if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) {
            requestBuilder.header("CF-Access-Client-Id", cfAccessClientId)
            requestBuilder.header("CF-Access-Client-Secret", cfAccessClientSecret)
        }
        
        val request = requestBuilder.build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    reconnectAttempts = 0 // Reset on successful connection
                    wasEverConnected = true // Mark that we've successfully connected
                    shouldReconnect = true // Enable auto-reconnect after successful connection
                    _connectionState.update { it.copy(
                        isConnected = true,
                        isConnecting = false,
                        isReconnecting = false,
                        reconnectAttempts = 0,
                        errorMessage = null
                    ) }
                    _events.send(DialogEvent.Connected)
                    
                    // Start latency ping
                    startLatencyPing()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    handleMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    _connectionState.update { it.copy(
                        isConnected = false,
                        isConnecting = false
                    ) }
                    _latency.value = null
                    _events.send(DialogEvent.Disconnected(reason))
                    
                    // Attempt reconnection with exponential backoff
                    scheduleReconnect(serverUrl)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _connectionState.update { it.copy(
                        isConnected = false,
                        isConnecting = false,
                        errorMessage = t.message ?: "Connection failed"
                    ) }
                    _latency.value = null
                    _events.send(DialogEvent.Error(t.message ?: "Unknown error"))
                    
                    // Attempt reconnection with exponential backoff
                    scheduleReconnect(serverUrl)
                }
            }
        })
    }
    
    /**
     * Schedule a reconnection attempt with exponential backoff
     * Only auto-reconnects if:
     * - We were previously connected (wasEverConnected)
     * - OR we haven't exceeded max initial attempts
     */
    private fun scheduleReconnect(serverUrl: String) {
        // For initial connection failures, limit retries
        if (!wasEverConnected) {
            if (reconnectAttempts >= maxInitialAttempts) {
                // Give up on initial connection after max attempts
                _connectionState.update { it.copy(
                    isReconnecting = false,
                    errorMessage = "Could not connect after $maxInitialAttempts attempts. Check server URL and try again."
                ) }
                return
            }
        }
        
        // For reconnection (was connected before), require shouldReconnect flag
        if (wasEverConnected && !shouldReconnect) return
        
        val delay = getReconnectDelay()
        reconnectAttempts++
        
        // Update state to show reconnection in progress
        _connectionState.update { it.copy(
            isReconnecting = true,
            reconnectAttempts = reconnectAttempts
        ) }
        
        reconnectJob = scope.launch {
            // Emit reconnecting event so UI can show status
            _events.send(DialogEvent.Reconnecting(reconnectAttempts, delay))
            
            delay(delay)
            if (!_connectionState.value.isConnected) {
                // Only continue if we should (for reconnection) or haven't given up (for initial)
                if (wasEverConnected && shouldReconnect) {
                    _connectionState.update { it.copy(isConnecting = false) } // Reset for retry
                    connect(serverUrl)
                } else if (!wasEverConnected && reconnectAttempts < maxInitialAttempts) {
                    _connectionState.update { it.copy(isConnecting = false) } // Reset for retry
                    connect(serverUrl)
                } else {
                    // Gave up - make sure state reflects that we're not reconnecting
                    _connectionState.update { it.copy(
                        isReconnecting = false,
                        errorMessage = if (!wasEverConnected) 
                            "Could not connect after $reconnectAttempts attempts. Check server URL and try again."
                        else 
                            "Connection lost. Auto-reconnect disabled."
                    ) }
                }
            }
        }
    }
    
    /**
     * Start periodic latency ping (every 30 seconds)
     */
    private fun startLatencyPing() {
        scope.launch {
            while (_connectionState.value.isConnected) {
                delay(30_000) // 30 seconds
                sendPing()
            }
        }
    }
    
    /**
     * Send a ping to measure latency
     */
    fun sendPing() {
        if (webSocket != null && _connectionState.value.isConnected && lastPingTime == null) {
            lastPingTime = System.currentTimeMillis()
            webSocket?.send("""{"type":"ping"}""")
        }
    }

    /**
     * Disconnect from the daemon
     */
    fun disconnect() {
        shouldReconnect = false
        wasEverConnected = false // Reset so next connect uses initial retry logic
        reconnectAttempts = 0
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.update { it.copy(isConnected = false, isConnecting = false, isReconnecting = false) }
        _latency.value = null
    }

    /**
     * Fetch current dialog details via REST API
     */
    suspend fun fetchCurrentDialog(serverUrl: String): DialogDetails? {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val request = buildRequestWithAuth("$httpUrl/api/current").build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val apiResponse = json.decodeFromString<ApiResponse<DialogDetails>>(body)
                    if (apiResponse.success) {
                        apiResponse.data?.also { dialog ->
                            _dialogState.update { it.copy(activeDialog = dialog) }
                        }
                    } else null
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Submit dialog answer via REST API
     */
    suspend fun answerDialog(
        serverUrl: String,
        dialogId: String,
        selection: Any,
        comment: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                
                val selectionJson = when (selection) {
                    is String -> JsonPrimitive(selection)
                    is Boolean -> JsonPrimitive(selection)
                    is Number -> JsonPrimitive(selection)
                    is List<*> -> buildJsonArray {
                        (selection as List<String>).forEach { add(JsonPrimitive(it)) }
                    }
                    else -> JsonPrimitive(selection.toString())
                }

                val requestBody = buildJsonObject {
                    put("id", dialogId)
                    put("selection", selectionJson)
                    comment?.let { put("comment", it) }
                }

                val request = buildRequestWithAuth("$httpUrl/api/answer")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext false
                    val apiResponse = json.decodeFromString<ApiResponse<String>>(body)
                    if (apiResponse.success) {
                        _dialogState.update { it.copy(
                            activeDialog = null,
                            selectedValue = "",
                            comment = "",
                            selectedOptions = emptySet()
                        ) }
                        true
                    } else false
                } else false
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Toggle hold mode via REST API
     */
    suspend fun toggleHoldMode(serverUrl: String): Boolean? {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val request = buildRequestWithAuth("$httpUrl/api/hold")
                    .post("".toRequestBody(null))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val apiResponse = json.decodeFromString<ApiResponse<Boolean>>(body)
                    apiResponse.data?.also { newState ->
                        _dialogState.update { it.copy(holdMode = newState) }
                    }
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Handle incoming WebSocket message
     */
    private suspend fun handleMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "pong" -> {
                    // Calculate latency from ping response
                    lastPingTime?.let { pingTime ->
                        _latency.value = System.currentTimeMillis() - pingTime
                        lastPingTime = null
                    }
                }
                "initial" -> {
                    val initial = json.decodeFromString<ServerMessage.Initial>(text)
                    _dialogState.update { it.copy(
                        queueCount = initial.queueCount,
                        holdMode = initial.holdMode
                    ) }
                    // If there's an active dialog, fetch its details
                    if (initial.hasActive) {
                        fetchCurrentDialog(_connectionState.value.serverUrl)
                    }
                }
                "NewDialog" -> {
                    val newDialog = json.decodeFromString<ServerMessage.NewDialog>(text)
                    _events.send(DialogEvent.NewDialog(newDialog.id, newDialog.title, newDialog.prompt, newDialog.dialogType))
                    // Fetch full dialog details
                    fetchCurrentDialog(_connectionState.value.serverUrl)
                }
                "DialogCompleted" -> {
                    val completed = json.decodeFromString<ServerMessage.DialogCompleted>(text)
                    _dialogState.update { it.copy(activeDialog = null) }
                    _events.send(DialogEvent.DialogCompleted(completed.id))
                }
                "QueueUpdate" -> {
                    val update = json.decodeFromString<ServerMessage.QueueUpdate>(text)
                    _dialogState.update { it.copy(queueCount = update.queueCount) }
                    // If there's a new active dialog, fetch it
                    if (update.activeId != null && 
                        update.activeId != _dialogState.value.activeDialog?.id) {
                        fetchCurrentDialog(_connectionState.value.serverUrl)
                    }
                }
                "HoldModeChanged" -> {
                    val holdMode = json.decodeFromString<ServerMessage.HoldModeChanged>(text)
                    _dialogState.update { it.copy(holdMode = holdMode.enabled) }
                }
            }
        } catch (e: Exception) {
            // Log parsing errors but don't crash
            e.printStackTrace()
        }
    }
    
    /**
     * Fetch dialog history from REST API
     */
    suspend fun fetchHistory(serverUrl: String, limit: Int = 50): List<HistoryItem> {
        return withContext(Dispatchers.IO) {
            _historyLoading.value = true
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val request = buildRequestWithAuth("$httpUrl/api/history?limit=$limit").build()
                
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext emptyList()
                    val apiResponse = json.decodeFromString<ApiResponse<List<HistoryItem>>>(body)
                    if (apiResponse.success) {
                        apiResponse.data?.also { items ->
                            _history.value = items
                        } ?: emptyList()
                    } else emptyList()
                } else emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            } finally {
                _historyLoading.value = false
            }
        }
    }
    
    /**
     * Reinvoke a historical dialog (send it again)
     */
    suspend fun reinvokeDialog(serverUrl: String, historyItem: HistoryItem): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                
                val requestBody = buildJsonObject {
                    put("dialog_type", historyItem.dialogType)
                    put("title", historyItem.title)
                    put("prompt", historyItem.prompt)
                    historyItem.options?.let { options ->
                        put("options", buildJsonArray {
                            options.forEach { opt ->
                                add(buildJsonObject {
                                    put("value", opt.value)
                                    put("label", opt.label)
                                    opt.description?.let { put("description", it) }
                                })
                            }
                        })
                    }
                    historyItem.timeout?.let { put("timeout_secs", it) }
                }
                
                val request = buildRequestWithAuth("$httpUrl/api/dialog")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                response.isSuccessful
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    // Update UI state methods
    fun updateSelectedValue(value: String) {
        _dialogState.update { it.copy(selectedValue = value) }
    }

    fun updateComment(comment: String) {
        _dialogState.update { it.copy(comment = comment) }
    }

    fun toggleOption(value: String) {
        _dialogState.update { state ->
            val newOptions = if (state.selectedOptions.contains(value)) {
                state.selectedOptions - value
            } else {
                state.selectedOptions + value
            }
            state.copy(selectedOptions = newOptions)
        }
    }

    fun updateSliderValue(value: Float) {
        _dialogState.update { it.copy(sliderValue = value) }
    }

    /**
     * Render a diagram (Mermaid or D2) to SVG
     * @param serverUrl The dialog daemon URL
     * @param diagramType "mermaid" or "d2"
     * @param content The diagram source code
     * @param theme "dark" or "light"
     * @return DiagramRenderResult containing SVG or error
     */
    suspend fun renderDiagram(
        serverUrl: String,
        diagramType: String,
        content: String,
        theme: String = "dark"
    ): DiagramRenderResult {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val requestBody = buildJsonObject {
                    put("type", diagramType)
                    put("content", content)
                    put("theme", theme)
                }

                val request = buildRequestWithAuth("$httpUrl/api/render-diagram")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext DiagramRenderResult(
                        success = false,
                        error = "Empty response body"
                    )
                    val apiResponse = json.decodeFromString<ApiResponse<DiagramRenderResponse>>(body)
                    if (apiResponse.success && apiResponse.data != null) {
                        DiagramRenderResult(
                            success = apiResponse.data.success,
                            svg = apiResponse.data.svg,
                            error = apiResponse.data.error
                        )
                    } else {
                        DiagramRenderResult(
                            success = false,
                            error = apiResponse.error ?: "Unknown error"
                        )
                    }
                } else {
                    DiagramRenderResult(
                        success = false,
                        error = "HTTP ${response.code}: ${response.message}"
                    )
                }
            } catch (e: Exception) {
                DiagramRenderResult(
                    success = false,
                    error = "Exception: ${e.message}"
                )
            }
        }
    }

    /**
     * Execute a quick action on the Synapsix daemon
     * @param serverUrl The dialog daemon URL
     * @param action The action name (e.g., "start_cursor", "start_android", "start_godot")
     * @param args Optional arguments for the action
     * @return ActionResult containing success status and output
     */
    suspend fun executeAction(
        serverUrl: String,
        action: String,
        args: List<String> = emptyList()
    ): ActionResult {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val requestBody = buildJsonObject {
                    put("action", action)
                    put("args", buildJsonArray {
                        args.forEach { add(JsonPrimitive(it)) }
                    })
                }

                val request = buildRequestWithAuth("$httpUrl/api/action")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext ActionResult(
                        success = false,
                        error = "Empty response body"
                    )
                    val apiResponse = json.decodeFromString<ApiResponse<ActionResponse>>(body)
                    if (apiResponse.success && apiResponse.data != null) {
                        ActionResult(
                            success = apiResponse.data.success,
                            output = apiResponse.data.output,
                            error = apiResponse.data.error,
                            exitCode = apiResponse.data.exitCode
                        )
                    } else {
                        ActionResult(
                            success = false,
                            error = apiResponse.error ?: "Unknown error"
                        )
                    }
                } else {
                    ActionResult(
                        success = false,
                        error = "HTTP ${response.code}: ${response.message}"
                    )
                }
            } catch (e: Exception) {
                ActionResult(
                    success = false,
                    error = "Exception: ${e.message}"
                )
            }
        }
    }
}

/**
 * Response data from the render-diagram API
 */
@kotlinx.serialization.Serializable
data class DiagramRenderResponse(
    val svg: String = "",
    val success: Boolean = false,
    val error: String? = null
)

/**
 * Result wrapper for diagram rendering
 */
data class DiagramRenderResult(
    val success: Boolean,
    val svg: String? = null,
    val error: String? = null
)

/**
 * Response data from the action API
 */
@kotlinx.serialization.Serializable
data class ActionResponse(
    val success: Boolean = false,
    val output: String? = null,
    val error: String? = null,
    @kotlinx.serialization.SerialName("exit_code")
    val exitCode: Int? = null
)

/**
 * Result wrapper for action execution
 */
data class ActionResult(
    val success: Boolean,
    val output: String? = null,
    val error: String? = null,
    val exitCode: Int? = null
)

// MediaType extension imported from okhttp3.MediaType.Companion.toMediaType

/**
 * Events from the WebSocket client
 */
sealed class DialogEvent {
    data object Connected : DialogEvent()
    data class Disconnected(val reason: String) : DialogEvent()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : DialogEvent()
    data class Error(val message: String) : DialogEvent()
    data class NewDialog(val id: String, val title: String, val prompt: String = "", val dialogType: String = "choice") : DialogEvent()
    data class DialogCompleted(val id: String) : DialogEvent()
}

