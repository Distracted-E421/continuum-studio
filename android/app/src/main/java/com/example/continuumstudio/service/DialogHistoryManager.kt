package com.example.continuumstudio.service

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages conversation history for Android Auto MessagingStyle notifications.
 * 
 * Stores recent dialog exchanges (agent prompts + user replies) to provide
 * context in the notification thread. This helps users understand the
 * conversation flow when replying via voice.
 * 
 * Features:
 * - Persistent storage (survives app restart)
 * - Configurable max history size
 * - Agent and user message distinction
 * - Timestamp tracking
 */
class DialogHistoryManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dialog_history")
    private val historyKey = stringPreferencesKey("message_history")
    
    private val _history = MutableStateFlow<List<HistoryMessage>>(emptyList())
    val history: StateFlow<List<HistoryMessage>> = _history
    
    var maxMessages = 10
        set(value) {
            field = value.coerceIn(3, 50)
            trimHistory()
        }

    @Serializable
    data class HistoryMessage(
        val id: String,
        val text: String,
        val isFromAgent: Boolean,
        val timestamp: Long,
        val dialogType: String? = null,
        val title: String? = null
    )

    init {
        loadHistory()
    }

    /**
     * Add an agent dialog to history.
     */
    fun addAgentDialog(
        dialogId: String,
        title: String,
        prompt: String,
        dialogType: String
    ) {
        scope.launch {
            val message = HistoryMessage(
                id = dialogId,
                text = prompt,
                isFromAgent = true,
                timestamp = System.currentTimeMillis(),
                dialogType = dialogType,
                title = title
            )
            
            val current = _history.value.toMutableList()
            current.add(message)
            trimAndSave(current)
            
            Log.d(TAG, "Added agent dialog: ${title.take(30)}...")
        }
    }

    /**
     * Add a user reply to history.
     */
    fun addUserReply(
        dialogId: String,
        replyText: String,
        parsedSelection: String? = null
    ) {
        scope.launch {
            val message = HistoryMessage(
                id = "${dialogId}_reply",
                text = parsedSelection ?: replyText,
                isFromAgent = false,
                timestamp = System.currentTimeMillis()
            )
            
            val current = _history.value.toMutableList()
            current.add(message)
            trimAndSave(current)
            
            Log.d(TAG, "Added user reply: ${replyText.take(30)}...")
        }
    }

    /**
     * Get recent history for MessagingStyle notification.
     * Returns messages in chronological order (oldest first).
     */
    fun getRecentHistory(limit: Int = maxMessages): List<HistoryMessage> {
        return _history.value.takeLast(limit)
    }

    /**
     * Clear all history.
     */
    fun clearHistory() {
        scope.launch {
            _history.value = emptyList()
            saveHistory(emptyList())
            Log.d(TAG, "History cleared")
        }
    }

    /**
     * Remove history older than specified duration.
     */
    fun pruneOldHistory(maxAgeMs: Long = 24 * 60 * 60 * 1000) {
        scope.launch {
            val cutoff = System.currentTimeMillis() - maxAgeMs
            val filtered = _history.value.filter { it.timestamp > cutoff }
            if (filtered.size != _history.value.size) {
                _history.value = filtered
                saveHistory(filtered)
                Log.d(TAG, "Pruned ${_history.value.size - filtered.size} old messages")
            }
        }
    }

    private fun trimHistory() {
        scope.launch {
            val current = _history.value
            if (current.size > maxMessages) {
                val trimmed = current.takeLast(maxMessages)
                _history.value = trimmed
                saveHistory(trimmed)
            }
        }
    }

    private suspend fun trimAndSave(messages: MutableList<HistoryMessage>) {
        // Keep only the most recent messages
        while (messages.size > maxMessages) {
            messages.removeAt(0)
        }
        _history.value = messages.toList()
        saveHistory(messages)
    }

    private fun loadHistory() {
        scope.launch {
            try {
                val prefs = context.dataStore.data.first()
                val historyJson = prefs[historyKey]
                if (!historyJson.isNullOrBlank()) {
                    _history.value = json.decodeFromString<List<HistoryMessage>>(historyJson)
                    Log.d(TAG, "Loaded ${_history.value.size} history messages")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading history", e)
                _history.value = emptyList()
            }
        }
    }

    private suspend fun saveHistory(messages: List<HistoryMessage>) {
        try {
            context.dataStore.edit { prefs ->
                prefs[historyKey] = json.encodeToString(messages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving history", e)
        }
    }

    companion object {
        private const val TAG = "DialogHistoryManager"
    }
}
