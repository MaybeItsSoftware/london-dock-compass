package uk.co.maybeitssoftware.londondockcompass.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    // TfL rounds a radius query's centre to three decimals and reports distances from there, so
    // this is its centre point rather than the one we asked with.
    private val charingCross = GeoPoint(51.507, -0.127)
    private val cravenStreet = GeoPoint(51.508103, -0.126021)

    @Test
    fun `distance agrees with the figure TfL reports for the same pair`() {
        // TfL's own radius query returns 140.24m for this pair. Agreeing to within a metre is a
        // real check on the haversine: a wrong earth radius or a degrees-radians slip misses by
        // percentage points, not centimetres.
        assertEquals(140.24, charingCross.distanceTo(cravenStreet), 1.0)
    }

    @Test
    fun `distance to self is zero`() {
        assertEquals(0.0, charingCross.distanceTo(charingCross), 0.0001)
    }

    @Test
    fun `bearing is measured clockwise from north`() {
        val due = GeoPoint(charingCross.lat + 0.01, charingCross.lon)
        assertEquals(0f, charingCross.bearingTo(due), 0.5f)

        val east = GeoPoint(charingCross.lat, charingCross.lon + 0.01)
        assertEquals(90f, charingCross.bearingTo(east), 0.5f)

        val south = GeoPoint(charingCross.lat - 0.01, charingCross.lon)
        assertEquals(180f, charingCross.bearingTo(south), 0.5f)

        val west = GeoPoint(charingCross.lat, charingCross.lon - 0.01)
        assertEquals(270f, charingCross.bearingTo(west), 0.5f)
    }

    @Test
    fun `normalise folds any angle into a single turn`() {
        assertEquals(10f, normaliseDegrees(370f), 0.001f)
        assertEquals(350f, normaliseDegrees(-10f), 0.001f)
        assertEquals(0f, normaliseDegrees(720f), 0.001f)
    }

    @Test
    fun `delta takes the short way round the circle`() {
        assertEquals(2f, angleDelta(359f, 1f), 0.001f)
        assertEquals(-2f, angleDelta(1f, 359f), 0.001f)
        // An exact half turn is a genuine tie; it breaks anticlockwise rather than at random.
        assertEquals(-180f, angleDelta(0f, 180f), 0.001f)
    }

    @Test
    fun `lerp crosses north without spinning the long way`() {
        // The bug this guards: 359 to 1 must travel two degrees, not three hundred and fifty eight.
        val stepped = lerpAngle(current = 359f, target = 1f, factor = 0.5f)
        assertEquals(0f, stepped, 0.001f)
    }

    @Test
    fun `lerp with a factor of one lands on the target`() {
        assertEquals(123f, lerpAngle(10f, 123f, 1f), 0.001f)
    }

    @Test
    fun `headings align within tolerance across the wrap point`() {
        assertTrue(headingsAlign(358f, 2f, tolerance = 5f))
        assertFalse(headingsAlign(358f, 20f, tolerance = 5f))
    }
}
