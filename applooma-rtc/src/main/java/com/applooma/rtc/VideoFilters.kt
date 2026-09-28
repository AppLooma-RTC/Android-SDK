package com.applooma.rtc

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Built-in video enhancement for the local camera. Install with
 * [AppEngine.setVideoEnhance]; pass null there to turn it off.
 *
 * @param beauty skin smoothing strength, 0 (off) to 1. Edge-preserving, with a slight brightness lift.
 * @param lowLight brightens dark scenes adaptively (measured every frame, smoothed over time).
 * @param sharpen detail boost, 0 (off) to 1.
 * @param warmth colour temperature, -1 (cooler) to 1 (warmer); 0 is neutral.
 */
data class AppVideoEnhance(
    val beauty: Float = 0f,
    val lowLight: Boolean = false,
    val sharpen: Float = 0f,
    val warmth: Float = 0f,
) {
    /** True when these options would change nothing. */
    val isNeutral: Boolean get() = beauty <= 0f && !lowLight && sharpen <= 0f && warmth == 0f

    companion object {
        /** A good default for a front camera: gentle smoothing plus low-light help. */
        val Portrait = AppVideoEnhance(beauty = 0.5f, lowLight = true)
    }
}

/**
 * The CPU filter behind [AppVideoEnhance], an [AppVideoProcessor] working on
 * the I420 planes in place. Usable directly (e.g. chained in your own
 * processor); [AppEngine.setVideoEnhance] is the usual way in.
 *
 * Cost: one pass over the luma plane at full resolution plus two passes at
 * half resolution (only when beauty or sharpen is on), and one pass over each
 * chroma plane (only when warmth is on). All buffers are reused across frames.
 */
class AppVideoFilters(options: AppVideoEnhance) : AppVideoProcessor {
    @Volatile var options: AppVideoEnhance = sanitize(options)
        set(value) { field = sanitize(value) }

    // Reused working memory, grown when the frame size changes.
    private var yRow = ByteArray(0)
    private var half = IntArray(0)      // half-res luma (2x2 average)
    private var blur = IntArray(0)      // half-res box blur of `half`
    private var tmp = IntArray(0)
    private var cRow = ByteArray(0)
    private val lut = IntArray(256)
    private val edgeWeight = IntArray(256) // 0..256, by |blur - y|
    private val uLut = IntArray(256)
    private val vLut = IntArray(256)
    private var lastBeauty = -1f
    private var lastWarmth = Float.NaN
    private var gamma = 1.0            // smoothed low-light gamma

    override fun process(frame: AppVideoFrame): AppVideoFrame {
        val o = options
        if (o.isNeutral) { gamma = 1.0; return frame }
        val w = frame.width
        val h = frame.height
        if (o.beauty > 0f || o.sharpen > 0f || o.lowLight) processLuma(frame, w, h, o)
        if (o.warmth != 0f) processChroma(frame, (w + 1) / 2, (h + 1) / 2, o.warmth)
        return frame
    }

    private fun processLuma(f: AppVideoFrame, w: Int, h: Int, o: AppVideoEnhance) {
        val yBuf = f.dataY.duplicate()
        val stride = f.strideY
        val hw = w / 2
        val hh = h / 2
        val needBlur = (o.beauty > 0f || o.sharpen > 0f) && hw >= 2 && hh >= 2
        if (yRow.size < w * 2) yRow = ByteArray(w * 2)

        // Pass 1 (half-res): 2x2 average, plus a sparse luma mean for low light.
        var sum = 0L
        var count = 0
        if (needBlur) {
            val n = hw * hh
            if (half.size < n) { half = IntArray(n); blur = IntArray(n); tmp = IntArray(n) }
            val row = yRow
            for (j in 0 until hh) {
                yBuf.position(2 * j * stride); yBuf.get(row, 0, w)
                yBuf.position((2 * j + 1) * stride); yBuf.get(row, w, w)
                val base = j * hw
                var x = 0
                for (i in 0 until hw) {
                    val a = (row[x].toInt() and 0xFF) + (row[x + 1].toInt() and 0xFF) +
                        (row[w + x].toInt() and 0xFF) + (row[w + x + 1].toInt() and 0xFF)
                    val v = (a + 2) shr 2
                    half[base + i] = v
                    if ((i and 3) == 0 && (j and 3) == 0) { sum += v; count++ }
                    x += 2
                }
            }
            val radius = if (o.beauty > 0f) (2 + (o.beauty * 3f).roundToInt()) else 1
            boxBlur(half, blur, tmp, hw, hh, radius)
        } else if (o.lowLight) {
            for (j in 0 until h step 8) {
                val p = j * stride
                for (i in 0 until w step 8) { sum += yBuf.get(p + i).toInt() and 0xFF; count++ }
            }
        }

        buildLut(o, if (count > 0) sum.toDouble() / count else 128.0)
        if (o.beauty != lastBeauty) buildEdgeWeights(o.beauty)

        // Pass 2 (full-res): smoothing / sharpening against the half-res blur, then the tone curve.
        val beautyOn = o.beauty > 0f && needBlur
        val sharpQ = (o.sharpen * 1.5f * 256f).toInt() // amount in 1/256
        val sharpOn = sharpQ > 0 && needBlur
        val row = yRow
        val lutL = lut
        val ew = edgeWeight
        for (j in 0 until h) {
            val p = j * stride
            yBuf.position(p); yBuf.get(row, 0, w)
            if (!beautyOn && !sharpOn) {
                for (i in 0 until w) row[i] = lutL[row[i].toInt() and 0xFF].toByte()
            } else {
                val hj = minOf(j shr 1, hh - 1)
                val bBase = hj * hw
                for (i in 0 until w) {
                    val y = row[i].toInt() and 0xFF
                    val hi = minOf(i shr 1, hw - 1)
                    var v = y
                    if (beautyOn) {
                        val b = blur[bBase + hi]
                        val d = b - y
                        v = y + ((d * ew[if (d < 0) -d else d]) shr 8)
                    }
                    if (sharpOn) {
                        // Detail = pixel minus its 2x2 cell average (zero on flat areas).
                        v += ((y - half[bBase + hi]) * sharpQ) shr 8
                    }
                    if (v < 0) v = 0 else if (v > 255) v = 255
                    row[i] = lutL[v].toByte()
                }
            }
            yBuf.position(p); yBuf.put(row, 0, w)
        }
    }

