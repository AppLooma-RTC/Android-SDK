package livekit.org.webrtc

import android.media.MediaCodecInfo

/**
 * The media engine keeps its MediaCodec encoder, codec-type enum, colour-format
 * helpers and bitrate adjusters package-private. This file lives in that
 * package so the SDK's own encoder factory
 * (com.applooma.rtc.AppLoomaVideoEncoderFactory) can reach them without copying
 * the encoder. Nothing here is part of the public SDK surface.
 */
internal object AppLoomaEncoderBridge {

    /** The codecs the engine can carry; [name] is the SDP codec name it uses. */
    internal enum class Codec(val mime: String) {
        VP8("video/x-vnd.on2.vp8"),
        VP9("video/x-vnd.on2.vp9"),
        H264("video/avc"),
        AV1("video/av01"),
        H265("video/hevc");

        internal fun engine(): VideoCodecMimeType = VideoCodecMimeType.valueOf(name)
    }

    /** How the encoder's target bitrate is corrected while it runs. */
    internal enum class Adjuster { NONE, FRAMERATE, DYNAMIC }

    /** Codec for an SDP codec name ("VP8", "H264", "H265", …), or null when unknown. */
    fun typeOf(sdpName: String): Codec? = Codec.values().firstOrNull { it.name == sdpName }

    fun mimeOf(type: Codec): String = type.mime

    fun isHardwareAccelerated(info: MediaCodecInfo): Boolean = MediaCodecUtils.isHardwareAccelerated(info)

    fun isSoftwareOnly(info: MediaCodecInfo): Boolean = MediaCodecUtils.isSoftwareOnly(info)

    /** Colour format for the surface (texture) input path, or null when the encoder has none. */
    fun surfaceColorFormat(caps: MediaCodecInfo.CodecCapabilities): Int? =
        MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps)

    /** Colour format for the byte-buffer (I420/NV12) input path, or null when none is supported. */
    fun yuvColorFormat(caps: MediaCodecInfo.CodecCapabilities): Int? =
        MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps)

    /** SDP format parameters the engine expects for a codec (H.264 profile-level-id and friends). */
    fun codecProperties(type: Codec, highProfile: Boolean): Map<String, String> =
        MediaCodecUtils.getCodecProperties(type.engine(), highProfile)

    fun isSameH264Profile(a: Map<String, String>, b: Map<String, String>): Boolean = H264Utils.isSameH264Profile(a, b)

    /**
     * The engine's MediaCodec encoder, built exactly as its own factory builds
     * it, minus the vendor gate. With [h264Tweaks], the SDK's modified copy of
     * it (AppLoomaHardwareVideoEncoder): constrained-baseline profile/level,
     * no B-frames, low latency, 2 s I-frame interval, and key frames kept when
     * they arrive in a codec-config buffer. Used for Codec2 H.264 encoders only.
     */
    fun createHardwareEncoder(
        codecName: String,
        type: Codec,
        surfaceColorFormat: Int?,
        yuvColorFormat: Int?,
        params: Map<String, String>,
        keyFrameIntervalSec: Int,
        forcedKeyFrameIntervalMs: Int,
        adjuster: Adjuster,
        sharedContext: EglBase14.Context?,
        h264Tweaks: Boolean = false,
    ): VideoEncoder = if (h264Tweaks) AppLoomaHardwareVideoEncoder(
        MediaCodecWrapperFactoryImpl(),
        codecName,
        type.engine(),
        surfaceColorFormat,
        yuvColorFormat,
        params,
        keyFrameIntervalSec,
        forcedKeyFrameIntervalMs,
        adjuster.create(),
        sharedContext,
        true,
    ) else HardwareVideoEncoder(
        MediaCodecWrapperFactoryImpl(),
        codecName,
        type.engine(),
        surfaceColorFormat,
        yuvColorFormat,
        params,
        keyFrameIntervalSec,
        forcedKeyFrameIntervalMs,
        adjuster.create(),
        sharedContext,
    )

    private fun Adjuster.create(): BitrateAdjuster = when (this) {
        Adjuster.DYNAMIC -> DynamicBitrateAdjuster()
        Adjuster.FRAMERATE -> FramerateBitrateAdjuster()
        Adjuster.NONE -> BaseBitrateAdjuster()
    }
}
