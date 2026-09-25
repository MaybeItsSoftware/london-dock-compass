package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockSearchTest {

    private val here = GeoPoint(51.5074, -0.1278)

    private fun dock(id: Int, name: String, metresNorth: Double = 0.0) = Dock(
        id = id,
        name = name,
        position = GeoPoint(here.lat + metresNorth / 111_320.0, here.lon)
    )

    private val docks = listOf(
        dock(1, "River Street , Clerkenwell", metresNorth = 3000.0),
        dock(2, "Craven Street, Strand", metresNorth = 200.0),
        dock(3, "Strand, Covent Garden", metresNorth = 100.0),
        dock(4, "Phillimore Gardens, Kensington", metresNorth = 5000.0)
    )

    @Test
    fun `every word must match, in any order`() {
        assertEquals(listOf(2), searchDocks(docks, "strand craven", here).map { it.id })
    }

    @Test
    fun `stray punctuation in the data does not hide a dock`() {
        assertEquals(listOf(1), searchDocks(docks, "river street, clerkenwell", here).map { it.id })
    }

    @Test
    fun `nearest match leads when the rider has a position`() {
        assertEquals(listOf(3, 2), searchDocks(docks, "STRAND", here).map { it.id })
    }

    @Test
    fun `alphabetical without a position`() {
        assertEquals(listOf(2, 3), searchDocks(docks, "strand", from = null).map { it.id })
    }

    @Test
    fun `a blank query finds nothing rather than everything`() {
        assertTrue(searchDocks(docks, "  ,  ", here).isEmpty())
    }

    @Test
    fun `partial words match, so a name can be typed as far as it takes`() {
        assertEquals(listOf(3, 4), searchDocks(docks, "gard", here).map { it.id })
    }
}
