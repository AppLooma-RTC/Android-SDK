package com.applooma.rtc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H264StreamFixerTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private val sc4 = b(0, 0, 0, 1)
    private val sc3 = b(0, 0, 1)
    private val spsCb = b(0x67, 0x42, 0xE0, 0x1F, 0xDA, 0x01)
    private val spsHigh = b(0x67, 0x64, 0x00, 0x29, 0xAC, 0x2B)
    private val pps = b(0x68, 0xCE, 0x3C, 0x80)
    private val idr = b(0x65, 0x88, 0x84, 0x21)
    private val slice = b(0x41, 0x9A, 0x02, 0x03)
    private val aud = b(0x09, 0xF0)
    private val sei = b(0x06, 0x05, 0x10, 0x80)

    private fun au(vararg nals: ByteArray, three: Boolean = false): ByteArray =
        nals.fold(ByteArray(0)) { acc, n -> acc + (if (three) sc3 else sc4) + n }

    @Test fun splitsFourAndThreeByteStartCodes() {
        val stream = sc4 + spsCb + sc3 + pps + sc4 + idr
        val nals = H264StreamFixer.split(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(spsCb, nals[0]); assertArrayEquals(pps, nals[1]); assertArrayEquals(idr, nals[2])
        assertEquals(listOf(7, 8, 5), nals.map { H264StreamFixer.type(it) })
    }

    @Test fun splitDropsTrailingZerosAndEmptyInput() {
        val nals = H264StreamFixer.split(sc4 + slice + b(0, 0) + sc4 + idr)
        assertArrayEquals(slice, nals[0])
        assertTrue(H264StreamFixer.split(ByteArray(0)).isEmpty())
        assertTrue(H264StreamFixer.split(b(1, 2, 3)).isEmpty())
    }

    @Test fun deltaFramePassesThroughUnchanged() {
        val f = H264StreamFixer()
        val input = au(slice)
        val r = f.process(input)
        assertSame(input, r.bytes); assertFalse(r.isKeyFrame); assertFalse(r.changed)
    }

    @Test fun prependsCachedSpsPpsToBareIdr() {
        val f = H264StreamFixer()
        val first = f.process(au(spsCb, pps, idr))
        assertTrue(first.isKeyFrame); assertFalse(first.changed)
        f.process(au(slice))
        val r = f.process(au(idr))
        assertTrue(r.isKeyFrame); assertTrue(r.changed)
        assertArrayEquals(au(spsCb, pps, idr), r.bytes)
    }

    @Test fun parameterSetsAloneAreCachedForTheNextIdr() {
        val f = H264StreamFixer()
        val cfg = f.process(au(spsCb, pps))
        assertFalse(cfg.isKeyFrame)
        assertArrayEquals(au(spsCb, pps, idr), f.process(au(idr)).bytes)
    }

    @Test fun idrWithoutAnyCachedSetsIsLeftAlone() {
        val r = H264StreamFixer().process(au(idr))
        assertTrue(r.isKeyFrame); assertFalse(r.changed)
    }

    @Test fun onlyMissingSetIsPrepended() {
        val f = H264StreamFixer()
        f.process(au(spsCb, pps))
        assertArrayEquals(au(spsCb, pps, idr), f.process(au(pps, idr)).bytes)
    }

    @Test fun stripsAudAndSei() {
        val f = H264StreamFixer()
        val r = f.process(au(aud, sei, slice))
        assertTrue(r.changed); assertFalse(r.isKeyFrame)
        assertArrayEquals(au(slice), r.bytes)
        val k = f.process(au(aud, spsCb, pps, sei, idr, three = true))
        assertTrue(k.isKeyFrame)
        assertArrayEquals(au(spsCb, pps, idr), k.bytes)
    }

    @Test fun detectsProfiles() {
        val cb = H264StreamFixer.parseProfile(spsCb)!!
        assertEquals("42e01f", cb.profileLevelId); assertTrue(cb.isConstrainedBaseline)
        val high = H264StreamFixer.parseProfile(spsHigh)!!
        assertEquals("640029", high.profileLevelId); assertFalse(high.isConstrainedBaseline)
        assertFalse(H264StreamFixer.parseProfile(b(0x67, 0x42, 0x00, 0x1F))!!.isConstrainedBaseline) // plain baseline
        assertTrue(H264StreamFixer.parseProfile(b(0x67, 0x4D, 0xC0, 0x1F))!!.isConstrainedBaseline)  // main with set0+set1
        assertNull(H264StreamFixer.parseProfile(pps))
        assertNull(H264StreamFixer.parseProfile(b(0x67, 0x42)))
    }

    @Test fun reportsProfileOnceUntilItChanges() {
        val seen = ArrayList<String>()
        val f = H264StreamFixer { seen += it.profileLevelId }
        f.process(au(spsCb, pps, idr)); f.process(au(spsCb, pps, idr))
        f.process(au(spsHigh, pps, idr))
        assertEquals(listOf("42e01f", "640029"), seen)
        assertEquals("640029", f.profile!!.profileLevelId)
    }

    @Test fun inspectSummarisesAStream() {
        val i = H264StreamFixer.inspect(au(spsHigh, pps, idr) + au(slice) + au(idr))
        assertTrue(i.hasSps); assertTrue(i.hasPps); assertEquals(2, i.idrCount)
        assertEquals("640029", i.profile!!.profileLevelId)
        assertFalse(SelfTestDecision.bitstreamOk(i))
        assertTrue(SelfTestDecision.bitstreamOk(H264StreamFixer.inspect(au(spsCb, pps, idr))))
        assertFalse(SelfTestDecision.bitstreamOk(H264StreamFixer.inspect(au(spsCb, idr))))
        assertFalse(SelfTestDecision.bitstreamOk(H264StreamFixer.inspect(au(spsCb, pps, slice))))
    }

    @Test fun selfTestVerdictFailsBadBitstream() {
        val ok = SelfTestDecision.Observation(false, false, true, 1000)
        assertNull(SelfTestDecision.verdict(ok))
        assertEquals(SelfTestDecision.BAD_BITSTREAM, SelfTestDecision.verdict(ok.copy(bitstreamChecked = true, bitstreamOk = false)))
        assertNull(SelfTestDecision.verdict(ok.copy(bitstreamChecked = true, bitstreamOk = true)))
        assertEquals("0.5.0+c2", SelfTestDecision.versionKey("0.5.0", true))
        assertEquals("0.5.0", SelfTestDecision.versionKey("0.5.0", false))
    }
}
