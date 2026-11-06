package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    /* Encoder self-test verdict */

    private fun obs(error: Boolean = false, timedOut: Boolean = false, key: Boolean = true, bytes: Long = 1000) =
        SelfTestDecision.Observation(error, timedOut, key, bytes)

    @Test fun healthyEncoderPasses() = assertNull(SelfTestDecision.verdict(obs()))

    @Test fun outputWithKeyFramePassesEvenIfWindowRanOut() = assertNull(SelfTestDecision.verdict(obs(timedOut = true)))

    @Test fun errorWins() = assertEquals(SelfTestDecision.ENCODER_ERROR, SelfTestDecision.verdict(obs(error = true, bytes = 0, key = false)))

    @Test fun silentEncoderFails() {
        assertEquals(SelfTestDecision.NO_OUTPUT, SelfTestDecision.verdict(obs(bytes = 0, key = false)))
        assertEquals(SelfTestDecision.TIMEOUT, SelfTestDecision.verdict(obs(bytes = 0, key = false, timedOut = true)))
    }

    @Test fun outputWithoutKeyFrameFails() = assertEquals(SelfTestDecision.NO_KEY_FRAME, SelfTestDecision.verdict(obs(key = false)))

    @Test fun runsOncePerSdkVersion() {
        assertTrue(SelfTestDecision.shouldRun(true, null, "0.4.16"))
        assertTrue(SelfTestDecision.shouldRun(true, "0.4.15", "0.4.16"))
        assertFalse(SelfTestDecision.shouldRun(true, "0.4.16", "0.4.16"))
        assertFalse(SelfTestDecision.shouldRun(false, null, "0.4.16"))
    }

    /* Fallback hysteresis */

    @Test fun fallbackEntersAfterSustainedPoorAndLeavesAfterSustainedGood() {
        val p = FallbackPolicy(enterMs = 4_000, exitMs = 10_000)
        assertNull(p.onSample(0, poor = true, good = false))
        assertNull(p.onSample(2_000, poor = true, good = false))
        assertEquals(true, p.onSample(4_000, poor = true, good = false))
        assertNull(p.onSample(6_000, poor = true, good = false))
        assertNull(p.onSample(8_000, poor = false, good = true))
        assertNull(p.onSample(16_000, poor = false, good = true))
        assertEquals(false, p.onSample(18_000, poor = false, good = true))
        assertFalse(p.active)
    }

    @Test fun briefDipDoesNotFallBack() {
        val p = FallbackPolicy()
        assertNull(p.onSample(0, poor = true, good = false))
        assertNull(p.onSample(2_000, poor = false, good = true))
        assertNull(p.onSample(4_000, poor = true, good = false))
        assertNull(p.onSample(6_000, poor = false, good = false))
        assertFalse(p.active)
    }

    @Test fun poorAndGoodClassification() {
        assertTrue(FallbackPolicy.isPoor(AppNetworkQuality.POOR, 0f))
        assertTrue(FallbackPolicy.isPoor(AppNetworkQuality.GOOD, 0.2f))
        assertFalse(FallbackPolicy.isPoor(AppNetworkQuality.EXCELLENT, 0.01f))
        assertTrue(FallbackPolicy.isGood(AppNetworkQuality.EXCELLENT, 0.01f))
        assertFalse(FallbackPolicy.isGood(AppNetworkQuality.UNKNOWN, 0f))
    }

    /* Token expiry */

    private fun b64url(s: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var buffer = 0; var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 6) { bits -= 6; sb.append(alphabet[(buffer shr bits) and 0x3F]) }
        }
        if (bits > 0) sb.append(alphabet[(buffer shl (6 - bits)) and 0x3F])
        return sb.toString()
    }

    private fun token(payload: String) = "${b64url("{\"alg\":\"HS256\"}")}.${b64url(payload)}.sig"

    @Test fun readsExpiry() {
        assertEquals(1_900_000_000_000L, TokenExpiry.expiresAtMs(token("{\"exp\":1900000000,\"sub\":\"u?\"}")))
    }

    @Test fun warnsThirtySecondsBefore() {
        val t = token("{\"exp\":1000}")
        assertEquals(1_000_000L - 30_000L - 100_000L, TokenExpiry.warnDelayMs(t, 100_000L))
        assertEquals(0L, TokenExpiry.warnDelayMs(t, 990_000L))
    }

    @Test fun noExpiryOrGarbage() {
        assertNull(TokenExpiry.expiresAtMs(token("{\"sub\":\"x\"}")))
        assertNull(TokenExpiry.expiresAtMs("not-a-token"))
        assertNull(TokenExpiry.expiresAtMs("a.!!!.c"))
        assertNull(TokenExpiry.expiresAtMs(null))
    }

    /* Cloud proxy retry */

    @Test fun autoRetriesOnTimeout() = assertTrue(ProxyPolicy.shouldRetry(AppCloudProxy.AUTO, java.util.concurrent.TimeoutException("could not establish pc connection")))
    @Test fun autoRetriesOnSocketTimeout() = assertTrue(ProxyPolicy.shouldRetry(AppCloudProxy.AUTO, java.net.SocketTimeoutException("timeout")))
    @Test fun noRetryOnDisposedEngine() = assertFalse(ProxyPolicy.shouldRetry(AppCloudProxy.AUTO, IllegalStateException("PeerConnectionFactory has been disposed.")))
    @Test fun noRetryOnRefusedToken() = assertFalse(ProxyPolicy.shouldRetry(AppCloudProxy.AUTO, RuntimeException("connection failed: 401 unauthorized")))
    @Test fun noRetryWhenOff() = assertFalse(ProxyPolicy.shouldRetry(AppCloudProxy.OFF, java.util.concurrent.TimeoutException("timeout")))
    @Test fun noRetryWhenForced() = assertFalse(ProxyPolicy.shouldRetry(AppCloudProxy.FORCE_TLS_443, java.util.concurrent.TimeoutException("timeout")))
    @Test fun noRetryOnUnrelatedError() = assertFalse(ProxyPolicy.isRetryable("IllegalStateException Already in a channel"))
}
