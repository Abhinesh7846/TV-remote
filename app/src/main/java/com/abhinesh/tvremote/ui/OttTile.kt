package com.abhinesh.tvremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinesh.tvremote.core.AppShortcut

private fun Dp.sp(f: Float): TextUnit = (value * f).sp

/**
 * Service mark drawn at [w]: red play badge (YouTube), red N (Netflix), white four-point star
 * (JioHotstar), "prime video" with a smile arc (Prime Video), or initials for custom apps.
 * These are simple drawn stand-ins, not the services' official logo files.
 */
@Composable
fun ServiceMark(s: AppShortcut, w: Dp) {
    when (s.id) {
        "netflix" -> Text("N", color = Color(0xFFE50914), fontSize = w.sp(1.0f), fontWeight = FontWeight.Black,
            fontFamily = AppFonts.manrope, modifier = Modifier.offset(y = -(w * 0.06f)))
        "youtube" -> Box(
            Modifier.size(width = w * 1.25f, height = w * 0.88f).background(Color(0xFFFF0000), RoundedCornerShape(28)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(w * 0.75f), tint = Color.White) }
        "jiohotstar" -> Canvas(Modifier.size(w)) {
            val c = center
            val r = size.minDimension / 2
            val p = Path().apply {
                moveTo(c.x, c.y - r)
                quadraticTo(c.x + r * 0.12f, c.y - r * 0.12f, c.x + r, c.y)
                quadraticTo(c.x + r * 0.12f, c.y + r * 0.12f, c.x, c.y + r)
                quadraticTo(c.x - r * 0.12f, c.y + r * 0.12f, c.x - r, c.y)
                quadraticTo(c.x - r * 0.12f, c.y - r * 0.12f, c.x, c.y - r)
                close()
            }
            drawPath(p, Color.White)
            drawLine(Color.White, Offset(c.x - r * 0.55f, c.y - r * 0.55f), Offset(c.x + r * 0.55f, c.y + r * 0.55f), r * 0.08f, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x + r * 0.55f, c.y - r * 0.55f), Offset(c.x - r * 0.55f, c.y + r * 0.55f), r * 0.08f, StrokeCap.Round)
        }
        "primevideo" -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("prime", color = Color.White, fontSize = w.sp(0.42f), fontWeight = FontWeight.ExtraBold, fontFamily = AppFonts.manrope, lineHeight = w.sp(0.44f))
            Text("video", color = Color.White, fontSize = w.sp(0.42f), fontWeight = FontWeight.ExtraBold, fontFamily = AppFonts.manrope, lineHeight = w.sp(0.44f))
            Canvas(Modifier.size(width = w * 1.0f, height = w * 0.2f)) {
                drawArc(Color(0xFF1FA3E0), 20f, 140f, false, topLeft = Offset(0f, -size.height * 1.4f),
                    size = Size(size.width, size.height * 2.2f), style = Stroke(size.height * 0.28f, cap = StrokeCap.Round))
            }
        }
        "sonyliv" -> Wordmark(w, "SONY" to Color.White, "LIV" to Color(0xFFF5C518), small = 0)
        "zee5" -> Wordmark(w, "ZEE5" to Color(0xFFB45CFF))
        "aha" -> Wordmark(w, "aha" to Color(0xFFFF6A13), scale = 0.62f)
        "etvwin" -> Wordmark(w, "ETV" to Color(0xFFF5B400), "WIN" to Color.White, small = 1)
        "sunnxt" -> Wordmark(w, "SUN" to Color(0xFFFF8A00), "NXT" to Color.White, small = 1)
        "appletv" -> Wordmark(w, "tv" to Color.White, scale = 0.62f)
        "spotify" -> Canvas(Modifier.size(w * 0.95f)) {
            drawCircle(Color(0xFF1DB954))
            val r = size.minDimension / 2
            listOf(0.42f to 0.11f, 0.62f to 0.09f, 0.80f to 0.07f).forEachIndexed { i, (y, t) ->
                drawArc(Color.Black, 200f, 140f, false,
                    topLeft = Offset(center.x - r * (0.62f - i * 0.08f), size.height * y - r * 0.18f),
                    size = Size(r * (1.24f - i * 0.16f), r * 0.55f), style = Stroke(r * t * 1.6f, cap = StrokeCap.Round))
            }
        }
        else -> Text(
            s.label.trim().take(2).ifEmpty { "?" }, color = Color(s.colorArgb).takeIf { it != Color.Black } ?: Ui.text,
            fontSize = w.sp(0.7f), fontWeight = FontWeight.Black, fontFamily = AppFonts.forText(s.label), maxLines = 1,
        )
    }
}

/** Text-only stand-in mark: one or two stacked words; [small] is the index of a smaller line, if any. */
@Composable
private fun Wordmark(w: Dp, vararg lines: Pair<String, Color>, small: Int = -1, scale: Float = 0.46f) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEachIndexed { i, (text, color) ->
            val f = if (lines.size == 1) scale else if (i == small) 0.3f else 0.44f
            Text(text, color = color, fontSize = w.sp(f), fontWeight = FontWeight.Black, fontFamily = AppFonts.manrope,
                lineHeight = w.sp(f * 1.08f), maxLines = 1)
        }
    }
}

/**
 * Charcoal app tile with the service mark and name, as in the Graphite & Copper design.
 * Fires on a normal tap (cancelled by scrolling), unlike the D-pad keys.
 */
@Composable
fun OttTile(s: AppShortcut, busy: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    val haptic = LocalHaptics.current
    val shape = RoundedCornerShape(if (compact) 16.dp else 22.dp)
    BoxWithConstraints(
        modifier.aspectRatio(if (compact) 1f else 1.05f).clip(shape).background(Ui.surface).border(1.dp, Ui.border, shape)
            .clickable(onClickLabel = "Open ${s.label} on the TV") { haptic(false); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val w = maxWidth
        if (busy) CircularProgressIndicator(Modifier.size(w * 0.3f), color = Ui.accent, strokeWidth = 3.dp)
        else Column(Modifier.fillMaxSize().padding(vertical = w * 0.1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ServiceMark(s, w * if (compact) 0.3f else 0.3f) }
            ScriptText(
                s.label, color = Ui.text, fontSize = if (compact) 11.sp else 18.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }
    }
}

/** Kept for callers that show the label outside the tile. */
@Composable
fun OttTileWithLabel(s: AppShortcut, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        OttTile(s, busy, Modifier.fillMaxWidth(), onClick = onClick)
    }
}
