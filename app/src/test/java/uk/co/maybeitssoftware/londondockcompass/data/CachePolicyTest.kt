package uk.co.maybeitssoftware.londondockcompass.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/**
 * The rules that decide whether the app talks to TfL, shows a stale figure, or falls back to
 * coordinates with no counts at all. Every branch of the offline story runs through here.
 */
class CachePolicyTest {

    private val waterloo = GeoPoint(51.5031, -0.1132)
    private val now = 1_700_000_000_000L

    /** Roughly [metres] north of [waterloo]; a degree of latitude is about 111km anywhere. */
    private fun northOfWaterloo(metres: Double) =
        GeoPoint(waterloo.lat + metres / 111_320.0, waterloo.lon)

    @Test
    fun `a snapshot taken here seconds ago is served without a request`() {
        assertTrue(CachePolicy.isFresh(waterloo, now - 5_000, waterloo, now))
    }

    @Test
    fun `a snapshot older than the freshness window is refetched`() {
        assertFalse(CachePolicy.isFresh(waterloo, now - CachePolicy.FRESH_MILLIS, waterloo, now))
    }

    @Test
    fun `riding out of the swept radius refetches even a brand new snapshot`() {
        val moved = northOfWaterloo(CachePolicy.REFETCH_DISTANCE_METRES + 20)
        assertFalse(CachePolicy.isFresh(waterloo, now - 1_000, moved, now))
        assertTrue(CachePolicy.isFresh(waterloo, now - 1_000, northOfWaterloo(50.0), now))
    }

    @Test
    fun `stale counts nearby are still worth showing`() {
        val age = now - (CachePolicy.FRESH_MILLIS + 60_000)
        assertFalse(CachePolicy.isFresh(waterloo, age, waterloo, now))
        assertTrue(CachePolicy.isUsableFallback(waterloo, age, waterloo, now))
    }

    @Test
    fun `counts from a mile away are not`() {
        val far = northOfWaterloo(CachePolicy.STALE_DISTANCE_METRES + 100)
        assertFalse(CachePolicy.isUsableFallback(waterloo, now - 1_000, far, now))
    }

    @Test
    fun `counts older than the usable window are not`() {
        val ancient = now - (CachePolicy.USABLE_MILLIS + 1)
        assertFalse(CachePolicy.isUsableFallback(waterloo, ancient, waterloo, now))
    }

    @Test
    fun `the usable window is inclusive at its edge and the fresh window is not`() {
        // Fresh is a strict comparison, usable is not. Both boundaries are load-bearing: one
        // decides whether we spend a request, the other whether the rider sees a figure at all.
        assertFalse(CachePolicy.isFresh(waterloo, now - CachePolicy.FRESH_MILLIS, waterloo, now))
        assertTrue(
            CachePolicy.isUsableFallback(waterloo, now - CachePolicy.USABLE_MILLIS, waterloo, now)
        )
    }
}
