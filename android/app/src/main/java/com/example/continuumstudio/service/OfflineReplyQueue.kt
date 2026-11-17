package com.example.continuumstudio.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages offline reply queuing for Android Auto dialog responses.
 * 
 * When the device is offline or server is unreachable, replies are queued
 * locally and automatically synced when connectivity is restored.
 * 
 * Features:
 * - Automatic connectivity monitoring
 * - Persistent queue storage (survives app restart)
 * - Background sync with exponential backoff
 * - Queue size limits and cleanup
 */
class OfflineReplyQueue(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "offline_queue")
    private val queueKey = stringPreferencesKey("pending_replies")
    private val serverUrlKey = stringPreferencesKey("dialog_server_url")
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    private val _isOnline = MutableStateFlow(isNetworkAvailable())
    val isOnline: StateFlow<Boolean> = _isOnline
    
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount
    
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing
    
    private val syncInProgress = AtomicBoolean(false)
    
    private var retryCount = 0
    private val maxRetries = 5
    private val maxQueueSize = 100

    @Serializable
    data class PendingReply(
        val dialogId: String,
        val selection: String,  // JSON-encoded selection value
        val comment: String,
        val timestamp: Long,
        val priority: Int = 0  // Higher = sync first
    )

    init {
        startNetworkMonitoring()
        loadQueueState()
    }

    /**
     * Queue a reply for later sync. Returns true if queued, false if queue is full.
     */
    fun queueReply(
        dialogId: String,
        selection: Any,
        comment: String = "Replied via Android Auto (offline)",
        priority: Int = 0
    ): Boolean {
        return try {
            val selectionJson = encodeSelection(selection)
            
            val reply = PendingReply(
                dialogId = dialogId,
                selection = selectionJson,
                comment = comment,
                timestamp = System.currentTimeMillis(),
                priority = priority
            )
            
            scope.launch {
                val queue = loadQueue().toMutableList()
                
                if (queue.size >= maxQueueSize) {
                    Log.w(TAG, "Queue full, removing oldest low-priority item")
                    val lowestPriority = queue.minByOrNull { it.priority }
                    lowestPriority?.let { queue.remove(it) }
                }
                
                queue.add(reply)
                saveQueue(queue)
                _pendingCount.value = queue.size
                
                Log.d(TAG, "Queued reply for dialog $dialogId (${queue.size} pending)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue reply", e)
            false
        }
    }

    /**
     * Attempt to sync all pending replies. Called automatically on connectivity change.
     */
    fun syncPending() {
        if (!syncInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Sync already in progress")
            return
        }
        
        scope.launch {
            try {
                _isSyncing.value = true
                
                val serverUrl = getServerUrl()
                if (serverUrl == null) {
                    Log.w(TAG, "No server URL configured, skipping sync")
                    return@launch
                }
                
                if (!isNetworkAvailable()) {
                    Log.d(TAG, "Network unavailable, skipping sync")
                    return@launch
                }
                
                val queue = loadQueue().toMutableList()
                if (queue.isEmpty()) {
                    Log.d(TAG, "No pending replies to sync")
                    retryCount = 0
                    return@launch
                }
                
                Log.i(TAG, "Syncing ${queue.size} pending replies")
                
                // Sort by priority (descending) then timestamp (ascending)
                queue.sortWith(compareByDescending<PendingReply> { it.priority }.thenBy { it.timestamp })
                
                val failed = mutableListOf<PendingReply>()
                var successCount = 0
                
                for (reply in queue) {
                    val success = sendReply(serverUrl, reply)
                    if (success) {
                        successCount++
                        Log.d(TAG, "Synced reply for dialog ${reply.dialogId}")
                    } else {
                        failed.add(reply)
                        Log.w(TAG, "Failed to sync reply for dialog ${reply.dialogId}")
                    }
                }
                
                // Save remaining failed items
                saveQueue(failed)
                _pendingCount.value = failed.size
                
                Log.i(TAG, "Sync complete: $successCount succeeded, ${failed.size} failed")
                
                // Reset retry count on any success
                if (successCount > 0) {
                    retryCount = 0
                } else if (failed.isNotEmpty()) {
                    retryCount++
                    if (retryCount < maxRetries) {
                        scheduleRetry()
                    } else {
                        Log.w(TAG, "Max retries reached, will retry on next connectivity change")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during sync", e)
            } finally {
                _isSyncing.value = false
                syncInProgress.set(false)
            }
        }
    }

    /**
     * Clear all pending replies (use carefully).
     */
    fun clearQueue() {
        scope.launch {
            saveQueue(emptyList())
            _pendingCount.value = 0
            Log.i(TAG, "Queue cleared")
        }
    }

    /**
     * Release resources.
     */
    fun shutdown() {
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        scope.cancel()
    }

    private suspend fun sendReply(serverUrl: String, reply: PendingReply): Boolean {
        return try {
            val httpUrl = serverUrl
                .replace("ws://", "http://")
                .replace("wss://", "https://")
                .removeSuffix("/ws/dialogs")
                .removeSuffix("/")
            
            val requestBody = """
                {
                    "id": "${reply.dialogId}",
                    "selection": ${reply.selection},
                    "comment": "${reply.comment}"
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url("$httpUrl/api/answer")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = httpClient.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error sending reply for ${reply.dialogId}", e)
            false
        }
    }

    private fun scheduleRetry() {
        val delayMs = (1000L * (1 shl retryCount)).coerceAtMost(60000L)
        Log.d(TAG, "Scheduling retry in ${delayMs}ms (attempt $retryCount)")
        
        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (isNetworkAvailable()) {
                syncPending()
            }
        }
    }

    private fun startNetworkMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Network available")
                _isOnline.value = true
                
                // Sync pending replies when connectivity is restored
                if (_pendingCount.value > 0) {
                    syncPending()
                }
            }
            
            override fun onLost(network: Network) {
                Log.d(TAG, "Network lost")
                _isOnline.value = isNetworkAvailable()  // Check if any network still available
            }
            
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                _isOnline.value = hasInternet
            }
        }
        
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun loadQueue(): List<PendingReply> {
        return try {
            val prefs = context.dataStore.data.first()
            val queueJson = prefs[queueKey] ?: return emptyList()
            json.decodeFromString<List<PendingReply>>(queueJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading queue", e)
            emptyList()
        }
    }

    private suspend fun saveQueue(queue: List<PendingReply>) {
        try {
            context.dataStore.edit { prefs ->
                prefs[queueKey] = json.encodeToString(queue)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving queue", e)
        }
    }

    private suspend fun getServerUrl(): String? {
        return try {
            val prefs = context.dataStore.data.first()
            prefs[serverUrlKey]
        } catch (e: Exception) {
            Log.e(TAG, "Error reading server URL", e)
            null
        }
    }

    private fun loadQueueState() {
        scope.launch {
            val queue = loadQueue()
            _pendingCount.value = queue.size
            Log.d(TAG, "Loaded ${queue.size} pending replies from storage")
        }
    }

    companion object {
        private const val TAG = "OfflineReplyQueue"
    }
}

/**
 * Encode a dialog selection as JSON for queueing.
 *
 * Extracted so it can be tested: the queued selection is replayed to the daemon
 * hours later, against a `serde_json::Value` field, so getting the type wrong
 * means answering with the wrong shape and no way to notice until much later.
 *
 * The bug this fixes: a multi-select answer is a `List`, and the previous
 * `else` branch wrapped it in quotes - so `["a","b"]` was stored as the *string*
 * `"[\"a\",\"b\"]"` and replayed as a single string rather than an array.
 */
internal fun encodeSelection(selection: Any): String = when (selection) {
    is String -> quote(selection)
    is Boolean, is Number -> selection.toString()
    is List<*> -> selection.joinToString(",", "[", "]") { quote(it?.toString() ?: "") }
    else -> quote(selection.toString())
}

/** Minimal JSON string quoting: enough for dialog values, which are plain text. */
private fun quote(value: String): String {
    val escaped = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
    return "\"$escaped\""
}
