package com.applooma.rtc

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Device-level tuning from the AppLooma server, and anonymous reports of how
 * the video encoder behaved on this phone. Process-wide, like the encoder
 * factory it tunes. Everything here is best effort: any failure is swallowed
 * and the call carries on with the SDK defaults.
 *
 * Config: the last good answer is kept in SharedPreferences with its ETag and
 * ttl and applied at engine creation, so a join never waits for the network.
 * A fetch at join refreshes it in the background (3 s timeout); the new
 * answer applies to the next publish.
 */
internal object DeviceTelemetry {
    /** Same API host the other SDKs use for their REST calls. */
    internal const val API_BASE = "https://api.applooma.dev/v1"
    private const val PREFS = "applooma.devcfg"
    private const val TIMEOUT_MS = 3_000
    private const val FLUSH_DELAY_MS = 5_000L
    private const val MAX_BATCH = 20
    private const val MAX_PENDING = 5

    @Volatile var current: DeviceVideoConfig = DeviceVideoConfig.EMPTY
        private set
    @Volatile private var loaded = false
    @Volatile private var fetching = false
    /** Region hint from [AppEngineOptions.region], sent with the config request and every report. */
    @Volatile var region: String = AppRegion.AUTO.code

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "applooma-device").apply { isDaemon = true } }

    /** Applies the cached config, once per process. Call before any room options are built. */
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val prefs = prefs(context)
            if (prefs.getString("key", null) != queryKey()) return
            DeviceVideoConfig.parse(prefs.getString("body", null))?.let { apply(it) }
        } catch (_: Throwable) {}
    }

    private fun apply(cfg: DeviceVideoConfig) {
        current = cfg
        cfg.denyHardware.forEach { AppLoomaVideoEncoderFactory.forceSoftware(it) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Refreshes the config in the background when the cached one is older than
     * its ttl. [onChanged] runs on the worker thread when a new answer arrived.
     */
    fun refresh(context: Context, token: String, onChanged: () -> Unit) {
        if (token.isBlank()) return
        flushWithoutJoin(context, token)
        if (fetching) return
        fetching = true
        val app = context.applicationContext
        try {
            io.execute {
                try { fetch(app, token, onChanged) } catch (_: Throwable) {} finally { fetching = false }
            }
        } catch (_: Throwable) { fetching = false }
    }

    private fun fetch(context: Context, token: String, onChanged: () -> Unit) {
        val prefs = prefs(context)
        val key = queryKey()
        val sameKey = prefs.getString("key", null) == key
        val now = System.currentTimeMillis()
        val ttlMs = current.ttlSeconds * 1000
        if (sameKey && now - prefs.getLong("at", 0L) in 0 until ttlMs) return

        val conn = URL("$API_BASE/sdk/device-config?${query()}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (sameKey) prefs.getString("etag", null)?.let { conn.setRequestProperty("If-None-Match", it) }
            when (conn.responseCode) {
                304 -> prefs.edit().putLong("at", now).apply()
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val cfg = DeviceVideoConfig.parse(body) ?: return
                    prefs.edit()
                        .putString("key", key)
                        .putString("body", body)
                        .putString("etag", conn.getHeaderField("ETag"))
                        .putLong("at", now)
                        .apply()
                    val changed = cfg != current
                    apply(cfg)
                    if (changed) {
                        AppEngine.debug("device tuning updated: $cfg")
                        onChanged()
                    }
                }
                else -> {} // keep the last good answer
            }
        } finally { conn.disconnect() }
    }

    /* ------------------------------------------------------------------ */
    /* Reports                                                             */
    /* ------------------------------------------------------------------ */

    private val queue = ArrayList<Pair<String, JSONObject>>()
    private var flushScheduled = false

    /**
     * Queue one report (sent in a batch a few seconds later). [token] is the
     * join token of the room it belongs to. [codec] is "vp8", "h264", "av1"...
     */
    fun report(token: String?, outcome: String, codec: String, encoderName: String?, fromCodec: String? = null, toCodec: String? = null) {
        if (token.isNullOrBlank()) return
        try {
            val item = item(outcome, codec, encoderName, fromCodec, toCodec)
            synchronized(queue) {
                queue += token to item
                if (!flushScheduled) {
                    flushScheduled = true
                    io.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * A report with no join token yet (the encoder self-test runs before any
     * join). Reports need a join token, so it is kept on the phone and sent
     * with the next join. At most [MAX_PENDING] are kept.
     */
    fun reportWithoutJoin(context: Context, outcome: String, codec: String, encoderName: String?) {
        try {
            val prefs = prefs(context)
            synchronized(queue) {
                val list = try { JSONArray(prefs.getString("pending", "[]")) } catch (_: Throwable) { JSONArray() }
                if (list.length() >= MAX_PENDING) return
                list.put(item(outcome, codec, encoderName, null, null))
                prefs.edit().putString("pending", list.toString()).apply()
            }
        } catch (_: Throwable) {}
    }

    private fun flushWithoutJoin(context: Context, token: String) {
        try {
            val prefs = prefs(context)
            val list = synchronized(queue) {
                val raw = prefs.getString("pending", null) ?: return
                prefs.edit().remove("pending").apply()
                try { JSONArray(raw) } catch (_: Throwable) { return }
            }
            synchronized(queue) {
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { queue += token to it.put("region", region) }
                if (!flushScheduled && list.length() > 0) {
                    flushScheduled = true
                    io.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun item(outcome: String, codec: String, encoderName: String?, fromCodec: String?, toCodec: String?): JSONObject =
            JSONObject().apply {
                put("sdkPlatform", "android")
                put("sdkVersion", clean(BuildConfig.SDK_VERSION, 32))
                put("osName", "Android")
                put("osVersion", clean(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(), 32))
                put("manufacturer", clean(Build.MANUFACTURER, 64))
                put("model", clean(Build.MODEL, 96))
                chipset()?.let { put("chipset", clean(it, 96)) }
                encoderName?.let { put("encoderName", clean(it, 128)) }
                put("codec", codec.lowercase())
                put("outcome", outcome)
                fromCodec?.let { put("fromCodec", it.lowercase()) }
                toCodec?.let { put("toCodec", it.lowercase()) }
                put("region", region)
            }

    private fun flush() {
        val items: List<Pair<String, JSONObject>>
        synchronized(queue) { items = ArrayList(queue); queue.clear(); flushScheduled = false }
        for ((token, group) in items.groupBy({ it.first }, { it.second })) {
            for (chunk in group.chunked(MAX_BATCH)) {
                try { post(token, JSONObject().put("reports", JSONArray(chunk)).toString()) } catch (_: Throwable) {}
            }
        }
    }

    private fun post(token: String, body: String) {
        val conn = URL("$API_BASE/sdk/device-report").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            conn.responseCode
        } finally { conn.disconnect() }
    }

    /* ------------------------------------------------------------------ */
    /* Device description                                                  */
    /* ------------------------------------------------------------------ */

    private fun chipset(): String? =
        (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null)
            ?: Build.HARDWARE?.takeIf { it.isNotBlank() }

    /** The hardware encoder the phone offers for the default codec, ignoring any software override. */
    private fun encoder(): String? =
        (AppLoomaVideoEncoderFactory.findPlatformHardwareEncoder("video/avc")
            ?: AppLoomaVideoEncoderFactory.findPlatformHardwareEncoder("video/x-vnd.on2.vp8"))?.name

    private fun fields(): List<Pair<String, String?>> = listOf(
        "platform" to "android",
        "sdkVersion" to BuildConfig.SDK_VERSION,
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "osVersion" to (Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString()),
        "encoder" to encoder(),
        "region" to region,
    )

    private fun query(): String = fields().filter { !it.second.isNullOrBlank() }
        .joinToString("&") { "${it.first}=${URLEncoder.encode(it.second!!.take(128), "UTF-8")}" }

    /** A cached answer is only valid for the same phone, OS and SDK. */
    private fun queryKey(): String = fields().joinToString("|") { it.second ?: "" }

    /** Keeps only the characters the server accepts. */
    internal fun clean(s: String?, max: Int): String =
        (s ?: "").replace(Regex("[^A-Za-z0-9 ._\\-+/(),:#]"), " ").trim().take(max).ifEmpty { "unknown" }
}
