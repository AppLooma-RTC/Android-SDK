package com.applooma.rtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioProcessorInterface
import io.livekit.android.audio.AudioProcessorOptions
import io.livekit.android.room.participant.BackupVideoCodec
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
import io.livekit.android.room.track.VideoCaptureParameter
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
        override fun onMusicFinished() { onMain { afterMusicEnded(); eventHandler?.onMusicFinished() } }
        override fun onMusicError(error: Throwable) { onMain { eventHandler?.onError(error) } }
    })

    // Post-processing chain: residual-noise gate, target-level AGC, gain, limiter
    // (see AppAudioOptions.micGain / noiseSuppressionLevel / agcTargetLevelDbfs).
    private val micGain = MicProcessor(options.audio.micGain, options.audio.noiseSuppressionLevel.thresholdDbfs, options.audio.agcTargetLevelDbfs)

    private val room: Room = LiveKit.create(
        context.applicationContext,
        options = roomOptions(options),
        overrides = LiveKitOverrides(
            // Our own encoder factory: hardware H.264/VP8/H.265 on MediaTek,
            // Unisoc, HiSilicon and Codec2-only Qualcomm phones too, not just
            // the two vendors the engine's stock factory accepts. It shares one
            // EGL context with the engine (decoders and renderers included), so
            // camera textures reach the encoder without a copy.
            videoEncoderFactory = AppLoomaVideoEncoderFactory(sharedEgl.eglBaseContext),
            eglBase = sharedEgl,
            audioOptions = AudioOptions(
                // MEDIA keeps media attributes (loudspeaker, media volume keys,
                // music-grade bandwidth) but runs in the communication audio
                // mode so the phone's echo canceller is engaged. Plain media
                // mode leaves the canceller off, and a loudspeaker session then
                // feeds itself back after a minute or two.
                audioOutputType = if (options.audioScenario == AppAudioScenario.MEDIA) AudioType.CustomAudioType(
                    AudioManager.MODE_IN_COMMUNICATION,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    AudioManager.STREAM_MUSIC,
                ) else AudioType.CallAudioType(),
                javaAudioDeviceModuleCustomizer = { builder ->
                    val hardware = options.audio.preferHardwareEffects && !options.softwareEchoCancellation
                    // The platform canceller references everything the phone
                    // plays (in-room music included); WebRTC's software AEC
                    // only knows its own render path. Use the phone's when it
                    // has one, with the software stages still behind it.
                    builder.setUseHardwareAcousticEchoCanceler(hardware && options.audio.echoCancellation)
                    builder.setUseHardwareNoiseSuppressor(hardware && options.audio.noiseSuppression)
                    // One hook serves music mixing and the (pre-processing) recording gain.
                    builder.setAudioBufferCallback(AudioBufferCallbackDispatcher().also { it.bufferCallback = mixer })
                },
                audioProcessorOptions = AudioProcessorOptions(capturePostProcessor = micGain),
            ),
        ),
    ).also { mixer.playoutUsage = if (options.audioScenario == AppAudioScenario.MEDIA) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION }
    private val appContext: Context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init { configureAudioRouting() }

    /**
     * Tell the engine's device switcher what to pick when nothing was chosen by
     * hand: a Bluetooth headset first, then a wired one, then the loudspeaker
     * (MEDIA) or the earpiece (CALL). The switcher is built when the room
     * connects, so this must be set before joinChannel — it is, at create time.
     * Every device change (headset paired, unplugged, SCO dropped) re-evaluates
     * the list and is reported through [AppEventHandler.onAudioRouteChanged].
     */
    private fun configureAudioRouting() {
        val handler = room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler ?: return
        val tail = if (options.audioScenario == AppAudioScenario.MEDIA)
            listOf(com.twilio.audioswitch.AudioDevice.Speakerphone::class.java, com.twilio.audioswitch.AudioDevice.Earpiece::class.java)
        else
            listOf(com.twilio.audioswitch.AudioDevice.Earpiece::class.java, com.twilio.audioswitch.AudioDevice.Speakerphone::class.java)
        handler.preferredDeviceList = listOf(
            com.twilio.audioswitch.AudioDevice.BluetoothHeadset::class.java,
            com.twilio.audioswitch.AudioDevice.WiredHeadset::class.java,
        ) + tail
        handler.audioDeviceChangeListener = { devices, selected ->
            val available = devices.map { it.toRoute() }.distinct()
            val route = selected?.toRoute()
            debug("audio route=$route available=$available")
            onMain { eventHandler?.onAudioRouteChanged(route, available) }
        }
    }

    private fun com.twilio.audioswitch.AudioDevice.toRoute(): AppAudioRoute = when (this) {
        is com.twilio.audioswitch.AudioDevice.BluetoothHeadset -> AppAudioRoute.BLUETOOTH
        is com.twilio.audioswitch.AudioDevice.WiredHeadset -> AppAudioRoute.WIRED_HEADSET
        is com.twilio.audioswitch.AudioDevice.Earpiece -> AppAudioRoute.EARPIECE
        else -> AppAudioRoute.SPEAKER
    }

    /**
     * Android 12+ hides Bluetooth SCO headsets from the device switcher unless
     * the app holds BLUETOOTH_CONNECT at runtime. Not fatal — the call still
     * works on the phone's own speaker/earpiece — but worth a loud log line,
     * because "my earbuds are silent" is the whole symptom.
     */
    private fun hasBluetoothPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private var eventsJob: Job? = null
    private var statsJob: Job? = null
    private val users = LinkedHashMap<String, AppRemoteUser>()
    private val pendingData = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var lastRole: AppRole? = null
    private var playbackVolume = 100
    private var videoConfig: AppVideoConfig = options.video
    private var noiseSuppression = true

    @Volatile
    var isJoined: Boolean = false
        private set

    companion object {
        private const val TAG = "AppLoomaRTC"

        @Volatile private var debugSink: ((String) -> Unit)? = null
        private var debugTree: timber.log.Timber.Tree? = null

        /**
         * Route the engine's own diagnostic log (signaling, ICE, reconnects,
         * track events) to [sink], one line per message, and turn its verbosity
         * up. Meant for a "report a problem" feature: keep the last few hundred
         * lines in memory and send them to your server when a room ends
         * unexpectedly. Pass null to turn it off again.
         */
        @JvmStatic fun enableDebugLogging(sink: ((String) -> Unit)?) {
            debugTree?.let { timber.log.Timber.uproot(it) }
            debugTree = null
            debugSink = sink
            if (sink == null) { LiveKit.loggingLevel = io.livekit.android.util.LoggingLevel.WARN; return }
            LiveKit.loggingLevel = io.livekit.android.util.LoggingLevel.VERBOSE
            val tree = object : timber.log.Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    val level = when (priority) { android.util.Log.ERROR -> "E"; android.util.Log.WARN -> "W"; android.util.Log.INFO -> "I"; android.util.Log.DEBUG -> "D"; else -> "V" }
                    sink("$level/${tag ?: "engine"}: $message${t?.let { " — $it" } ?: ""}")
                }
            }
            debugTree = tree
            timber.log.Timber.plant(tree)
        }

        internal fun debug(line: String) { debugSink?.invoke("I/$TAG: $line") }

        /** Whether this phone has a hardware encoder for the given MIME type. */
        internal fun hasHardwareEncoder(mime: String): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !(info.name.startsWith("OMX.google.", true) || info.name.startsWith("c2.android.", true))
            }
        } catch (_: Throwable) { false }

        /**
         * One EGL context for the whole process: the engine's decoders and
         * renderers, and our encoder factory, all share it (a texture from the
         * camera reaches the hardware encoder without a copy). Passed to every
         * room as an override, so the engine does not release it when a room
         * closes — it lives as long as the app.
         */
        private val sharedEgl: livekit.org.webrtc.EglBase by lazy { livekit.org.webrtc.EglBase.create() }

        /**
         * Whether this phone has an H.264 hardware encoder our factory can drive
         * ([AppLoomaVideoEncoderFactory]). There is no software H.264 encoder on
         * Android, so without one an H.264 track is published but never carries a
         * frame — viewers see black. The engine's stock factory only took
         * OMX.qcom./OMX.Exynos.; ours also takes MediaTek, Unisoc, HiSilicon,
         * Intel and the Codec2 names (realme RMX3941, Infinix X6532C, Snapdragon
         * phones exposing only c2.qti.).
         */
        internal fun hasUsableH264Encoder(): Boolean = AppLoomaVideoEncoderFactory.findHardwareEncoder("video/avc") != null

        /** The codec actually published for a config on this phone, after AUTO is resolved. */
        internal fun resolveCodec(c: AppVideoCodec): String = when (c) {
            AppVideoCodec.VP8 -> "vp8"
            // The engine has no H.265 publish path on Android yet (our factory
            // already exposes a hardware H.265 encoder for when it does).
            AppVideoCodec.H265 -> resolveCodec(AppVideoCodec.AUTO).also { android.util.Log.w(TAG, "AppVideoCodec.H265 is not available on Android; publishing $it") }
            // AV1 only where the chipset encodes it; software AV1 at camera sizes would starve the CPU.
            AppVideoCodec.AV1 -> if (hasHardwareEncoder("video/av01")) "av1" else resolveCodec(AppVideoCodec.AUTO).also { android.util.Log.w(TAG, "No AV1 hardware encoder on this phone; publishing $it") }
            // An explicit H.264 request still cannot be honoured where no usable
            // encoder exists; VP8 (software if need be) beats a black stream.
            AppVideoCodec.H264 -> if (hasUsableH264Encoder()) "h264" else "vp8".also { android.util.Log.w(TAG, "No usable H.264 hardware encoder on this phone; publishing VP8") }
            // Hardware H.264 wherever our factory has an encoder for it (every
            // phone chipset made this decade), hardware VP8 where there is no
            // H.264 hardware, and VP8 on the CPU as the last resort. Anything
            // but VP8 keeps the VP8 backup layer (see roomOptions).
            AppVideoCodec.AUTO -> when {
                hasUsableH264Encoder() -> "h264"
                AppLoomaVideoEncoderFactory.findHardwareEncoder("video/x-vnd.on2.vp8") != null -> "vp8"
                else -> "vp8"
            }
        }

        private val HEIGHTS = intArrayOf(360, 540, 720, 1080, 1440, 2160)
        internal fun snapHeight(wanted: Int): Int = HEIGHTS.minByOrNull { kotlin.math.abs(it - wanted) } ?: 1080

        /** `AUTO` leaves the engine's per-source default (frame rate first for a camera). */
        private fun degradationOf(d: AppVideoDegradation): livekit.org.webrtc.RtpParameters.DegradationPreference? = when (d) {
            AppVideoDegradation.KEEP_RESOLUTION -> livekit.org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            AppVideoDegradation.KEEP_FRAMERATE -> livekit.org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            AppVideoDegradation.BALANCED -> livekit.org.webrtc.RtpParameters.DegradationPreference.BALANCED
            AppVideoDegradation.AUTO -> null
        }

        private fun mimeOfCodec(codec: String): String = when (codec) {
            "h264" -> "video/avc"; "av1" -> "video/av01"; else -> "video/x-vnd.on2.vp8"
        }

        /**
         * STABLE_HD tier, probed once per process: whether the codec AUTO
         * resolves to has a hardware encoder on this phone. Hardware holds
         * 1080p30 steadily; a CPU encoder (VP8 on MediaTek, for one) does not,
         * so those phones get 720p30.
         */
        private val hardwareTier: Boolean by lazy {
            AppLoomaVideoEncoderFactory.findHardwareEncoder(mimeOfCodec(resolveCodec(AppVideoCodec.AUTO))) != null
        }

        private val ultraHdGate: Boolean by lazy {
            try {
                val mime = mimeOfCodec(resolveCodec(AppVideoCodec.AUTO))
                val hw = AppLoomaVideoEncoderFactory.findHardwareEncoder(mime)
                val caps = hw?.getCapabilitiesForType(mime)?.videoCapabilities
                caps != null && (caps.isSizeSupported(3840, 2160) || caps.isSizeSupported(2160, 3840))
            } catch (_: Throwable) { false }
        }

        /**
         * Whether this phone can publish [AppVideoMode.ULTRA_HD_4K]: the
         * hardware encoder for the codec the SDK would pick accepts 3840x2160.
         * When false, ULTRA_HD_4K falls back to STABLE_HD. Unreleased.
         */
        @JvmStatic fun isUltraHdSupported(): Boolean = ultraHdGate

        /** What a config resolves to on this phone once the mode fills in the blanks. */
        internal data class ResolvedVideo(
            val mode: AppVideoMode,
            val height: Int,
            val fps: Int,
            val maxBitrate: Int,
            val simulcast: Boolean,
            val degradation: AppVideoDegradation,
        )

        internal fun resolveVideo(c: AppVideoConfig): ResolvedVideo {
            var mode = c.mode
            if (mode == AppVideoMode.ULTRA_HD_4K && !ultraHdGate) {
                android.util.Log.w(TAG, "AppVideoMode.ULTRA_HD_4K: no hardware encoder for 3840x2160 on this phone; using STABLE_HD")
                mode = AppVideoMode.STABLE_HD
            }
            val tierHeight = when (mode) {
                AppVideoMode.ULTRA_HD_4K -> 2160
                AppVideoMode.STABLE_HD -> if (hardwareTier) 1080 else 720
                AppVideoMode.ADAPTIVE -> 1080
            }
            val height = if (c.height > 0) snapHeight(c.height) else tierHeight
            if (c.height > 0 && height != c.height) android.util.Log.w(TAG, "AppVideoConfig.height=${c.height} is not a preset; using ${height}p")
            val maxBitrate = when {
                c.maxBitrate > 0 -> c.maxBitrate
                height == 2160 -> 25_000_000
                mode == AppVideoMode.STABLE_HD -> when (height) { 1440 -> 5_000_000; 1080 -> 3_500_000; 720 -> 1_800_000; else -> presetOf(height).encoding.maxBitrate }
                else -> when (height) { 1440 -> 5_000_000; 1080 -> 4_000_000; 720 -> 2_200_000; else -> presetOf(height).encoding.maxBitrate }
            }
            val simulcast = c.simulcast ?: (mode == AppVideoMode.ADAPTIVE)
            val degradation = if (c.degradation == AppVideoDegradation.AUTO && mode != AppVideoMode.ADAPTIVE) AppVideoDegradation.KEEP_RESOLUTION else c.degradation
            // Software-encoder phones (no usable hardware encoder, e.g. MediaTek
            // today) cannot hold 720p at 30 fps while also decoding the other
            // side: measured on realme RMX3941 the frame rate swung 2-28 fps
            // under CPU limit. A steady 20 fps looks far smoother. Only the
            // default fps is capped; an app that asks for another value keeps it.
            val fps = if (mode == AppVideoMode.STABLE_HD && !hardwareTier && c.height <= 0 && c.fps == 30) 20 else c.fps
            return ResolvedVideo(mode, height, fps, maxBitrate, simulcast, degradation)
        }

        private fun presetOf(height: Int): VideoPreset169 = when (height) {
            360 -> VideoPreset169.H360
            540 -> VideoPreset169.H540
            720 -> VideoPreset169.H720
            1440 -> VideoPreset169.H1440
            else -> VideoPreset169.H1080
        }

        private fun roomOptions(o: AppEngineOptions): RoomOptions {
            val v = resolveVideo(o.video)
            // 2160p has no engine preset; capture at 3840x2160 directly.
            // Capture at the resolved frame rate too, so a 20 fps tier does not pay
            // for 30 captured frames it throws away.
            val capture = if (v.height == 2160) VideoCaptureParameter(3840, 2160, v.fps) else presetOf(v.height).capture.let { VideoCaptureParameter(it.width, it.height, v.fps) }
            if (o.video.maxBitrate in 1 until 10_000)
                android.util.Log.w(TAG, "AppVideoConfig.maxBitrate=${o.video.maxBitrate} is in bits per second - did you mean ${o.video.maxBitrate}_000 (kbps)?")
            // minBitrate: the engine's encoding carries no floor on Android, so
            // AppVideoConfig.minBitrate is not applied here. There is no start
            // bitrate / initial bandwidth estimate hook for a publisher either,
            // so the encoder ramps up to the tier cap on its own.
            val encoding = VideoEncoding(maxBitrate = v.maxBitrate, maxFps = v.fps)
            val codec = resolveCodec(o.video.codec)
            debug("video mode ${v.mode} ${v.height}p${v.fps} max=${v.maxBitrate / 1000}kbps simulcast=${v.simulcast} degradation=${v.degradation} codec=$codec")
            return RoomOptions(
                // ADAPTIVE: adaptive stream down-shifts a remote layer for a small
                // or hidden view (good on weak links). STABLE_HD / ULTRA_HD_4K
                // turn it off so a receiver keeps the full picture. Dynacast is
                // OFF: with it on, a publisher withholds a video layer until a
                // subscriber's "viewing" signal arrives, which on flaky links left
                // co-hosts staring at a black frame. Off, every publisher always
                // sends - video just works.
                adaptiveStream = v.mode == AppVideoMode.ADAPTIVE,
                dynacast = false,
                videoTrackCaptureDefaults = LocalVideoTrackOptions(captureParams = capture),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = encoding,
                    simulcast = v.simulcast,
                    videoCodec = codec,
                    // Anything but VP8 rides with a VP8 backup layer: a viewer
                    // whose phone cannot decode the main stream (or a hardware
                    // encoder that emits something only it can read) is served
                    // VP8 by the server instead of a black frame.
                    backupCodec = if (codec != "vp8") BackupVideoCodec(codec = "vp8", encoding = encoding, simulcast = v.simulcast) else null,
                    degradationPreference = degradationOf(v.degradation),
                ),
                audioTrackCaptureDefaults = LocalAudioTrackOptions(
                    noiseSuppression = o.audio.noiseSuppression,
                    echoCancellation = o.audio.echoCancellation,
                    autoGainControl = o.audio.autoGainControl,
                ),
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
        joinStartedAt = System.currentTimeMillis()
        firstFrameSeen.clear()
        subscribeEvents()
        // Auto-subscribe so an audience member (and every co-host) receives all
        // published tracks the moment they join — no manual per-track subscribe.
        debug("joinChannel role=${options.role} camera=${options.camera} mic=${options.microphone} sdk=0.4.7 device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
        publishedCodec.let { debug("video codec=$it ${AppLoomaVideoEncoderFactory.describe(it)}") }
        if (!hasBluetoothPermission()) {
            android.util.Log.w(TAG, "BLUETOOTH_CONNECT is not granted: Bluetooth headsets will not be offered as an audio route on this phone. Request it at runtime next to RECORD_AUDIO.")
        }
        room.connect(wsUrl, token, ConnectOptions(autoSubscribe = true))
        isJoined = true
        debug("connected localUid=$localUid")
        eventHandler?.onConnectionStage(AppConnectionStage.CONNECTED, null, elapsedSinceJoin())

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
    fun enableCamera(on: Boolean) = launchSafely { room.localParticipant.setCameraEnabled(on); notifyLocalVideoChanged() }
    /**
     * Turn your microphone on or off. Music is independent of it: while a
     * song is playing, muting sends silence in place of your voice but keeps
     * the music going, so a host can mute themselves without the room going
     * quiet. Once the music stops, a muted microphone becomes a real mute
     * (nothing is sent). [isMicrophoneEnabled] reports what you asked for.
     */
    fun enableMicrophone(on: Boolean) = launchSafely {
        micWanted = on
        if (on) {
            mixer.micMuted = false
            room.localParticipant.setMicrophoneEnabled(true)
        } else if (mixer.currentState != AppMusicState.STOPPED) {
            // Music is playing: keep the capture track alive and silence the voice.
            mixer.micMuted = true
            if (!room.localParticipant.isMicrophoneEnabled()) room.localParticipant.setMicrophoneEnabled(true)
        } else {
            mixer.micMuted = false
            room.localParticipant.setMicrophoneEnabled(false)
        }
    }

    /** Whether you asked for the microphone to be on (what a mic button should show). */
    val isMicrophoneEnabled: Boolean get() = micWanted
    private var micWanted = true

    /** Music ended or was stopped: a logically muted microphone now really mutes. */
    private fun afterMusicEnded() {
        if (!micWanted) launchSafely { mixer.micMuted = false; room.localParticipant.setMicrophoneEnabled(false) }
    }

    /** Flip between the front and back cameras. Does nothing until the camera is on. */
    fun switchCamera() = launchSafely { (localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack)?.switchCamera(); notifyLocalVideoChanged() }

    /**
     * Route audio to the loudspeaker (true) or the earpiece (false).
     * A wired or Bluetooth headset, when present, keeps priority either way:
     * this only decides which of the phone's own outputs to use when no
     * headset is connected. To force a specific output use [setAudioRoute].
     */
    fun setSpeakerphone(on: Boolean) {
        val routes = getAudioRoutes()
        val headset = routes.firstOrNull { it == AppAudioRoute.BLUETOOTH || it == AppAudioRoute.WIRED_HEADSET }
        setAudioRoute(headset ?: if (on) AppAudioRoute.SPEAKER else AppAudioRoute.EARPIECE)
    }

    /**
     * The outputs that can be selected right now, best first: a Bluetooth
     * headset (when paired and, on Android 12+, `BLUETOOTH_CONNECT` is granted),
     * a wired headset, then the speaker and earpiece. Empty before the first
     * join — the switcher starts with the connection.
     */
    fun getAudioRoutes(): List<AppAudioRoute> {
        val handler = room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler ?: return emptyList()
        return handler.availableAudioDevices.map { it.toRoute() }.distinct()
    }

    /** The output audio is going to right now, or null before the first join. */
    val currentAudioRoute: AppAudioRoute?
        get() = (room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler)?.selectedAudioDevice?.toRoute()

    /**
     * Send audio to one specific output — for an in-call route picker
     * ("Phone / Speaker / AirPods"). Returns false when that output is not in
     * [getAudioRoutes] (nothing changes). A manual choice sticks until the
     * device disappears, after which the automatic order applies again;
     * [AppEventHandler.onAudioRouteChanged] reports every change.
     */
    fun setAudioRoute(route: AppAudioRoute): Boolean {
        val handler = room.audioHandler as? io.livekit.android.audio.AudioSwitchHandler ?: return false
        val wanted = handler.availableAudioDevices.firstOrNull { it.toRoute() == route } ?: return false
        handler.selectDevice(wanted)
        return true
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

    /**
     * Change camera resolution, frame rate or bitrate while publishing — the
     * hook for a thermal throttle (drop to 720p/15 fps when the phone is hot)
     * or a crowd rule (lower the host's bitrate when the room passes N viewers).
     *
     * Resolution and fps change in place, without a republish. A bitrate or
     * simulcast change republishes the camera, which costs about half a second
     * of video for viewers and nothing for audio.
     */
    fun setVideoConfig(config: AppVideoConfig) = launchSafely {
        val previous = videoConfig
        videoConfig = config
        val ro = roomOptions(options.copy(video = config))
        val lp = room.localParticipant
        lp.videoTrackCaptureDefaults = ro.videoTrackCaptureDefaults ?: lp.videoTrackCaptureDefaults
        lp.videoTrackPublishDefaults = ro.videoTrackPublishDefaults ?: lp.videoTrackPublishDefaults
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack ?: return@launchSafely
        val was = resolveVideo(previous)
        val now = resolveVideo(config)
        val republish = now.maxBitrate != was.maxBitrate || now.simulcast != was.simulcast ||
            config.codec != previous.codec || now.degradation != was.degradation
        if (republish) {
            lp.setCameraEnabled(false)
            lp.setCameraEnabled(true)
        } else {
            track.restartTrack(track.options.copy(captureParams = ro.videoTrackCaptureDefaults!!.captureParams))
        }
        notifyLocalVideoChanged()
    }

    /** The camera settings in force right now. */
    val currentVideoConfig: AppVideoConfig get() = videoConfig

    /**
     * Turn noise suppression on or off at runtime. It costs CPU per published
     * stream, so a crowded voice room can switch it off above a headcount and
     * back on when the room thins out. Re-captures the microphone; a few tens
     * of milliseconds of audio are lost while it restarts.
     */
    fun setNoiseSuppression(on: Boolean) = launchSafely {
        if (noiseSuppression == on) return@launchSafely
        noiseSuppression = on
        val lp = room.localParticipant
        lp.audioTrackCaptureDefaults = lp.audioTrackCaptureDefaults.copy(noiseSuppression = on)
        if (lp.isMicrophoneEnabled()) {
            lp.setMicrophoneEnabled(false)
            lp.setMicrophoneEnabled(true)
        }
    }

    val isNoiseSuppressionEnabled: Boolean get() = noiseSuppression

    /**
     * Grab one frame of your own camera as a Bitmap, on this device only.
     * Nothing is uploaded. Use it for a host presence check or a thumbnail.
     * Returns null through the callback if the camera is off.
     *
     * @param maxEdge longest side of the returned bitmap; 0 = full size.
     */
    @JvmOverloads
    fun captureLocalFrame(maxEdge: Int = 480, callback: (android.graphics.Bitmap?) -> Unit) {
        val track = localVideoTrack ?: return callback(null)
        FrameGrab(track, maxEdge) { bmp -> onMain { callback(bmp) } }.start()
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
     * The music does not need your microphone: with the mic muted, the room
     * hears the song and not you (since 0.4.5). Only a publisher can play —
     * an audience member's call is ignored.
     *
     * @param loops how many times to play; 0 = forever.
     * @param startMs where in the file to begin.
     */
    @JvmOverloads
    fun playMusic(path: String, loops: Int = 1, startMs: Int = 0) {
        if (!micWanted) launchSafely {
            // Music rides on the capture track; bring it up silently.
            mixer.micMuted = true
            if (!room.localParticipant.isMicrophoneEnabled()) room.localParticipant.setMicrophoneEnabled(true)
        }
        mixer.play(path, loops, startMs)
    }
    fun pauseMusic() = mixer.pause()
    fun resumeMusic() = mixer.resume()
    fun stopMusic() { mixer.stop(); afterMusicEnded() }
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
    // Views showing the local camera; re-bound on every local track change.
    internal val localViews = LinkedHashSet<AppVideoView>()
    private var lastLocalTrack: VideoTrack? = null
    private var joinStartedAt = 0L
    private val firstFrameSeen = HashSet<String>()
    private fun elapsedSinceJoin() = if (joinStartedAt == 0L) 0L else System.currentTimeMillis() - joinStartedAt

    /**
     * The local camera track was replaced (on/off, switch, config). Re-bind
     * every self-view and tell the app. Safe to call when nothing changed.
     */
    internal fun notifyLocalVideoChanged() {
        val now = localVideoTrack
        val changed = now !== lastLocalTrack
        lastLocalTrack = now
        localViews.toList().forEach { it.refreshLocal(this) }
        trackWaiters.toList().forEach { it.localTrackArrived(this) }
        if (changed) onMain { eventHandler?.onLocalVideoChanged() }
    }

    /**
     * Microphone gain applied after echo cancellation, noise suppression and
     * AGC, with a soft limiter — so a distant voice gets louder without the
     * clipping a pre-processing gain causes. 100 = unity, 200 = twice as loud.
     * Prefer this over [setRecordingVolume], which sits before processing.
     */
    fun setMicGain(percent: Int) { micGain.gainPercent = percent.coerceIn(0, 400) }
    val micGainPercent: Int get() = micGain.gainPercent

    /**
     * How hard residual noise between words is pushed down (after the
     * suppressor). Takes effect immediately, mid-call. See [AppNoiseSuppressionLevel].
     */
    fun setNoiseSuppressionLevel(level: AppNoiseSuppressionLevel) { micGain.gateThresholdDbfs = level.thresholdDbfs; noiseLevel = level }
    val noiseSuppressionLevel: AppNoiseSuppressionLevel get() = noiseLevel
    private var noiseLevel = options.audio.noiseSuppressionLevel

    /**
     * Level the microphone is steered towards while someone talks, in dBFS
     * (-18 is a good broadcast target; 0 turns it off). Immediate, mid-call.
     */
    fun setAgcTargetLevel(dbfs: Int) { micGain.agcTargetDbfs = dbfs.coerceIn(-40, 0) }
    val agcTargetLevelDbfs: Int get() = micGain.agcTargetDbfs

    /** The codec this phone publishes with, after AUTO (or an unavailable choice) is resolved: "vp8", "h264" or "av1". */
    val publishedCodec: String get() = resolveCodec(options.video.codec)

    /** Fires onFirstRemoteVideoFrame once per remote user per join, then detaches. */
    private inner class FirstFrameSink(private val uid: String, private val track: VideoTrack) : livekit.org.webrtc.VideoSink {
        private val done = java.util.concurrent.atomic.AtomicBoolean(false)
        fun start() { try { track.addRenderer(this) } catch (_: Throwable) {} }
        override fun onFrame(frame: livekit.org.webrtc.VideoFrame) {
            if (!done.compareAndSet(false, true)) return
            val ms = elapsedSinceJoin()
            Thread { try { track.removeRenderer(this) } catch (_: Throwable) {} }.start()
            onMain {
                eventHandler?.onFirstRemoteVideoFrame(uid, ms)
                eventHandler?.onConnectionStage(AppConnectionStage.FIRST_REMOTE_VIDEO_FRAME, uid, ms)
            }
        }
    }

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
                    // What our own camera is sending: the top simulcast layer
                    // that carries frames, reported only when it changes.
                    if (localVideoTrack != null) reportLocalQuality(reports[0], secs) else { lastQuality = null; lastVideoBytes = 0L }
                    // Belt and braces for self-views: if the local track was swapped
                    // by a path that raised no event, the next tick re-binds it.
                    if (localVideoTrack !== lastLocalTrack) notifyLocalVideoChanged()
                    // Per-remote-user health — which picture is stuck, whose audio is dropping.
                    val remote = users.values.mapNotNull { u -> remoteStatsFor(u) }
                    if (remote.isNotEmpty()) eventHandler?.onRemoteStats(remote)
                } catch (_: Throwable) { /* stats are best effort */ }
            }
        }
    }

    private var lastQuality: AppVideoQualityInfo? = null
    private var lastAvailableOutKbps = -1
    private var lastVideoBytes = 0L

    private fun reportLocalQuality(report: livekit.org.webrtc.RTCStatsReport?, secs: Double) {
        val video = report?.statsMap?.values
            ?.filter { it.type == "outbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video" }
            ?: return
        if (video.isEmpty()) return
        lastAvailableOutKbps = report.statsMap.values
            .filter { it.type == "candidate-pair" && (it.members["nominated"] as? Boolean) == true }
            .mapNotNull { (it.members["availableOutgoingBitrate"] as? Number)?.toInt() }
            .maxOrNull()?.div(1000) ?: -1
        val bytes = video.sumOf { (it.members["bytesSent"] as? Number)?.toLong() ?: 0L }
        val kbps = if (lastVideoBytes > 0) (((bytes - lastVideoBytes).coerceAtLeast(0) * 8) / secs / 1000).toInt() else 0
        lastVideoBytes = bytes
        val active = video.filter { ((it.members["framesPerSecond"] as? Number)?.toDouble() ?: 0.0) > 0 && ((it.members["frameHeight"] as? Number)?.toInt() ?: 0) > 0 }
        val top = active.maxByOrNull { (it.members["frameHeight"] as? Number)?.toInt() ?: 0 } ?: return
        val m = top.members
        val h = (m["frameHeight"] as? Number)?.toInt() ?: 0
        val configured = resolveVideo(videoConfig).height
        val layer = when (m["rid"] as? String) {
            "f" -> AppVideoLayer.HIGH
            "h" -> AppVideoLayer.MEDIUM
            "q" -> AppVideoLayer.LOW
            else -> if (h >= configured * 0.75) AppVideoLayer.HIGH else if (h >= configured * 0.4) AppVideoLayer.MEDIUM else AppVideoLayer.LOW
        }
        val q = AppVideoQualityInfo(
            width = (m["frameWidth"] as? Number)?.toInt() ?: 0,
            height = h,
            fps = ((m["framesPerSecond"] as? Number)?.toDouble() ?: 0.0).toInt(),
            bitrateKbps = kbps,
            layer = layer,
            reason = when (m["qualityLimitationReason"] as? String) { "bandwidth" -> AppVideoQualityReason.BANDWIDTH; "cpu" -> AppVideoQualityReason.CPU; else -> AppVideoQualityReason.NONE },
        )
        val prev = lastQuality
        lastQuality = q
        if (prev == null) return // the first sample is the baseline, not a change
        val same = prev.width == q.width && prev.height == q.height && prev.layer == q.layer && prev.reason == q.reason && kotlin.math.abs(prev.fps - q.fps) < 5
        if (!same) {
            debug("video out ${q.width}x${q.height} ${q.fps}fps ${q.bitrateKbps}kbps layer=${q.layer} limit=${q.reason} uplinkEstimate=${lastAvailableOutKbps}kbps")
            eventHandler?.onVideoQualityChanged(q)
        }
    }

    private suspend fun trackStats(track: Track?): livekit.org.webrtc.RTCStatsReport? {
        if (track == null) return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try { track.getRTCStats { cont.resume(it) {} } } catch (_: Throwable) { cont.resume(null) {} }
        }
    }

    private suspend fun remoteStatsFor(u: AppRemoteUser): AppRemoteStats? {
        val video = u.raw.videoTrackPublications.firstNotNullOfOrNull { it.second }
        val audio = u.raw.audioTrackPublications.firstNotNullOfOrNull { it.second }
        if (video == null && audio == null) return null
        var fps = 0; var w = 0; var h = 0; var freezes = 0; var vLost = 0L; var aLost = 0L; var jitterMs = 0
        trackStats(video)?.statsMap?.values?.filter { it.type == "inbound-rtp" }?.forEach { s ->
            val m = s.members
            fps = (m["framesPerSecond"] as? Number)?.toInt() ?: fps
            w = (m["frameWidth"] as? Number)?.toInt() ?: w
            h = (m["frameHeight"] as? Number)?.toInt() ?: h
            freezes = (m["freezeCount"] as? Number)?.toInt() ?: freezes
            vLost = (m["packetsLost"] as? Number)?.toLong() ?: vLost
        }
        trackStats(audio)?.statsMap?.values?.filter { it.type == "inbound-rtp" }?.forEach { s ->
            val m = s.members
            aLost = (m["packetsLost"] as? Number)?.toLong() ?: aLost
            val jbDelay = (m["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
            val jbEmitted = (m["jitterBufferEmittedCount"] as? Number)?.toDouble() ?: 0.0
            if (jbEmitted > 0) jitterMs = (jbDelay / jbEmitted * 1000).toInt()
        }
        return AppRemoteStats(uid = u.uid, videoFps = fps, videoWidth = w, videoHeight = h, videoFreezeCount = freezes, videoPacketsLost = vLost, audioPacketsLost = aLost, audioJitterMs = jitterMs)
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
                        if (event.track is VideoTrack) {
                            trackWaiters.toList().forEach { it.trackArrived(this@AppEngine, u) }
                            // Time-to-first-frame, once per user per join.
                            if (firstFrameSeen.add(u.uid)) FirstFrameSink(u.uid, event.track as VideoTrack).start()
                        }
                    }
                    is RoomEvent.TrackPublished ->
                        if (event.participant === room.localParticipant) notifyLocalVideoChanged()
                        else (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackUnpublished -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackMuted -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    // Camera off→on keeps the publication and swaps the track underneath:
                    // the unmute is the moment self-views must re-bind.
                    is RoomEvent.TrackUnmuted ->
                        if (event.participant === room.localParticipant) notifyLocalVideoChanged()
                        else (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.LocalTrackSubscribed -> if (event.publication.track is VideoTrack) notifyLocalVideoChanged()
                    is RoomEvent.Reconnecting -> { debug("reconnecting"); eventHandler?.onReconnecting() }
                    is RoomEvent.Reconnected -> {
                        debug("reconnected")
                        users.clear()
                        seedExistingParticipants()
                        eventHandler?.onReconnected()
                    }
                    is RoomEvent.Disconnected -> {
                        debug("disconnected reason=${event.reason?.name} error=${event.error}")
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
            // Sent back to the audience: nothing of ours may play, music included.
            mixer.stop(); mixer.micMuted = false; micWanted = false
            try { room.localParticipant.setMicrophoneEnabled(false); room.localParticipant.setCameraEnabled(false) } catch (_: Throwable) {}
        } else {
            micWanted = true; mixer.micMuted = false
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

/**
 * Post-processing microphone chain, run after WebRTC's AEC/NS/AGC on the
 * capture path: residual-noise gate → target-level AGC → gain → soft limiter.
 *
 * Everything here works on what the suppressor left behind, so pushing the
 * gate or the gain does not also lift what was about to be removed, and the
 * limiter keeps loud moments from clipping (the pre-processing recording gain
 * had no such guard). One buffer is 10 ms; the smoothing constants are per
 * buffer.
 */
internal class MicProcessor(
    @Volatile var gainPercent: Int,
    @Volatile var gateThresholdDbfs: Float,
    /** 0 = off. */
    @Volatile var agcTargetDbfs: Int,
) : AudioProcessorInterface {
    private var gateGain = 1f
    private var agcGain = 1f

    override fun isEnabled(): Boolean = gainPercent != 100 || gateThresholdDbfs > Float.NEGATIVE_INFINITY || agcTargetDbfs != 0
    override fun getName(): String = "applooma-mic"
    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) { gateGain = 1f; agcGain = 1f }
    override fun resetAudioProcessing(newRate: Int) { gateGain = 1f; agcGain = 1f }

    override fun processAudio(numBands: Int, numFrames: Int, buffer: java.nio.ByteBuffer) {
        // Never let an exception escape into the native audio thread: the
        // engine aborts the whole process on it (seen on realme/vivo, Android 16).
        try { process(numBands, numFrames, buffer) } catch (_: Throwable) {}
    }

    private fun process(numBands: Int, numFrames: Int, buffer: java.nio.ByteBuffer) {
        val fb = buffer.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        // Real devices report numBands = 3 for the 48 kHz band split while the
        // buffer holds only the full-band samples (480 floats per 10 ms), so the
        // product overshoots the buffer; the buffer's own limit is the truth.
        val n = minOf(numBands * numFrames, fb.limit())
        if (n <= 0) return

        var sum = 0.0
        for (i in 0 until n) { val v = fb.get(i); sum += (v * v).toDouble() }
        val levelDb = (20.0 * kotlin.math.log10(kotlin.math.sqrt(sum / n) + 1e-9)).toFloat()
        val speaking = levelDb >= gateThresholdDbfs

        // Gate: opens fast so the first syllable is not lost, closes slowly so
        // a pause mid-sentence does not pump.
        val gateTarget = if (speaking) 1f else GATE_FLOOR
        gateGain += (gateTarget - gateGain) * (if (gateTarget > gateGain) 0.5f else 0.05f)

        // AGC: only steers while someone is talking — silence is never lifted.
        if (agcTargetDbfs != 0 && speaking) {
            val wanted = Math.pow(10.0, ((agcTargetDbfs - levelDb) / 20.0).toDouble()).toFloat().coerceIn(AGC_MIN, AGC_MAX)
            agcGain += (wanted - agcGain) * (if (wanted < agcGain) 0.3f else 0.02f)
        } else if (agcTargetDbfs == 0) agcGain = 1f

        val g = gateGain * agcGain * (gainPercent / 100f)
        if (g == 1f) return
        for (i in 0 until n) {
            var y = fb.get(i) * g
            // Soft knee above ±0.8, hard ceiling at ±1: loud peaks compress instead of clip.
            val a = kotlin.math.abs(y)
            if (a > KNEE) {
                val over = a - KNEE
                val limited = KNEE + (1f - KNEE) * (1f - kotlin.math.exp(-over / (1f - KNEE)))
                y = if (y < 0) -limited else limited
            }
            fb.put(i, y)
        }
    }

    private companion object {
        const val KNEE = 0.8f
        /** -20 dB: noise is eased down, not muted, so the room never sounds "dead". */
        const val GATE_FLOOR = 0.1f
        const val AGC_MIN = 0.25f  // -12 dB
        const val AGC_MAX = 8f     // +18 dB
    }
}
