package uk.co.maybeitssoftware.londondockcompass.mobile.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import uk.co.maybeitssoftware.londondockcompass.theme.Brand

/**
 * The house palette on a phone.
 *
 * The watch runs true black because an OLED wrist screen at night costs nothing to leave dark. A
 * phone is a page again, so this is the paper-and-hairlines version: warm off-white, a bruised
 * purple ink, no shadows, and separation carried by 1px borders. The accents are the same hex the
 * watch and the tile use, from the same [Brand] object — that is the whole point of it living in
 * core.
 */
private val Chalk = Color(Brand.CHALK)
private val Grape = Color(0xFF444054)
private val Raspberry = Color(Brand.RASPBERRY)
private val Emerald = Color(Brand.EMERALD)
private val Amber = Color(Brand.AMBER)
private val Azure = Color(Brand.AZURE)

private val LightScheme = lightColorScheme(
    primary = Azure,
    onPrimary = Color.White,
    secondary = Grape,
    onSecondary = Color.White,
    background = Chalk,
    onBackground = Grape,
    // Cards sit half a step above the paper; that plus a hairline is the whole depth model.
    surface = Color.White,
    onSurface = Grape,
    surfaceVariant = Color(0xFFEDEBEF),
    onSurfaceVariant = Color(0xFF6E6B7C),
    outline = Color(0xFFD8D5DD),
    outlineVariant = Color(0xFFE6E4EA),
    error = Raspberry,
    onError = Color.White
)

private val DarkScheme = darkColorScheme(
    primary = Azure,
    onPrimary = Color.White,
    secondary = Color(0xFFB6B3BF),
    onSecondary = Color(0xFF1C1A23),
    // The same grape hue pulled down, never a neutral grey.
    background = Color(0xFF1C1A23),
    onBackground = Color(0xFFF5F4F7),
    surface = Color(0xFF25232F),
    onSurface = Color(0xFFF5F4F7),
    surfaceVariant = Color(0xFF2D2B38),
    onSurfaceVariant = Color(0xFFB6B3BF),
    outline = Color(0xFF34313F),
    outlineVariant = Color(0xFF2D2B38),
    error = Raspberry,
    onError = Color.White
)

/** Status colours, keyed the same four ways as everywhere else. */
object Status {
    val Emerald = uk.co.maybeitssoftware.londondockcompass.mobile.theme.Emerald
    val Amber = uk.co.maybeitssoftware.londondockcompass.mobile.theme.Amber
    val Raspberry = uk.co.maybeitssoftware.londondockcompass.mobile.theme.Raspberry
    val Azure = uk.co.maybeitssoftware.londondockcompass.mobile.theme.Azure

    /** One rule for count-to-colour, shared with the watch and the tile via [Brand]. */
    fun forCount(count: Int?): Color = Color(Brand.availabilityColour(count))
}

/**
 * The signature micro-label: 10px, bold, upper case, widely tracked.
 *
 * Hierarchy comes from surface and position rather than from label size, which is what keeps a
 * dense list of docks readable without a single heading getting large.
 */
val MicroLabel = TextStyle(
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.15.em
)

@Composable
fun LondonDockCompassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = Typography(),
        content = content
    )
}
