package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.service.CognixPushService
import com.example.continuumstudio.ui.cognix.Nudge
import com.example.continuumstudio.ui.cognix.NudgePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val Context.cognixDataStore by preferencesDataStore(name = "cognix_settings")

data class CognixUiState(
    val isLoading: Boolean = true,
    val recentPatterns: List<String> = emptyList(),
    val lastSync: Long = 0,
    val error: String? = null,
    val patternsLoading: Boolean = false,
    val patternCategories: List<PatternCategoryData> = emptyList(),
    val patternSummary: PatternSummary? = null
)

data class PatternCategoryData(
    val name: String,
    val count: Int,
    val patterns: List<PatternData>,
    val topConfidence: Float
)

data class PatternData(
    val id: String,
    val patternType: String,
    val description: String,
    val confidence: Float,
    val occurrenceCount: Int,
    val category: String,
    val metadata: Map<String, Any> = emptyMap()
)

data class PatternSummary(
    val totalPatterns: Int,
    val daysAnalyzed: Int,
    val lastAnalysis: String?
)

data class CognixConnectionState(
    val isConnected: Boolean = false,
    val serverUrl: String = "http://100.89.3.87:4200",
    val latencyMs: Long = 0,
    val deviceRegistered: Boolean = false
)

data class CognixSettings(
    val enableNudges: Boolean = true,
    val nudgeSound: Boolean = false,
    val nudgeVibration: Boolean = true,
    val localProcessingOnly: Boolean = true,
    val screenCapture: Boolean = false
)

