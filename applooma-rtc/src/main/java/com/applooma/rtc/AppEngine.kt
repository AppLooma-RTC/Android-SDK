package com.applooma.rtc

import android.content.Context
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioBufferCallbackDispatcher
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalAudioTrackOptions
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoQuality
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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
        data = payload?.let { obj -> obj.keys().asSequence().associateWith { obj.opt(it) } },
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
 *
 * One engine is one connection to one channel. To be in two channels at once
 * (a PK battle, watching a second stream) create a second engine; instances
 * are independent and any number may coexist.
 */
class AppEngine private constructor(
    context: Context,
    val appId: String,
    private val eventHandler: AppEventHandler?,
    private val options: AppEngineOptions,
) {
    private val mixer = MusicMixer(object : MusicMixer.Listener {
        override fun onMusicStateChanged(state: AppMusicState) { onMain { eventHandler?.onMusicStateChanged(state) } }
        override fun onMusicFinished() { onMain { eventHandler?.onMusicFinished() } }
        override fun onMusicError(error: Throwable) { onMain { eventHandler?.onError(error) } }
    })

    private val room: Room = LiveKit.create(
        context.applicationContext,
        options = roomOptions(options),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                audioOutputType = if (options.audioScenario == AppAudioScenario.MEDIA) AudioType.MediaAudioType() else AudioType.CallAudioType(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    if (options.softwareEchoCancellation) {
                        // The hardware canceller most phones ship is tuned for
                        // the earpiece and gives up on the loudspeaker.
                        builder.setUseHardwareAcousticEchoCanceler(false)
                        builder.setUseHardwareNoiseSuppressor(false)
                    }
                    // One hook serves music mixing and the recording gain.
                    builder.setAudioBufferCallback(AudioBufferCallbackDispatcher().also { it.bufferCallback = mixer })
                },
            ),
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var eventsJob: Job? = null
    private var statsJob: Job? = null
    private val users = LinkedHashMap<String, AppRemoteUser>()
    private val pendingData = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var lastRole: AppRole? = null
    private var playbackVolume = 100

    @Volatile
    var isJoined: Boolean = false
        private set

    companion object {
        private fun roomOptions(o: AppEngineOptions): RoomOptions {
            val preset = when (o.video.height) {
                360 -> VideoPreset169.H360
                540 -> VideoPreset169.H540
                720 -> VideoPreset169.H720
                else -> VideoPreset169.H1080
            }
            val encoding = if (o.video.maxBitrate > 0 || o.video.fps != 30)
                VideoEncoding(maxBitrate = if (o.video.maxBitrate > 0) o.video.maxBitrate else preset.encoding.maxBitrate, maxFps = o.video.fps)
            else preset.encoding
            return RoomOptions(
                adaptiveStream = true,
                dynacast = true,
                videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = preset.capture),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = encoding,
                    simulcast = o.video.simulcast,
                    // Every Android phone encodes and decodes H.264 in hardware;
                    // VP8 at 1080p is software on most of them.
                    videoCodec = "h264",
                ),
                audioTrackCaptureDefaults = LocalAudioTrackOptions(noiseSuppression = true, echoCancellation = true, autoGainControl = true),
                audioTrackPublishDefaults = AudioTrackPublishDefaults(audioBitrate = 96_000, dtx = false, red = true),
            )
        }

        /** Create an engine instance with your AppLooma RTC App ID (applooma.dev/dashboard). */
        @JvmStatic
        @JvmOverloads
        fun create(context: Context, appId: String, eventHandler: AppEventHandler? = null, options: AppEngineOptions = AppEngineOptions()): AppEngine {
            require(appId.isNotBlank()) { "AppEngine.create: appId is required" }
            return AppEngine(context, appId, eventHandler, options)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Join / leave                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Join a channel with a token from your server
     * (POST https://api.applooma.dev/v1/token → { token, wsUrl }).
     * Returns once you are in; [AppEventHandler.onJoined] fires at the same moment.
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
        lastRole = currentRole
        eventHandler?.onJoined(localUid)
        flushPending()
        startStats()

        if (options.role != AppRole.AUDIENCE) {
            if (options.microphone) room.localParticipant.setMicrophoneEnabled(true)
            if (options.camera) room.localParticipant.setCameraEnabled(true)
        }
    }

    /** Java-friendly fire-and-forget variant. Success arrives as [AppEventHandler.onJoined]. */
    fun joinChannelAsync(token: String, wsUrl: String, options: AppJoinOptions = AppJoinOptions()) {
        scope.launch {
            try { joinChannel(token, wsUrl, options) } catch (t: Throwable) { eventHandler?.onError(t) }
        }
    }

    fun leaveChannel() {
        mixer.stop()
        statsJob?.cancel(); statsJob = null
        room.disconnect()
        isJoined = false
        users.clear()
        pendingData.clear()
        eventsJob?.cancel()
    }

    /** Announces everyone already present, and their already-published tracks. */
    private fun seedExistingParticipants() {
        room.remoteParticipants.values.forEach { p ->
            val user = userFor(p)
            eventHandler?.onUserJoined(user)
            if (p.trackPublications.values.any { it.track != null }) eventHandler?.onTrackSubscribed(user)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Local media                                                         */
    /* ------------------------------------------------------------------ */

    // Fire-and-forget for Java callers, so a failure (missing CAMERA
    // permission, publish rejected) is reported rather than thrown from a
    // coroutine, where it would crash the host application.
    fun enableCamera(on: Boolean) = launchSafely { room.localParticipant.setCameraEnabled(on) }
    fun enableMicrophone(on: Boolean) = launchSafely { room.localParticipant.setMicrophoneEnabled(on) }

    /** Flip between the front and back cameras. Does nothing until the camera is on. */
    fun switchCamera() = launchSafely { (localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack)?.switchCamera() }

    /**
     * Route audio to the loudspeaker (true) or the earpiece (false).
     * A wired or Bluetooth headset, when present, keeps priority either way.
     */
    fun setSpeakerphone(on: Boolean) {
        val handler = room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler ?: return
        val wanted = handler.availableAudioDevices.firstOrNull {
            if (on) it is com.twilio.audioswitch.AudioDevice.Speakerphone else it is com.twilio.audioswitch.AudioDevice.Earpiece
        } ?: return
        handler.selectDevice(wanted)
    }

    /**
     * Your own role, as your server set it in the token. Changes when your
     * server promotes or demotes you (see the REST call in the docs); the
     * engine reports it through [AppEventHandler.onRoleChanged] and turns the
     * microphone on or off to match, with no rejoin and no gap in audio.
     */
    val currentRole: AppRole
        get() {
            val meta = try { JSONObject(room.localParticipant.metadata ?: "{}") } catch (_: Exception) { JSONObject() }
            return when (meta.optString("role")) { "host" -> AppRole.HOST; "cohost" -> AppRole.COHOST; else -> AppRole.AUDIENCE }
        }

    /** Microphone gain, 0..400 (100 = as captured). Applied before sending. */
    fun setRecordingVolume(volume: Int) { mixer.recordingVolume = volume.coerceIn(0, 400) }

    /** Volume of everyone you hear, 0..400 (100 = as sent). Per-user mutes stay in force. */
    fun setPlaybackVolume(volume: Int) {
        playbackVolume = volume.coerceIn(0, 400)
        users.values.forEach { if (!it.isMutedLocally) applyVolume(it, playbackVolume) }
    }

    /* ------------------------------------------------------------------ */
    /* Remote media                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Stop (or resume) hearing one user, on this device only. Everyone else
     * still hears them, and they are not told.
     */
    fun muteRemoteUser(uid: String, muted: Boolean) {
        val u = users[uid] ?: return
        u.isMutedLocally = muted
        applyVolume(u, if (muted) 0 else playbackVolume)
    }

    /** Volume for one user, 0..400. A muted user stays muted until unmuted. */
    fun setRemoteVolume(uid: String, volume: Int) {
        val u = users[uid] ?: return
        if (!u.isMutedLocally) applyVolume(u, volume.coerceIn(0, 400))
    }

    private fun applyVolume(u: AppRemoteUser, volume: Int) {
        u.raw.audioTrackPublications.forEach { (pub, track) ->
            (track as? RemoteAudioTrack)?.setVolume(volume / 100.0)
            // No track yet: remember the wish on the publication so it applies on arrival.
            if (track == null && volume == 0) (pub as? RemoteTrackPublication)?.setEnabled(false)
            else if (track == null) (pub as? RemoteTrackPublication)?.setEnabled(true)
        }
    }

    /**
     * Which simulcast layer to receive from one user's camera. Use LOW for a
     * small tile or a weak link, HIGH for the spotlight. The default is
     * automatic from the view size.
     */
    fun setRemoteVideoQuality(uid: String, quality: AppRemoteQuality) {
        val u = users[uid] ?: return
        val q = when (quality) { AppRemoteQuality.LOW -> VideoQuality.LOW; AppRemoteQuality.MEDIUM -> VideoQuality.MEDIUM; AppRemoteQuality.HIGH -> VideoQuality.HIGH }
        u.raw.videoTrackPublications.forEach { (pub, _) -> (pub as? RemoteTrackPublication)?.setVideoQuality(q) }
    }

    /**
     * Stop receiving one user's video while keeping their audio: the manual
     * audio-only fallback for a weak network or a tile scrolled off screen.
     */
    fun setRemoteVideoEnabled(uid: String, enabled: Boolean) {
        val u = users[uid] ?: return
        u.raw.videoTrackPublications.forEach { (pub, _) -> (pub as? RemoteTrackPublication)?.setEnabled(enabled) }
    }

    /* ------------------------------------------------------------------ */
    /* Music                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Play a music file into the room. Everyone hears it mixed with your
     * microphone; you hear it on this device. Any audio format the phone can
     * decode (mp3, aac/m4a, ogg, wav, flac). `path` is a local file path.
     *
     * @param loops how many times to play; 0 = forever.
     * @param startMs where in the file to begin.
     */
    @JvmOverloads
    fun playMusic(path: String, loops: Int = 1, startMs: Int = 0) = mixer.play(path, loops, startMs)
    fun pauseMusic() = mixer.pause()
    fun resumeMusic() = mixer.resume()
    fun stopMusic() = mixer.stop()
    fun seekMusic(ms: Int) = mixer.seek(ms)
    /** Current position in milliseconds. */
    val musicPosition: Int get() = mixer.position
    /** Length of the file in milliseconds, once known. */
    val musicDuration: Int get() = mixer.duration
    val musicState: AppMusicState get() = mixer.currentState

    /**
     * Two independent volumes, 0..400 each: how loud the music is in your own
     * ear (playout) and how loud it goes to the room (publish). 100 = as
     * decoded. A host can crank playout without shouting into the audience.
     */
    fun setMusicVolume(playout: Int, publish: Int) {
        mixer.playoutVolume = playout.coerceIn(0, 400)
        mixer.publishVolume = publish.coerceIn(0, 400)
    }

    /* ------------------------------------------------------------------ */
    /* Messaging                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Send a message to everyone in the channel.
     *
     * An audience member can call this even though they cannot publish video —
     * which is what makes live comments on a broadcast work.
     *
     * Safe to call before the join completes: the message waits and goes out
     * the moment you are in (up to 50 queued). Nothing is stored server-side,
     * so a message reaches whoever is present when it is sent.
     */
    @JvmOverloads
    fun sendMessage(text: String? = null, data: Map<String, Any?>? = null, reliable: Boolean = true): AppMessage {
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

    /** Broadcast raw bytes. Prefer sendMessage() unless you need your own format. Queued until joined. */
    @JvmOverloads
    fun sendData(data: ByteArray, reliable: Boolean = true) {
        if (!isJoined) {
            synchronized(pendingData) {
                if (pendingData.size >= 50) pendingData.removeFirst()
                pendingData.addLast(data to reliable)
            }
            return
        }
        launchSafely { publish(data, reliable) }
    }

    private suspend fun publish(data: ByteArray, reliable: Boolean) {
        room.localParticipant.publishData(
            data,
            if (reliable) io.livekit.android.room.track.DataPublishReliability.RELIABLE
            else io.livekit.android.room.track.DataPublishReliability.LOSSY,
        )
    }

    private fun flushPending() {
        val queued = synchronized(pendingData) { pendingData.toList().also { pendingData.clear() } }
        queued.forEach { (d, r) -> launchSafely { publish(d, r) } }
    }

    private fun launchSafely(block: suspend () -> Unit) {
        scope.launch { try { block() } catch (t: Throwable) { eventHandler?.onError(t) } }
    }

    private fun onMain(block: () -> Unit) { scope.launch { block() } }

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

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

    /** Look a user up by uid. */
    fun getUser(uid: String): AppRemoteUser? = users[uid]

    // Internal: AppVideoView needs the room to initialise a renderer, and the
    // local camera track to draw a preview. Neither belongs in the public API.
    internal val raw: Room get() = room

    internal val localVideoTrack: VideoTrack?
        get() = room.localParticipant.videoTrackPublications.firstNotNullOfOrNull { it.second as? VideoTrack }

    /** Prepares a renderer view to receive frames. Used by [AppVideoView]. */
    internal fun initRenderer(view: io.livekit.android.renderer.TextureViewRenderer) { room.initVideoRenderer(view) }

    // Views waiting for a track that has not arrived yet (see AppVideoView.attach).
    internal val trackWaiters = LinkedHashSet<AppVideoView>()

    private fun userFor(p: RemoteParticipant): AppRemoteUser = users.getOrPut(p.identity?.value ?: "") { AppRemoteUser(p) }

    /* ------------------------------------------------------------------ */
    /* Stats                                                               */
    /* ------------------------------------------------------------------ */

    private fun startStats() {
        statsJob?.cancel()
        statsJob = scope.launch {
            var prevOutLost = 0L; var prevOutSent = 0L; var prevInLost = 0L; var prevInRecv = 0L
            var prevBytesOut = 0L; var prevBytesIn = 0L; var prevAt = System.currentTimeMillis()
            while (isActive && isJoined) {
                delay(2000)
                try {
                    val reports = listOf(rtcStats(publisher = true), rtcStats(publisher = false))
                    var rtt = -1; var outLost = 0L; var outSent = 0L; var inLost = 0L; var inRecv = 0L; var bytesOut = 0L; var bytesIn = 0L
                    reports.flatMap { it?.statsMap?.values ?: emptyList() }.forEach { s ->
                        val m = s.members
                        when (s.type) {
                            "candidate-pair" -> if (m["state"] == "succeeded" && m["currentRoundTripTime"] != null) rtt = ((m["currentRoundTripTime"] as Double) * 1000).toInt()
                            "outbound-rtp" -> { bytesOut += (m["bytesSent"] as? Number)?.toLong() ?: 0; outSent += (m["packetsSent"] as? Number)?.toLong() ?: 0 }
                            "remote-inbound-rtp" -> outLost += (m["packetsLost"] as? Number)?.toLong() ?: 0
                            "inbound-rtp" -> { bytesIn += (m["bytesReceived"] as? Number)?.toLong() ?: 0; inRecv += (m["packetsReceived"] as? Number)?.toLong() ?: 0; inLost += (m["packetsLost"] as? Number)?.toLong() ?: 0 }
                        }
                    }
                    val now = System.currentTimeMillis()
                    val secs = ((now - prevAt) / 1000.0).coerceAtLeast(0.5)
                    val dOutSent = (outSent - prevOutSent).coerceAtLeast(0); val dOutLost = (outLost - prevOutLost).coerceAtLeast(0)
                    val dInRecv = (inRecv - prevInRecv).coerceAtLeast(0); val dInLost = (inLost - prevInLost).coerceAtLeast(0)
                    val stats = AppStats(
                        rttMs = rtt,
                        uploadLoss = if (dOutSent + dOutLost > 0) dOutLost.toFloat() / (dOutSent + dOutLost) else 0f,
                        downloadLoss = if (dInRecv + dInLost > 0) dInLost.toFloat() / (dInRecv + dInLost) else 0f,
                        uploadBitrate = (((bytesOut - prevBytesOut).coerceAtLeast(0) * 8) / secs).toInt(),
                        downloadBitrate = (((bytesIn - prevBytesIn).coerceAtLeast(0) * 8) / secs).toInt(),
                        quality = qualityOf(room.localParticipant.connectionQuality),
                    )
                    prevOutSent = outSent; prevOutLost = outLost; prevInRecv = inRecv; prevInLost = inLost; prevBytesOut = bytesOut; prevBytesIn = bytesIn; prevAt = now
                    eventHandler?.onStats(stats)
                } catch (_: Throwable) { /* stats are best effort */ }
            }
        }
    }

    private suspend fun rtcStats(publisher: Boolean): livekit.org.webrtc.RTCStatsReport? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                if (publisher) room.getPublisherRTCStats { cont.resume(it) {} } else room.getSubscriberRTCStats { cont.resume(it) {} }
            } catch (_: Throwable) { cont.resume(null) {} }
        }

    private fun qualityOf(q: ConnectionQuality): AppNetworkQuality = when (q) {
        ConnectionQuality.EXCELLENT -> AppNetworkQuality.EXCELLENT
        ConnectionQuality.GOOD -> AppNetworkQuality.GOOD
        ConnectionQuality.POOR -> AppNetworkQuality.POOR
        ConnectionQuality.LOST -> AppNetworkQuality.LOST
        else -> AppNetworkQuality.UNKNOWN
    }

    /* ------------------------------------------------------------------ */
    /* Events                                                              */
    /* ------------------------------------------------------------------ */

    private fun subscribeEvents() {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            var lastSpeakersAt = 0L
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
                    // A promotion or demotion rewrites the metadata; for the
                    // local participant that is a role change without rejoin.
                    is RoomEvent.ParticipantMetadataChanged -> {
                        if (event.participant === room.localParticipant) handleRoleChange()
                        eventHandler?.onAudienceChanged(audience)
                    }
                    is RoomEvent.ParticipantPermissionsChanged -> {
                        if (event.participant === room.localParticipant) handleRoleChange()
                    }
                    is RoomEvent.TrackSubscribed -> (event.participant as? RemoteParticipant)?.let { p ->
                        val u = userFor(p)
                        if (u.isMutedLocally) (event.track as? RemoteAudioTrack)?.setVolume(0.0)
                        else if (playbackVolume != 100) (event.track as? RemoteAudioTrack)?.setVolume(playbackVolume / 100.0)
                        eventHandler?.onTrackSubscribed(u)
                        if (event.track is VideoTrack) trackWaiters.toList().forEach { it.trackArrived(this@AppEngine, u) }
                    }
                    is RoomEvent.TrackPublished -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackUnpublished -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackMuted -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackUnmuted -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.LocalTrackSubscribed -> if (event.publication.track is VideoTrack) trackWaiters.toList().forEach { it.localTrackArrived(this@AppEngine) }
                    is RoomEvent.Reconnecting -> eventHandler?.onReconnecting()
                    is RoomEvent.Reconnected -> {
                        users.clear()
                        seedExistingParticipants()
                        eventHandler?.onReconnected()
                    }
                    is RoomEvent.Disconnected -> {
                        isJoined = false
                        mixer.stop()
                        statsJob?.cancel()
                        // Report everyone as gone; a stale roster after a
                        // disconnect made remoteUsers wrong until the next join.
                        users.values.forEach { eventHandler?.onUserLeft(it) }
                        users.clear()
                        eventHandler?.onDisconnected(event.reason?.name)
                    }
                    is RoomEvent.DataReceived -> {
                        val from = (event.participant as? RemoteParticipant)?.let { userFor(it) }
                        // Each frame is either ours or the customer's, never both.
                        val frame = try { JSONObject(String(event.data, Charsets.UTF_8)) } catch (_: Exception) { null }
                        when (frame?.optString("type")) {
                            MESSAGE_TYPE -> eventHandler?.onMessage(parseMessage(frame, from))
                            GIFT_TYPE -> AppGiftEvent.tryParse(event.data)?.let { eventHandler?.onGiftReceived(it) }
                            else -> eventHandler?.onDataReceived(event.data, from)
                        }
                    }
                    is RoomEvent.ActiveSpeakersChanged -> {
                        eventHandler?.onActiveSpeakersChanged(event.speakers.mapNotNull { it.identity?.value })
                        val now = System.currentTimeMillis()
                        if (now - lastSpeakersAt >= 300) {
                            lastSpeakersAt = now
                            eventHandler?.onSpeakerLevels(event.speakers.mapNotNull { s -> s.identity?.value?.let { AppSpeaker(it, s.audioLevel.coerceIn(0f, 1f)) } })
                        }
                    }
                    is RoomEvent.ConnectionQualityChanged -> {
                        val id = event.participant.identity?.value
                        if (id != null && event.participant !== room.localParticipant) eventHandler?.onNetworkQuality(id, qualityOf(event.quality))
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun handleRoleChange() {
        val role = currentRole
        if (role == lastRole) return
        lastRole = role
        val canPublish = room.localParticipant.permissions?.canPublish ?: (role != AppRole.AUDIENCE)
        if (role == AppRole.AUDIENCE || !canPublish) {
            try { room.localParticipant.setMicrophoneEnabled(false); room.localParticipant.setCameraEnabled(false) } catch (_: Throwable) {}
        } else {
            try { room.localParticipant.setMicrophoneEnabled(true) } catch (t: Throwable) { eventHandler?.onError(t) }
        }
        eventHandler?.onRoleChanged(role)
    }

    /** Release all resources. */
    fun destroy() {
        leaveChannel()
        scope.launch { room.release() }
    }
}
