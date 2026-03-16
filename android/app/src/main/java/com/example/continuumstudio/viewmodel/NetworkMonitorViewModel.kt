package com.example.continuumstudio.viewmodel

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "NetworkMonitorViewModel"
private const val DEFAULT_PING_INTERVAL_MS = 10_000L
private const val MAX_MEASUREMENTS = 500

data class CurrentNetworkState(
    val isConnected: Boolean = false,
    val connectionType: ConnectionType = ConnectionType.UNKNOWN,
    val networkName: String? = null,
    val signalStrength: Int? = null,
    val lastLatencyMs: Int? = null,
    val quality: NetworkQuality = NetworkQuality.UNKNOWN
)

enum class ConnectionType(val displayName: String) {
    WIFI("WiFi"),
    CELLULAR_5G("5G"),
    CELLULAR_LTE("LTE"),
    CELLULAR_3G("3G"),
    CELLULAR_2G("2G"),
    ETHERNET("Ethernet"),
    VPN("VPN"),
    UNKNOWN("Unknown")
}

enum class NetworkQuality(val emoji: String, val label: String) {
    EXCELLENT("🟢", "Excellent"),
    GOOD("🟡", "Good"),
    FAIR("🟠", "Fair"),
    POOR("🔴", "Poor"),
    DISCONNECTED("⚫", "Disconnected"),
    UNKNOWN("⚪", "Unknown")
}

class NetworkMonitorViewModel(application: Application) : AndroidViewModel(application) {
    
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager = application.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
    
    private var pingJob: Job? = null
    
    private val measurementHistory = mutableListOf<NetworkMeasurement>()
    
    private val _serverUrl = MutableStateFlow("http://100.109.236.61:8080")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()
    
    private val _currentState = MutableStateFlow(CurrentNetworkState())
    val currentState: StateFlow<CurrentNetworkState> = _currentState.asStateFlow()
    
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()
    
    private val _selectedTimeRange = MutableStateFlow(TimeRange.FIFTEEN_MINUTES)
    val selectedTimeRange: StateFlow<TimeRange> = _selectedTimeRange.asStateFlow()
    
    private val _measurements = MutableStateFlow<List<NetworkMeasurement>>(emptyList())
    val measurements: StateFlow<List<NetworkMeasurement>> = _measurements.asStateFlow()
    
    private val _stats = MutableStateFlow<NetworkStats?>(null)
    val stats: StateFlow<NetworkStats?> = _stats.asStateFlow()
    
    init {
        updateCurrentNetworkInfo()
    }
    
    fun setServerUrl(url: String) {
        _serverUrl.value = url
    }
    
    fun setTimeRange(range: TimeRange) {
        _selectedTimeRange.value = range
        refreshMeasurementsAndStats()
    }
    
    fun startMonitoring() {
        if (_isMonitoring.value) return
        
        _isMonitoring.value = true
        pingJob = viewModelScope.launch {
            while (isActive) {
                performPing()
                delay(DEFAULT_PING_INTERVAL_MS)
            }
        }
    }
    
    fun stopMonitoring() {
        _isMonitoring.value = false
        pingJob?.cancel()
        pingJob = null
    }
    
    fun performSinglePing() {
        viewModelScope.launch {
            performPing()
        }
    }
    
