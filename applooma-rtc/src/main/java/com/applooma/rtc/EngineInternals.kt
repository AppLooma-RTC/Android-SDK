package com.applooma.rtc

import android.content.Context
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.track.LocalVideoTrack
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.RtpSender
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoProcessor
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoSource

/**
 * Access to two parts of a local camera track that the engine does not
 * expose: its video source (for a frame processor) and its RTP sender (to
 * change bitrate without a republish). Looked up by type, not by name, so an
 * app's code shrinker renaming the fields does not break it.
 */
internal object TrackAccess {
    private val sourceField by lazy {
        try { LocalVideoTrack::class.java.declaredFields.firstOrNull { it.type == VideoSource::class.java }?.apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val senderMethod by lazy {
        try { LocalVideoTrack::class.java.declaredMethods.firstOrNull { it.returnType == RtpSender::class.java && it.parameterTypes.isEmpty() }?.apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val transceiverField by lazy {
        try { LocalVideoTrack::class.java.declaredFields.firstOrNull { it.type == RtpTransceiver::class.java }?.apply { isAccessible = true } } catch (_: Throwable) { null }
    }
    private val roomEngineField by lazy {
        try { io.livekit.android.room.Room::class.java.declaredFields.firstOrNull { it.type == io.livekit.android.room.RTCEngine::class.java }?.apply { isAccessible = true } } catch (_: Throwable) { null }
    }

    fun source(track: LocalVideoTrack): VideoSource? = try { sourceField?.get(track) as? VideoSource } catch (_: Throwable) { null }

    /**
     * The track's RTP sender. In an app built with a code shrinker the
     * engine's internal sender getter could be inlined away (the reason live
     * bitrate changes were "not reachable" in production), so three routes
     * are tried in order: that getter, the track's transceiver field, and the
     * room's publisher connection, matched by track id. The route name
     * (senderWithRoute) goes to the log.
     */
    fun sender(track: LocalVideoTrack, room: io.livekit.android.room.Room? = null): RtpSender? = senderWithRoute(track, room)?.first

    fun senderWithRoute(track: LocalVideoTrack, room: io.livekit.android.room.Room?): Pair<RtpSender, String>? {
        try { (senderMethod?.invoke(track) as? RtpSender)?.let { return it to "getter" } } catch (_: Throwable) {}
        try { (transceiverField?.get(track) as? RtpTransceiver)?.sender?.let { return it to "transceiver" } } catch (_: Throwable) {}
        if (room != null) try {
            val id = track.rtcTrack.id()
            val engine = roomEngineField?.get(room) as? io.livekit.android.room.RTCEngine
            val pc = engine?.getPublisherPeerConnection()
            pc?.senders?.firstOrNull { s -> try { s.track()?.id() == id } catch (_: Throwable) { false } }?.let { return it to "connection" }
        } catch (_: Throwable) {}
        return null
    }
}

/** Bridges an [AppVideoProcessor] onto a capture source. One per source. */
internal class ProcessorAdapter(private val processor: AppVideoProcessor) : VideoProcessor {
    @Volatile private var sink: VideoSink? = null

    override fun setSink(sink: VideoSink?) { this.sink = sink }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        val processed = try { process(frame) } catch (_: Throwable) { null }
        if (processed == null) { out.onFrame(frame); return }
        out.onFrame(processed)
        processed.release()
    }

    private fun process(frame: VideoFrame): VideoFrame {
        val i420 = frame.buffer.toI420() ?: throw IllegalStateException("no I420")
        try {
            val input = AppVideoFrame(i420.width, i420.height, i420.dataY, i420.strideY, i420.dataU, i420.strideU, i420.dataV, i420.strideV, frame.rotation, frame.timestampNs)
            val result = processor.process(input)
            if (result === input) return VideoFrame(i420, frame.rotation, frame.timestampNs)
            val wrapped = JavaI420Buffer.wrap(result.width, result.height, result.dataY, result.strideY, result.dataU, result.strideU, result.dataV, result.strideV) { i420.release() }
            return VideoFrame(wrapped, result.rotation, result.timestampNs)
        } catch (t: Throwable) {
            i420.release()
            throw t
        }
    }
}

/**
 * The phone has one audio route and one audio mode, but every engine's room
 * would otherwise drive them on its own: a second engine (a PK battle) created
 * with CALL moved everything, the first engine's audio included, to the
 * earpiece, and leaving it reset the route under the engine still in a call.
 *
 * All engines share one device switcher. The first engine to join configures
 * it with its scenario; an engine joining while another is in a channel
 * adopts what is in force. The switcher stops only when the last engine
 * leaves.
 */
internal object SharedAudio {
    private var switcher: AudioSwitchHandler? = null
    private val holders = LinkedHashSet<Any>()
    private val listeners = LinkedHashMap<Any, (List<com.twilio.audioswitch.AudioDevice>, com.twilio.audioswitch.AudioDevice?) -> Unit>()

    /** The scenario in force while any engine is joined, or null when none is. */
    @Volatile var activeScenario: AppAudioScenario? = null
        private set
    /**
     * The app's loudspeaker choice from setSpeakerphone(), remembered even
     * before the first join (apps call it right after creating the engine;
     * before this was kept, that call was lost and a CALL-scenario voice room
     * came up on the earpiece). null = the scenario's default.
     */
    @Volatile var preferSpeaker: Boolean? = null

    @Synchronized fun switcher(context: Context): AudioSwitchHandler =
        switcher ?: AudioSwitchHandler(context.applicationContext).also { s ->
            switcher = s
            s.audioDeviceChangeListener = { devices, selected -> snapshotListeners().forEach { it(devices, selected) } }
        }

    @Synchronized private fun snapshotListeners() = listeners.values.toList()

    @Synchronized fun listen(owner: Any, l: (List<com.twilio.audioswitch.AudioDevice>, com.twilio.audioswitch.AudioDevice?) -> Unit) { listeners[owner] = l }
    @Synchronized fun unlisten(owner: Any) { listeners.remove(owner) }

    /** The per-room handle. The engine calls start on connect and stop on disconnect. */
    /** Profile settings of the engine whose scenario is in force. */
    @Volatile private var activeProfile: AudioProfileSettings? = null

    fun handlerFor(context: Context, owner: Any, scenario: AppAudioScenario, profile: AudioProfileSettings): AudioHandler = object : AudioHandler {
        override fun start() = acquire(context, owner, scenario, profile)
        override fun stop() = release(owner)
    }

    @Synchronized private fun acquire(context: Context, owner: Any, scenario: AppAudioScenario, profile: AudioProfileSettings) {
        if (!holders.add(owner)) return
        val s = switcher(context)
        if (holders.size == 1) {
            activeProfile = profile
            configure(s, scenario)
            activeScenario = scenario
            s.start()
        } else if (activeScenario != scenario) {
            android.util.Log.w("AppLoomaRTC", "Another AppEngine is already in a channel with audioScenario=$activeScenario; this engine uses the same audio route instead of $scenario")
        }
    }

    @Synchronized private fun release(owner: Any) {
        if (!holders.remove(owner)) return
        if (holders.isEmpty()) {
            switcher?.stop()
            activeScenario = null
            activeProfile = null
            preferSpeaker = null // the next channel starts from its own scenario's default
        }
    }

    /** Re-apply the device order after a speaker preference change while in a channel. */
    @Synchronized fun reapply(context: Context) {
        val scenario = activeScenario ?: return
        val s = switcher(context)
        configure(s, scenario)
        // Pick the best device in the new order now; the switcher only re-picks on device changes.
        val order = s.preferredDeviceList ?: return
        val best = order.asSequence().mapNotNull { cls -> s.availableAudioDevices.firstOrNull { cls.isInstance(it) } }.firstOrNull()
        if (best != null) s.selectDevice(best)
    }

    private fun configure(s: AudioSwitchHandler, scenario: AppAudioScenario) {
        val normal = activeProfile?.normalMode == true
        val media = normal || scenario == AppAudioScenario.MEDIA
        // Same values the engine applies to its own switcher for these audio types.
        s.audioMode = if (normal) android.media.AudioManager.MODE_NORMAL else if (media) MicCapture.mediaMode() else android.media.AudioManager.MODE_IN_COMMUNICATION
        s.audioStreamType = if (media) android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_VOICE_CALL
        s.audioAttributeUsageType = if (media) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
        s.audioAttributeContentType = if (media) android.media.AudioAttributes.CONTENT_TYPE_MUSIC else android.media.AudioAttributes.CONTENT_TYPE_SPEECH
        // Earpiece first only for an explicit SPEECH profile in the CALL scenario.
        val speakerFirst = preferSpeaker ?: (activeProfile?.let { !it.earpieceFirst } ?: media)
        val tail = if (speakerFirst)
            listOf(com.twilio.audioswitch.AudioDevice.Speakerphone::class.java, com.twilio.audioswitch.AudioDevice.Earpiece::class.java)
        else
            listOf(com.twilio.audioswitch.AudioDevice.Earpiece::class.java, com.twilio.audioswitch.AudioDevice.Speakerphone::class.java)
        s.preferredDeviceList = listOf(
            com.twilio.audioswitch.AudioDevice.BluetoothHeadset::class.java,
            com.twilio.audioswitch.AudioDevice.WiredHeadset::class.java,
        ) + tail
    }
}

/**
 * Remembers, per phone, hardware encoders that automatic codec recovery found
 * unable to produce a picture viewers can see, so the next session starts on
 * the working path. One entry per MIME type, keyed to the device, the encoder
 * name and the SDK version; an entry expires after 30 days or with a new SDK.
 */
internal object CodecMemory {
    private const val PREFS = "applooma.codec"
    private val MIMES = arrayOf("video/avc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01", "video/hevc")
    @Volatile private var applied = false

    private fun device() = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} api=${android.os.Build.VERSION.SDK_INT}"

    /** Apply remembered verdicts to the encoder factory. Once per process; cheap after that. */
    fun apply(context: Context) {
        if (applied) return
        applied = true
        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val edit = prefs.edit()
            for (mime in MIMES) {
                val key = "bad:$mime"
                val v = CodecVerdict.decode(prefs.getString(key, null)) ?: continue
                val encoder = AppLoomaVideoEncoderFactory.findPlatformHardwareEncoder(mime)?.name
                if (encoder != null && v.isValid(now, device(), encoder, BuildConfig.SDK_VERSION)) {
                    AppLoomaVideoEncoderFactory.forceSoftware(mime)
                    AppEngine.debug("codec recovery: $encoder remembered as unusable on this phone; $mime uses software")
                } else edit.remove(key)
            }
            edit.apply()
        } catch (_: Throwable) {}
    }

    fun rememberBad(context: Context, mime: String, encoder: String) {
        try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("bad:$mime", CodecVerdict(device(), encoder, BuildConfig.SDK_VERSION, System.currentTimeMillis()).encode())
                .apply()
        } catch (_: Throwable) {}
    }
}
