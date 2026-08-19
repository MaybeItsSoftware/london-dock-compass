package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * What the rider is trying to do right now.
 *
 * This is the whole point of the app: a dock with nineteen bikes and no spaces is the best dock on
 * the street if you need a bike and the worst one if you are trying to end a journey. One toggle,
 * and every ranking below flips with it.
 */
enum class RideMode(
    /** Shown in the mode chip. */
    val label: String,
    /** Names the thing being counted, e.g. "3 bikes". */
    val unit: String,
    /** Said out loud when nothing nearby has any. */
    val emptyMessage: String
) {
    HIRE(label = "BIKES", unit = "bikes", emptyMessage = "No bikes nearby"),
    EBIKE(label = "E-BIKES", unit = "e-bikes", emptyMessage = "No e-bikes nearby"),
    PARK(label = "SPACES", unit = "spaces", emptyMessage = "No spaces nearby");

    /** What a dock with none left is, in the rider's terms. */
    val exhaustedLabel: String get() = if (this == PARK) "FULL" else "EMPTY"

    /** Tapping the chip walks round the ring. */
    fun next(): RideMode = entries[(ordinal + 1) % entries.size]

    fun describe(count: Int): String = "$count ${if (count == 1) unit.removeSuffix("s") else unit}"
}
