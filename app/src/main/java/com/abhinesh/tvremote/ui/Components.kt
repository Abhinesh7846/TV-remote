package com.abhinesh.tvremote.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Graphite & Copper palette shared by every screen. */
object Ui {
    val bg = Color(0xFF1B1918)          // graphite
    val surface = Color(0xFF262321)     // charcoal card
    val key = Color(0xFF2E2A28)         // raised button
    val keyPressed = Color(0xFF3A3532)
    val border = Color(0xFF38332F)
    val accent = Color(0xFFC9814F)      // satin copper
    val accentLight = Color(0xFFDE9A68)
    val accentDark = Color(0xFFA25F36)
    val accentText = Color(0xFFE6A97C)
    val onAccent = Color(0xFFFFF4EA)
    val text = Color(0xFFF3EADF)        // warm ivory
    val textDim = Color(0xFFB8ADA2)
    val textMuted = Color(0xFF8C8279)
    val success = Color(0xFF3DD3A8)     // teal connection
    val warning = Color(0xFFF2B65A)
    val danger = Color(0xFFFF8070)
    val outline = Color(0xFF4A433E)

    val copper = androidx.compose.ui.graphics.Brush.linearGradient(listOf(accentLight, accent, accentDark))

    val colors: ColorScheme = darkColorScheme(
        primary = accent, onPrimary = onAccent, background = bg, onBackground = text,
        surface = surface, onSurface = text, surfaceVariant = key, onSurfaceVariant = textDim,
        surfaceContainer = surface, surfaceContainerHigh = key, surfaceContainerHighest = keyPressed,
        surfaceContainerLow = surface, surfaceContainerLowest = bg,
        secondaryContainer = Color(0xFF4A3426), onSecondaryContainer = text,
        error = danger, outline = outline, outlineVariant = border,
    )
}

@Composable
fun TvRemoteTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Ui.colors, typography = AppFonts.typography, content = content)

/** Haptic tick; `true` = long-press strength. Provided by the root so the setting is honoured. */
val LocalHaptics = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun rememberHaptics(enabled: Boolean): (Boolean) -> Unit {
    val view = LocalView.current
    return remember(enabled, view) {
        { long ->
            if (enabled) view.performHapticFeedback(if (long) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }
}

enum class Hold {
    /** Fires once on touch-down. */
    TAP,
    /** Fires on touch-down, then repeats while held (D-pad, volume). */
    REPEAT,
    /** Short tap fires [onPress]; holding 0.5 s sends key-down, and release always sends key-up. */
    LONG,
}

private object Cancelled

/**
 * A remote button with explicit touch handling. Every key-down sent for a long press is
 * paired with a key-up: on release, on the finger sliding off, and when the button leaves
 * the screen (the gesture coroutine's finally block), so keys can't get stuck on the TV.
 */
@Composable
fun RemoteKey(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    hold: Hold = Hold.TAP,
    onLongStart: () -> Unit = {},
    onLongEnd: () -> Unit = {},
    shape: Shape = RoundedCornerShape(18.dp),
    color: Color = Ui.key,
    contentColor: Color = Ui.text,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHaptics.current
    val press by rememberUpdatedState(onPress)
    val longStart by rememberUpdatedState(onLongStart)
    val longEnd by rememberUpdatedState(onLongEnd)
    var pressed by remember { mutableStateOf(false) }
    val bg = when {
        !pressed -> color
        color == Color.Transparent -> Color.White.copy(alpha = 0.10f)
        else -> lerp(color, Color.White, 0.14f)
    }
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .semantics {
                contentDescription = label
                role = Role.Button
                onClick(label) { press(); true }
            }
            .pointerInput(hold) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    haptic(false)
                    try {
                        when (hold) {
                            Hold.TAP -> {
                                press()
                                waitForUpOrCancellation()
                            }
                            Hold.REPEAT -> {
                                press()
                                var wait = 400L
                                while (withTimeoutOrNull(wait) { waitForUpOrCancellation() ?: Cancelled } == null) {
                                    press()
                                    wait = 110L
                                }
                            }
                            Hold.LONG -> {
                                val r = withTimeoutOrNull(500) { waitForUpOrCancellation() ?: Cancelled }
                                if (r == null) {
                                    haptic(true)
                                    longStart()
                                    try { waitForUpOrCancellation() } finally { longEnd() }
                                } else if (r !== Cancelled) {
                                    press()
                                }
                            }
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/**
 * Touch area that turns swipes into D-pad presses (one press per ~56 dp of travel) and a tap
 * into OK. It is not a mouse pointer: the TV only receives arrow and OK keys.
 */
@Composable
fun SwipePad(onDirection: (Int) -> Unit, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHaptics.current
    val step = with(LocalDensity.current) { 56.dp.toPx() }
    val dir by rememberUpdatedState(onDirection)
    val tap by rememberUpdatedState(onTap)
    Box(
        modifier
            .fillMaxWidth()
            .height(250.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Ui.key)
            .semantics {
                contentDescription = "Swipe pad: swipe to move, tap for OK"
                onClick("OK") { tap(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var acc = Offset.Zero
                    var moved = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!moved && ch.uptimeMillis - down.uptimeMillis < 450) { haptic(false); tap() }
                            break
                        }
                        acc += ch.positionChange()
                        ch.consume()
                        while (abs(acc.x) >= step || abs(acc.y) >= step) {
                            moved = true
                            haptic(false)
                            if (abs(acc.x) >= abs(acc.y)) {
                                if (acc.x > 0) { dir(22); acc = acc.copy(x = acc.x - step) } else { dir(21); acc = acc.copy(x = acc.x + step) }
                            } else {
                                if (acc.y > 0) { dir(20); acc = acc.copy(y = acc.y - step) } else { dir(19); acc = acc.copy(y = acc.y + step) }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Swipe to move · tap for OK\nSends D-pad presses, not a mouse pointer",
            color = Ui.textDim, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        )
    }
}
