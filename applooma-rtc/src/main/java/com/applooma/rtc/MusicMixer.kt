package com.applooma.rtc

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.livekit.android.audio.MixerAudioBufferCallback
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * In-room music: a file decoded on a background thread, mixed into the
 * microphone capture so everyone hears it, and played out locally so the host
 * hears it too — at two independent volumes.
 *
 * How the pieces fit:
 *  - [MediaExtractor] + [MediaCodec] decode the file to 16-bit PCM in chunks.
 *  - Chunks are resampled to the capture format the engine reports on every
 *    callback (usually 48 kHz mono) and kept in a ring of about two seconds.
 *  - The engine's capture callback is the clock: each time it asks for a
 *    buffer, the same slice of music goes two ways — scaled by the publish
 *    volume into the outgoing audio, and scaled by the playout volume into an
 *    [AudioTrack] on this device.
 *  - When the microphone is off the capture callback stops, so a small pump
 *    thread keeps local playout going; publishing resumes the moment the mic
 *    is on again.
 *
 * The same callback also applies the recording gain to the microphone signal,
 * so one hook serves both features.
 */
internal class MusicMixer(private val listener: Listener) : MixerAudioBufferCallback() {

    interface Listener {
        fun onMusicStateChanged(state: AppMusicState)
        fun onMusicFinished()
        fun onMusicError(error: Throwable)
    }

    /** 0..100 → multiplier. 100 is unity; up to 400 allowed for quiet files. */
    @Volatile var playoutVolume: Int = 100
    @Volatile var publishVolume: Int = 100
    /**
     * Audio usage for the local playout track. MEDIA sessions play music on
     * the media path (loudspeaker, media volume keys); CALL sessions on the
     * communication path. Set once by the engine from its audio scenario.
     */
    @Volatile var playoutUsage: Int = AudioAttributes.USAGE_VOICE_COMMUNICATION
    /** Recording (microphone) gain, 0..400, 100 = unity. */
    @Volatile var recordingVolume: Int = 100
    /**
     * When set, the microphone samples are replaced with silence before mixing,
     * so only the music reaches the room. This is how a muted microphone and
     * playing music coexist: the capture track stays alive (music rides on it)
     * while the person's voice does not. See AppEngine.enableMicrophone.
     */
    @Volatile var micMuted: Boolean = false

    @Volatile private var state: AppMusicState = AppMusicState.STOPPED
    private val paused = AtomicBoolean(false)
    private var decoder: Thread? = null
    private var pump: Thread? = null
    private val stopRequested = AtomicBoolean(false)

    // Ring buffer of 16-bit PCM in the *capture* format.
    private val ring = ShortArray(48_000 * 2 * 4) // 4 s at 48 kHz stereo, plenty
    private var readPos = 0
    private var writePos = 0
    private var stored = 0
    private val lock = Object()

    private var targetRate = 48_000
    private var targetChannels = 1
    private val formatKnown = AtomicBoolean(false)

    private val positionMs = AtomicLong(0)
    private val durationMs = AtomicLong(0)
    private val seekToMs = AtomicLong(-1)
    private val lastCallbackNs = AtomicLong(0)
    private var playout: AudioTrack? = null
    private val loops = AtomicInteger(1)

    val currentState: AppMusicState get() = state
    val position: Int get() = positionMs.get().toInt()
    val duration: Int get() = durationMs.get().toInt()

    /* ------------------------------------------------------------------ */
    /* Public controls (called from the engine, any thread)                */
    /* ------------------------------------------------------------------ */

    fun play(path: String, loopCount: Int, startMs: Int) {
        stop()
        stopRequested.set(false)
        paused.set(false)
        loops.set(if (loopCount <= 0) Int.MAX_VALUE else loopCount)
        seekToMs.set(if (startMs > 0) startMs.toLong() else -1)
        synchronized(lock) { readPos = 0; writePos = 0; stored = 0 }
        setState(AppMusicState.PLAYING)
        decoder = Thread({ decodeLoop(path) }, "applooma-music-decode").apply { isDaemon = true; start() }
        pump = Thread({ pumpLoop() }, "applooma-music-pump").apply { isDaemon = true; start() }
    }

    fun pause() { if (state == AppMusicState.PLAYING) { paused.set(true); playout?.pause(); setState(AppMusicState.PAUSED) } }
    fun resume() { if (state == AppMusicState.PAUSED) { paused.set(false); playout?.play(); setState(AppMusicState.PLAYING) } }

