package com.applooma.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VoiceEffectsTest {
    private val rate = 48_000

    private fun sine(hz: Double, seconds: Double = 1.0, amp: Float = 0.3f) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

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

    private fun strongest(x: FloatArray, lo: Double, hi: Double, step: Double): Double {
        val from = x.size / 2
        var bestHz = 0.0; var bestPow = -1.0
        var hz = lo
        while (hz <= hi) {
            val k = 2 * kotlin.math.cos(2 * PI * hz / rate)
            var s1 = 0.0; var s2 = 0.0
            for (i in from until x.size) { val s0 = x[i] + k * s1 - s2; s2 = s1; s1 = s0 }
            val pw = s1 * s1 + s2 * s2 - k * s1 * s2
            if (pw > bestPow) { bestPow = pw; bestHz = hz }
            hz += step
        }
        return bestHz
    }

    @Test fun keysMatchTheIntegrationGuide() {
        assertEquals(listOf("off", "ktv", "concert", "studio", "phonograph", "stereo", "spacial", "ethereal", "3d"),
            AppAudioEffect.values().map { it.key })
        assertEquals(listOf("off", "magnetic", "fresh", "vitality", "deep", "mellow", "clear", "resounding", "ringing", "pop", "rnb", "autotune"),
            AppVoiceStyle.values().map { it.key })
        assertEquals(AppAudioEffect.VOICE_3D, AppAudioEffect.fromKey(" 3D "))
        assertEquals(AppVoiceStyle.OFF, AppVoiceStyle.fromKey("nope"))
        assertEquals(AppAudioEffect.OFF, AppAudioEffect.fromKey(null))
    }

    @Test fun everyPresetHasASetting() {
        for (e in AppAudioEffect.values()) if (e != AppAudioEffect.OFF) assertTrue("$e", VoiceEffects.ROOMS.containsKey(e))
        for (s in AppVoiceStyle.values()) if (s != AppVoiceStyle.OFF) assertTrue("$s", VoiceEffects.STYLES.containsKey(s))
    }

    @Test fun allOffIsBitExact() {
        val x = sine(300.0, 0.2)
        assertArrayEquals(x, run(VoiceChanger(rate), x), 0f)
    }

    @Test fun switchingEverythingOffRestoresTheVoice() {
        val vc = VoiceChanger(rate).apply { setStyle(AppVoiceStyle.POP); setRoom(AppAudioEffect.CONCERT) }
        run(vc, sine(200.0, 0.3))
        vc.setStyle(AppVoiceStyle.OFF); vc.setRoom(AppAudioEffect.OFF)
        val x = sine(200.0, 0.2)
        assertArrayEquals(x, run(vc, x), 0f)
    }

    @Test fun roomsLeaveATail() {
        // 100 ms of tone, then silence: a room keeps sounding after the voice stops.
        for (e in AppAudioEffect.values()) {
            if (e == AppAudioEffect.OFF) continue
            val x = FloatArray(rate / 2)
            for (i in 0 until rate / 10) x[i] = (0.3 * sin(2 * PI * 400 * i / rate)).toFloat()
            val out = run(VoiceChanger(rate).apply { setRoom(e) }, x)
            val tail = (rate / 10 + rate / 20 until rate / 10 + rate / 5).maxOf { abs(out[it]) }
            assertTrue("$e tail $tail", tail > 0.002f)
        }
    }

    @Test fun neverProducesNaNOrRunawayLevels() {
        val loud = FloatArray(rate) { if (it % 97 < 48) 0.99f else -0.99f }
        for (e in AppAudioEffect.values()) for (s in listOf(AppVoiceStyle.OFF, AppVoiceStyle.POP, AppVoiceStyle.AUTOTUNE)) {
            val out = run(VoiceChanger(rate).apply { setRoom(e); setStyle(s) }, loud)
            assertTrue("$e/$s NaN", out.none { it.isNaN() || it.isInfinite() })
            assertTrue("$e/$s peak ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 4f)
        }
        for (s in AppVoiceStyle.values()) {
            val out = run(VoiceChanger(rate).apply { setStyle(s) }, loud)
            assertTrue("$s NaN", out.none { it.isNaN() || it.isInfinite() })
            assertTrue("$s peak", out.maxOf { abs(it) } < 4f)
        }
    }

    @Test fun autotuneSnapsToTheNearestNote() {
        // 210 Hz is 28 cents above G#3 (207.65 Hz).
        val out = run(VoiceChanger(rate).apply { setStyle(AppVoiceStyle.AUTOTUNE) }, sine(210.0))
        val got = strongest(out, 190.0, 230.0, 0.25)
        assertTrue("autotune gave $got Hz", abs(got - 207.65) < 1.5)
    }

    @Test fun stagesCombine() {
        val vc = VoiceChanger(rate).apply { set(AppVoiceChanger.FEMALE); setStyle(AppVoiceStyle.CLEAR); setRoom(AppAudioEffect.KTV) }
        val out = run(vc, sine(150.0))
        assertTrue(out.none { it.isNaN() })
        assertTrue(out.maxOf { abs(it) } > 0.05f)
    }
}
