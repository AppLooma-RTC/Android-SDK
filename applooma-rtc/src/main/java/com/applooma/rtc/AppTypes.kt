package com.applooma.rtc

import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import org.json.JSONObject
import java.util.Date

/** Roles supported by AppLooma RTC channels. */
enum class AppRole { HOST, COHOST, AUDIENCE }

/**
 * How the engine treats audio for the whole session. Pick once, at create time.
 *
 * CALL routes voice through the phone's communication path: echo cancellation
 * tuned for speech, the earpiece by default, volume keys control the call.
 * MEDIA treats the session like a music player: wider bandwidth, the
 * loudspeaker by default, media volume keys. Voice rooms with music and live
 * streams sound better on MEDIA; a private call belongs on CALL.
 */
enum class AppAudioScenario { CALL, MEDIA }

/** Capture and encode settings for your own camera. */
data class AppVideoConfig @JvmOverloads constructor(
    /** 360, 540, 720 or 1080 — the short edge of a 16:9 frame. */
    val height: Int = 1080,
    val fps: Int = 30,
    /** Cap on the encoder's bitrate in bits per second; 0 = the preset's default. */
    val maxBitrate: Int = 0,
    /** Three layers so weak links still get a picture. Off = one layer only. */
    val simulcast: Boolean = true,
)

/** Engine-wide settings. All optional; the defaults are what most apps want. */
data class AppEngineOptions @JvmOverloads constructor(
    val audioScenario: AppAudioScenario = AppAudioScenario.CALL,
    val video: AppVideoConfig = AppVideoConfig(),
    /** Echo cancellation in software rather than the phone's own. Keep on unless you know why. */
    val softwareEchoCancellation: Boolean = true,
)

/** Which simulcast layer to receive from a remote camera. */
enum class AppRemoteQuality { LOW, MEDIUM, HIGH }

/** Where the in-room music player is. */
enum class AppMusicState { STOPPED, PLAYING, PAUSED }

/** Who is speaking and how loudly, 0..1. */
data class AppSpeaker(val uid: String, val level: Float)

/** Link quality for one participant, as the media server measures it. */
enum class AppNetworkQuality { EXCELLENT, GOOD, POOR, LOST, UNKNOWN }

/** A periodic snapshot of your own connection. Delivered by [AppEventHandler.onStats] every two seconds. */
data class AppStats(
    /** Round-trip time to the media server in milliseconds, or -1 if unknown. */
    val rttMs: Int,
    /** Fraction of outgoing packets lost since the last snapshot, 0..1. */
    val uploadLoss: Float,
    /** Fraction of incoming packets lost since the last snapshot, 0..1. */
    val downloadLoss: Float,
    val uploadBitrate: Int,
    val downloadBitrate: Int,
    val quality: AppNetworkQuality,
)

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

    /** Whether this user's microphone track is publishing. */
    val hasAudio: Boolean get() = p.isMicrophoneEnabled()

    /** Whether you have chosen not to hear this user (see AppEngine.muteRemoteUser). */
    @Volatile var isMutedLocally: Boolean = false
        internal set

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

    /** You are in the channel. Media and messages work from this moment. */
    open fun onJoined(localUid: String) {}
    /** The connection dropped and the engine is bringing it back; media pauses. */
    open fun onReconnecting() {}
    /** Back in the channel after a drop, with the roster refreshed. */
    open fun onReconnected() {}
    /**
     * Your role changed without leaving: your server promoted you to the stage
     * or sent you back to the audience. The engine has already enabled or
     * disabled your microphone to match.
     */
    open fun onRoleChanged(role: AppRole) {}
    /** Who is speaking and how loudly. Fires with every change, at most every 300 ms. */
    open fun onSpeakerLevels(speakers: List<AppSpeaker>) {}
    /** Another participant's link quality changed. */
    open fun onNetworkQuality(uid: String, quality: AppNetworkQuality) {}
    /** Your own connection, every two seconds while joined. */
    open fun onStats(stats: AppStats) {}
    /** A remote user turned their camera or microphone on or off. */
    open fun onUserMediaChanged(user: AppRemoteUser, video: Boolean, audio: Boolean) {}
    open fun onMusicStateChanged(state: AppMusicState) {}
    /** The music file reached its end (after the last loop). */
    open fun onMusicFinished() {}
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
