package com.applooma.uikit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ public API

/** Who is using the kit. `id` must be unique in the room; `name` is shown to others. */
data class KitUser(val id: String, val name: String, val avatarUrl: String? = null)

/** A token from YOUR server: `{ token, wsUrl }`. The API secret never reaches the app. */
data class KitToken(val token: String, val wsUrl: String)

/**
 * What every kit screen needs.
 *
 * @param tokenProvider asks YOUR server for a token for (room, role, user). Put
 *   `{ "name": … }` in the token metadata so others see the user's name.
 */
class AppLoomaKit(
    val appId: String,
    val user: KitUser,
    val tokenProvider: suspend (room: String, role: String, user: KitUser) -> KitToken,
)

internal fun formatTime(sec: Long) = "%02d:%02d".format(sec / 60, sec % 60)

// ------------------------------------------------------------------ design system

internal object C {
    val ink = Color(0xFF07070D)
    val glass = Color(0x12FFFFFF)
    val glass2 = Color(0x1FFFFFFF)
    val line = Color(0x17FFFFFF)
    val text = Color(0xFFF5F5FA)
    val muted = Color(0xFF9A9AB0)
    val violet = Color(0xFF7C5CFF)
    val pink = Color(0xFFFF4FA3)
    val live = Color(0xFFFF3B5C)
    val gold = Color(0xFFFFC24B)
    val green = Color(0xFF2EE59D)
    val brand = Brush.linearGradient(listOf(violet, pink))
}

private val gradients = listOf(
    0xFF7C5CFF to 0xFFFF4FA3, 0xFF13B58A to 0xFF0E6F78, 0xFFFF9A3C to 0xFFFF4F7A, 0xFF3C8DFF to 0xFF7C5CFF,
    0xFFFF4F7A to 0xFFFFC24B, 0xFF00C2D1 to 0xFF2EE59D, 0xFFB04BFF to 0xFF5A3CFF, 0xFFFF6B3C to 0xFFFF3B5C,
)

internal fun gradientFor(key: String): Brush {
    var h = 0
    for (c in key) h = (h * 31 + c.code) and 0x7fffffff
    val (a, b) = gradients[h % gradients.size]
    return Brush.linearGradient(listOf(Color(a), Color(b)))
}

@Composable
internal fun Avatar(id: String, name: String, size: Dp = 36.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(gradientFor(id)).border(1.5.dp, Color.White.copy(alpha = 0.18f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.4f).sp) }
}

@Composable
internal fun Glass(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(999.dp)).background(Color.Black.copy(alpha = 0.38f)).border(1.dp, C.line, RoundedCornerShape(999.dp)).padding(padding),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** Round control: soft glass, white when "off", red for end, gradient for brand. */
@Composable
internal fun RoundBtn(
    icon: ImageVector, onClick: () -> Unit, size: Dp = 50.dp, off: Boolean = false, danger: Boolean = false,
    brand: Boolean = false, tint: Color = Color.White, bg: Color = C.glass2, label: String? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val base = Modifier.size(size)
            .then(if (danger) Modifier.shadow(16.dp, CircleShape, ambientColor = C.live, spotColor = C.live) else Modifier)
            .clip(CircleShape)
        val painted = when {
            off -> base.background(Color.White)
            danger -> base.background(C.live)
            brand -> base.background(C.brand)
            else -> base.background(bg)
        }
        Box(painted.clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (off) Color(0xFF111111) else tint, modifier = Modifier.size(size * 0.44f))
        }
        if (label != null) Text(label, color = Color(0xFFCFCFE0), fontSize = 11.5.sp, modifier = Modifier.padding(top = 7.dp))
    }
}

internal data class ChatLine(val who: String, val text: String, val system: Boolean = false, val gift: Boolean = false)

@Composable
internal fun ChatBubble(l: ChatLine) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (l.gift) Modifier.background(Brush.horizontalGradient(listOf(C.gold.copy(alpha = 0.35f), C.pink.copy(alpha = 0.25f))), shape)
    else Modifier.background(Color.Black.copy(alpha = 0.35f), shape)
    Text(
        buildAnnotatedString {
            if (l.who.isNotEmpty()) withStyle(SpanStyle(color = C.gold, fontWeight = FontWeight.Bold)) { append(l.who + "  ") }
            withStyle(SpanStyle(color = if (l.system) C.muted else C.text)) { append(l.text) }
        },
        fontSize = 13.sp, lineHeight = 18.sp,
        modifier = Modifier.padding(bottom = 6.dp).then(bg).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A top/bottom shade over video so white text stays readable. */
@Composable
internal fun Shade(top: Boolean, height: Dp, modifier: Modifier = Modifier) {
    val colors = if (top) listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent) else listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(colors)))
}
