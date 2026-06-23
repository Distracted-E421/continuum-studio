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
import com.example.continuumstudio.MainActivity
import com.example.continuumstudio.ui.cognix.Nudge
import com.example.continuumstudio.ui.cognix.NudgePriority
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Foreground service for Cognix nudge delivery.
 * 
 * Provides real-time nudge delivery via:
 * 1. WebSocket connection to Cognix backend (primary)
 * 2. Polling fallback when WebSocket unavailable
 * 
 * Shows notifications for nudges even when app is backgrounded.
 */
class CognixPushService : Service() {
    
    companion object {
        const val CHANNEL_ID = "cognix_nudges"
        const val CHANNEL_NAME = "Cognix Nudges"
        const val CHANNEL_CRITICAL_ID = "cognix_critical"
        const val CHANNEL_CRITICAL_NAME = "Critical Nudges"
        const val SERVICE_NOTIFICATION_ID = 100
        const val NUDGE_NOTIFICATION_ID = 101
        
        const val ACTION_START = "com.example.continuumstudio.cognix.START_SERVICE"
        const val ACTION_STOP = "com.example.continuumstudio.cognix.STOP_SERVICE"
        const val ACTION_SHOW_NUDGE = "com.example.continuumstudio.cognix.SHOW_NUDGE"
        const val ACTION_DISMISS_NUDGE = "com.example.continuumstudio.cognix.DISMISS_NUDGE"
        const val ACTION_SNOOZE_NUDGE = "com.example.continuumstudio.cognix.SNOOZE_NUDGE"
        
        const val EXTRA_NUDGE_ID = "nudge_id"
        const val EXTRA_NUDGE_TITLE = "nudge_title"
        const val EXTRA_NUDGE_MESSAGE = "nudge_message"
        const val EXTRA_NUDGE_PRIORITY = "nudge_priority"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_SNOOZE_MINUTES = "snooze_minutes"
        
        private const val TAG = "CognixPushService"
        private const val WS_RECONNECT_DELAY_MS = 5000L
        private const val POLL_INTERVAL_MS = 30000L
        
        fun startService(context: Context, serverUrl: String, deviceId: String) {
            val intent = Intent(context, CognixPushService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SERVER_URL, serverUrl)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            context.startService(Intent(context, CognixPushService::class.java).apply {
                action = ACTION_STOP
            })
        }
        
        fun showNudge(
            context: Context,
            nudgeId: String,
            title: String,
            message: String,
            priority: String = "normal"
        ) {
            context.startService(Intent(context, CognixPushService::class.java).apply {
                action = ACTION_SHOW_NUDGE
                putExtra(EXTRA_NUDGE_ID, nudgeId)
                putExtra(EXTRA_NUDGE_TITLE, title)
                putExtra(EXTRA_NUDGE_MESSAGE, message)
                putExtra(EXTRA_NUDGE_PRIORITY, priority)
            })
        }
    }
    
    private lateinit var notificationManager: NotificationManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private var webSocket: WebSocket? = null
    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var isWebSocketConnected = false
    private var pollingJob: Job? = null
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(1000, "Service destroyed")
        pollingJob?.cancel()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return START_NOT_STICKY
                deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return START_NOT_STICKY
                
