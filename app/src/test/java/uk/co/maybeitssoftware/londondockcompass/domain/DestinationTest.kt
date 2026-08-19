package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationTest {

    private fun ranked(count: Int?) = RankedDock(
        dock = Dock(1, "Somewhere", GeoPoint(51.5, -0.1)),
        distanceMetres = 400,
        bearingDegrees = 0f,
        count = count
    )

    @Test
    fun `plenty of spaces is nothing to report`() {
        assertEquals(DestinationHealth.FINE, destinationHealth(ranked(9)))
    }

    @Test
    fun `down to the last couple is worth a nudge`() {
        assertEquals(DestinationHealth.TIGHT, destinationHealth(ranked(2)))
        assertEquals(DestinationHealth.TIGHT, destinationHealth(ranked(1)))
    }

    @Test
    fun `nothing left is critical`() {
        assertEquals(DestinationHealth.CRITICAL, destinationHealth(ranked(0)))
    }

    @Test
    fun `no figures means unknown, not fine`() {
        assertEquals(DestinationHealth.UNKNOWN, destinationHealth(ranked(null)))
        assertEquals(DestinationHealth.UNKNOWN, destinationHealth(null))
    }

    @Test
    fun `only worsening conditions interrupt the rider`() {
        assertTrue(shouldAlert(DestinationHealth.FINE, DestinationHealth.TIGHT))
        assertTrue(shouldAlert(DestinationHealth.TIGHT, DestinationHealth.CRITICAL))
        assertTrue(shouldAlert(DestinationHealth.FINE, DestinationHealth.CRITICAL))
    }

    @Test
    fun `good news waits until they look at the watch`() {
        assertFalse(shouldAlert(DestinationHealth.CRITICAL, DestinationHealth.FINE))
        assertFalse(shouldAlert(DestinationHealth.TIGHT, DestinationHealth.FINE))
    }

    @Test
    fun `an unchanged state never buzzes twice`() {
        assertFalse(shouldAlert(DestinationHealth.CRITICAL, DestinationHealth.CRITICAL))
        assertFalse(shouldAlert(DestinationHealth.FINE, DestinationHealth.FINE))
    }

    @Test
    fun `the first reading is not an escalation`() {
        // Pinning a dock that is already full should not buzz before you have even set off.
        assertFalse(shouldAlert(null, DestinationHealth.CRITICAL))
    }

    @Test
    fun `losing live data is not treated as trouble`() {
        assertFalse(shouldAlert(DestinationHealth.FINE, DestinationHealth.UNKNOWN))
    }
}
