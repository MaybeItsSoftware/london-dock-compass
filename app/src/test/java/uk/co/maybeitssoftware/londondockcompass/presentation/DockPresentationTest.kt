package uk.co.maybeitssoftware.londondockcompass.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.formatDistance
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock

class DockPresentationTest {

    private fun ranked(availability: Availability?, metres: Int = 140) = RankedDock(
        dock = Dock(
            id = 341,
            name = "Craven Street, Strand",
            position = GeoPoint(51.508103, -0.126021),
            availability = availability
        ),
        distanceMetres = metres,
        bearingDegrees = 0f,
        count = null
    )

    private fun counts(bikes: Int, eBikes: Int, spaces: Int) =
        Availability(bikes, eBikes, bikes - eBikes, spaces, 23, 0L)

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
            "Craven Street, Strand, 140m to your right, 19 bikes, 2 e-bikes, 4 spaces",
            ranked(counts(19, 2, 4)).describe(relativeBearing = 90f)
        )
    }

    @Test
    fun `single figures are spoken in the singular`() {
        assertEquals(
            "Craven Street, Strand, 140m straight ahead, 1 bike, 0 e-bikes, 1 space",
            ranked(counts(1, 0, 1)).describe(relativeBearing = 0f)
        )
    }

    @Test
    fun `missing figures are announced as missing`() {
        assertEquals(
            "Craven Street, Strand, 140m behind you, availability unknown",
            ranked(null).describe(relativeBearing = 180f)
        )
    }
}
