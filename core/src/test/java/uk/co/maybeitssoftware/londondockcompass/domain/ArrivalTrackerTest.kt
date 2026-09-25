package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalTrackerTest {

    @Test
    fun `arriving then leaving ends the trip, once`() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(1, 300))
        assertFalse(tracker.update(1, 20))
        assertFalse(tracker.update(1, 90))
        assertTrue(tracker.update(1, 200))
        assertFalse(tracker.update(1, 400))
    }

    @Test
    fun `waiting at the dock is not leaving it`() {
        val tracker = ArrivalTracker()
        tracker.update(1, 10)
        repeat(20) { assertFalse(tracker.update(1, 35)) }
    }

    @Test
    fun `riding away without ever arriving keeps the pin`() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(1, 120))
        assertFalse(tracker.update(1, 900))
    }

    @Test
    fun `a vague fix counts for nothing either way`() {
        val tracker = ArrivalTracker()
        assertFalse(tracker.update(1, 10, accuracyMetres = 500f))
        assertFalse(tracker.update(1, 600, accuracyMetres = 5f))
    }

    @Test
    fun `a new destination starts from scratch`() {
        val tracker = ArrivalTracker()
        tracker.update(1, 10)
        assertFalse(tracker.update(2, 600))
    }
}
