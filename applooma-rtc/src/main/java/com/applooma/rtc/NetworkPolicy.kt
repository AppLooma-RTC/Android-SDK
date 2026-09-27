package com.applooma.rtc

/**
 * Pure decision rules for network fallback, token expiry and the encoder
 * self-test. No Android types, so they are covered by plain
 * JVM unit tests (see NetworkPolicyTest).
 */

/**
 * Hysteresis for one direction of network fallback: poor for [enterMs] enters
 * the fallback, good for [exitMs] leaves it. Anything in between (neither poor
 * nor good) holds the current state and resets both timers.
 */
internal class FallbackPolicy(
    private val enterMs: Long = 4_000,
    private val exitMs: Long = 10_000,
) {
    var active = false
        private set
    private var poorSince = -1L
    private var goodSince = -1L

    /** Returns the new state when it changed on this sample, null otherwise. */
    fun onSample(nowMs: Long, poor: Boolean, good: Boolean): Boolean? {
        if (poor) {
            goodSince = -1L
            if (poorSince < 0) poorSince = nowMs
            if (!active && nowMs - poorSince >= enterMs) { active = true; return true }
        } else if (good) {
            poorSince = -1L
            if (goodSince < 0) goodSince = nowMs
            if (active && nowMs - goodSince >= exitMs) { active = false; return false }
        } else {
            poorSince = -1L
            goodSince = -1L
        }
        return null
    }

    fun reset() { active = false; poorSince = -1L; goodSince = -1L }

    companion object {
        /** Loss above this fraction counts as poor even when the server still says good. */
        const val POOR_LOSS = 0.10f

        fun isPoor(q: AppNetworkQuality, loss: Float) = q == AppNetworkQuality.POOR || q == AppNetworkQuality.LOST || loss > POOR_LOSS
        fun isGood(q: AppNetworkQuality, loss: Float) = (q == AppNetworkQuality.EXCELLENT || q == AppNetworkQuality.GOOD) && loss <= POOR_LOSS / 2
    }
}

/** When the AUTO cloud proxy retries a failed join through the TLS 443 relay. */
internal object ProxyPolicy {
    private val NOT_NETWORK = Regex("401|403|unauthori[sz]ed|forbidden|permission|invalid token|token is expired|not allowed|already", RegexOption.IGNORE_CASE)
    private val NETWORK = Regex("time[d ]?out|timeout|network|transport|\\bice\\b|connection|connect|unreachable|socket|peer", RegexOption.IGNORE_CASE)

    /** A failed direct join that the relay may fix: a connection or network timeout, not a refused token. */
    fun shouldRetry(mode: AppCloudProxy, error: Throwable): Boolean {
        if (mode != AppCloudProxy.AUTO) return false
        var t: Throwable? = error
        val text = StringBuilder()
        while (t != null) { text.append(t.javaClass.simpleName).append(' ').append(t.message ?: "").append(' '); t = t.cause.takeIf { it !== t } }
        return isRetryable(text.toString())
    }

    fun isRetryable(text: String): Boolean = !NOT_NETWORK.containsMatchIn(text) && NETWORK.containsMatchIn(text)
}

/** Reads the expiry out of a join token without verifying it. */
internal object TokenExpiry {
    /** Warn this long before the token expires. */
    const val WARN_BEFORE_MS = 30_000L
    private val EXP = Regex(""""exp"\s*:\s*([0-9]+(?:\.[0-9]+)?)""")

    /** `exp` in epoch milliseconds, or null when the token carries none or is not a token. */
    fun expiresAtMs(token: String?): Long? {
        val parts = token?.split('.') ?: return null
        if (parts.size < 2) return null
        val json = decodeBase64Url(parts[1]) ?: return null
        // A plain scan rather than a JSON parser, so the rule runs in JVM unit tests as-is.
        val m = EXP.find(String(json, Charsets.UTF_8)) ?: return null
        val exp = m.groupValues[1].toDoubleOrNull() ?: return null
        return if (exp > 0) (exp * 1000).toLong() else null
    }

    /** Delay until the warning should fire; 0 when it is already due, null without an expiry. */
    fun warnDelayMs(token: String?, nowMs: Long): Long? =
        expiresAtMs(token)?.let { (it - WARN_BEFORE_MS - nowMs).coerceAtLeast(0L) }

    internal fun decodeBase64Url(s: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val clean = s.trimEnd('=').replace('+', '-').replace('/', '_')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}

/** What the encoder self-test observed, and the verdict on it. */
internal object SelfTestDecision {
    const val NO_OUTPUT = "no_output"
    const val NO_KEY_FRAME = "no_key_frame"
    const val ENCODER_ERROR = "encoder_error"
    const val TIMEOUT = "timeout"

    /** Output has to show up within this long of the first frame fed. */
    const val OUTPUT_WINDOW_MS = 2_000L
    /** The whole test, codec creation included, is abandoned after this long. */
    const val TOTAL_TIMEOUT_MS = 3_000L

    data class Observation(
        /** The codec threw while being created, configured, started or fed. */
        val error: Boolean,
        /** The test did not finish within [TOTAL_TIMEOUT_MS]. */
        val timedOut: Boolean,
        /** Parameter sets (a codec-config buffer or an output format with them) or a key frame was seen. */
        val sawConfigOrKeyFrame: Boolean,
        /** Bytes of encoded output within [OUTPUT_WINDOW_MS]. */
        val outputBytes: Long,
    )

    /** Null means the encoder passed; otherwise the failure reason. */
    fun verdict(o: Observation): String? = when {
        o.error -> ENCODER_ERROR
        o.outputBytes <= 0L && o.timedOut -> TIMEOUT
        o.outputBytes <= 0L -> NO_OUTPUT
        !o.sawConfigOrKeyFrame -> NO_KEY_FRAME
        else -> null
    }

    /** Whether the self-test still has to run for this SDK version, given what was stored after the last run. */
    fun shouldRun(enabled: Boolean, storedVersion: String?, sdkVersion: String): Boolean =
        enabled && storedVersion != sdkVersion
}
