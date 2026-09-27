package com.applooma.uikit

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.applooma.rtc.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 1-to-1 call, voice or video. Both people open the same call code; the second
 * to arrive connects the call and the timer starts.
 */
@Composable
fun AppLoomaCall(kit: AppLoomaKit, room: String, video: Boolean = true, onLeave: () -> Unit = {}) {
    val onClose = onLeave
    var tick by remember { mutableIntStateOf(0) }
    var peer by remember { mutableStateOf<AppRemoteUser?>(null) }
    var startedAt by remember { mutableStateOf<Long?>(null) }
    var status by remember { mutableStateOf("Connecting…") }
    var quality by remember { mutableStateOf("HD") }
    var bandwidthSince by remember { mutableStateOf<Long?>(null) }
    var mic by remember { mutableStateOf(true) }
    var cam by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var pip by remember { mutableStateOf(IntOffset.Zero) }

    fun meet(u: AppRemoteUser) { if (peer == null) peer = u; if (startedAt == null) startedAt = System.currentTimeMillis(); tick++ }

    val engine = rememberEngine(
        kit.appId,
        // A private voice call belongs on the call path: earpiece, speech-tuned echo cancelling.
        AppEngineOptions(audioScenario = if (video) AppAudioScenario.MEDIA else AppAudioScenario.CALL, video = AppVideoConfig(fps = 30)),
        object : AppEventHandler() {
            override fun onUserJoined(user: AppRemoteUser) = meet(user)
            override fun onTrackSubscribed(user: AppRemoteUser) = meet(user)
            override fun onUserMediaChanged(user: AppRemoteUser, video: Boolean, audio: Boolean) { tick++ }
            override fun onUserLeft(user: AppRemoteUser) {
                if (user.uid != peer?.uid) return
                peer = null; startedAt = null; status = "Call ended"; ended = true
            }
            override fun onRemoteStats(stats: List<AppRemoteStats>) {
                val s = stats.firstOrNull() ?: return
                // Never a pixel count: "HD" normally, "Weak network" only after
                // five seconds of a bandwidth-limited camera or heavy audio loss.
                val limited = bandwidthSince?.let { System.currentTimeMillis() - it >= 5000 } == true
                quality = if (limited || s.audioPacketsLost > 50) "Weak network" else "HD"
            }
            override fun onVideoQualityChanged(quality: AppVideoQualityInfo) {
                bandwidthSince = if (quality.reason == AppVideoQualityReason.BANDWIDTH) (bandwidthSince ?: System.currentTimeMillis()) else null
            }
        },
    )

    RequestPermissions(camera = video, onDenied = { status = "Permissions are needed for the call" }) {
        try {
            val t = kit.tokenProvider(room, "host", kit.user)
            engine.joinChannel(t.token, t.wsUrl, AppJoinOptions(role = AppRole.HOST, camera = video, microphone = true))
            status = "Calling…"
            engine.remoteUsers.forEach(::meet)
        } catch (e: Exception) { status = e.message ?: "Could not connect" }
    }
    LaunchedEffect(ended) { if (ended) { delay(1400); onClose() } }
    LaunchedEffect(startedAt) { while (startedAt != null) { delay(500); tick++ } }

    @Suppress("UNUSED_EXPRESSION") tick
    val p = peer
    val name = p?.name ?: status
    val showRemote = video && p?.hasVideo == true
    val elapsed = startedAt?.let { formatTime((System.currentTimeMillis() - it) / 1000) }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearOutSlowInEasing)), label = "p")

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (showRemote) RemoteVideo(engine, p!!, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF1D3A4D), C.ink))), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                    for (phase in listOf(0f, 0.5f)) {
                        val v = (pulse.value + phase) % 1f
                        Box(Modifier.size(132.dp).scale(1f + 0.7f * v).alpha(1f - v).clip(CircleShape).background(C.green.copy(alpha = 0.18f)))
                    }
                    Avatar(p?.uid ?: room, if (p == null) "…" else name, 132.dp)
                }
                Text(name, color = C.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 10.dp))
                Text(
                    elapsed?.let { buildAnnotatedString { append(it) } } ?: buildAnnotatedString {
                        append("Share the code ")
                        withStyle(SpanStyle(color = C.text, fontWeight = FontWeight.Bold)) { append(room.replace(Regex("^(a|v)call-"), "")) }
                    },
                    color = C.muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (showRemote) Shade(top = true, height = 140.dp, modifier = Modifier.align(Alignment.TopCenter))

        Row(Modifier.systemBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Glass(Modifier.clickable(onClick = onClose), PaddingValues(8.dp)) { Icon(Icons.Rounded.KeyboardArrowDown, null, tint = C.text, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.weight(1f))
            if (showRemote && elapsed != null) Glass { Text(elapsed, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            Glass {
                Icon(Icons.Rounded.SignalCellularAlt, null, tint = C.green, modifier = Modifier.size(14.dp))
                Text(quality, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 5.dp))
            }
        }

        if (video && cam) {
            Box(
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(top = 60.dp, end = 14.dp)
                    .offset { pip }
                    .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); pip = IntOffset(pip.x + drag.x.roundToInt(), pip.y + drag.y.roundToInt()) } }
                    .size(108.dp, 160.dp).shadow(20.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF111111)).border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
            ) { LocalVideo(engine, Modifier.fillMaxSize()) }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .navigationBarsPadding().padding(top = 30.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top,
        ) {
            RoundBtn(if (mic) Icons.Rounded.Mic else Icons.Rounded.MicOff, { mic = !mic; engine.enableMicrophone(mic) }, off = !mic, label = "Mute")
            if (video) RoundBtn(if (cam) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, { cam = !cam; engine.enableCamera(cam) }, off = !cam, label = "Camera")
            RoundBtn(Icons.Rounded.CallEnd, onClose, size = 66.dp, danger = true, label = "End")
            if (video) RoundBtn(Icons.Rounded.Cameraswitch, { engine.switchCamera() }, label = "Flip")
        }
    }
}
