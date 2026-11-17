package com.example.continuumstudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.example.continuumstudio.MainActivity
import com.example.continuumstudio.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Foreground service to maintain WebSocket connection and show notifications
 * for incoming dialogs even when the app is in the background.
 */
class DialogNotificationService : Service() {
    
    companion object {
        const val CHANNEL_ID = "dialog_notifications"
        const val CHANNEL_NAME = "Dialog Notifications"
        const val CHANNEL_HIGH_PRIORITY_ID = "high_priority_dialogs"
        const val CHANNEL_HIGH_PRIORITY_NAME = "High Priority Dialogs"
        const val CHANNEL_AGENT_STATUS_ID = "agent_status"
        const val CHANNEL_AGENT_STATUS_NAME = "Agent Status"
        const val CONNECTION_CHANNEL_ID = "connection_status"
        const val CONNECTION_CHANNEL_NAME = "Connection Status"
        const val SERVICE_NOTIFICATION_ID = 1
        const val DIALOG_NOTIFICATION_ID = 2
        const val CONNECTION_NOTIFICATION_ID = 3
        const val AGENT_STATUS_NOTIFICATION_ID = 4
        
        const val ACTION_START = "com.example.continuumstudio.START_SERVICE"
        const val ACTION_STOP = "com.example.continuumstudio.STOP_SERVICE"
        const val ACTION_NEW_DIALOG = "com.example.continuumstudio.NEW_DIALOG"
        const val ACTION_DISMISS_DIALOG = "com.example.continuumstudio.DISMISS_DIALOG"
        const val ACTION_CONNECTION_LOST = "com.example.continuumstudio.CONNECTION_LOST"
        const val ACTION_CONNECTION_RESTORED = "com.example.continuumstudio.CONNECTION_RESTORED"
        const val ACTION_AGENT_STATUS = "com.example.continuumstudio.AGENT_STATUS"
        const val ACTION_AUTO_HANDLED = "com.example.continuumstudio.AUTO_HANDLED"
        
        const val EXTRA_DIALOG_ID = "dialog_id"
        const val EXTRA_DIALOG_TITLE = "dialog_title"
        const val EXTRA_DIALOG_PROMPT = "dialog_prompt"
        const val EXTRA_DIALOG_TYPE = "dialog_type"
        const val EXTRA_DIALOG_PRIORITY = "dialog_priority"
        const val EXTRA_AGENT_ID = "agent_id"
        const val EXTRA_AGENT_STATUS = "agent_status"
        const val EXTRA_AUTO_RESPONSE = "auto_response"
        const val EXTRA_AUTO_REASONING = "auto_reasoning"
        
        // Android Auto reply handling
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.example.continuumstudio.ACTION_REPLY"
        const val ACTION_MARK_READ = "com.example.continuumstudio.ACTION_MARK_READ"
        const val EXTRA_OPTIONS_JSON = "options_json"
        
        // TTS configuration
        const val ACTION_CONFIGURE_TTS = "com.example.continuumstudio.CONFIGURE_TTS"
        const val EXTRA_TTS_ENABLED = "tts_enabled"
        const val EXTRA_TTS_SPEECH_RATE = "tts_speech_rate"
        const val EXTRA_TTS_READ_OPTIONS = "tts_read_options"
        
        fun startService(context: Context) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        fun notifyNewDialog(
            context: Context,
            dialogId: String,
            title: String,
            prompt: String,
            dialogType: String = "choice",
            priority: String = "normal",
            optionsJson: String? = null
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_NEW_DIALOG
                putExtra(EXTRA_DIALOG_ID, dialogId)
                putExtra(EXTRA_DIALOG_TITLE, title)
                putExtra(EXTRA_DIALOG_PROMPT, prompt)
                putExtra(EXTRA_DIALOG_TYPE, dialogType)
                putExtra(EXTRA_DIALOG_PRIORITY, priority)
                optionsJson?.let { putExtra(EXTRA_OPTIONS_JSON, it) }
            }
            context.startService(intent)
        }
        
