package com.applooma.rtc

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encodes about 30 synthetic 720p frames through the hardware H.264 encoder
 * the SDK would pick, straight through MediaCodec. Never touches the camera.
 * A failing encoder is remembered like one found bad during a call, so the
 * next publish goes to software.
 */
internal object EncoderSelfTest {
    private const val MIME = "video/avc"
    private const val PREFS = "applooma.selftest"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val FRAMES = 30
    private const val FPS = 30
    /** Encoded bytes kept for the bitstream check on the Codec2 path. */
    private const val MAX_INSPECT_BYTES = 2 * 1024 * 1024

    private val running = AtomicBoolean(false)

    /** Runs once per install and SDK version when [enabled]. */
    fun runOnceIfDue(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        val prefs = try { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) } catch (_: Throwable) { return }
        val key = SelfTestDecision.versionKey(BuildConfig.SDK_VERSION, AppLoomaVideoEncoderFactory.h264Codec2Enabled())
        if (!SelfTestDecision.shouldRun(enabled, prefs.getString("version", null), key)) return
        run(app, null) { prefs.edit().putString("version", key).apply() }
    }

    /** Runs now on a background thread; [callback] is delivered on the main thread. */
    fun run(context: Context, callback: ((AppEncoderSelfTestResult) -> Unit)?, afterDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        fun deliver(r: AppEncoderSelfTestResult) { if (callback != null) main.post { try { callback(r) } catch (_: Throwable) {} } }
        if (!running.compareAndSet(false, true)) {
            deliver(AppEncoderSelfTestResult(passed = true, encoderName = null, reason = null, elapsedMs = 0))
            return
        }
        try {
            Thread({
                try {
                    val r = testNow(app)
                    afterDone?.invoke()
                    deliver(r)
                } catch (_: Throwable) {
                } finally { running.set(false) }
            }, "applooma-selftest").apply { isDaemon = true }.start()
        } catch (_: Throwable) { running.set(false) }
    }

    private fun testNow(app: Context): AppEncoderSelfTestResult {
        val started = System.currentTimeMillis()
        val info = try { AppLoomaVideoEncoderFactory.findHardwareEncoder(MIME) } catch (_: Throwable) { null }
        if (info == null) {
            AppEngine.debug("encoder self-test: no hardware encoder in use for this codec, nothing to test")
            return AppEncoderSelfTestResult(true, null, null, 0)
        }
        // The encode runs on its own thread so a codec that hangs inside a
        // platform call cannot hold this one past the total timeout.
        var observation: SelfTestDecision.Observation? = null
        // On the Codec2 path, test the call's settings and check the bitstream itself.
        val codec2 = AppLoomaVideoEncoderFactory.isCodec2H264(info.name)
        val done = CountDownLatch(1)
        val worker = Thread({
            try { observation = encode(info, codec2) } catch (_: Throwable) {} finally { done.countDown() }
        }, "applooma-selftest-codec").apply { isDaemon = true }
        worker.start()
        val finished = try { done.await(SelfTestDecision.TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: Throwable) { false }
        val o = if (finished) observation ?: SelfTestDecision.Observation(true, false, false, 0)
        else SelfTestDecision.Observation(error = false, timedOut = true, sawConfigOrKeyFrame = false, outputBytes = 0)
        val reason = SelfTestDecision.verdict(o)
        val elapsed = System.currentTimeMillis() - started
        AppEngine.debug("encoder self-test encoder=${info.name} result=${reason ?: "ok"} bytes=${o.outputBytes} elapsed=${elapsed}ms" +
            if (o.bitstreamChecked) " codec2=true profile=${o.profileLevelId ?: "none"}" else "")
        if (reason != null) {
            CodecMemory.rememberBad(app, MIME, info.name)
            AppLoomaVideoEncoderFactory.forceSoftware(MIME)
            DeviceTelemetry.reportWithoutJoin(app, CodecHealth.REASON_ENCODER_ERROR, "h264", info.name)
            Diagnostics.problem(DiagPolicy.PROBLEM_SELF_TEST)
        }
        return AppEncoderSelfTestResult(reason == null, info.name, reason, elapsed)
    }

    private fun colorFormat(info: MediaCodecInfo): Int? {
        val caps = info.getCapabilitiesForType(MIME)
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
        )
        return preferred.firstOrNull { f -> caps.colorFormats.contains(f) }
    }

    private fun encode(info: MediaCodecInfo, codec2: Boolean): SelfTestDecision.Observation {
        val stream = if (codec2) java.io.ByteArrayOutputStream() else null
        var codec: MediaCodec? = null
        var sawConfig = false
        var bytes = 0L
        var error = false
        var timedOut = false
        try {
            val color = colorFormat(info) ?: return SelfTestDecision.Observation(true, false, false, 0)
            val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, color)
                setInteger(MediaFormat.KEY_BIT_RATE, 1_800_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (codec2) {
                    // The keys the call sets on this path (AppLoomaHardwareVideoEncoder).
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                    setInteger(MediaFormat.KEY_PROFILE,
                        if (Build.VERSION.SDK_INT >= 27) MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                        else MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                    setInteger("max-bframes", 0)
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                    if (Build.VERSION.SDK_INT >= 29) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
            }
            codec = MediaCodec.createByCodecName(info.name)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val frameSize = WIDTH * HEIGHT * 3 / 2
            val frame = ByteArray(frameSize)
            val bufferInfo = MediaCodec.BufferInfo()
            val firstFedAt = System.currentTimeMillis()
            var fed = 0
            while (true) {
                val now = System.currentTimeMillis()
                if (now - firstFedAt > SelfTestDecision.OUTPUT_WINDOW_MS) { timedOut = true; break }
                if (sawConfig && bytes > 0 && fed >= FRAMES) break
                if (fed < FRAMES) {
                    val inIndex = codec.dequeueInputBuffer(5_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)
                        if (buf != null) {
                            // A moving grey ramp: cheap, and never all-identical frames.
                            val shade = (16 + fed * 6).toByte()
                            java.util.Arrays.fill(frame, 0, WIDTH * HEIGHT, shade)
                            java.util.Arrays.fill(frame, WIDTH * HEIGHT, frameSize, 128.toByte())
                            buf.clear()
                            val n = minOf(buf.remaining(), frameSize)
                            buf.put(frame, 0, n)
                            codec.queueInputBuffer(inIndex, 0, n, fed * 1_000_000L / FPS, 0)
                            fed++
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 5_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (codec.outputFormat.containsKey("csd-0")) sawConfig = true
                    }
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            bytes += bufferInfo.size
                            val flags = bufferInfo.flags
                            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) sawConfig = true
                            if (stream != null && stream.size() < MAX_INSPECT_BYTES) {
                                codec.getOutputBuffer(outIndex)?.let { b ->
                                    b.position(bufferInfo.offset); b.limit(bufferInfo.offset + bufferInfo.size)
                                    val chunk = ByteArray(bufferInfo.size); b.get(chunk); stream.write(chunk)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                }
            }
        } catch (_: Throwable) {
            error = true
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
        }
        if (stream == null) return SelfTestDecision.Observation(error, timedOut, sawConfig, bytes)
        val inspection = H264StreamFixer.inspect(stream.toByteArray())
        return SelfTestDecision.Observation(
            error, timedOut, sawConfig, bytes,
            bitstreamChecked = true,
            bitstreamOk = SelfTestDecision.bitstreamOk(inspection),
            profileLevelId = inspection.profile?.profileLevelId,
        )
    }
}
