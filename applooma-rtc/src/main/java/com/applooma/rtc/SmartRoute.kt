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
 */
internal object SmartRoute {
    private const val TIMEOUT_MS = 2000

    fun isOurs(url: String): Boolean {
        val host = try { URI(url).host?.lowercase() } catch (_: Throwable) { null } ?: return false
        return host == "applooma.dev" || host.endsWith(".applooma.dev")
    }

    suspend fun resolve(token: String, wsUrl: String): String {
        if (!isOurs(wsUrl)) return wsUrl
        return withContext(Dispatchers.IO) {
            try {
                val conn = URL("${DeviceTelemetry.API_BASE}/rtc/route").openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("Accept", "application/json")
                    conn.outputStream.use { it.write(JSONObject().put("token", token).toString().toByteArray(Charsets.UTF_8)) }
                    if (conn.responseCode != 200) return@withContext wsUrl
                    val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val routed = json.optString("wsUrl", "")
                    if (routed.isEmpty() || !isOurs(routed)) return@withContext wsUrl
                    AppEngine.debug("route region=${json.optString("region", "")}")
                    routed
                } finally { conn.disconnect() }
            } catch (_: Throwable) { wsUrl }
        }
    }
}
