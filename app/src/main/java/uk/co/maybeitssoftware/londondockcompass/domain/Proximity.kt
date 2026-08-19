package uk.co.maybeitssoftware.londondockcompass.domain

/**
 * Distance bands used to buzz the rider in.
 *
 * You cannot read a watch at fifteen miles an hour in traffic, so the useful signal is the one you
 * feel. Each band fires once as you cross into it and re-arms only if you leave by a clear margin,
 * which stops a dock hovering on a boundary from buzzing your wrist off.
 */
enum class ProximityBand(val enterAtMetres: Int, val leaveAtMetres: Int) {
    APPROACHING(enterAtMetres = 100, leaveAtMetres = 140),
    ARRIVED(enterAtMetres = 25, leaveAtMetres = 45);

    companion object {
        /** The tightest band the rider is inside, or null if none. */
        fun forDistance(metres: Int): ProximityBand? =
            entries.lastOrNull { metres <= it.enterAtMetres }
    }
}

/**
 * Tracks band crossings with hysteresis. Not thread-safe; owned by the UI layer.
 */
class ProximityTracker {
    private var armed: MutableSet<ProximityBand> = ProximityBand.entries.toMutableSet()
    private var currentTargetId: Int? = null

    /**
     * Feeds a new reading and returns the band just entered, if any.
     * Changing target resets everything — a new dock deserves its own approach.
     */
    fun update(targetId: Int, distanceMetres: Int): ProximityBand? {
        if (targetId != currentTargetId) {
            currentTargetId = targetId
            armed = ProximityBand.entries.toMutableSet()
        }

        // Re-arm any band we have clearly left, so riding away and back buzzes again.
        ProximityBand.entries.forEach { band ->
            if (distanceMetres > band.leaveAtMetres) armed.add(band)
        }

        val entered = ProximityBand.entries
            .filter { distanceMetres <= it.enterAtMetres && it in armed }
            .minByOrNull { it.enterAtMetres }
            ?: return null

        // Entering a tight band implies the looser ones; consume them all so we buzz once.
        ProximityBand.entries
            .filter { distanceMetres <= it.enterAtMetres }
            .forEach { armed.remove(it) }
        return entered
    }

    fun reset() {
        armed = ProximityBand.entries.toMutableSet()
        currentTargetId = null
    }
}
