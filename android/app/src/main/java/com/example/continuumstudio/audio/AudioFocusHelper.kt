package com.example.continuumstudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

private const val TAG = "AudioFocusHelper"

/**
 * Manages audio focus for TTS playback, allowing speech to overlay
 * other audio (like Spotify/YouTube) without stopping them.
 */
class AudioFocusHelper(private val context: Context) {
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(audioAttributes)
        .setOnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> stopSpeaking()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Log.d(TAG, "Focus lost transiently")
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Log.d(TAG, "Focus lost - can duck")
                AudioManager.AUDIOFOCUS_GAIN -> Log.d(TAG, "Focus gained")
            }
        }
        .setWillPauseWhenDucked(false)
        .build()
    
    init { initializeTts() }
    
    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.US)
                    if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setAudioAttributes(audioAttributes)
                        isTtsReady = true
                    }
                }
            }
        }
        
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _isSpeaking.value = true }
            override fun onDone(utteranceId: String?) { _isSpeaking.value = false; abandonAudioFocus() }
            @Deprecated("Deprecated") override fun onError(utteranceId: String?) { _isSpeaking.value = false; abandonAudioFocus() }
            override fun onError(utteranceId: String?, errorCode: Int) { _isSpeaking.value = false; abandonAudioFocus() }
        })
    }
    
    fun speakOverlay(text: String) {
        if (!isTtsReady) return
        if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
    }
    
    fun queueSpeech(text: String) {
        if (!isTtsReady) return
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }
    
    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
        abandonAudioFocus()
    }
    
    private fun abandonAudioFocus() { audioManager.abandonAudioFocusRequest(focusRequest) }
    
    fun setSpeechRate(rate: Float) { tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f)) }
    fun setSpeechPitch(pitch: Float) { tts?.setPitch(pitch.coerceIn(0.5f, 2.0f)) }
    
    fun release() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        isTtsReady = false
    }
    
    companion object {
        fun isSoundAssistantInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo("com.samsung.android.soundassistant", 0)
            true
        } catch (e: Exception) { false }
        
        fun isSamsungDevice(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
    }
}
