package com.applooma.rtc

import livekit.org.webrtc.audio.JavaAudioDeviceModule

/**
 * Microphone capture source and rate. A local debug switch lets the team compare
 * capture paths on a test phone without a new build:
 *   adb shell setprop debug.applooma.micsource mic      (or "voice", the default path)
 *   adb shell setprop debug.applooma.micrate 48000
 *   adb shell setprop debug.applooma.stereoin 0|1  (also stereoout, hpf)
 * The audio profile (AppAudioProfile) sets the defaults these override.
 * Unset properties leave the profile's settings untouched.
 */
internal object MicCapture {
    internal fun prop(name: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    /** HD_VOICE test style: adb shell setprop debug.applooma.audiostyle a|b */
    fun style(): String? = prop("debug.applooma.audiostyle")

    /** Test override: adb shell setprop debug.applooma.<name> 0|1 */
    fun flag(name: String, default: Boolean): Boolean = when (prop("debug.applooma.$name")) { "0" -> false; "1" -> true; else -> default }

    /** Audio mode for the MEDIA scenario. Test: adb shell setprop debug.applooma.playmode normal */
    fun mediaMode(): Int = if (prop("debug.applooma.playmode") == "normal") android.media.AudioManager.MODE_NORMAL else android.media.AudioManager.MODE_IN_COMMUNICATION

    private fun stereoInputSupported(rate: Int): Boolean = try {
        android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT) > 0
    } catch (_: Throwable) { false }

    @Volatile private var lastPlayoutRate = 0
    @Volatile private var lastPlayoutChannels = 0

    /** Applies the audio profile's capture settings; the debug properties still override them. */
    fun apply(builder: JavaAudioDeviceModule.Builder, profile: AudioProfileSettings) {
        builder.setAudioSource(when (prop("debug.applooma.micsource")) {
            "mic" -> android.media.MediaRecorder.AudioSource.MIC
            "voice" -> android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "camcorder" -> android.media.MediaRecorder.AudioSource.CAMCORDER
            else -> profile.audioSource
        })
        (prop("debug.applooma.micrate")?.toIntOrNull()?.takeIf { it in 8000..48000 } ?: profile.inputSampleRate)?.let { builder.setInputSampleRate(it) }
        // Stereo capture only where the platform reports a valid stereo config at 48 kHz.
        builder.setUseStereoInput(flag("stereoin", profile.stereoInput && stereoInputSupported(profile.inputSampleRate ?: 48000)))
        builder.setUseStereoOutput(flag("stereoout", profile.stereoOutput))
        // Playout at 48 kHz. Left to the platform, the voice-communication output
        // path runs at 16 kHz on many phones (seen: realme RMX3941), which cuts
        // everything above 8 kHz from every voice and song the listener hears.
        val out = prop("debug.applooma.outrate")?.toIntOrNull()?.takeIf { it in 8000..48000 } ?: profile.outputSampleRate
        builder.setOutputSampleRate(out)
        // Report the rate the playout track really runs at, once per change, so
        // the diagnostics show it (a call's playout start logs it the first time).
        builder.setPlaybackSamplesReadyCallback { s ->
            if (s.sampleRate != lastPlayoutRate || s.channelCount != lastPlayoutChannels) {
                lastPlayoutRate = s.sampleRate; lastPlayoutChannels = s.channelCount
                AppEngine.debug("playout track rate=${s.sampleRate} channels=${s.channelCount} requested=$out")
            }
        }
        AppEngine.debug("mic capture source=${prop("debug.applooma.micsource") ?: "default"} rate=${prop("debug.applooma.micrate") ?: "default"} ns=${prop("debug.applooma.ns") ?: "default"} aec=${prop("debug.applooma.aec") ?: "default"} agc=${prop("debug.applooma.agc") ?: "default"} playmode=${prop("debug.applooma.playmode") ?: "default"} outrate=${prop("debug.applooma.outrate") ?: "default"}")
    }
}
