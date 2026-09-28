package com.applooma.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class VideoFiltersTest {
    private fun plane(w: Int, h: Int, stride: Int, value: (Int, Int) -> Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(stride * h)
        for (j in 0 until h) for (i in 0 until w) b.put(j * stride + i, value(i, j).toByte())
        return b
    }

    private fun frame(w: Int, h: Int, y: (Int, Int) -> Int, stride: Int = w + 16): AppVideoFrame {
        val cw = w / 2; val ch = h / 2
        return AppVideoFrame(w, h, plane(w, h, stride, y), stride,
            plane(cw, ch, cw, { _, _ -> 128 }), cw, plane(cw, ch, cw, { _, _ -> 128 }), cw, 0, 0L)
    }

    private fun lumaOf(f: AppVideoFrame): IntArray =
        IntArray(f.width * f.height) { f.dataY.get((it / f.width) * f.strideY + it % f.width).toInt() and 0xFF }

    @Test fun darkFrameGetsBrighter() {
        val f = frame(64, 48, { i, j -> 20 + (i + j) % 10 })
        val before = lumaOf(f).average()
        val filter = AppVideoFilters(AppVideoEnhance(lowLight = true))
        repeat(30) { filter.process(f) } // adapt, as over a second of video
        val after = lumaOf(f).average()
        assertTrue("before=$before after=$after", after > before + 10)
    }

    @Test fun flatFrameUnchangedBySharpen() {
        val f = frame(64, 48, { _, _ -> 100 })
        val before = lumaOf(f)
        AppVideoFilters(AppVideoEnhance(sharpen = 1f)).process(f)
        assertArrayEquals(before, lumaOf(f))
    }

    @Test fun valuesStayInRangeAndPaddingUntouched() {
        val w = 64; val h = 48; val stride = w + 16
        val f = frame(w, h, { i, j -> if ((i / 3 + j / 3) % 2 == 0) 0 else 255 }, stride)
        for (j in 0 until h) for (i in w until stride) f.dataY.put(j * stride + i, 7)
        AppVideoFilters(AppVideoEnhance(beauty = 1f, lowLight = true, sharpen = 1f, warmth = 1f)).process(f)
        // Bytes are always 0..255; check the padding survived and chroma moved the right way.
        for (j in 0 until h) for (i in w until stride) assertEquals(7, f.dataY.get(j * stride + i).toInt())
        assertTrue((f.dataV.get(0).toInt() and 0xFF) > 128)
        assertTrue((f.dataU.get(0).toInt() and 0xFF) < 128)
    }

    @Test fun beautySmoothsNoiseButKeepsEdges() {
        val w = 64; val h = 48
        val f = frame(w, h, { i, j -> (if (i < w / 2) 60 else 200) + (if ((i + j) % 2 == 0) 4 else -4) })
        AppVideoFilters(AppVideoEnhance(beauty = 1f)).process(f)
        val y = lumaOf(f)
        assertTrue(y[10 * w + 5] < 110 && y[10 * w + w - 5] > 170) // edge kept
        assertTrue(kotlin.math.abs(y[10 * w + 5] - y[10 * w + 6]) < 8) // noise reduced
    }

    @Test fun neutralOptionsLeaveFrameAlone() {
        val f = frame(32, 32, { i, _ -> i * 7 })
        val before = lumaOf(f)
        AppVideoFilters(AppVideoEnhance()).process(f)
        assertArrayEquals(before, lumaOf(f))
    }
}
