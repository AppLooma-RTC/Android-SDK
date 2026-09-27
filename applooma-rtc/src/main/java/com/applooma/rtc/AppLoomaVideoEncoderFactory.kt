package com.applooma.rtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import livekit.org.webrtc.AppLoomaEncoderBridge
import livekit.org.webrtc.AppLoomaEncoderBridge.Adjuster
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.EglBase14
import livekit.org.webrtc.SimulcastVideoEncoderFactory
import livekit.org.webrtc.SoftwareVideoEncoderFactory
import livekit.org.webrtc.VideoCodecInfo
import livekit.org.webrtc.AppLoomaEncoderBridge.Codec
import livekit.org.webrtc.VideoCodecStatus
import livekit.org.webrtc.VideoEncoder
import livekit.org.webrtc.VideoEncoderFactory
import livekit.org.webrtc.VideoEncoderFallback
import livekit.org.webrtc.VideoFrame
import java.util.concurrent.Callable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The SDK's video encoder factory.
 *
 * The engine's stock factory only drives H.264 through encoders named
 * `OMX.qcom.` / `OMX.Exynos.`, so MediaTek (`OMX.MTK.`, `c2.mtk.`), Unisoc,
 * HiSilicon and every Codec2-only Qualcomm phone (`c2.qti.`) ended up on
 * software VP8 — CPU-bound and soft at 720p/1080p. This factory enumerates
 * MediaCodec itself, accepts any platform hardware encoder from a known vendor
 * (any platform-declared hardware encoder on Android 10+), and builds the
 * engine's own MediaCodec encoder around it, so nothing changes in how frames
 * are fed or delivered.
 *
 * Layout, mirroring what the engine does for its default factory:
 *   simulcast adapter ( per-layer thread ( hardware ),
 *                       per-layer thread ( hardware → software fallback ) )
 * A hardware encoder that fails to start hands the layer to the engine's
 * software encoder (VP8/VP9/AV1); H.264 has no software encoder on Android,
 * which is why [AppEngine.resolveCodec] only picks it when hardware exists.
 *
 * Built lazily: the engine loads its native library before the peer
 * connection factory asks for codecs, and the software factory needs it.
 */
internal class AppLoomaVideoEncoderFactory(private val eglContext: EglBase.Context?) : VideoEncoderFactory {

    private val delegate: VideoEncoderFactory by lazy {
        val hardware = HardwareEncoders(eglContext)
        val software = SoftwareVideoEncoderFactory()
        SimulcastVideoEncoderFactory(
            PerLayerThread(hardware),
            PerLayerThread(HardwareThenSoftware(hardware, software)),
        )
    }

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = delegate.createEncoder(info)

    override fun getSupportedCodecs(): Array<VideoCodecInfo> =
        delegate.supportedCodecs.distinctBy { it.name to it.params }.toTypedArray()

    // ── Hardware ────────────────────────────────────────────────────────────

    /** MediaCodec hardware encoders, vendor-gated by [isUsableHardwareEncoder]. */
    private class HardwareEncoders(private val eglContext: EglBase.Context?) : VideoEncoderFactory {

        override fun getSupportedCodecs(): Array<VideoCodecInfo> {
            val out = ArrayList<VideoCodecInfo>()
            for (type in arrayOf(Codec.VP8, Codec.VP9, Codec.H264, Codec.AV1, Codec.H265)) {
                val codec = findEncoder(type, eglContext) ?: continue
                if (type == Codec.H264 && supportsH264HighProfile(codec)) {
                    out += VideoCodecInfo(type.name, AppLoomaEncoderBridge.codecProperties(type, true), emptyList())
                }
                out += VideoCodecInfo(type.name, AppLoomaEncoderBridge.codecProperties(type, false), emptyList())
            }
            return out.toTypedArray()
        }

        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            val type = AppLoomaEncoderBridge.typeOf(info.name) ?: return null
            val codec = findEncoder(type, eglContext) ?: return null
            val mime = AppLoomaEncoderBridge.mimeOf(type)
            val caps = try { codec.getCapabilitiesForType(mime) } catch (_: Throwable) { return null }

            if (type == Codec.H264) {
                val high = AppLoomaEncoderBridge.isSameH264Profile(info.params, AppLoomaEncoderBridge.codecProperties(type, true))
                val baseline = AppLoomaEncoderBridge.isSameH264Profile(info.params, AppLoomaEncoderBridge.codecProperties(type, false))
                if (!high && !baseline) return null
                if (high && !supportsH264HighProfile(codec)) return null
            }

