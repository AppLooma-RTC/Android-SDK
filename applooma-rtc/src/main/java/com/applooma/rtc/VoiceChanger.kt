package com.applooma.rtc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt


/**
 * Voice changer DSP, run on the microphone after noise suppression and before
 * the send limiter (inside MicProcessor), so everyone in the room hears it.
 *
 * Pure Kotlin on FloatArray in ±1, no Android types: the presets are checked by
 * plain JVM tests (VoiceChangerTest), including the pitch each one produces.
 *
 * Pitch is moved with a two-tap delay line: the read point travels at
 * [Preset.ratio] times the write speed, and two taps half a window apart are
 * cross-faded (sin² + cos² = 1, so the level stays flat). It adds about half a
 * window of delay — 12 ms with the 24 ms window plus a few ms of splice search, about 16 ms, which keeps lips and voice
 * together, unlike FFT pitch shifters that need 40 ms and more. The timbre is
 * then shaped with RBJ biquads, plus ring modulation (robot) and a soft
 * saturator (monster, phone) where a preset calls for them.
 */
internal class VoiceChanger(val sampleRate: Int) {

    /** One voice: pitch, tone shaping and colour. */
    internal data class Preset(
        val semitones: Float = 0f,
        val filters: List<Biquad.Spec> = emptyList(),
        val ringHz: Float = 0f,
        val ringMix: Float = 0f,
        val drive: Float = 0f,
    ) {
        val ratio: Float get() = 2f.pow(semitones / 12f)
    }

    val rate = if (sampleRate > 0) sampleRate else 48_000
    @Volatile private var target: AppVoiceChanger = AppVoiceChanger.OFF
    private var current: AppVoiceChanger = AppVoiceChanger.OFF
    private var preset = Preset()
    private var filters: List<Biquad> = emptyList()
    private val shifter = PitchShifter(rate)
    private var ringPhase = 0.0

    /** Safe from any thread; applied at the start of the next buffer. */
    fun set(changer: AppVoiceChanger) { target = changer }

    val active: Boolean get() = target != AppVoiceChanger.OFF

    /** True while a preset is on or still being switched off (state to reset). */
    val busy: Boolean get() = target != AppVoiceChanger.OFF || current != AppVoiceChanger.OFF

    /** Processes [n] samples of [x] in place. */
    fun process(x: FloatArray, n: Int) {
        val t = target
        if (t != current) {
            current = t
            preset = PRESETS[t] ?: Preset()
            filters = preset.filters.map { Biquad(it, rate) }
            shifter.reset()
            ringPhase = 0.0
        }
        if (current == AppVoiceChanger.OFF) return
        val p = preset
        if (p.semitones != 0f) shifter.process(x, n, p.ratio)
        for (f in filters) f.process(x, n)
        if (p.ringMix > 0f) {
            val step = 2.0 * PI * p.ringHz / rate
            for (i in 0 until n) {
                val m = sin(ringPhase).toFloat()
                x[i] = x[i] * (1f - p.ringMix) + x[i] * m * p.ringMix
                ringPhase += step
                if (ringPhase > 2 * PI) ringPhase -= 2 * PI
            }
        }
        if (p.drive > 0f) {
            val norm = 1f / softClip(p.drive)
            for (i in 0 until n) x[i] = softClip(x[i] * p.drive) * norm
        }
    }

    /** tanh-like saturation (Padé approximant, clamped): close to tanh, far cheaper per sample. */
    private fun softClip(v: Float): Float {
        val c = v.coerceIn(-3f, 3f)
        val c2 = c * c
        return c * (27f + c2) / (27f + 9f * c2)
    }

