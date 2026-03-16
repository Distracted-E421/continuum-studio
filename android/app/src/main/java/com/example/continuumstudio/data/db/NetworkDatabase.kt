package com.example.continuumstudio.data.db

data class NetworkMeasurement(
    val id: Long = 0,
    val timestamp: Long,
    val latencyMs: Int,
    val connectionType: String,
    val networkName: String?,
    val signalStrength: Int?,
    val serverUrl: String,
    val success: Boolean
)

data class NetworkStats(
    val averageLatency: Double,
    val minLatency: Int,
    val maxLatency: Int,
    val measurementCount: Int,
    val failureCount: Int,
    val successRate: Double
)

enum class TimeRange(val millis: Long, val label: String) {
    FIVE_MINUTES(5 * 60 * 1000L, "5 min"),
    FIFTEEN_MINUTES(15 * 60 * 1000L, "15 min"),
    ONE_HOUR(60 * 60 * 1000L, "1 hr"),
    SIX_HOURS(6 * 60 * 60 * 1000L, "6 hr"),
    TWENTY_FOUR_HOURS(24 * 60 * 60 * 1000L, "24 hr")
}
