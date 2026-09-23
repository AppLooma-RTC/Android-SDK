package com.applooma.uikit

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applooma.rtc.*
import kotlinx.coroutines.delay

/**
 * Voice room: up to 8 people on seats with speaking rings, plus text chat.
 *
 * Seats are ordered by join time. Everyone announces theirs with a small
 * message and answers newcomers, so every device draws the same seat order
 * without any server state. The first person holds the crown.
 */

@Composable
fun AppLoomaVoiceRoom(kit: AppLoomaKit, room: String, seats: Int = 8, onLeave: () -> Unit = {}) {
    val onClose = onLeave
    val SEATS = seats.coerceIn(2, 16)
    var tick by remember { mutableIntStateOf(0) }
    val joinedAt = remember { mutableStateMapOf(kit.user.id to System.currentTimeMillis()) }
    val names = remember { mutableStateMapOf(kit.user.id to kit.user.name) }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var speaking by remember { mutableStateOf(emptySet<String>()) }
    var mic by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("connecting…") }
    var say by remember { mutableStateOf("") }
    fun add(l: ChatLine) { chat.add(l); if (chat.size > 60) chat.removeAt(0) }

    lateinit var engineRef: AppEngine
    fun hello() = engineRef.sendMessage(data = mapOf("kind" to "hi", "at" to joinedAt[kit.user.id], "name" to kit.user.name))

    val engine = rememberEngine(kit.appId, AppEngineOptions(audioScenario = AppAudioScenario.MEDIA), object : AppEventHandler() {
        override fun onUserJoined(user: AppRemoteUser) { names[user.uid] = user.name; add(ChatLine(user.name, "joined the room", system = true)); hello() }
        override fun onUserLeft(user: AppRemoteUser) { add(ChatLine(names[user.uid] ?: user.uid, "left", system = true)); joinedAt.remove(user.uid) }
        override fun onUserMediaChanged(user: AppRemoteUser, video: Boolean, audio: Boolean) { tick++ }
        override fun onActiveSpeakersChanged(uids: List<String>) { speaking = uids.toSet() }
        override fun onMessage(message: AppMessage) {
            val uid = message.from?.uid ?: return
            val d = message.data
            when {
                d?.get("kind") == "hi" -> { joinedAt[uid] = (d["at"] as Number).toLong(); names[uid] = (d["name"] as? String) ?: uid }
                d?.get("kind") == "wave" -> add(ChatLine(names[uid] ?: uid, "waved 👋", system = true))
                message.text != null -> add(ChatLine(names[uid] ?: uid, message.text!!))
            }
        }
        override fun onError(error: Throwable) { add(ChatLine("", error.message ?: "Error", system = true)) }
    })
    engineRef = engine

    RequestPermissions(camera = false, onDenied = { status = "microphone permission needed" }) {
        try {
            val t = kit.tokenProvider(room, "cohost", kit.user)
            engine.joinChannel(t.token, t.wsUrl, AppJoinOptions(role = AppRole.COHOST, microphone = true))
            engine.remoteUsers.forEach { names[it.uid] = it.name }
            add(ChatLine("", "You are on a seat — just start talking 🎧", system = true))
            status = "live"
            hello()
        } catch (e: Exception) { status = "failed"; add(ChatLine("", e.message ?: "Could not connect", system = true)) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } } // remote mute changes

    @Suppress("UNUSED_EXPRESSION") tick
    val present = (listOf(kit.user.id) + engine.remoteUsers.map { it.uid })
        .sortedWith(compareBy<String> { joinedAt[it] ?: Long.MAX_VALUE }.thenBy { it })
    val ripple = rememberInfiniteTransition(label = "ripple").animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "r")

    @Composable
    fun Seat(index: Int, big: Boolean = false, modifier: Modifier = Modifier) {
        val uid = present.getOrNull(index)
        val size: Dp = if (big) 86.dp else 60.dp
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (uid == null) {
                Box(Modifier.padding(8.dp).size(size).clip(CircleShape).background(C.glass).border(1.5.dp, Color.White.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, null, tint = C.muted)
                }
                Text("Seat ${index + 1}", color = C.muted, fontSize = 10.5.sp)
                return@Column
            }
            val name = names[uid] ?: uid
            val muted = if (uid == kit.user.id) !mic else engine.getUser(uid)?.hasAudio == false
            Box(Modifier.size(size + 16.dp), contentAlignment = Alignment.Center) {
                if (speaking.contains(uid) && !muted) {
                    Box(Modifier.size(size).scale(1f + 0.3f * ripple.value).alpha(1f - ripple.value).border(2.5.dp, C.green, CircleShape))
                }
                Avatar(uid, name, size)
                if (index == 0) Text("👑", fontSize = 18.sp, modifier = Modifier.align(Alignment.TopCenter))
                if (muted) Box(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(C.live).border(2.dp, C.ink, CircleShape),
                    contentAlignment = Alignment.Center) { Icon(Icons.Rounded.MicOff, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
            Text(if (uid == kit.user.id) "$name (you)" else name, color = Color(0xFFD9D9E6), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF2C1F66), 0.4f to Color(0xFF141030), 1f to C.ink))) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glass(Modifier.clickable(onClick = onClose), PaddingValues(8.dp)) { Icon(Icons.Rounded.Close, null, tint = C.text, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.weight(1f))
                Glass {
                    Icon(Icons.Rounded.People, null, tint = C.text, modifier = Modifier.size(15.dp))
                    Text("${present.size}", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("🎙️ ${room.removePrefix("voice-")}", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(C.green))
                    Text("Voice room · $status", color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            Seat(0, big = true, modifier = Modifier.align(Alignment.CenterHorizontally))
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    for (col in 0 until 4) {
                        val i = 1 + row * 4 + col
                        if (i < SEATS) Seat(i, modifier = Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), reverseLayout = true) { items(chat.reversed()) { ChatBubble(it) } }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SayField(say, { say = it }, "Say hi to the room…", Modifier.weight(1f)) {
                    val t = say.trim(); if (t.isNotEmpty()) { say = ""; add(ChatLine(kit.user.name, t)); engine.sendMessage(text = t) }
                }
                RoundBtn(if (mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, { mic = !mic; engine.enableMicrophone(mic) }, off = !mic)
                RoundBtn(Icons.Rounded.WavingHand, { add(ChatLine(kit.user.name, "waved 👋", system = true)); engine.sendMessage(data = mapOf("kind" to "wave")) })
            }
        }
    }
}
