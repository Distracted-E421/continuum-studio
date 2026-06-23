package com.example.continuumstudio.viewmodel

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.service.GeminiAccessibilityService
import com.example.continuumstudio.service.GeminiInteraction
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Instant

class GeminiCaptureViewModel : ViewModel() {

    private val _interactions = MutableStateFlow<List<GeminiInteraction>>(emptyList())
    val interactions: StateFlow<List<GeminiInteraction>> = _interactions.asStateFlow()

    private val _isServiceEnabled = MutableStateFlow(false)
    val isServiceEnabled: StateFlow<Boolean> = _isServiceEnabled.asStateFlow()

    private val _captureStats = MutableStateFlow(CaptureStats())
    val captureStats: StateFlow<CaptureStats> = _captureStats.asStateFlow()

    init {
        viewModelScope.launch {
            GeminiAccessibilityService.interactions.collect { interaction ->
                _interactions.update { current ->
                    (listOf(interaction) + current).take(100)
                }
                _captureStats.update { stats ->
                    stats.copy(
                        totalCaptures = stats.totalCaptures + 1,
                        lastCaptureTime = Instant.now()
                    )
                }
            }
        }

        viewModelScope.launch {
            GeminiAccessibilityService.serviceState.collect { state ->
                _isServiceEnabled.value = state is GeminiAccessibilityService.ServiceState.Enabled
            }
        }
    }

    fun checkServiceStatus(context: Context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

        _isServiceEnabled.value = enabledServices.any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
            info.resolveInfo.serviceInfo.name == GeminiAccessibilityService::class.java.name
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun clearHistory() {
        _interactions.value = emptyList()
    }

    suspend fun exportInteractions(): String {
        val data = _interactions.value.map { interaction ->
            mapOf(
                "id" to interaction.id,
                "timestamp" to interaction.timestamp,
                "query" to interaction.query,
                "response" to interaction.response,
                "thinking" to interaction.thinking,
                "source" to interaction.source,
                "packageName" to interaction.packageName
            )
        }

        return kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.serializer(),
            mapOf(
                "version" to "1.0",
                "source" to "android_accessibility",
                "exportDate" to Instant.now().toString(),
                "data" to data
            )
        )
    }

    data class CaptureStats(
        val totalCaptures: Int = 0,
        val lastCaptureTime: Instant? = null,
        val sessionStart: Instant = Instant.now()
    )
}
