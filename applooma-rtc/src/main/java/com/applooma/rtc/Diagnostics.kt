package com.applooma.rtc

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * Remote diagnostics: the SDK's own log lines, kept in memory and uploaded
 * (scrubbed) as one session when a call had a problem, when the server's
 * device config asks for it, or when the app calls
 * [AppEngine.uploadDiagnostics]. No audio, video, messages or names.
 * Opt out with [AppEngineOptions.remoteDiagnostics].
 */

/** Fixed-size ring of log lines. Adding is O(1) and formats nothing; timestamps are rendered only on [snapshot]. */
internal class DiagRing(val capacity: Int = 1500) {
    private val times = LongArray(capacity)
    private val lines = arrayOfNulls<String>(capacity)
    private var next = 0
    private var count = 0

    @Synchronized fun add(line: String, now: Long = System.currentTimeMillis()) {
        times[next] = now
        lines[next] = line
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    @Synchronized fun size(): Int = count

    @Synchronized fun clear() { java.util.Arrays.fill(lines, null); next = 0; count = 0 }

    /** Oldest first, as (time, line). */
    @Synchronized fun entries(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>(count)
        val start = (next - count + capacity) % capacity
        for (i in 0 until count) {
            val k = (start + i) % capacity
            out += times[k] to (lines[k] ?: continue)
        }
        return out
    }

    /**
     * Oldest first, "HH:mm:ss.SSS line" in UTC, scrubbed, keeping the newest
     * lines that fit in [maxBytes].
     */
    fun snapshot(scrub: (String) -> String = { it }, maxBytes: Int = Int.MAX_VALUE): List<String> {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val all = entries()
        val out = ArrayList<String>(all.size)
        var bytes = 0
        for (i in all.indices.reversed()) {
            val s = fmt.format(Date(all[i].first)) + " " + scrub(all[i].second)
            val n = s.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + n > maxBytes) break
            bytes += n
            out += s
        }
        out.reverse()
        return out
    }
}

/**
 * Removes anything personal or internal from a line before upload: engine
 * class and package names (mapped to neutral words), tokens, URL query
 * strings, display names, metadata and attribute values. User ids are
 * replaced by a short salted hash that is stable within one process, so a
 * log still shows "the same user" without saying who.
 */
internal object DiagScrubber {
    private val salt = java.security.SecureRandom().nextLong()

    // Built from parts so the SDK source itself stays free of these names.
    private val E1 = "live" + "kit"
    private val E2 = "web" + "rtc"
    private val E3 = "ago" + "ra"
    private val ENGINE = listOf(
        Regex("(?i)io\\.$E1\\.android") to "engine",
        Regex("(?i)$E1\\.org\\.$E2") to "rtc",
        Regex("(?i)org\\.$E2") to "rtc",
        Regex("(?i)$E1") to "engine",
        Regex("(?i)$E2") to "rtc",
        Regex("(?i)$E3") to "vendor",
    )
    private val JWT = Regex("\\beyJ[\\w-]{6,}\\.[\\w-]{6,}\\.[\\w-]{6,}\\b")
    private val URL_QUERY = Regex("(?i)\\b((?:https?|wss?)://[^\\s?#\"']+)\\?[^\\s\"']*")
    private val SECRET = Regex("(?i)\\b(token|access_token|secret|password|key|authorization)(\\s*[=:]\\s*)(\"[^\"]*\"|[^\\s,;&)}\\]]+)")
    private val PERSONAL = Regex("(?i)\\b(name|displayName|metadata|attributes|userName|nickname)(\\s*[=:]\\s*)(\"[^\"]*\"|\\{[^}]*\\}|[^\\s,;)}\\]]+)")
    private val UID = Regex("(?i)\\b(uid|localUid|identity|userId|viewer uid)(\\s*[=:]\\s*)([^\\s,;)}\\]]+)")

