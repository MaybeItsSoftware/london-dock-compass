package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProximityTrackerTest {

    private val tracker = ProximityTracker()

    @Test
    fun `far away is silent`() {
        assertNull(tracker.update(targetId = 1, distanceMetres = 800))
        assertNull(tracker.update(targetId = 1, distanceMetres = 300))
    }

    @Test
    fun `each band fires once as it is crossed`() {
        assertNull(tracker.update(1, 300))
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
        assertNull(tracker.update(1, 80))
        assertEquals(ProximityBand.ARRIVED, tracker.update(1, 20))
        assertNull(tracker.update(1, 15))
    }

    @Test
    fun `hovering on a boundary does not buzz repeatedly`() {
        // The failure this prevents: waiting at a light 100m out, wrist buzzing every second as
        // GPS noise walks the reading back and forth across the threshold.
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 99))
        assertNull(tracker.update(1, 101))
        assertNull(tracker.update(1, 98))
        assertNull(tracker.update(1, 102))
        assertNull(tracker.update(1, 97))
    }

    @Test
    fun `leaving by a clear margin re-arms the band`() {
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
        assertNull(tracker.update(1, 120)) // still inside the hysteresis gap
        assertNull(tracker.update(1, 150)) // clearly left; the band re-arms
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
    }

    @Test
    fun `arriving straight from far away buzzes only for the tightest band`() {
        // A fix that jumps from 400m to 10m should feel like arrival, not two alerts at once.
        assertNull(tracker.update(1, 400))
        assertEquals(ProximityBand.ARRIVED, tracker.update(1, 10))
        assertNull(tracker.update(1, 8))
    }

    @Test
    fun `a new target starts its own approach`() {
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
        assertNull(tracker.update(1, 85))
        assertEquals(ProximityBand.APPROACHING, tracker.update(targetId = 2, distanceMetres = 85))
    }

    @Test
    fun `reset clears the history`() {
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
        tracker.reset()
        assertEquals(ProximityBand.APPROACHING, tracker.update(1, 90))
    }

    @Test
    fun `band lookup picks the tightest one containing the distance`() {
        assertNull(ProximityBand.forDistance(500))
        assertEquals(ProximityBand.APPROACHING, ProximityBand.forDistance(80))
        assertEquals(ProximityBand.ARRIVED, ProximityBand.forDistance(10))
    }
}