class CognixViewModel(application: Application) : AndroidViewModel(application) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    private val _uiState = MutableStateFlow(CognixUiState())
    val uiState: StateFlow<CognixUiState> = _uiState
    
    private val _connectionState = MutableStateFlow(CognixConnectionState())
    val connectionState: StateFlow<CognixConnectionState> = _connectionState
    
    private val _activeNudges = MutableStateFlow<List<Nudge>>(emptyList())
    val activeNudges: StateFlow<List<Nudge>> = _activeNudges
    
    private val _settings = MutableStateFlow(CognixSettings())
    val settings: StateFlow<CognixSettings> = _settings
    
    private val deviceId: String by lazy {
        Settings.Secure.getString(
            application.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown-${System.currentTimeMillis()}"
    }
    
    companion object {
        private val COGNIX_URL_KEY = stringPreferencesKey("cognix_server_url")
        private val ENABLE_NUDGES_KEY = stringPreferencesKey("cognix_enable_nudges")
        private val NUDGE_SOUND_KEY = stringPreferencesKey("cognix_nudge_sound")
        private val NUDGE_VIBRATION_KEY = stringPreferencesKey("cognix_nudge_vibration")
        private val LOCAL_PROCESSING_KEY = stringPreferencesKey("cognix_local_processing")
        private val SCREEN_CAPTURE_KEY = stringPreferencesKey("cognix_screen_capture")
        private const val POLL_INTERVAL_MS = 30_000L
    }
    
    init {
        viewModelScope.launch {
            loadSettings()
            connect()
            startPolling()
        }
    }
    
    private suspend fun loadSettings() {
        try {
            val context = getApplication<Application>()
            val prefs = context.cognixDataStore.data.first()
            
            prefs[COGNIX_URL_KEY]?.let { url ->
                _connectionState.update { it.copy(serverUrl = url) }
            }
            
            _settings.update { current ->
                current.copy(
                    enableNudges = prefs[ENABLE_NUDGES_KEY]?.toBooleanStrictOrNull() ?: true,
                    nudgeSound = prefs[NUDGE_SOUND_KEY]?.toBooleanStrictOrNull() ?: false,
                    nudgeVibration = prefs[NUDGE_VIBRATION_KEY]?.toBooleanStrictOrNull() ?: true,
                    localProcessingOnly = prefs[LOCAL_PROCESSING_KEY]?.toBooleanStrictOrNull() ?: true,
                    screenCapture = prefs[SCREEN_CAPTURE_KEY]?.toBooleanStrictOrNull() ?: false
                )
            }
        } catch (e: Exception) {
            // Use defaults
        }
    }
    
    fun connect() {
        viewModelScope.launch {
            try {
                val startTime = System.currentTimeMillis()
                val request = Request.Builder()
                    .url("${_connectionState.value.serverUrl}/health")
                    .get()
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                val latency = System.currentTimeMillis() - startTime
                
                _connectionState.update { it.copy(
                    isConnected = response.isSuccessful,
                    latencyMs = latency
                )}
                
                if (response.isSuccessful) {
                    registerDevice()
                    fetchNudges()
                    fetchPatterns()
                    startPushService()
                }
                
                _uiState.update { it.copy(error = null) }
            } catch (e: Exception) {
                _connectionState.update { it.copy(isConnected = false) }
                _uiState.update { it.copy(error = e.message) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
    
    private fun startPushService() {
        if (_settings.value.enableNudges) {
            CognixPushService.startService(
                getApplication(),
                _connectionState.value.serverUrl,
                deviceId
            )
        }
    }
    
    fun stopPushService() {
        CognixPushService.stopService(getApplication())
    }
    
    private suspend fun registerDevice() {
        try {
            val body = """
                {
                    "device_id": "$deviceId",
                    "capabilities": ["ws", "ntfy"],
                    "app_version": "${Build.VERSION.RELEASE}"
                }
            """.trimIndent()
            
            val request = Request.Builder()
                .url("${_connectionState.value.serverUrl}/api/push/register")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            _connectionState.update { it.copy(deviceRegistered = response.isSuccessful) }
        } catch (e: Exception) {
            // Device registration failed, but we can still poll
        }
    }
    
    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (_connectionState.value.isConnected) {
                    fetchNudges()
                    sendHeartbeat()
                } else {
                    connect()
                }
            }
        }
    }
    
    private suspend fun sendHeartbeat() {
        try {
            val request = Request.Builder()
                .url("${_connectionState.value.serverUrl}/api/push/heartbeat/$deviceId")
                .post("".toRequestBody(jsonMediaType))
                .build()
            
            withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
        } catch (e: Exception) {
            // Heartbeat failed, non-critical
        }
    }
    
    private suspend fun fetchNudges() {
        try {
            val request = Request.Builder()
                .url("${_connectionState.value.serverUrl}/api/nudges/active")
                .get()
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                
                if (jsonResponse["success"]?.jsonPrimitive?.boolean == true) {
                    val nudgesArray = jsonResponse["nudges"]?.jsonArray ?: return
                    
                    val nudges = nudgesArray.mapNotNull { element ->
                        try {
                            val obj = element.jsonObject
                            Nudge(
                                id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                                title = obj["type"]?.jsonPrimitive?.contentOrNull ?: "Nudge",
                                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "",
                                priority = when (obj["priority"]?.jsonPrimitive?.contentOrNull) {
                                    "critical", "urgent", "high" -> NudgePriority.HIGH
                                    "low" -> NudgePriority.LOW
                                    else -> NudgePriority.MEDIUM
                                },
                                timestamp = System.currentTimeMillis()
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    
                    _activeNudges.value = nudges
                    _uiState.update { it.copy(lastSync = System.currentTimeMillis()) }
                }
            }
        } catch (e: Exception) {
            // Fetch failed, keep existing nudges
        }
    }
    
    private suspend fun fetchPatterns() {
        _uiState.update { it.copy(patternsLoading = true) }
        
        try {
            val request = Request.Builder()
                .url("${_connectionState.value.serverUrl}/api/patterns/categories?days=30")
                .get()
                .build()
            
            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return
                val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
                
                val categories = jsonResponse["categories"]?.jsonArray?.mapNotNull { catElement ->
                    try {
                        val catObj = catElement.jsonObject
                        val patterns = catObj["patterns"]?.jsonArray?.mapNotNull { patElement ->
                            try {
                                val patObj = patElement.jsonObject
                                PatternData(
                                    id = patObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                                    patternType = patObj["pattern_type"]?.jsonPrimitive?.contentOrNull ?: "",
                                    description = patObj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                                    confidence = patObj["confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f,
                                    occurrenceCount = patObj["occurrence_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                                    category = patObj["category"]?.jsonPrimitive?.contentOrNull ?: "Other"
                                )
                            } catch (e: Exception) {
                                null
                            }
                        } ?: emptyList()
                        
                        PatternCategoryData(
                            name = catObj["name"]?.jsonPrimitive?.contentOrNull ?: "Unknown",
                            count = catObj["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: patterns.size,
                            patterns = patterns,
                            topConfidence = catObj["top_confidence"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()
                
                val summary = jsonResponse["summary"]?.jsonObject?.let { sumObj ->
                    PatternSummary(
                        totalPatterns = sumObj["total_patterns"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                        daysAnalyzed = sumObj["days_analyzed"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 30,
                        lastAnalysis = sumObj["last_analysis"]?.jsonPrimitive?.contentOrNull
                    )
                }
                
                // Extract descriptions for the simple pattern list
                val recentPatterns = categories
                    .flatMap { it.patterns }
                    .sortedByDescending { it.confidence }
                    .take(5)
                    .map { it.description }
                
                _uiState.update { it.copy(
                    patternCategories = categories,
                    patternSummary = summary,
                    recentPatterns = recentPatterns,
                    patternsLoading = false
                )}
            }
        } catch (e: Exception) {
            // Fallback to placeholder patterns if API fails
            _uiState.update { it.copy(
                recentPatterns = listOf(
                    "Pattern analysis requires imported data",
                    "Import Google Takeout to see insights"
                ),
                patternsLoading = false
            )}
        }
    }
    
    fun refreshPatterns() {
        viewModelScope.launch {
            fetchPatterns()
        }
    }
    
    fun triggerPatternAnalysis() {
        viewModelScope.launch {
            try {
                val request = Request.Builder()
                    .url("${_connectionState.value.serverUrl}/api/patterns/analyze?days=30")
                    .post("".toRequestBody(jsonMediaType))
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                // Refresh patterns after analysis
                delay(1000)
                fetchPatterns()
            } catch (e: Exception) {
                // Analysis trigger failed
            }
        }
    }
    
    fun dismissNudge(nudgeId: String) {
        viewModelScope.launch {
            // Optimistic update
            _activeNudges.update { nudges ->
                nudges.filter { it.id != nudgeId }
            }
            
            try {
                val request = Request.Builder()
                    .url("${_connectionState.value.serverUrl}/api/nudges/$nudgeId/dismiss")
                    .post("".toRequestBody(jsonMediaType))
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
            } catch (e: Exception) {
                // Dismiss might have failed on server, but UI is already updated
            }
        }
    }
    
    fun snoozeNudge(nudgeId: String, minutes: Int) {
        viewModelScope.launch {
            // Optimistic update
            _activeNudges.update { nudges ->
                nudges.filter { it.id != nudgeId }
            }
            
            try {
                val request = Request.Builder()
                    .url("${_connectionState.value.serverUrl}/api/nudges/$nudgeId/snooze?minutes=$minutes")
                    .post("".toRequestBody(jsonMediaType))
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
            } catch (e: Exception) {
                // Snooze might have failed on server, but UI is already updated
            }
        }
    }
    
    fun ackNudge(nudgeId: String) {
        viewModelScope.launch {
            try {
                val body = """
                    {
                        "nudge_id": "$nudgeId",
                        "device_id": "$deviceId"
                    }
                """.trimIndent()
                
                val request = Request.Builder()
                    .url("${_connectionState.value.serverUrl}/api/push/ack")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()
                
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
            } catch (e: Exception) {
                // Ack failed, non-critical
            }
        }
    }
    
    fun updateServerUrl(url: String) {
        viewModelScope.launch {
            _connectionState.update { it.copy(serverUrl = url, deviceRegistered = false) }
            
            // Save to DataStore
            try {
                val context = getApplication<Application>()
                context.cognixDataStore.edit { prefs ->
                    prefs[COGNIX_URL_KEY] = url
                }
            } catch (e: Exception) {
                // Save failed
            }
            
            connect()
        }
    }
    
    fun updateEnableNudges(enabled: Boolean) {
        viewModelScope.launch {
            _settings.update { it.copy(enableNudges = enabled) }
            saveSettingToDataStore(ENABLE_NUDGES_KEY, enabled.toString())
            
            // Start or stop push service based on setting
            if (enabled && _connectionState.value.isConnected) {
                startPushService()
            } else if (!enabled) {
                stopPushService()
            }
        }
    }
    
    fun updateNudgeSound(enabled: Boolean) {
        viewModelScope.launch {
            _settings.update { it.copy(nudgeSound = enabled) }
            saveSettingToDataStore(NUDGE_SOUND_KEY, enabled.toString())
        }
    }
    
    fun updateNudgeVibration(enabled: Boolean) {
        viewModelScope.launch {
            _settings.update { it.copy(nudgeVibration = enabled) }
            saveSettingToDataStore(NUDGE_VIBRATION_KEY, enabled.toString())
        }
    }
    
    fun updateLocalProcessingOnly(enabled: Boolean) {
        viewModelScope.launch {
            _settings.update { it.copy(localProcessingOnly = enabled) }
            saveSettingToDataStore(LOCAL_PROCESSING_KEY, enabled.toString())
        }
    }
    
    fun updateScreenCapture(enabled: Boolean) {
        viewModelScope.launch {
            _settings.update { it.copy(screenCapture = enabled) }
            saveSettingToDataStore(SCREEN_CAPTURE_KEY, enabled.toString())
        }
    }
    
    private suspend fun saveSettingToDataStore(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        try {
            val context = getApplication<Application>()
            context.cognixDataStore.edit { prefs ->
                prefs[key] = value
            }
        } catch (e: Exception) {
            // Save failed
        }
    }
}
