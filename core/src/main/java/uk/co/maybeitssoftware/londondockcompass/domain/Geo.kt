package uk.co.maybeitssoftware.londondockcompass.domain

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on the globe.
 *
 * Deliberately free of [android.location.Location] so every piece of geometry in this app can be
 * exercised by plain JVM unit tests — the framework class is all native stubs off-device.
 */
data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_METRES = 6_371_008.8

private fun Double.toRad() = this * (Math.PI / 180.0)
private fun Double.toDeg() = this * (180.0 / Math.PI)

/** Great-circle distance in metres. Accurate to well under a metre at London scale. */
fun GeoPoint.distanceTo(other: GeoPoint): Double {
    val dLat = (other.lat - lat).toRad()
    val dLon = (other.lon - lon).toRad()
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat.toRad()) * cos(other.lat.toRad()) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METRES * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial great-circle bearing to [other], in degrees clockwise from **true** north. */
fun GeoPoint.bearingTo(other: GeoPoint): Float {
    val dLon = (other.lon - lon).toRad()
    val y = sin(dLon) * cos(other.lat.toRad())
    val x = cos(lat.toRad()) * sin(other.lat.toRad()) -
        sin(lat.toRad()) * cos(other.lat.toRad()) * cos(dLon)
    return normaliseDegrees(atan2(y, x).toDeg().toFloat())
}

/** Folds any angle into [0, 360). */
fun normaliseDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

/**
 * Signed difference from [current] to [target], in [-180, 180).
 *
 * An exact half turn is ambiguous — both ways round are the same length — and resolves
 * anticlockwise.
 */
fun angleDelta(current: Float, target: Float): Float = ((target - current + 540f) % 360f) - 180f

/**
 * Eases [current] toward [target] the short way round the circle, so a 359° → 1° step travels 2°
 * rather than 358°.
 */
fun lerpAngle(current: Float, target: Float, factor: Float): Float =
    normaliseDegrees(current + angleDelta(current, target) * factor)

/** Whether two headings are within [tolerance] degrees of each other. */
fun headingsAlign(a: Float, b: Float, tolerance: Float): Boolean =
    abs(angleDelta(a, b)) <= tolerance
