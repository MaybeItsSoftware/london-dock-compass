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

    private fun stocked(id: Int, metresNorth: Double, bikes: Int, spaces: Int, inService: Boolean = true) = Dock(
        id = id,
        name = "Dock $id",
        position = GeoPoint(51.5 + metresNorth / 111_320.0, -0.1),
        inService = inService,
        availability = Availability(bikes, 0, bikes, spaces, bikes + spaces, 0L)
    )

    @Test
    fun `a pick-up watches bikes and a drop-off watches spaces`() {
        val availability = Availability(bikes = 0, eBikes = 0, standardBikes = 0, emptyDocks = 20, totalDocks = 20, observedAtMillis = 0L)
        assertEquals(0, Purpose.PICK_UP.countIn(availability))
        assertEquals(20, Purpose.DROP_OFF.countIn(availability))
    }

    @Test
    fun `parking diverts to the dock nearest where you were going`() {
        val destination = Destination(1, "Full", GeoPoint(51.5, -0.1), Purpose.DROP_OFF)
        val nearDestination = stocked(2, 150.0, bikes = 0, spaces = 8)
        val nearRider = stocked(3, 900.0, bikes = 0, spaces = 8)
        val rider = GeoPoint(51.5 + 1000 / 111_320.0, -0.1)

        assertEquals(2, suggestAlternative(destination, listOf(nearRider, nearDestination), rider)?.id)
    }

    @Test
    fun `picking up diverts to the dock nearest you, since you are walking`() {
        val destination = Destination(1, "Empty", GeoPoint(51.5, -0.1), Purpose.PICK_UP)
        val nearDestination = stocked(2, 150.0, bikes = 8, spaces = 0)
        val nearRider = stocked(3, 900.0, bikes = 8, spaces = 0)
        val rider = GeoPoint(51.5 + 1000 / 111_320.0, -0.1)

        assertEquals(3, suggestAlternative(destination, listOf(nearDestination, nearRider), rider)?.id)
    }

    @Test
    fun `never divert to a dock about to fail the same way, or a locked one`() {
        val destination = Destination(1, "Full", GeoPoint(51.5, -0.1), Purpose.DROP_OFF)
        val almostFull = stocked(2, 50.0, bikes = 10, spaces = 1)
        val locked = stocked(3, 60.0, bikes = 0, spaces = 10, inService = false)
        val itself = stocked(1, 0.0, bikes = 0, spaces = 10)

        assertEquals(null, suggestAlternative(destination, listOf(almostFull, locked, itself), null))
    }
}
