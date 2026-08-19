package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * The dock you are riding *to*, as opposed to the ones you happen to be passing.
 *
 * Arriving at a full dock is the defining Santander Cycles frustration: you cannot end the journey,
 * the clock keeps running, and you find out only once you are standing there. Pinning a destination
 * lets us watch its spaces on your behalf and tell you early enough to divert.
 */
data class Destination(val dockId: Int, val name: String, val position: GeoPoint)

/** How the pinned destination is doing, evaluated fresh on every location or data update. */
enum class DestinationHealth {
    /** Comfortable margin — nothing to say. */
    FINE,

    /** Down to the last couple. Worth a glance and a nudge. */
    TIGHT,

    /** Nothing left. Divert now, while diverting is still cheap. */
    CRITICAL,

    /** We have the dock but no live figures for it. */
    UNKNOWN
}

/** Below this many, a destination is one other rider away from being useless. */
const val TIGHT_THRESHOLD = 3

fun destinationHealth(ranked: RankedDock?): DestinationHealth = when {
    ranked == null -> DestinationHealth.UNKNOWN
    ranked.count == null -> DestinationHealth.UNKNOWN
    ranked.count <= 0 -> DestinationHealth.CRITICAL
    ranked.count < TIGHT_THRESHOLD -> DestinationHealth.TIGHT
    else -> DestinationHealth.FINE
}

/**
 * Whether a health change deserves interrupting the rider.
 *
 * Only escalation buzzes. Improving conditions are good news and good news can wait until they look
 * at the watch — buzzing both ways is how a wrist device teaches people to ignore it.
 */
fun shouldAlert(previous: DestinationHealth?, current: DestinationHealth): Boolean {
    if (previous == null || previous == current) return false
    val severity = mapOf(
        DestinationHealth.UNKNOWN to 0,
        DestinationHealth.FINE to 0,
        DestinationHealth.TIGHT to 1,
        DestinationHealth.CRITICAL to 2
    )
    return (severity[current] ?: 0) > (severity[previous] ?: 0)
}
