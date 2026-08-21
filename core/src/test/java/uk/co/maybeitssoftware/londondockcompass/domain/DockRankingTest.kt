package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DockRankingTest {

    private val here = GeoPoint(51.5074, -0.1278)

    private fun dockAt(
        id: Int,
        metresNorth: Double,
        bikes: Int = 10,
        eBikes: Int = 2,
        emptyDocks: Int = 10,
        inService: Boolean = true
    ) = Dock(
        id = id,
        name = "Dock $id",
        position = GeoPoint(here.lat + metresNorth / 111_320.0, here.lon),
        inService = inService,
        availability = Availability(
            bikes = bikes,
            eBikes = eBikes,
            standardBikes = bikes - eBikes,
            emptyDocks = emptyDocks,
            totalDocks = bikes + emptyDocks,
            observedAtMillis = 0L
        )
    )

    @Test
    fun `nearest usable dock leads`() {
        val docks = listOf(dockAt(1, 300.0), dockAt(2, 100.0), dockAt(3, 200.0))
        assertEquals(listOf(2, 3, 1), rankDocks(here, docks, RideMode.HIRE).map { it.id })
    }

    @Test
    fun `a full dock is the best dock to hire from and the worst to park at`() {
        // The live case that motivated the mode switch: Craven Street, nineteen bikes, no spaces.
        // Nearest of all, and completely useless if you are trying to end a journey.
        val full = dockAt(1, 100.0, bikes = 19, emptyDocks = 0)
        val roomy = dockAt(2, 400.0, bikes = 0, eBikes = 0, emptyDocks = 12)
        val docks = listOf(full, roomy)

        assertEquals(1, rankDocks(here, docks, RideMode.HIRE).first().id)
        assertEquals(2, rankDocks(here, docks, RideMode.PARK).first().id)
    }

    @Test
    fun `unusable docks sink but are never hidden`() {
        val empty = dockAt(1, 100.0, bikes = 0, eBikes = 0)
        val stocked = dockAt(2, 500.0, bikes = 4)
        val ranked = rankDocks(here, listOf(empty, stocked), RideMode.HIRE)

        assertEquals(listOf(2, 1), ranked.map { it.id })
        assertFalse(ranked.last().isUsable)
    }

    @Test
    fun `out of service docks are dropped outright`() {
        val docks = listOf(dockAt(1, 50.0, inService = false), dockAt(2, 400.0))
        assertEquals(listOf(2), rankDocks(here, docks, RideMode.HIRE).map { it.id })
    }

    @Test
    fun `e-bike mode counts only e-bikes`() {
        val plentyOfStandards = dockAt(1, 100.0, bikes = 12, eBikes = 0)
        val oneElectric = dockAt(2, 400.0, bikes = 1, eBikes = 1)
        val ranked = rankDocks(here, listOf(plentyOfStandards, oneElectric), RideMode.EBIKE)

        assertEquals(2, ranked.first().id)
        assertEquals(1, ranked.first().count)
    }

    @Test
    fun `docks with no live data stay usable because we cannot rule them out`() {
        val unknown = Dock(1, "Unknown", GeoPoint(here.lat + 0.001, here.lon))
        val ranked = rankDocks(here, listOf(unknown), RideMode.PARK).single()

        assertTrue(ranked.isUnknown)
        assertTrue(ranked.isUsable)
        assertNull(ranked.count)
    }

    @Test
    fun `the limit caps the deck`() {
        val docks = (1..20).map { dockAt(it, it * 50.0) }
        assertEquals(8, rankDocks(here, docks, RideMode.HIRE).size)
        assertEquals(3, rankDocks(here, docks, RideMode.HIRE, limit = 3).size)
    }

    @Test
    fun `distance is reported in whole metres from the rider`() {
        val ranked = rankDocks(here, listOf(dockAt(1, 250.0)), RideMode.HIRE).single()
        assertEquals(250.0, ranked.distanceMetres.toDouble(), 2.0)
    }

    @Test
    fun `spaces never come from arithmetic on the bike count`() {
        // TfL counts broken docks in neither figure, so bikes plus spaces need not equal capacity.
        val availability = Availability(
            bikes = 19,
            eBikes = 2,
            standardBikes = 17,
            emptyDocks = 0,
            totalDocks = 23,
            observedAtMillis = 0L
        )
        assertEquals(0, availability.countFor(RideMode.PARK))
    }
}
