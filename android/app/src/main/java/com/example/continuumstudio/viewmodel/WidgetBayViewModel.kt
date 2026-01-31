package com.example.continuumstudio.viewmodel

import android.app.Application
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "WidgetBayViewModel"

class WidgetBayViewModel(application: Application) : AndroidViewModel(application) {
    
    private val dataStore = application.dataStore
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    // Configuration persistence key
    private object PrefsKeys {
        val BAY_CONFIG = stringPreferencesKey("widget_bay_config")
    }
    
    // Widget Bay configuration
    private val _bayConfig = MutableStateFlow(BayConfig(widgets = WidgetType.defaults()))
    val bayConfig: StateFlow<BayConfig> = _bayConfig.asStateFlow()
    
    // Widget data streams
    private val _harnesses = MutableStateFlow<List<HarnessInfo>>(emptyList())
    val harnesses: StateFlow<List<HarnessInfo>> = _harnesses.asStateFlow()
    
    private val _services = MutableStateFlow<List<ServiceInfo>>(emptyList())
    val services: StateFlow<List<ServiceInfo>> = _services.asStateFlow()
    
    private val _nodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val nodes: StateFlow<List<NodeInfo>> = _nodes.asStateFlow()
    
    // Loading states
    private val _isLoadingHarnesses = MutableStateFlow(false)
    val isLoadingHarnesses: StateFlow<Boolean> = _isLoadingHarnesses.asStateFlow()
    
    private val _isLoadingServices = MutableStateFlow(false)
    val isLoadingServices: StateFlow<Boolean> = _isLoadingServices.asStateFlow()
    
    init {
        loadConfig()
    }
    
    /**
     * Load saved bay configuration
     */
    private fun loadConfig() {
        viewModelScope.launch {
            dataStore.data.map { prefs ->
                prefs[PrefsKeys.BAY_CONFIG]?.let { configJson ->
                    try {
                        json.decodeFromString<BayConfig>(configJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse config: ${e.message}")
                        null
                    }
                }
            }.collect { config ->
                if (config != null) {
                    _bayConfig.value = config
                }
            }
        }
    }
    
    /**
     * Save current bay configuration
     */
    private fun saveConfig() {
        viewModelScope.launch {
            try {
                val configJson = json.encodeToString(_bayConfig.value)
                dataStore.edit { prefs ->
                    prefs[PrefsKeys.BAY_CONFIG] = configJson
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save config: ${e.message}")
            }
        }
    }
    
    /**
     * Add a widget to the bay
     */
    fun addWidget(type: WidgetType) {
        val currentConfig = _bayConfig.value
        val maxOrder = currentConfig.widgets.maxOfOrNull { it.order } ?: -1
        val newWidget = WidgetConfig(
            type = type,
            span = type.defaultSpan,
            order = maxOrder + 1
        )
        _bayConfig.value = currentConfig.copy(
            widgets = currentConfig.widgets + newWidget
        )
        saveConfig()
    }
    
    /**
     * Remove a widget from the bay
     */
    fun removeWidget(widgetId: String) {
        val currentConfig = _bayConfig.value
        _bayConfig.value = currentConfig.copy(
            widgets = currentConfig.widgets.filter { it.id != widgetId }
        )
        saveConfig()
    }
    
    /**
     * Reorder widgets (move from one position to another)
     */
    fun reorderWidgets(fromIndex: Int, toIndex: Int) {
        val currentConfig = _bayConfig.value
        val sortedWidgets = currentConfig.widgets.sortedBy { it.order }.toMutableList()
        
        if (fromIndex in sortedWidgets.indices && toIndex in sortedWidgets.indices) {
            val widget = sortedWidgets.removeAt(fromIndex)
            sortedWidgets.add(toIndex, widget)
            
            // Re-assign order values
            val reorderedWidgets = sortedWidgets.mapIndexed { index, w ->
                w.copy(order = index)
            }
            
            _bayConfig.value = currentConfig.copy(widgets = reorderedWidgets)
            saveConfig()
        }
    }
    
    /**
     * Update widget span (1 or 2 columns)
     */
    fun updateWidgetSpan(widgetId: String, span: Int) {
        val currentConfig = _bayConfig.value
        _bayConfig.value = currentConfig.copy(
            widgets = currentConfig.widgets.map { widget ->
                if (widget.id == widgetId) widget.copy(span = span.coerceIn(1, 2))
                else widget
            }
        )
        saveConfig()
    }
    
    /**
     * Update column count
     */
    fun setColumns(columns: Int) {
        _bayConfig.value = _bayConfig.value.copy(columns = columns.coerceIn(1, 3))
        saveConfig()
    }
    
    /**
     * Reset to default configuration
     */
    fun resetToDefaults() {
        _bayConfig.value = BayConfig(widgets = WidgetType.defaults())
        saveConfig()
    }
    
    /**
     * Fetch harness data from Synapsix
     */
    fun fetchHarnesses(serverUrl: String) {
        viewModelScope.launch {
            _isLoadingHarnesses.value = true
            try {
                val url = "http://$serverUrl/api/dns/services"
                val request = Request.Builder().url(url).build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        // Parse services and filter for harnesses
                        val allServices = parseServices(body)
                        _harnesses.value = allServices
                            .filter { it.serviceType.contains("harness") }
                            .map { service ->
                                HarnessInfo(
                                    id = service.id,
                                    name = service.displayName,
                                    type = service.serviceType,
                                    status = service.health,
                                    pid = service.metadata["pid"]?.toIntOrNull(),
                                    windowId = service.metadata["window_id"],
                                    startedAt = service.metadata["started_at"]?.toLongOrNull()
                                )
                            }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch harnesses: ${e.message}")
            } finally {
                _isLoadingHarnesses.value = false
            }
        }
    }
    
    /**
     * Fetch all services from the registry
     */
    fun fetchServices(serverUrl: String) {
        viewModelScope.launch {
            _isLoadingServices.value = true
            try {
                val url = "http://$serverUrl/api/dns/services"
                val request = Request.Builder().url(url).build()
                
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: "[]"
                        _services.value = parseServices(body)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch services: ${e.message}")
            } finally {
                _isLoadingServices.value = false
            }
        }
    }
    
    /**
     * Parse service JSON response
     */
    private fun parseServices(jsonBody: String): List<ServiceInfo> {
        return try {
            val jsonArray = org.json.JSONArray(jsonBody)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                val metadataObj = obj.optJSONObject("metadata") ?: org.json.JSONObject()
                val metadata = mutableMapOf<String, String>()
                metadataObj.keys().forEach { key ->
                    metadata[key] = metadataObj.optString(key, "")
                }
                
                ServiceInfo(
                    id = obj.optString("id", ""),
                    displayName = obj.optString("display_name", obj.optString("name", "Unknown")),
                    serviceType = obj.optString("type", ""),
                    host = obj.optString("host", ""),
                    port = obj.optInt("port", 0),
                    health = obj.optString("health", "unknown"),
                    metadata = metadata
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse services: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Refresh all widget data
     */
    fun refreshAll(serverUrl: String) {
        fetchHarnesses(serverUrl)
        fetchServices(serverUrl)
    }
}

