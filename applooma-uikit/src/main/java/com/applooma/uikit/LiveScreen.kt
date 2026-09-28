package com.applooma.uikit

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applooma.rtc.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Live streaming: one host on camera, any number of viewers. Comments, hearts
 * and gifts travel over sendMessage — no extra server.
 */
private val gifts = listOf("🌹" to "Rose", "💎" to "Diamond", "🚀" to "Rocket", "👑" to "Crown")
private data class Heart(val id: Long, val dx: Float, val emoji: String)

@Composable
fun AppLoomaLiveStream(kit: AppLoomaKit, room: String, role: String? = null, onLeave: () -> Unit = {}) {
    val onClose = onLeave
    var tick by remember { mutableIntStateOf(0) }
    val chat = remember { mutableStateListOf<ChatLine>() }
    val hearts = remember { mutableStateListOf<Heart>() }
    var banner by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var isHost by remember { mutableStateOf(true) }
    var joined by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(true) }
    var cam by remember { mutableStateOf(true) }
    var enhance by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(true) }
    var audioRoute by remember { mutableStateOf<AppAudioRoute?>(null) }
    var say by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun add(l: ChatLine) { chat.add(l); if (chat.size > 40) chat.removeAt(0) }
    fun heart() { hearts.add(Heart(System.nanoTime(), Random.nextFloat() * 60 - 30, listOf("💖", "💜", "💗", "✨", "🔥").random())) }
    fun gift(from: String, i: Int) {
        val (e, n) = gifts[i % gifts.size]
        add(ChatLine(from, "sent $n $e", gift = true))
        banner = Triple(from, n, e)
        scope.launch { delay(3200); banner = null }
    }

    val engine = rememberEngine(kit.appId, AppEngineOptions(audioScenario = AppAudioScenario.MEDIA, video = AppVideoConfig(fps = 30)), object : AppEventHandler() {
        override fun onTrackSubscribed(user: AppRemoteUser) { tick++ }
        override fun onUserMediaChanged(user: AppRemoteUser, video: Boolean, audio: Boolean) { tick++ }
        override fun onAudienceChanged(audience: List<AppRemoteUser>) { tick++ }
        override fun onUserJoined(user: AppRemoteUser) { if (!user.isPublisher) add(ChatLine(user.name, "joined", system = true)); tick++ }
        override fun onUserLeft(user: AppRemoteUser) { tick++ }
        override fun onAudioRouteChanged(route: AppAudioRoute?, available: List<AppAudioRoute>) { audioRoute = route }

        override fun onMessage(message: AppMessage) {
            val from = message.from?.name ?: "Someone"
            when {
                message.text != null -> add(ChatLine(from, message.text!!))
                message.data?.get("kind") == "like" -> heart()
                message.data?.get("kind") == "gift" -> gift(from, (message.data!!["i"] as Number).toInt())
            }
        }
        override fun onError(error: Throwable) { add(ChatLine("", error.message ?: "Error", system = true)) }
    })

    var wantJoin by remember { mutableStateOf(false) }
    fun start() { if (!joining && !joined) { joining = true; wantJoin = true } }
    if (wantJoin) {
        RequestPermissions(camera = isHost, onDenied = { joining = false; wantJoin = false; add(ChatLine("", "Camera and microphone permission is needed", system = true)) }) {
            try {
                engine.setSpeakerphone(speaker)
                val t = kit.tokenProvider(room, if (isHost) "host" else "audience", kit.user)
                engine.joinChannel(t.token, t.wsUrl, AppJoinOptions(role = if (isHost) AppRole.HOST else AppRole.AUDIENCE, camera = isHost, microphone = isHost))
                add(ChatLine("", "Welcome! Be kind in the chat 💬", system = true))
                joined = true
            } catch (e: Exception) {
                add(ChatLine("", e.message ?: "Could not connect", system = true))
                wantJoin = false
            } finally { joining = false }
        }
    }
    // A fixed role skips the chooser and joins straight away.
    LaunchedEffect(Unit) { if (role != null) { isHost = role == "host"; start() } }

    @Suppress("UNUSED_EXPRESSION") tick
    val host = engine.hosts.firstOrNull()
    val showVideo = joined && (if (isHost) cam else host?.hasVideo == true)
    val hostName = if (isHost) kit.user.name else host?.name ?: "—"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (showVideo) {
            if (isHost) LocalVideo(engine, Modifier.fillMaxSize()) else RemoteVideo(engine, host!!, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2A1F5A), C.ink))), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Sensors, null, tint = C.muted, modifier = Modifier.size(34.dp))
                    Text(if (!joined) "Live · ${room.removePrefix("live-")}" else if (isHost) "Camera is off" else "Waiting for the host…",
                        color = C.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Shade(top = true, height = 140.dp, modifier = Modifier.align(Alignment.TopCenter))
        Shade(top = false, height = 340.dp, modifier = Modifier.align(Alignment.BottomCenter))

        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // Top bar
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Glass(padding = PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 12.dp)) {
                    Avatar(if (isHost) kit.user.id else host?.uid ?: room, hostName, 34.dp)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(if (joined) hostName else "—", color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(if (isHost && joined) "You are live" else "Live", color = C.muted, fontSize = 11.sp)
                    }
                }
                if (joined) Text("LIVE", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(C.live).padding(horizontal = 8.dp, vertical = 3.dp))
                Spacer(Modifier.weight(1f))
                Glass {
                    Icon(Icons.Rounded.Visibility, null, tint = C.text, modifier = Modifier.size(15.dp))
                    Text("${engine.audienceCount + if (isHost || !joined) 0 else 1}", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
                }
                Glass(Modifier.clickable(onClick = onClose), PaddingValues(8.dp)) { Icon(Icons.Rounded.Close, null, tint = C.text, modifier = Modifier.size(18.dp)) }
            }
            // Gift banner
            banner?.let { (from, n, e) ->
                Row(
                    Modifier.padding(start = 12.dp, top = 6.dp).clip(RoundedCornerShape(999.dp))
                        .background(Brush.horizontalGradient(listOf(Color(0xF2FFC24B), Color(0xE6FF4FA3)))).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(from, from, 34.dp)
                    Column(Modifier.padding(horizontal = 10.dp)) {
                        Text(from, color = Color(0xFF1A0F00), fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        Text("sent $n", color = Color(0xFF1A0F00), fontSize = 12.sp)
                    }
                    Text(e, fontSize = 28.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth()) {
                LazyColumn(Modifier.padding(start = 12.dp, end = 90.dp).heightIn(max = 240.dp), reverseLayout = true) {
                    items(chat.reversed()) { ChatBubble(it) }
                }
                // Floating hearts
                hearts.forEach { h -> key(h.id) { FloatingHeart(h) { hearts.remove(h) } } }
                if (joined && isHost) Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundBtn(if (mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, { mic = !mic; engine.enableMicrophone(mic) }, off = !mic)
                    RoundBtn(if (cam) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, { cam = !cam; engine.enableCamera(cam) }, off = !cam)
                    RoundBtn(Icons.Rounded.Cameraswitch, { engine.switchCamera() })
                    RoundBtn(Icons.Rounded.AutoFixHigh, { enhance = !enhance; engine.setVideoEnhance(if (enhance) AppVideoEnhance.Portrait else null) }, brand = enhance)
                }
            }
            if (joined) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SayField(say, { say = it }, "Say something…", Modifier.weight(1f)) {
                        val text = say.trim(); if (text.isNotEmpty()) { say = ""; add(ChatLine(kit.user.name, text)); engine.sendMessage(text = text) }
                    }
                    SpeakerBtn(speaker, audioRoute, { speaker = !speaker; engine.setSpeakerphone(speaker) })
                    RoundBtn(Icons.Rounded.CardGiftcard, { val i = Random.nextInt(gifts.size); gift(kit.user.name, i); engine.sendMessage(data = mapOf("kind" to "gift", "i" to i)) },
                        tint = C.gold, bg = C.gold.copy(alpha = 0.2f))
                    RoundBtn(Icons.Rounded.Favorite, { heart(); engine.sendMessage(data = mapOf("kind" to "like"), reliable = false) }, brand = true)
                }
            } else {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Go live" to true, "Watch" to false).forEach { (label, v) ->
                            val on = isHost == v
                            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(12.dp)).then(if (on) Modifier.background(C.brand) else Modifier).clickable { isHost = v },
                                contentAlignment = Alignment.Center) { Text(label, color = if (on) Color.White else C.muted, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(C.brand).clickable { start() }, contentAlignment = Alignment.Center) {
                        Text(if (joining) "Connecting…" else if (isHost) "Start broadcast" else "Join as viewer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.FloatingHeart(h: Heart, done: () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(2200, easing = LinearOutSlowInEasing)); done() }
    val v = t.value
    Text(h.emoji, fontSize = 28.sp, modifier = Modifier.align(Alignment.BottomEnd)
        .padding(end = 22.dp, bottom = 150.dp)
        .offset(x = (h.dx * v).dp, y = (-280 * v).dp)
        .alpha(if (v < 0.15f) v / 0.15f else 1 - (v - 0.15f) / 0.85f)
        .scale(0.6f + 0.5f * (if (v < 0.15f) v / 0.15f else 1 - v * 0.3f)))
}

@Composable
fun SayField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier, onSend: () -> Unit) {
    Box(modifier.height(46.dp).clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(hint, color = C.muted, fontSize = 14.sp)
        BasicTextField(value, { if (it.length <= 120) onChange(it) }, singleLine = true,
            textStyle = TextStyle(color = C.text, fontSize = 14.sp), cursorBrush = SolidColor(C.violet),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }),
            modifier = Modifier.fillMaxWidth())
    }
}
