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

/**
 * Which codec your camera is published with.
 *
 * AUTO (default) picks per phone: VP8 where the chipset has a hardware VP8
 * encoder, otherwise H.264 (which every Android phone encodes in hardware),
 * always with a VP8 backup layer so a viewer that cannot decode H.264 still
 * gets a picture. Force one only for a known fleet.
 */
enum class AppVideoCodec {
    AUTO, VP8, H264,
    /**
     * H.265 is not available on Android: the engine's encoder factory does not
     * drive H.265 hardware. Falls back to AUTO with a warning; kept so one
     * config compiles on every platform.
     */
    H265,
    /**
     * AV1, only where the phone has a hardware AV1 encoder (a few 2023+ flagship
     * chipsets); everywhere else it falls back to AUTO with a warning. Always
     * with a VP8 backup layer.
     */
    AV1,
}

/**
 * What the encoder gives up first when the network or the CPU cannot carry
 * the full picture.
 *
 * AUTO (default) is the engine's choice for a camera — frame rate is kept and
 * resolution drops, which looks smoothest in a call. KEEP_RESOLUTION keeps the
 * picture sharp and drops frames instead — for a 1080p broadcast where
 * crispness is the product. KEEP_FRAMERATE keeps motion smooth and lets the
 * picture soften. BALANCED gives up a little of each.
 */
enum class AppVideoDegradation { AUTO, KEEP_RESOLUTION, KEEP_FRAMERATE, BALANCED }

/**
 * How the SDK tunes your camera when you leave the details to it.
 *
 * STABLE_HD (default) holds one steady HD picture: a single layer, resolution
 * kept under pressure (frame rate gives way first) and receivers pinned to the
 * full picture. The resolution is picked once per phone: 1080p30 at 3.5 Mbps
 * where the chosen codec has a hardware encoder, 720p30 at 1.8 Mbps where it
 * would be encoded on the CPU.
 *
 * ULTRA_HD_4K is the premium mode: 2160p30, one layer, resolution kept. It
 * needs a hardware encoder that can do 3840x2160 (see
 * [AppEngine.isUltraHdSupported]); where there is none the SDK falls back to
 * STABLE_HD and logs a warning. Both ends need a strong network (20 Mbps or
 * more each way recommended) and the phone runs warmer.
 *
 * ADAPTIVE is the behaviour before 0.4.11: three layers, receivers switch
 * layers by view size and network, resolution may drop to keep motion smooth.
 *
 * Any field you set explicitly on [AppVideoConfig] wins over the mode.
 * Since 0.4.11.
 */
enum class AppVideoMode { STABLE_HD, ULTRA_HD_4K, ADAPTIVE }

/**
 * Capture and encode settings for your own camera.
 *
 * `mode` picks sensible values for everything below; see [AppVideoMode].
 * Fields left at their default follow the mode, fields you set win.
 *
 * `maxBitrate` is in **bits per second** — `1_900_000` for 1.9 Mbps. A value
 * under 10 000 is almost certainly kbps by mistake and is logged as a warning.
 * `height` must be one of 360 / 540 / 720 / 1080 / 1440 / 2160; anything else
 * snaps to the nearest preset (also logged). 0 = chosen by the mode.
 *
 * With `maxBitrate` 0 the cap comes from the mode: 3.5 Mbps at 1080p and
 * 1.8 Mbps at 720p for STABLE_HD, 25 Mbps for ULTRA_HD_4K; in ADAPTIVE it is
 * 5 Mbps at 1440p, 4 Mbps at 1080p, 2.2 Mbps at 720p, and the preset default
 * below that.
 */
