package com.applooma.rtc

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoFrame
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Normalises an Annex-B H.264 stream from a Codec2 hardware encoder before it
 * reaches the packetizer:
 *  - caches the latest SPS/PPS and prepends them to every IDR that lacks them;
 *  - drops access unit delimiters (NAL 9) and SEI (NAL 6);
 *  - reports any access unit that contains an IDR (NAL 5) as a key frame,
 *    whatever flag the encoder set;
 *  - reads the SPS profile, so a stream that is not constrained baseline
 *    (what the SDP announces) is caught during the call.
 *
 * Pure Kotlin on byte arrays (no Android types), covered by H264StreamFixerTest.
 * Not thread-safe: one instance per encoder, used on its output thread.
 */
internal class H264StreamFixer(private val onProfile: (Profile) -> Unit = {}) {

    /** What [process] made of one access unit. [bytes] is the input array itself when nothing had to change. */
    class Result(val bytes: ByteArray, val isKeyFrame: Boolean, val changed: Boolean)

    /** profile_idc, the constraint flag byte and level_idc of an SPS. */
    data class Profile(val profileIdc: Int, val constraints: Int, val levelIdc: Int) {
        /** The SDP-style profile-level-id, e.g. "42e01f". */
        val profileLevelId: String get() = "%02x%02x%02x".format(profileIdc, constraints, levelIdc)

        /** Constrained baseline: profile_idc 66 with constraint_set1 (0x40), or 77/88 with set0+set1 as the SDP rules allow. */
        val isConstrainedBaseline: Boolean
            get() = when (profileIdc) {
                66 -> constraints and 0x40 != 0
                77, 88 -> constraints and 0xC0 == 0xC0
                else -> false
            }
    }

    /** Summary of a raw encoder output stream, see [inspect]. */
    data class Inspection(val hasSps: Boolean, val hasPps: Boolean, val idrCount: Int, val profile: Profile?)

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var lastProfile: Profile? = null

    /** The profile of the most recent SPS seen, or null before the first one. */
    val profile: Profile? get() = lastProfile

    fun process(au: ByteArray): Result {
        val nals = split(au).toMutableList()
        if (nals.isEmpty()) return Result(au, false, false)
        var hasIdr = false; var hasSps = false; var hasPps = false; var hasJunk = false
        for ((i, raw) in nals.withIndex()) when (type(raw)) {
            NAL_SPS -> {
                // Some Codec2 encoders (MediaTek) emit plain Baseline 42001f even when
                // asked for Constrained Baseline, while the call negotiated 42e01f.
                // This encoder never uses the Baseline-only tools (no FMO/ASO/B-frames),
                // so the stream already is Constrained Baseline: set the flags to match.
                val n = constrainBaseline(raw)
                if (n !== raw) { nals[i] = n; hasJunk = true }
                hasSps = true; sps = n; readProfile(n)
            }
            NAL_PPS -> { hasPps = true; pps = raw }
            NAL_IDR -> hasIdr = true
            NAL_AUD, NAL_SEI -> hasJunk = true
        }
        val prependSps = hasIdr && !hasSps && sps != null
        val prependPps = hasIdr && !hasPps && pps != null
        if (!hasJunk && !prependSps && !prependPps) return Result(au, hasIdr, false)

        val out = ByteArrayOutputStream(au.size + 64)
        if (prependSps) write(out, sps!!)
        if (prependPps) write(out, pps!!)
        for (n in nals) {
            val t = type(n)
            if (t == NAL_AUD || t == NAL_SEI) continue
            write(out, n)
        }
        return Result(out.toByteArray(), hasIdr, true)
    }

    private fun readProfile(sps: ByteArray) {
        val p = parseProfile(sps) ?: return
        if (p == lastProfile) return
        lastProfile = p
        onProfile(p)
    }

