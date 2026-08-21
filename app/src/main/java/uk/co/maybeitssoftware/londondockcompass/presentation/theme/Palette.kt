package uk.co.maybeitssoftware.londondockcompass.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import uk.co.maybeitssoftware.londondockcompass.theme.Brand
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
    val Raspberry = Color(Brand.RASPBERRY)
    val Emerald = Color(Brand.EMERALD)
    val Amber = Color(Brand.AMBER)
    val Azure = Color(Brand.AZURE)
    val Chalk = Color(Brand.CHALK)
    val Muted = Color(Brand.MUTED)
    val Dim = Color(Brand.DIM)
    val Ink = Color(Brand.INK)
    val Surface = Color(Brand.SURFACE)
    val Scrim = Color(Brand.SCRIM)
    val Well = Color(Brand.WELL)
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
            surface = Palette.Surface,
            onSurface = Palette.Chalk,
            error = Palette.Raspberry
        ),
        content = content
    )
}
