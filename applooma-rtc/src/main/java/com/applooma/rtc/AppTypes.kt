package com.applooma.rtc

import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import org.json.JSONObject
import java.util.Date

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

/**
 * A message sent to everyone in the channel.
 *
 * Messages ride the same channel as the media, so they arrive with the same
 * latency and need no second connection. Nothing is stored: a message reaches
 * whoever is in the channel at the time.
 */
data class AppMessage(
    /** Unique to this message. Useful as a list key and for de-duplicating. */
    val id: String,
    /** What was sent, when sendMessage was given a string. */
    val text: String? = null,
    /** What was sent, when it was given a map. */
    val data: Map<String, Any?>? = null,
    /** Who sent it. Null if it came from your own server. */
    val from: AppRemoteUser? = null,
    /** The sender's clock, not ours — do not order messages by it alone. */
    val sentAt: Date = Date(),
)

/** A remote user in the channel. */
class AppRemoteUser internal constructor(private val p: RemoteParticipant) {
    val uid: String get() = p.identity?.value ?: ""
    val displayName: String? get() = p.name
    val isSpeaking: Boolean get() = p.isSpeaking

    /**
     * What this user is allowed to do, decided by the token their server minted.
     * An AUDIENCE member can watch and send messages but cannot publish.
     */
    val role: AppRole
        get() = when (parsed().optString("role")) {
            "host" -> AppRole.HOST
            "cohost" -> AppRole.COHOST
            else -> AppRole.AUDIENCE
        }

    /** Whether this user can publish. Convenient for laying out a stage. */
    val isPublisher: Boolean get() = role != AppRole.AUDIENCE

    /** The metadata your own server put in the token, with our fields stripped out. */
    val attributes: Map<String, Any?>
        get() {
            val json = parsed()
            return json.keys().asSequence()
                .filter { it != "appId" && it != "role" }
                .associateWith { json.opt(it) }
        }

    /** The raw metadata string. Prefer [attributes]. */
    val metadata: String? get() = p.metadata

    private var cachedRaw: String? = null
    private var cachedValue: JSONObject? = null

    /** Parsing runs once per metadata string, not once per read. */
    private fun parsed(): JSONObject {
        val raw = p.metadata
        val cached = cachedValue
        if (cached != null && cachedRaw == raw) return cached
        val value = try {
            if (raw.isNullOrEmpty()) JSONObject() else JSONObject(raw)
        } catch (_: Exception) {
            // Not ours — a customer may put anything here.
            JSONObject()
        }
        cachedRaw = raw
        cachedValue = value
        return value
    }

    /**
     * Whether this participant is currently publishing a camera.
     *
     * A participant is announced before their track arrives, so this is false
     * for a moment after they join.
     */
    val hasVideo: Boolean get() = videoTrack != null

    // Internal: rendering goes through AppVideoView, which needs these. Keeping
    // them out of the public API keeps the transport layer's types off a
    // consumer's compile classpath.
    internal val videoTrack: VideoTrack?
        get() = p.videoTrackPublications.firstNotNullOfOrNull { it.second as? VideoTrack }

    internal val raw: RemoteParticipant get() = p
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
    /** Someone sent a message with sendMessage(). */
    open fun onMessage(message: AppMessage) {}

    /**
     * The audience changed — someone started or stopped watching.
     * Carries the whole list, so a viewer count can be rendered from it directly.
     */
    open fun onAudienceChanged(audience: List<AppRemoteUser>) {}

    /** Raw bytes from sendData(). Platform frames are not reported here. */
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
