package com.applooma.rtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import org.webrtc.AppLoomaEncoderBridge
import org.webrtc.AppLoomaEncoderBridge.Adjuster
import org.webrtc.EglBase
import org.webrtc.EglBase14
import org.webrtc.SimulcastVideoEncoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoCodecInfo
import org.webrtc.AppLoomaEncoderBridge.Codec
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoEncoderFallback
import org.webrtc.VideoFrame
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
            // Codec2 H.264 (server-enabled per model): the SDK's tuned encoder copy plus the stream fixer.
            val codec2H264 = type == Codec.H264 && !isProvenH264(name) && h264Codec2Enabled()
            val byteBuffer = codec2H264 && h264ByteBufferEnabled()
            val sharedContext = if (byteBuffer) null else eglContext as? EglBase14.Context
            val surfaceFormat = sharedContext?.let { AppLoomaEncoderBridge.surfaceColorFormat(caps) }
            val yuvFormat = AppLoomaEncoderBridge.yuvColorFormat(caps)
            if (surfaceFormat == null && yuvFormat == null) return null
            if (codec2H264) AppEngine.debug("h264: codec2 path encoder=$name input=${if (surfaceFormat != null) "surface" else "bytebuffer"}")

            val encoder = AppLoomaEncoderBridge.createHardwareEncoder(
                codecName = name,
                type = type,
                surfaceColorFormat = surfaceFormat,
                yuvColorFormat = yuvFormat,
                params = info.params,
                keyFrameIntervalSec = PERIODIC_KEY_FRAME_INTERVAL_S,
                forcedKeyFrameIntervalMs = forcedKeyFrameIntervalMs(type, name),
                adjuster = bitrateAdjuster(type, name),
                sharedContext = sharedContext,
                h264Tweaks = codec2H264,
            )
            return if (!codec2H264) encoder else H264FixingEncoder(encoder) { id ->
                reportFailure(info.name, "h264 profile $id is not constrained baseline")
            }
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
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = inner.createEncoder(info)?.let { LayerEncoder(it, info.name) }
        override fun getSupportedCodecs(): Array<VideoCodecInfo> = inner.supportedCodecs
    }

    /**
     * Runs one simulcast layer's encoder on its own thread, as the engine's
     * default factory does. The MediaCodec encoder checks that init, encode and
     * release all arrive on one thread; the thread is released when idle
     * (the encoder detaches its thread check on release, so a later re-init on
     * a fresh thread is fine).
     */
    private class LayerEncoder(private val encoder: VideoEncoder, private val codecName: String) : VideoEncoder {
        private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
            setThreadFactory { r -> Thread(r, "applooma-encoder").apply { isDaemon = true } }
        }
        @Volatile private var settings: VideoEncoder.Settings? = null

        private fun <T> on(block: () -> T): T = executor.submit(Callable(block)).get()

        override fun initEncode(s: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus = on {
            settings = s
            val status = try { encoder.initEncode(s, callback) } catch (t: Throwable) { reportFailure(codecName, "init threw $t"); throw t }
            if (status != VideoCodecStatus.OK && status != VideoCodecStatus.FALLBACK_SOFTWARE) reportFailure(codecName, "init status=$status")
            status
        }

        override fun release(): VideoCodecStatus = on { encoder.release() }

        override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus = on {
            val status = try { encodeInner(frame, info) } catch (t: Throwable) { reportFailure(codecName, "encode threw $t"); throw t }
            if (status == VideoCodecStatus.ERROR) reportFailure(codecName, "encode status=$status")
            status
        }

        private fun encodeInner(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
            val s = settings
            // The simulcast adapter scales per layer itself; this only catches a
            // frame that slipped through at the wrong size.
            if (s != null && s.width > 0 && s.height > 0 && (frame.buffer.width != s.width || frame.buffer.height != s.height)) {
                val scaled = frame.buffer.cropAndScale(0, 0, frame.buffer.width, frame.buffer.height, s.width, s.height)
                val scaledFrame = VideoFrame(scaled, frame.rotation, frame.timestampNs)
                return try { encoder.encode(scaledFrame, info) } finally { scaled.release() }
            }
            return encoder.encode(frame, info)
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

        /** MIME types whose hardware encoder is skipped for the rest of the process. */
        private val softwareOnly = java.util.concurrent.CopyOnWriteArraySet<String>()

        /**
         * Process-wide switch: never use a hardware encoder for [mime] again in
         * this process (automatic codec recovery sets it when a hardware encoder
         * proved unable to produce a usable stream). Takes effect for the next
         * encoder the factory creates, i.e. the next publish.
         */
        internal fun forceSoftware(mime: String) { softwareOnly += mime.lowercase() }
        internal fun isForcedSoftware(mime: String): Boolean = mime.lowercase() in softwareOnly
        /** Changes whenever [forceSoftware] adds a MIME type (for caches keyed on it). */
        internal fun forcedSoftwareKey(): String = softwareOnly.sorted().joinToString(",")

        /** Told when a layer encoder fails to start or to encode: (codec name, detail). */
        private val failureListeners = java.util.concurrent.CopyOnWriteArraySet<(String, String) -> Unit>()
        internal fun addFailureListener(l: (String, String) -> Unit) { failureListeners += l }
        internal fun removeFailureListener(l: (String, String) -> Unit) { failureListeners -= l }
        internal fun reportFailure(codec: String, detail: String) {
            failureListeners.forEach { try { it(codec, detail) } catch (_: Throwable) {} }
        }

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
        private val H264_PROVEN_PREFIXES = arrayOf("OMX.qcom.", "OMX.Exynos.")
        private fun isProvenH264(name: String) = H264_PROVEN_PREFIXES.any { name.startsWith(it, true) }

        /**
         * Local test switch for the Codec2 H.264 path, set from
         * [AppEngineOptions.experimentalH264Codec2]. Not for production: the
         * server's device config (`video.h264Codec2`) is the real gate.
         */
        @Volatile internal var experimentalH264Codec2 = false

        /** Android system property that also turns the path on for a test build: `adb shell setprop debug.applooma.h264codec2 1`. */
        private const val PROP_H264_CODEC2 = "debug.applooma.h264codec2"
        /** Same, for byte-buffer input on that path: `adb shell setprop debug.applooma.h264bytebuffer 1`. */
        private const val PROP_H264_BYTEBUFFER = "debug.applooma.h264bytebuffer"

        private fun systemFlag(key: String): Boolean = try {
            val v = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String
            v == "1" || v.equals("true", true)
        } catch (_: Throwable) { false }

        /**
         * Whether H.264 may run on a hardware encoder outside the proven set
         * (Codec2 c2.mtk / c2.qti / …). Off unless the server's device config,
         * the experimental option or the debug property turns it on.
         */
        internal fun h264Codec2Enabled(): Boolean =
            DeviceTelemetry.current.h264Codec2 || experimentalH264Codec2 || systemFlag(PROP_H264_CODEC2)

        /** Whether H.264 on encoder [name] runs through the Codec2 path (tuned encoder + stream fixer). */
        internal fun isCodec2H264(name: String): Boolean = !isProvenH264(name) && h264Codec2Enabled()

        /** With [h264Codec2Enabled]: feed that encoder byte buffers instead of a surface. */
        internal fun h264ByteBufferEnabled(): Boolean =
            h264Codec2Enabled() && (DeviceTelemetry.current.h264ByteBuffer || systemFlag(PROP_H264_BYTEBUFFER))

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
            // H.264 only on the encoders the engine itself has always driven
            // (OMX.qcom., OMX.Exynos.). Codec2 H.264 encoders run and the server
            // receives packets, but no other phone can bind the stream: seen on
            // MediaTek (c2.mtk, realme RMX3941) and on Qualcomm Codec2
            // (realme RMX5555, 2026-09-27: every viewer "track not bound").
            // Those phones publish VP8 instead, which every phone decodes, unless
            // the server enables the Codec2 H.264 path for the model (h264Codec2),
            // which drives them through the tuned encoder and the stream fixer.
            if (mime.equals("video/avc", true) && !isProvenH264(name) && !h264Codec2Enabled()) return false
            return true
        }

        /** The hardware encoder this factory would use for [mime], or null when it would be software (or nothing). */
        internal fun findHardwareEncoder(mime: String): MediaCodecInfo? = if (isForcedSoftware(mime)) null else findPlatformHardwareEncoder(mime)

        /** Like [findHardwareEncoder] but ignoring [forceSoftware]: the encoder the phone would offer. */
        internal fun findPlatformHardwareEncoder(mime: String): MediaCodecInfo? = try {
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