    fun stop() {
        if (state == AppMusicState.STOPPED && decoder == null) return
        stopRequested.set(true)
        paused.set(false)
        decoder?.interrupt(); decoder = null
        pump?.interrupt(); pump = null
        synchronized(lock) { readPos = 0; writePos = 0; stored = 0; lock.notifyAll() }
        releasePlayout()
        positionMs.set(0)
        setState(AppMusicState.STOPPED)
    }

    fun seek(ms: Int) { seekToMs.set(max(0, ms).toLong()); synchronized(lock) { readPos = 0; writePos = 0; stored = 0; lock.notifyAll() } }

    /* ------------------------------------------------------------------ */
    /* Engine capture callback                                             */
    /* ------------------------------------------------------------------ */

    override fun onBufferRequest(
        originalBuffer: ByteBuffer, audioFormat: Int, channelCount: Int, sampleRate: Int, bytesRead: Int, captureTimeNs: Long,
    ): BufferResponse? {
        lastCallbackNs.set(System.nanoTime())
        CaptureProbes.raw.feed(originalBuffer, sampleRate, channelCount, bytesRead)
        AudioDump.raw(originalBuffer, sampleRate, channelCount, bytesRead)
        if (targetRate != sampleRate || targetChannels != channelCount) {
            targetRate = sampleRate; targetChannels = channelCount
            synchronized(lock) { readPos = 0; writePos = 0; stored = 0 }
            releasePlayout()
        }
        formatKnown.set(true)

        // Recording gain on the microphone itself, in place — or silence, when
        // the microphone is muted but the track is kept alive for the music.
        val gain = if (micMuted) 0 else recordingVolume
        if (gain != 100 && bytesRead > 0) scaleInPlace(originalBuffer, bytesRead, gain / 100f)

        if (state != AppMusicState.PLAYING) return null
        val samples = bytesRead / 2
        if (samples <= 0) return null
        val slice = ShortArray(samples)
        val got = take(slice)
        if (got == 0) return null

        writePlayout(slice, got)

        val pub = publishVolume / 100f
        val out = ByteBuffer.allocateDirect(samples * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until samples) out.putShort(if (i < got) clip(slice[i] * pub) else 0)
        out.flip()
        advancePosition(got)
        return BufferResponse(out, captureTimeNs)
    }

    /* ------------------------------------------------------------------ */
    /* Decoder                                                             */
    /* ------------------------------------------------------------------ */

    private fun decodeLoop(path: String) {
        var remainingLoops = loops.get()
        try {
            while (!stopRequested.get() && remainingLoops > 0) {
                decodeOnce(path)
                if (stopRequested.get()) return
                if (remainingLoops != Int.MAX_VALUE) remainingLoops--
                if (remainingLoops > 0) positionMs.set(0)
            }
            // Let the tail drain, then finish.
            while (!stopRequested.get() && synchronized(lock) { stored } > 0) Thread.sleep(20)
            if (!stopRequested.get()) {
                releasePlayout()
                setState(AppMusicState.STOPPED)
                listener.onMusicFinished()
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            setState(AppMusicState.STOPPED)
            listener.onMusicError(t)
        }
    }

    private fun decodeOnce(path: String) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(path)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("No audio track in $path")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            if (format.containsKey(MediaFormat.KEY_DURATION)) durationMs.set(format.getLong(MediaFormat.KEY_DURATION) / 1000)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !stopRequested.get()) {
                val seek = seekToMs.getAndSet(-1)
                if (seek >= 0) {
                    extractor.seekTo(seek * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    positionMs.set(seek)
                    inputDone = false
                }
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIndex >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (info.size > 0) {
                            // Wait for the engine to tell us its capture format
                            // before converting; the first callback arrives
                            // within a few ms of the mic starting.
                            var waited = 0
                            while (!formatKnown.get() && waited < 500 && !stopRequested.get()) { Thread.sleep(10); waited += 10 }
                            val pcm = ShortArray(info.size / 2)
                            outBuf.order(ByteOrder.nativeOrder()).position(info.offset)
                            outBuf.asShortBuffer().get(pcm)
                            val converted = convert(pcm, srcRate, srcChannels, targetRate, targetChannels)
                            put(converted)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec?.stop(); codec?.release() } catch (_: Throwable) {}
            extractor.release()
        }
    }