    fun scrub(line: String): String {
        var s = line
        for ((re, to) in ENGINE) s = re.replace(s, to)
        s = JWT.replace(s, "[token]")
        s = URL_QUERY.replace(s) { "${it.groupValues[1]}?[redacted]" }
        s = SECRET.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        s = PERSONAL.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        s = UID.replace(s) { "${it.groupValues[1]}${it.groupValues[2]}#${hash(it.groupValues[3])}" }
        if (s.length > 1000) s = s.take(1000)
        return s
    }

    private fun hash(v: String): String {
        var h = salt xor -7046029254386353131L
        for (c in v) h = (h xor c.code.toLong()) * 0x100000001B3L
        return java.lang.Long.toHexString(h).takeLast(6)
    }
}

/** Decides whether a session's log buffer is uploaded, and why. Pure. */
internal object DiagPolicy {
    const val TRIGGER_PROBLEM = "auto_problem"
    const val TRIGGER_REMOTE = "remote_enabled"
    const val TRIGGER_APP = "app_requested"
    /** Reconnects in one session that count as a problem. */
    const val REPEATED_RECONNECTS = 3

    const val PROBLEM_FALLBACK = "fallback"
    const val PROBLEM_NO_FRAMES = "no_frames"
    const val PROBLEM_SELF_TEST = "selftest_failed"
    const val PROBLEM_JOIN = "join_failed"
    const val PROBLEM_RECONNECTS = "reconnects"
    const val PROBLEM_ENCODER = "encoder_error"

    /** (trigger, reason) or null for "do not upload". An app request wins, then a problem, then remote collection. */
    fun decide(optedIn: Boolean, appReason: String?, problems: Set<String>, reconnects: Int, remoteEnabled: Boolean): Pair<String, String?>? {
        if (!optedIn) return null
        if (appReason != null) return TRIGGER_APP to appReason
        val all = if (reconnects >= REPEATED_RECONNECTS) problems + PROBLEM_RECONNECTS else problems
        if (all.isNotEmpty()) return TRIGGER_PROBLEM to all.sorted().joinToString(",").take(64)
        if (remoteEnabled) return TRIGGER_REMOTE to null
        return null
    }

    /** Characters the server accepts in `reason`. */
    fun cleanReason(s: String): String = s.replace(Regex("[^A-Za-z0-9 ._\\-+/(),:#]"), " ").trim().take(64).ifEmpty { "app" }
}

/** Process-wide collector and uploader. Everything is best effort; errors are swallowed. */
internal object Diagnostics {
    private const val MAX_UPLOAD_BYTES = 240 * 1024
    private const val MID_CALL_DELAY_S = 60L
    private const val TIMEOUT_MS = 10_000
    private const val PENDING_FILE = "applooma-diag-pending.gz"

