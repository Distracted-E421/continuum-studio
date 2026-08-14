package com.example.continuumstudio.network

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * WebSocket client for the multi-surface dialog coordinator (v2 API)
 * 
 * Features:
 * - Connects to /ws/v2 endpoint
 * - Real-time dialog events across all surfaces
 * - Soft claiming support
 * - Race resolution with 200ms grace period
 */
class CoordinatorWebSocketClient(
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

    // Our surface identity
    val surfaceId: String = "android-${UUID.randomUUID()}"
    private val surfaceType: SurfaceType = SurfaceType.MOBILE

    // Reconnection state
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var shouldReconnect = false
    private var wasEverConnected = false
    private val maxInitialAttempts = 8

    // Auth credentials
    private var cfAccessClientId: String = ""
    private var cfAccessClientSecret: String = ""

    // Connection state
    private val _connectionState = MutableStateFlow(CoordinatorConnectionState())
    val connectionState: StateFlow<CoordinatorConnectionState> = _connectionState.asStateFlow()

    // Dialog queue (all pending dialogs)
    private val _dialogs = MutableStateFlow<List<CoordinatorDialog>>(emptyList())
    val dialogs: StateFlow<List<CoordinatorDialog>> = _dialogs.asStateFlow()

    // Connected surfaces
    private val _surfaces = MutableStateFlow<List<ConnectedSurface>>(emptyList())
    val surfaces: StateFlow<List<ConnectedSurface>> = _surfaces.asStateFlow()

    // Events channel
    private val _events = Channel<CoordinatorEvent>(Channel.BUFFERED)
    val events: Flow<CoordinatorEvent> = _events.receiveAsFlow()

    /**
     * Calculate reconnection delay with exponential backoff and jitter
     */
    private fun getReconnectDelay(): Long {
        val baseDelay = 1000L
        val maxDelay = 30000L
        val delay = min(baseDelay * 2.0.pow(reconnectAttempts.toDouble()).toLong(), maxDelay)
        val jitter = (delay * 0.3 * Random.nextDouble()).toLong()
        return delay + jitter
    }

    /**
     * Build WebSocket URL with surface identification
     */
    private fun buildWebSocketUrl(serverUrl: String): String {
        val baseUrl = serverUrl
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        val secure = shouldUseSecure(serverUrl)
        val protocol = if (secure) "wss" else "ws"
        return "$protocol://$baseUrl/ws/v2?surface_id=$surfaceId&surface_type=${surfaceType.apiName}"
    }

    private fun buildHttpUrl(serverUrl: String): String {
        val baseUrl = serverUrl
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
        val secure = shouldUseSecure(serverUrl)
        return if (secure) "https://$baseUrl" else "http://$baseUrl"
    }

    private fun shouldUseSecure(url: String): Boolean {
        val lowered = url.lowercase()
        if (lowered.startsWith("https://") || lowered.startsWith("wss://")) return true
        if (lowered.startsWith("http://") || lowered.startsWith("ws://")) return false
        
        val host = lowered
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("wss://").removePrefix("ws://")
            .split("/").first()
            .split(":").first()
        
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) return false
        if (host.startsWith("100.")) return false // Tailscale
        
        return true
    }

    /**
     * Connect to the coordinator
     */
    fun connect(serverUrl: String, clientId: String = "", clientSecret: String = "") {
        val state = _connectionState.value
        if (state.isConnected || state.isConnecting) return

        if (!state.isReconnecting) {
            reconnectAttempts = 0
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

        val requestBuilder = Request.Builder().url(wsUrl)
        if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) {
            requestBuilder.header("CF-Access-Client-Id", cfAccessClientId)
            requestBuilder.header("CF-Access-Client-Secret", cfAccessClientSecret)
        }

        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    reconnectAttempts = 0
                    wasEverConnected = true
                    shouldReconnect = true
                    _connectionState.update { it.copy(
                        isConnected = true,
                        isConnecting = false,
                        isReconnecting = false,
                        reconnectAttempts = 0,
                        errorMessage = null
                    ) }
                    _events.send(CoordinatorEvent.Connected)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch {
                    _connectionState.update { it.copy(isConnected = false, isConnecting = false) }
                    _events.send(CoordinatorEvent.Disconnected(reason))
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
                    _events.send(CoordinatorEvent.Error(t.message ?: "Unknown error"))
                    scheduleReconnect(serverUrl)
                }
            }
        })
    }

    private fun scheduleReconnect(serverUrl: String) {
        if (!wasEverConnected && reconnectAttempts >= maxInitialAttempts) {
            _connectionState.update { it.copy(
                isReconnecting = false,
                errorMessage = "Could not connect after $maxInitialAttempts attempts"
            ) }
            return
        }
        if (wasEverConnected && !shouldReconnect) return

        val delay = getReconnectDelay()
        reconnectAttempts++

        _connectionState.update { it.copy(isReconnecting = true, reconnectAttempts = reconnectAttempts) }

        reconnectJob = scope.launch {
            _events.send(CoordinatorEvent.Reconnecting(reconnectAttempts, delay))
            delay(delay)
            if (!_connectionState.value.isConnected) {
                _connectionState.update { it.copy(isConnecting = false) }
                connect(serverUrl)
            }
        }
    }

    fun disconnect() {
        shouldReconnect = false
        wasEverConnected = false
        reconnectAttempts = 0
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.update { it.copy(isConnected = false, isConnecting = false, isReconnecting = false) }
    }

    /**
     * Handle incoming WebSocket message
     */
    private suspend fun handleMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.contentOrNull

            when (type) {
                "dialog_queue" -> {
                    val dialogList = jsonElement.jsonObject["dialogs"]?.jsonArray?.map { elem ->
                        json.decodeFromJsonElement<CoordinatorDialog>(elem)
                    } ?: emptyList()
                    _dialogs.value = dialogList
                    _events.send(CoordinatorEvent.DialogQueueReceived(dialogList))
                }
                "DialogCreated" -> {
                    val dialog = parseDialogCreated(jsonElement.jsonObject)
                    _dialogs.update { it + dialog }
                    _events.send(CoordinatorEvent.DialogCreated(dialog))
                }
                "DialogAnswered" -> {
                    val dialogId = jsonElement.jsonObject["dialog_id"]?.jsonPrimitive?.content ?: return
                    val surfaceId = jsonElement.jsonObject["surface_id"]?.jsonPrimitive?.content ?: ""
                    _dialogs.update { list -> list.filter { it.id != dialogId } }
                    _events.send(CoordinatorEvent.DialogAnswered(dialogId, surfaceId))
                }
                "DialogClaimed" -> {
                    val dialogId = jsonElement.jsonObject["dialog_id"]?.jsonPrimitive?.content ?: return
                    val claimingSurface = jsonElement.jsonObject["surface_id"]?.jsonPrimitive?.content ?: ""
                    _dialogs.update { list ->
                        list.map { d ->
                            if (d.id == dialogId) d.copy(
                                claim = SoftClaimInfo(claimingSurface, 30)
                            ) else d
                        }
                    }
                    _events.send(CoordinatorEvent.DialogClaimed(dialogId, claimingSurface))
                }
                "DialogClaimReleased" -> {
                    val dialogId = jsonElement.jsonObject["dialog_id"]?.jsonPrimitive?.content ?: return
                    _dialogs.update { list ->
                        list.map { d -> if (d.id == dialogId) d.copy(claim = null) else d }
                    }
                    _events.send(CoordinatorEvent.DialogClaimReleased(dialogId))
                }
                "DialogExpired" -> {
                    val dialogId = jsonElement.jsonObject["dialog_id"]?.jsonPrimitive?.content ?: return
                    _dialogs.update { list -> list.filter { it.id != dialogId } }
                    _events.send(CoordinatorEvent.DialogExpired(dialogId))
                }
                "DialogCancelled" -> {
                    val dialogId = jsonElement.jsonObject["dialog_id"]?.jsonPrimitive?.content ?: return
                    _dialogs.update { list -> list.filter { it.id != dialogId } }
                    _events.send(CoordinatorEvent.DialogCancelled(dialogId))
                }
                "SurfaceConnected" -> {
                    val sId = jsonElement.jsonObject["surface_id"]?.jsonPrimitive?.content ?: return
                    val sType = jsonElement.jsonObject["surface_type"]?.jsonPrimitive?.content ?: "other"
                    _surfaces.update { it + ConnectedSurface(sId, SurfaceType.fromApiName(sType)) }
                    _events.send(CoordinatorEvent.SurfaceConnected(sId, SurfaceType.fromApiName(sType)))
                }
                "SurfaceDisconnected" -> {
                    val sId = jsonElement.jsonObject["surface_id"]?.jsonPrimitive?.content ?: return
                    _surfaces.update { list -> list.filter { it.id != sId } }
                    _events.send(CoordinatorEvent.SurfaceDisconnected(sId))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseDialogCreated(obj: JsonObject): CoordinatorDialog {
        return CoordinatorDialog(
            id = obj["dialog_id"]?.jsonPrimitive?.content ?: "",
            title = obj["title"]?.jsonPrimitive?.content ?: "",
            prompt = obj["prompt"]?.jsonPrimitive?.content ?: "",
            dialogType = obj["dialog_type"] ?: JsonNull,
            state = "pending",
            agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNull,
            priority = obj["priority"]?.jsonPrimitive?.intOrNull ?: 1,
            timeoutMs = obj["timeout_ms"]?.jsonPrimitive?.intOrNull,
            createdAt = System.currentTimeMillis().toString(),
            claim = null
        )
    }

    // ========================================================================
    // Actions
    // ========================================================================

    /**
     * Answer a dialog via WebSocket command
     */
    fun answerDialog(dialogId: String, selection: JsonElement, comment: String? = null) {
        val cmd = buildJsonObject {
            put("type", "answer")
            put("dialog_id", dialogId)
            put("selection", selection)
            comment?.let { put("comment", it) }
        }
        webSocket?.send(cmd.toString())
    }

    /**
     * Claim a dialog (soft claim)
     */
    fun claimDialog(dialogId: String) {
        val cmd = buildJsonObject {
            put("type", "claim")
            put("dialog_id", dialogId)
        }
        webSocket?.send(cmd.toString())
    }

    /**
     * Release a claim
     */
    fun releaseClaimDialog(dialogId: String) {
        val cmd = buildJsonObject {
            put("type", "release")
            put("dialog_id", dialogId)
        }
        webSocket?.send(cmd.toString())
    }

    /**
     * Answer via REST API (more reliable than WebSocket for critical actions)
     */
    suspend fun answerDialogRest(
        serverUrl: String,
        dialogId: String,
        selection: JsonElement,
        comment: String? = null
    ): AnswerResult {
        return withContext(Dispatchers.IO) {
            try {
                val httpUrl = buildHttpUrl(serverUrl)
                val requestBody = buildJsonObject {
                    put("selection", selection)
                    comment?.let { put("comment", it) }
                    put("surface_id", surfaceId)
                }

                val requestBuilder = Request.Builder()
                    .url("$httpUrl/api/v2/dialog/$dialogId/answer")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                
                if (cfAccessClientId.isNotBlank() && cfAccessClientSecret.isNotBlank()) {
                    requestBuilder.header("CF-Access-Client-Id", cfAccessClientId)
                    requestBuilder.header("CF-Access-Client-Secret", cfAccessClientSecret)
                }

                val response = client.newCall(requestBuilder.build()).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext AnswerResult(false, "Empty response")
                    json.decodeFromString<AnswerResult>(body)
                } else {
                    AnswerResult(false, "HTTP ${response.code}")
                }
            } catch (e: Exception) {
                AnswerResult(false, e.message)
            }
        }
    }
}

