package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WhiteLabelLogTest {
    @Test fun engineNameNeverReachesTheAppLog() {
        val raw = "V/engine: response: # livekit.LivekitRtc\$SignalResponse@1 (LiveKit, Live Kit, live_kit)"
        val out = AppEngine.whiteLabel(raw)
        assertFalse(out.contains("kit", ignoreCase = true))
        assertEquals("V/engine: response: # rtc.rtcRtc\$SignalResponse@1 (rtc, rtc, rtc)", out)
    }
}
