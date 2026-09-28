package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceConfigTest {

    @Test fun parsesFullResponse() {
        val cfg = DeviceVideoConfig.parse(
            """{"version":123456789,"ttlSeconds":1800,"video":{"denyHardware":["video/avc","VIDEO/HEVC","bogus"],
               "forceCodec":"VP8","preferCodec":"h264","maxHeight":720,"maxFps":20,"maxBitrateKbps":1500,"disableSimulcast":true}}"""
        )!!
        assertEquals(setOf("video/avc", "video/hevc"), cfg.denyHardware)
        assertEquals("vp8", cfg.forceCodec)
        assertEquals("h264", cfg.preferCodec)
        assertEquals(720, cfg.maxHeight)
        assertEquals(20, cfg.maxFps)
        assertEquals(1500, cfg.maxBitrateKbps)
        assertTrue(cfg.disableSimulcast)
        assertEquals(1800L, cfg.ttlSeconds)
    }

    @Test fun emptyVideoMeansDefaults() {
        val cfg = DeviceVideoConfig.parse("""{"version":1,"ttlSeconds":3600,"video":{}}""")!!
        assertEquals(DeviceVideoConfig.EMPTY, cfg)
    }

    @Test fun ignoresBadFieldsAndRejectsBadBodies() {
        val cfg = DeviceVideoConfig.parse("""{"video":{"forceCodec":"mpeg2","maxHeight":-5,"maxFps":"20","disableSimulcast":"yes"}}""")!!
        assertNull(cfg.forceCodec)
        assertEquals(0, cfg.maxHeight)
        assertEquals(0, cfg.maxFps)
        assertFalse(cfg.disableSimulcast)
        assertNull(DeviceVideoConfig.parse("not json"))
        assertNull(DeviceVideoConfig.parse("[1,2]"))
        assertNull(DeviceVideoConfig.parse(null))
        assertNull(DeviceVideoConfig.parse("""{"video":{}"""))
    }

    @Test fun parsesEscapesAndNesting() {
        val v = MiniJson("""{"a":"x\"yA","b":[1,2.5,-3e2,null,true],"c":{"d":false}}""").parse() as Map<*, *>
        assertEquals("x\"yA", v["a"])
        assertEquals(listOf(1L, 2.5, -300.0, null, true), v["b"])
        assertEquals(mapOf("d" to false), v["c"])
    }

    @Test fun capsOnlyLower() {
        val base = DeviceCaps.Caps(height = 1080, fps = 30, maxBitrate = 3_500_000, simulcast = true)
        val cfg = DeviceVideoConfig(maxHeight = 720, maxFps = 20, maxBitrateKbps = 1500, disableSimulcast = true)
        assertEquals(DeviceCaps.Caps(720, 20, 1_500_000, false), DeviceCaps.apply(base, cfg))

        val low = DeviceCaps.Caps(height = 540, fps = 15, maxBitrate = 800_000, simulcast = false)
        assertEquals(low, DeviceCaps.apply(low, cfg))
        assertEquals(base, DeviceCaps.apply(base, DeviceVideoConfig.EMPTY))
    }

    @Test fun maxHeightSnapsDownToAPreset() {
        val base = DeviceCaps.Caps(1080, 30, 3_500_000, false)
        assertEquals(540, DeviceCaps.apply(base, DeviceVideoConfig(maxHeight = 600)).height)
        assertEquals(360, DeviceCaps.apply(base, DeviceVideoConfig(maxHeight = 200)).height)
        assertEquals(1080, DeviceCaps.apply(base, DeviceVideoConfig(maxHeight = 1080)).height)
    }

    @Test fun codecChoice() {
        val force = DeviceVideoConfig(forceCodec = "vp8", preferCodec = "h264")
        assertEquals(AppVideoCodec.VP8, DeviceCaps.codec(AppVideoCodec.H264, force))
        assertEquals(AppVideoCodec.VP8, DeviceCaps.codec(AppVideoCodec.AUTO, force))

        val prefer = DeviceVideoConfig(preferCodec = "vp8")
        assertEquals(AppVideoCodec.VP8, DeviceCaps.codec(AppVideoCodec.AUTO, prefer))
        assertEquals(AppVideoCodec.H264, DeviceCaps.codec(AppVideoCodec.H264, prefer))

        // vp9 cannot be published on Android: ignored.
        assertEquals(AppVideoCodec.AUTO, DeviceCaps.codec(AppVideoCodec.AUTO, DeviceVideoConfig(forceCodec = "vp9")))
        assertEquals(AppVideoCodec.AUTO, DeviceCaps.codec(AppVideoCodec.AUTO, DeviceVideoConfig.EMPTY))
    }

    @Test fun parsesCodec2H264Flags() {
        val on = DeviceVideoConfig.parse("""{"video":{"forceCodec":"h264","h264Codec2":true,"h264ByteBuffer":true}}""")!!
        assertTrue(on.h264Codec2); assertTrue(on.h264ByteBuffer)
        val off = DeviceVideoConfig.parse("""{"video":{"h264Codec2":"yes"}}""")!!
        assertFalse(off.h264Codec2); assertFalse(off.h264ByteBuffer)
        assertFalse(DeviceVideoConfig.EMPTY.h264Codec2)
    }
}
