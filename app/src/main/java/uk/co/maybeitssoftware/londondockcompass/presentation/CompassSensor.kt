package uk.co.maybeitssoftware.londondockcompass.presentation

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.lerpAngle
import uk.co.maybeitssoftware.londondockcompass.domain.normaliseDegrees

/** How much the compass can currently be trusted. */
enum class CompassAccuracy { GOOD, FAIR, NEEDS_CALIBRATION }

/**
 * Turns the rotation vector into a heading we can point an arrow with.
 *
 * Two things the first implementation got wrong. The sensor reports degrees from **magnetic** north
 * while a bearing between two coordinates is measured from **true** north, so the two were being
 * subtracted without ever being in the same frame; [GeomagneticField] reconciles them. And a
 * magnetometer that has not been calibrated points confidently at the wrong thing, so accuracy is
 * now surfaced instead of being swallowed by an empty callback.
 */
class CompassSensor(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val _heading = mutableFloatStateOf(0f)
    private val _accuracy = mutableStateOf(CompassAccuracy.FAIR)

    /** Degrees clockwise from true north that the top of the watch is pointing. */
    val heading: State<Float> = _heading
    val accuracy: State<CompassAccuracy> = _accuracy

    /** Degrees to add to a magnetic reading to get a true one. About one degree in London. */
    private var declination = 0f

    val isAvailable: Boolean get() = rotationVector != null

    fun start() {
        val sensor = rotationVector ?: return
        // GAME is roughly 50Hz. FASTEST can be several hundred on some watches, which no display
        // can show and no battery should pay for.
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sensorManager.unregisterListener(this)

    /** Declination varies by where you are; cheap to recompute when the fix moves. */
    fun onPosition(point: GeoPoint) {
        declination = GeomagneticField(
            point.lat.toFloat(),
            point.lon.toFloat(),
            0f,
            System.currentTimeMillis()
        ).declination
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val magnetic = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val trueNorth = normaliseDegrees(magnetic + declination)
        // Enough smoothing to stop the needle shivering, little enough to keep up with a turn.
        _heading.floatValue = lerpAngle(_heading.floatValue, trueNorth, SMOOTHING)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        _accuracy.value = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.FAIR
            else -> CompassAccuracy.NEEDS_CALIBRATION
        }
    }

    private companion object {
        const val SMOOTHING = 0.35f
    }
}