data class AppVideoConfig @JvmOverloads constructor(
    /** 360, 540, 720, 1080, 1440 or 2160 — the short edge of a 16:9 frame. 0 = chosen by [mode]. */
    val height: Int = 0,
    val fps: Int = 30,
    /** Cap on the encoder's bitrate in bits per second (e.g. 1_900_000); 0 = chosen by [mode]. */
    val maxBitrate: Int = 0,
    /**
     * Three layers so weak links still get a picture; false = one layer only.
     * null = chosen by [mode] (on in ADAPTIVE, off in STABLE_HD and ULTRA_HD_4K).
     */
    val simulcast: Boolean? = null,
    /** VP8, H.264, AV1, or let the engine choose per phone. */
    val codec: AppVideoCodec = AppVideoCodec.AUTO,
    /**
     * What to give up first under pressure. See [AppVideoDegradation]. AUTO =
     * chosen by [mode] (BALANCED in STABLE_HD, KEEP_RESOLUTION in ULTRA_HD_4K). Since 0.4.10.
     */
    val degradation: AppVideoDegradation = AppVideoDegradation.AUTO,
    /**
     * Floor on the encoder's bitrate in bits per second; 0 = engine default.
     * Reserved: the engine exposes no per-sender minimum on Android today, so
     * the value is kept but does not change what is sent. Since 0.4.10.
     */
    val minBitrate: Int = 0,
    /** The overall tuning; see [AppVideoMode]. Since 0.4.11. */
    val mode: AppVideoMode = AppVideoMode.STABLE_HD,
)

/** Which of your simulcast layers the numbers in [AppVideoQualityInfo] describe. */
enum class AppVideoLayer { HIGH, MEDIUM, LOW }

/** Why the encoder is not sending the full picture, when it is not. */
enum class AppVideoQualityReason { BANDWIDTH, CPU, NONE }

/**
 * What your own camera is actually sending right now — the top layer that
 * carries frames, its size, rate and bitrate, and why it is not the full
 * picture if it is not. Delivered by [AppEventHandler.onVideoQualityChanged]
 * whenever any of it changes while the camera is on.
 */
data class AppVideoQualityInfo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val layer: AppVideoLayer,
    val reason: AppVideoQualityReason,
)

/**
 * Microphone processing. Each stage can be switched independently; all are on
 * by default. `micGain` is applied **after** processing (post-AEC/NS/AGC) with
 * a soft limiter, so raising it makes a distant voice louder without the
 * clipping that a pre-processing gain causes — use it instead of
 * `setRecordingVolume` for "the host holds the phone at arm's length".
 */
data class AppAudioOptions @JvmOverloads constructor(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true,
    /**
     * Use the phone's own echo canceller / noise suppressor instead of the
     * SDK's software ones. Off by default since 0.4.11: many budget phones ship
     * a weak hardware canceller, and on the loudspeaker in a voice room a
     * speaker heard their own voice come back quietly. The software canceller
     * is consistent on every phone.
     *
     * Turn it on only if your app plays in-room music through the SDK's
     * music player on the loudspeaker: the platform canceller also references
     * that playout, the software one does not.
     */
    val preferHardwareEffects: Boolean = false,
    /** Post-processing microphone gain in percent; 100 = unity, 200 = twice as loud (limited, never clips). */
    val micGain: Int = 100,
    /**
     * How hard residual noise between words is pushed down, after the
     * suppressor has run. LOW is safe everywhere; HIGH / VERY_HIGH suit a noisy
     * street or a fan next to the phone, at the cost of clipping the tail of a
     * very quiet voice. See [AppNoiseSuppressionLevel]. Since 0.4.6.
     */
    val noiseSuppressionLevel: AppNoiseSuppressionLevel = AppNoiseSuppressionLevel.LOW,
    /**
     * Level the microphone is steered towards, in dBFS (0 = off). -18 is a
     * good broadcast target: a whisper and a shout end up within a few dB of
     * each other. Works on top of the engine's own AGC, after noise handling,
     * and never boosts silence. Since 0.4.6.
     */
    val agcTargetLevelDbfs: Int = 0,
    /**
     * Noise suppression mode. STANDARD (default) is the engine's suppressor;
     * CLEAR adds Clear Voice on top, a stronger spectral suppressor for
     * steady noise (see [AppNoiseSuppression]); OFF turns suppression off.
     * [noiseSuppression] = false also means OFF. Since 0.4.18.
     */
    val noiseSuppressionMode: AppNoiseSuppression = AppNoiseSuppression.STANDARD,
    /**
     * Audio quality profile: capture source, processing and codec settings in
     * one choice. HD_VOICE (default) is full-band 48 kHz voice for live
     * streams and voice rooms. See [AppAudioProfile]. Set at create time.
     * Since 0.4.21.
     */
    val profile: AppAudioProfile = AppAudioProfile.HD_VOICE,
    /**
     * Keeps the phone in call audio mode while nobody is speaking, by playing
     * a silent track. Without it, Android 11 and later can drop call mode after
     * a few quiet seconds and the audio route jumps. On by default.
     *
     * If a phone refuses the silent track, the SDK logs it and turns the
     * keep-alive off by itself; the call is not affected. Set false to never
     * use it. AppLooma can also turn it off for specific phone models from the
     * server. Since 0.4.25.
     */
    val audioKeepAlive: Boolean = true,
) {
    /** The mode in force once [noiseSuppression] and [noiseSuppressionMode] are combined. */
    val effectiveNoiseSuppression: AppNoiseSuppression
        get() = if (!noiseSuppression) AppNoiseSuppression.OFF else noiseSuppressionMode
}

