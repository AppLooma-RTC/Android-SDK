package com.applooma.rtc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clear Voice on the post-processing stage: 10 ms buffers of 48 kHz mono as
 * floats in 16-bit range, as the engine delivers them.
 */
class ClearVoiceTest {
    private val rate = 48000
    private val hop = 480

    private fun clearOnly() = MicProcessor(gainPercent = 100, gateThresholdDbfs = Float.NEGATIVE_INFINITY, agcTargetDbfs = 0).also { it.clearVoice = true }

    /** Feeds [seconds] of [signal] (sample index -> value) and returns the output. */
    private fun run(p: MicProcessor, seconds: Double, signal: (Int) -> Float): FloatArray {
        val total = (seconds * rate).toInt() / hop * hop
        val out = FloatArray(total)
        var i = 0
        while (i < total) {
            val b = ByteBuffer.allocateDirect(hop * 4).order(ByteOrder.nativeOrder())
            val f = b.asFloatBuffer()
            for (k in 0 until hop) f.put(k, signal(i + k))
            p.processAudio(3, 160, b)
            for (k in 0 until hop) out[i + k] = f.get(k)
            i += hop
        }
        return out
    }

    private fun energyDb(x: FloatArray, from: Int): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i].toDouble() * x[i]
        return 10 * log10(s / (x.size - from) + 1e-12)
    }

    /** Amplitude of the [hz] component over x[from..], by single-bin DFT. */
    private fun toneDb(x: FloatArray, from: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in from until x.size) { val a = 2 * PI * hz * i / rate; re += x[i] * cos(a); im -= x[i] * sin(a) }
        return 20 * log10(2 * sqrt(re * re + im * im) / (x.size - from))
    }

    private fun noise(seed: Long, amplitude: Float): (Int) -> Float {
        val r = Random(seed)
        val cache = HashMap<Int, Float>()
        return { i -> cache.getOrPut(i) { (r.nextGaussian() * amplitude).toFloat() } }
    }

    @Test fun clearReducesWhiteNoiseByAtLeast6dB() {
        val n = noise(1, 1000f)
        val input = FloatArray(3 * rate) { n(it) }
        val out = run(clearOnly(), 3.0) { input[it] }
        val skip = rate // let the noise floor settle for a second
        val reduction = energyDb(input, skip) - energyDb(out, skip)
        assertTrue("expected >= 6 dB less noise, got $reduction dB", reduction >= 6.0)
    }

    @Test fun clearKeepsA440HzToneWithin3dB() {
        val n = noise(2, 300f)
        val input = FloatArray(3 * rate) { (6000 * sin(2 * PI * 440 * it / rate)).toFloat() + n(it) }
        val out = run(clearOnly(), 3.0) { input[it] }
        val skip = rate
        val change = toneDb(out, skip, 440.0) - toneDb(input, skip, 440.0)
        assertTrue("expected the 440 Hz tone within 3 dB, got $change dB", change > -3.0 && change < 3.0)
    }

    @Test fun standardLeavesTheSignalUntouched() {
        val p = MicProcessor(gainPercent = 100, gateThresholdDbfs = Float.NEGATIVE_INFINITY, agcTargetDbfs = 0)
        assertTrue(!p.isEnabled())
        p.clearVoice = true
        assertTrue(p.isEnabled())
    }
}