            val name = codec.name
            val surfaceFormat = (eglContext as? EglBase14.Context)?.let { AppLoomaEncoderBridge.surfaceColorFormat(caps) }
            val yuvFormat = AppLoomaEncoderBridge.yuvColorFormat(caps)
            if (surfaceFormat == null && yuvFormat == null) return null

            return AppLoomaEncoderBridge.createHardwareEncoder(
                codecName = name,
                type = type,
                surfaceColorFormat = surfaceFormat,
                yuvColorFormat = yuvFormat,
                params = info.params,
                keyFrameIntervalSec = PERIODIC_KEY_FRAME_INTERVAL_S,
                forcedKeyFrameIntervalMs = forcedKeyFrameIntervalMs(type, name),
                adjuster = bitrateAdjuster(type, name),
                sharedContext = eglContext as? EglBase14.Context,
            )
        }

        // The engine's own tuning per vendor, plus MediaTek: its encoders honour
        // the requested frame rate loosely, so the bitrate is corrected for the
        // real one, and a periodic key frame keeps late joiners from waiting.
        private fun forcedKeyFrameIntervalMs(type: Codec, name: String): Int = when {
            type == Codec.VP8 && name.startsWith("OMX.qcom.", true) -> if (Build.VERSION.SDK_INT == 23) 20_000 else 15_000
            isMediaTek(name) -> 20_000
            else -> 0
        }