/**
 * Noise suppression mode, see [AppAudioOptions.noiseSuppressionMode].
 *
 * CLEAR ("Clear Voice") runs a spectral noise suppressor after the standard
 * one, in the SDK's microphone post-processing: fans, hiss, air conditioning
 * and traffic rumble drop by 10 dB and more while speech passes. It is a DSP
 * suppressor, not a neural model, and adds 10 ms of latency. Since 0.4.18.
 */
enum class AppNoiseSuppression { STANDARD, CLEAR, OFF }

/**
 * Audio quality profile, see [AppAudioOptions.profile]. It combines with
 * [AppAudioScenario]: the scenario picks volume stream and attributes, the
 * profile picks how the microphone is captured, processed and encoded.
 * Since 0.4.21.
 */
enum class AppAudioProfile {
    /**
     * Phone-call voice for 1-to-1 calls on the earpiece: the platform's voice
     * capture path, echo cancellation, noise suppression and AGC on, mono
     * 32 kbps. Narrow band on many phones. With [AppAudioScenario.CALL] the
     * call starts on the earpiece.
     */
    SPEECH,
    /**
     * HD voice (default) for live streams, voice rooms and loudspeaker calls:
     * 48 kHz voice capture with the phone's and the engine's echo
     * cancelling, noise suppression and AGC, call audio mode, high-pass
     * filter, mono Opus 64 kbps. Clear Voice ([AppNoiseSuppression.CLEAR]),
     * the noise gate and micGain still apply.
     */
    HD_VOICE,
    /**
     * Music for karaoke, music hosts and performers: 48 kHz stereo capture
     * where the phone supports it, every processing stage off, 128 kbps,
     * media audio mode. Use with headphones: without echo cancellation the
     * loudspeaker is picked up by the microphone and others hear an echo.
     */
    MUSIC,
}

/**
 * How much delay an audience member accepts for smoother playback, see
 * [AppEngineOptions.audienceLatency]. Since 0.4.18.
 */
enum class AppAudienceLatency { ULTRA_LOW, LOW, STANDARD }

/**
 * One video codec this device can handle, from [AppEngine.getSupportedVideoCodecs].
 * [mime] is `"video/VP8"`, `"video/VP9"`, `"video/H264"`, `"video/H265"` or
 * `"video/AV1"`. [hardware] is true when the SDK would encode it on a
 * hardware encoder. Since 0.4.18.
 */
data class AppVideoCodecCapability(
    val mime: String,
    val hardware: Boolean,
    val encoder: Boolean,
    val decoder: Boolean,
)

/**
 * Residual-noise handling after the noise suppressor. Each level is the
 * loudness below which the microphone is treated as "nobody is talking" and
 * eased down by 20 dB; speech above it passes untouched.
 */
enum class AppNoiseSuppressionLevel(internal val thresholdDbfs: Float) {
    OFF(Float.NEGATIVE_INFINITY),
    /** -60 dBFS: only the faintest hiss. */
    LOW(-60f),
    /** -54 dBFS: room tone, distant traffic. */
    MODERATE(-54f),
    /** -48 dBFS: a fan, a busy room. */
    HIGH(-48f),
    /** -42 dBFS: a street, a crowd — will also eat the tail of a whisper. */
    VERY_HIGH(-42f),
}