// ============================================================================
// Data Models
// ============================================================================

@Serializable
enum class SurfaceType(val apiName: String) {
    DESKTOP_POPUP("desktop_popup"),
    TUI("tui"),
    MOBILE("mobile"),
    BROWSER("browser"),
    OTHER("other");

    companion object {
        fun fromApiName(name: String): SurfaceType {
            return entries.find { it.apiName == name } ?: OTHER
        }
    }
}

@Serializable
data class CoordinatorDialog(
    val id: String,
    val title: String,
    val prompt: String,
    val dialogType: JsonElement,
    val state: String,
    val agentId: String? = null,
    val priority: Int = 1,
    val timeoutMs: Int? = null,
    val createdAt: String,
    val claim: SoftClaimInfo? = null
)

@Serializable
data class SoftClaimInfo(
    val surfaceId: String,
    val expiresInSecs: Long
)

@Serializable
data class ConnectedSurface(
    val id: String,
    val type: SurfaceType
)

@Serializable
data class AnswerResult(
    val accepted: Boolean,
    val reason: String? = null,
    val answeredBy: String? = null
)

data class CoordinatorConnectionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isReconnecting: Boolean = false,
    val reconnectAttempts: Int = 0,
    val serverUrl: String = "",
    val errorMessage: String? = null
)

sealed class CoordinatorEvent {
    data object Connected : CoordinatorEvent()
    data class Disconnected(val reason: String) : CoordinatorEvent()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : CoordinatorEvent()
    data class Error(val message: String) : CoordinatorEvent()
    data class DialogQueueReceived(val dialogs: List<CoordinatorDialog>) : CoordinatorEvent()
    data class DialogCreated(val dialog: CoordinatorDialog) : CoordinatorEvent()
    data class DialogAnswered(val dialogId: String, val surfaceId: String) : CoordinatorEvent()
    data class DialogClaimed(val dialogId: String, val surfaceId: String) : CoordinatorEvent()
    data class DialogClaimReleased(val dialogId: String) : CoordinatorEvent()
    data class DialogExpired(val dialogId: String) : CoordinatorEvent()
    data class DialogCancelled(val dialogId: String) : CoordinatorEvent()
    data class SurfaceConnected(val surfaceId: String, val surfaceType: SurfaceType) : CoordinatorEvent()
    data class SurfaceDisconnected(val surfaceId: String) : CoordinatorEvent()
}