        private fun bitrateAdjuster(type: Codec, name: String): Adjuster = when {
            isExynos(name) -> if (type == Codec.VP8) Adjuster.DYNAMIC else Adjuster.FRAMERATE
            isMediaTek(name) -> Adjuster.FRAMERATE
            else -> Adjuster.NONE
        }
    }

    /** Hardware first; when the engine also has a software encoder for the codec, fall to it if hardware fails. */
    private class HardwareThenSoftware(private val hardware: VideoEncoderFactory, private val software: VideoEncoderFactory) : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
            val hw = hardware.createEncoder(info)
            val sw = software.createEncoder(info)
            return if (hw != null && sw != null) VideoEncoderFallback(sw, hw) else hw ?: sw
        }

        override fun getSupportedCodecs(): Array<VideoCodecInfo> = hardware.supportedCodecs + software.supportedCodecs
    }

    // ── Per-layer thread ────────────────────────────────────────────────────

    private class PerLayerThread(private val inner: VideoEncoderFactory) : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = inner.createEncoder(info)?.let { LayerEncoder(it) }
        override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs
    }

    /**
     * Runs one simulcast layer's encoder on its own thread, as the engine's
     * default factory does. The MediaCodec encoder checks that init, encode and
     * release all arrive on one thread; the thread is released when idle
     * (the encoder detaches its thread check on release, so a later re-init on
     * a fresh thread is fine).
     */
    private class LayerEncoder(private val encoder: VideoEncoder) : VideoEncoder {
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
            setThreadFactory { r -> Thread(r, "applooma-encoder").apply { isDaemon = true } }
        }
        @Volatile private var settings: VideoEncoder.Settings? = null

        private fun <T> on(block: () -> T): T = executor.submit(Callable(block)).get()

        override fun initEncode(s: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus = on {
            settings = s
            encoder.initEncode(s, callback)
        }

        override fun release(): VideoCodecStatus = on { encoder.release() }

        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus = on {
            val s = settings
            // The simulcast adapter scales per layer itself; this only catches a
            // frame that slipped through at the wrong size.
            if (s != null && s.width > 0 && s.height > 0 && (frame.buffer.width != s.width || frame.buffer.height != s.height)) {
                val scaled = frame.buffer.cropAndScale(0, 0, frame.buffer.width, frame.buffer.height, s.width, s.height)
                val scaledFrame = VideoFrame(scaled, frame.rotation, frame.timestampNs)
                try { encoder.encode(scaledFrame, info) } finally { scaled.release() }
            } else encoder.encode(frame, info)
        }

        override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus = on { encoder.setRateAllocation(allocation, framerate) }
        override fun setRates(rcParameters: VideoEncoder.RateControlParameters): VideoCodecStatus = on { encoder.setRates(rcParameters) }
        override fun getScalingSettings(): VideoEncoder.ScalingSettings = on { encoder.scalingSettings }
        override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = on { encoder.resolutionBitrateLimits }
        override fun getImplementationName(): String = on { encoder.implementationName }
        override fun getEncoderInfo(): VideoEncoder.EncoderInfo = on { encoder.encoderInfo }
        // A native-backed encoder (the fallback wrapper) bypasses this Java object entirely.
        override fun createNative(webrtcEnvRef: Long): Long = encoder.createNative(webrtcEnvRef)
        override fun isHardwareEncoder(): Boolean = encoder.isHardwareEncoder
    }

    companion object {
        private const val PERIODIC_KEY_FRAME_INTERVAL_S = 3600

        /**
         * Vendor prefixes accepted on every Android version (case-insensitive).
         * On Android 10+ any encoder the platform flags as hardware-accelerated
         * is accepted as well, so a vendor missing here still gets hardware there.
         */
        internal val HARDWARE_PREFIXES = arrayOf(
            "OMX.qcom.", "c2.qti.",
            "OMX.Exynos.", "c2.exynos.",
            "OMX.MTK.", "c2.mtk.",
            "OMX.hisi.", "c2.hisi.",
            "OMX.Intel.",
            "c2.unisoc.", "OMX.sprd.",
        )

        /** Never hardware, whatever the platform flag says. */
        private val SOFTWARE_PREFIXES = arrayOf("OMX.google.", "c2.android.", "OMX.SEC.")

        /** Old phones whose H.264 hardware encoder produces a stream nobody can decode (from the engine's own list). */
        private val H264_EXCEPTION_MODELS = listOf("SAMSUNG-SGH-I337", "Nexus 7", "Nexus 4")

        private fun isExynos(name: String) = name.startsWith("OMX.Exynos.", true) || name.startsWith("c2.exynos.", true)
        private fun isMediaTek(name: String) = name.startsWith("OMX.MTK.", true) || name.startsWith("c2.mtk.", true)

        private fun supportsH264HighProfile(codec: MediaCodecInfo): Boolean {
            // The engine sets profile=high, level=3 on the MediaFormat for this
            // path; only Exynos has been proven to honour it at every preset.
            if (!isExynos(codec.name)) return false
            val caps = try { codec.getCapabilitiesForType("video/avc") } catch (_: Throwable) { return false }
            return caps.profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh }
        }

        /** Whether [info] is a hardware encoder this factory will drive for [mime]. */
        internal fun isUsableHardwareEncoder(info: MediaCodecInfo, mime: String): Boolean {
            if (!info.isEncoder) return false
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) return false
            val name = info.name
            if (SOFTWARE_PREFIXES.any { name.startsWith(it, true) }) return false
            if (Build.VERSION.SDK_INT >= 29) {
                if (info.isAlias || !info.isHardwareAccelerated) return false
            } else if (HARDWARE_PREFIXES.none { name.startsWith(it, true) }) return false
            if (mime.equals("video/avc", true) && Build.MODEL in H264_EXCEPTION_MODELS) return false
            // MediaTek H.264: the encoder runs and the server receives packets,
            // but no other phone can bind/decode the stream (verified 2026-09-27
            // on realme RMX3941 c2.mtk.avc.encoder -> vivo V2419 showed black).
            // Those phones stay on VP8 until a MediaTek-specific format fix exists.
            if (mime.equals("video/avc", true) && isMediaTek(name)) return false
            return true
        }

        /** The hardware encoder this factory would use for [mime], or null when it would be software (or nothing). */
        internal fun findHardwareEncoder(mime: String): MediaCodecInfo? = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { isUsableHardwareEncoder(it, mime) }
        } catch (_: Throwable) { null }

        private fun findEncoder(type: Codec, eglContext: EglBase.Context?): MediaCodecInfo? {
            val mime = AppLoomaEncoderBridge.mimeOf(type)
            val codec = findHardwareEncoder(mime) ?: return null
            // Must have an input path we can feed: a surface (texture frames)
            // or a planar YUV buffer format.
            val caps = try { codec.getCapabilitiesForType(mime) } catch (_: Throwable) { return null }
            val hasSurface = eglContext is EglBase14.Context && AppLoomaEncoderBridge.surfaceColorFormat(caps) != null
            return if (hasSurface || AppLoomaEncoderBridge.yuvColorFormat(caps) != null) codec else null
        }

        /** One log line naming the encoder a published codec ("h264", "vp8", …) will run on. */
        internal fun describe(codec: String): String {
            val mime = when (codec.lowercase()) {
                "h264" -> "video/avc"; "vp8" -> "video/x-vnd.on2.vp8"; "vp9" -> "video/x-vnd.on2.vp9"
                "av1" -> "video/av01"; "h265" -> "video/hevc"; else -> return "encoder=unknown codec=$codec"
            }
            val hw = findHardwareEncoder(mime)
            return if (hw != null) "encoder=${hw.name} mime=$mime hardware=true"
            else "encoder=software mime=$mime hardware=false"
        }
    }
}