/** Engine-wide settings. All optional; the defaults are what most apps want. */
data class AppEngineOptions @JvmOverloads constructor(
    val audioScenario: AppAudioScenario = AppAudioScenario.CALL,
    val video: AppVideoConfig = AppVideoConfig(),
    /**
     * Force the software echo canceller and ignore the phone's own. Since
     * 0.4.11 the software canceller is already the default
     * (`audio.preferHardwareEffects = false`); this flag is kept for
     * compatibility and wins over `preferHardwareEffects = true`.
     */
    val softwareEchoCancellation: Boolean = false,
    val audio: AppAudioOptions = AppAudioOptions(),
    /**
     * Adaptive stream: lower a remote camera's layer for a small or hidden
     * view and pause it when no view shows it. `null` (default) lets
     * [AppVideoConfig.mode] decide: on in [AppVideoMode.ADAPTIVE], off in
     * STABLE_HD and ULTRA_HD_4K. `false` turns it off in ADAPTIVE too, which
     * also makes [AppEngine.setRemoteVideoQuality] take effect.
     */
    val adaptiveStream: Boolean? = null,
    /**
     * Stalled-video recovery. While a remote camera is attached to a visible
     * [AppVideoView] and its sender is publishing, a received frame rate of 0
     * for more than 4 seconds makes the engine call [AppEngine.resubscribe]
     * for that user (at most once per 15 seconds per user).
     */
    val autoRecoverStalledVideo: Boolean = true,
    /**
     * Run the encoder self-test ([AppEngine.runEncoderSelfTest]) once per
     * install and SDK version, in the background after the first
     * [AppEngine.create]. It never touches the camera. Since 0.4.16.
     */
    val encoderSelfTest: Boolean = true,
    /**
     * Where your users are, as a hint for server selection. Preparation for
     * multi-region: today every region connects to the server URL your token
     * endpoint returns; the value is sent with the SDK's device-tuning
     * request and reports. Since 0.4.16.
     */
    val region: AppRegion = AppRegion.AUTO,
    /**
     * Remote diagnostics. The SDK keeps its own recent log lines in memory
     * (never audio, video, messages or names) and uploads them, scrubbed, to
     * AppLooma after a call that had a problem, when support switched
     * collection on for this device or project, or when you call
     * [AppEngine.uploadDiagnostics]. Kept 14 days. `false` turns every upload
     * off. Since 0.4.16.
     */
    val remoteDiagnostics: Boolean = true,
    /**
     * Cloud proxy for restrictive networks (office firewalls, UDP blocked).
     * AUTO (default) connects directly and, when the first join fails with a
     * connection or network timeout, retries once through AppLooma's relay on
     * TLS port 443. FORCE_TLS_443 always relays on TLS 443. OFF never
     * relays. Reported by [AppEventHandler.onProxyStateChanged]. Since 0.4.17.
     */
    val cloudProxy: AppCloudProxy = AppCloudProxy.AUTO,
    /**
     * Audience latency tier, applied only while the local user is audience
     * (not publishing). ULTRA_LOW (default) is the lowest delay. LOW and
     * STANDARD trade delay for smoothness on bad networks; on Android the
     * engine has no receive-side jitter-buffer control, so here LOW behaves
     * like ULTRA_LOW and STANDARD only makes the receiver prefer lower video
     * layers under congestion (it turns on
     * [AppEngine.setRemoteSubscribeFallback] VIDEO_LOW_QUALITY unless you set
     * a fallback yourself). Since 0.4.18.
     */
    val audienceLatency: AppAudienceLatency = AppAudienceLatency.ULTRA_LOW,
    /**
     * Experimental, for local testing only; do not ship with this on. Allows
     * hardware H.264 on Codec2 encoders (MediaTek, newer Qualcomm) that are
     * otherwise kept on VP8, using the SDK's tuned encoder settings and
     * stream fixes. In production this path is enabled per device model by
     * AppLooma's remote device config. Default false. Since 0.4.18.
     */
    val experimentalH264Codec2: Boolean = false,
)

/** See [AppEngineOptions.cloudProxy]. Since 0.4.17. */
enum class AppCloudProxy { AUTO, FORCE_TLS_443, OFF }

/**
 * Whether media runs through the cloud proxy. DIRECT: not relayed (default,
 * also after leaving); CONNECTING: joining through the relay on TLS 443
 * (forced, or the AUTO retry after a failed direct join); CONNECTED: in the
 * channel through the relay. Since 0.4.17.
 */
enum class AppProxyState { DIRECT, CONNECTING, CONNECTED }

/** Region hint, see [AppEngineOptions.region]. Since 0.4.16. */
enum class AppRegion(val code: String) { AUTO("auto"), BD("bd"), IN("in"), SA("sa"), SG("sg"), US("us") }

