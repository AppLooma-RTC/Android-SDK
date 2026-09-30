package com.applooma.rtc

/**
 * Pure rules that keep the camera stream inside what the phone can encode.
 * No Android types, so every rule is covered by JVM unit tests (VideoLoadTest).
 */

/**
 * Keeps the encoded picture at or below the tier. The camera does not always
 * offer the requested size (asked for 960x540, a phone delivers 960x720), and
 * without a cap the encoder then works on the full captured frame.
 */
internal object ResolutionCap {
    /**
     * The total scale-down (capture / encoded) the top layer needs so its short
     * side does not exceed [tierShort].
     *
     * [observedShort] is the short side the top layer is encoding now, at
     * [currentScale]. Returns the new scale, or null when no change is needed.
     * A smaller encoded size than expected is trusted (and the scale lowered)
     * only when the encoder reports no quality limitation, because under a CPU
     * or bandwidth limit the encoder shrinks the picture on its own.
     */
    fun scaleFor(observedShort: Int, currentScale: Double, tierShort: Int, limited: Boolean): Double? {
        if (observedShort <= 0 || tierShort <= 0 || currentScale <= 0.0) return null
        val captureShort = observedShort * currentScale
        val wanted = maxOf(1.0, captureShort / tierShort)
        val tooBig = observedShort > tierShort * 1.05
        val tooSmall = observedShort < tierShort * 0.9 && currentScale > 1.0 && !limited
        if (!tooBig && !tooSmall) return null
        return if (kotlin.math.abs(wanted - currentScale) < 0.02) null else wanted
    }
}

/** A phone that should start below the normal software tier. */
internal object WeakDevice {
    fun isWeak(cores: Int, lowRam: Boolean, memoryClassMb: Int): Boolean =
        lowRam || (cores in 1..4) || (memoryClassMb in 1..128)
}

/**
 * Steps a software-encoded camera down when the encoder cannot keep up:
 * the sent frame rate below [lowFps] for [downAfterMs] moves one step down the
 * ladder; a step back up needs [upAfterMs] of frame rate at [upFps] or more
 * with no CPU limit, and is allowed only once per session so a phone that is
 * marginal does not oscillate.
 */
internal class CpuTierController(
    private val ladder: List<Int>,
    private val lowFps: Int = 12,
    private val upFps: Int = 18,
    private val downAfterMs: Long = 10_000,
    private val upAfterMs: Long = 60_000,
) {
    var level = 0
        private set
    private var lowSince = -1L
    private var goodSince = -1L
    private var upsUsed = 0

    /** Target short side of the current level. */
    val height: Int get() = ladder[level]

    fun reset() { level = 0; lowSince = -1L; goodSince = -1L; upsUsed = 0 }

    /** Returns the new level when it changed, else null. */
    fun onSample(nowMs: Long, fps: Int, cpuLimited: Boolean): Int? {
        if (fps < lowFps) {
            goodSince = -1L
            if (lowSince < 0) lowSince = nowMs
            if (nowMs - lowSince >= downAfterMs && level < ladder.size - 1) {
                level++; lowSince = -1L
                return level
            }
            return null
        }
        lowSince = -1L
        if (fps >= upFps && !cpuLimited && level > 0 && upsUsed < 1) {
            if (goodSince < 0) goodSince = nowMs
            if (nowMs - goodSince >= upAfterMs) { level--; upsUsed++; goodSince = -1L; return level }
        } else goodSince = -1L
        return null
    }

    companion object {
        /** The heights below [base] the controller may step down to. */
        fun ladderFrom(base: Int): List<Int> = listOf(base) + listOf(540, 360).filter { it < base }
    }
}
