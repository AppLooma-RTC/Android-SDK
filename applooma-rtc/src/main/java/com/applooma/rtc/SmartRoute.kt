package com.applooma.rtc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Smart routing: asks the AppLooma API which media server suits this device
 * and the room. Only used for our own wsUrls; any failure keeps the app's URL.
 *
 * The answer matters more than it looks: everyone in a channel must land on
 * the same media server, and the API pins the room on the first routed join.
 * A device that gives up on the route and connects to the token's URL can end
 * up alone on another server while later joiners are routed elsewhere. So the
 * route is tried twice, with a longer wait than the API ever needs, and a
 * final failure is logged as a warning rather than passed over in silence.
 */
internal object SmartRoute {
    /** Per attempt. The API answers in well under a second; a dead region no longer holds it. */
    internal const val TIMEOUT_MS = 3000
    internal const val ATTEMPTS = 2

    fun isOurs(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Throwable) { null } ?: return false
        return host == "applooma.dev" || host.endsWith(".applooma.dev")
    }

    suspend fun resolve(token: String, wsUrl: String): String {
        if (!isOurs(wsUrl)) return wsUrl
        return withContext(Dispatchers.IO) {
            var lastError: String? = null
            repeat(ATTEMPTS) { attempt ->
                when (val r = attemptOnce(token)) {
                    is Result.Routed -> {
                        AppEngine.debug("route region=${r.region}${if (attempt > 0) " (attempt ${attempt + 1})" else ""}")
                        return@withContext r.wsUrl
                    }
                    is Result.Refused -> {
                        // A clear "no" from the API (bad token, unknown room): retrying cannot change it.
                        AppEngine.debug("route refused: HTTP ${r.code}; using the token's server")
                        return@withContext wsUrl
                    }
                    is Result.Failed -> lastError = r.error
                }
            }
            android.util.Log.w(
                AppEngine.TAG,
                "Smart routing failed after $ATTEMPTS attempts ($lastError); connecting to the token's server. " +
                    "If others in this channel were routed elsewhere they will not see each other.",
            )
            AppEngine.debug("warning: route failed ($lastError); using the token's server")
            wsUrl
        }
    }

    private sealed class Result {
        class Routed(val wsUrl: String, val region: String) : Result()
        class Refused(val code: Int) : Result()
        class Failed(val error: String) : Result()
    }

    private fun attemptOnce(token: String): Result = try {
        val conn = URL("${DeviceTelemetry.API_BASE}/rtc/route").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            when {
                code == 200 -> {
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val routed = json.optString("wsUrl", "")
                    if (routed.isEmpty() || !isOurs(routed)) Result.Failed("unusable answer") else Result.Routed(routed, json.optString("region", ""))
                }
                code in 400..499 -> Result.Refused(code)
                else -> Result.Failed("HTTP $code")
            }
        } finally { conn.disconnect() }
    } catch (t: Throwable) { Result.Failed(t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")) }
}
