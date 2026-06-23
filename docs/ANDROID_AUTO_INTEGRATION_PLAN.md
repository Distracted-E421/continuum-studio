# Android Auto Integration Plan

**Status:** Phase A Implementation Complete
**Target:** Continuum Studio Android App
**Date:** May 2026

## Implementation Status

### Phase A: MessagingStyle Notifications ✅

| Component | Status | Notes |
|-----------|--------|-------|
| `automotive_app_desc.xml` | ✅ | Declares notification capability |
| `AndroidManifest.xml` | ✅ | Meta-data + AutoReplyService |
| `DialogNotificationService.kt` | ✅ | MessagingStyle + options JSON + history |
| `AutoReplyService.kt` | ✅ | Voice parsing + HTTP API + history |
| `DialogTTSHelper.kt` | ✅ | TTS for dialog reading |
| `OfflineReplyQueue.kt` | ✅ | Offline queue + network-aware sync |
| `DialogHistoryManager.kt` | ✅ | Conversation history persistence |
| Voice Parsing | ✅ | Fuzzy matching, ordinals, Levenshtein |
| TTS Feedback | ✅ | Audio focus, markdown cleanup |
| TTS Confirmation | ✅ | Speak parsed reply before sending |
| Offline Queue | ✅ | Persistent DataStore, auto-sync |
| Conversation History | ✅ | Recent dialogs for context |

### Phase B: Cars App Library (Future)

Not started. Deferred until beta restrictions are lifted.

## Overview

This document outlines the implementation plan for adding Android Auto support to Continuum Studio, enabling safe interaction with Synapsix dialogs while driving.

## Goals

1. **Read dialogs** - Display incoming agent dialogs in the Android Auto messaging UI
2. **Voice responses** - Reply to dialogs via voice input
3. **TTS alerts** - Hear dialog content read aloud
4. **Basic triage** - Handle choice/confirm dialogs via structured responses

## Non-Goals (For Now)

- Full agent orchestration from car
- Complex tile layouts or markdown rendering
- XR integration (handled separately by main app)
- Navigation features (not a navigation app)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                     Android Auto Head Unit                       │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  MessagingStyle Notifications OR Templated Conversations │   │
│  │  - Dialog title/prompt as message                        │   │
│  │  - Choice options as numbered lines                      │   │
│  │  - Voice reply → RemoteInput                             │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ Bluetooth/USB
                              │
┌─────────────────────────────────────────────────────────────────┐
│                    Continuum Studio Android                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ Dialog       │──│ AutoReply    │──│ DialogNotification  │  │
│  │ WebSocket    │  │ Service      │  │ Service (MessStyle) │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│         │                 │                     │               │
│         ▼                 ▼                     ▼               │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │                   DialogViewModel                         │  │
│  │  - speakDialog() TTS (existing)                          │  │
│  │  - respondToDialog() HTTP (existing)                     │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ HTTP / WebSocket (Tailscale)
                              │
┌─────────────────────────────────────────────────────────────────┐
│                  Synapsix Dialog Daemon                          │
│                  (Obsidian / homelab)                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Two Implementation Paths

### Path A: MessagingStyle Notifications (Recommended First)

**Pros:**

- Production-ready immediately
- Simple implementation
- Leverages existing notification infrastructure
- Can ship to Play Store without restrictions

**Cons:**

- Limited UI customization
- History limited to current AA session
- Less control over conversation flow

**Implementation:**

1. Add `automotive_app_desc.xml` with notification capability
2. Convert dialogs to `MessagingStyle` notifications
3. Add reply action with `RemoteInput`
4. Handle replies in `AutoReplyService`

### Path B: Cars App Library (Future)

**Pros:**

- Full templated UI
- ConversationItem with rich affordances
- Better conversation history
- More control over UX

**Cons:**

- **Currently in BETA** - cannot ship to Open/Production testing
- More complex implementation
- Requires Google approval for distribution
- Category fit may be questioned ("communication" app?)

**Recommendation:** Start with Path A, transition to Path B when templated messaging exits beta.