    companion object {
        /** Returns an SPS with constraint_set0/1 flags set when it is Baseline without them; otherwise the same array. */
        fun constrainBaseline(sps: ByteArray): ByteArray {
            if (sps.size < 4 || type(sps) != NAL_SPS) return sps
            val profileIdc = sps[1].toInt() and 0xFF
            val flags = sps[2].toInt() and 0xFF
            if (profileIdc != 66 || flags and 0x40 != 0) return sps
            val copy = sps.copyOf()
            copy[2] = (flags or 0xE0).toByte()
            return copy
        }

        const val NAL_IDR = 5
        const val NAL_SEI = 6
        const val NAL_SPS = 7
        const val NAL_PPS = 8
        const val NAL_AUD = 9

        private val START = byteArrayOf(0, 0, 0, 1)

        private fun write(o: ByteArrayOutputStream, nal: ByteArray) { o.write(START); o.write(nal) }

        fun type(nal: ByteArray): Int = if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1F

        /** NAL units (without start codes) of an Annex-B buffer; 3- and 4-byte start codes. */
        fun split(b: ByteArray): List<ByteArray> {
            val starts = ArrayList<IntArray>() // [start code index, payload index]
            var i = 0
            while (i + 2 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
                    if (b[i + 2].toInt() == 1) { starts += intArrayOf(i, i + 3); i += 3; continue }
                    if (i + 3 < b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += intArrayOf(i, i + 4); i += 4; continue }
                }
                i++
            }
            return starts.mapIndexedNotNull { k, s ->
                var end = if (k + 1 < starts.size) starts[k + 1][0] else b.size
                // trailing_zero_8bits belong to no NAL unit.
                while (end > s[1] && b[end - 1].toInt() == 0) end--
                if (end > s[1]) b.copyOfRange(s[1], end) else null
            }
        }

        /** Profile of an SPS NAL unit (header byte included), or null when it is not one. */
        fun parseProfile(sps: ByteArray): Profile? {
            if (sps.size < 4 || type(sps) != NAL_SPS) return null
            return Profile(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)
        }

        /** Summary of a raw encoder output stream, for the self-test. */
        fun inspect(stream: ByteArray): Inspection {
            var s = false; var p = false; var idr = 0; var profile: Profile? = null
            for (n in split(stream)) when (type(n)) {
                NAL_SPS -> { s = true; if (profile == null) profile = parseProfile(constrainBaseline(n)) }
                NAL_PPS -> p = true
                NAL_IDR -> idr++
            }
            return Inspection(s, p, idr, profile)
        }
    }
}

/**
 * Wraps a hardware H.264 encoder so its output goes through [H264StreamFixer]
 * before the engine packetizes it. [onBadProfile] gets the profile-level-id
 * once when the encoder produces something other than constrained baseline.
 */
internal class H264FixingEncoder(
    private val inner: VideoEncoder,
    private val onBadProfile: (String) -> Unit,
) : VideoEncoder {

    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        var reported = false
        val fixer = H264StreamFixer { p ->
            AppEngine.debug("h264 stream: encoder=${inner.implementationName} profile-level-id=${p.profileLevelId}")
            if (!p.isConstrainedBaseline && !reported) { reported = true; onBadProfile(p.profileLevelId) }
        }
        return inner.initEncode(settings, FixingCallback(callback, fixer))
    }

    private class FixingCallback(private val out: VideoEncoder.Callback, private val fixer: H264StreamFixer) : VideoEncoder.Callback {
        override fun onEncodedFrame(image: EncodedImage, info: VideoEncoder.CodecSpecificInfo) {
            val src = image.buffer.duplicate()
            val bytes = ByteArray(src.remaining()).also { src.get(it) }
            val r = fixer.process(bytes)
            val wantType = if (r.isKeyFrame) EncodedImage.FrameType.VideoFrameKey else image.frameType
            if (!r.changed && wantType == image.frameType) { out.onEncodedFrame(image, info); return }
            // The engine copies the payload inside onEncodedFrame, so a heap-backed direct copy is enough;
            // the original image keeps its release callback and is released by the encoder as usual.
            val buf = ByteBuffer.allocateDirect(r.bytes.size).put(r.bytes).also { it.rewind() }
            val rebuilt = EncodedImage.builder()
                .setBuffer(buf, null)
                .setEncodedWidth(image.encodedWidth)
                .setEncodedHeight(image.encodedHeight)
                .setCaptureTimeNs(image.captureTimeNs)
                .setFrameType(wantType)
                .setRotation(image.rotation)
                .setQp(image.qp)
                .createEncodedImage()
            out.onEncodedFrame(rebuilt, info)
        }
    }

    override fun release(): VideoCodecStatus = inner.release()
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus = inner.encode(frame, info)
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int): VideoCodecStatus = inner.setRateAllocation(allocation, framerate)
    override fun setRates(rcParameters: VideoEncoder.RateControlParameters): VideoCodecStatus = inner.setRates(rcParameters)
    override fun getScalingSettings(): VideoEncoder.ScalingSettings = inner.scalingSettings
    override fun getResolutionBitrateLimits(): Array<VideoEncoder.ResolutionBitrateLimits> = inner.resolutionBitrateLimits
    override fun getImplementationName(): String = inner.implementationName
    override fun getEncoderInfo(): VideoEncoder.EncoderInfo = inner.encoderInfo
    override fun isHardwareEncoder(): Boolean = inner.isHardwareEncoder
}
