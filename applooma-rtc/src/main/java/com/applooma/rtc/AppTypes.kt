package com.applooma.rtc

import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack

/** Roles supported by AppLooma RTC channels. */
enum class AppRole { HOST, COHOST, AUDIENCE }

/** Options for joining a channel. */
data class AppJoinOptions @JvmOverloads constructor(
    val role: AppRole = AppRole.HOST,
    /** Auto-enable camera on join (ignored for audience). */
    val camera: Boolean = false,
    /** Auto-enable microphone on join (ignored for audience). */
    val microphone: Boolean = true,
)

/** A remote user in the channel. */
class AppRemoteUser internal constructor(private val p: RemoteParticipant) {
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
abstract class AppEventHandler {
    open fun onUserJoined(user: AppRemoteUser) {}
    open fun onUserLeft(user: AppRemoteUser) {}
    open fun onTrackSubscribed(user: AppRemoteUser) {}
    open fun onDisconnected(reason: String?) {}
    open fun onDataReceived(data: ByteArray, from: AppRemoteUser?) {}
    open fun onGiftReceived(gift: AppGiftEvent) {}
    open fun onActiveSpeakersChanged(uids: List<String>) {}
    open fun onError(error: Throwable) {}
}

/** A virtual gift broadcast to the room (sent via your server's POST /v1/gifts/send). */
data class AppGiftEvent(
    val txId: String,
    val giftId: String,
    val giftName: String,
    val imageUrl: String,
    val coinPrice: Int,
    val sender: String,
    val receiver: String,
    val quantity: Int,
) {
    companion object {
        /** Parses a data-channel payload; returns null if it isn't a applooma.gift event. */
        @JvmStatic
        fun tryParse(data: ByteArray): AppGiftEvent? = try {
            val json = org.json.JSONObject(String(data, Charsets.UTF_8))
            if (json.optString("type") != "applooma.gift") null else {
                val gift = json.getJSONObject("gift")
                AppGiftEvent(
                    txId = json.getString("txId"),
                    giftId = gift.getString("id"),
                    giftName = gift.getString("name"),
                    imageUrl = gift.getString("imageUrl"),
                    coinPrice = gift.optInt("coinPrice", 0),
                    sender = json.getString("sender"),
                    receiver = json.getString("receiver"),
                    quantity = json.optInt("quantity", 1),
                )
            }
        } catch (_: Exception) { null }
    }
}
