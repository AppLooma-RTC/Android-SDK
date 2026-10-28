package com.applooma.rtc

/**
 * The capture, processing and encoding settings one [AppAudioProfile] stands
 * for, resolved against the app's [AppAudioOptions] and scenario. Pure data so
 * the mapping is unit-tested without a device.
 */
internal data class AudioProfileSettings(
    val profile: AppAudioProfile,
    /** android.media.MediaRecorder.AudioSource value. */
    val audioSource: Int,
    /** Capture rate; null leaves the platform default. */
    val inputSampleRate: Int?,
    val outputSampleRate: Int,
    val stereoInput: Boolean,
    val stereoOutput: Boolean,
    val echoCancellation: Boolean,
    /** The engine's (WebRTC) noise suppressor. Clear Voice and the gate are separate. */
    val noiseSuppression: Boolean,
    /** The engine's (WebRTC) AGC. */
    val autoGainControl: Boolean,
    val highPassFilter: Boolean,
    val bitrate: Int,
    val dtx: Boolean,
    val red: Boolean,
    /** MEDIA audio type with MODE_NORMAL (no communication mode). */
    val normalMode: Boolean,
    /** Earpiece before loudspeaker in the device order. */
    val earpieceFirst: Boolean,
    /** Fixed post-processing gain applied by MicProcessor, on top of micGain. */
    val fixedGain: Float,
    /** Peak ceiling of MicProcessor's soft limiter, linear full scale. */
    val limiterCeiling: Float,
    /** The phone's own echo canceller on the capture session (voice capture path only). */
    val hardwareAec: Boolean = false,
    /** The phone's own noise suppressor on the capture session. */
    val hardwareNs: Boolean = false,
) {
    fun logLine(): String {
        val src = when (audioSource) {
            MIC -> "mic"; VOICE_COMMUNICATION -> "voice"; else -> audioSource.toString()
        }
        val agc = if (autoGainControl) "on" else if (limiterCeiling < 1f) "off(limiter)" else "off"
        return "audio profile=$profile source=$src in=${inputSampleRate ?: "default"} out=$outputSampleRate " +
            "aec=${onOff(echoCancellation)}${if (hardwareAec) "+hw" else ""} ns=${onOff(noiseSuppression)}${if (hardwareNs) " hwns=on" else ""} agc=$agc " +
            "mode=${if (normalMode) "normal" else "call"} bitrate=${bitrate / 1000}k stereo=$stereoInput"
    }

    companion object {
        // Literal values of MediaRecorder.AudioSource, so JVM unit tests need no Android stubs.
        const val MIC = 1
        const val VOICE_COMMUNICATION = 7
        /** -3 dBFS. */
        const val HD_CEILING = 0.708f
        /** +2 dB: the raw MIC path has no platform gain behind it. */
        const val HD_GAIN = 1.26f

        private fun onOff(b: Boolean) = if (b) "on" else "off"

        /**
         * [style] is a test switch for HD_VOICE (adb shell setprop debug.applooma.audiostyle b):
         * the default is the stock engine setup (voice capture path, phone and engine
         * echo cancelling, noise suppression and AGC, call mode); "b" is raw 48 kHz
         * capture in plain media mode with only the engine's echo canceller.
         */
        fun resolve(profile: AppAudioProfile, audio: AppAudioOptions, scenario: AppAudioScenario, style: String? = null): AudioProfileSettings = when (profile) {
            AppAudioProfile.SPEECH -> AudioProfileSettings(
                profile, VOICE_COMMUNICATION, null, 48000, false, false,
                echoCancellation = audio.echoCancellation,
                noiseSuppression = audio.effectiveNoiseSuppression != AppNoiseSuppression.OFF,
                autoGainControl = audio.autoGainControl,
                highPassFilter = true, bitrate = 32_000, dtx = false, red = true,
                normalMode = false, earpieceFirst = scenario == AppAudioScenario.CALL,
                fixedGain = 1f, limiterCeiling = 1f,
                hardwareAec = audio.echoCancellation,
                hardwareNs = audio.effectiveNoiseSuppression != AppNoiseSuppression.OFF,
            )
            AppAudioProfile.HD_VOICE -> if (style == "b") AudioProfileSettings(
                profile, MIC, 48000, 48000, false, false,
                echoCancellation = audio.echoCancellation,
                noiseSuppression = false,
                autoGainControl = false,
                highPassFilter = true, bitrate = 128_000, dtx = false, red = true,
                normalMode = true, earpieceFirst = false,
                fixedGain = HD_GAIN, limiterCeiling = HD_CEILING,
            ) else AudioProfileSettings(
                // The stock engine setup most live apps ship with: the
                // phone's voice capture path and its own echo canceller and noise
                // suppressor, the engine's canceller, suppressor and AGC behind it,
                // call audio mode.
                profile, VOICE_COMMUNICATION, 48000, 48000, false, false,
                echoCancellation = audio.echoCancellation,
                noiseSuppression = audio.effectiveNoiseSuppression != AppNoiseSuppression.OFF,
                autoGainControl = audio.autoGainControl,
                highPassFilter = true, bitrate = 128_000, dtx = false, red = true,
                normalMode = false, earpieceFirst = scenario == AppAudioScenario.CALL,
                fixedGain = 1f, limiterCeiling = 1f,
                hardwareAec = audio.echoCancellation,
                hardwareNs = audio.effectiveNoiseSuppression != AppNoiseSuppression.OFF,
            )
            AppAudioProfile.MUSIC -> AudioProfileSettings(
                profile, MIC, 48000, 48000, true, true,
                echoCancellation = false, noiseSuppression = false, autoGainControl = false,
                highPassFilter = false, bitrate = 128_000, dtx = false, red = true,
                normalMode = true, earpieceFirst = false,
                fixedGain = 1f, limiterCeiling = 1f,
            )
        }
    }
}
