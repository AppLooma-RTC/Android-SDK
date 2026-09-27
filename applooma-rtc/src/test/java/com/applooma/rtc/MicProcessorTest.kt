package com.applooma.rtc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The engine feeds the post-processing stage 10 ms of 48 kHz mono as floats in
 * 16-bit range (±32768). These tests pin the behaviour a customer hit: any
 * gain other than unity used to squash the voice to ±1 (silence).
 */
class MicProcessorTest {
    private fun voice(amplitude: Float, frames: Int = 480): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        val f = b.asFloatBuffer()
        for (i in 0 until frames) f.put(i, (amplitude * sin(2 * PI * 220 * i / 48000.0)).toFloat())
        return b
    }

    private fun peak(b: ByteBuffer): Float {
        val f = b.order(ByteOrder.nativeOrder()).asFloatBuffer()
        var p = 0f
        for (i in 0 until f.limit()) p = maxOf(p, abs(f.get(i)))
        return p
    }

    /** Runs a few buffers so the gate/AGC smoothing settles, returns the last output peak. */
    private fun run(p: MicProcessor, amplitude: Float, buffers: Int = 50): Float {
        var last = 0f
        repeat(buffers) { val b = voice(amplitude); p.processAudio(3, 160, b); last = peak(b) }
        return last
    }

    @Test fun gain200DoublesA16BitVoiceInsteadOfSilencingIt() {
        val out = run(MicProcessor(gainPercent = 200, gateThresholdDbfs = Float.NEGATIVE_INFINITY, agcTargetDbfs = 0), 6000f)
        assertTrue("expected ~12000, got $out", out in 11000f..13000f)
    }

    @Test fun loudVoiceIsLimitedBelowFullScaleNotClippedToOne() {
        val out = run(MicProcessor(gainPercent = 300, gateThresholdDbfs = Float.NEGATIVE_INFINITY, agcTargetDbfs = 0), 20000f)
        assertTrue("expected soft-limited below 32768 and far above 1, got $out", out > 25000f && out <= 32768f)
    }

    @Test fun agcTargetKeepsTheVoiceAudible() {
        val out = run(MicProcessor(gainPercent = 100, gateThresholdDbfs = Float.NEGATIVE_INFINITY, agcTargetDbfs = -18), 3000f, buffers = 400)
        // -18 dBFS RMS of a sine is a peak of about 0.178 * 1.414 * 32768 ≈ 8250.
        assertTrue("expected the AGC to lift toward ~8000, got $out", out in 5000f..12000f)
    }

    @Test fun gateEasesSilenceDownButLetsSpeechThrough() {
        val p = MicProcessor(gainPercent = 100, gateThresholdDbfs = -50f, agcTargetDbfs = 0)
        val speech = run(p, 6000f)
        assertTrue("speech should pass the gate, got $speech", speech > 5000f)
        val hiss = run(p, 30f, buffers = 200)   // about -60 dBFS
        assertTrue("hiss below the threshold should be eased down, got $hiss", hiss < 10f)
    }
}
