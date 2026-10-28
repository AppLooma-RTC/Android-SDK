package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProfileTest {
    private val audio = AppAudioOptions()

    @Test fun defaultProfileIsHdVoice() = assertEquals(AppAudioProfile.HD_VOICE, AppAudioOptions().profile)

    @Test fun hdVoiceDefault() {
        val s = AudioProfileSettings.resolve(AppAudioProfile.HD_VOICE, audio, AppAudioScenario.CALL)
        assertEquals(AudioProfileSettings.VOICE_COMMUNICATION, s.audioSource)
        assertTrue(s.echoCancellation && s.hardwareAec && s.hardwareNs && s.noiseSuppression && s.autoGainControl)
        assertEquals(128_000, s.bitrate); assertFalse(s.normalMode)
        // A CALL-scenario engine starts on the earpiece, as before 0.4.21; MEDIA on the loudspeaker.
        assertTrue(s.earpieceFirst)
        assertFalse(AudioProfileSettings.resolve(AppAudioProfile.HD_VOICE, audio, AppAudioScenario.MEDIA).earpieceFirst)
        assertEquals("audio profile=HD_VOICE source=voice in=48000 out=48000 aec=on+hw ns=on hwns=on agc=on mode=call bitrate=128k stereo=false", s.logLine())
    }

    @Test fun hdVoiceStyleB() {
        val s = AudioProfileSettings.resolve(AppAudioProfile.HD_VOICE, audio, AppAudioScenario.CALL, "b")
        assertEquals(AudioProfileSettings.MIC, s.audioSource)
        assertTrue(s.echoCancellation); assertFalse(s.hardwareAec || s.hardwareNs || s.noiseSuppression || s.autoGainControl)
        assertTrue(s.normalMode)
    }

    @Test fun speech() {
        val call = AudioProfileSettings.resolve(AppAudioProfile.SPEECH, audio, AppAudioScenario.CALL)
        assertEquals(AudioProfileSettings.VOICE_COMMUNICATION, call.audioSource)
        assertTrue(call.echoCancellation && call.noiseSuppression && call.autoGainControl)
        assertEquals(32_000, call.bitrate); assertTrue(call.red); assertFalse(call.stereoInput)
        assertTrue(call.earpieceFirst)
        assertFalse(AudioProfileSettings.resolve(AppAudioProfile.SPEECH, audio, AppAudioScenario.MEDIA).earpieceFirst)
        // The app's own switches still apply.
        val off = AudioProfileSettings.resolve(AppAudioProfile.SPEECH, AppAudioOptions(noiseSuppressionMode = AppNoiseSuppression.OFF, autoGainControl = false), AppAudioScenario.CALL)
        assertFalse(off.noiseSuppression); assertFalse(off.autoGainControl)
    }

    @Test fun music() {
        val s = AudioProfileSettings.resolve(AppAudioProfile.MUSIC, audio, AppAudioScenario.MEDIA)
        assertEquals(AudioProfileSettings.MIC, s.audioSource)
        assertTrue(s.stereoInput && s.stereoOutput)
        assertFalse(s.echoCancellation || s.noiseSuppression || s.autoGainControl || s.highPassFilter)
        assertEquals(128_000, s.bitrate); assertTrue(s.normalMode)
    }

    @Test fun hdVoiceRespectsAecSwitch() =
        assertFalse(AudioProfileSettings.resolve(AppAudioProfile.HD_VOICE, AppAudioOptions(echoCancellation = false), AppAudioScenario.MEDIA).echoCancellation)
}
