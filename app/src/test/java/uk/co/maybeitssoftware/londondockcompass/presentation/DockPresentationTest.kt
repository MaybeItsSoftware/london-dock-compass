package uk.co.maybeitssoftware.londondockcompass.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode

class DockPresentationTest {

    private fun ranked(count: Int?, metres: Int = 140) = RankedDock(
        dock = Dock(
            id = 341,
            name = "Craven Street, Strand",
            position = GeoPoint(51.508103, -0.126021),
            availability = count?.let { Availability(it, 2, it - 2, it, 23, 0L) }
        ),
        distanceMetres = metres,
        bearingDegrees = 0f,
        count = count
    )

    @Test
    fun `metres up close, kilometres once precision stops mattering`() {
        assertEquals("140m", formatDistance(140))
        assertEquals("999m", formatDistance(999))
        assertEquals("1.0km", formatDistance(1000))
        assertEquals("2.4km", formatDistance(2350))
    }

    @Test
    fun `directions are spoken relative to the way the rider is facing`() {
        assertEquals("straight ahead", 0f.asSpokenDirection())
        assertEquals("to your right", 90f.asSpokenDirection())
        assertEquals("behind you", 180f.asSpokenDirection())
        assertEquals("to your left", 270f.asSpokenDirection())
        assertEquals("ahead and to your right", 45f.asSpokenDirection())
    }

    @Test
    fun `spoken directions survive negative and oversized angles`() {
        // Relative bearing is bearing minus heading, so it arrives unnormalised.
        assertEquals("to your left", (-90f).asSpokenDirection())
        assertEquals("straight ahead", 360f.asSpokenDirection())
        assertEquals("behind you", (-180f).asSpokenDirection())
    }

    @Test
    fun `a screen reader gets the whole card, not just the arrow`() {
        assertEquals(
            "Craven Street, Strand, 140m to your right, 19 bikes",
            ranked(19).describe(RideMode.HIRE, relativeBearing = 90f)
        )
    }

    @Test
    fun `an exhausted dock says so out loud`() {
        assertEquals(
            "Craven Street, Strand, 140m straight ahead, full",
            ranked(0).describe(RideMode.PARK, relativeBearing = 0f)
        )
    }

    @Test
    fun `missing figures are announced as missing`() {
        assertEquals(
            "Craven Street, Strand, 140m behind you, availability unknown",
            ranked(null).describe(RideMode.HIRE, relativeBearing = 180f)
        )
    }
}
