package com.applooma.rtc

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clear Voice ([AppNoiseSuppression.CLEAR]): a spectral noise suppressor that
 * runs in the post-processing stage ([MicProcessor]) after the engine's own
 * suppressor.
 *
 * This is a DSP implementation, not a neural model: short-time spectrum
 * (sqrt-Hann window, 50 % overlap), a per-band noise floor that follows the
 * quietest level quickly and rises slowly (about 2 dB/s), and a Wiener-style
 * gain with over-subtraction and a -20 dB floor so the room never sounds
 * "dead". It removes steady noise (fans, hiss, hum, traffic rumble) far more
 * strongly than the standard suppressor. Sounds that change like speech pass;
 * a perfectly steady tone held for many seconds is eventually treated as
 * noise, as it would be by any stationary-noise suppressor.
 *
 * Works on any buffer length (the hop is the engine's 10 ms buffer) and is
 * scale-independent, so floats in ±1 or ±32768 range both work. Adds one hop
 * (10 ms) of latency. Not thread-safe: called from the audio thread only.
 */
internal class ClearVoiceDenoiser {
    private var hop = 0
    private var fftSize = 0
    private lateinit var window: FloatArray
    private lateinit var inFrame: FloatArray
    private lateinit var overlap: FloatArray
    private lateinit var re: FloatArray
    private lateinit var im: FloatArray
    private lateinit var smoothPower: FloatArray
    private lateinit var noise: FloatArray
    private lateinit var gain: FloatArray
    private var frames = 0

    private fun setup(n: Int) {
        hop = n
        fftSize = Integer.highestOneBit(2 * n - 1) shl 1
        val w = 2 * n
        // Periodic sqrt-Hann: analysis x synthesis windows sum to 1 at 50 % overlap.
        window = FloatArray(w) { i -> sqrt(0.5 - 0.5 * cos(2 * PI * i / w)).toFloat() }
        inFrame = FloatArray(w)
        overlap = FloatArray(n)
        re = FloatArray(fftSize); im = FloatArray(fftSize)
        val bins = fftSize / 2 + 1
        smoothPower = FloatArray(bins); noise = FloatArray(bins); gain = FloatArray(bins) { 1f }
        frames = 0
    }

    fun reset() { hop = 0 }

    /** Denoise [n] samples of [buf] in place (read at `get(i)`, written at `put(i, v)`). */
    fun process(buf: java.nio.FloatBuffer, n: Int) {
        if (n <= 1) return
        if (n != hop) setup(n)
        val w = 2 * hop
        System.arraycopy(inFrame, hop, inFrame, 0, hop)
        for (i in 0 until hop) inFrame[hop + i] = buf.get(i)

        java.util.Arrays.fill(re, 0f); java.util.Arrays.fill(im, 0f)
        for (i in 0 until w) re[i] = inFrame[i] * window[i]
        fft(re, im, false)

        val bins = fftSize / 2 + 1
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k]
            smoothPower[k] = if (frames == 0) p else POWER_SMOOTH * smoothPower[k] + (1 - POWER_SMOOTH) * p
        }
        // Start the floor at the median band level of the first frame, not at
        // each band's own level: a voice or tone already present at the start
        // would otherwise be taken for noise until the floor decays.
        val startFloor = if (frames == 0) smoothPower.copyOf().also { it.sort() }[bins / 2] else 0f
        for (k in 0 until bins) {
            // A little smoothing across neighbouring bands steadies the estimate.
            val s = (smoothPower[max(0, k - 1)] + smoothPower[k] + smoothPower[minOf(bins - 1, k + 1)]) / 3f
            noise[k] = when {
                frames == 0 -> startFloor
                s < noise[k] -> FALL * noise[k] + (1 - FALL) * s
                else -> minOf(noise[k] * RISE, s)
            }
            val g = if (s <= 0f) FLOOR else (1f - OVER * noise[k] / s).coerceIn(FLOOR, 1f)
            // Open fast, close a little slower: fewer "musical" artefacts.
            gain[k] = if (g > gain[k]) g else 0.6f * gain[k] + 0.4f * g
            re[k] *= gain[k]; im[k] *= gain[k]
            if (k in 1 until fftSize / 2) {
                re[fftSize - k] = re[k]; im[fftSize - k] = -im[k]
            }
        }
        fft(re, im, true)
        for (i in 0 until hop) {
            buf.put(i, overlap[i] + re[i] * window[i])
            overlap[i] = re[hop + i] * window[hop + i]
        }
        frames++
    }

    private companion object {
        const val POWER_SMOOTH = 0.7f
        /** Noise floor rise per 10 ms hop: about +2 dB/s. */
        const val RISE = 1.005f
        const val FALL = 0.7f
        /** Over-subtraction: the floor follows the quiet end of the noise, so scale it back up. */
        const val OVER = 3f
        /** -20 dB. */
        const val FLOOR = 0.1f

        /** In-place radix-2 FFT; [inverse] also scales by 1/n. */
        fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val ang = 2 * PI / len * (if (inverse) 1 else -1)
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val a = i + k; val b = a + len / 2
                        val xr = (re[b] * cr - im[b] * ci).toFloat()
                        val xi = (re[b] * ci + im[b] * cr).toFloat()
                        re[b] = re[a] - xr; im[b] = im[a] - xi
                        re[a] += xr; im[a] += xi
                        val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
            if (inverse) for (i in 0 until n) { re[i] /= n; im[i] /= n }
        }
    }
}
