package uk.co.maybeitssoftware.londondockcompass.domain

import kotlin.math.roundToInt

/** A dock placed relative to the rider, and judged against what the rider is trying to do. */
data class RankedDock(
    val dock: Dock,
    val distanceMetres: Int,
    /** Degrees clockwise from true north. */
    val bearingDegrees: Float,
    /** How many of the thing the rider wants — null when availability is unknown. */
    val count: Int?
) {
    val id: Int get() = dock.id
    val name: String get() = dock.name

    /** Unknown counts as usable: offline we cannot rule a dock out, so we must not hide it. */
    val isUsable: Boolean get() = count == null || count > 0

    val isUnknown: Boolean get() = count == null
}

/**
 * Orders docks for a rider standing at [from].
 *
 * Usable docks come first, nearest first; the rest follow, also nearest first, so the list never
 * empties out and a full dock stays visible as the last resort it is. Out-of-service docks are
 * dropped outright — no ranking makes a locked dock worth cycling to.
 */
fun rankDocks(
    from: GeoPoint,
    docks: List<Dock>,
    mode: RideMode,
    limit: Int = 8
): List<RankedDock> =
    docks.asSequence()
        .filter { it.inService }
        .map { dock ->
            RankedDock(
                dock = dock,
                distanceMetres = from.distanceTo(dock.position).roundToInt(),
                bearingDegrees = from.bearingTo(dock.position),
                count = dock.availability?.countFor(mode)
            )
        }
        .sortedWith(compareBy({ !it.isUsable }, { it.distanceMetres }))
        .take(limit)
        .toList()
