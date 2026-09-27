package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecHealthTest {

    private fun sample(t: Long, frames: Long, keys: Long, sent: Long = frames) = EncoderSample(t, frames, keys, sent)

    @Test fun healthyStreamNeverTrips() {
        val w = EncoderWatchdog().apply { reset(0) }
        var frames = 0L
        for (t in 2_000L..60_000L step 2_000L) {
            frames += 60
            assertNull(w.onSample(sample(t, frames, 1 + t / 10_000)))
        }
    }

    @Test fun noFramesTrips() {
        val w = EncoderWatchdog().apply { reset(0) }
        assertNull(w.onSample(sample(2_000, 0, 0)))
        assertNull(w.onSample(sample(4_000, 0, 0)))
        assertEquals(CodecHealth.REASON_NO_FRAMES, w.onSample(sample(6_000, 0, 0)))
        // Trips only once per reset.
        assertNull(w.onSample(sample(8_000, 0, 0)))
    }

    @Test fun framesStopMidStreamTrips() {
        val w = EncoderWatchdog().apply { reset(0) }
        assertNull(w.onSample(sample(2_000, 60, 1)))
        assertNull(w.onSample(sample(4_000, 120, 1)))
        assertNull(w.onSample(sample(6_000, 120, 1)))
        assertNull(w.onSample(sample(8_000, 120, 1)))
        assertEquals(CodecHealth.REASON_NO_FRAMES, w.onSample(sample(10_000, 120, 1)))
    }

    @Test fun framesWithoutKeyFrameTrips() {
        val w = EncoderWatchdog().apply { reset(0) }
        assertNull(w.onSample(sample(2_000, 60, 0)))
        assertNull(w.onSample(sample(4_000, 120, 0)))
        // Key frame late since 4 s after publish; unhealthy counted from publish.
        assertEquals(CodecHealth.REASON_NO_FRAMES, w.onSample(sample(6_000, 180, 0)))
    }

    @Test fun resetRestartsTheClock() {
        val w = EncoderWatchdog().apply { reset(0) }
        assertNull(w.onSample(sample(2_000, 0, 0)))
        w.reset(3_000)
        assertNull(w.onSample(sample(4_000, 30, 1)))
        assertNull(w.onSample(sample(6_000, 90, 1)))
    }

    @Test fun twoViewerReportsWithinWindowTrip() {
        val r = ViewerReports()
        assertFalse(r.onReport(0, "a", otherParticipants = 5))
        assertFalse(r.onReport(5_000, "a", otherParticipants = 5)) // same viewer twice is still one
        assertTrue(r.onReport(15_000, "b", otherParticipants = 5))
    }

    @Test fun reportsOutsideWindowDoNotAddUp() {
        val r = ViewerReports()
        assertFalse(r.onReport(0, "a", otherParticipants = 5))
        assertFalse(r.onReport(25_000, "b", otherParticipants = 5))
    }

    @Test fun singleReportInOneToOneTrips() {
        assertTrue(ViewerReports().onReport(0, "a", otherParticipants = 1))
    }

    @Test fun oneFallbackPerSession() {
        val g = FallbackGate()
        assertTrue(g.tryAcquire())
        assertFalse(g.tryAcquire())
        g.reset()
        assertTrue(g.tryAcquire())
    }

    @Test fun verdictExpiresAndFollowsSdkVersion() {
        val v = CodecVerdict("maker model", "c2.vendor.avc.encoder", "1.0.0", 1_000)
        assertTrue(v.isValid(1_000 + 60_000, "maker model", "c2.vendor.avc.encoder", "1.0.0"))
        assertFalse(v.isValid(1_000 + CodecVerdict.MAX_AGE_MS, "maker model", "c2.vendor.avc.encoder", "1.0.0"))
        assertFalse(v.isValid(2_000, "maker model", "c2.vendor.avc.encoder", "1.0.1"))
        assertFalse(v.isValid(2_000, "maker model", "other.encoder", "1.0.0"))
        assertEquals(v, CodecVerdict.decode(v.encode()))
        assertNull(CodecVerdict.decode("garbage"))
    }

    @Test fun internalTopics() {
        assertTrue(CodecHealth.isInternalTopic(CodecHealth.REPORT_TOPIC))
        assertFalse(CodecHealth.isInternalTopic("chat"))
        assertFalse(CodecHealth.isInternalTopic(null))
    }
}