                startForeground(SERVICE_NOTIFICATION_ID, createServiceNotification())
                connectWebSocket()
            }
            ACTION_STOP -> {
                webSocket?.close(1000, "User stopped")
                pollingJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_SHOW_NUDGE -> {
                val nudgeId = intent.getStringExtra(EXTRA_NUDGE_ID) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_NUDGE_TITLE) ?: "Cognix"
                val message = intent.getStringExtra(EXTRA_NUDGE_MESSAGE) ?: ""
                val priority = intent.getStringExtra(EXTRA_NUDGE_PRIORITY) ?: "normal"
                showNudgeNotification(nudgeId, title, message, priority)
            }
            ACTION_DISMISS_NUDGE -> {
                val nudgeId = intent.getStringExtra(EXTRA_NUDGE_ID) ?: return START_STICKY
                dismissNudgeOnServer(nudgeId)
                notificationManager.cancel(NUDGE_NOTIFICATION_ID)
            }
            ACTION_SNOOZE_NUDGE -> {
                val nudgeId = intent.getStringExtra(EXTRA_NUDGE_ID) ?: return START_STICKY
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                snoozeNudgeOnServer(nudgeId, minutes)
                notificationManager.cancel(NUDGE_NOTIFICATION_ID)
            }
        }
        return START_STICKY
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val normalChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Proactive nudges and reminders from Cognix"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(normalChannel)
            
            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL_ID,
                CHANNEL_CRITICAL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical nudges requiring immediate attention"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }
            notificationManager.createNotificationChannel(criticalChannel)
        }
    }
    
    private fun createServiceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cognix Active")
            .setContentText("Monitoring for nudges...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun connectWebSocket() {
        if (serverUrl.isEmpty()) return
        
        val wsUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        
        val request = Request.Builder()
            .url("$wsUrl/ws/nudges?device_id=$deviceId")
            .build()
        
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                isWebSocketConnected = true
                pollingJob?.cancel()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isWebSocketConnected = false
                scheduleReconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failed: ${t.message}")
                isWebSocketConnected = false
                startPollingFallback()
            }
        })
    }
    
    private fun handleWebSocketMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.contentOrNull
            
            when (type) {
                "nudge" -> {
                    val nudge = jsonElement.jsonObject
                    val id = nudge["id"]?.jsonPrimitive?.contentOrNull ?: return
                    val title = nudge["title"]?.jsonPrimitive?.contentOrNull ?: "Cognix"
                    val message = nudge["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    val priority = nudge["priority"]?.jsonPrimitive?.contentOrNull ?: "normal"
                    
                    showNudgeNotification(id, title, message, priority)
                    ackNudgeOnServer(id)
                }
                "ping" -> {
                    webSocket?.send("""{"type":"pong"}""")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WebSocket message", e)
        }
    }
    
    private fun scheduleReconnect() {
        serviceScope.launch {
            delay(WS_RECONNECT_DELAY_MS)
            if (!isWebSocketConnected) {
                connectWebSocket()
            }
        }
    }
    
    private fun startPollingFallback() {
        if (pollingJob?.isActive == true) return
        
        Log.d(TAG, "Starting polling fallback")
        pollingJob = serviceScope.launch {
            while (isActive && !isWebSocketConnected) {
                try {
                    pollForNudges()
                } catch (e: Exception) {
                    Log.w(TAG, "Poll failed: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }
    
    private suspend fun pollForNudges() {
        val request = Request.Builder()
            .url("$serverUrl/api/nudges/active")
            .get()
            .build()
        
        val response = client.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string() ?: return
            val jsonResponse = json.parseToJsonElement(body).jsonObject
            
            if (jsonResponse["success"]?.jsonPrimitive?.boolean == true) {
                val nudges = jsonResponse["nudges"]?.jsonArray ?: return
                
                nudges.firstOrNull()?.jsonObject?.let { nudge ->
                    val id = nudge["id"]?.jsonPrimitive?.contentOrNull ?: return
                    val title = nudge["type"]?.jsonPrimitive?.contentOrNull ?: "Cognix"
                    val message = nudge["message"]?.jsonPrimitive?.contentOrNull ?: ""
                    val priority = nudge["priority"]?.jsonPrimitive?.contentOrNull ?: "normal"
                    
                    showNudgeNotification(id, title, message, priority)
                }
            }
        }
    }
    
    private fun showNudgeNotification(nudgeId: String, title: String, message: String, priority: String) {
        val openIntent = PendingIntent.getActivity(
            this,
            nudgeId.hashCode(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NUDGE_ID, nudgeId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val dismissIntent = PendingIntent.getService(
            this,
            nudgeId.hashCode() + 1,
            Intent(this, CognixPushService::class.java).apply {
                action = ACTION_DISMISS_NUDGE
                putExtra(EXTRA_NUDGE_ID, nudgeId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val snoozeIntent = PendingIntent.getService(
            this,
            nudgeId.hashCode() + 2,
            Intent(this, CognixPushService::class.java).apply {
                action = ACTION_SNOOZE_NUDGE
                putExtra(EXTRA_NUDGE_ID, nudgeId)
                putExtra(EXTRA_SNOOZE_MINUTES, 15)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val channelId = if (priority.lowercase() in listOf("critical", "high")) {
            CHANNEL_CRITICAL_ID
        } else {
            CHANNEL_ID
        }
        
        val priorityEmoji = when (priority.lowercase()) {
            "critical" -> "🚨 "
            "high" -> "🔴 "
            else -> ""
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("$priorityEmoji$title")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissIntent)
            .addAction(android.R.drawable.ic_menu_recent_history, "Snooze 15m", snoozeIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        
        notificationManager.notify(NUDGE_NOTIFICATION_ID, notification)
    }
    
    private fun dismissNudgeOnServer(nudgeId: String) {
        serviceScope.launch {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/nudges/$nudgeId/dismiss")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to dismiss nudge on server", e)
            }
        }
    }
    
    private fun snoozeNudgeOnServer(nudgeId: String, minutes: Int) {
        serviceScope.launch {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/nudges/$nudgeId/snooze?minutes=$minutes")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to snooze nudge on server", e)
            }
        }
    }
    
    private fun ackNudgeOnServer(nudgeId: String) {
        serviceScope.launch {
            try {
                val body = """{"nudge_id":"$nudgeId","device_id":"$deviceId"}"""
                val request = Request.Builder()
                    .url("$serverUrl/api/push/ack")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to ack nudge on server", e)
            }
        }
    }
}
