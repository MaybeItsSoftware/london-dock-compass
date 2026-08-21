package uk.co.maybeitssoftware.londondockcompass.presentation.theme

import uk.co.maybeitssoftware.londondockcompass.domain.TIGHT_THRESHOLD

/**
 * The brand hues as raw ARGB, defined exactly once, plus the one rule that maps a count to one.
 *
 * Compose is not the only thing that draws this app. Tiles are built out of protolayout, which
 * wants an `Int`, so the tile used to carry its own copy of the palette *and* its own copy of the
 * count-to-colour rule — two places to change a threshold, and no way to notice when only one of
 * them moved. Nothing here touches Compose, so every surface can share it.
 *
 * Deliberately no theme flipping: a watch face at night in traffic is not a page, so the paper is
 * true black and stays that way.
 */
object Brand {
    const val RASPBERRY = 0xFFD62246.toInt()
    const val EMERALD = 0xFF4CC38E.toInt()
    const val AMBER = 0xFFFFBF00.toInt()
    const val AZURE = 0xFF007FFF.toInt()
    const val CHALK = 0xFFFAF8F4.toInt()
    const val MUTED = 0xFFB6B3BF.toInt()
    const val DIM = 0xFF6E6B7C.toInt()
    const val INK = 0xFF000000.toInt()
    const val SURFACE = 0xFF1C1A23.toInt()

    /** A well: the mode chip, sunk a half-step below true black so its hairline reads. */
    const val WELL = 0xFF15131A.toInt()

    /**
     * The backdrop behind the actions sheet.
     *
     * Theme-invariant on purpose: it sits over the compass arrow, and what is underneath is not
     * chrome for it to agree with.
     */
    const val SCRIM = 0xE60D0C11.toInt()

    /**
     * The four-way status convention, applied to an availability count.
     *
     * Null is "we do not know", which is not the same as zero and must never be coloured as if it
     * were. Zero is the wall. Below [TIGHT_THRESHOLD] you are one other rider away from the wall.
     */
    fun availabilityColour(count: Int?): Int = when {
        count == null -> MUTED
        count <= 0 -> RASPBERRY
        count < TIGHT_THRESHOLD -> AMBER
        else -> EMERALD
    }
}
