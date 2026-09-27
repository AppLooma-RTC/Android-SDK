package com.applooma.rtc

import android.content.Context
import io.livekit.android.audio.AudioHandler
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.track.LocalVideoTrack
import livekit.org.webrtc.JavaI420Buffer
import livekit.org.webrtc.RtpSender
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

    fun source(track: LocalVideoTrack): VideoSource? = try { sourceField?.get(track) as? VideoSource } catch (_: Throwable) { null }
    fun sender(track: LocalVideoTrack): RtpSender? = try { senderMethod?.invoke(track) as? RtpSender } catch (_: Throwable) { null }
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
    fun handlerFor(context: Context, owner: Any, scenario: AppAudioScenario): AudioHandler = object : AudioHandler {
        override fun start() = acquire(context, owner, scenario)
        override fun stop() = release(owner)
    }

    @Synchronized private fun acquire(context: Context, owner: Any, scenario: AppAudioScenario) {
        if (!holders.add(owner)) return
        val s = switcher(context)
        if (holders.size == 1) {
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
        val media = scenario == AppAudioScenario.MEDIA
        // Same values the engine applies to its own switcher for these audio types.
        s.audioMode = android.media.AudioManager.MODE_IN_COMMUNICATION
        s.audioStreamType = if (media) android.media.AudioManager.STREAM_MUSIC else android.media.AudioManager.STREAM_VOICE_CALL
        s.audioAttributeUsageType = if (media) android.media.AudioAttributes.USAGE_MEDIA else android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION
        s.audioAttributeContentType = if (media) android.media.AudioAttributes.CONTENT_TYPE_MUSIC else android.media.AudioAttributes.CONTENT_TYPE_SPEECH
        val speakerFirst = preferSpeaker ?: media
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