    /** Linear resample + channel up/down-mix. Good enough for room music; not for mastering. */
    private fun convert(src: ShortArray, srcRate: Int, srcCh: Int, dstRate: Int, dstCh: Int): ShortArray {
        val srcFrames = src.size / srcCh
        val dstFrames = if (srcRate == dstRate) srcFrames else (srcFrames.toLong() * dstRate / srcRate).toInt()
        val out = ShortArray(dstFrames * dstCh)
        val step = srcFrames.toDouble() / dstFrames
        for (f in 0 until dstFrames) {
            val pos = f * step
            val i0 = min(pos.toInt(), srcFrames - 1)
            val i1 = min(i0 + 1, srcFrames - 1)
            val t = (pos - i0).toFloat()
            for (c in 0 until dstCh) {
                val sc = if (srcCh == dstCh) c else if (srcCh == 1) 0 else -1 // -1 = downmix
                val a: Float; val b: Float
                if (sc >= 0) { a = src[i0 * srcCh + sc].toFloat(); b = src[i1 * srcCh + sc].toFloat() }
                else { a = avg(src, i0 * srcCh, srcCh); b = avg(src, i1 * srcCh, srcCh) }
                out[f * dstCh + c] = clip(a + (b - a) * t)
            }
        }
        return out
    }

    private fun avg(s: ShortArray, off: Int, n: Int): Float { var sum = 0f; for (i in 0 until n) sum += s[off + i]; return sum / n }

    /* ------------------------------------------------------------------ */
    /* Ring buffer                                                         */
    /* ------------------------------------------------------------------ */

    private fun put(data: ShortArray) {
        var offset = 0
        while (offset < data.size && !stopRequested.get()) {
            synchronized(lock) {
                while (stored >= ring.size - 1 && !stopRequested.get()) lock.wait(20)
                val free = ring.size - stored
                val n = min(free, data.size - offset)
                for (i in 0 until n) { ring[writePos] = data[offset + i]; writePos = (writePos + 1) % ring.size }
                stored += n; offset += n
                lock.notifyAll()
            }
        }
    }

    private fun take(dst: ShortArray): Int = synchronized(lock) {
        if (paused.get()) return 0
        val n = min(stored, dst.size)
        for (i in 0 until n) { dst[i] = ring[readPos]; readPos = (readPos + 1) % ring.size }
        stored -= n
        lock.notifyAll()
        n
    }

    /* ------------------------------------------------------------------ */
    /* Local playout                                                       */
    /* ------------------------------------------------------------------ */

    private fun ensurePlayout(): AudioTrack? {
        playout?.let { return it }
        return try {
            val channelMask = if (targetChannels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
            val minBuf = AudioTrack.getMinBufferSize(targetRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(playoutUsage).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(targetRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(max(minBuf, targetRate * targetChannels * 2 / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also {
                    it.play(); playout = it
                    AppEngine.debug("music monitor track rate=${it.sampleRate} channels=${it.channelCount} usage=$playoutUsage")
                }
        } catch (t: Throwable) { listener.onMusicError(t); null }
    }

    private fun writePlayout(slice: ShortArray, n: Int) {
        val track = ensurePlayout() ?: return
        val vol = playoutVolume / 100f
        val buf = ShortArray(n) { clip(slice[it] * vol) }
        track.write(buf, 0, n, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun releasePlayout() {
        playout?.let { try { it.stop(); it.release() } catch (_: Throwable) {} }
        playout = null
    }

    /** Keeps local playout alive while the microphone (and its callback) is off. */
    private fun pumpLoop() {
        val frame = targetRate / 100 * targetChannels // 10 ms
        try {
            while (!stopRequested.get()) {
                Thread.sleep(10)
                if (state != AppMusicState.PLAYING) continue
                val sinceCallback = System.nanoTime() - lastCallbackNs.get()
                if (sinceCallback < 200_000_000L) continue // the engine is driving
                val slice = ShortArray(frame)
                val got = take(slice)
                if (got == 0) continue
                writePlayout(slice, got)
                advancePosition(got)
            }
        } catch (_: InterruptedException) {}
    }

    private fun advancePosition(samples: Int) { positionMs.addAndGet(samples * 1000L / targetChannels / targetRate) }

    private fun setState(s: AppMusicState) { if (state != s) { state = s; listener.onMusicStateChanged(s) } }

    private fun scaleInPlace(buf: ByteBuffer, bytes: Int, gain: Float) {
        val sb = buf.duplicate().order(ByteOrder.nativeOrder())
        val n = bytes / 2
        for (i in 0 until n) { val v = sb.getShort(i * 2); sb.putShort(i * 2, clip(v * gain)) }
    }

    private fun clip(v: Float): Short = max(-32768f, min(32767f, v)).toInt().toShort()
}
