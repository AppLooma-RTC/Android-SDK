package com.applooma.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

class VoiceChangerTest {
    private val rate = 48_000

    private fun sine(hz: Double, seconds: Double = 1.0, amp: Float = 0.3f) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    /** Runs [x] through [vc] in 10 ms buffers, as the engine does. */
    private fun run(vc: VoiceChanger, x: FloatArray): FloatArray {
        val out = x.copyOf()
        val frame = rate / 100
        val buf = FloatArray(frame)
        var i = 0
        while (i + frame <= out.size) {
            System.arraycopy(out, i, buf, 0, frame)
            vc.process(buf, frame)
            System.arraycopy(buf, 0, out, i, frame)
            i += frame
        }
        return out
    }

    /**
     * Strongest frequency between 60 and 800 Hz over the second half (after
     * start-up), by a 0.5 Hz Goertzel scan. Robust to the shifter's window
     * cross-fades, which can fool a lag-based estimate.
     */
    private fun pitch(x: FloatArray): Double {
        val from = x.size / 2
        var bestHz = 0.0; var bestPow = -1.0
        var hz = 60.0
        while (hz <= 800.0) {
            val k = 2 * kotlin.math.cos(2 * PI * hz / rate)
            var s1 = 0.0; var s2 = 0.0
            for (i in from until x.size) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
            val pw = s1 * s1 + s2 * s2 - k * s1 * s2
            if (pw > bestPow) { bestPow = pw; bestHz = hz }
            hz += 0.5
        }
        return bestHz
    }

        @Test fun eachPresetMovesPitchByItsSemitones() {
        val misses = mutableListOf<String>()
        for ((preset, p) in VoiceChanger.PRESETS) {
            if (p.ringMix > 0f) continue // ring modulation adds sidebands; checked separately
            val vc = VoiceChanger(rate).apply { set(preset) }
            val out = run(vc, sine(200.0))
            val want = 200.0 * 2.0.pow(p.semitones / 12.0)
            val got = pitch(out)
            if (abs(got - want) / want >= 0.02) misses += "$preset wanted ${"%.1f".format(want)} got ${"%.1f".format(got)}"
        }
        assertTrue(misses.joinToString(), misses.isEmpty())
    }

    @Test fun offIsBitExactPassThrough() {
        val x = sine(300.0, 0.2)
        val out = run(VoiceChanger(rate), x)
        assertArrayEquals(x, out, 0f)
    }

    @Test fun switchingOffRestoresTheVoice() {
        val vc = VoiceChanger(rate).apply { set(AppVoiceChanger.CHIPMUNK) }
        run(vc, sine(200.0, 0.2))
        vc.set(AppVoiceChanger.OFF)
        val x = sine(200.0, 0.2)
        assertArrayEquals(x, run(vc, x), 0f)
    }

    @Test fun addedDelayStaysUnder20ms() {
        val ms = VoiceChanger.PitchShifter(rate).latencySamples * 1000.0 / rate
        assertTrue("pitch shifter delay $ms ms", ms < 20.0)
    }

    @Test fun neverProducesNaNOrRunawayLevels() {
        val loud = FloatArray(rate) { if (it % 97 < 48) 0.99f else -0.99f } // harsh square, full scale
        for (preset in AppVoiceChanger.values()) {
            val out = run(VoiceChanger(rate).apply { set(preset) }, loud)
            val peak = out.maxOf { abs(it) }
            assertTrue("$preset produced NaN", out.none { it.isNaN() || it.isInfinite() })
            assertTrue("$preset peak $peak", peak < 4f) // the send limiter after it brings this under full scale
        }
    }

    @Test fun robotIsRingModulated() {
        val vc = VoiceChanger(rate).apply { set(AppVoiceChanger.ROBOT) }
        val out = run(vc, sine(400.0))
        // Ring modulation at 80 Hz makes the envelope pulse: the level varies a lot between 2 ms slices.
        val slice = rate / 500
        val levels = (rate / 2 until rate - slice step slice).map { s -> (s until s + slice).maxOf { abs(out[it]) } }
        assertTrue("robot envelope does not pulse", levels.max() > levels.min() * 1.5f)
    }

    @Test fun otherSampleRatesWork() {
        for (r in intArrayOf(16_000, 32_000, 44_100)) {
            val vc = VoiceChanger(r).apply { set(AppVoiceChanger.BABY) }
            val x = FloatArray(r) { (0.3 * sin(2 * PI * 200 * it / r)).toFloat() }
            val buf = FloatArray(r / 100)
            var i = 0
            while (i + buf.size <= x.size) { System.arraycopy(x, i, buf, 0, buf.size); vc.process(buf, buf.size); System.arraycopy(buf, 0, x, i, buf.size); i += buf.size }
            assertTrue(x.none { it.isNaN() })
        }
    }

    @Test fun keysMatchTheIntegrationGuide() {
        val keys = listOf("off", "baby", "female", "male", "deepmale", "neutral", "cartoon", "chipmunk",
            "shinchan", "girlishman", "ironlady", "monster", "robot", "groot", "vader", "phone")
        assertEquals(keys, AppVoiceChanger.values().map { it.key })
        for (k in keys) assertEquals(k, AppVoiceChanger.fromKey(k).key)
        assertEquals(AppVoiceChanger.BABY, AppVoiceChanger.fromKey(" Baby "))
        assertEquals(AppVoiceChanger.OFF, AppVoiceChanger.fromKey("nonsense"))
        assertEquals(AppVoiceChanger.OFF, AppVoiceChanger.fromKey(null))
    }

    @Test fun everyPresetHasAVoice() {
        for (p in AppVoiceChanger.values()) if (p != AppVoiceChanger.OFF) assertTrue("$p has no preset", VoiceChanger.PRESETS.containsKey(p))
    }
}