        fun notifyAgentStatus(
            context: Context,
            agentId: String,
            status: String
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_AGENT_STATUS
                putExtra(EXTRA_AGENT_ID, agentId)
                putExtra(EXTRA_AGENT_STATUS, status)
            }
            context.startService(intent)
        }
        
        fun notifyAutoHandled(
            context: Context,
            dialogId: String,
            title: String,
            response: String,
            reasoning: String
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_AUTO_HANDLED
                putExtra(EXTRA_DIALOG_ID, dialogId)
                putExtra(EXTRA_DIALOG_TITLE, title)
                putExtra(EXTRA_AUTO_RESPONSE, response)
                putExtra(EXTRA_AUTO_REASONING, reasoning)
            }
            context.startService(intent)
        }
        
        fun notifyConnectionLost(context: Context) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_CONNECTION_LOST
            }
            context.startService(intent)
        }
        
        fun notifyConnectionRestored(context: Context) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_CONNECTION_RESTORED
            }
            context.startService(intent)
        }
        
        fun dismissDialogNotification(context: Context) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_DISMISS_DIALOG
            }
            context.startService(intent)
        }
        
        /**
         * Configure TTS settings for dialog reading.
         *
         * @param context Application context
         * @param enabled Whether TTS is enabled (default true)
         * @param speechRate Speech rate multiplier (0.5 = slow, 1.0 = normal, 2.0 = fast)
         * @param readOptions Whether to read options aloud for choice dialogs
         */
        fun configureTTS(
            context: Context,
            enabled: Boolean = true,
            speechRate: Float = 1.0f,
            readOptions: Boolean = true
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_CONFIGURE_TTS
                putExtra(EXTRA_TTS_ENABLED, enabled)
                putExtra(EXTRA_TTS_SPEECH_RATE, speechRate)
                putExtra(EXTRA_TTS_READ_OPTIONS, readOptions)
            }
            context.startService(intent)
        }
    }
    
    private lateinit var notificationManager: NotificationManager
    private var ttsHelper: DialogTTSHelper? = null
    private var historyManager: DialogHistoryManager? = null
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        
        // Initialize TTS helper for Android Auto voice reading
        ttsHelper = DialogTTSHelper(this)
        
        // Initialize history manager for conversation context
        historyManager = DialogHistoryManager(this)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        ttsHelper?.shutdown()
        ttsHelper = null
        historyManager = null
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(SERVICE_NOTIFICATION_ID, createServiceNotification())
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_NEW_DIALOG -> {
                val dialogId = intent.getStringExtra(EXTRA_DIALOG_ID) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_DIALOG_TITLE) ?: "New Dialog"
                val prompt = intent.getStringExtra(EXTRA_DIALOG_PROMPT) ?: ""
                val dialogType = intent.getStringExtra(EXTRA_DIALOG_TYPE) ?: "choice"
                val priority = intent.getStringExtra(EXTRA_DIALOG_PRIORITY) ?: "normal"
                val optionsJson = intent.getStringExtra(EXTRA_OPTIONS_JSON)
                showDialogNotification(dialogId, title, prompt, dialogType, priority, optionsJson)
            }
            ACTION_DISMISS_DIALOG -> {
                notificationManager.cancel(DIALOG_NOTIFICATION_ID)
                ttsHelper?.stop()  // Stop TTS when dialog is dismissed
            }
            ACTION_CONNECTION_LOST -> {
                showConnectionLostNotification()
            }
            ACTION_CONNECTION_RESTORED -> {
                notificationManager.cancel(CONNECTION_NOTIFICATION_ID)
            }
            ACTION_AGENT_STATUS -> {
                val agentId = intent.getStringExtra(EXTRA_AGENT_ID) ?: return START_STICKY
                val status = intent.getStringExtra(EXTRA_AGENT_STATUS) ?: "unknown"
                showAgentStatusNotification(agentId, status)
            }
            ACTION_AUTO_HANDLED -> {
                val dialogId = intent.getStringExtra(EXTRA_DIALOG_ID) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_DIALOG_TITLE) ?: "Dialog"
                val response = intent.getStringExtra(EXTRA_AUTO_RESPONSE) ?: ""
                val reasoning = intent.getStringExtra(EXTRA_AUTO_REASONING) ?: ""
                showAutoHandledNotification(dialogId, title, response, reasoning)
            }
            ACTION_CONFIGURE_TTS -> {
                val enabled = intent.getBooleanExtra(EXTRA_TTS_ENABLED, true)
                val speechRate = intent.getFloatExtra(EXTRA_TTS_SPEECH_RATE, 1.0f)
                val readOptions = intent.getBooleanExtra(EXTRA_TTS_READ_OPTIONS, true)
                configureTTSHelper(enabled, speechRate, readOptions)
            }
        }
        return START_STICKY
    }
    
    private fun configureTTSHelper(enabled: Boolean, speechRate: Float, readOptions: Boolean) {
        ttsHelper?.apply {
            ttsEnabled = enabled
            this.speechRate = speechRate
            readOptionsAloud = readOptions
            Log.d("DialogNotificationSvc", "TTS configured: enabled=$enabled, rate=$speechRate, options=$readOptions")
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Normal priority channel for dialogs
            val dialogChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming AI dialog requests"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(dialogChannel)
            
            // High priority channel for critical dialogs
            val highPriorityChannel = NotificationChannel(
                CHANNEL_HIGH_PRIORITY_ID,
                CHANNEL_HIGH_PRIORITY_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical and high-priority dialog requests requiring immediate attention"
                enableVibration(true)
                enableLights(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(highPriorityChannel)
            
            // Agent status channel
            val agentChannel = NotificationChannel(
                CHANNEL_AGENT_STATUS_ID,
                CHANNEL_AGENT_STATUS_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "CLI agent status updates (completed, failed)"
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(agentChannel)
            
            // Lower priority channel for connection status
            val connectionChannel = NotificationChannel(
                CONNECTION_CHANNEL_ID,
                CONNECTION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Connection status updates"
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(connectionChannel)
        }
    }
    
    private fun createServiceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Continuum Studio")
            .setContentText("Listening for dialogs...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun showDialogNotification(dialogId: String, title: String, prompt: String, dialogType: String, priority: String, optionsJson: String? = null) {
        val openIntent = PendingIntent.getActivity(
            this,
            dialogId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DIALOG_ID, dialogId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val dismissIntent = PendingIntent.getService(
            this,
            dialogId.hashCode() + 1,
            Intent(this, DialogNotificationService::class.java).apply {
                action = ACTION_DISMISS_DIALOG
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Use high priority channel for critical/high dialogs
        val channelId = if (priority.lowercase() in listOf("critical", "high")) {
            CHANNEL_HIGH_PRIORITY_ID
        } else {
            CHANNEL_ID
        }
        
        // Add dialog to history for conversation context
        historyManager?.addAgentDialog(dialogId, title, prompt, dialogType)
        
        // Build notification with MessagingStyle for Android Auto
        val notification = buildMessagingStyleNotification(
            dialogId, title, prompt, dialogType, priority, channelId, openIntent, dismissIntent, optionsJson
        )
        
        notificationManager.notify(DIALOG_NOTIFICATION_ID, notification)
        
        // Read dialog aloud using TTS (for Android Auto hands-free use).
        // The dialog id is passed through so playback can be tied to the dialog
        // rather than to its title, which is what lets the audio bar know which
        // clip it is controlling.
        //
        // Settings are re-applied first: this service can be the only thing
        // running when a dialog arrives (app backgrounded or closed), and it used
        // to speak with hardcoded defaults - a different voice, speed and wait
        // than the app screen would use for the same dialog.
        ttsHelper?.let { helper ->
            com.example.continuumstudio.service.tts.AudioPrefs.applyTo(
                helper,
                com.example.continuumstudio.service.tts.AudioPrefs.load(this),
            )
            helper.speakDialog(title, prompt, dialogType, optionsJson, priority, dialogId)
        }
    }
    
    /**
     * Build a MessagingStyle notification for Android Auto compatibility.
     * Uses conversation-style formatting that works with Android Auto's messaging UI.
     */
    private fun buildMessagingStyleNotification(
        dialogId: String,
        title: String,
        prompt: String,
        dialogType: String,
        priority: String,
        channelId: String,
        openIntent: PendingIntent,
        dismissIntent: PendingIntent,
        optionsJson: String? = null
    ): Notification {
        // Create Person for the agent (sender of the dialog)
        val agentPerson = Person.Builder()
            .setName("Synapsix Agent")
            .setKey("synapsix_agent")
            .build()
        
        // Create Person for the user (for reply messages in history)
        val userPerson = Person.Builder()
            .setName("You")
            .setKey("user")
            .build()
        
        // Create MessagingStyle with conversation-style layout
        val messagingStyle = NotificationCompat.MessagingStyle(agentPerson)
            .setConversationTitle(title.ifBlank { "Synapsix Dialog" })
        
        // Add recent history for conversation context
        val history = historyManager?.getRecentHistory(5) ?: emptyList()
        for (historyMsg in history.dropLast(1)) {  // Exclude the current message (just added)
            val person = if (historyMsg.isFromAgent) agentPerson else userPerson
            val displayText = if (historyMsg.isFromAgent) {
                // For agent messages, show title + truncated prompt
                val prefix = historyMsg.title?.let { "[$it] " } ?: ""
                val truncatedText = historyMsg.text.take(100).let { 
                    if (historyMsg.text.length > 100) "$it..." else it 
                }
                "$prefix$truncatedText"
            } else {
                // For user replies, show as-is
                historyMsg.text
            }
            messagingStyle.addMessage(displayText, historyMsg.timestamp, person)
        }
        
        // Add the current dialog prompt as the main message
        messagingStyle.addMessage(
            prompt,
            System.currentTimeMillis(),
            agentPerson
        )
        
        // For choice dialogs, add options as a follow-up message with numbered list
        // This makes it easier to respond via voice: "Reply with 1" or "Reply with OK"
        val optionsText = when (dialogType.lowercase()) {
            "choice" -> {
                // Format options if available
                val formattedOptions = formatOptionsForVoice(optionsJson)
                if (formattedOptions.isNotEmpty()) {
                    "\n\nOptions:\n$formattedOptions\n\nSay the option number or name."
                } else {
                    "\n\nReply with your choice (number or text)."
                }
            }
            "confirm", "confirmation" -> {
                "\n\nReply with YES or NO."
            }
            "text" -> {
                "\n\nReply with your response."
            }
            else -> ""
        }
        
        if (optionsText.isNotEmpty()) {
            messagingStyle.addMessage(
                optionsText,
                System.currentTimeMillis() + 1,
                agentPerson
            )
        }
        
        // Build RemoteInput for voice replies (Android Auto)
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply")
            .build()
        
        // Reply action with RemoteInput
        val replyIntent = Intent(this, AutoReplyService::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_DIALOG_ID, dialogId)
            putExtra(EXTRA_DIALOG_TYPE, dialogType)
            putExtra(EXTRA_DIALOG_TITLE, title)
            optionsJson?.let { putExtra(EXTRA_OPTIONS_JSON, it) }
        }
        
        val replyPendingIntent = PendingIntent.getService(
            this,
            dialogId.hashCode() + 10,
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        
        // Mark as read action
        val markReadIntent = Intent(this, AutoReplyService::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_DIALOG_ID, dialogId)
        }
        
        val markReadPendingIntent = PendingIntent.getService(
            this,
            dialogId.hashCode() + 11,
            markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val markReadAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Mark Read",
            markReadPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        
        // Priority indicators for title (only in non-Auto views)
        val priorityPrefix = when (priority.lowercase()) {
            "critical" -> "🚨 "
            "high" -> "🔴 "
            else -> ""
        }
        
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setStyle(messagingStyle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(replyAction)
            .addAction(markReadAction)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open App",
                openIntent
            )
            .build()
    }
    
    private fun showAgentStatusNotification(agentId: String, status: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            agentId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val (icon, title) = when (status.lowercase()) {
            "completed" -> "✅" to "Agent Completed"
            "failed" -> "❌" to "Agent Failed"
            "timeout" -> "⏱️" to "Agent Timeout"
            else -> "🤖" to "Agent Status"
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_AGENT_STATUS_ID)
            .setContentTitle("$icon $title")
            .setContentText("Agent ${agentId.take(8)}... has ${status.lowercase()}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        notificationManager.notify(AGENT_STATUS_NOTIFICATION_ID, notification)
    }
    
    private fun showAutoHandledNotification(dialogId: String, title: String, response: String, reasoning: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            dialogId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_AGENT_STATUS_ID)
            .setContentTitle("✨ Auto-handled: $title")
            .setContentText("Response: $response")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Response: $response\n\nReasoning: $reasoning"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        notificationManager.notify(AGENT_STATUS_NOTIFICATION_ID + 1, notification)
    }
    
    private fun showConnectionLostNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this,
            CONNECTION_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL_ID)
            .setContentTitle("⚠️ Connection Lost")
            .setContentText("Tap to reconnect to the dialog server")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        
        notificationManager.notify(CONNECTION_NOTIFICATION_ID, notification)
    }

    /**
     * Format options JSON into a human-readable numbered list for voice UI.
     * Example output: "1. Continue\n2. Cancel\n3. Skip"
     */
    private fun formatOptionsForVoice(optionsJson: String?): String {
        if (optionsJson.isNullOrBlank()) return ""

        return try {
            val jsonArray = Json.parseToJsonElement(optionsJson).jsonArray
            jsonArray.mapIndexed { index, element ->
                val obj = element.jsonObject
                val label = obj["label"]?.jsonPrimitive?.content
                    ?: obj["value"]?.jsonPrimitive?.content
                    ?: return@mapIndexed null
                "${index + 1}. $label"
            }.filterNotNull().joinToString("\n")
        } catch (e: Exception) {
            Log.w("DialogNotificationSvc", "Failed to parse options for voice formatting: $optionsJson", e)
            ""
        }
    }
}