    private suspend fun performPing() {
        updateCurrentNetworkInfo()
        
        val currentNet = _currentState.value
        if (!currentNet.isConnected) {
            addMeasurement(NetworkMeasurement(
                timestamp = System.currentTimeMillis(),
                latencyMs = -1,
                connectionType = currentNet.connectionType.name,
                networkName = currentNet.networkName,
                signalStrength = currentNet.signalStrength,
                serverUrl = _serverUrl.value,
                success = false
            ))
            return
        }
        
        try {
            val startTime = System.currentTimeMillis()
            val request = Request.Builder()
                .url("${_serverUrl.value}/api/status")
                .head()
                .build()
            
            withContext(Dispatchers.IO) {
                httpClient.newCall(request).execute().use { response ->
                    val latency = (System.currentTimeMillis() - startTime).toInt()
                    val success = response.isSuccessful || response.code == 404
                    
                    addMeasurement(NetworkMeasurement(
                        timestamp = System.currentTimeMillis(),
                        latencyMs = latency,
                        connectionType = currentNet.connectionType.name,
                        networkName = currentNet.networkName,
                        signalStrength = currentNet.signalStrength,
                        serverUrl = _serverUrl.value,
                        success = success
                    ))
                    
                    _currentState.update { state ->
                        state.copy(
                            lastLatencyMs = latency,
                            quality = calculateQuality(latency, success)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed: ${e.message}")
            addMeasurement(NetworkMeasurement(
                timestamp = System.currentTimeMillis(),
                latencyMs = -1,
                connectionType = currentNet.connectionType.name,
                networkName = currentNet.networkName,
                signalStrength = currentNet.signalStrength,
                serverUrl = _serverUrl.value,
                success = false
            ))
            
            _currentState.update { state ->
                state.copy(
                    lastLatencyMs = null,
                    quality = NetworkQuality.POOR
                )
            }
        }
    }
    
    private fun addMeasurement(measurement: NetworkMeasurement) {
        synchronized(measurementHistory) {
            measurementHistory.add(measurement)
            if (measurementHistory.size > MAX_MEASUREMENTS) {
                measurementHistory.removeAt(0)
            }
        }
        refreshMeasurementsAndStats()
    }
    
    private fun refreshMeasurementsAndStats() {
        val since = System.currentTimeMillis() - _selectedTimeRange.value.millis
        val filtered = synchronized(measurementHistory) {
            measurementHistory.filter { it.timestamp > since }.toList()
        }
        _measurements.value = filtered
        
        val successful = filtered.filter { it.success }
        if (successful.isNotEmpty()) {
            val avgLatency = successful.map { it.latencyMs }.average()
            val minLatency = successful.minOf { it.latencyMs }
            val maxLatency = successful.maxOf { it.latencyMs }
            val failureCount = filtered.count { !it.success }
            
            _stats.value = NetworkStats(
                averageLatency = avgLatency,
                minLatency = minLatency,
                maxLatency = maxLatency,
                measurementCount = filtered.size,
                failureCount = failureCount,
                successRate = (filtered.size - failureCount).toDouble() / filtered.size * 100
            )
        } else {
            _stats.value = null
        }
    }
    
    private fun updateCurrentNetworkInfo() {
        val network = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        
        if (network == null || capabilities == null) {
            _currentState.update { state ->
                state.copy(
                    isConnected = false,
                    connectionType = ConnectionType.UNKNOWN,
                    networkName = null,
                    signalStrength = null,
                    quality = NetworkQuality.DISCONNECTED
                )
            }
            return
        }
        
        val connectionType = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> getCellularType()
            else -> ConnectionType.UNKNOWN
        }
        
        _currentState.update { state ->
            state.copy(
                isConnected = true,
                connectionType = connectionType,
                networkName = getNetworkName(connectionType)
            )
        }
    }
    
    private fun getCellularType(): ConnectionType {
        return when (telephonyManager?.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_NR -> ConnectionType.CELLULAR_5G
            TelephonyManager.NETWORK_TYPE_LTE -> ConnectionType.CELLULAR_LTE
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_UMTS -> ConnectionType.CELLULAR_3G
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA -> ConnectionType.CELLULAR_2G
            else -> ConnectionType.CELLULAR_LTE
        }
    }
    
    private fun getNetworkName(type: ConnectionType): String? {
        return when (type) {
            ConnectionType.WIFI -> "WiFi"
            ConnectionType.CELLULAR_5G -> telephonyManager?.networkOperatorName ?: "5G"
            ConnectionType.CELLULAR_LTE -> telephonyManager?.networkOperatorName ?: "LTE"
            ConnectionType.CELLULAR_3G -> telephonyManager?.networkOperatorName ?: "3G"
            ConnectionType.CELLULAR_2G -> telephonyManager?.networkOperatorName ?: "2G"
            ConnectionType.ETHERNET -> "Ethernet"
            ConnectionType.VPN -> "VPN"
            ConnectionType.UNKNOWN -> null
        }
    }
    
    private fun calculateQuality(latencyMs: Int, success: Boolean): NetworkQuality {
        if (!success) return NetworkQuality.POOR
        return when {
            latencyMs < 50 -> NetworkQuality.EXCELLENT
            latencyMs < 150 -> NetworkQuality.GOOD
            latencyMs < 300 -> NetworkQuality.FAIR
            else -> NetworkQuality.POOR
        }
    }
    
    fun clearHistory() {
        synchronized(measurementHistory) {
            measurementHistory.clear()
        }
        refreshMeasurementsAndStats()
    }
    
    override fun onCleared() {
        super.onCleared()
        pingJob?.cancel()
    }
}