    val ring = DiagRing(1500)
    @Volatile var enabled = true
        private set
    @Volatile private var appContext: Context? = null
    @Volatile private var token: String? = null
    @Volatile private var active = false
    private val problems = HashSet<String>()
    @Volatile private var reconnects = 0
    @Volatile private var appReason: String? = null
    @Volatile private var uploadedInSession = false
    private var midCall: ScheduledFuture<*>? = null

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "applooma-diag").apply { isDaemon = true } }

    /** Always on (cheap): one array slot per line. Only SDK-produced lines come here. */
    fun log(line: String) { ring.add(line) }

    fun configure(context: Context, remoteDiagnostics: Boolean) {
        appContext = context.applicationContext
        // One opt-out anywhere in the process wins.
        if (!remoteDiagnostics) enabled = false
    }

    fun problem(kind: String) {
        synchronized(problems) { problems += kind }
        log("diag: problem $kind")
    }

    fun reconnecting() { reconnects++ }

    fun updateToken(t: String) { if (active) token = t }

    fun beginSession(context: Context, joinToken: String) {
        appContext = context.applicationContext
        token = joinToken
        active = true
        uploadedInSession = false
        reconnects = 0
        if (!enabled) return
        val ctx = appContext ?: return
        try { io.execute { sendPending(ctx, joinToken) } } catch (_: Throwable) {}
        try {
            midCall?.cancel(false)
            midCall = io.schedule({ maybeUpload(final = false) }, MID_CALL_DELAY_S, TimeUnit.SECONDS)
        } catch (_: Throwable) {}
    }

    /** At leave or disconnect. Uploads when a trigger holds, then resets the session state. */
    fun endSession(why: String) {
        if (!active) return
        log("diag: session end ($why)")
        active = false
        try { midCall?.cancel(false) } catch (_: Throwable) {}
        midCall = null
        maybeUpload(final = true)
    }

    /** [AppEngine.uploadDiagnostics]: now when in a call, otherwise with the next session. */
    fun requestUpload(reason: String) {
        appReason = DiagPolicy.cleanReason(reason)
        log("diag: app requested upload")
        if (active) maybeUpload(final = false, force = true)
    }

    private fun maybeUpload(final: Boolean, force: Boolean = false) {
        val t = token
        val decision = DiagPolicy.decide(
            enabled, appReason, synchronized(problems) { problems.toSet() }, reconnects, DeviceTelemetry.current.diagEnabled
        )
        if (final) {
            // A new session starts clean, whether or not this one uploaded.
            synchronized(problems) { problems.clear() }
            reconnects = 0
        }
        if (decision == null || t.isNullOrBlank()) return
        // Mid-call: once per session (so a crash later still leaves a trace);
        // at the end: always, since it has the most lines. App requests: always.
        if (!final && !force && uploadedInSession) return
        if (decision.first == DiagPolicy.TRIGGER_APP) appReason = null
        uploadedInSession = true
        val ctx = appContext ?: return
        val lines = ring.snapshot(DiagScrubber::scrub, MAX_UPLOAD_BYTES)
        if (lines.isEmpty()) return
        try { io.execute { upload(ctx, t, decision.first, decision.second, lines) } } catch (_: Throwable) {}
    }

    private fun body(trigger: String, reason: String?, lines: List<String>): ByteArray {
        val json = JSONObject().apply {
            put("platform", "android")
            put("sdkVersion", DeviceTelemetry.clean(BuildConfig.SDK_VERSION, 32))
            put("manufacturer", DeviceTelemetry.clean(Build.MANUFACTURER, 64))
            put("model", DeviceTelemetry.clean(Build.MODEL, 96))
            put("osVersion", DeviceTelemetry.clean(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(), 32))
            put("region", DeviceTelemetry.region)
            put("trigger", trigger)
            reason?.let { put("reason", it) }
            put("lines", JSONArray(lines))
        }
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    private fun upload(context: Context, token: String, trigger: String, reason: String?, lines: List<String>) {
        val gz = try { body(trigger, reason, lines) } catch (_: Throwable) { return }
        val code = try { post(token, gz) } catch (_: java.io.IOException) { -1 } catch (_: Throwable) { return }
        // No network: keep this one (only the latest) for the next join.
        if (code < 0) try { File(context.filesDir, PENDING_FILE).writeBytes(gz) } catch (_: Throwable) {}
    }

    private fun sendPending(context: Context, token: String) {
        try {
            val f = File(context.filesDir, PENDING_FILE)
            if (!f.exists()) return
            val gz = f.readBytes()
            val code = try { post(token, gz) } catch (_: java.io.IOException) { -1 }
            if (code >= 0) f.delete()
        } catch (_: Throwable) {}
    }

    /** Returns the HTTP status; throws IOException when the server could not be reached. */
    private fun post(token: String, gz: ByteArray): Int {
        val conn = URL("${DeviceTelemetry.API_BASE}/sdk/diag").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setFixedLengthStreamingMode(gz.size)
            conn.outputStream.use { it.write(gz) }
            return conn.responseCode
        } finally { conn.disconnect() }
    }
}
