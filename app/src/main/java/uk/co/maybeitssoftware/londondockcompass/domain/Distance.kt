package uk.co.maybeitssoftware.londondockcompass.domain

import kotlin.math.roundToInt

/**
 * Metres up close, where precision matters; kilometres once it stops mattering.
 *
 * Shared by the screen, the tile and the complication. The complication used to format its own with
 * `String.format(Locale.getDefault(), "%.1fkm", ...)`, so the same distance read "1.3km" on the
 * watch face and "1,3km" one swipe away for anyone not on an English locale.
 */
fun formatDistance(metres: Int): String =
    if (metres < 1000) "${metres}m" else "${(metres / 100f).roundToInt() / 10f}km"
