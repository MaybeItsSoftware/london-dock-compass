package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * The three things a dock can have for you: a bike, an e-bike, or a space to end a journey.
 *
 * A dock with nineteen bikes and no spaces is the best dock on the street if you need a bike and
 * the worst one if you are trying to end a journey. Every surface shows all three figures; this is
 * what the phone sorts by, and what the pinned destination is judged on (spaces).
 */
enum class RideMode(
    /** Shown on the sort chip. */
    val label: String,
    /** Names the thing being counted, e.g. "3 bikes". */
    val unit: String
) {
    HIRE(label = "BIKES", unit = "bikes"),
    EBIKE(label = "E-BIKES", unit = "e-bikes"),
    PARK(label = "SPACES", unit = "spaces");

    fun describe(count: Int): String = "$count ${if (count == 1) unit.removeSuffix("s") else unit}"
}
