package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    /* Ring buffer */

    @Test fun ringKeepsNewestLinesInOrder() {
        val r = DiagRing(3)
        for (i in 1..5) r.add("line $i", i.toLong())
        assertEquals(3, r.size())
        assertEquals(listOf("line 3", "line 4", "line 5"), r.entries().map { it.second })
    }

    @Test fun ringBelowCapacity() {
        val r = DiagRing(10)
        r.add("a", 0); r.add("b", 1)
        assertEquals(listOf("a", "b"), r.entries().map { it.second })
        r.clear()
        assertEquals(0, r.size())
        assertTrue(r.entries().isEmpty())
    }

    @Test fun snapshotFormatsScrubsAndCapsBytes() {
        val r = DiagRing(100)
        for (i in 0 until 50) r.add("x".repeat(90) + " $i", 0)
        val snap = r.snapshot({ it.uppercase() }, maxBytes = 1000)
        assertTrue(snap.sumOf { it.toByteArray().size + 1 } <= 1000)
        assertTrue(snap.last().endsWith(" 49"))
        assertTrue(snap.first().startsWith("00:00:00.000 XXX"))
        assertTrue(snap.size < 50)
    }

    /* Scrubber */

    @Test fun scrubsTokensUrlsAndSecrets() {
        val s = DiagScrubber.scrub("connect wss://rtc.example.com/rtc?access_token=abc&auto=1 token=eyJhbGciOiJI.eyJzdWIiOiJ1.c2lnbmF0dXJl secret: s3cr3t")
        assertFalse(s.contains("access_token=abc"))
        assertTrue(s.contains("wss://rtc.example.com/rtc?[redacted]"))
        assertFalse(s.contains("eyJ"))
        assertFalse(s.contains("s3cr3t"))
    }

    @Test fun scrubsNamesMetadataAndUserIds() {
        val s = DiagScrubber.scrub("Participant(uid=alice42, name=\"Alice Smith\", metadata={\"vip\":true}) displayName=Bob")
        assertFalse(s.contains("alice42"))
        assertFalse(s.contains("Alice"))
        assertFalse(s.contains("vip"))
        assertFalse(s.contains("Bob"))
        assertTrue(s.contains("uid=#"))
        // Same user id gives the same hash within a process.
        val a = DiagScrubber.scrub("uid=u1"); val b = DiagScrubber.scrub("uid=u1"); val c = DiagScrubber.scrub("uid=u2")
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test fun mapsEngineNames() {
        val engine = "live" + "kit"; val rtc = "web" + "rtc"
        val s = DiagScrubber.scrub("error: io.$engine.android.room.Room failed in org.$rtc.PeerConnection (${engine.uppercase()})")
        assertFalse(s.lowercase().contains(engine))
        assertFalse(s.lowercase().contains(rtc))
        assertTrue(s.contains("engine.room.Room"))
    }

    @Test fun capsLineLength() {
        assertEquals(1000, DiagScrubber.scrub("y".repeat(5000)).length)
    }

    /* Trigger decision */

    @Test fun optOutNeverUploads() {
        assertNull(DiagPolicy.decide(false, "user", setOf("fallback"), 9, true))
    }

    @Test fun nothingToReport() {
        assertNull(DiagPolicy.decide(true, null, emptySet(), 2, false))
    }

    @Test fun appRequestWins() {
        assertEquals(DiagPolicy.TRIGGER_APP to "user_report", DiagPolicy.decide(true, "user_report", setOf("fallback"), 0, true))
    }

    @Test fun problemsBeforeRemote() {
        assertEquals(DiagPolicy.TRIGGER_PROBLEM to "fallback,no_frames", DiagPolicy.decide(true, null, setOf("no_frames", "fallback"), 0, true))
    }

    @Test fun repeatedReconnectsAreAProblem() {
        assertEquals(DiagPolicy.TRIGGER_PROBLEM to "reconnects", DiagPolicy.decide(true, null, emptySet(), DiagPolicy.REPEATED_RECONNECTS, false))
        assertNull(DiagPolicy.decide(true, null, emptySet(), DiagPolicy.REPEATED_RECONNECTS - 1, false))
    }

    @Test fun remoteEnabledAlone() {
        assertEquals(DiagPolicy.TRIGGER_REMOTE to null, DiagPolicy.decide(true, null, emptySet(), 0, true))
    }

    @Test fun cleanReasonKeepsServerCharacters() {
        assertEquals("bad    thing", DiagPolicy.cleanReason("bad <> thing"))
        assertEquals("app", DiagPolicy.cleanReason("<<>>"))
        assertEquals(64, DiagPolicy.cleanReason("a".repeat(100)).length)
    }

    @Test fun deviceConfigParsesDiag() {
        assertTrue(DeviceVideoConfig.parse("""{"ttlSeconds":60,"video":{},"diag":{"enabled":true,"level":"debug"}}""")!!.diagEnabled)
        assertTrue(DeviceVideoConfig.parse("""{"diag":{"enabled":true}}""")!!.diagEnabled)
        assertFalse(DeviceVideoConfig.parse("""{"video":{}}""")!!.diagEnabled)
    }
}
