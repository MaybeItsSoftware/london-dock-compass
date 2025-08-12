package uk.co.maybeitsadam.cycles.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.Button
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.github.davidmoten.geo.GeoHash
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.location.Location
import androidx.compose.foundation.Image
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import uk.co.maybeitsadam.cycles.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height


@Serializable
data class Station(
    val id: Int,
    val name: String,
    val terminalName: Int,
    val lat: Double,
    val long: Double,
    val installed: Boolean,
    val locked: Boolean,
    val installDate: Long?,
    val removalDate: Long? = null,
    val temporary: Boolean,
    var nbBikes: Int? = null,
    var nbStandardBikes: Int? = null,
    var nbEBikes: Int? = null,
    var nbEmptyDocks: Int? = null,
    var nbDocks: Int? = null
)

data class NearbyStationCompass(
    val station: Station?,
    var distanceInMeters: Int,
    var cycle_bearing: Float
)

fun Color.inverse(): Color {
    return Color(
        red = 1f - this.red,
        green = 1f - this.green,
        blue = 1f - this.blue,
        alpha = this.alpha
    )
}

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    private var gravity: FloatArray? = null
    private var geomagnetic: FloatArray? = null

    private val bearingState = mutableFloatStateOf(0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            val appColours = MaterialTheme.colors.copy(
                background = Color.White,
                onBackground = Color(0xFF0009AB).inverse()

            )
            MaterialTheme(colors = appColours) {
                Box(modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                ) {


                    LocationPermissionHandler(bearingState.floatValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start listening to sensor updates when the activity is in the foreground.
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            gravity = lowPass(event.values.clone(), gravity)
        }
        if (event?.sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            geomagnetic = lowPass(event.values.clone(), geomagnetic)
        }

        if (gravity != null && geomagnetic != null) {
            val rotationMatrix = FloatArray(9)
            val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)

            if (success) {
                val remappedRotationMatrix = FloatArray(9)
                SensorManager.remapCoordinateSystem(
                    rotationMatrix,
                    SensorManager.AXIS_Y,
                    SensorManager.AXIS_MINUS_X,
                    remappedRotationMatrix
                )

                val orientation = FloatArray(3)
                SensorManager.getOrientation(remappedRotationMatrix, orientation)

                val bearingInDegrees = Math.toDegrees(orientation[0].toDouble()).toFloat()

                if (kotlin.math.abs(bearingInDegrees - bearingState.floatValue) > 1) {
                    bearingState.floatValue = (bearingInDegrees + 360) % 360
                }
            }
        }
    }

    private fun lowPass(input: FloatArray, output: FloatArray?): FloatArray {
        if (output == null) return input
        for (i in input.indices) {
            output[i] = output[i] + 0.1f * (input[i] - output[i])
        }
        return output
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

private fun loadStations(context: Context): Map<String, List<Station>> {
    val inputStream: InputStream = context.resources.openRawResource(R.raw.docklocations)
    return Json.decodeFromString(
        inputStream.bufferedReader().use { it.readText() }
    )
}

@Composable
fun LocationPermissionHandler(bearing: Float) {
    var hasLocationPermission by remember {
        mutableStateOf(false)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasLocationPermission = isGranted }
    )

    LaunchedEffect(key1 = true) {
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if (hasLocationPermission) {
        LocationPermissionGiven(bearing)
    } else {
        PermissionDeniedScreen(
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        )
    }
}

@Composable
fun LocationPermissionGiven(bearing: Float) {

    val context = LocalContext.current

    val stations: Map<String, List<Station>> = remember { loadStations(context) }

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var nearestStation by remember {
        mutableStateOf(NearbyStationCompass(
            station = null,
            distanceInMeters = 0,
            cycle_bearing = 0f)
        )
    }

    var locationInfo by remember {
        mutableStateOf("Fetching location...")
    }

    var position by remember {
        mutableStateOf<Location?>(null)
    }

    var currentGeoHash by remember {
        mutableStateOf<String?>(null)
    }

    fun findNearestStationsInLayers(checkedHashes: MutableSet<String>): MutableSet<Station> {
        val candidates: MutableSet<Station> = mutableSetOf()

        val hashesToCheck: MutableSet<String> = mutableSetOf()

        for (hash in checkedHashes) {
            hashesToCheck.addAll(GeoHash.neighbours(hash))
        }

        hashesToCheck.removeAll(checkedHashes)

        for (hash in hashesToCheck) {
            if (hash in stations) {
                candidates.addAll(stations.getValue(hash))
            }
        }

        if (candidates.any() ) {
            return candidates
        } else {
            val allNowChecked: MutableSet<String> = mutableSetOf()
            allNowChecked.addAll(hashesToCheck)
            allNowChecked.addAll(checkedHashes)
            return (findNearestStationsInLayers(allNowChecked))
        }
    }

    fun findSantanderCandidatesCenteredAt(geoHash: String): Set<Station> {
        val candidates: MutableSet<Station> = mutableSetOf()

        val surroundings: MutableSet<String> = mutableSetOf()
        surroundings.add(geoHash)
        surroundings.addAll(GeoHash.neighbours(geoHash))

        // check surrounding 9 hashes for a station
        for (hash in surroundings) {
            if (hash in stations) {
                candidates.addAll(stations.getValue(hash))
            }
        }

        return if (candidates.any()) candidates
        else {
            return findNearestStationsInLayers(surroundings)
        }
    }

    fun fetchLocation() {
        if (ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED ) {
            locationInfo = "Permission not granted"
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    position = Location("user").apply {
                        latitude = loc.latitude; longitude = loc.longitude
                    }
                    currentGeoHash = GeoHash.encodeHash(loc.latitude, loc.longitude, 7)
                } else {
                    locationInfo = "Could not retrieve location"
                    currentGeoHash = null
                }
        }
            .addOnFailureListener {
                locationInfo = "Failed to get location: ${it.message}"
                currentGeoHash = null
            }
    }

    fun orderNearestStations(){}

    fun findNearestStationTo (position: Location, geoHash: String): NearbyStationCompass {
        val candidates = findSantanderCandidatesCenteredAt(geoHash)

        return candidates.map { station ->
            val stationLocation: Location = Location("station").apply { latitude = station.lat; longitude = station.long }

            val distance = position.distanceTo(stationLocation).toInt()
            val cycleBearing = position.bearingTo(stationLocation)

            NearbyStationCompass(station, distance, cycleBearing)
        }.minBy{ it.distanceInMeters }
    }

    // currently only doing on app launch, should be more frequent
    LaunchedEffect(key1 = Unit) {
        while (true) {
            delay(4000)
            fetchLocation()
            currentGeoHash?.let {
                nearestStation = findNearestStationTo(position!!, it)
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.arrow),
            contentDescription = "Arrow",
            modifier = Modifier
                .rotate(180f - bearing + nearestStation.cycle_bearing)
                .fillMaxSize(fraction = 0.9f),
        )
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            position?.let {
                Text(position.toString())
            } ?: run {
                Text(locationInfo)
            }
            Spacer(modifier = Modifier.height(10.dp))
            currentGeoHash?.let {
                Text(it)
            }
            Spacer(modifier = Modifier.height(10.dp))
            nearestStation.station?.let { Text(it.name) }
            Text(nearestStation.distanceInMeters.toString())
            Text(nearestStation.cycle_bearing.toString())
            Spacer(modifier = Modifier.height(5.dp))
        }
    }
}

@Composable
fun PermissionDeniedScreen (onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Permission to view your location is required to use this app")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRequestPermission) {
            Text("Enable Location")
        }
    }
}
