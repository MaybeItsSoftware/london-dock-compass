package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * A docking station.
 *
 * [availability] is null when all we have is the bundled coordinate list — offline, we know where
 * the dock is but not what is in it, and the UI has to say so rather than imply zero.
 */
data class Dock(
    val id: Int,
    val name: String,
    val position: GeoPoint,
    val inService: Boolean = true,
    val availability: Availability? = null
)

/**
 * A live snapshot of what is in a dock.
 *
 * TfL reports bikes and empty docks separately, and the two do not have to add up to [totalDocks] —
 * broken docks are counted in neither. Always take spaces from [emptyDocks], never from arithmetic.
 */
data class Availability(
    val bikes: Int,
    val eBikes: Int,
    val standardBikes: Int,
    val emptyDocks: Int,
    val totalDocks: Int,
    /** When TfL last observed this, epoch millis. Drives the staleness warning. */
    val observedAtMillis: Long
) {
    fun countFor(mode: RideMode): Int = when (mode) {
        RideMode.HIRE -> bikes
        RideMode.EBIKE -> eBikes
        RideMode.PARK -> emptyDocks
    }
}

/** How stale a snapshot is allowed to get before we stop trusting it out loud. */
const val STALE_AFTER_MILLIS = 5 * 60 * 1000L

fun Availability.isStaleAt(nowMillis: Long): Boolean =
    nowMillis - observedAtMillis > STALE_AFTER_MILLIS