---

## Phase A: MessagingStyle Implementation

### Step 1: Manifest & Descriptor

**File:** `android/app/src/main/res/xml/automotive_app_desc.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<automotiveApp>
    <uses name="notification" />
</automotiveApp>
```

**File:** `android/app/src/main/AndroidManifest.xml` (additions)

```xml
<application>
    <!-- Existing content -->
    
    <!-- Android Auto support -->
    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />
    
    <!-- Reply handling service -->
    <service
        android:name=".service.AutoReplyService"
        android:exported="false" />
</application>
```

### Step 2: MessagingStyle Notification Builder

**File:** `android/app/src/main/java/com/example/continuumstudio/service/DialogNotificationService.kt`

Update existing `showDialogNotification()` to use `MessagingStyle`:

```kotlin
private fun buildMessagingNotification(dialog: DialogDetails): Notification {
    val person = Person.Builder()
        .setName(dialog.agentId ?: "Agent")
        .setKey(dialog.id)
        .build()
    
    val messagingStyle = NotificationCompat.MessagingStyle(person)
        .setConversationTitle(dialog.title.ifBlank { "Synapsix Dialog" })
    
    // Add dialog prompt as message
    messagingStyle.addMessage(
        dialog.prompt,
        System.currentTimeMillis(),
        person
    )
    
    // For choice dialogs, add options as numbered list
    if (dialog.dialogType.type == "choice") {
        dialog.dialogType.options?.let { options ->
            val optionsText = options.mapIndexed { i, opt -> 
                "${i + 1}. ${opt.label}" 
            }.joinToString("\n")
            messagingStyle.addMessage(
                "Options:\n$optionsText\n\nReply with option number or text.",
                System.currentTimeMillis(),
                person
            )
        }
    }
    
    // Reply action with RemoteInput
    val replyLabel = "Reply"
    val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
        .setLabel(replyLabel)
        .build()
    
    val replyIntent = Intent(this, AutoReplyService::class.java).apply {
        action = ACTION_REPLY
        putExtra(EXTRA_DIALOG_ID, dialog.id)
        putExtra(EXTRA_DIALOG_TYPE, dialog.dialogType.type)
        putExtra(EXTRA_OPTIONS_JSON, dialog.dialogType.options?.let { 
            Gson().toJson(it) 
        })
    }
    
    val replyPendingIntent = PendingIntent.getService(
        this,
        dialog.id.hashCode(),
        replyIntent,
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    
    val replyAction = NotificationCompat.Action.Builder(
        R.drawable.ic_reply,
        replyLabel,
        replyPendingIntent
    )
        .addRemoteInput(remoteInput)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .setShowsUserInterface(false)
        .build()
    
    // Mark as read action
    val markReadIntent = Intent(this, AutoReplyService::class.java).apply {
        action = ACTION_MARK_READ
        putExtra(EXTRA_DIALOG_ID, dialog.id)
    }
    val markReadPendingIntent = PendingIntent.getService(
        this,
        dialog.id.hashCode() + 1,
        markReadIntent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val markReadAction = NotificationCompat.Action.Builder(
        R.drawable.ic_check,
        "Mark Read",
        markReadPendingIntent
    )
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .setShowsUserInterface(false)
        .build()
    
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setStyle(messagingStyle)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .addAction(replyAction)
        .addAction(markReadAction)
        .setAutoCancel(true)
        .build()
}

companion object {
    const val KEY_TEXT_REPLY = "key_text_reply"
    const val ACTION_REPLY = "com.example.continuumstudio.ACTION_REPLY"
    const val ACTION_MARK_READ = "com.example.continuumstudio.ACTION_MARK_READ"
    const val EXTRA_DIALOG_ID = "dialog_id"
    const val EXTRA_DIALOG_TYPE = "dialog_type"
    const val EXTRA_OPTIONS_JSON = "options_json"
}
```

### Step 3: Reply Service

**File:** `android/app/src/main/java/com/example/continuumstudio/service/AutoReplyService.kt`

