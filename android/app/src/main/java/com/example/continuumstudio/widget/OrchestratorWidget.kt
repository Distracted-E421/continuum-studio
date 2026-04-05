package com.example.continuumstudio.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.example.continuumstudio.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OrchestratorWidget : GlanceAppWidget() {
    
    override val stateDefinition: GlanceStateDefinition<*> = PreferencesGlanceStateDefinition
    
    companion object {
        val KEY_MODE = stringPreferencesKey("orchestrator_mode")
        val KEY_MODE_EMOJI = stringPreferencesKey("orchestrator_mode_emoji")
        val KEY_PENDING_DIALOGS = intPreferencesKey("pending_dialogs")
        val KEY_RUNNING_AGENTS = intPreferencesKey("running_agents")
        val KEY_LAST_UPDATE = stringPreferencesKey("last_update")
        
        val PARAM_NEW_MODE = ActionParameters.Key<String>("new_mode")
    }
    
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            WidgetContent()
        }
    }
    
    @Composable
    private fun WidgetContent() {
        val prefs = currentState<Preferences>()
        val mode = prefs[KEY_MODE] ?: "user_active"
        val modeEmoji = prefs[KEY_MODE_EMOJI] ?: "🟢"
        val pendingDialogs = prefs[KEY_PENDING_DIALOGS] ?: 0
        val runningAgents = prefs[KEY_RUNNING_AGENTS] ?: 0
        
        GlanceTheme {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceTheme.colors.surface)
                    .cornerRadius(16.dp)
                    .padding(12.dp)
                    .clickable(actionStartActivity<MainActivity>()),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header
                    Text(
                        text = "Continuum",
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    
                    // Mode selector row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ModeButton("🟢", "user_active", mode == "user_active")
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        ModeButton("🟡", "user_delegate", mode == "user_delegate")
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        ModeButton("🟠", "spectator", mode == "spectator")
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        ModeButton("🔴", "autonomous", mode == "autonomous")
                    }
                    
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    
                    // Status row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Pending dialogs
                        StatusItem(
                            label = "Dialogs",
                            value = pendingDialogs.toString(),
                            highlight = pendingDialogs > 0
                        )
                        
                        Spacer(modifier = GlanceModifier.width(16.dp))
                        
                        // Running agents
                        StatusItem(
                            label = "Agents",
                            value = runningAgents.toString(),
                            highlight = false
                        )
                    }
                    
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    
                    // Refresh button
                    Box(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(GlanceTheme.colors.primaryContainer)
                            .cornerRadius(8.dp)
                            .padding(8.dp)
                            .clickable(actionRunCallback<RefreshAction>()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔄 Refresh",
                            style = TextStyle(
                                color = GlanceTheme.colors.onPrimaryContainer,
                                fontSize = 12.sp
                            )
                        )
                    }
                }
            }
        }
    }
    
    @Composable
    private fun ModeButton(emoji: String, modeValue: String, isSelected: Boolean) {
        val bgColor = if (isSelected) {
            GlanceTheme.colors.primary
        } else {
            GlanceTheme.colors.surfaceVariant
        }
        
        Box(
            modifier = GlanceModifier
                .size(36.dp)
                .background(bgColor)
                .cornerRadius(8.dp)
                .clickable(
                    actionRunCallback<ChangeModeAction>(
                        actionParametersOf(PARAM_NEW_MODE to modeValue)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emoji,
                style = TextStyle(fontSize = 16.sp)
            )
        }
    }
    
    @Composable
    private fun StatusItem(label: String, value: String, highlight: Boolean) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = TextStyle(
                    color = if (highlight) GlanceTheme.colors.error else GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = label,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp
                )
            )
        }
    }
}

class ChangeModeAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val newMode = parameters[OrchestratorWidget.PARAM_NEW_MODE] ?: return
        
        // Update local state immediately
        updateAppWidgetState(context, glanceId) { prefs ->
            prefs[OrchestratorWidget.KEY_MODE] = newMode
            prefs[OrchestratorWidget.KEY_MODE_EMOJI] = when (newMode) {
                "user_active" -> "🟢"
                "user_delegate" -> "🟡"
                "spectator" -> "🟠"
                "autonomous" -> "🔴"
                else -> "🟢"
            }
        }
        OrchestratorWidget().update(context, glanceId)
        
        // Make API call to update server
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                
                val body = """{"mode":"$newMode"}""".toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://100.102.101.72:8082/api/orchestrator/mode")
                    .post(body)
                    .build()
                
                client.newCall(request).execute()
            } catch (e: Exception) {
                // Silently fail - user can refresh
            }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                
                // Fetch orchestrator mode
                val modeRequest = Request.Builder()
                    .url("http://100.102.101.72:8082/api/orchestrator/mode")
                    .get()
                    .build()
                
                val modeResponse = client.newCall(modeRequest).execute()
                if (modeResponse.isSuccessful) {
                    val body = modeResponse.body?.string() ?: "{}"
                    val modeRegex = """"mode":\s*"([^"]+)"""".toRegex()
                    val modeMatch = modeRegex.find(body)
                    val mode = modeMatch?.groupValues?.get(1) ?: "user_active"
                    
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[OrchestratorWidget.KEY_MODE] = mode
                        prefs[OrchestratorWidget.KEY_MODE_EMOJI] = when (mode) {
                            "user_active" -> "🟢"
                            "user_delegate" -> "🟡"
                            "spectator" -> "🟠"
                            "autonomous" -> "🔴"
                            else -> "🟢"
                        }
                    }
                }
                
                // Fetch pending dialogs
                val dialogsRequest = Request.Builder()
                    .url("http://100.102.101.72:8082/api/agent-dialogs")
                    .get()
                    .build()
                
                val dialogsResponse = client.newCall(dialogsRequest).execute()
                if (dialogsResponse.isSuccessful) {
                    val body = dialogsResponse.body?.string() ?: "[]"
                    // Simple count: look for "id" occurrences
                    val count = """"id"""".toRegex().findAll(body).count()
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[OrchestratorWidget.KEY_PENDING_DIALOGS] = count
                    }
                }
                
                // Fetch running agents
                val agentsRequest = Request.Builder()
                    .url("http://100.102.101.72:4001/api/cli-agents")
                    .get()
                    .build()
                
                val agentsResponse = client.newCall(agentsRequest).execute()
                if (agentsResponse.isSuccessful) {
                    val body = agentsResponse.body?.string() ?: "[]"
                    // Count agents with status "running"
                    val count = """"status":\s*"running"""".toRegex().findAll(body).count()
                    updateAppWidgetState(context, glanceId) { prefs ->
                        prefs[OrchestratorWidget.KEY_RUNNING_AGENTS] = count
                    }
                }
                
            } catch (e: Exception) {
                // Failed to refresh
            }
        }
        
        OrchestratorWidget().update(context, glanceId)
    }
}

class OrchestratorWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = OrchestratorWidget()
    
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // Start periodic updates when first widget is added
        WidgetUpdateWorker.schedule(context)
    }
    
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Stop updates when last widget is removed
        WidgetUpdateWorker.cancel(context)
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: android.appwidget.AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Trigger an immediate update
        WidgetUpdateWorker.updateNow(context)
    }
}
