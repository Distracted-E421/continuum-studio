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
        const val SERVICE_NOTIFICATION_ID = 1
        const val DIALOG_NOTIFICATION_ID = 2
        
        const val ACTION_START = "com.example.continuumstudio.START_SERVICE"
        const val ACTION_STOP = "com.example.continuumstudio.STOP_SERVICE"
        const val ACTION_NEW_DIALOG = "com.example.continuumstudio.NEW_DIALOG"
        
        const val EXTRA_DIALOG_ID = "dialog_id"
        const val EXTRA_DIALOG_TITLE = "dialog_title"
        const val EXTRA_DIALOG_PROMPT = "dialog_prompt"
        
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
            prompt: String
        ) {
            val intent = Intent(context, DialogNotificationService::class.java).apply {
                action = ACTION_NEW_DIALOG
                putExtra(EXTRA_DIALOG_ID, dialogId)
                putExtra(EXTRA_DIALOG_TITLE, title)
                putExtra(EXTRA_DIALOG_PROMPT, prompt)
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
                showDialogNotification(dialogId, title, prompt)
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming AI dialog requests"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
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
    
    private fun showDialogNotification(dialogId: String, title: String, prompt: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            dialogId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_DIALOG_ID, dialogId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🤖 $title")
            .setContentText(prompt.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        
        notificationManager.notify(DIALOG_NOTIFICATION_ID, notification)
    }
}

