package uk.co.maybeitssoftware.londondockcompass.data

import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.distanceTo

/**
 * Whether a snapshot we are already holding can answer for where the rider is now.
 *
 * Lifted out of [DockRepository] so it can be exercised without a Context. The decisions are the
 * whole reason the app keeps pointing somewhere sensible in a tunnel or against a rate limit, and
 * they were the least tested code in the project — two time thresholds and two distance ones,
 * with strict and non-strict comparisons that are easy to get subtly wrong and impossible to
 * notice from the outside.
 */
internal object CachePolicy {

    /** Inside this, a snapshot is as good as a new request and we do not make one. */
    const val FRESH_MILLIS = 30_000L

    /** Past this, counts are too old to show even as a fallback. */
    const val USABLE_MILLIS = 10 * 60_000L

    /** Ride further than this from where a snapshot was taken and it names the wrong docks. */
    const val REFETCH_DISTANCE_METRES = 150.0

    /** Stale counts are still worth showing; counts from a mile away are not. */
    const val STALE_DISTANCE_METRES = 600.0

    /** Good enough to serve as-is, without going to the network at all. */
    fun isFresh(origin: GeoPoint, fetchedAtMillis: Long, at: GeoPoint, now: Long): Boolean =
        now - fetchedAtMillis < FRESH_MILLIS && origin.distanceTo(at) < REFETCH_DISTANCE_METRES

    /** Not fresh, but better than the bundled coordinates when the network has failed us. */
    fun isUsableFallback(origin: GeoPoint, fetchedAtMillis: Long, at: GeoPoint, now: Long): Boolean =
        now - fetchedAtMillis <= USABLE_MILLIS && origin.distanceTo(at) <= STALE_DISTANCE_METRES
}
