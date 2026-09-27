package com.applooma.rtc

/**
 * Per-device video overrides decided by the AppLooma server (GET
 * /v1/sdk/device-config). Pure Kotlin, no Android types, so parsing and the
 * merge into the video caps are covered by plain JVM tests (DeviceConfigTest).
 *
 * Every field is optional; [EMPTY] means "use the SDK defaults".
 */
internal data class DeviceVideoConfig(
    val denyHardware: Set<String> = emptySet(),
    val forceCodec: String? = null,
    val preferCodec: String? = null,
    val maxHeight: Int = 0,
    val maxFps: Int = 0,
    val maxBitrateKbps: Int = 0,
    val disableSimulcast: Boolean = false,
    val ttlSeconds: Long = DEFAULT_TTL_S,
) {
    companion object {
        const val DEFAULT_TTL_S = 3600L
        val EMPTY = DeviceVideoConfig()
        private val CODECS = setOf("vp8", "h264", "h265", "av1", "vp9")
        private val MIMES = setOf("video/avc", "video/hevc", "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01")

        /** Parses a response body. Unknown or malformed fields are ignored; null when the body is not a JSON object. */
        fun parse(body: String?): DeviceVideoConfig? {
            val root = try { MiniJson(body ?: return null).parse() } catch (_: Throwable) { return null } as? Map<*, *> ?: return null
            val ttl = (root["ttlSeconds"] as? Number)?.toLong()?.takeIf { it > 0 } ?: DEFAULT_TTL_S
            val v = root["video"] as? Map<*, *> ?: return DeviceVideoConfig(ttlSeconds = ttl)
            fun codec(key: String) = (v[key] as? String)?.lowercase()?.takeIf { it in CODECS }
            fun positive(key: String) = (v[key] as? Number)?.toInt()?.takeIf { it > 0 } ?: 0
            return DeviceVideoConfig(
                denyHardware = (v["denyHardware"] as? List<*>)?.mapNotNull { (it as? String)?.lowercase()?.takeIf { m -> m in MIMES } }?.toSet() ?: emptySet(),
                forceCodec = codec("forceCodec"),
                preferCodec = codec("preferCodec"),
                maxHeight = positive("maxHeight"),
                maxFps = positive("maxFps"),
                maxBitrateKbps = positive("maxBitrateKbps"),
                disableSimulcast = v["disableSimulcast"] == true,
                ttlSeconds = ttl,
            )
        }
    }
}

/** Merges a [DeviceVideoConfig] into what the engine resolved from the app's config. */
internal object DeviceCaps {
    data class Caps(val height: Int, val fps: Int, val maxBitrate: Int, val simulcast: Boolean)

    private val HEIGHTS = intArrayOf(360, 540, 720, 1080, 1440, 2160)

    /** Upper caps only: never raises what the app or the tier chose. */
    fun apply(c: Caps, cfg: DeviceVideoConfig): Caps {
        val height = if (cfg.maxHeight > 0 && c.height > cfg.maxHeight)
            HEIGHTS.filter { it <= cfg.maxHeight }.maxOrNull() ?: HEIGHTS.first()
        else c.height
        val fps = if (cfg.maxFps > 0) minOf(c.fps, cfg.maxFps) else c.fps
        val cap = cfg.maxBitrateKbps.toLong() * 1000
        val bitrate = if (cap > 0 && c.maxBitrate > cap) cap.toInt() else c.maxBitrate
        return Caps(height, fps, bitrate, c.simulcast && !cfg.disableSimulcast)
    }

    /**
     * The codec to resolve: [DeviceVideoConfig.forceCodec] always wins,
     * [DeviceVideoConfig.preferCodec] only replaces AUTO. Codecs Android cannot
     * publish (vp9) are ignored.
     */
    fun codec(app: AppVideoCodec, cfg: DeviceVideoConfig): AppVideoCodec {
        codecOf(cfg.forceCodec)?.let { return it }
        if (app == AppVideoCodec.AUTO) codecOf(cfg.preferCodec)?.let { return it }
        return app
    }

    private fun codecOf(s: String?): AppVideoCodec? = when (s) {
        "vp8" -> AppVideoCodec.VP8; "h264" -> AppVideoCodec.H264; "h265" -> AppVideoCodec.H265; "av1" -> AppVideoCodec.AV1
        else -> null
    }
}

/** A small JSON reader: objects, arrays, strings, numbers, booleans and null. */
internal class MiniJson(private val s: String) {
    private var i = 0

    fun parse(): Any? { val v = value(); ws(); require(i == s.length) { "trailing data" }; return v }

    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun value(): Any? {
        ws()
        require(i < s.length) { "unexpected end" }
        return when (val c = s[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> lit("true", true)
            'f' -> lit("false", false)
            'n' -> lit("null", null)
            else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected $c")
        }
    }

    private fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)); i += word.length; return v }

    private fun obj(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        i++; ws()
        if (s[i] == '}') { i++; return out }
        while (true) {
            ws(); require(s[i] == '"'); val k = str()
            ws(); require(s[i] == ':'); i++
            out[k] = value(); ws()
            when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("bad object") }
        }
    }

    private fun arr(): List<Any?> {
        val out = ArrayList<Any?>()
        i++; ws()
        if (s[i] == ']') { i++; return out }
        while (true) {
            out += value(); ws()
            when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("bad array") }
        }
    }

    private fun str(): String {
        val b = StringBuilder()
        i++
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return b.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                    'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> b.append(e)
                }
                else -> b.append(c)
            }
        }
    }

    private fun num(): Number {
        val start = i
        while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
        val t = s.substring(start, i)
        return t.toLongOrNull() ?: t.toDouble()
    }
}