/**
 * What the SDK gives up when the network cannot carry everything.
 * NONE keeps everything (default); VIDEO_LOW_QUALITY drops to the low layer
 * (receiving) or a lower bitrate (sending); AUDIO_ONLY drops video entirely
 * until the network recovers. See [AppEngine.setRemoteSubscribeFallback] and
 * [AppEngine.setLocalPublishFallback]. Since 0.4.16.
 */
enum class AppFallbackOption { NONE, VIDEO_LOW_QUALITY, AUDIO_ONLY }

/** The result of [AppEngine.runEncoderSelfTest]. Since 0.4.16. */
data class AppEncoderSelfTestResult(
    /** True when the encoder produced a decodable start (parameter sets or a key frame) and data in time, or there was nothing to test. */
    val passed: Boolean,
    /** Hardware encoder tested, or null when the phone has none the SDK would use (then nothing was tested). */
    val encoderName: String?,
    /** Why it failed: `"no_output"`, `"no_key_frame"`, `"encoder_error"`, `"timeout"`; null on success. */
    val reason: String?,
    val elapsedMs: Long,
)

/** The result of [AppEngine.startNetworkTest]. Since 0.4.16. */
data class AppNetworkTestResult(
    val uplinkQuality: AppNetworkQuality,
    val downlinkQuality: AppNetworkQuality,
    /** Round-trip time to the media server in milliseconds, -1 if unknown. */
    val rttMs: Int,
    /** Incoming jitter in milliseconds, -1 if unknown. */
    val jitterMs: Int,
    /** Fraction of outgoing packets lost, 0..1. */
    val uplinkLoss: Float,
    /** Fraction of incoming packets lost, 0..1. */
    val downlinkLoss: Float,
    /** Null on success, otherwise why the test could not run. */
    val error: String? = null,
)

/** Milestones on the way to seeing a remote picture, each with elapsed ms since joinChannel. */
enum class AppConnectionStage { CONNECTED, FIRST_REMOTE_VIDEO_FRAME }

/**
 * Per-remote-user media health, delivered every two seconds while joined.
 * `videoFps` 0 with a subscribed camera means that user's picture is stuck
 * on your phone; `videoFreezeCount` climbs each time it stalls.
 */
data class AppRemoteStats(
    val uid: String,
    val videoFps: Int,
    val videoWidth: Int,
    val videoHeight: Int,
    /** Times the picture stalled since you subscribed. */
    val videoFreezeCount: Int,
    val videoPacketsLost: Long,
    val audioPacketsLost: Long,
    /** Audio jitter-buffer delay in milliseconds. */
    val audioJitterMs: Int,
)

/** Which simulcast layer to receive from a remote camera. */
enum class AppRemoteQuality { LOW, MEDIUM, HIGH }

/** Where the in-room music player is. */
enum class AppMusicState { STOPPED, PLAYING, PAUSED }

/** Who is speaking and how loudly, 0..1. */
data class AppSpeaker(val uid: String, val level: Float)

/**
 * Where the call's audio is going. A Bluetooth headset, when connected and
 * permitted, wins by default; then a wired headset; then the loudspeaker
 * (MEDIA) or the earpiece (CALL). See [AppEngine.setAudioRoute].
 *
 * On Android 12 and above the app must hold `BLUETOOTH_CONNECT` at runtime
 * for [BLUETOOTH] to appear at all — request it next to `RECORD_AUDIO`.
 */
