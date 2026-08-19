package uk.co.maybeitssoftware.londondockcompass.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.MaterialTheme

/**
 * The house palette, cut down to what survives on a watch.
 *
 * A wrist screen at night in traffic is not a page: the paper is true black, because OLED black
 * costs no battery and everything else reads brighter against it. The accents are unchanged from
 * the brand, and status keeps the same four-way convention as everywhere else.
 */
object Palette {
    val Raspberry = Color(0xFFD62246)
    val Emerald = Color(0xFF4CC38E)
    val Amber = Color(0xFFFFBF00)
    val Azure = Color(0xFF007FFF)
    val Chalk = Color(0xFFFAF8F4)
    val Muted = Color(0xFFB6B3BF)
    val Dim = Color(0xFF6E6B7C)
    val Ink = Color.Black
}

/**
 * The signature micro-label: tiny, bold, upper case, widely tracked. Hierarchy comes from position
 * and colour rather than size, which is the only way to keep a 1.2 inch screen legible.
 */
val MicroLabel = TextStyle(
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.15.em
)

@Composable
fun LondonDockCompassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            background = Palette.Ink,
            onBackground = Palette.Chalk,
            primary = Palette.Raspberry,
            onPrimary = Palette.Chalk,
            surface = Color(0xFF1C1A23),
            onSurface = Palette.Chalk,
            error = Palette.Raspberry
        ),
        content = content
    )
}
