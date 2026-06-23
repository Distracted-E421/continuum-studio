package com.example.continuumstudio.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Accessibility Service for capturing Gemini app interactions.
 *
 * Part of Project Ouroboros - captures clean query/response pairs
 * before they're sent to Google, for local AI training.
 *
 * ## Limitations
 * - Requires user to manually enable accessibility service
 * - Only captures text visible in UI (not streaming/hidden content)
 * - Cannot intercept network traffic
 * - May miss rapid UI updates
 *
 * ## Privacy
 * - All data stored locally on device
 * - Syncs to Obsidian via encrypted channel (Tailscale)
 * - No cloud upload
 */
class GeminiAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var lastQuery: String? = null
    private var lastQueryTime: Instant? = null
    private var pendingResponses = mutableListOf<String>()

    private val _isEnabled = MutableStateFlow(false)
    private val _captureCount = MutableStateFlow(0)

    companion object {
        private const val TAG = "GeminiA11y"
        private const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        private const val NOTEBOOK_LM_PACKAGE = "com.google.android.apps.notebooklm"

        private val TARGET_PACKAGES = setOf(GEMINI_PACKAGE, NOTEBOOK_LM_PACKAGE)

        private val _interactions = MutableSharedFlow<GeminiInteraction>(
            replay = 10,
            extraBufferCapacity = 50
        )
        val interactions = _interactions.asSharedFlow()

        private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Disabled)
        val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

        var instance: GeminiAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _serviceState.value = ServiceState.Enabled
        _isEnabled.value = true

        android.util.Log.i(TAG, "Gemini Accessibility Service connected")

        configureService()
    }

    private fun configureService() {
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS

            notificationTimeout = 100

            packageNames = TARGET_PACKAGES.toTypedArray()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.packageName?.toString() !in TARGET_PACKAGES) return

        scope.launch {
            processEvent(event)
        }
    }

    private suspend fun processEvent(event: AccessibilityEvent) {
        val rootNode = try {
            rootInActiveWindow
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to get root window: ${e.message}")
            return
        }

        rootNode ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                handleTextChanged(event, rootNode)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChanged(rootNode)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleClick(event, rootNode)
            }
            else -> {}
        }

        rootNode.recycle()
    }

    private suspend fun handleTextChanged(event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val inputField = findInputField(root)
        inputField?.let { node ->
            val text = node.text?.toString() ?: return
            if (text.length > 10 && isLikelyQuery(text)) {
                lastQuery = text
                lastQueryTime = Instant.now()
                android.util.Log.d(TAG, "Captured query: ${text.take(50)}...")
            }
            node.recycle()
        }
    }

    private suspend fun handleContentChanged(root: AccessibilityNodeInfo) {
        val responseText = extractResponseText(root)
        if (responseText.isNotEmpty() && lastQuery != null) {
            processInteraction(lastQuery!!, responseText)
        }
    }

    private suspend fun handleClick(event: AccessibilityEvent, root: AccessibilityNodeInfo) {
        val clickedNode = event.source ?: return

        val isSendButton = clickedNode.contentDescription?.toString()?.lowercase()?.let {
            it.contains("send") || it.contains("submit")
        } == true

        if (isSendButton && lastQuery != null) {
            android.util.Log.d(TAG, "Send button clicked, query pending: ${lastQuery?.take(30)}...")
        }

        clickedNode.recycle()
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassName(root, "android.widget.EditText", editTexts)

        return editTexts.firstOrNull { node ->
            node.isFocusable && node.isVisibleToUser
        }
    }

    private fun extractResponseText(root: AccessibilityNodeInfo): String {
        val textNodes = mutableListOf<AccessibilityNodeInfo>()

        findTextNodes(root, textNodes)

        val responses = textNodes
            .filter { node ->
                val text = node.text?.toString() ?: ""
                text.length > 50 && isLikelyResponse(text)
            }
            .map { it.text.toString() }

        textNodes.forEach { it.recycle() }

        return responses.maxByOrNull { it.length } ?: ""
    }

    private fun findNodesByClassName(
        node: AccessibilityNodeInfo,
        className: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.className?.toString() == className) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByClassName(child, className, results)
            child.recycle()
        }
    }

    private fun findTextNodes(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() && text.length > 20) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findTextNodes(child, results)
            child.recycle()
        }
    }

    private fun isLikelyQuery(text: String): Boolean {
        val hasNaturalPunctuation = text.contains("?") || text.endsWith(".")
        val hasSpaces = text.count { it == ' ' } >= 2
        val notAllCaps = text != text.uppercase()
        val reasonableLength = text.length in 10..5000

        return reasonableLength && hasSpaces && (hasNaturalPunctuation || notAllCaps)
    }

    private fun isLikelyResponse(text: String): Boolean {
        val hasSpaces = text.count { it == ' ' } >= 5
        val reasonableLength = text.length > 50

        return reasonableLength && hasSpaces
    }

    private suspend fun processInteraction(query: String, response: String) {
        val interaction = GeminiInteraction(
            id = java.util.UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            query = query.trim(),
            response = response.trim(),
            source = "accessibility_service",
            packageName = GEMINI_PACKAGE
        )

        _interactions.emit(interaction)
        _captureCount.value++

        android.util.Log.i(TAG, "Captured interaction: Q=${query.take(30)}... R=${response.take(30)}...")

        lastQuery = null
        lastQueryTime = null
        pendingResponses.clear()
    }

    override fun onInterrupt() {
        android.util.Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        instance = null
        _serviceState.value = ServiceState.Disabled
        _isEnabled.value = false
        android.util.Log.i(TAG, "Service destroyed")
    }

    sealed class ServiceState {
        data object Disabled : ServiceState()
        data object Enabled : ServiceState()
        data class Error(val message: String) : ServiceState()
    }
}

data class GeminiInteraction(
    val id: String,
    val timestamp: String,
    val query: String,
    val response: String,
    val thinking: String? = null,
    val source: String,
    val packageName: String,
    val metadata: Map<String, Any> = emptyMap()
)
