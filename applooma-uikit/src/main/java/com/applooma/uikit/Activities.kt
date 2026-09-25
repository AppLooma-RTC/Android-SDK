package com.applooma.uikit

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A token provider for Java callers: runs on a background thread, so it may
 * block on your HTTP call. Return `{ token, wsUrl }` from YOUR server.
 */
fun interface TokenProvider {
    @Throws(Exception::class)
    fun getToken(room: String, role: String, user: KitUser): KitToken
}

/** Java-friendly constructor: a blocking [TokenProvider] instead of a suspend lambda. */
fun AppLoomaKit(appId: String, user: KitUser, provider: TokenProvider): AppLoomaKit =
    AppLoomaKit(appId, user) { room, role, u -> withContext(Dispatchers.IO) { provider.getToken(room, role, u) } }

/**
 * Ready-made screens as Activities, for apps that are not written in Compose
 * (Java, XML layouts, or a mixed codebase). Call [setup] once at startup,
 * then start any screen with one line:
 *
 * ```java
 * AppLoomaUiKit.setup(new AppLoomaKit("YOUR_APP_ID", new KitUser(me.id, me.name), (room, role, user) -> {
 *     MyApi.Token t = myApi.rtcToken(room, role);          // YOUR server, on a background thread
 *     return new KitToken(t.token, t.wsUrl);
 * }));
 * AppLoomaUiKit.openLiveStream(this, "show-42", "host");
 * ```
 */
object AppLoomaUiKit {
    @Volatile private var kit: AppLoomaKit? = null

    /** Register the kit every screen will use. Call once, before opening a screen. */
    @JvmStatic fun setup(kit: AppLoomaKit) { this.kit = kit }

    internal fun kitOrThrow(): AppLoomaKit = kit ?: error("AppLoomaUiKit.setup(kit) has not been called")

    /** Live streaming. `role` is "host", "audience", or null to let the user choose. */
    @JvmStatic @JvmOverloads
    fun openLiveStream(context: Context, room: String, role: String? = null) =
        context.startActivity(Intent(context, LiveStreamActivity::class.java).putExtra(ROOM, room).putExtra(ROLE, role))

    /** Voice room with `seats` seats (2–16, default 8). */
    @JvmStatic @JvmOverloads
    fun openVoiceRoom(context: Context, room: String, seats: Int = 8) =
        context.startActivity(Intent(context, VoiceRoomActivity::class.java).putExtra(ROOM, room).putExtra(SEATS, seats))

    /** 1-to-1 call; `video` false for voice only. */
    @JvmStatic @JvmOverloads
    fun openCall(context: Context, room: String, video: Boolean = true) =
        context.startActivity(Intent(context, CallActivity::class.java).putExtra(ROOM, room).putExtra(VIDEO, video))

    internal const val ROOM = "room"
    internal const val ROLE = "role"
    internal const val SEATS = "seats"
    internal const val VIDEO = "video"
}

private fun ComponentActivity.host(content: @Composable (kit: AppLoomaKit, room: String, leave: () -> Unit) -> Unit) {
    enableEdgeToEdge()
    val kit = AppLoomaUiKit.kitOrThrow()
    val room = intent.getStringExtra(AppLoomaUiKit.ROOM) ?: error("room extra missing")
    setContent {
        MaterialTheme(colorScheme = darkColorScheme(primary = C.violet, secondary = C.pink, background = C.ink, surface = C.ink)) {
            content(kit, room) { finish() }
        }
    }
}

class LiveStreamActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val role = intent.getStringExtra(AppLoomaUiKit.ROLE)
        host { kit, room, leave -> AppLoomaLiveStream(kit, room, role, leave) }
    }
}

class VoiceRoomActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seats = intent.getIntExtra(AppLoomaUiKit.SEATS, 8)
        host { kit, room, leave -> AppLoomaVoiceRoom(kit, room, seats, leave) }
    }
}

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val video = intent.getBooleanExtra(AppLoomaUiKit.VIDEO, true)
        host { kit, room, leave -> AppLoomaCall(kit, room, video, leave) }
    }
}

