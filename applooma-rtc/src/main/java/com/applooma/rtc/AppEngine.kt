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

    /**
     * The audio scenario this engine really runs with. An engine created while
     * another is in a channel (a PK battle's second connection) adopts the
     * scenario in force, so it does not move the first engine's audio.
     */
    private val audioScenario: AppAudioScenario = SharedAudio.activeScenario?.also {
        if (it != options.audioScenario) android.util.Log.w(TAG, "AppEngine.create: another engine is in a channel with audioScenario=$it; adopting it instead of ${options.audioScenario}")
    } ?: options.audioScenario

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
                audioOutputType = if (audioScenario == AppAudioScenario.MEDIA) AudioType.CustomAudioType(
                    AudioManager.MODE_IN_COMMUNICATION,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    AudioManager.STREAM_MUSIC,
                ) else AudioType.CallAudioType(),
                // One device switcher shared by every engine in the process (see SharedAudio).
                audioHandler = SharedAudio.handlerFor(context, this, audioScenario),
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
    ).also { mixer.playoutUsage = if (audioScenario == AppAudioScenario.MEDIA) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION }
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
        // The preferred order is applied by SharedAudio when the first engine joins.
        SharedAudio.listen(this) { devices, selected ->
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
    private var lastDiagStatsAt = 0L
    private val users = LinkedHashMap<String, AppRemoteUser>()
    private val pendingData = ArrayDeque<Pair<ByteArray, Boolean>>()
    private var lastRole: AppRole? = null
    private var lastCanPublish: Boolean? = null
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

        /** SDK log line: always kept in the diagnostics ring, and passed to the app's sink when debug logging is on. */
        internal fun debug(line: String) {
            Diagnostics.log(line)
            debugSink?.invoke("I/$TAG: $line")
        }

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
        internal fun resolveCodec(c: AppVideoCodec): String = resolveCodecOnPhone(DeviceCaps.codec(c, DeviceTelemetry.current))

        /** [resolveCodec] without the server's per-device codec choice. */
        private fun resolveCodecOnPhone(c: AppVideoCodec): String = when (c) {
            AppVideoCodec.VP8 -> "vp8"
            // The engine has no H.265 publish path on Android yet (our factory
            // already exposes a hardware H.265 encoder for when it does).
            AppVideoCodec.H265 -> resolveCodecOnPhone(AppVideoCodec.AUTO).also { android.util.Log.w(TAG, "AppVideoCodec.H265 is not available on Android; publishing $it") }
            // AV1 only where the chipset encodes it; software AV1 at camera sizes would starve the CPU.
            AppVideoCodec.AV1 -> if (hasHardwareEncoder("video/av01") && !AppLoomaVideoEncoderFactory.isForcedSoftware("video/av01")) "av1" else resolveCodecOnPhone(AppVideoCodec.AUTO).also { android.util.Log.w(TAG, "No AV1 hardware encoder on this phone; publishing $it") }
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

        private const val VP8_MIME = "video/x-vnd.on2.vp8"

        private fun mimeOfCodec(codec: String): String = when (codec) {
            "h264" -> "video/avc"; "av1" -> "video/av01"; else -> "video/x-vnd.on2.vp8"
        }

        /**
         * STABLE_HD tier, probed once per process: whether the codec AUTO
         * resolves to has a hardware encoder on this phone. Hardware holds
         * 1080p30 steadily; a CPU encoder (VP8 on MediaTek, for one) does not,
         * so those phones get 720p30.
         */
        // Re-probed when automatic codec recovery forces a codec onto software,
        // so the recovered stream gets the software tier (720p20 in STABLE_HD).
        private var hardwareTierCache: Pair<String, Boolean>? = null
        private val hardwareTier: Boolean
            @Synchronized get() {
                val key = AppLoomaVideoEncoderFactory.forcedSoftwareKey()
                hardwareTierCache?.let { if (it.first == key) return it.second }
                val v = AppLoomaVideoEncoderFactory.findHardwareEncoder(mimeOfCodec(resolveCodec(AppVideoCodec.AUTO))) != null
                hardwareTierCache = key to v
                return v
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
            // Per-device caps from the server (DeviceTelemetry): only ever lower.
            val capped = DeviceCaps.apply(DeviceCaps.Caps(height, fps, maxBitrate, simulcast), DeviceTelemetry.current)
            return ResolvedVideo(mode, capped.height, capped.fps, capped.maxBitrate, capped.simulcast, degradation)
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
                // AppEngineOptions.adaptiveStream, when set, overrides the mode.
                adaptiveStream = o.adaptiveStream ?: (v.mode == AppVideoMode.ADAPTIVE),
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
            CodecMemory.apply(context)
            DeviceTelemetry.region = options.region.code
            DeviceTelemetry.load(context)
            Diagnostics.configure(context, options.remoteDiagnostics)
            val engine = AppEngine(context, appId, eventHandler, options)
            // Once per install and SDK version, in the background; never the camera.
            EncoderSelfTest.runOnceIfDue(context, options.encoderSelfTest)
            return engine
        }

        /**
         * Encode about 30 synthetic 720p frames through the hardware H.264
         * encoder the SDK would use, and check that it produces parameter sets
         * or a key frame and real output within 2 s. Never touches the camera;
         * runs on a background thread and gives up after 3 s. A failing encoder
         * is remembered on this phone (like one found bad during a call), so
         * later publishes use the software path, and an anonymous device report
         * is sent with the next join. [callback] runs on the main thread.
         *
         * Runs by itself once per install and SDK version after the first
         * [create] unless [AppEngineOptions.encoderSelfTest] is false; call it
         * yourself for a support screen. Unreleased.
         */
        @JvmStatic
        fun runEncoderSelfTest(context: Context, callback: (AppEncoderSelfTestResult) -> Unit) {
            EncoderSelfTest.run(context, callback)
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
        resetCodecRecovery()
        joinToken = token
        Diagnostics.beginSession(appContext, token)
        armTokenTimer(token)
        // Device tuning: this join uses the cached answer (already in the room
        // options); a fresh one is fetched in the background and applies to
        // the next publish. Never blocks the join.
        DeviceTelemetry.refresh(appContext, token) { onMain { refreshPublishDefaults() } }
        subscribeEvents()
        // Auto-subscribe so an audience member (and every co-host) receives all
        // published tracks the moment they join — no manual per-track subscribe.
        debug("joinChannel region=${this.options.region.code} role=${options.role} camera=${options.camera} mic=${options.microphone} sdk=${BuildConfig.SDK_VERSION} device=${Build.MANUFACTURER} ${Build.MODEL} api=${Build.VERSION.SDK_INT}")
        publishedCodec.let { debug("video codec=$it ${AppLoomaVideoEncoderFactory.describe(it)}") }
        if (!hasBluetoothPermission()) {
            android.util.Log.w(TAG, "BLUETOOTH_CONNECT is not granted: Bluetooth headsets will not be offered as an audio route on this phone. Request it at runtime next to RECORD_AUDIO.")
        }
        try {
            room.connect(wsUrl, token, ConnectOptions(autoSubscribe = true))
        } catch (t: Throwable) {
            debug("join failed: ${t.javaClass.simpleName}: ${t.message}")
            Diagnostics.problem(DiagPolicy.PROBLEM_JOIN)
            Diagnostics.endSession("join failed")
            throw t
        }
        isJoined = true
        debug("connected localUid=$localUid")
        eventHandler?.onConnectionStage(AppConnectionStage.CONNECTED, null, elapsedSinceJoin())

        // Participants already in the room when we join never produce a
        // ParticipantConnected event; without this an audience member joining a
        // live stream sees nobody and never receives the host's video.
        seedExistingParticipants()
        lastRole = currentRole
        lastCanPublish = room.localParticipant.permissions?.canPublish ?: (lastRole != AppRole.AUDIENCE)
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
            try { joinChannel(token, wsUrl, options) } catch (t: Throwable) { debug("error: joinChannelAsync ${t.javaClass.simpleName}: ${t.message}"); eventHandler?.onError(t) }
        }
    }

    fun leaveChannel() {
        if (isJoined) debug("leaveChannel")
        Diagnostics.endSession("leave")
        tokenTimer?.cancel(); tokenTimer = null
        resetFallbacks()
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
    fun enableCamera(on: Boolean) = launchSafely { localFallbackMuted = false; room.localParticipant.setCameraEnabled(on); notifyLocalVideoChanged() }
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
        // Remember it first: before the first join there is no route yet, and
        // the choice must survive until the connection opens the audio device.
        SharedAudio.preferSpeaker = on
        debug("speakerphone=$on (active=${SharedAudio.activeScenario != null})")
        if (SharedAudio.activeScenario != null) SharedAudio.reapply(appContext)
    }

    /** Whether the loudspeaker is the chosen phone output (a headset still wins when connected). */
    val isSpeakerphoneOn: Boolean
        get() = currentAudioRoute?.let { it == AppAudioRoute.SPEAKER } ?: (SharedAudio.preferSpeaker ?: (options.audioScenario == AppAudioScenario.MEDIA))

    /**
     * The outputs that can be selected right now, best first: a Bluetooth
     * headset (when paired and, on Android 12+, `BLUETOOTH_CONNECT` is granted),
     * a wired headset, then the speaker and earpiece. Empty before the first
     * join — the switcher starts with the connection.
     */
    fun getAudioRoutes(): List<AppAudioRoute> {
        if (SharedAudio.activeScenario == null) return emptyList()
        val handler = SharedAudio.switcher(appContext)
        return handler.availableAudioDevices.map { it.toRoute() }.distinct()
    }

    /** The output audio is going to right now, or null before the first join. */
    val currentAudioRoute: AppAudioRoute?
        get() = if (SharedAudio.activeScenario == null) null else SharedAudio.switcher(appContext).selectedAudioDevice?.toRoute()

    /**
     * Send audio to one specific output — for an in-call route picker
     * ("Phone / Speaker / AirPods"). Returns false when that output is not in
     * [getAudioRoutes] (nothing changes). A manual choice sticks until the
     * device disappears, after which the automatic order applies again;
     * [AppEventHandler.onAudioRouteChanged] reports every change.
     */
    fun setAudioRoute(route: AppAudioRoute): Boolean {
        if (SharedAudio.activeScenario == null) return false
        val handler = SharedAudio.switcher(appContext)
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
     * Resolution and fps change in place (the camera restarts, the
     * publication stays). Bitrate and degradation change on the live encoder,
     * with no interruption. Only a codec or simulcast change republishes the
     * camera, which costs about half a second of video for viewers and nothing
     * for audio. None of these touch what you receive from others.
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
        val republish = now.simulcast != was.simulcast || resolveCodec(config.codec) != resolveCodec(previous.codec)
        val resize = now.height != was.height || now.fps != was.fps
        val encoderOnly = now.maxBitrate != was.maxBitrate || now.degradation != was.degradation
        debug("setVideoConfig ${was.height}p${was.fps}/${was.maxBitrate / 1000}kbps -> ${now.height}p${now.fps}/${now.maxBitrate / 1000}kbps republish=$republish resize=$resize encoder=$encoderOnly")
        if (republish) {
            republishCamera(track)
        } else {
            if (resize) track.restartTrack(track.options.copy(captureParams = ro.videoTrackCaptureDefaults!!.captureParams))
            if (encoderOnly && !applyEncoderParams(track, was.maxBitrate, now.maxBitrate, now.degradation)) {
                debug("encoder parameters not reachable; bitrate applies at the next publish")
            }
        }
        notifyLocalVideoChanged()
    }

    /**
     * setCameraEnabled(false/true) only mutes and unmutes the same track, so a
     * new codec/simulcast never took effect; unpublish for real and publish a
     * new track with the current defaults. Only the publisher connection
     * renegotiates; subscriptions are untouched.
     */
    private suspend fun republishCamera(track: io.livekit.android.room.track.LocalVideoTrack) {
        val lp = room.localParticipant
        val pub = lp.videoTrackPublications.firstOrNull { it.second === track }?.first
        val wasOn = pub?.muted != true
        lp.videoTrackCaptureDefaults = lp.videoTrackCaptureDefaults.copy(position = track.options.position ?: lp.videoTrackCaptureDefaults.position)
        debug("republish camera: unpublish")
        lp.unpublishTrack(track)
        if (wasOn) lp.setCameraEnabled(true)
        debug("republish camera: published=${localVideoTrack != null}")
    }

    /* ------------------------------------------------------------------ */
    /* Automatic codec recovery                                            */
    /* ------------------------------------------------------------------ */

    private val encoderWatchdog = EncoderWatchdog()
    private val viewerReports = ViewerReports()
    private val fallbackGate = FallbackGate()
    private var watchedRtc: Any? = null
    private val noFrameSince = HashMap<String, Long>()
    private val lastReportSent = HashMap<String, Long>()
    private val remoteCodec = HashMap<String, String>()
    @Volatile private var lastEncoderFailureAt = 0L
    private val encoderFailureListener: (String, String) -> Unit = { codec, detail ->
        val now = System.currentTimeMillis()
        if (now - lastEncoderFailureAt > 5_000) {
            lastEncoderFailureAt = now
            onMain { onEncoderFailure(codec, detail) }
        }
    }

    init { AppLoomaVideoEncoderFactory.addFailureListener(encoderFailureListener) }

    private fun resetCodecRecovery() {
        fallbackGate.reset(); viewerReports.clear(); encoderWatchdog.pause(); watchedRtc = null
        noFrameSince.clear(); lastReportSent.clear(); remoteCodec.clear()
        reportedOutcomes.clear(); healthySince = -1L
    }

    /* Anonymous device reports (DeviceTelemetry): at most one per outcome and codec per join. */
    @Volatile private var joinToken: String? = null
    private val reportedOutcomes = HashSet<String>()
    private var healthySince = -1L

    private fun reportDevice(outcome: String, codec: String, encoderName: String?, fromCodec: String? = null, toCodec: String? = null) {
        if (!reportedOutcomes.add("$outcome:$codec")) return
        DeviceTelemetry.report(joinToken, outcome, codec, encoderName, fromCodec, toCodec)
    }

    private fun encoderNameFor(codec: String): String? = AppLoomaVideoEncoderFactory.findHardwareEncoder(mimeOfCodec(codec))?.name

    /** Re-reads the publish defaults so a new device tuning applies to the next publish. */
    private fun refreshPublishDefaults() {
        try {
            val ro = roomOptions(options.copy(video = videoConfig))
            val lp = room.localParticipant
            lp.videoTrackCaptureDefaults = ro.videoTrackCaptureDefaults ?: lp.videoTrackCaptureDefaults
            lp.videoTrackPublishDefaults = ro.videoTrackPublishDefaults ?: lp.videoTrackPublishDefaults
        } catch (_: Throwable) {}
    }

    /** The codec the camera publishes with right now ("h264", "vp8", "av1"). */
    private val currentCodec: String get() = resolveCodec(videoConfig.codec)

    private fun onEncoderFailure(codec: String, detail: String) {
        if (localVideoTrack == null || !codec.equals(currentCodec, ignoreCase = true)) return
        debug("video encoder failure codec=$codec $detail")
        Diagnostics.problem(DiagPolicy.PROBLEM_ENCODER)
        fallBack(CodecHealth.REASON_ENCODER_ERROR)
    }

    /** Each stats tick: is our camera encoder producing frames and key frames? */
    private fun watchLocalEncoder(report: livekit.org.webrtc.RTCStatsReport?) {
        val lp = room.localParticipant
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack
        val pub = track?.let { t -> lp.videoTrackPublications.firstOrNull { it.second === t }?.first }
        if (track == null || pub == null || pub.muted) { encoderWatchdog.pause(); watchedRtc = null; healthySince = -1L; return }
        val now = System.currentTimeMillis()
        val rtc = try { track.rtcTrack } catch (_: Throwable) { null }
        if (rtc !== watchedRtc) { watchedRtc = rtc; encoderWatchdog.reset(now); healthySince = -1L }
        val video = report?.statsMap?.values
            ?.filter { it.type == "outbound-rtp" && (it.members["kind"] ?: it.members["mediaType"]) == "video" }
            ?: return
        if (video.isEmpty()) return
        fun sum(key: String) = video.sumOf { (it.members[key] as? Number)?.toLong() ?: 0L }
        val sample = EncoderSample(now, sum("framesEncoded"), sum("keyFramesEncoded"), sum("framesSent"))
        val reason = encoderWatchdog.onSample(sample)
        if (reason == null) {
            // One "ok" per join once the camera has been encoding for 20 s.
            if (sample.keyFramesEncoded > 0 && sample.framesEncoded > 0) {
                if (healthySince < 0) healthySince = now
                if (now - healthySince >= 20_000) currentCodec.let { reportDevice("ok", it, encoderNameFor(it)) }
            }
            return
        }
        healthySince = -1L
        debug("video encoder unhealthy: frames=${sample.framesEncoded} keyFrames=${sample.keyFramesEncoded} sent=${sample.framesSent}")
        fallBack(reason)
    }

    /**
     * Move the camera to the safe encoding path: software VP8 when a hardware
     * encoder was in use (remembered for this phone), otherwise one republish
     * as a single layer. At most once per join.
     */
    private fun fallBack(reason: String) = launchSafely {
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack ?: return@launchSafely
        if (!fallbackGate.tryAcquire()) { debug("codec recovery already used in this session; $reason ignored"); return@launchSafely }
        val codec = currentCodec
        val mime = mimeOfCodec(codec)
        val hw = AppLoomaVideoEncoderFactory.findHardwareEncoder(mime)
        val from = "$codec:${if (hw != null) "hw" else "sw"}"
        val to: String
        if (hw != null) {
            CodecMemory.rememberBad(appContext, mime, hw.name)
            AppLoomaVideoEncoderFactory.forceSoftware(mime)
            AppLoomaVideoEncoderFactory.forceSoftware(VP8_MIME)
            to = "vp8:sw"
        } else {
            videoConfig = videoConfig.copy(simulcast = false)
            to = "$from:single"
        }
        debug("codec recovery: $from -> $to reason=$reason")
        Diagnostics.problem(DiagPolicy.PROBLEM_FALLBACK)
        reportDevice("fallback", codec, hw?.name, fromCodec = codec, toCodec = if (hw != null) "vp8" else codec)
        val ro = roomOptions(options.copy(video = videoConfig))
        val lp = room.localParticipant
        lp.videoTrackCaptureDefaults = ro.videoTrackCaptureDefaults ?: lp.videoTrackCaptureDefaults
        lp.videoTrackPublishDefaults = ro.videoTrackPublishDefaults ?: lp.videoTrackPublishDefaults
        republishCamera(track)
        notifyLocalVideoChanged()
        eventHandler?.onVideoCodecFallback(from, to, reason)
    }

    /**
     * Viewer side: a remote camera shown in a visible view, whose sender is
     * publishing, decoded at 0 fps for more than 6 s. Tell that publisher (and
     * only them) on the internal report topic; at most once per 20 s each.
     */
    private fun reportUndecodableVideo(remote: List<AppRemoteStats>) {
        val now = System.currentTimeMillis()
        val shown = remoteViews.filter { it.isShown }.mapNotNull { it.boundUid }.toSet()
        for (u in users.values) {
            val uid = u.uid
            val pub = u.raw.videoTrackPublications.firstOrNull()?.first as? RemoteTrackPublication
            val live = pub != null && pub.subscribed && !pub.muted && uid in shown && uid !in videoDisabled
            val fps = remote.firstOrNull { it.uid == uid }?.videoFps ?: 0
            if (!live || fps > 0) { noFrameSince.remove(uid); continue }
            val since = noFrameSince.getOrPut(uid) { now }
            if (now - since <= 6_000) continue
            if (now - (lastReportSent[uid] ?: 0L) < 20_000) continue
            lastReportSent[uid] = now
            val payload = JSONObject().apply { put("v", 1); put("t", CodecHealth.REASON_NO_FRAMES); put("codec", remoteCodec[uid] ?: "") }
            debug("video from uid=$uid has no picture for ${(now - since) / 1000}s; reporting to the sender")
            launchSafely {
                room.localParticipant.publishData(
                    payload.toString().toByteArray(Charsets.UTF_8),
                    io.livekit.android.room.track.DataPublishReliability.RELIABLE,
                    CodecHealth.REPORT_TOPIC,
                    listOf(io.livekit.android.room.participant.Participant.Identity(uid)),
                )
            }
        }
    }

    /** An SDK-internal data message; never reaches the app. */
    private fun onInternalData(topic: String?, data: ByteArray, from: RemoteParticipant?) {
        if (topic != CodecHealth.REPORT_TOPIC || from == null) return
        val msg = try { JSONObject(String(data, Charsets.UTF_8)) } catch (_: Exception) { return }
        if (msg.optString("t") != CodecHealth.REASON_NO_FRAMES) return
        if (localVideoTrack == null) return
        val viewer = from.identity?.value ?: return
        debug("viewer uid=$viewer reports no picture (codec=${msg.optString("codec")})")
        Diagnostics.problem(DiagPolicy.PROBLEM_NO_FRAMES)
        currentCodec.let { reportDevice(CodecHealth.REASON_VIEWERS, it, encoderNameFor(it)) }
        if (viewerReports.onReport(System.currentTimeMillis(), viewer, room.remoteParticipants.size)) {
            fallBack(CodecHealth.REASON_VIEWERS)
        }
    }

    /** Bitrate / degradation straight on the sender: no republish, no renegotiation. */
    private fun applyEncoderParams(track: io.livekit.android.room.track.LocalVideoTrack, oldMax: Int, newMax: Int, degradation: AppVideoDegradation): Boolean {
        val sender = TrackAccess.sender(track) ?: return false
        return try {
            val params = sender.parameters
            val single = params.encodings.size <= 1
            params.encodings.forEach { e ->
                val cur = e.maxBitrateBps
                e.maxBitrateBps = if (single || cur == null || oldMax <= 0) newMax else (cur.toLong() * newMax / oldMax).toInt()
            }
            degradationOf(degradation)?.let { params.degradationPreference = it }
            val ok = sender.setParameters(params)
            debug("encoder params max=${newMax / 1000}kbps layers=${params.encodings.size} degradation=$degradation ok=$ok")
            ok
        } catch (t: Throwable) { debug("encoder params failed: $t"); false }
    }

    /**
     * A hook on every captured camera frame, before it is encoded and shown
     * in your preview: brightness, beauty, filters. Pass null to remove it.
     * Survives camera restarts, camera switches and republishes. Each frame is
     * copied to CPU memory for the processor, which costs CPU; keep it off
     * when not needed.
     */
    fun setVideoProcessor(processor: AppVideoProcessor?) {
        videoProcessor = processor
        appliedProcessorTrack = null
        onMain { applyVideoProcessor() }
    }

    private var videoProcessor: AppVideoProcessor? = null
    private var appliedProcessorTrack: VideoTrack? = null
    private var appliedSource: livekit.org.webrtc.VideoSource? = null

    private fun applyVideoProcessor() {
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack
        val source = track?.let { TrackAccess.source(it) }
        if (track === appliedProcessorTrack && source === appliedSource) return
        appliedProcessorTrack = track
        appliedSource = source
        if (source == null) { if (track != null && videoProcessor != null) debug("video processor: capture source not reachable"); return }
        try {
            source.setVideoProcessor(videoProcessor?.let { ProcessorAdapter(it) })
            debug("video processor ${if (videoProcessor != null) "installed" else "removed"}")
        } catch (t: Throwable) { debug("video processor failed: $t") }
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
     * small tile or a weak link, HIGH for the spotlight. Only matters when the
     * sender publishes several layers (simulcast, i.e. [AppVideoMode.ADAPTIVE]).
     *
     * With adaptive stream on (ADAPTIVE mode, unless
     * [AppEngineOptions.adaptiveStream] is false) the layer follows the size of
     * the view showing the video and this call has no effect; it is logged
     * through the debug log. Turn adaptive stream off to choose layers by hand.
     */
    fun setRemoteVideoQuality(uid: String, quality: AppRemoteQuality) {
        val u = users[uid] ?: return
        val q = when (quality) { AppRemoteQuality.LOW -> VideoQuality.LOW; AppRemoteQuality.MEDIUM -> VideoQuality.MEDIUM; AppRemoteQuality.HIGH -> VideoQuality.HIGH }
        u.raw.videoTrackPublications.forEach { (pub, _) ->
            val rp = pub as? RemoteTrackPublication ?: return@forEach
            if (rp.isAutoManaged) debug("setRemoteVideoQuality uid=$uid ignored: adaptive stream picks the layer from the view size")
            else rp.setVideoQuality(q)
        }
    }

    /**
     * Stop receiving one user's video while keeping their audio: the manual
     * audio-only fallback for a weak network or a tile scrolled off screen.
     *
     * With adaptive stream on, the engine ignores a pause request, so the SDK
     * unsubscribes the video instead (and subscribes again on `true`, about a
     * second until the first frame). Views attached to the user pick the video
     * up again by themselves.
     */
    fun setRemoteVideoEnabled(uid: String, enabled: Boolean) {
        val u = users[uid] ?: return
        if (enabled) videoDisabled.remove(uid) else videoDisabled.add(uid)
        u.raw.videoTrackPublications.forEach { (pub, _) ->
            val rp = pub as? RemoteTrackPublication ?: return@forEach
            if (rp.isAutoManaged) {
                debug("setRemoteVideoEnabled uid=$uid enabled=$enabled via subscription (adaptive stream on)")
                rp.setSubscribed(enabled)
            } else {
                if (enabled && !rp.subscribed) rp.setSubscribed(true)
                rp.setEnabled(enabled)
            }
        }
    }

    // Users whose video the app turned off; the stall watchdog leaves them alone.
    private val videoDisabled = HashSet<String>()
    private val stallSince = HashMap<String, Long>()
    private val lastRecovery = HashMap<String, Long>()

    /**
     * Drop and re-take the subscription to one user's camera (and microphone,
     * when [includeAudio] is true, or when their audio is published but not
     * being received). The remedy for a remote picture stuck at 0 fps while
     * the sender is sending: the first frame is usually back within about a
     * second. Views attached to the user re-bind by themselves. Does nothing
     * for an unknown uid.
     */
    @JvmOverloads
    fun resubscribe(uid: String, includeAudio: Boolean = false) = launchSafely {
        val u = users[uid] ?: return@launchSafely
        val video = u.raw.videoTrackPublications.mapNotNull { it.first as? RemoteTrackPublication }
        val audio = u.raw.audioTrackPublications.mapNotNull { it.first as? RemoteTrackPublication }
            .filter { includeAudio || !it.subscribed }
        debug("resubscribe uid=$uid video=${video.size} audio=${audio.size}")
        (video + audio).forEach { it.setSubscribed(false) }
        delay(250)
        (video + audio).forEach { it.setSubscribed(true) }
        stallSince.remove(uid)
    }

    /**
     * Stall watchdog, run on each stats tick. A remote camera shown in a
     * visible view whose sender is publishing, received at 0 fps for more
     * than 4 s, is resubscribed; at most once per 15 s per user.
     */
    private fun watchStalls(remote: List<AppRemoteStats>) {
        if (!options.autoRecoverStalledVideo) return
        val now = System.currentTimeMillis()
        val shown = remoteViews.filter { it.isShown }.mapNotNull { it.boundUid }.toSet()
        for (u in users.values) {
            val uid = u.uid
            val pub = u.raw.videoTrackPublications.firstOrNull()?.first as? RemoteTrackPublication
            val live = pub != null && !pub.muted && uid in shown && uid !in videoDisabled
            val fps = remote.firstOrNull { it.uid == uid }?.videoFps ?: 0
            if (!live || fps > 0) { stallSince.remove(uid); continue }
            val since = stallSince.getOrPut(uid) { now }
            if (now - since <= 4_000) continue
            if (now - (lastRecovery[uid] ?: 0L) < 15_000) continue
            lastRecovery[uid] = now
            debug("video stalled uid=$uid 0fps for ${(now - since) / 1000}s; resubscribing")
            resubscribe(uid)
        }
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
        scope.launch { try { block() } catch (t: Throwable) { debug("error: ${t.javaClass.simpleName}: ${t.message}"); eventHandler?.onError(t) } }
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

    /** Whether the local camera faces the user (drives the default self-view mirror). */
    internal val localCameraIsFront: Boolean
        get() = (localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack)?.options?.position != io.livekit.android.room.track.CameraPosition.BACK

    /** Prepares a renderer view to receive frames. Used by [AppVideoView]. */
    internal fun initRenderer(view: io.livekit.android.renderer.TextureViewRenderer) { room.initVideoRenderer(view) }

    // Views waiting for a track that has not arrived yet (see AppVideoView.attach).
    internal val trackWaiters = LinkedHashSet<AppVideoView>()
    // Views showing the local camera; re-bound on every local track change.
    internal val localViews = LinkedHashSet<AppVideoView>()
    // Views showing a remote camera; re-bound when that user's track is replaced.
    internal val remoteViews = LinkedHashSet<AppVideoView>()
    private var lastLocalTrack: VideoTrack? = null
    private var lastLocalRtc: livekit.org.webrtc.VideoTrack? = null
    private var joinStartedAt = 0L
    private val firstFrameSeen = HashSet<String>()
    private fun elapsedSinceJoin() = if (joinStartedAt == 0L) 0L else System.currentTimeMillis() - joinStartedAt

    /**
     * The local camera track was replaced (on/off, switch, config). Re-bind
     * every self-view and tell the app. Safe to call when nothing changed.
     */
    internal fun notifyLocalVideoChanged() {
        val now = localVideoTrack
        // A restart (resolution change) keeps the track object and swaps the
        // native track inside it, so compare both.
        val rtc = try { now?.rtcTrack } catch (_: Throwable) { null }
        val changed = now !== lastLocalTrack || rtc !== lastLocalRtc
        lastLocalTrack = now
        lastLocalRtc = rtc
        applyVideoProcessor()
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
                    if (now - lastDiagStatsAt >= 10_000) {
                        lastDiagStatsAt = now
                        debug("stats rtt=${stats.rttMs}ms up=${stats.uploadBitrate / 1000}kbps down=${stats.downloadBitrate / 1000}kbps lossUp=${(stats.uploadLoss * 100).toInt()}% lossDown=${(stats.downloadLoss * 100).toInt()}% quality=${stats.quality} remotes=${room.remoteParticipants.size}")
                    }
                    applyFallbacks(stats)
                    // What our own camera is sending: the top simulcast layer
                    // that carries frames, reported only when it changes.
                    if (localVideoTrack != null) reportLocalQuality(reports[0], secs) else { lastQuality = null; lastVideoBytes = 0L }
                    // Belt and braces for self-views: if the local track was swapped
                    // by a path that raised no event, the next tick re-binds it.
                    if (localVideoTrack !== lastLocalTrack || localVideoTrack?.rtcTrack !== lastLocalRtc) notifyLocalVideoChanged()
                    // Per-remote-user health — which picture is stuck, whose audio is dropping.
                    val remote = users.values.mapNotNull { u -> remoteStatsFor(u) }
                    if (remote.isNotEmpty()) eventHandler?.onRemoteStats(remote)
                    watchStalls(remote)
                    reportUndecodableVideo(remote)
                    watchLocalEncoder(reports[0])
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
        val videoReport = trackStats(video)
        videoReport?.statsMap?.values?.filter { it.type == "inbound-rtp" }?.forEach { s ->
            val m = s.members
            (m["codecId"] as? String)?.let { id -> (videoReport.statsMap[id]?.members?.get("mimeType") as? String)?.let { remoteCodec[u.uid] = it } }
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
                            remoteViews.toList().forEach { it.remoteTrackChanged(this@AppEngine, u) }
                            // Time-to-first-frame, once per user per join.
                            if (firstFrameSeen.add(u.uid)) FirstFrameSink(u.uid, event.track as VideoTrack).start()
                        }
                    }
                    is RoomEvent.TrackPublished ->
                        if (event.participant === room.localParticipant) notifyLocalVideoChanged()
                        else (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackUnsubscribed -> (event.participant as? RemoteParticipant)?.let { p ->
                        if (event.track is VideoTrack) users[p.identity?.value]?.let { u -> remoteViews.toList().forEach { it.remoteTrackChanged(this@AppEngine, u) } }
                    }
                    is RoomEvent.TrackUnpublished -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.TrackMuted -> (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    // Camera off→on keeps the publication and swaps the track underneath:
                    // the unmute is the moment self-views must re-bind.
                    is RoomEvent.TrackUnmuted ->
                        if (event.participant === room.localParticipant) notifyLocalVideoChanged()
                        else (event.participant as? RemoteParticipant)?.let { p -> eventHandler?.onUserMediaChanged(userFor(p), p.isCameraEnabled(), p.isMicrophoneEnabled()) }
                    is RoomEvent.LocalTrackSubscribed -> if (event.publication.track is VideoTrack) notifyLocalVideoChanged()
                    is RoomEvent.Reconnecting -> { debug("reconnecting"); Diagnostics.reconnecting(); eventHandler?.onReconnecting() }
                    is RoomEvent.Reconnected -> {
                        debug("reconnected")
                        users.clear()
                        seedExistingParticipants()
                        eventHandler?.onReconnected()
                    }
                    is RoomEvent.Disconnected -> {
                        debug("disconnected reason=${event.reason?.name} error=${event.error}")
                        Diagnostics.endSession("disconnected")
                        isJoined = false
                        tokenTimer?.cancel(); tokenTimer = null
                        resetFallbacks()
                        mixer.stop()
                        statsJob?.cancel()
                        // Report everyone as gone; a stale roster after a
                        // disconnect made remoteUsers wrong until the next join.
                        users.values.forEach { eventHandler?.onUserLeft(it) }
                        users.clear()
                        eventHandler?.onDisconnected(event.reason?.name)
                    }
                    is RoomEvent.DataReceived -> if (CodecHealth.isInternalTopic(event.topic)) {
                        // SDK-internal traffic (codec recovery reports) never reaches the app.
                        onInternalData(event.topic, event.data, event.participant)
                    } else {
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
        val canPublish = room.localParticipant.permissions?.canPublish ?: (role != AppRole.AUDIENCE)
        // A seat change arrives as two separate events: the role (metadata) and
        // the publish permission. They can land in either order. Reacting to the
        // role alone published the microphone before the permission existed
        // ("no permission to publish"), and when the permission then arrived the
        // role had not changed, so the microphone was never published again and
        // the speaker could not be heard (seen in a customer voice room). Act on
        // the pair instead.
        if (role == lastRole && canPublish == lastCanPublish) return
        val roleChanged = role != lastRole
        lastRole = role
        lastCanPublish = canPublish
        if (role == AppRole.AUDIENCE || !canPublish) {
            // Sent back to the audience: nothing of ours may play, music included.
            mixer.stop(); mixer.micMuted = false; micWanted = false
            try { room.localParticipant.setMicrophoneEnabled(false); room.localParticipant.setCameraEnabled(false) } catch (_: Throwable) {}
        } else {
            micWanted = true; mixer.micMuted = false
            try { room.localParticipant.setMicrophoneEnabled(true) } catch (t: Throwable) { eventHandler?.onError(t) }
        }
        debug("seat role=$role canPublish=$canPublish")
        if (roleChanged) eventHandler?.onRoleChanged(role)
    }

    /* ------------------------------------------------------------------ */
    /* Token renewal                                                       */
    /* ------------------------------------------------------------------ */

    private var tokenTimer: Job? = null

    private fun armTokenTimer(token: String) {
        tokenTimer?.cancel()
        val wait = TokenExpiry.warnDelayMs(token, System.currentTimeMillis()) ?: return
        tokenTimer = scope.launch {
            delay(wait)
            if (joinToken == token) {
                debug("join token expires in about ${TokenExpiry.WARN_BEFORE_MS / 1000} s")
                eventHandler?.onTokenWillExpire(token)
            }
        }
    }

    /**
     * Replace the join token after [AppEventHandler.onTokenWillExpire]. The
     * SDK uses it from now on for its own requests and for a later join, and
     * warns again 30 s before the new one expires. While you are connected
     * the media server keeps the live connection's credential fresh by
     * itself, so the call is not interrupted. Unreleased.
     */
    fun renewToken(token: String) {
        if (token.isBlank()) return
        joinToken = token
        Diagnostics.updateToken(token)
        if (isJoined) armTokenTimer(token)
        debug("join token renewed")
    }

    /* ------------------------------------------------------------------ */
    /* Network fallback                                                    */
    /* ------------------------------------------------------------------ */

    private var subscribeFallback = AppFallbackOption.NONE
    private var publishFallback = AppFallbackOption.NONE
    private val downlinkPolicy = FallbackPolicy()
    private val uplinkPolicy = FallbackPolicy()
    /** Users whose video the subscribe fallback turned off (to turn back on). */
    private val fallbackHidden = HashSet<String>()
    /** The publish fallback muted the camera and will unmute it. */
    private var localFallbackMuted = false
    private var localFallbackBitrate: Pair<Int, Int>? = null

    /**
     * What to give up on the video you receive when your downlink is poor for
     * about 4 s: VIDEO_LOW_QUALITY asks for every remote camera's low layer
     * (only where the sender publishes layers and adaptive stream is off),
     * AUDIO_ONLY stops receiving remote video. Everything comes back after
     * about 10 s of good network; video you turned off yourself stays off.
     * Reported by [AppEventHandler.onFallbackStateChanged] with isLocal false.
     * Default NONE. Unreleased.
     */
    fun setRemoteSubscribeFallback(option: AppFallbackOption) {
        if (option == subscribeFallback) return
        if (downlinkPolicy.active) restoreSubscribe()
        downlinkPolicy.reset()
        subscribeFallback = option
    }

    /**
     * What to give up on the video you send when your uplink is poor for
     * about 4 s: VIDEO_LOW_QUALITY lowers the camera bitrate to 30 % (at
     * least 300 kbps), AUDIO_ONLY pauses the camera. Restored after about
     * 10 s of good network. Reported by [AppEventHandler.onFallbackStateChanged]
     * with isLocal true. Default NONE. Unreleased.
     */
    fun setLocalPublishFallback(option: AppFallbackOption) {
        if (option == publishFallback) return
        if (uplinkPolicy.active) launchSafely { restorePublish() }
        uplinkPolicy.reset()
        publishFallback = option
    }

    private fun applyFallbacks(stats: AppStats) {
        val now = System.currentTimeMillis()
        if (subscribeFallback != AppFallbackOption.NONE) {
            when (downlinkPolicy.onSample(now, FallbackPolicy.isPoor(stats.quality, stats.downloadLoss), FallbackPolicy.isGood(stats.quality, stats.downloadLoss))) {
                true -> { debug("fallback subscribe -> $subscribeFallback"); eventHandler?.onFallbackStateChanged(false, subscribeFallback) }
                false -> { restoreSubscribe(); debug("fallback subscribe restored"); eventHandler?.onFallbackStateChanged(false, AppFallbackOption.NONE) }
                null -> {}
            }
            // Re-applied each tick so users who join during a fallback are covered too.
            if (downlinkPolicy.active) applySubscribeFallback()
        }
        if (publishFallback != AppFallbackOption.NONE) {
            when (uplinkPolicy.onSample(now, FallbackPolicy.isPoor(stats.quality, stats.uploadLoss), FallbackPolicy.isGood(stats.quality, stats.uploadLoss))) {
                true -> launchSafely { applyPublishFallback(); debug("fallback publish -> $publishFallback"); eventHandler?.onFallbackStateChanged(true, publishFallback) }
                false -> launchSafely { restorePublish(); debug("fallback publish restored"); eventHandler?.onFallbackStateChanged(true, AppFallbackOption.NONE) }
                null -> {}
            }
        }
    }

    private fun applySubscribeFallback() {
        users.values.forEach { u ->
            if (u.uid in videoDisabled) return@forEach
            u.raw.videoTrackPublications.forEach { (pub, _) ->
                val rp = pub as? RemoteTrackPublication ?: return@forEach
                when (subscribeFallback) {
                    AppFallbackOption.AUDIO_ONLY -> if (rp.subscribed) { rp.setSubscribed(false); fallbackHidden.add(u.uid) }
                    AppFallbackOption.VIDEO_LOW_QUALITY -> if (!rp.isAutoManaged) rp.setVideoQuality(VideoQuality.LOW)
                    AppFallbackOption.NONE -> {}
                }
            }
        }
    }

    private fun restoreSubscribe() {
        users.values.forEach { u ->
            if (u.uid in videoDisabled) return@forEach
            u.raw.videoTrackPublications.forEach { (pub, _) ->
                val rp = pub as? RemoteTrackPublication ?: return@forEach
                if (u.uid in fallbackHidden && !rp.subscribed) rp.setSubscribed(true)
                if (subscribeFallback == AppFallbackOption.VIDEO_LOW_QUALITY && !rp.isAutoManaged) rp.setVideoQuality(VideoQuality.HIGH)
            }
        }
        fallbackHidden.clear()
    }

    private suspend fun applyPublishFallback() {
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack ?: return
        when (publishFallback) {
            AppFallbackOption.AUDIO_ONLY -> {
                if (room.localParticipant.isCameraEnabled()) { room.localParticipant.setCameraEnabled(false); localFallbackMuted = true; notifyLocalVideoChanged() }
            }
            AppFallbackOption.VIDEO_LOW_QUALITY -> {
                val v = resolveVideo(videoConfig)
                val low = maxOf(300_000, v.maxBitrate * 3 / 10)
                if (low < v.maxBitrate && applyEncoderParams(track, v.maxBitrate, low, v.degradation)) localFallbackBitrate = v.maxBitrate to low
            }
            AppFallbackOption.NONE -> {}
        }
    }

    private suspend fun restorePublish() {
        if (localFallbackMuted && isJoined) { localFallbackMuted = false; room.localParticipant.setCameraEnabled(true); notifyLocalVideoChanged() }
        localFallbackMuted = false
        val track = localVideoTrack as? io.livekit.android.room.track.LocalVideoTrack
        localFallbackBitrate?.let { (full, low) -> if (track != null) applyEncoderParams(track, low, full, resolveVideo(videoConfig).degradation) }
        localFallbackBitrate = null
    }

    private fun resetFallbacks() {
        downlinkPolicy.reset(); uplinkPolicy.reset()
        fallbackHidden.clear(); localFallbackMuted = false; localFallbackBitrate = null
    }

    /* ------------------------------------------------------------------ */
    /* Pre-call network test                                               */
    /* ------------------------------------------------------------------ */

    private var networkTestJob: Job? = null

    /**
     * Test the network before a call: opens a short, separate connection with
     * [token] (mint it for a throwaway test channel, e.g. "nettest-<uid>";
     * nothing is published), samples connection quality, round-trip time,
     * jitter and loss for about 5 s, leaves, and calls [callback] once on the
     * main thread. Gives up after 10 s with [AppNetworkTestResult.error] set.
     * Does not touch the channel you are in. Loss and jitter are measured on
     * the control traffic only, so they read 0 on a clean line; quality is
     * the media server's estimate. Unreleased.
     */
    fun startNetworkTest(serverUrl: String, token: String, callback: (AppNetworkTestResult) -> Unit) {
        networkTestJob?.cancel()
        networkTestJob = scope.launch {
            val probe = try {
                LiveKit.create(appContext, options = RoomOptions(adaptiveStream = false), overrides = LiveKitOverrides(audioOptions = AudioOptions(audioHandler = io.livekit.android.audio.NoAudioHandler())))
            } catch (t: Throwable) { callback(failedTest("could not start: ${t.message}")); return@launch }
            var delivered = false
            try {
                val result = kotlinx.coroutines.withTimeoutOrNull(10_000) {
                    probe.connect(serverUrl, token, ConnectOptions(autoSubscribe = false))
                    var rtt = -1; var jitter = -1
                    var outSent = 0L; var outLost = 0L; var inRecv = 0L; var inLost = 0L
                    val qualities = ArrayList<AppNetworkQuality>()
                    repeat(5) {
                        delay(1000)
                        qualities += qualityOf(probe.localParticipant.connectionQuality)
                        for (publisher in listOf(true, false)) {
                            val report = kotlinx.coroutines.suspendCancellableCoroutine<livekit.org.webrtc.RTCStatsReport?> { cont ->
                                try {
                                    if (publisher) probe.getPublisherRTCStats { cont.resume(it) {} } else probe.getSubscriberRTCStats { cont.resume(it) {} }
                                } catch (_: Throwable) { cont.resume(null) {} }
                            }
                            report?.statsMap?.values?.forEach { st ->
                                val m = st.members
                                when (st.type) {
                                    "candidate-pair" -> if (m["state"] == "succeeded") (m["currentRoundTripTime"] as? Double)?.let { rtt = (it * 1000).toInt() }
                                    "outbound-rtp" -> outSent = (m["packetsSent"] as? Number)?.toLong() ?: outSent
                                    "remote-inbound-rtp" -> outLost = (m["packetsLost"] as? Number)?.toLong() ?: outLost
                                    "inbound-rtp" -> {
                                        inRecv = (m["packetsReceived"] as? Number)?.toLong() ?: inRecv
                                        inLost = (m["packetsLost"] as? Number)?.toLong() ?: inLost
                                        (m["jitter"] as? Double)?.let { jitter = (it * 1000).toInt() }
                                    }
                                }
                            }
                        }
                    }
                    val known = qualities.filter { it != AppNetworkQuality.UNKNOWN }
                    // The worst reading of the window, so a spike is not hidden.
                    val quality = known.maxByOrNull { it.ordinal } ?: AppNetworkQuality.UNKNOWN
                    val upLoss = if (outSent + outLost > 0) outLost.toFloat() / (outSent + outLost) else 0f
                    val downLoss = if (inRecv + inLost > 0) inLost.toFloat() / (inRecv + inLost) else 0f
                    AppNetworkTestResult(
                        uplinkQuality = if (upLoss > FallbackPolicy.POOR_LOSS && quality.ordinal < AppNetworkQuality.POOR.ordinal) AppNetworkQuality.POOR else quality,
                        downlinkQuality = if (downLoss > FallbackPolicy.POOR_LOSS && quality.ordinal < AppNetworkQuality.POOR.ordinal) AppNetworkQuality.POOR else quality,
                        rttMs = rtt, jitterMs = jitter, uplinkLoss = upLoss, downlinkLoss = downLoss,
                    )
                }
                delivered = true
                callback(result ?: failedTest("timed out"))
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                delivered = true
                callback(failedTest(t.message ?: "network test failed"))
            } finally {
                try { probe.disconnect() } catch (_: Throwable) {}
                try { probe.release() } catch (_: Throwable) {}
                if (!delivered) debug("network test stopped")
            }
        }
    }

    /** Stop a running [startNetworkTest]; its callback is not called. Unreleased. */
    fun stopNetworkTest() { networkTestJob?.cancel(); networkTestJob = null }

    private fun failedTest(error: String) = AppNetworkTestResult(AppNetworkQuality.UNKNOWN, AppNetworkQuality.UNKNOWN, -1, -1, 0f, 0f, error)

    /**
     * Upload this device's recent SDK log lines to AppLooma support now (or
     * with the next join when not in a channel), for example from a "report a
     * problem" button. [reason] is a short label you choose ("user_report").
     * Only SDK-produced lines are sent, scrubbed of names, metadata, tokens
     * and URL parameters; never audio, video or messages. Does nothing when
     * [AppEngineOptions.remoteDiagnostics] is false. Unreleased.
     */
    fun uploadDiagnostics(reason: String) {
        try { Diagnostics.requestUpload(reason) } catch (_: Throwable) {}
    }

    /** Release all resources. */
    fun destroy() {
        stopNetworkTest()
        leaveChannel()
        SharedAudio.unlisten(this)
        AppLoomaVideoEncoderFactory.removeFailureListener(encoderFailureListener)
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
    /** True once a sample above ±2 proves the buffer is in 16-bit float range. */
    @Volatile private var s16Scale = false

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

        // The engine hands this stage audio as floats in 16-bit range
        // (±32768), not ±1. Treating it as ±1 made every gain other than
        // unity (setMicGain, AGC target) squash the voice to ±1 in the limiter,
        // i.e. silence (reported by a customer: host inaudible with
        // setMicGain(200)). Detect the scale once from the signal and work in
        // ±1 internally; the gate and AGC levels are then true dBFS.
        var peak = 0f
        var sum = 0.0
        for (i in 0 until n) { val v = fb.get(i); val a = kotlin.math.abs(v); if (a > peak) peak = a; sum += (v * v).toDouble() }
        if (!s16Scale && peak > 2f) s16Scale = true
        val scale = if (s16Scale) S16 else 1f
        val levelDb = (20.0 * kotlin.math.log10(kotlin.math.sqrt(sum / n) / scale + 1e-9)).toFloat()
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
            var y = fb.get(i) / scale * g
            // Soft knee above ±0.8, hard ceiling at ±1: loud peaks compress instead of clip.
            val a = kotlin.math.abs(y)
            if (a > KNEE) {
                val over = a - KNEE
                val limited = KNEE + (1f - KNEE) * (1f - kotlin.math.exp(-over / (1f - KNEE)))
                y = if (y < 0) -limited else limited
            }
            fb.put(i, y * scale)
        }
    }

    private companion object {
        const val KNEE = 0.8f
        const val S16 = 32768f
        /** -20 dB: noise is eased down, not muted, so the room never sounds "dead". */
        const val GATE_FLOOR = 0.1f
        const val AGC_MIN = 0.25f  // -12 dB
        const val AGC_MAX = 8f     // +18 dB
    }
}
