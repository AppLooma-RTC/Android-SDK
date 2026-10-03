package com.applooma.rtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures, once per session, how wide the microphone signal really is: the
 * capture sample rate and how much energy sits above 8 kHz while someone is
 * speaking. Some phones route voice capture through a 16 kHz path, which cuts
 * everything above 8 kHz and makes speech sound dull; this line in the
 * diagnostics log shows it without anyone having to listen.
 */
internal class CaptureProbe(private val label: String) {
    private val N = 2048
    private val WANT_BLOCKS = 40          // about 1.7 s of speech at 48 kHz
    private val buf = FloatArray(N)
    private var fill = 0
    private var blocks = 0
    private var low = 0.0
    private var high = 0.0
    @Volatile private var done = true
    private var rate = 0

    fun start() { synchronized(this) { fill = 0; blocks = 0; low = 0.0; high = 0.0; done = false; rate = 0 } }

    /** Called with raw 16-bit PCM from the capture callback (any channel count). */
    fun feed(data: ByteBuffer, sampleRate: Int, channels: Int, bytes: Int) {
        if (done) return
        synchronized(this) {
            if (done) return
            rate = sampleRate
            val sb = data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            sb.position(0)
            val frames = bytes / 2 / channels.coerceAtLeast(1)
            for (i in 0 until frames) {
                if (sb.remaining() < 2 * channels) break
                val s = sb.short / 32768f
                for (c in 1 until channels) sb.short
                buf[fill++] = s
                if (fill == N) { fill = 0; analyse() }
                if (done) return
            }
        }
    }

    /** Float samples from the processing stage (any scale; only ratios matter). */
    fun feedFloat(fb: java.nio.FloatBuffer, n: Int, sampleRate: Int) {
        if (done) return
        synchronized(this) {
            if (done) return
            rate = sampleRate
            var peak = 0f
            for (i in 0 until n) { val a = kotlin.math.abs(fb.get(i)); if (a > peak) peak = a }
            val sc = if (peak > 2f) 32768f else 1f
            for (i in 0 until n) {
                buf[fill++] = fb.get(i) / sc
                if (fill == N) { fill = 0; analyse() }
                if (done) return
            }
        }
    }

    val active: Boolean get() = !done

    private val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "applooma-probe").apply { isDaemon = true } }
    @Volatile private var busy = false

    private fun analyse() {
        var e = 0.0
        for (v in buf) e += v * v
        if (sqrt(e / N) < 0.01 || busy) return       // speech only; never queue work on the audio thread
        busy = true
        val copy = buf.copyOf()
        val r = rate
        worker.execute { try { spectrum(copy, r) } finally { busy = false } }
    }

    private fun spectrum(buf: FloatArray, rate: Int) {
        // Energy below and above 8 kHz with a coarse DFT on a few bins (cheap, runs ~40 times per session).
        val step = 8
        for (k in 1 until N / 2 step step) {
            val f = k.toDouble() * rate / N
            var re = 0.0; var im = 0.0
            for (n in 0 until N) {
                val w = 0.5 - 0.5 * cos(2 * PI * n / (N - 1))
                val a = 2 * PI * k * n / N
                re += buf[n] * w * cos(a); im -= buf[n] * w * sin(a)
            }
            val p = re * re + im * im
            if (f in 300.0..4000.0) low += p else if (f > 8500 && f < 16000) high += p
        }
        var finished = false
        synchronized(this) { if (!done && ++blocks >= WANT_BLOCKS) { done = true; finished = true } }
        if (finished) {
            val db = 10 * log10((high + 1e-12) / (low + 1e-12))
            AppEngine.debug("mic probe $label rate=${rate}Hz hf8k=${"%.1f".format(db)}dB (above -35 dB = full band; far below = band-limited)")
        }
    }
}

internal object CaptureProbes {
    val raw = CaptureProbe("raw")
    val sent = CaptureProbe("sent")
    fun start() { raw.start(); sent.start() }
}
