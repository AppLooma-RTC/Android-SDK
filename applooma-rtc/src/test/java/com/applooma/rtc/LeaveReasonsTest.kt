package com.applooma.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class LeaveReasonsTest {
    @Test fun mapsTransportNames() {
        assertEquals(AppLeaveReason.QUIT, LeaveReasons.fromEngineName("CLIENT_INITIATED"))
        assertEquals(AppLeaveReason.DROPPED, LeaveReasons.fromEngineName("SIGNAL_CLOSE"))
        assertEquals(AppLeaveReason.DROPPED, LeaveReasons.fromEngineName("STATE_MISMATCH"))
        assertEquals(AppLeaveReason.KICKED, LeaveReasons.fromEngineName("PARTICIPANT_REMOVED"))
        assertEquals(AppLeaveReason.DUPLICATE_IDENTITY, LeaveReasons.fromEngineName("DUPLICATE_IDENTITY"))
        assertEquals(AppLeaveReason.ROOM_CLOSED, LeaveReasons.fromEngineName("ROOM_DELETED"))
        assertEquals(AppLeaveReason.ROOM_CLOSED, LeaveReasons.fromEngineName("SERVER_SHUTDOWN"))
        assertEquals(AppLeaveReason.UNKNOWN, LeaveReasons.fromEngineName("UNKNOWN_REASON"))
        assertEquals(AppLeaveReason.UNKNOWN, LeaveReasons.fromEngineName(null))
    }

    @Test fun rosterDiffKeepsStayersQuiet() {
        val d = RosterDiff.of(before = listOf("a", "b", "c"), after = listOf("b", "c", "d"))
        assertEquals(listOf("a"), d.gone)
        assertEquals(listOf("d"), d.added)
    }

    @Test fun rosterDiffNoChange() {
        val d = RosterDiff.of(listOf("a"), listOf("a"))
        assertEquals(emptyList<String>(), d.gone)
        assertEquals(emptyList<String>(), d.added)
    }
}

class ConnectionReasonsTest {
    @Test fun holdsOnlyRecoverableLeaves() {
        assertEquals(true, LeaveReasons.holdsForReturn(AppLeaveReason.DROPPED))
        assertEquals(true, LeaveReasons.holdsForReturn(AppLeaveReason.UNKNOWN))
        assertEquals(true, LeaveReasons.holdsForReturn(AppLeaveReason.DUPLICATE_IDENTITY))
        assertEquals(false, LeaveReasons.holdsForReturn(AppLeaveReason.QUIT))
        assertEquals(false, LeaveReasons.holdsForReturn(AppLeaveReason.KICKED))
        assertEquals(false, LeaveReasons.holdsForReturn(AppLeaveReason.ROOM_CLOSED))
    }

    @Test fun mapsOwnDisconnectReasons() {
        assertEquals(AppConnectionReason.LEAVE_CHANNEL, ConnectionReasons.fromEngineName("CLIENT_INITIATED"))
        assertEquals(AppConnectionReason.KICKED, ConnectionReasons.fromEngineName("PARTICIPANT_REMOVED"))
        assertEquals(AppConnectionReason.DUPLICATE_IDENTITY, ConnectionReasons.fromEngineName("DUPLICATE_IDENTITY"))
        assertEquals(AppConnectionReason.ROOM_CLOSED, ConnectionReasons.fromEngineName("ROOM_DELETED"))
        assertEquals(AppConnectionReason.NETWORK_LOST, ConnectionReasons.fromEngineName("SIGNAL_CLOSE"))
        assertEquals(AppConnectionReason.UNKNOWN, ConnectionReasons.fromEngineName(null))
    }
}
