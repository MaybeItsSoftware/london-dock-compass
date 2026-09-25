package uk.co.maybeitssoftware.londondockcompass.data

import android.util.Log
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.DestinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.Purpose
import uk.co.maybeitssoftware.londondockcompass.domain.healthForCount
import uk.co.maybeitssoftware.londondockcompass.domain.suggestAlternative

/** The pinned dock as it stands, and where to go instead if it is letting you down. */
data class DestinationReport(
    val dock: Dock?,
    val health: DestinationHealth,
    /** Only looked for when [health] is TIGHT or CRITICAL; null otherwise, or if nothing fits. */
    val alternative: Dock?
)

/**
 * Checks on the pinned dock. Shared by both screens and the background session, so the rule for
 * "is it letting you down, and what instead" exists once.
 *
 * Costs one request per check for the dock itself, unless the rider's own sweep already covers it.
 * Looking for an alternative costs one radius query more, and only while the destination is in
 * trouble — and that result is reused for [AREA_REFRESH_MILLIS], because the docks around a
 * destination do not change their minds every twenty seconds.
 */
class DestinationWatcher(private val api: TflBikePointApi) {

    private var area: List<Dock> = emptyList()
    private var areaFor: Pair<Int, Purpose>? = null
    private var areaFetchedAt = 0L

    /**
     * @param nearby the rider's current sweep, if the caller has one — reused rather than refetched.
     * @param known the last copy of the destination dock, kept if the fetch fails.
     */
    suspend fun check(
        destination: Destination,
        rider: GeoPoint?,
        nearby: List<Dock>,
        known: Dock? = null
    ): DestinationReport {
        val dock = nearby.firstOrNull { it.id == destination.dockId }
            ?: runCatching { api.dock(destination.dockId) }
                .onFailure { Log.w(TAG, "Destination refresh failed", it) }
                .getOrNull()
            ?: known
        val health = healthForCount(dock?.availability?.let(destination.purpose::countIn))
        if (health != DestinationHealth.TIGHT && health != DestinationHealth.CRITICAL) {
            return DestinationReport(dock, health, alternative = null)
        }
        val alternative = suggestAlternative(destination, candidates(destination, rider, nearby), rider)
        return DestinationReport(dock, health, alternative)
    }

    /**
     * Docks worth considering instead. A pick-up looks around the rider, and their sweep usually
     * already covers that; a drop-off looks around the destination, which it usually does not.
     */
    private suspend fun candidates(
        destination: Destination,
        rider: GeoPoint?,
        nearby: List<Dock>
    ): List<Dock> {
        if (destination.purpose == Purpose.PICK_UP && nearby.isNotEmpty()) return nearby

        val key = destination.dockId to destination.purpose
        val now = System.currentTimeMillis()
        if (key != areaFor || now - areaFetchedAt > AREA_REFRESH_MILLIS) {
            val centre = when (destination.purpose) {
                Purpose.PICK_UP -> rider ?: destination.position
                Purpose.DROP_OFF -> destination.position
            }
            runCatching { api.docksNear(centre, AREA_RADIUS_METRES) }
                .onSuccess {
                    area = it
                    areaFor = key
                    areaFetchedAt = now
                }
                .onFailure { Log.w(TAG, "Alternative search failed", it) }
        }
        return (area + nearby).distinctBy { it.id }
    }

    private companion object {
        const val TAG = "DestinationWatcher"
        const val AREA_RADIUS_METRES = 600
        const val AREA_REFRESH_MILLIS = 120_000L
    }
}
