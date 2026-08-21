package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RideModeTest {

    @Test
    fun `tapping the chip walks the whole ring and comes back`() {
        var mode = RideMode.HIRE
        repeat(RideMode.entries.size) { mode = mode.next() }
        assertEquals(RideMode.HIRE, mode)
    }

    @Test
    fun `counts are described in the rider's terms`() {
        assertEquals("3 bikes", RideMode.HIRE.describe(3))
        assertEquals("1 bike", RideMode.HIRE.describe(1))
        assertEquals("1 e-bike", RideMode.EBIKE.describe(1))
        assertEquals("4 spaces", RideMode.PARK.describe(4))
        assertEquals("1 space", RideMode.PARK.describe(1))
    }

    @Test
    fun `a dock with none left reads differently depending on what you wanted`() {
        assertEquals("EMPTY", RideMode.HIRE.exhaustedLabel)
        assertEquals("EMPTY", RideMode.EBIKE.exhaustedLabel)
        assertEquals("FULL", RideMode.PARK.exhaustedLabel)
    }

    @Test
    fun `availability answers whichever question is being asked`() {
        val availability = Availability(
            bikes = 19,
            eBikes = 2,
            standardBikes = 17,
            emptyDocks = 4,
            totalDocks = 23,
            observedAtMillis = 0L
        )
        assertEquals(19, availability.countFor(RideMode.HIRE))
        assertEquals(2, availability.countFor(RideMode.EBIKE))
        assertEquals(4, availability.countFor(RideMode.PARK))
    }

    @Test
    fun `staleness is measured against when TfL observed it, not when we asked`() {
        val observed = 1_000_000L
        val availability = Availability(1, 0, 1, 1, 2, observed)
        assertEquals(false, availability.isStaleAt(observed + STALE_AFTER_MILLIS - 1))
        assertEquals(true, availability.isStaleAt(observed + STALE_AFTER_MILLIS + 1))
    }
}
