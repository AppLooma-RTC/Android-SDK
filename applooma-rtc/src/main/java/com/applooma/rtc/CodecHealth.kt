package com.applooma.rtc

/**
 * Pure decision rules behind automatic codec recovery. No Android types, so
 * every rule is covered by plain JVM unit tests (see CodecHealthTest).
 *
 * Three independent pieces:
 *  - [EncoderWatchdog]: is the local camera encoder actually producing frames?
 *  - [ViewerReports]: are viewers telling us they cannot decode our video?
 *  - [CodecVerdict]: a remembered "this encoder is bad on this phone" entry.
 */
internal object CodecHealth {
    /** Reserved data topic for SDK-internal video quality reports. Never delivered to the app. */
    const val REPORT_TOPIC = "_al.vq"
    /** Any topic with this prefix is internal to the SDK. */
    const val INTERNAL_TOPIC_PREFIX = "_al."

    const val REASON_NO_FRAMES = "no_frames"
    const val REASON_ENCODER_ERROR = "encoder_error"
    const val REASON_VIEWERS = "viewers_cannot_decode"
    /** Device report outcome: viewers see no picture while our encoder is fine. */
    const val OUTCOME_VIEWER_STALL = "viewer_stall"

    fun isInternalTopic(topic: String?): Boolean = topic != null && topic.startsWith(INTERNAL_TOPIC_PREFIX)
}

/** One outbound video stats sample, summed over every layer of the camera track. */
internal data class EncoderSample(
    val atMs: Long,
    val framesEncoded: Long,
    val keyFramesEncoded: Long,
    val framesSent: Long,
)

/**
 * Watches the local camera encoder. Healthy means frames keep being encoded
 * (or sent) and at least one key frame came out within [firstKeyFrameMs] of
 * the publish. Unhealthy for longer than [unhealthyMs] trips it, once.
 */
internal class EncoderWatchdog(
    private val firstKeyFrameMs: Long = 4_000,
    private val unhealthyMs: Long = 5_000,
) {
    private var startedAt = -1L
    private var last: EncoderSample? = null
    private var unhealthySince = -1L
    private var tripped = false
    /** When frames were last seen being encoded and sent, or -1. */
    var lastProgressAt = -1L
        private set

    /** Whether frames were encoded and sent within [withinMs] of [nowMs]. */
    fun producedRecently(nowMs: Long, withinMs: Long = 6_000): Boolean =
        lastProgressAt >= 0 && nowMs - lastProgressAt <= withinMs

    /** The camera was (re)published or restarted: start over. */
    fun reset(nowMs: Long) {
        startedAt = nowMs
        last = null
        unhealthySince = -1L
        tripped = false
        lastProgressAt = -1L
    }

    /** Camera off or muted: nothing to judge; the clock restarts when it is back. */
    fun pause() { startedAt = -1L; last = null; unhealthySince = -1L; lastProgressAt = -1L }

    /**
     * Feed one sample. Returns [CodecHealth.REASON_NO_FRAMES] exactly once
     * when the encoder has been unhealthy for longer than [unhealthyMs], else null.
     */
    fun onSample(s: EncoderSample): String? {
        val prev = last
        if (prev != null && s.framesEncoded > prev.framesEncoded && s.framesSent > prev.framesSent) lastProgressAt = s.atMs
        if (tripped) { last = s; return null }
        if (startedAt < 0) startedAt = s.atMs
        last = s
        val progressing = prev == null ||
            s.framesEncoded > prev.framesEncoded || s.framesSent > prev.framesSent
        val keyFrameLate = s.keyFramesEncoded < 1 && s.atMs - startedAt > firstKeyFrameMs
        // The very first sample only sets the baseline, unless nothing at all
        // has come out of the encoder yet.
        val noOutputYet = prev == null && s.framesEncoded <= 0 && s.framesSent <= 0
        val healthy = (progressing && !noOutputYet) && !keyFrameLate
        if (healthy) { unhealthySince = -1L; return null }
        // Unhealthy since publish (nothing or no key frame yet), or since the
        // previous sample (the last moment frames were known to flow).
        if (unhealthySince < 0) unhealthySince = if (noOutputYet || keyFrameLate || prev == null) startedAt else prev.atMs
        if (s.atMs - unhealthySince > unhealthyMs) { tripped = true; return CodecHealth.REASON_NO_FRAMES }
        return null
    }
}

/**
 * Counts "I cannot decode your video" reports from viewers. Trips when
 * [needed] distinct viewers report within [windowMs], or on a single report
 * when the room holds exactly one other person (1:1).
 */
internal class ViewerReports(
    private val windowMs: Long = 20_000,
    private val needed: Int = 2,
) {
    private val reports = LinkedHashMap<String, Long>()

    fun clear() = reports.clear()

    /** Returns true when the reports justify a fallback. */
    fun onReport(nowMs: Long, viewerUid: String, otherParticipants: Int): Boolean {
        reports[viewerUid] = nowMs
        reports.entries.removeAll { nowMs - it.value > windowMs }
        if (otherParticipants <= 1) return true
        return reports.size >= needed
    }
}

/** What to do with a viewer's "no picture" report. */
internal enum class ViewerReportAction {
    /** Already on the safe path (software, one layer): a republish cannot help. */
    IGNORE_ALREADY_SAFE,
    /** Our encoder is producing and sending frames: the problem is on the network or the viewer's side. */
    VIEWER_STALL,
    /** Count it towards a fallback. */
    COUNT,
}

internal object ViewerReportRule {
    fun decide(softwareEncoder: Boolean, singleLayer: Boolean, encoderProducing: Boolean): ViewerReportAction = when {
        softwareEncoder && singleLayer -> ViewerReportAction.IGNORE_ALREADY_SAFE
        encoderProducing -> ViewerReportAction.VIEWER_STALL
        else -> ViewerReportAction.COUNT
    }
}

/** At most one automatic fallback per publish session. */
internal class FallbackGate {
    private var used = false
    fun tryAcquire(): Boolean { if (used) return false; used = true; return true }
    fun reset() { used = false }
}

/**
 * After the one codec fallback, a stalled camera gets a plain republish on the
 * same (already safe) encoder: at most [max] per publish session and at least
 * [minGapMs] apart, so a phone that cannot encode at all never loops.
 */
internal class CameraRestartBudget(
    private val max: Int = 3,
    private val minGapMs: Long = 30_000,
) {
    private var used = 0
    private var lastAt = -1L
    val count: Int get() = used
    fun tryAcquire(nowMs: Long): Boolean {
        if (used >= max) return false
        if (lastAt >= 0 && nowMs - lastAt < minGapMs) return false
        used++; lastAt = nowMs; return true
    }
    fun reset() { used = 0; lastAt = -1L }
}

/** A remembered "encoder X is bad on this phone" entry. */
internal data class CodecVerdict(
    val device: String,
    val encoder: String,
    val sdkVersion: String,
    val savedAtMs: Long,
) {
    fun isValid(nowMs: Long, device: String, encoder: String, sdkVersion: String): Boolean =
        this.device == device && this.encoder == encoder && this.sdkVersion == sdkVersion &&
            nowMs >= savedAtMs && nowMs - savedAtMs < MAX_AGE_MS

    fun encode(): String = listOf(device, encoder, sdkVersion, savedAtMs.toString()).joinToString("\u001f")

    companion object {
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        fun decode(s: String?): CodecVerdict? {
            val p = s?.split("\u001f") ?: return null
            if (p.size != 4) return null
            val at = p[3].toLongOrNull() ?: return null
            return CodecVerdict(p[0], p[1], p[2], at)
        }
    }
}
