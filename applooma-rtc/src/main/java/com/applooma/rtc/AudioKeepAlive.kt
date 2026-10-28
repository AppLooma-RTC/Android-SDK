package com.applooma.rtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.nio.ByteBuffer

/**
 * Keeps Android in communication audio mode while a call is joined but nothing
 * is being played out (an empty room, everyone muted).
 *
 * Android 11 and later drops MODE_IN_COMMUNICATION after about six seconds
 * with no playback or capture (issuetracker.google.com/issues/209493718); the
 * route then jumps and echo cancellation switches off. A silent looping track
 * with voice-communication attributes keeps the mode. This is the SDK's own
 * version of that keep-alive; the transport's built-in one is always disabled.
 *
 * The difference is that every step here is guarded. Some phones refuse the
 * extra track ("Cannot create AudioTrack": too many tracks open in the app,
 * a route change in progress, a vendor audio limit). A refusal is logged once,
 * the keep-alive switches itself off for the rest of the process, and the call
 * carries on. It must never take the app down: it is a helper, not audio the
 * user hears.
 *
 * It can also be turned off by the app ([AppAudioOptions.audioKeepAlive]) or
 * per phone model by the AppLooma server (device-config `audio.keepAlive`).
 */
internal class AudioKeepAlive(
    /** Only meaningful when the call runs in MODE_IN_COMMUNICATION. */
    private val communicationMode: Boolean,
    private val log: (String) -> Unit,
    /** Injected for tests; the device's API level otherwise. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var started = false
    private var enabled = false
    private var playoutRunning = false
    private var playing = false

    /** Fed from the audio device module: the engine's own playout started / stopped. */
    fun onPlayoutStarted() = update { playoutRunning = true }
    fun onPlayoutStopped() = update { playoutRunning = false }

    /** A call is starting. [allowed] combines the app option, the server rule and the debug switch. */
    fun start(allowed: Boolean) = update {
        started = true
        enabled = allowed && communicationMode && sdkInt >= Build.VERSION_CODES.R && !refused
        if (allowed && communicationMode && refused) log("audio keep-alive: skipped (refused earlier in this process)")
    }

    /** The call ended. */
    fun stop() = update { started = false }

    /** The engine is destroyed: free the track. */
    /** Whether the silent track is playing now (for tests and logs). */
    val isPlaying: Boolean get() = synchronized(lock) { playing }

    fun release() = synchronized(lock) {
        started = false
        playing = false
        safely("release") { track?.release() }
        track = null
    }

    private inline fun update(change: () -> Unit) {
        // Callers include the audio thread; nothing below may throw out of here.
        try {
            synchronized(lock) {
                change()
                apply()
            }
        } catch (t: Throwable) {
            giveUp("update", t)
        }
    }

    private fun apply() {
        val want = enabled && started && !playoutRunning
        if (want == playing) return
        if (want) {
            val t = track ?: build() ?: return
            track = t
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                giveUp("play", IllegalStateException("track not initialized (state=${t.state})"))
                return
            }
            if (safely("play") { t.play() }) playing = true
        } else {
            playing = false
            track?.let { t -> safely("pause") { if (t.state == AudioTrack.STATE_INITIALIZED) t.pause() } }
        }
    }

    /** Test switch: make track creation fail as a refusing phone does. */
    @Volatile var forceFailure = false

    private fun build(): AudioTrack? = try {
        if (forceFailure) throw UnsupportedOperationException("Cannot create AudioTrack (forced by debug.applooma.keepalivefail)")
        val frames = SAMPLE_RATE / 100 // 10 ms of silence, looped
        val silence = ByteBuffer.allocateDirect(frames * 2)
        AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setBufferSizeInBytes(silence.capacity())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
            .build()
            .apply {
                write(silence, silence.remaining(), AudioTrack.WRITE_BLOCKING)
                setLoopPoints(0, frames - 1, -1)
            }
    } catch (t: Throwable) {
        giveUp("create", t)
        null
    }

    /** Runs [block]; on any failure gives up for the process and returns false. */
    private inline fun safely(step: String, block: () -> Unit): Boolean = try {
        block(); true
    } catch (t: Throwable) {
        giveUp(step, t); false
    }

    private fun giveUp(step: String, t: Throwable) {
        if (!refused) log("audio keep-alive: $step failed (${t.javaClass.simpleName}: ${t.message}); turned off, the call continues")
        refused = true
        enabled = false
        playing = false
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /**
         * A phone that refused once will refuse again in the same process, and
         * retrying on every join would only fill the log. Cleared on restart.
         */
        @Volatile internal var refused = false
    }
}