```kotlin
@AndroidEntryPoint
class AutoReplyService : Service() {
    
    @Inject
    lateinit var dialogRepository: DialogRepository
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            DialogNotificationService.ACTION_REPLY -> handleReply(intent)
            DialogNotificationService.ACTION_MARK_READ -> handleMarkRead(intent)
        }
        return START_NOT_STICKY
    }
    
    private fun handleReply(intent: Intent) {
        val dialogId = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_ID) 
            ?: return
        val dialogType = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_TYPE)
        val optionsJson = intent.getStringExtra(DialogNotificationService.EXTRA_OPTIONS_JSON)
        
        val results = RemoteInput.getResultsFromIntent(intent)
        val replyText = results?.getCharSequence(
            DialogNotificationService.KEY_TEXT_REPLY
        )?.toString()
        
        if (replyText.isNullOrBlank()) return
        
        // Parse the reply based on dialog type
        val response = parseResponse(dialogType, optionsJson, replyText)
        
        // Send to Synapsix
        CoroutineScope(Dispatchers.IO).launch {
            try {
                dialogRepository.respondToDialog(dialogId, response)
                
                // Update notification to show reply was sent
                updateNotificationWithReply(dialogId, replyText)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reply", e)
            }
        }
    }
    
    private fun parseResponse(
        dialogType: String?,
        optionsJson: String?,
        replyText: String
    ): DialogResponse {
        return when (dialogType) {
            "choice" -> {
                val options = optionsJson?.let { 
                    Gson().fromJson(it, Array<DialogOption>::class.java).toList() 
                } ?: emptyList()
                
                // Try to match by number (e.g., "1", "2")
                val numMatch = replyText.trim().toIntOrNull()
                if (numMatch != null && numMatch in 1..options.size) {
                    DialogResponse.Choice(options[numMatch - 1].value)
                } else {
                    // Try fuzzy match against option labels
                    val match = options.find { 
                        it.label.contains(replyText, ignoreCase = true) ||
                        replyText.contains(it.label, ignoreCase = true)
                    }
                    DialogResponse.Choice(match?.value ?: replyText)
                }
            }
            "confirmation", "confirm" -> {
                val positive = listOf("yes", "ok", "confirm", "accept", "1", "true")
                val isPositive = positive.any { 
                    replyText.trim().equals(it, ignoreCase = true) 
                }
                DialogResponse.Confirm(isPositive)
            }
            "text" -> DialogResponse.Text(replyText)
            else -> DialogResponse.Text(replyText)
        }
    }
    
    private fun handleMarkRead(intent: Intent) {
        val dialogId = intent.getStringExtra(DialogNotificationService.EXTRA_DIALOG_ID)
            ?: return
        
        // Cancel the notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.cancel(dialogId.hashCode())
    }
    
    private fun updateNotificationWithReply(dialogId: String, reply: String) {
        // Show brief confirmation notification
        val notification = NotificationCompat.Builder(this, 
            DialogNotificationService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check)
            .setContentTitle("Reply sent")
            .setContentText(reply.take(50))
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        
        NotificationManagerCompat.from(this)
            .notify(dialogId.hashCode(), notification)
    }
    
    companion object {
        private const val TAG = "AutoReplyService"
    }
}

sealed class DialogResponse {
    data class Choice(val value: String) : DialogResponse()
    data class Confirm(val confirmed: Boolean) : DialogResponse()
    data class Text(val text: String) : DialogResponse()
}
```

### Step 4: Testing on Android Auto

1. **Desktop Head Unit (DHU):** Use Google's Desktop Head Unit for testing

   ```bash
   $ANDROID_HOME/extras/google/auto/desktop-head-unit
   ```

2. **Test Scenarios:**
   - Receive choice dialog → See MessagingStyle notification
   - Reply with number "1" → Verify correct option selected
   - Reply with option text → Verify fuzzy match works
   - Confirm dialog → Reply "yes"/"no"
   - Voice input → Verify transcription handled correctly

---

## Phase B: Cars App Library (Future)

**Prerequisites:**

