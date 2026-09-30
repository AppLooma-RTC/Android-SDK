package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoLoadTest {

    @Test fun capturedLargerThanTierIsScaledDown() {
        // 540p tier, camera delivers 720x960 (short side 720) at scale 1.
        val s = ResolutionCap.scaleFor(observedShort = 720, currentScale = 1.0, tierShort = 540, limited = false)!!
        assertEquals(720.0 / 540.0, s, 1e-6)
        assertEquals(540.0, 720 / s, 1e-6)
    }

    @Test fun atTierNothingChanges() {
        assertNull(ResolutionCap.scaleFor(540, 720.0 / 540.0, 540, limited = false))
        assertNull(ResolutionCap.scaleFor(540, 1.0, 540, limited = false))
        assertNull(ResolutionCap.scaleFor(360, 1.0, 540, limited = false)) // smaller camera: never upscaled
    }

    @Test fun lowerTierIncreasesScale() {
        // Already scaled 720 -> 540; the CPU tier now wants 360.
        val s = ResolutionCap.scaleFor(540, 720.0 / 540.0, 360, limited = true)!!
        assertEquals(2.0, s, 1e-6)
    }

    @Test fun backUpLowersScaleOnlyWhenNotLimited() {
        val scale = 2.0 // 720 capture encoded at 360
        assertNull(ResolutionCap.scaleFor(360, scale, 540, limited = true))
        assertEquals(720.0 / 540.0, ResolutionCap.scaleFor(360, scale, 540, limited = false)!!, 1e-6)
    }

    @Test fun weakDevice() {
        assertTrue(WeakDevice.isWeak(4, false, 256))
        assertTrue(WeakDevice.isWeak(8, true, 256))
        assertTrue(WeakDevice.isWeak(8, false, 128))
        assertFalse(WeakDevice.isWeak(8, false, 256))
        assertFalse(WeakDevice.isWeak(0, false, 0))
    }

    @Test fun ladder() {
        assertEquals(listOf(720, 540, 360), CpuTierController.ladderFrom(720))
        assertEquals(listOf(540, 360), CpuTierController.ladderFrom(540))
        assertEquals(listOf(360), CpuTierController.ladderFrom(360))
    }

    @Test fun sustainedLowFpsStepsDownAfterTenSeconds() {
        val c = CpuTierController(listOf(540, 360))
        assertNull(c.onSample(0, 8, true))
        assertNull(c.onSample(8_000, 9, true))
        assertEquals(1, c.onSample(10_000, 7, true))
        assertEquals(360, c.height)
        // Bottom of the ladder: no further step.
        for (t in 12_000L..60_000L step 2_000L) assertNull(c.onSample(t, 5, true))
    }

    @Test fun briefDipDoesNotStepDown() {
        val c = CpuTierController(listOf(540, 360))
        assertNull(c.onSample(0, 8, true))
        assertNull(c.onSample(6_000, 8, true))
        assertNull(c.onSample(8_000, 20, false))
        assertNull(c.onSample(10_000, 8, true))
        assertNull(c.onSample(18_000, 8, true))
        assertEquals(0, c.level)
    }

    @Test fun stepsBackUpOnceAfterSixtySecondsGood() {
        val c = CpuTierController(listOf(540, 360))
        c.onSample(0, 6, true); assertEquals(1, c.onSample(10_000, 6, true))
        assertNull(c.onSample(20_000, 20, false))
        assertNull(c.onSample(60_000, 20, true)) // CPU limited: the clock restarts
        assertNull(c.onSample(62_000, 20, false))
        assertEquals(0, c.onSample(122_000, 20, false))
        // Down again, and no second step up in this session.
        c.onSample(130_000, 6, true); assertEquals(1, c.onSample(140_000, 6, true))
        for (t in 142_000L..400_000L step 2_000L) assertNull(c.onSample(t, 24, false))
        assertEquals(1, c.level)
    }
}