    /**
     * Two-tap delay-line pitch shifter (see the class comment), with the splice
     * point of each tap chosen by waveform match.
     *
     * A plain two-tap shifter lets the taps meet at whatever phase they happen
     * to have, so they partly cancel during each cross-fade: the voice warbles
     * and the pitch lands short of the target (measured 15-20 % short). Each
     * time a tap re-enters (at zero gain), it is placed up to [SEARCH_MS] later
     * where its waveform best matches the tap that is playing, the way WSOLA
     * time-stretchers splice. The voice stays smooth and the pitch exact.
     */
    internal class PitchShifter(rate: Int) {
        private val window = (rate * 0.024).toInt().coerceAtLeast(64)
        private val search = (rate * SEARCH_MS / 1000).coerceAtLeast(8)
        private val corrLen = (rate * 0.0025).toInt().coerceAtLeast(16)
        private val size = Integer.highestOneBit((window + search + corrLen) * 2) * 2
        private val mask = size - 1
        private val buf = FloatArray(size)
        private var write = 0
        private var phase = 0.0
        private var offA = 0
        private var offB = 0

        /** Average added delay in samples. */
        val latencySamples: Int get() = window / 2 + search / 2 + GUARD

        fun reset() { buf.fill(0f); write = 0; phase = 0.0; offA = 0; offB = 0 }

        fun process(x: FloatArray, n: Int, ratio: Float) {
            // Delay changes by (1 - ratio) samples per sample; phase is that
            // change as a fraction of the window.
            val dPhase = (1.0 - ratio) / window
            for (i in 0 until n) {
                buf[write] = x[i]
                val pa = phase
                val pb = if (pa < 0.5) pa + 0.5 else pa - 0.5
                // sin²(πa) from a table; the other tap's gain is cos²(πa) = 1 - that.
                val ga = FADE[(pa * FADE_N).toInt()]
                x[i] = tap(GUARD + pa * window + offA) * ga + tap(GUARD + pb * window + offB) * (1f - ga)
                write = (write + 1) and mask
                val before = phase
                phase += dPhase
                if (phase >= 1.0) phase -= 1.0 else if (phase < 0.0) phase += 1.0
                // Tap A re-enters when the phase wraps through 0/1; tap B when it crosses 0.5.
                if (kotlin.math.abs(phase - before) > 0.5) offA = bestOffset(phase, (phase + 0.5) % 1.0, offB)
                else if ((before < 0.5) != (phase < 0.5)) offB = bestOffset((phase + 0.5) % 1.0, phase, offA)
            }
        }

        /** Extra delay for a re-entering tap at [p] that best lines up with the playing tap at [q] + [qOff]. */
        private fun bestOffset(p: Double, q: Double, qOff: Int): Int {
            val other = write - (GUARD + q * window + qOff).toInt()
            val base = write - (GUARD + p * window).toInt()
            var best = 0; var bestScore = Double.NEGATIVE_INFINITY
            var off = 0
            while (off <= search) {
                val start = base - off
                var c = 0.0; var e = 1e-9
                for (k in 0 until corrLen) {
                    val a = buf[(start - k) and mask]
                    c += a * buf[(other - k) and mask]
                    e += a * a
                }
                val score = c / kotlin.math.sqrt(e)
                if (score > bestScore) { bestScore = score; best = off }
                off += 3
            }
            return best
        }

        /** Linear-interpolated read [delay] samples behind the write head. */
        private fun tap(delay: Double): Float {
            // + size keeps the position positive, so toInt() is a floor.
            val pos = write - delay + size
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            val a = buf[i0 and mask]
            return a + (buf[(i0 + 1) and mask] - a) * frac
        }

        private companion object {
            const val GUARD = 2
            const val SEARCH_MS = 8
            const val FADE_N = 1024
            /** sin²(πp) for p in [0, 1], indexed by p * FADE_N. */
            val FADE = FloatArray(FADE_N + 1) { sin(PI * it / FADE_N).let { s -> (s * s).toFloat() } }
        }
    }

        /** RBJ cookbook biquad, direct form I. */
    internal class Biquad(spec: Spec, rate: Int) {
        enum class Type { LOW_SHELF, HIGH_SHELF, PEAK, LOW_PASS, HIGH_PASS }
        data class Spec(val type: Type, val hz: Float, val gainDb: Float = 0f, val q: Float = 0.707f)

