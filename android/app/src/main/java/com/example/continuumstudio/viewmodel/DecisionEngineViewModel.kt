package com.example.continuumstudio.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.continuumstudio.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*

/**
 * Decision Engine ViewModel.
 * Implements automated dialog handling based on orchestrator mode and priority.
 * Core goal: Keep existing agents running and unblocked to minimize request costs.
 */
class DecisionEngineViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TRIAGE_PROCESS_INTERVAL_MS = 1000L
    }
    
    private val _uiState = MutableStateFlow(DecisionEngineUiState())
    val uiState: StateFlow<DecisionEngineUiState> = _uiState.asStateFlow()
    
    private val config = DecisionEngineConfig()
    private val history = ArrayDeque<DecisionRecord>(config.maxHistorySize)
    private val triageQueue = mutableListOf<TriageItem>()
    private val undoableDecisions = mutableListOf<Pair<DecisionRecord, Long>>()
    
    private var processJob: Job? = null
    
    // Callback for when a decision should be executed
    var onAutoDecision: ((dialogId: String, response: String) -> Unit)? = null
    
    init {
        startProcessingLoop()
    }
    
    fun setMode(mode: OrchestratorPresenceMode) {
        _uiState.update { it.copy(mode = mode) }
    }
    
    fun getMode(): OrchestratorPresenceMode = _uiState.value.mode
    
    /**
     * Evaluate a dialog and determine how it should be handled.
     */
    fun evaluate(dialog: PendingAgentDialog): DecisionResult {
        val detectedCritical = detectCriticalKeywords(dialog.prompt) ||
                detectCriticalKeywords(dialog.title)
        
        val effectivePriority = if (detectedCritical) {
            DialogPriority.CRITICAL
        } else {
            parseDialogPriority(dialog.priority)
        }
        
        return when (_uiState.value.mode) {
            OrchestratorPresenceMode.USER_ACTIVE -> {
                DecisionResult.RequireUser(
                    reasoning = "User active mode - all dialogs require user attention"
                )
            }
            
            OrchestratorPresenceMode.USER_DELEGATE -> {
                when (effectivePriority) {
                    DialogPriority.LOW, DialogPriority.NORMAL -> {
                        val (response, reasoning) = suggestResponse(dialog)
                        if (response != null) {
                            DecisionResult.AutoHandle(response, reasoning)
                        } else {
                            DecisionResult.Triage(
                                state = TriageState.AUTO_APPROVE,
                                suggestedResponse = suggestDefaultResponse(dialog),
                                reasoning = "Routine dialog - will auto-approve unless overridden",
                                timeoutMs = getTimeoutForAgent(dialog)
                            )
                        }
                    }
                    DialogPriority.HIGH, DialogPriority.CRITICAL -> {
                        val suffix = if (detectedCritical) " (critical keywords detected)" else ""
                        DecisionResult.RequireUser(
                            reasoning = "Priority ${effectivePriority.asString()} - escalating to user$suffix"
                        )
                    }
                }
            }
            
            OrchestratorPresenceMode.SPECTATOR -> {
                val timeoutMs = getTimeoutForAgent(dialog)
                when (effectivePriority) {
                    DialogPriority.LOW, DialogPriority.NORMAL, DialogPriority.HIGH -> {
                        val (response, reasoning) = suggestResponse(dialog)
                        if (response != null) {
                            DecisionResult.Triage(
                                state = TriageState.AUTO_APPROVE,
                                suggestedResponse = response,
                                reasoning = "Spectator mode: $reasoning (user can claim within ${timeoutMs/1000}s)",
                                timeoutMs = timeoutMs
                            )
                        } else {
                            DecisionResult.Triage(
                                state = TriageState.AUTO_APPROVE,
                                suggestedResponse = suggestDefaultResponse(dialog),
                                reasoning = "Spectator mode: Will auto-approve; user can claim to override",
                                timeoutMs = timeoutMs
                            )
                        }
                    }
                    DialogPriority.CRITICAL -> {
                        val suffix = if (detectedCritical) " (critical keywords detected)" else ""
                        DecisionResult.Triage(
                            state = TriageState.MANUAL,
                            suggestedResponse = null,
                            reasoning = "Critical dialog - requires user decision$suffix",
                            timeoutMs = timeoutMs
                        )
                    }
                }
            }
            
            OrchestratorPresenceMode.AUTONOMOUS -> {
                when (effectivePriority) {
                    DialogPriority.LOW, DialogPriority.NORMAL, DialogPriority.HIGH -> {
                        val (response, reasoning) = suggestResponse(dialog)
                        if (response != null) {
                            DecisionResult.AutoHandle(response, reasoning)
                        } else {
                            DecisionResult.AutoHandle(
                                response = suggestDefaultResponse(dialog) ?: "continue",
                                reasoning = "Autonomous mode: Using default response to keep agent running"
                            )
                        }
                    }
                    DialogPriority.CRITICAL -> {
                        val suffix = if (detectedCritical) " (critical keywords detected)" else ""
                        DecisionResult.Triage(
                            state = TriageState.PAUSED,
                            suggestedResponse = null,
                            reasoning = "Critical dialog queued for user review$suffix",
                            timeoutMs = 24 * 60 * 60 * 1000L // 24 hours
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Process a dialog through the decision engine.
     * Returns true if auto-handled, false if user action required.
     */
    fun processDialog(dialog: PendingAgentDialog): Boolean {
        val result = evaluate(dialog)
        
        return when (result) {
            is DecisionResult.AutoHandle -> {
                recordDecision(
                    dialog = dialog,
                    response = result.response,
                    reasoning = result.reasoning,
                    autoHandled = true
                )
                onAutoDecision?.invoke(dialog.id, result.response)
                true
            }
            is DecisionResult.RequireUser -> {
                false
            }
            is DecisionResult.Triage -> {
                addToTriage(TriageItem(
                    dialog = dialog,
                    state = result.state,
                    addedAt = System.currentTimeMillis(),
                    suggestedResponse = result.suggestedResponse,
                    reasoning = result.reasoning,
                    timeoutMs = result.timeoutMs
                ))
                result.state != TriageState.MANUAL && result.state != TriageState.PAUSED
            }
        }
    }
    
    // ==========================================================================
    // Triage Queue Management
    // ==========================================================================
    
    fun addToTriage(item: TriageItem) {
        triageQueue.add(item)
        updateTriageState()
    }
    
    fun removeFromTriage(dialogId: String): TriageItem? {
        val index = triageQueue.indexOfFirst { it.dialog.id == dialogId }
        return if (index >= 0) {
            val item = triageQueue.removeAt(index)
            updateTriageState()
            item
        } else null
    }
    
    fun setTriageState(dialogId: String, state: TriageState) {
        val index = triageQueue.indexOfFirst { it.dialog.id == dialogId }
        if (index >= 0) {
            triageQueue[index] = triageQueue[index].copy(state = state)
            updateTriageState()
        }
    }
    
    fun approveTriage(dialogId: String) {
        val item = removeFromTriage(dialogId) ?: return
        val response = item.suggestedResponse ?: return
        
        recordDecision(
            dialog = item.dialog,
            response = response,
            reasoning = "User approved triage suggestion",
            autoHandled = false
        )
        onAutoDecision?.invoke(dialogId, response)
    }
    
    fun declineTriage(dialogId: String) {
        val item = removeFromTriage(dialogId) ?: return
        setTriageState(dialogId, TriageState.MANUAL)
    }
    
    fun claimDialog(dialogId: String) {
        setTriageState(dialogId, TriageState.MANUAL)
    }
    
    // ==========================================================================
    // Decision Recording
    // ==========================================================================
    
    private fun recordDecision(
        dialog: PendingAgentDialog,
        response: String,
        reasoning: String,
        autoHandled: Boolean
    ) {
        val record = DecisionRecord(
            dialogId = dialog.id,
            agentId = dialog.agentId,
            workspace = dialog.workspace,
            source = dialog.source ?: "unknown",
            priority = dialog.priority ?: "normal",
            response = response,
            reasoning = reasoning,
            autoHandled = autoHandled,
            timestamp = System.currentTimeMillis(),
            mode = _uiState.value.mode.name.lowercase(),
            undoable = autoHandled
        )
        
        if (record.undoable) {
            undoableDecisions.add(record to System.currentTimeMillis())
        }
        
        history.addFirst(record)
        while (history.size > config.maxHistorySize) {
            history.removeLast()
        }
        
        _uiState.update { 
            it.copy(
                recentDecisions = history.take(20).toList(),
                undoableDecisions = getUndoableDecisions()
            )
        }
    }
    
    fun getUndoableDecisions(): List<DecisionRecord> {
        val undoWindowMs = config.undoWindowSecs * 1000
        val now = System.currentTimeMillis()
        return undoableDecisions
            .filter { (_, timestamp) -> now - timestamp < undoWindowMs }
            .map { (record, _) -> record }
    }
    
    fun cleanupUndoable() {
        val undoWindowMs = config.undoWindowSecs * 1000
        val now = System.currentTimeMillis()
        undoableDecisions.removeAll { (_, timestamp) -> now - timestamp >= undoWindowMs }
        _uiState.update { it.copy(undoableDecisions = getUndoableDecisions()) }
    }
    
    // ==========================================================================
    // Helper Functions
    // ==========================================================================
    
    private fun detectCriticalKeywords(text: String): Boolean {
        val textLower = text.lowercase()
        return config.criticalKeywords.any { textLower.contains(it) }
    }
    
    private fun suggestResponse(dialog: PendingAgentDialog): Pair<String?, String> {
        val promptLower = dialog.prompt.lowercase()
        val options = dialog.options ?: return null to "No options available"
        
        // "Continue?" type dialogs
        if (promptLower.contains("continue") || promptLower.contains("proceed")) {
            for (opt in options) {
                val valLower = opt.value.lowercase()
                if (valLower == "continue" || valLower == "yes" || valLower == "proceed") {
                    return opt.value to "Selected 'continue' option to keep agent running"
                }
            }
        }
        
        // Session continuation dialogs - ALWAYS continue to save requests
        if (promptLower.contains("session") && 
            (promptLower.contains("continue") || promptLower.contains("end"))) {
            for (opt in options) {
                val valLower = opt.value.lowercase()
                // Never select "done" or "end" - we want to keep agents running
                if (valLower == "continue" || valLower.contains("next")) {
                    return opt.value to "Continuing session to minimize request costs"
                }
            }
        }
        
        // Confirmation dialogs - default to "yes" for non-destructive
        if (options.size == 2) {
            val hasYes = options.any { it.value.lowercase() == "yes" }
            val hasNo = options.any { it.value.lowercase() == "no" }
            if (hasYes && hasNo && !detectCriticalKeywords(dialog.prompt)) {
                return "yes" to "Non-destructive confirmation - defaulting to 'yes'"
            }
        }
        
        return null to "No confident suggestion"
    }
    
    private fun suggestDefaultResponse(dialog: PendingAgentDialog): String? {
        val options = dialog.options ?: return null
        return options.firstOrNull()?.value
    }
    
    private fun getTimeoutForAgent(dialog: PendingAgentDialog): Long {
        val agentId = dialog.agentId
        if (agentId != null && config.agentTimeouts.containsKey(agentId)) {
            return config.agentTimeouts[agentId]!! * 1000
        }
        return config.defaultTriageTimeoutSecs * 1000
    }
    
    private fun parseDialogPriority(priority: String?): DialogPriority {
        return when (priority?.lowercase()) {
            "low" -> DialogPriority.LOW
            "high" -> DialogPriority.HIGH
            "critical" -> DialogPriority.CRITICAL
            else -> DialogPriority.NORMAL
        }
    }
    
    // ==========================================================================
    // Processing Loop
    // ==========================================================================
    
    private fun startProcessingLoop() {
        processJob?.cancel()
        processJob = viewModelScope.launch {
            while (true) {
                processTriageTimeouts()
                cleanupUndoable()
                delay(TRIAGE_PROCESS_INTERVAL_MS)
            }
        }
    }
    
    private fun processTriageTimeouts() {
        val now = System.currentTimeMillis()
        val expired = triageQueue.filter { it.isExpired }
        
        for (item in expired) {
            when (item.state) {
                TriageState.AUTO_APPROVE -> {
                    val response = item.suggestedResponse
                    if (response != null) {
                        recordDecision(
                            dialog = item.dialog,
                            response = response,
                            reasoning = "Triage timeout - auto-approved",
                            autoHandled = true
                        )
                        onAutoDecision?.invoke(item.dialog.id, response)
                    }
                    triageQueue.remove(item)
                }
                TriageState.AUTO_DECLINE -> {
                    // Just remove from queue, no action taken
                    triageQueue.remove(item)
                }
                TriageState.MANUAL, TriageState.PAUSED -> {
                    // Keep in queue
                }
            }
        }
        
        updateTriageState()
    }
    
    private fun updateTriageState() {
        _uiState.update {
            it.copy(triageQueue = triageQueue.toList())
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        processJob?.cancel()
    }
}
