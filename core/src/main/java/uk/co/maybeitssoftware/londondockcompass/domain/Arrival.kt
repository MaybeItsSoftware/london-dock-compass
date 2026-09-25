package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * Notices when a trip to the pinned dock is over, so the pin does not outlive it.
 *
 * "Over" is arriving and then leaving: within [ARRIVED_WITHIN_METRES] of the dock, then more than
 * [LEFT_BEYOND_METRES] from it. That covers both ways a trip ends — you dock and walk off, or you
 * take a bike and ride off — without guessing from one reading. Merely arriving is not enough;
 * you may be standing there waiting for a space to free up.
 *
 * Readings less precise than [MAX_ACCURACY_METRES] are ignored in both directions: an approximate
 * fix can put you at the dock and a block away within a minute without you moving at all.
 */
class ArrivalTracker {
    private var dockId: Int? = null
    private var arrived = false

    /** Feeds a reading. Returns true exactly once, when the trip to [targetId] has just finished. */
    fun update(targetId: Int, distanceMetres: Int, accuracyMetres: Float? = null): Boolean {
        if (targetId != dockId) {
            dockId = targetId
            arrived = false
        }
        if (accuracyMetres != null && accuracyMetres > MAX_ACCURACY_METRES) return false

        if (distanceMetres <= ARRIVED_WITHIN_METRES) {
            arrived = true
            return false
        }
        if (arrived && distanceMetres > LEFT_BEYOND_METRES) {
            arrived = false
            return true
        }
        return false
    }

    companion object {
        const val ARRIVED_WITHIN_METRES = 40
        const val LEFT_BEYOND_METRES = 150
        const val MAX_ACCURACY_METRES = 50f
    }
}