enum class AppAudioRoute { BLUETOOTH, WIRED_HEADSET, EARPIECE, SPEAKER }

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
    /**
     * Connect to the AppLooma media server best placed for this device and room
     * (applooma.dev URLs only; falls back to the given URL on any failure). Since 0.4.24.
     */
    val smartRouting: Boolean = true,
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
    /**
     * Who sent it. Taken from the connection the message arrived on, not from
     * the payload, so a client cannot claim to be someone else; compare
     * `from.uid` with the uid your server issued the token for. Null if it
     * came from your own server (server-side send) or on messages you created.
     */
    val from: AppRemoteUser? = null,
    /**
     * On a sent message, when you sent it. On a received message, when it
     * arrived on this phone (the sender's own timestamp is not trusted).
     */
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
    /**
     * Someone sent a message with sendMessage(). `text` and `data` are exactly
     * what the sender passed; values inside `data` arrive as JSON types
     * (String, Number, Boolean, JSONObject, JSONArray, or JSONObject.NULL).
     * `from` is set by the server from the sender's connection and cannot be
     * forged by a client. Your own messages are not echoed back. Before a
     * join completes, up to 50 outgoing messages are queued (the oldest are
     * dropped beyond that); keep each message under about 15 KB. Raw bytes
     * from sendData() arrive in onDataReceived, never here.
     */
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

    /**
     * Your own camera track was (re)created — camera on/off, switch, or a
     * settings change. Every [AppVideoView] showing you re-binds by itself;
     * this is for anything else that holds a reference to your preview.
     */
    open fun onLocalVideoChanged() {}
    /** The first decoded frame of a remote user's camera reached your screen. `elapsedMs` counts from joinChannel. */
    open fun onFirstRemoteVideoFrame(uid: String, elapsedMs: Long) {}
    /** A milestone on the way to picture, with elapsed ms since joinChannel — for measuring join latency. */
    open fun onConnectionStage(stage: AppConnectionStage, uid: String?, elapsedMs: Long) {}
    /** Per-remote-user media health, every two seconds while joined (see [AppRemoteStats]). */
    open fun onRemoteStats(stats: List<AppRemoteStats>) {}
    /**
     * The size, rate or layer your own camera is sending changed — the encoder
     * stepped down for the network or the CPU, or came back up. Checked every
     * two seconds while your camera is on. Since 0.4.10.
     */
    open fun onVideoQualityChanged(quality: AppVideoQualityInfo) {}
    /**
     * The audio route changed — a headset was plugged in or paired, taken off,
     * or [AppEngine.setAudioRoute] was called. `route` is null while nothing is
     * selected yet; `available` is what [AppEngine.getAudioRoutes] returns now.
     */
    open fun onAudioRouteChanged(route: AppAudioRoute?, available: List<AppAudioRoute>) {}
    /**
     * Automatic codec recovery switched how your camera is encoded, because
     * the encoder in use was not producing a picture viewers could see. The
     * camera was republished on the safer path by itself (viewers see about
     * half a second of interruption); there is nothing to do, this is for
     * logs and support. At most once per join.
     *
     * `from` / `to` name the codec and encoder kind, e.g. `"h264:hw"` →
     * `"vp8:sw"`; a stream already on the safest codec is republished as a
     * single layer (`"vp8:sw"` → `"vp8:sw:single"`).
     * `reason` is `"no_frames"` (the encoder produced no frames or no key
     * frame), `"encoder_error"` (the encoder failed to start or to encode) or
     * `"viewers_cannot_decode"` (viewers reported they receive no picture).
     * A hardware encoder found bad is remembered on this phone for 30 days,
     * so later sessions start on the working path.
     */
    open fun onVideoCodecFallback(from: String, to: String, reason: String) {}

    /**
     * A network fallback started or ended. `isLocal` true is what you send
     * ([AppEngine.setLocalPublishFallback]), false is what you receive
     * ([AppEngine.setRemoteSubscribeFallback]). `state` is the fallback now in
     * effect; NONE means everything was restored. Since 0.4.16.
     */
    open fun onFallbackStateChanged(isLocal: Boolean, state: AppFallbackOption) {}
    /**
     * The join token expires in about 30 seconds (read from the token itself).
     * Fetch a new one from your server and pass it to [AppEngine.renewToken].
     * Since 0.4.16.
     */
    open fun onTokenWillExpire(token: String) {}
    /**
     * The cloud proxy state changed (see [AppEngineOptions.cloudProxy]).
     * [autoRetry] is true when the relay is used because the direct join
     * failed. Since 0.4.17.
     */
    open fun onProxyStateChanged(state: AppProxyState, autoRetry: Boolean) {}
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

/**
 * One captured camera frame in I420 (YUV 4:2:0) layout, handed to an
 * [AppVideoProcessor]. The planes are direct ByteBuffers that may be edited in
 * place. `rotation` (0/90/180/270) is how the frame must be turned to be
 * upright; `width`/`height` are before that rotation.
 *
 * To return a different frame (a resized or re-drawn one), construct a new
 * AppVideoFrame with direct ByteBuffers and even width/height.
 */
