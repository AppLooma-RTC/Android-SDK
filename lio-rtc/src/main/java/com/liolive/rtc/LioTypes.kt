package com.liolive.rtc

import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack

/** Roles supported by Lio Live channels. */
enum class LioRole { HOST, COHOST, AUDIENCE }

/** Options for joining a channel. */
data class LioJoinOptions @JvmOverloads constructor(
    val role: LioRole = LioRole.HOST,
    /** Auto-enable camera on join (ignored for audience). */
    val camera: Boolean = false,
    /** Auto-enable microphone on join (ignored for audience). */
    val microphone: Boolean = true,
)

/** A remote user in the channel. */
class LioRemoteUser internal constructor(private val p: RemoteParticipant) {
    val uid: String get() = p.identity?.value ?: ""
    val displayName: String? get() = p.name
    val metadata: String? get() = p.metadata
    val isSpeaking: Boolean get() = p.isSpeaking

    /** First available video track for rendering (attach to a video renderer view). */
    val videoTrack: VideoTrack?
        get() = p.videoTrackPublications.firstNotNullOfOrNull { it.second as? VideoTrack }

    /** internal */
    val raw: RemoteParticipant get() = p
}

/**
 * Channel event callbacks. Override only what you need.
 * All callbacks are delivered on the main thread.
 */
abstract class LioEventHandler {
    open fun onUserJoined(user: LioRemoteUser) {}
    open fun onUserLeft(user: LioRemoteUser) {}
    open fun onTrackSubscribed(user: LioRemoteUser) {}
    open fun onDisconnected(reason: String?) {}
    open fun onDataReceived(data: ByteArray, from: LioRemoteUser?) {}
    open fun onActiveSpeakersChanged(uids: List<String>) {}
    open fun onError(error: Throwable) {}
}
