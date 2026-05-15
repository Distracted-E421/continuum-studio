package com.example.continuumstudio.xrcontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "ActionExecutor"

/**
 * Executes widget actions including macros, HTTP requests, and system commands.
 */
class ActionExecutor(
    private val context: Context,
    private val synapsixBaseUrl: String = "http://100.102.101.72:8082"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    interface ActionCallback {
        fun onActionStarted(action: WidgetAction)
        fun onActionCompleted(action: WidgetAction, success: Boolean, result: String?)
        fun onDialogSubmit(optionIndex: Int?, optionValue: String?)
        fun onNavigate(route: String)
        fun onLayoutModeChange(mode: String)
        fun onProfileSwitch(profileId: String)
        fun onPointerEvent(event: WidgetAction.PointerEvent)
        fun onScrollEvent(direction: ScrollDirection, amount: Float)
    }
    
    var callback: ActionCallback? = null
    
    /**
     * Execute a single action
     */
    suspend fun execute(action: WidgetAction, widgetId: String): ExecutedAction {
        callback?.onActionStarted(action)
        
        return try {
            val result = when (action) {
                is WidgetAction.ShellCommand -> executeShellCommand(action)
                is WidgetAction.DialogResponse -> executeDialogResponse(action)
                is WidgetAction.AppNavigation -> executeAppNavigation(action)
                is WidgetAction.KeyEvent -> executeKeyEvent(action)
                is WidgetAction.PointerEvent -> executePointerEvent(action)
                is WidgetAction.Scroll -> executeScroll(action)
                is WidgetAction.SetLayoutMode -> executeSetLayoutMode(action)
                is WidgetAction.ShowDialog -> executeShowDialog(action)
                is WidgetAction.SwitchProfile -> executeSwitchProfile(action)
                is WidgetAction.SystemSetting -> executeSystemSetting(action)
                is WidgetAction.Launch -> executeLaunch(action)
                is WidgetAction.Delay -> executeDelay(action)
                is WidgetAction.HttpRequest -> executeHttpRequest(action)
                is WidgetAction.Conditional -> executeConditional(action, widgetId)
            }
            
            callback?.onActionCompleted(action, result.success, result.result)
            result.copy(widgetId = widgetId)
        } catch (e: Exception) {
            Log.e(TAG, "Action execution failed", e)
            val result = ExecutedAction(action, widgetId, success = false, result = e.message)
            callback?.onActionCompleted(action, false, e.message)
            result
        }
    }
    
    /**
     * Execute a list of actions (macro) sequentially
     */
    suspend fun executeMacro(actions: List<WidgetAction>, widgetId: String): List<ExecutedAction> {
        val results = mutableListOf<ExecutedAction>()
        
        for (action in actions) {
            val result = execute(action, widgetId)
            results.add(result)
            
            // Stop on first failure unless it's a delay
            if (!result.success && action !is WidgetAction.Delay) {
                break
            }
        }
        
        return results
    }
    
    // ========================================================================
    // Action Implementations
    // ========================================================================
    
    private suspend fun executeShellCommand(action: WidgetAction.ShellCommand): ExecutedAction {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$synapsixBaseUrl/api/shell")
                    .post("""{"command": "${action.command}"}""".toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                
                ExecutedAction(
                    action = action,
                    widgetId = "",
                    success = response.isSuccessful,
                    result = body
                )
            } catch (e: Exception) {
                ExecutedAction(action, "", success = false, result = e.message)
            }
        }
    }
    
    private fun executeDialogResponse(action: WidgetAction.DialogResponse): ExecutedAction {
        callback?.onDialogSubmit(action.optionIndex, action.optionValue)
        return ExecutedAction(action, "", success = true, result = "Dialog response sent")
    }
    
    private fun executeAppNavigation(action: WidgetAction.AppNavigation): ExecutedAction {
        callback?.onNavigate(action.route)
        return ExecutedAction(action, "", success = true, result = "Navigated to ${action.route}")
    }
    
    private fun executeKeyEvent(action: WidgetAction.KeyEvent): ExecutedAction {
        // Note: Sending key events to other apps requires special permissions
        // For now, we'll signal the callback to handle it
        Log.d(TAG, "KeyEvent: ${action.keyCode} with modifiers: ${action.modifiers}")
        return ExecutedAction(action, "", success = true, result = "Key event: ${action.keyCode}")
    }
    
    private fun executePointerEvent(action: WidgetAction.PointerEvent): ExecutedAction {
        callback?.onPointerEvent(action)
        return ExecutedAction(action, "", success = true, result = "Pointer: ${action.type}")
    }
    
    private fun executeScroll(action: WidgetAction.Scroll): ExecutedAction {
        callback?.onScrollEvent(action.direction, action.amount)
        return ExecutedAction(action, "", success = true, result = "Scroll: ${action.direction}")
    }
    
    private fun executeSetLayoutMode(action: WidgetAction.SetLayoutMode): ExecutedAction {
        callback?.onLayoutModeChange(action.mode)
        return ExecutedAction(action, "", success = true, result = "Layout: ${action.mode}")
    }
    
    private suspend fun executeShowDialog(action: WidgetAction.ShowDialog): ExecutedAction {
        return withContext(Dispatchers.IO) {
            try {
                val body = buildString {
                    append("""{"dialog_type": "${action.dialogType}", "prompt": "${action.prompt}"""")
                    if (action.options.isNotEmpty()) {
                        append(""", "options": ${action.options.map { "\"$it\"" }}""")
                    }
                    append("}")
                }
                
                val request = Request.Builder()
                    .url("$synapsixBaseUrl/api/dialog/show")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                ExecutedAction(action, "", success = response.isSuccessful, result = response.body?.string())
            } catch (e: Exception) {
                ExecutedAction(action, "", success = false, result = e.message)
            }
        }
    }
    
    private fun executeSwitchProfile(action: WidgetAction.SwitchProfile): ExecutedAction {
        callback?.onProfileSwitch(action.profileId)
        return ExecutedAction(action, "", success = true, result = "Switched to ${action.profileId}")
    }
    
    private fun executeSystemSetting(action: WidgetAction.SystemSetting): ExecutedAction {
        // System settings typically require special permissions
        Log.d(TAG, "System setting: ${action.setting} = ${action.value}")
        return ExecutedAction(action, "", success = true, result = "Setting: ${action.setting}")
    }
    
    private fun executeLaunch(action: WidgetAction.Launch): ExecutedAction {
        return try {
            val intent = if (action.isUrl) {
                Intent(Intent.ACTION_VIEW, Uri.parse(action.target)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                context.packageManager.getLaunchIntentForPackage(action.target)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                } ?: throw IllegalArgumentException("Package not found: ${action.target}")
            }
            
            context.startActivity(intent)
            ExecutedAction(action, "", success = true, result = "Launched: ${action.target}")
        } catch (e: Exception) {
            ExecutedAction(action, "", success = false, result = e.message)
        }
    }
    
    private suspend fun executeDelay(action: WidgetAction.Delay): ExecutedAction {
        delay(action.milliseconds)
        return ExecutedAction(action, "", success = true, result = "Delayed ${action.milliseconds}ms")
    }
    
    private suspend fun executeHttpRequest(action: WidgetAction.HttpRequest): ExecutedAction {
        return withContext(Dispatchers.IO) {
            try {
                val url = if (action.url.startsWith("http")) {
                    action.url
                } else {
                    "$synapsixBaseUrl${action.url}"
                }
                
                val requestBuilder = Request.Builder().url(url)
                
                action.headers.forEach { (key, value) ->
                    requestBuilder.header(key, value)
                }
                
                when (action.method.uppercase()) {
                    "GET" -> requestBuilder.get()
                    "POST" -> requestBuilder.post(
                        (action.body ?: "").toRequestBody("application/json".toMediaType())
                    )
                    "PUT" -> requestBuilder.put(
                        (action.body ?: "").toRequestBody("application/json".toMediaType())
                    )
                    "DELETE" -> requestBuilder.delete()
                }
                
                val response = client.newCall(requestBuilder.build()).execute()
                ExecutedAction(
                    action, "",
                    success = response.isSuccessful,
                    result = response.body?.string()
                )
            } catch (e: Exception) {
                ExecutedAction(action, "", success = false, result = e.message)
            }
        }
    }
    
    private suspend fun executeConditional(
        action: WidgetAction.Conditional,
        widgetId: String
    ): ExecutedAction {
        // Evaluate condition (simple string evaluation for now)
        val conditionMet = evaluateCondition(action.condition)
        
        val actionsToExecute = if (conditionMet) action.thenActions else action.elseActions
        val results = executeMacro(actionsToExecute, widgetId)
        
        return ExecutedAction(
            action, widgetId,
            success = results.all { it.success },
            result = "Condition ${if (conditionMet) "met" else "not met"}, executed ${results.size} actions"
        )
    }
    
    private fun evaluateCondition(condition: String): Boolean {
        // Simple condition evaluation - expand as needed
        return when {
            condition == "true" -> true
            condition == "false" -> false
            condition.startsWith("connected:") -> {
                // TODO: Check connection state
                true
            }
            condition.startsWith("dialog:") -> {
                // TODO: Check if dialog is active
                false
            }
            else -> false
        }
    }
}
