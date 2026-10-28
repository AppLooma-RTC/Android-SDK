package com.applooma.rtc

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * Voice style ([AppVoiceStyle]) and room acoustics ([AppAudioEffect]): the two
 * effect stages that run after the voice changer, in that order, inside
 * VoiceChanger.process. Pure Kotlin on FloatArray in ±1, checked by
 * VoiceEffectsTest.
 *
 * A voice style is tone shaping: RBJ biquads plus a gentle compressor that
 * keeps the level even, and for a few styles a short room. AUTOTUNE snaps the
 * pitch to the nearest semitone with the formant-preserving PSOLA stage, so
 * the voice stays the speaker's own while the melody locks to notes.
 *
 * Room acoustics is a Freeverb-style reverb (eight damped combs into four
 * allpasses, Jezar's tuning scaled to the sample rate) with a pre-delay,
 * optional early reflections and a filter on the reverb only, so the dry
 * voice keeps its clarity. The send is mono, so the "stereo" and "3D" rooms
 * create width and movement in time and level rather than across channels.
 */
internal object VoiceEffects {

    /** One voice style. */
    internal data class Style(
        val filters: List<VoiceChanger.Biquad.Spec> = emptyList(),
        val comp: Boolean = true,
        /** Short room under the voice; 0 = none. */
        val roomWet: Float = 0f,
        val autotune: Boolean = false,
    )

    /** One room. */
    internal data class Room(
        val feedback: Float,
        val damp: Float,
        val wet: Float,
        val preDelayMs: Int = 0,
        /** Filters on the reverb only (keep it out of the low end, or darken it). */
        val wetFilters: List<VoiceChanger.Biquad.Spec> = emptyList(),
        /** Filters on the whole output (phonograph). */
        val outFilters: List<VoiceChanger.Biquad.Spec> = emptyList(),
        /** Early reflections: delay in ms to gain. */
        val taps: List<Pair<Int, Float>> = emptyList(),
        val drive: Float = 0f,
        /** Slow level movement between dry and wet, for a voice that travels around the listener. */
        val orbitHz: Float = 0f,
        val orbitDepth: Float = 0f,
        val dry: Float = 1f,
    )

    private fun ls(hz: Float, db: Float) = VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.LOW_SHELF, hz, db)
    private fun hs(hz: Float, db: Float) = VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.HIGH_SHELF, hz, db)
    private fun pk(hz: Float, db: Float, q: Float = 1f) = VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.PEAK, hz, db, q)
    private fun lp(hz: Float) = VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.LOW_PASS, hz)
    private fun hp(hz: Float) = VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.HIGH_PASS, hz)

    /** Tuned by ear on speech; see the docs table for what each is meant to sound like. */
    val STYLES: Map<AppVoiceStyle, Style> = mapOf(
        AppVoiceStyle.MAGNETIC to Style(listOf(hp(70f), ls(160f, 4f), pk(400f, -2f), pk(3000f, 2f))),
        AppVoiceStyle.FRESH to Style(listOf(hp(120f), pk(250f, -2f), pk(4000f, 2f), hs(8000f, 3f))),
        AppVoiceStyle.VITALITY to Style(listOf(hp(90f), pk(2500f, 4f), hs(7000f, 2f))),
        AppVoiceStyle.DEEP to Style(listOf(ls(120f, 6f), pk(300f, 1f), hs(5000f, -2f))),
        AppVoiceStyle.MELLOW to Style(listOf(ls(200f, 3f), pk(2500f, -2f), hs(4000f, -4f))),
        AppVoiceStyle.CLEAR to Style(listOf(hp(100f), pk(300f, -3f), pk(3500f, 3f), hs(10000f, 2f))),
        AppVoiceStyle.RESOUNDING to Style(listOf(ls(180f, 3f), pk(1000f, 2f)), roomWet = 0.12f),
        AppVoiceStyle.RINGING to Style(listOf(hp(120f), pk(2000f, 5f, 2f), hs(6000f, 3f))),
        AppVoiceStyle.POP to Style(listOf(ls(100f, 2f), pk(350f, -2f), hs(8000f, 3f)), roomWet = 0.1f),
        AppVoiceStyle.RNB to Style(listOf(ls(120f, 4f), pk(300f, 2f), hs(7000f, 1f)), roomWet = 0.14f),
        AppVoiceStyle.AUTOTUNE to Style(listOf(hp(90f), hs(6000f, 2f)), roomWet = 0.1f, autotune = true),
    )

    val ROOMS: Map<AppAudioEffect, Room> = mapOf(
        AppAudioEffect.KTV to Room(0.80f, 0.35f, 0.30f, 20, listOf(hp(200f))),
        AppAudioEffect.CONCERT to Room(0.87f, 0.30f, 0.32f, 45, listOf(hp(180f)), taps = listOf(30 to 0.2f, 55 to 0.12f)),
        AppAudioEffect.STUDIO to Room(0.62f, 0.50f, 0.14f, 8, listOf(hp(250f), lp(8000f))),
        AppAudioEffect.PHONOGRAPH to Room(0.55f, 0.6f, 0.08f, 0, outFilters = listOf(hp(300f), lp(3500f), pk(1200f, 4f)), drive = 1.8f),
        AppAudioEffect.STEREO to Room(0.72f, 0.40f, 0.22f, 10, listOf(hp(200f)), taps = listOf(7 to 0.35f, 13 to 0.28f, 19 to 0.2f)),
        AppAudioEffect.SPACIAL to Room(0.90f, 0.25f, 0.36f, 60, listOf(hp(200f)), taps = listOf(40 to 0.18f, 80 to 0.12f)),
        AppAudioEffect.ETHEREAL to Room(0.94f, 0.15f, 0.45f, 30, listOf(hp(300f), hs(5000f, 3f)), dry = 0.85f),
        AppAudioEffect.VOICE_3D to Room(0.78f, 0.35f, 0.26f, 15, listOf(hp(200f)), taps = listOf(11 to 0.3f, 23 to 0.2f), orbitHz = 0.25f, orbitDepth = 0.5f),
    )

    /** Feed-forward compressor: -24 dBFS threshold, 3:1, 5 ms attack, 120 ms release, +4 dB make-up. */
    internal class Compressor(rate: Int) {
        private val att = exp(-1.0 / (rate * 0.005)).toFloat()
        private val rel = exp(-1.0 / (rate * 0.120)).toFloat()
        private var env = 0f
        private var gain = 1f
        fun process(x: FloatArray, n: Int) {
            for (i in 0 until n) {
                val a = abs(x[i])
                env = if (a > env) att * env + (1 - att) * a else rel * env + (1 - rel) * a
                if (i and 15 == 0) {
                    val db = 20f * log10(env + 1e-9f)
                    val over = db - THRESHOLD
                    val gr = if (over > 0) -over * (1f - 1f / RATIO) else 0f
                    gain = 10f.pow((gr + MAKEUP) / 20f)
                }
                x[i] *= gain
            }
        }
        private companion object { const val THRESHOLD = -24f; const val RATIO = 3f; const val MAKEUP = 4f }
    }

    /** Freeverb-style mono reverb. Returns only the reverb (wet) signal into [out]. */
    internal class Reverb(private val rate: Int) {
        private val scale = rate / 44_100.0
        private val combs = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617).map { FloatArray((it * scale).toInt().coerceAtLeast(8)) }
        private val combIdx = IntArray(8)
        private val combStore = FloatArray(8)
        private val aps = intArrayOf(556, 441, 341, 225).map { FloatArray((it * scale).toInt().coerceAtLeast(4)) }
        private val apIdx = IntArray(4)
        var feedback = 0.8f
        var damp = 0.3f

        fun reset() {
            combs.forEach { it.fill(0f) }; aps.forEach { it.fill(0f) }
            combIdx.fill(0); apIdx.fill(0); combStore.fill(0f)
        }

        fun tick(input: Float): Float {
            val inp = input * 0.015f * 2f
            var out = 0f
            val d1 = damp; val d2 = 1f - damp
            for (c in 0 until 8) {
                val b = combs[c]; val k = combIdx[c]
                val y = b[k]
                combStore[c] = y * d2 + combStore[c] * d1
                b[k] = inp + combStore[c] * feedback
                combIdx[c] = if (k + 1 == b.size) 0 else k + 1
                out += y
            }
            for (a in 0 until 4) {
                val b = aps[a]; val k = apIdx[a]
                val bo = b[k]
                b[k] = out + bo * 0.5f
                out = bo - out
                apIdx[a] = if (k + 1 == b.size) 0 else k + 1
            }
            return out
        }
    }

    /** A room: pre-delay, early reflections, the reverb, its filters and the dry/wet mix. */
    internal class RoomStage(private val rate: Int) {
        private val reverb = Reverb(rate)
        private val delay = FloatArray(Integer.highestOneBit(rate / 5) * 2) // > 100 ms
        private val dmask = delay.size - 1
        private var w = 0
        private var room: Room? = null
        private var wetFilters: List<VoiceChanger.Biquad> = emptyList()
        private var outFilters: List<VoiceChanger.Biquad> = emptyList()
        private var wetBuf = FloatArray(960)
        private var phase = 0.0

        fun set(r: Room?) {
            if (r == room) return
            room = r
            reverb.reset(); delay.fill(0f); w = 0; phase = 0.0
            if (r == null) return
            reverb.feedback = r.feedback; reverb.damp = r.damp
            wetFilters = r.wetFilters.map { VoiceChanger.Biquad(it, rate) }
            outFilters = r.outFilters.map { VoiceChanger.Biquad(it, rate) }
        }

        fun process(x: FloatArray, n: Int) {
            val r = room ?: return
            if (wetBuf.size < n) wetBuf = FloatArray(n)
            val pre = (rate * r.preDelayMs / 1000).coerceAtMost(dmask)
            val taps = r.taps
            for (i in 0 until n) {
                delay[w] = x[i]
                var early = 0f
                for (t in taps) early += delay[(w - rate * t.first / 1000) and dmask] * t.second
                wetBuf[i] = reverb.tick(delay[(w - pre) and dmask]) + early
                w = (w + 1) and dmask
            }
            for (f in wetFilters) f.process(wetBuf, n)
            val step = 2 * PI * r.orbitHz / rate
            for (i in 0 until n) {
                var dryG = r.dry; var wetG = r.wet
                if (r.orbitDepth > 0f) {
                    val m = (0.5 + 0.5 * sin(phase)).toFloat() * r.orbitDepth
                    dryG *= 1f - m * 0.6f; wetG *= 1f + m
                    phase += step; if (phase > 2 * PI) phase -= 2 * PI
                }
                x[i] = x[i] * dryG + wetBuf[i] * wetG
            }
            if (r.drive > 0f) {
                val norm = 1f / clip(r.drive)
                for (i in 0 until n) x[i] = clip(x[i] * r.drive) * norm
            }
            for (f in outFilters) f.process(x, n)
        }

        private fun clip(v: Float): Float { val c = v.coerceIn(-3f, 3f); val c2 = c * c; return c * (27f + c2) / (27f + 9f * c2) }
    }

    /** A voice style: optional autotune, EQ, compressor, a short room. */
    internal class StyleStage(private val rate: Int) {
        private var style: Style? = null
        private var filters: List<VoiceChanger.Biquad> = emptyList()
        private val comp = Compressor(rate)
        private val tune = VoiceChanger.Psola(rate)
        private val room = RoomStage(rate)

        fun set(s: Style?) {
            if (s == style) return
            style = s
            tune.reset()
            if (s == null) { room.set(null); return }
            filters = s.filters.map { VoiceChanger.Biquad(it, rate) }
            room.set(if (s.roomWet > 0f) Room(0.70f, 0.45f, s.roomWet, 12, listOf(VoiceChanger.Biquad.Spec(VoiceChanger.Biquad.Type.HIGH_PASS, 250f))) else null)
        }

        fun process(x: FloatArray, n: Int) {
            val s = style ?: return
            if (s.autotune) tune.process(x, n, 1f, autotune = true)
            for (f in filters) f.process(x, n)
            if (s.comp) comp.process(x, n)
            room.process(x, n)
        }
    }
}
