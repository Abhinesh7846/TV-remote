package com.abhinesh.tvremote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abhinesh.tvremote.R

/** Manrope for Latin text, Noto Sans Telugu and Noto Sans Devanagari for Telugu and Hindi (variable fonts, OFL). */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
object AppFonts {
    private fun family(res: Int) = FontFamily(
        listOf(400, 500, 600, 700, 800).map { w ->
            Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
        },
    )

    val manrope = family(R.font.manrope)
    val telugu = family(R.font.noto_sans_telugu)
    val devanagari = family(R.font.noto_sans_devanagari)

    /** Picks the family for a string's script, so Telugu and Hindi render in their Noto fonts. */
    fun forText(text: String): FontFamily = when {
        text.any { it in 'ఀ'..'౿' } -> telugu
        text.any { it in 'ऀ'..'ॿ' } -> devanagari
        else -> manrope
    }

    private val base = Typography()
    val typography = Typography(
        headlineLarge = base.headlineLarge.copy(fontFamily = manrope, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = manrope, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = manrope, fontWeight = FontWeight.Bold, fontSize = 26.sp),
        titleLarge = base.titleLarge.copy(fontFamily = manrope, fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontFamily = manrope, fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.copy(fontFamily = manrope, fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(fontFamily = manrope),
        bodyMedium = base.bodyMedium.copy(fontFamily = manrope),
        bodySmall = base.bodySmall.copy(fontFamily = manrope, fontSize = 12.5.sp),
        labelLarge = base.labelLarge.copy(fontFamily = manrope, fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontFamily = manrope, fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(fontFamily = manrope, fontWeight = FontWeight.SemiBold),
        displaySmall = base.displaySmall.copy(fontFamily = manrope),
    )
}

/** Text that switches to the Telugu or Devanagari font when the string contains that script. */
@Composable
fun ScriptText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Ui.text,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight, textAlign = textAlign,
    maxLines = maxLines, overflow = TextOverflow.Ellipsis, style = scriptStyle(style, text),
)

/** Telugu and Devanagari glyphs are taller than Latin ones: let them use their natural line height so they sit centred. */
fun scriptStyle(style: TextStyle, text: String): TextStyle {
    val family = AppFonts.forText(text)
    return if (family == AppFonts.manrope) style.copy(fontFamily = family) else style.copy(fontFamily = family, lineHeight = TextUnit.Unspecified)
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ui.text)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Ui.textDim) }
    }
}

/** Charcoal card with a hairline border. */
@Composable
fun GCard(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Ui.surface)
            .border(1.dp, Ui.border, RoundedCornerShape(22.dp)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Satin copper filled button. */
@Composable
fun CopperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 60.dp,
) {
    Row(
        modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(20.dp))
            .background(Ui.copper).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = Ui.onAccent, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)) }
        Text(text, color = Ui.onAccent, style = MaterialTheme.typography.titleMedium, fontSize = 19.sp)
    }
}

/** Outlined companion to [CopperButton]. */
@Composable
fun OutlineCopperButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    Row(
        modifier.fillMaxWidth().height(height).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(1.2.dp, Ui.text.copy(alpha = 0.55f)), RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = Ui.text, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)) }
        Text(text, color = Ui.text, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
    }
}

/** Language / filter pill: copper when selected, outlined otherwise. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.background(Ui.copper) else Modifier.border(1.dp, Ui.outline, RoundedCornerShape(24.dp)).background(Ui.surface))
            .clickable(onClick = onClick).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Noto Telugu/Devanagari reserve extra space below the letters; nudge them down to look centred.
        val nudge = if (AppFonts.forText(text) == AppFonts.manrope) 0.dp else 3.dp
        ScriptText(text, Modifier.offset(y = nudge), color = if (selected) Ui.onAccent else Ui.text, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp)
    }
}

/** "● Connected" style status line. */
@Composable
fun StatusDot(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}
