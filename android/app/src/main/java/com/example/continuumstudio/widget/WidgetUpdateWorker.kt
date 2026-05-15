package com.example.continuumstudio.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically updates the widget state.
 * Runs every 15 minutes (minimum allowed by WorkManager).
 */
class WidgetUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "WidgetUpdateWorker"
        private const val WORK_NAME = "widget_periodic_update"
        
        /**
         * Schedule periodic widget updates (every 15 minutes).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetUpdateWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.d(TAG, "Scheduled periodic widget updates")
        }
        
        /**
         * Trigger an immediate one-time update.
         */
        fun updateNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
            Log.d(TAG, "Triggered immediate widget update")
        }
        
        /**
         * Cancel scheduled updates.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic widget updates")
        }
    }
    
    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting widget update")
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                
                // Fetch orchestrator mode
                val mode = fetchMode(client)
                val pendingDialogs = fetchPendingDialogs(client)
                val runningAgents = fetchRunningAgents(client)
                
                // Update all widget instances
                val manager = GlanceAppWidgetManager(applicationContext)
                val glanceIds = manager.getGlanceIds(OrchestratorWidget::class.java)
                
                for (id in glanceIds) {
                    updateAppWidgetState(applicationContext, id) { prefs ->
                        prefs[OrchestratorWidget.KEY_MODE] = mode
                        prefs[OrchestratorWidget.KEY_MODE_EMOJI] = modeToEmoji(mode)
                        prefs[OrchestratorWidget.KEY_PENDING_DIALOGS] = pendingDialogs
                        prefs[OrchestratorWidget.KEY_RUNNING_AGENTS] = runningAgents
                        prefs[OrchestratorWidget.KEY_LAST_UPDATE] = 
                            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date())
                    }
                    OrchestratorWidget().update(applicationContext, id)
                }
                
                Log.d(TAG, "Widget updated: mode=$mode, dialogs=$pendingDialogs, agents=$runningAgents")
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Widget update failed", e)
                Result.retry()
            }
        }
    }
    
    private fun fetchMode(client: OkHttpClient): String {
        return try {
            val request = Request.Builder()
                .url("http://100.109.236.61:8080/api/orchestrator/mode")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val regex = """"mode":\s*"([^"]+)"""".toRegex()
                regex.find(body)?.groupValues?.get(1) ?: "user_active"
            } else {
                "user_active"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch mode", e)
            "user_active"
        }
    }
    
    private fun fetchPendingDialogs(client: OkHttpClient): Int {
        return try {
            val request = Request.Builder()
                .url("http://100.109.236.61:8080/api/agent-dialogs")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                """"id"""".toRegex().findAll(body).count()
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch dialogs", e)
            0
        }
    }
    
    private fun fetchRunningAgents(client: OkHttpClient): Int {
        return try {
            val request = Request.Builder()
                .url("http://100.109.236.61:4001/api/cli-agents")
                .get()
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: "[]"
                """"status":\s*"running"""".toRegex().findAll(body).count()
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch agents", e)
            0
        }
    }
    
    private fun modeToEmoji(mode: String): String {
        return when (mode) {
            "user_active" -> "🟢"
            "user_delegate" -> "🟡"
            "spectator" -> "🟠"
            "autonomous" -> "🔴"
            else -> "🟢"
        }
    }
}