class AppVideoFrame(
    val width: Int,
    val height: Int,
    val dataY: java.nio.ByteBuffer,
    val strideY: Int,
    val dataU: java.nio.ByteBuffer,
    val strideU: Int,
    val dataV: java.nio.ByteBuffer,
    val strideV: Int,
    val rotation: Int,
    val timestampNs: Long,
)

/**
 * Capture-side frame hook: brightness, beauty, filters, watermarks. Runs on
 * the camera thread for every frame before it is encoded; what it returns is
 * both sent and shown in your own preview. Keep it fast (a 1080p30 camera
 * leaves ~33 ms per frame, including a GPU-to-CPU copy the SDK does for you).
 * An exception thrown here sends the original frame.
 *
 * Install with [AppEngine.setVideoProcessor].
 */
fun interface AppVideoProcessor {
    fun process(frame: AppVideoFrame): AppVideoFrame
}

/**
 * One camera frame on the GPU, handed to an [AppTextureProcessor].
 *
 * [textureId] is an external OES texture ([isOes] = true, the camera) or a
 * GL_TEXTURE_2D one. Sample it with [transformMatrix] (4x4, column-major, as
 * for SurfaceTexture.getTransformMatrix) applied to the texture coordinates.
 * `rotation` is how the frame must be turned to be upright; `width`/`height`
 * are before that rotation. Since 0.4.23.
 */
class AppTextureFrame(
    val textureId: Int,
    val isOes: Boolean,
    val transformMatrix: FloatArray,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val timestampNs: Long,
)

/**
 * GPU frame hook for beauty, filters and effects at camera frame rate with no
 * GPU-to-CPU copy. Runs on the camera thread with the SDK's shared EGL context
 * current, before any [AppVideoProcessor]. Since 0.4.23.
 *
 * Return the id of a GL_TEXTURE_2D (RGBA) the size of the frame, drawn in the
 * frame's own orientation (sampled with an identity matrix), or a negative
 * number to send the frame unchanged. The SDK reads the returned texture
 * after [process] returns, possibly on the encoder; keep it untouched until
 * [onTextureReleased] hands it back. A pool of three textures is enough.
 * An exception thrown here sends the original frame.
 *
 * Install with [AppEngine.setTextureProcessor].
 */
interface AppTextureProcessor {
    fun process(frame: AppTextureFrame): Int
    /** The SDK no longer reads [textureId]; it may be drawn into again. */
    fun onTextureReleased(textureId: Int) {}
    /** The processor was removed or the camera stopped; free GL resources here (EGL context current). */
    fun release() {}
}

/**
 * Voice changer presets for [AppEngine.setVoiceChangerPreset]. Everyone in the
 * room hears the changed voice; it is applied on the microphone before sending.
 *
 * [key] is a stable short name an app can store (for example in its settings)
 * and turn back into a preset with [fromKey]. Keys never change between SDK
 * versions. Since 0.5.0.
 */
enum class AppVoiceChanger(val key: String) {
    OFF("off"),
    /** High pitch, small voice. */
    BABY("baby"),
    /** Slightly higher, softer. */
    FEMALE("female"),
    /** Slightly lower, fuller. */
    MALE("male"),
    /** Much lower, with bass. */
    DEEP_MALE("deepmale"),
    /** Between a man's and a woman's voice. */
    NEUTRAL("neutral"),
    /** A cartoon character. */
    CARTOON("cartoon"),
    /** Very high and quick-sounding. */
    CHIPMUNK("chipmunk"),
    /** A cheeky child, nasal. */
    SHIN_CHAN("shinchan"),
    /** A man's voice made lighter. */
    GIRLISH_MAN("girlishman"),
    /** A firm, edgy woman's voice. */
    IRON_LADY("ironlady"),
    /** Very low with a growl. */
    MONSTER("monster"),
    /** Metallic, mechanical. */
    ROBOT("robot"),
    /** Deep and dull, like a talking tree. */
    GROOT("groot"),
    /** Deep voice behind a mask. */
    DARTH_VADER("vader"),
    /** Narrow, like an old telephone line. */
    PHONE("phone");

    companion object {
        /** The preset for a stored [key]; unknown or null keys give [OFF]. */
        @JvmStatic fun fromKey(key: String?): AppVoiceChanger =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) } ?: OFF
    }
}
