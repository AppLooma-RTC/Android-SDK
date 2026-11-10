package com.applooma.rtc

/**
 * Why a remote user is no longer in the channel. Delivered with
 * [AppEventHandler.onUserLeft] and kept on [AppRemoteUser.leaveReason].
 *
 * A viewer's app typically ends the show at once on [QUIT] or [ROOM_CLOSED],
 * and waits a little on [DROPPED], since the host may come back.
 */
enum class AppLeaveReason {
    /** They left on purpose: leaveChannel() or the app was closed cleanly. */
    QUIT,
    /** Their connection died and did not come back within the server's grace period (about 20 s). */
    DROPPED,
    /** Your server removed them (POST /v1/rooms/{room}/participants/{identity}/remove). */
    KICKED,
    /** The same identity joined again from elsewhere, so this connection was replaced. */
    DUPLICATE_IDENTITY,
    /** The room itself ended. */
    ROOM_CLOSED,
    /** No reason was reported. */
    UNKNOWN,
}

/** Pure mapping from the transport's reason names, so it is unit-tested without Android. */
internal object LeaveReasons {
    /** Whether a leave with this reason may be a drop the user comes back from (held for the grace period). */
    fun holdsForReturn(reason: AppLeaveReason): Boolean = when (reason) {
        AppLeaveReason.DROPPED, AppLeaveReason.UNKNOWN, AppLeaveReason.DUPLICATE_IDENTITY -> true
        else -> false
    }

    fun fromEngineName(name: String?): AppLeaveReason = when (name) {
        "CLIENT_INITIATED" -> AppLeaveReason.QUIT
        "SIGNAL_CLOSE", "STATE_MISMATCH", "CONNECTION_TIMEOUT", "MIGRATION", "JOIN_FAILURE" -> AppLeaveReason.DROPPED
        "PARTICIPANT_REMOVED" -> AppLeaveReason.KICKED
        "DUPLICATE_IDENTITY" -> AppLeaveReason.DUPLICATE_IDENTITY
        "ROOM_DELETED", "ROOM_CLOSED", "SERVER_SHUTDOWN" -> AppLeaveReason.ROOM_CLOSED
        else -> AppLeaveReason.UNKNOWN
    }
}

/** Our own disconnect reason names, mapped for [AppEventHandler.onConnectionStateChanged]. */
internal object ConnectionReasons {
    fun fromEngineName(name: String?): AppConnectionReason = when (name) {
        "CLIENT_INITIATED" -> AppConnectionReason.LEAVE_CHANNEL
        "DUPLICATE_IDENTITY" -> AppConnectionReason.DUPLICATE_IDENTITY
        "PARTICIPANT_REMOVED" -> AppConnectionReason.KICKED
        "ROOM_DELETED", "ROOM_CLOSED", "SERVER_SHUTDOWN" -> AppConnectionReason.ROOM_CLOSED
        "SIGNAL_CLOSE", "STATE_MISMATCH", "CONNECTION_TIMEOUT", "MIGRATION" -> AppConnectionReason.NETWORK_LOST
        "JOIN_FAILURE" -> AppConnectionReason.JOIN_FAILED
        else -> AppConnectionReason.UNKNOWN
    }
}

/**
 * What changed in the roster while we were reconnecting. Pure, so the diff is
 * unit-tested; the engine only supplies the two identity sets.
 */
internal data class RosterDiff(val gone: List<String>, val added: List<String>) {
    companion object {
        fun of(before: Collection<String>, after: Collection<String>): RosterDiff {
            val a = after.toSet(); val b = before.toSet()
            return RosterDiff(gone = before.filter { it !in a }, added = after.filter { it !in b })
        }
    }
}
