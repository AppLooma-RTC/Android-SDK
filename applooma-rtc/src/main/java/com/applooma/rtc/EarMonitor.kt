package com.applooma.rtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-ear monitoring: the speaker hears their own voice, with the voice changer
 * and every other microphone effect applied, while they talk.
 *
 * The processed microphone (the last stage of MicProcessor) is pushed into a
 * single-producer ring from the audio thread; a playback thread drains it into
 * a low-latency AudioTrack. Only the newest audio is kept: if the ring holds
 * more than [MAX_BACKLOG_MS], the oldest part is dropped, so the delay can never
 * creep up during a long session.
 *
 * It plays only while the call is routed to headphones (wired or Bluetooth).
 * On the loudspeaker or earpiece the monitored voice would go back into the
 * microphone and the room would hear an echo, so it stays silent there.
 *
 * Like the keep-alive, every step is guarded: a phone that refuses the track
 * gets one log line and monitoring switches off; the call is never affected.
 */
internal class EarMonitor(
    /** True while the call is on headphones; polled from the playback thread. */
    private val onHeadphones: () -> Boolean,
    private val log: (String) -> Unit,
) {
    @Volatile var enabled = false
        set(v) { field = v; if (v) ensureThread() }
    /** 0..1 */
    @Volatile var volume = 1f

    @Volatile private var rate = 0
    private val ring = FloatArray(RING)
    private val writeIdx = AtomicInteger(0)
    private val readIdx = AtomicInteger(0)
    @Volatile private var thread: Thread? = null
    @Volatile private var released = false

    /** Audio thread: the final processed microphone samples in ±1. */
    fun feed(x: FloatArray, n: Int, sampleRate: Int) {
        if (!enabled || released) return
        rate = sampleRate
        var w = writeIdx.get()
        for (i in 0 until n) { ring[w and MASK] = x[i]; w++ }
        writeIdx.set(w)
    }

    fun release() {
        released = true
        enabled = false
        thread?.interrupt()
        thread = null
    }

    private fun ensureThread() {
        if (thread != null || released) return
        thread = Thread({ run() }, "AppLoomaEarMonitor").apply { isDaemon = true; start() }
    }

    private fun run() {
        var track: AudioTrack? = null
        var trackRate = 0
        var routeOk = false
        var lastRouteCheck = 0L
        val chunk = FloatArray(RING)
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (!released && enabled) {
                val now = System.currentTimeMillis()
                if (now - lastRouteCheck > 500) {
                    routeOk = try { onHeadphones() } catch (_: Throwable) { false }
                    lastRouteCheck = now
                }
                val r = rate
                val available = writeIdx.get() - readIdx.get()
                if (r <= 0 || available <= 0) { Thread.sleep(5); continue }

                // Drop anything older than the backlog limit: newest audio only.
                val maxBacklog = r * MAX_BACKLOG_MS / 1000
                if (available > maxBacklog) readIdx.addAndGet(available - maxBacklog)
                val start = readIdx.get()
                val count = minOf(writeIdx.get() - start, chunk.size)
                for (i in 0 until count) chunk[i] = ring[(start + i) and MASK]
                readIdx.addAndGet(count)

                if (!routeOk) {
                    track?.let { t -> try { t.pause(); t.flush() } catch (_: Throwable) {} }
                    continue
                }
                if (track == null || trackRate != r) {
                    try { track?.release() } catch (_: Throwable) {}
                    track = build(r)
                    trackRate = r
                    track.play()
                } else if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                }
                val v = volume
                if (v != 1f) for (i in 0 until count) chunk[i] *= v
                track.write(chunk, 0, count, AudioTrack.WRITE_NON_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            log("in-ear monitoring: stopped (${t.javaClass.simpleName}: ${t.message}); the call continues")
            enabled = false
        } finally {
            try { track?.release() } catch (_: Throwable) {}
            thread = null
            // Switched off and back on while this thread was ending: start again.
            if (enabled && !released) ensureThread()
        }
    }

    private fun build(r: Int): AudioTrack {
        val min = AudioTrack.getMinBufferSize(r, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val b = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(r)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, r / 50 * 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        return b.build()
    }

    private companion object {
        const val RING = 16_384 // power of two; > 300 ms at 48 kHz
        const val MASK = RING - 1
        const val MAX_BACKLOG_MS = 30
    }
}
