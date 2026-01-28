package com.example.continuumstudio.network

import com.example.continuumstudio.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for connecting to the Continuum Studio dialog daemon
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

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Dialog state
    private val _dialogState = MutableStateFlow(DialogUiState())
    val dialogState: StateFlow<DialogUiState> = _dialogState.asStateFlow()

    // Events channel for one-shot events
    private val _events = Channel<DialogEvent>(Channel.BUFFERED)
    val events: Flow<DialogEvent> = _events.receiveAsFlow()

    /**
     * Connect to the dialog daemon
     */
    fun connect(serverUrl: String) {
        if (_connectionState.value.isConnected || _connectionState.value.isConnecting) {
            return
        }

        _connectionState.update { it.copy(
            isConnecting = true,
            serverUrl = serverUrl,
            errorMessage = null
        ) }

        val wsUrl = if (serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")) {
            serverUrl
        } else {
            "ws://$serverUrl"
        }

        val request = Request.Builder()
            .url("$wsUrl/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    _connectionState.update { it.copy(
                        isConnected = true,
                        isConnecting = false,
                        errorMessage = null
                    ) }
                    _events.send(DialogEvent.Connected)
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
                    _events.send(DialogEvent.Disconnected(reason))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scope.launch {
                    _connectionState.update { it.copy(
                        isConnected = false,
                        isConnecting = false,
                        errorMessage = t.message ?: "Connection failed"
                    ) }
                    _events.send(DialogEvent.Error(t.message ?: "Unknown error"))
                }
            }
        })
    }

    /**
     * Disconnect from the daemon
     */
    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
    }

    /**
     * Fetch current dialog details via REST API
     */
    suspend fun fetchCurrentDialog(serverUrl: String): DialogDetails? {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl"
                val request = Request.Builder()
                    .url("$httpUrl/api/current")
                    .build()
                
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
                val httpUrl = if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl"
                
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

                val request = Request.Builder()
                    .url("$httpUrl/api/answer")
                    .post(RequestBody.create(
                        "application/json".toMediaType(),
                        requestBody.toString()
                    ))
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
                val httpUrl = if (serverUrl.startsWith("http")) serverUrl else "http://$serverUrl"
                val request = Request.Builder()
                    .url("$httpUrl/api/hold")
                    .post(RequestBody.create(null, ""))
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
                    _events.send(DialogEvent.NewDialog(newDialog.id, newDialog.title, newDialog.prompt))
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
}

// MediaType extension imported from okhttp3.MediaType.Companion.toMediaType

/**
 * Events from the WebSocket client
 */
sealed class DialogEvent {
    data object Connected : DialogEvent()
    data class Disconnected(val reason: String) : DialogEvent()
    data class Error(val message: String) : DialogEvent()
    data class NewDialog(val id: String, val title: String, val prompt: String = "") : DialogEvent()
    data class DialogCompleted(val id: String) : DialogEvent()
}

