package com.applooma.rtc

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.Room
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/** Our own frames travel on the same channel as customer data, tagged so the two never mix. */
private const val MESSAGE_TYPE = "applooma.message"
private const val GIFT_TYPE = "applooma.gift"

private val messageCounter = AtomicLong(0)

private fun newMessageId(): String =
    "m_${System.currentTimeMillis().toString(36)}_${messageCounter.incrementAndGet().toString(36)}"

private fun isoInstant(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(date)

private fun parseMessage(frame: JSONObject, from: AppRemoteUser?): AppMessage {
    val payload = frame.optJSONObject("data")
    return AppMessage(
        id = frame.optString("id").ifEmpty { newMessageId() },
        text = if (frame.has("text")) frame.optString("text") else null,
        data = payload?.let { obj ->
            obj.keys().asSequence().associateWith { obj.opt(it) }
        },
        from = from,
        sentAt = Date(),
    )
}

/**
 * AppLooma RTC Android SDK — main entry point.
 * © AppLooma LLC
 *
 * Kotlin:
 * ```kotlin
 * val engine = AppEngine.create(context, "YOUR_APP_ID", eventHandler)
 * engine.joinChannel(token, wsUrl, AppJoinOptions(role = AppRole.HOST, camera = true))
 * ```
 *
 * Java:
 * ```java
 * AppEngine engine = AppEngine.create(context, "YOUR_APP_ID", handler);
 * engine.joinChannelAsync(token, wsUrl, options);
 * ```
 */
class AppEngine private constructor(
    context: Context,
    val appId: String,
    private val eventHandler: AppEventHandler?,
) {
    private val room: Room = LiveKit.create(context.applicationContext, options = QUALITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private val users = LinkedHashMap<String, AppRemoteUser>()

    @Volatile
    var isJoined: Boolean = false
        private set

    companion object {
        /**
         * What every call publishes unless the app says otherwise.
         *
         * 1080p with three simulcast layers: the server hands each viewer the
         * layer their screen and link can take, so a weak connection still gets
         * a picture and a strong one gets a sharp one. Voice at 96 kbps with
         * redundancy and no DTX — the difference between a phone call and being
         * in the room. Left to the engine's defaults, video published at 720p
         * and small tiles were served the lowest layer, which looked soft.
         */
        private val QUALITY = RoomOptions(
            adaptiveStream = true,
            dynacast = true,
            videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = VideoPreset169.H1080.capture),
            videoTrackPublishDefaults = VideoTrackPublishDefaults(
                videoEncoding = VideoPreset169.H1080.encoding,
                simulcast = true,
                // Every Android phone encodes and decodes H.264 in hardware;
                // VP8 at 1080p is software on most of them, and a starved
                // software encoder is what a blurry, stuttering call looks like.
                videoCodec = "h264",
            ),
            audioTrackCaptureDefaults = LocalAudioTrackOptions(
                noiseSuppression = true,
                echoCancellation = true,
                autoGainControl = true,
            ),
            audioTrackPublishDefaults = AudioTrackPublishDefaults(
                audioBitrate = 96_000,
                dtx = false,
                red = true,
            ),
        )

        /** Create an engine instance with your AppLooma RTC App ID (applooma.dev/dashboard). */
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, appId: String, eventHandler: AppEventHandler? = null): AppEngine {
            require(appId.isNotBlank()) { "AppEngine.create: appId is required" }
            return AppEngine(context, appId, eventHandler)
        }
    }

    /**
     * Join a channel with a token from your server
     * (POST https://api.applooma.dev/v1/token → { token, wsUrl }).
     */
    suspend fun joinChannel(token: String, wsUrl: String, options: AppJoinOptions = AppJoinOptions()) {
        check(!isJoined) { "Already in a channel — call leaveChannel() first" }
        subscribeEvents()
        room.connect(wsUrl, token)
        isJoined = true

        // Participants already in the room when we join never produce a
        // ParticipantConnected event; without this an audience member joining a
        // live stream sees nobody and never receives the host's video.
        seedExistingParticipants()

        if (options.role != AppRole.AUDIENCE) {
            if (options.microphone) room.localParticipant.setMicrophoneEnabled(true)
            if (options.camera) room.localParticipant.setCameraEnabled(true)
        }
    }

    /** Java-friendly fire-and-forget variant. */
    fun joinChannelAsync(token: String, wsUrl: String, options: AppJoinOptions = AppJoinOptions()) {
        scope.launch {
            try {
                joinChannel(token, wsUrl, options)
            } catch (t: Throwable) {
                eventHandler?.onError(t)
            }
        }
    }

    fun leaveChannel() {
        room.disconnect()
        isJoined = false
        users.clear()
        eventsJob?.cancel()
    }

    /** Announces everyone already present, and their already-published tracks. */
    private fun seedExistingParticipants() {
        room.remoteParticipants.values.forEach { p ->
            val user = userFor(p)
            eventHandler?.onUserJoined(user)
            if (p.trackPublications.values.any { it.track != null }) {
                eventHandler?.onTrackSubscribed(user)
            }
        }
    }

    // ---- Local media controls ----
    // These are fire-and-forget for Java callers, so a failure (missing CAMERA
    // permission, publish rejected) must be reported rather than thrown from a
    // coroutine, where it would crash the host application.
    fun enableCamera(on: Boolean) = launchSafely { room.localParticipant.setCameraEnabled(on) }
    fun enableMicrophone(on: Boolean) = launchSafely { room.localParticipant.setMicrophoneEnabled(on) }

    /** Flip between the front and back cameras. Does nothing until the camera is on. */
    fun switchCamera() = launchSafely {
        (localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack)?.switchCamera()
    }

    /**
     * Route audio to the loudspeaker (true) or the earpiece (false).
     *
     * The default routing prefers the earpiece, which is right for a phone
     * call and wrong for a video call held at arm's length. A wired or
     * Bluetooth headset, when present, keeps priority either way.
     */
    fun setSpeakerphone(on: Boolean) {
        val handler = room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler ?: return
        val wanted = handler.availableAudioDevices.firstOrNull {
            if (on) it is com.twilio.audioswitch.AudioDevice.Speakerphone
            else it is com.twilio.audioswitch.AudioDevice.Earpiece
        } ?: return
        handler.selectDevice(wanted)
    }

    /** Broadcast data to the channel (chat, signals, gifts). */
    /**
     * Send a message to everyone in the channel.
     *
     * An audience member can call this even though they cannot publish video —
     * which is what makes live comments on a broadcast work.
     *
     * Nothing is stored, so a message reaches whoever is present when it is sent.
     * Returns immediately; the message is delivered on a background coroutine.
     */
    @JvmOverloads
    fun sendMessage(
        text: String? = null,
        data: Map<String, Any?>? = null,
        reliable: Boolean = true,
    ): AppMessage {
        require(text != null || data != null) { "sendMessage needs text or data" }
        val message = AppMessage(id = newMessageId(), text = text, data = data)
        val frame = JSONObject().apply {
            put("type", MESSAGE_TYPE)
            put("id", message.id)
            text?.let { put("text", it) }
            data?.let { put("data", JSONObject(it)) }
            put("sentAt", isoInstant(message.sentAt))
        }
        sendData(frame.toString().toByteArray(Charsets.UTF_8), reliable)
        return message
    }

    /** Broadcast raw bytes. Prefer sendMessage() unless you need your own format. */
    @JvmOverloads
    fun sendData(data: ByteArray, reliable: Boolean = true) = launchSafely {
        room.localParticipant.publishData(
            data,
            if (reliable) io.livekit.android.room.track.DataPublishReliability.RELIABLE
            else io.livekit.android.room.track.DataPublishReliability.LOSSY
        )
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                eventHandler?.onError(t)
            }
        }
    }

    // ---- State ----
    val localUid: String get() = room.localParticipant.identity?.value ?: ""
    val channelName: String get() = room.name ?: ""
    val remoteUsers: List<AppRemoteUser> get() = users.values.toList()

    /** Everyone watching without publishing. The live audience of a broadcast. */
    val audience: List<AppRemoteUser> get() = users.values.filter { !it.isPublisher }

    /** How many people are watching. */
    val audienceCount: Int get() = users.values.count { !it.isPublisher }

    /** Everyone on stage: the host and any co-hosts, excluding you. */
    val hosts: List<AppRemoteUser> get() = users.values.filter { it.isPublisher }

    /** Whether your own camera is publishing right now — what a self-view tile should key on. */
    val hasLocalVideo: Boolean get() = localVideoTrack != null

    // Internal: AppVideoView needs the room to initialise a renderer, and the
    // local camera track to draw a preview. Neither belongs in the public API.
    internal val raw: Room get() = room

    internal val localVideoTrack: VideoTrack?
        get() = room.localParticipant.videoTrackPublications
            .firstNotNullOfOrNull { it.second as? VideoTrack }

    /** Prepares a renderer view to receive frames. Used by [AppVideoView]. */
    internal fun initRenderer(view: io.livekit.android.renderer.TextureViewRenderer) {
        room.initVideoRenderer(view)
    }

    private fun userFor(p: RemoteParticipant): AppRemoteUser =
        users.getOrPut(p.identity?.value ?: "") { AppRemoteUser(p) }

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> {
                        eventHandler?.onUserJoined(userFor(event.participant))
                        eventHandler?.onAudienceChanged(audience)
                    }
                    is RoomEvent.ParticipantDisconnected -> {
                        val u = users.remove(event.participant.identity?.value)
                        if (u != null) eventHandler?.onUserLeft(u)
                        eventHandler?.onAudienceChanged(audience)
                    }
                    // A promotion or demotion rewrites the token metadata, which
                    // is how someone moves between the stage and the audience.
                    is RoomEvent.ParticipantMetadataChanged ->
                        eventHandler?.onAudienceChanged(audience)
                    is RoomEvent.TrackSubscribed ->
                        (event.participant as? RemoteParticipant)?.let {
                            eventHandler?.onTrackSubscribed(userFor(it))
                        }
                    is RoomEvent.Disconnected -> {
                        isJoined = false
                        // Report everyone as gone; a stale roster after a
                        // disconnect made remoteUsers wrong until the next join.
                        users.values.forEach { eventHandler?.onUserLeft(it) }
                        users.clear()
                        eventHandler?.onDisconnected(event.reason?.name)
                    }
                    is RoomEvent.Reconnected -> {
                        users.clear()
                        seedExistingParticipants()
                    }
                    is RoomEvent.DataReceived -> {
                        val from = (event.participant as? RemoteParticipant)?.let { userFor(it) }

                        // Each frame is either ours or the customer's, never both.
                        // Reporting our own envelopes as raw data as well would
                        // deliver every comment twice to anyone handling both.
                        val frame = try {
                            JSONObject(String(event.data, Charsets.UTF_8))
                        } catch (_: Exception) {
                            null
                        }

                        when (frame?.optString("type")) {
                            MESSAGE_TYPE -> eventHandler?.onMessage(parseMessage(frame, from))
                            GIFT_TYPE ->
                                AppGiftEvent.tryParse(event.data)
                                    ?.let { eventHandler?.onGiftReceived(it) }
                            else -> eventHandler?.onDataReceived(event.data, from)
                        }
                    }
                    is RoomEvent.ActiveSpeakersChanged ->
                        eventHandler?.onActiveSpeakersChanged(
                            event.speakers.mapNotNull { it.identity?.value }
                        )
                    else -> {}
                }
            }
        }
    }

    /** Release all resources. */
    fun destroy() {
        leaveChannel()
        room.release()
    }
}