    private fun processChroma(f: AppVideoFrame, cw: Int, ch: Int, warmth: Float) {
        if (warmth != lastWarmth) {
            lastWarmth = warmth
            val shift = (warmth * 14f).roundToInt() // warmer: less blue, more red
            for (k in 0 until 256) {
                uLut[k] = (k - shift).coerceIn(0, 255)
                vLut[k] = (k + shift).coerceIn(0, 255)
            }
        }
        if (cRow.size < cw) cRow = ByteArray(cw)
        applyPlane(f.dataU.duplicate(), f.strideU, cw, ch, uLut)
        applyPlane(f.dataV.duplicate(), f.strideV, cw, ch, vLut)
    }

    private fun applyPlane(buf: ByteBuffer, stride: Int, cw: Int, ch: Int, table: IntArray) {
        val row = cRow
        for (j in 0 until ch) {
            val p = j * stride
            buf.position(p); buf.get(row, 0, cw)
            for (i in 0 until cw) row[i] = table[row[i].toInt() and 0xFF].toByte()
            buf.position(p); buf.put(row, 0, cw)
        }
    }

    /** Tone curve: adaptive gamma for low light, then the beauty brightness lift. */
    private fun buildLut(o: AppVideoEnhance, mean: Double) {
        var target = 1.0
        if (o.lowLight) {
            // Aim the mean at ~110/255; only brighten, never beyond gamma 0.45.
            val m = mean.coerceIn(4.0, 250.0) / 255.0
            target = if (m < 0.43) (ln(0.43) / ln(m)).coerceIn(0.45, 1.0) else 1.0
        }
        gamma += (target - gamma) * 0.15 // smooth across frames to avoid flicker
        if (!o.lowLight && abs(gamma - 1.0) < 0.01) gamma = 1.0
        val lift = o.beauty * 10f
        for (k in 0 until 256) {
            var v = if (gamma == 1.0) k.toDouble() else 255.0 * (k / 255.0).pow(gamma)
            v += lift * (1.0 - k / 255.0) // lift shadows/mids, leave white alone
            lut[k] = v.roundToInt().coerceIn(0, 255)
        }
    }

    /** Blend weight by |blurred - original|: full on small (skin) differences, zero on edges. */
    private fun buildEdgeWeights(beauty: Float) {
        lastBeauty = beauty
        val strength = beauty.coerceIn(0f, 1f) * 0.85f
        val sigma = 6f + 14f * beauty
        for (d in 0 until 256) {
            val x = d / sigma
            val fall = if (x >= 2f) 0f else if (x <= 1f) 1f else 2f - x
            edgeWeight[d] = (256f * strength * fall).toInt()
        }
    }

    internal companion object {
        fun sanitize(o: AppVideoEnhance) = AppVideoEnhance(
            beauty = o.beauty.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f,
            lowLight = o.lowLight,
            sharpen = o.sharpen.takeIf { !it.isNaN() }?.coerceIn(0f, 1f) ?: 0f,
            warmth = o.warmth.takeIf { !it.isNaN() }?.coerceIn(-1f, 1f) ?: 0f,
        )

        /** Separable running-sum box blur with clamped edges. O(n) regardless of radius. */
        fun boxBlur(src: IntArray, dst: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
            val div = 2 * r + 1
            for (j in 0 until h) {
                val base = j * w
                var acc = 0
                for (k in -r..r) acc += src[base + k.coerceIn(0, w - 1)]
                for (i in 0 until w) {
                    tmp[base + i] = acc / div
                    acc += src[base + minOf(i + r + 1, w - 1)] - src[base + maxOf(i - r, 0)]
                }
            }
            for (i in 0 until w) {
                var acc = 0
                for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + i]
                for (j in 0 until h) {
                    dst[j * w + i] = acc / div
                    acc += tmp[minOf(j + r + 1, h - 1) * w + i] - tmp[maxOf(j - r, 0) * w + i]
                }
            }
        }

        /** Runs [first] then [second] on the same frame. */
        fun chain(first: AppVideoProcessor?, second: AppVideoProcessor?): AppVideoProcessor? = when {
            first == null -> second
            second == null -> first
            else -> AppVideoProcessor { frame -> second.process(first.process(frame)) }
        }
    }
}
