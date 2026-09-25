package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * The dock you are heading *to*, as opposed to the ones you happen to be passing.
 *
 * Arriving at a full dock is the defining Santander Cycles frustration: you cannot end the journey,
 * the clock keeps running, and you find out only once you are standing there. Walking ten minutes
 * to a dock that has just been emptied is its mirror image. Pinning a destination lets us watch the
 * figure that matters on your behalf and tell you early enough to go somewhere else.
 */
data class Destination(
    val dockId: Int,
    val name: String,
    val position: GeoPoint,
    val purpose: Purpose = Purpose.DROP_OFF
)

/**
 * Why you are heading to a dock, which decides which of its figures can let you down.
 *
 * DROP_OFF is the default because it is what every pin meant before purposes existed, and an
 * older build syncing a pin across must not quietly turn a ride into a walk.
 */
enum class Purpose(
    /** Which count is watched: bikes for a pick-up, spaces for a drop-off. */
    val mode: RideMode,
    /** What the dock is called when that count hits zero. */
    val exhaustedLabel: String,
    /** What it is called when it is down to the last couple. */
    val lowLabel: String,
    /** The action that pins with this purpose. */
    val action: String
) {
    /** On foot, heading to a dock to take a bike. */
    PICK_UP(RideMode.HIRE, exhaustedLabel = "EMPTY", lowLabel = "RUNNING LOW", action = "Get a bike here"),

    /** Riding, heading to a dock to end the journey. */
    DROP_OFF(RideMode.PARK, exhaustedLabel = "FULL", lowLabel = "FILLING", action = "Park here");

    fun countIn(availability: Availability): Int = availability.countFor(mode)
}

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

fun destinationHealth(ranked: RankedDock?): DestinationHealth = healthForCount(ranked?.count)

fun healthForCount(count: Int?): DestinationHealth = when {
    count == null -> DestinationHealth.UNKNOWN
    count <= 0 -> DestinationHealth.CRITICAL
    count < TIGHT_THRESHOLD -> DestinationHealth.TIGHT
    else -> DestinationHealth.FINE
}

/**
 * Where to go instead when the destination is letting you down.
 *
 * The anchor depends on why you were going. Parking, you still want to end up near where you were
 * riding to, so the replacement is the nearest comfortable dock to the *destination*. Picking up,
 * you are on foot and the destination was only ever a place to get a bike, so the replacement is
 * the nearest dock with bikes to *you* — walking past the empty dock to one beyond it helps nobody.
 *
 * "Comfortable" means at least [TIGHT_THRESHOLD], so we never divert you to a dock that is about
 * to fail you the same way.
 */
fun suggestAlternative(
    destination: Destination,
    candidates: List<Dock>,
    rider: GeoPoint?
): Dock? {
    val anchor = when (destination.purpose) {
        Purpose.PICK_UP -> rider ?: destination.position
        Purpose.DROP_OFF -> destination.position
    }
    return candidates
        .asSequence()
        .filter { it.inService && it.id != destination.dockId }
        .filter { dock ->
            val count = dock.availability?.let(destination.purpose::countIn) ?: return@filter false
            count >= TIGHT_THRESHOLD
        }
        .minByOrNull { anchor.distanceTo(it.position) }
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
