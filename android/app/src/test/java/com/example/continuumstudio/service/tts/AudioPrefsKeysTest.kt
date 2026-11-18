package com.example.continuumstudio.service.tts

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards against the preferences split that caused a real bug.
 *
 * Audio preferences are referenced from two places: `DialogViewModel.PrefsKeys`
 * (which writes them) and `AudioPrefs.Keys` (which the notification and
 * auto-reply services read). They are deliberately duplicated rather than shared,
 * because the ViewModel depends on the audio module and importing it back would
 * be a cycle.
 *
 * Duplication is only safe if something checks it. If the key strings drift, the
 * services silently read the wrong preference - or none - and a dialog read from
 * the car uses defaults while the app uses the user's settings. That is exactly
 * what happened: `DialogNotificationService.configureTTS` existed and was never
 * called, and the service read no preferences at all.
 */
class AudioPrefsKeysTest {

    @Test
    fun `audio pref keys match the names the view model writes`() {
        // These literals come from DialogViewModel.PrefsKeys. They are compared as
        // strings because the actual PreferenceKey instances live in a different
        // module and comparing them across modules is not meaningful.
        assertEquals("tts_voice", AudioPrefs.Keys.TTS_VOICE.name)
        assertEquals("tts_speed", AudioPrefs.Keys.TTS_SPEED.name)
        assertEquals("tts_enabled", AudioPrefs.Keys.TTS_ENABLED.name)
        assertEquals("audio_wait_ms", AudioPrefs.Keys.AUDIO_WAIT_MS.name)
        assertEquals("audio_focus_policy", AudioPrefs.Keys.FOCUS_POLICY.name)
    }

    @Test
    fun `focus policy key matches the name the view model writes`() {
        // DialogViewModel.PrefsKeys.AUDIO_FOCUS_POLICY. If these drift, the
        // service path reads no policy and silently falls back to overlap while
        // the app shows the user's choice - the same class of split that made
        // the notification service ignore every audio setting.
        assertEquals("audio_focus_policy", AudioPrefs.Keys.FOCUS_POLICY.name)
    }

    @Test
    fun `snapshot defaults to overlap so nothing else is interrupted`() {
        assertEquals(FocusPolicy.OVERLAP, AudioPrefs.Snapshot().focusPolicy)
        assertEquals(FocusPolicy.OVERLAP, FocusPolicy.DEFAULT)
    }

    @Test
    fun `snapshot defaults are sane when nothing is stored`() {
        val s = AudioPrefs.Snapshot()
        // Reading must default to off: speaking a dialog the user never asked to
        // hear is worse than silence.
        assertEquals(false, s.enabled)
        assertEquals(1.0f, s.speed, 0.001f)
        assertEquals(EasyVoiceClient.DEFAULT_VOICE, s.voice)
        // Must match the player's default so app and service agree.
        assertEquals(DialogAudioPlayer.DEFAULT_WAIT_MS, s.waitMs)
    }

    @Test
    fun `default wait is generous enough for a mobile link`() {
        // Guards against a future change quietly reintroducing the old 8s cap
        // that made slow connections fall back to the on-device voice.
        assert(AudioPrefs.Snapshot().waitMs >= 20_000L) {
            "default wait should be at least 20s, was ${AudioPrefs.Snapshot().waitMs}ms"
        }
    }
}