        private val b0: Double; private val b1: Double; private val b2: Double
        private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        init {
            val f = spec.hz.toDouble().coerceIn(20.0, rate * 0.45)
            val w = 2 * PI * f / rate
            val cw = cos(w); val sw = sin(w)
            val a = 10.0.pow(spec.gainDb / 40.0)
            val alpha = sw / (2 * spec.q)
            val c: DoubleArray = when (spec.type) {
                Type.LOW_PASS -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                Type.HIGH_PASS -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
                Type.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a, 1 + alpha / a, -2 * cw, 1 - alpha / a)
                Type.LOW_SHELF -> { val s = 2 * sqrt(a) * alpha; doubleArrayOf(
                    a * ((a + 1) - (a - 1) * cw + s), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - s),
                    (a + 1) + (a - 1) * cw + s, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - s) }
                Type.HIGH_SHELF -> { val s = 2 * sqrt(a) * alpha; doubleArrayOf(
                    a * ((a + 1) + (a - 1) * cw + s), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - s),
                    (a + 1) - (a - 1) * cw + s, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - s) }
            }
            b0 = c[0] / c[3]; b1 = c[1] / c[3]; b2 = c[2] / c[3]; a1 = c[4] / c[3]; a2 = c[5] / c[3]
        }

        fun process(x: FloatArray, n: Int) {
            for (i in 0 until n) {
                val v = x[i].toDouble()
                val y = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = v; y2 = y1; y1 = y
                x[i] = y.toFloat()
            }
        }
    }

    internal companion object {
        private fun ls(hz: Float, db: Float) = Biquad.Spec(Biquad.Type.LOW_SHELF, hz, db)
        private fun hs(hz: Float, db: Float) = Biquad.Spec(Biquad.Type.HIGH_SHELF, hz, db)
        private fun pk(hz: Float, db: Float, q: Float = 1f) = Biquad.Spec(Biquad.Type.PEAK, hz, db, q)
        private fun lp(hz: Float) = Biquad.Spec(Biquad.Type.LOW_PASS, hz)
        private fun hp(hz: Float) = Biquad.Spec(Biquad.Type.HIGH_PASS, hz)

        /**
         * The voices. Upward shifts also thin the low end, and downward ones
         * lift it, so a raised voice reads as smaller rather than as a sped-up
         * tape. Tuned by ear; the pitch of each is pinned by VoiceChangerTest.
         */
        val PRESETS: Map<AppVoiceChanger, Preset> = mapOf(
            AppVoiceChanger.BABY to Preset(7f, listOf(ls(220f, -5f), hs(3000f, 3f))),
            AppVoiceChanger.FEMALE to Preset(4f, listOf(ls(250f, -3f), pk(3000f, 2f))),
            AppVoiceChanger.MALE to Preset(-3f, listOf(ls(160f, 3f))),
            AppVoiceChanger.DEEP_MALE to Preset(-6f, listOf(ls(130f, 5f), hs(6000f, -3f))),
            AppVoiceChanger.NEUTRAL to Preset(2f, listOf(ls(200f, -2f), pk(1500f, -2f))),
            AppVoiceChanger.CARTOON to Preset(9f, listOf(pk(2000f, 4f))),
            AppVoiceChanger.CHIPMUNK to Preset(12f, listOf(hp(180f))),
            AppVoiceChanger.SHIN_CHAN to Preset(5f, listOf(pk(1200f, 6f, 2f))),
            AppVoiceChanger.GIRLISH_MAN to Preset(3f, listOf(hs(4000f, 3f))),
            AppVoiceChanger.IRON_LADY to Preset(1f, listOf(pk(2500f, 4f)), drive = 1.5f),
            AppVoiceChanger.MONSTER to Preset(-9f, listOf(ls(150f, 6f)), drive = 3f),
            AppVoiceChanger.ROBOT to Preset(0f, listOf(pk(1500f, 3f)), ringHz = 80f, ringMix = 0.7f),
            AppVoiceChanger.GROOT to Preset(-7f, listOf(lp(2500f), ls(200f, 4f))),
            AppVoiceChanger.DARTH_VADER to Preset(-5f, listOf(lp(3000f), pk(500f, 4f)), drive = 1.3f),
            AppVoiceChanger.PHONE to Preset(0f, listOf(hp(400f), lp(3200f)), drive = 2f),
        )
    }
}
