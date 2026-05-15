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
import androidx.core.app.NotificationCompat
import com.example.continuumstudio.MainActivity
import com.example.continuumstudio.R

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
            priority: String = "normal"
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_NEW_DIALOG
                putExtra(EXTRA_DIALOG_ID, dialogId)
                putExtra(EXTRA_DIALOG_TITLE, title)
                putExtra(EXTRA_DIALOG_PROMPT, prompt)
                putExtra(EXTRA_DIALOG_TYPE, dialogType)
                putExtra(EXTRA_DIALOG_PRIORITY, priority)
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
    }
    
    private lateinit var notificationManager: NotificationManager
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
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
                showDialogNotification(dialogId, title, prompt, dialogType, priority)
            }
            ACTION_DISMISS_DIALOG -> {
                notificationManager.cancel(DIALOG_NOTIFICATION_ID)
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
        }
        return START_STICKY
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
    
    private fun showDialogNotification(dialogId: String, title: String, prompt: String, dialogType: String, priority: String) {
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
        
        // Choose icon based on dialog type
        val typeIcon = when (dialogType.lowercase()) {
            "choice" -> "📝"
            "text" -> "✏️"
            "confirm" -> "❓"
            "slider" -> "🎚️"
            else -> "🤖"
        }
        
        // Add priority indicator
        val priorityPrefix = when (priority.lowercase()) {
            "critical" -> "🚨 "
            "high" -> "🔴 "
            else -> ""
        }
        
        // Use high priority channel for critical/high dialogs
        val channelId = if (priority.lowercase() in listOf("critical", "high")) {
            CHANNEL_HIGH_PRIORITY_ID
        } else {
            CHANNEL_ID
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("$priorityPrefix$typeIcon $title")
            .setContentText(prompt.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open",
                openIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dismiss",
                dismissIntent
            )
            .build()
        
        notificationManager.notify(DIALOG_NOTIFICATION_ID, notification)
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
}