- Google opens templated messaging to production distribution
- OR: Internal-only deployment acceptable

### Dependencies

**File:** `android/app/build.gradle.kts`

```kotlin
dependencies {
    // ... existing
    
    // Android Auto - Car App Library
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
}
```

### Service Declaration

```xml
<service
    android:name=".auto.ContinuumCarAppService"
    android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.MESSAGING" />
    </intent-filter>
</service>
```

### Implementation Skeleton

```kotlin
class ContinuumCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }
    
    override fun onCreateSession(): Session {
        return ContinuumSession()
    }
}

class ContinuumSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return ConversationListScreen(carContext)
    }
}

class ConversationListScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        // Build list of active dialogs as ConversationItem
        // ...
    }
}
```

---

## Dialog Type Mapping

| Synapsix Dialog Type | Android Auto Representation |
|---------------------|----------------------------|
| `choice` | MessagingStyle message with numbered options; parse reply by number or fuzzy text |
| `confirmation` | MessagingStyle message asking yes/no; parse "yes", "ok", "no", "cancel" |
| `text` | MessagingStyle message with prompt; pass through raw reply text |
| `slider` | Not suitable for driving; defer to phone or use approximate text ("half", "maximum") |

---

## Voice Reply Parsing Examples

| User Says | Dialog Type | Parsed Response |
|-----------|-------------|-----------------|
| "One" / "1" | choice (3 options) | Select option at index 0 |
| "The second one" | choice | Fuzzy match → option 2 |
| "Yes" / "OK" | confirmation | confirmed = true |
| "No" / "Cancel" | confirmation | confirmed = false |
| "Maximum" | slider (0-100) | value = 100 (approximate) |
| "Deploy to staging" | text | text = "Deploy to staging" |

---

## Testing Checklist

### Development Testing

- [ ] DHU messaging notification appears
- [ ] Voice reply triggers RemoteInput
- [ ] Number parsing works (1, 2, 3...)
- [ ] Fuzzy label matching works
- [ ] Confirmation yes/no parsing
- [ ] TTS readout of dialog content
- [ ] Notification dismissal after response

### Quality Gates (Pre-Release)

- [ ] [Car app quality checklist](https://developer.android.com/docs/quality-guidelines/car-app-quality) passed
- [ ] No more than 5 steps per task flow
- [ ] Dialogs flatten gracefully (no crashes on complex content)
- [ ] Connectivity loss handled (queue responses, retry)
- [ ] No sensitive data in notifications (check privacy)

---

## Timeline Estimate

| Phase | Duration | Notes |
|-------|----------|-------|
| Phase A.1: Manifest + descriptor | 1 hour | Boilerplate |
| Phase A.2: MessagingStyle builder | 2-3 hours | Mostly code in this doc |
| Phase A.3: Reply service | 2-3 hours | Response parsing logic |
| Phase A.4: Integration testing | 4-6 hours | DHU + real device |
| Phase A.5: QA + polish | 4-8 hours | Edge cases, errors |
| **Phase A Total** | **1-2 days** | |
| Phase B: Car App Library | TBD | Depends on Google's beta timeline |

---

## References

- [Android for Cars Overview](https://developer.android.com/training/cars/apps)
- [Messaging Notifications for Auto](https://developer.android.com/training/cars/messaging)
- [Templated Messaging (Beta)](https://developer.android.com/training/cars/communication/templated-messaging)
- [Car App Quality Guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality)
- [Design for Cars - Other Apps](https://developers.google.com/cars/design/create-apps/app-types/other)

---

## Open Questions

1. **App category for Play Store**: Is "Tools" or "Communication" more appropriate for Continuum Studio with Auto support?
2. **Slider dialogs**: Should we attempt approximate parsing ("half", "low", "high") or always defer to phone?
3. **Multi-agent context**: How to represent multiple agents in conversation threads? One thread per agent, or unified inbox?
4. **XR integration**: Should Auto mode and XR mode be mutually exclusive, or can RayNeo 3 work while Auto is connected?
