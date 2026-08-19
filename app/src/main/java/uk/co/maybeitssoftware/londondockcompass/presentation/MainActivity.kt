package uk.co.maybeitssoftware.londondockcompass.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.davidmoten.geo.GeoHash
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.co.maybeitssoftware.londondockcompass.R
import uk.co.maybeitssoftware.londondockcompass.data.BikePointRepository
import uk.co.maybeitssoftware.londondockcompass.data.BikePointStatus
import java.io.InputStream
import kotlin.math.roundToInt

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
    val temporary: Boolean
)

data class NearbyStationCompass(
    val station: Station,
    val distanceInMeters: Int,
    val cycleBearing: Float
)

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationVectorSensor: Sensor? = null

    // compassBearing is the direction the top of the watch is pointing (0=North, clockwise)
    private val compassBearing = mutableFloatStateOf(0f)

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    // Track if the watch is awake or resting (ambient mode)
    private val isAmbient = mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient.value = true
        }

        override fun onExitAmbient() {
            isAmbient.value = false
        }

        override fun onUpdateAmbient() {
            // Called occasionally by the system to refresh the screen in ambient mode
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            val appColors = MaterialTheme.colors.copy(
                background = Color.Black,
                onBackground = Color.White,
                primary = Color(0xFFD62246) // Raspberry — the brand colour
            )
            MaterialTheme(colors = appColors) {
                Scaffold(timeText = { TimeText() }) {
                    LondonDockCompassApp(
                        bearing = compassBearing.floatValue,
                        isAmbient = isAmbient.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationVectorSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)

        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        val newBearing = (azimuthDeg + 360f) % 360f

        // Track the heading closely — the arrow has to keep up with a moving bike
        compassBearing.floatValue = lerpAngle(compassBearing.floatValue, newBearing, 0.5f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

private fun lerpAngle(current: Float, target: Float, factor: Float): Float {
    val diff = ((target - current + 540f) % 360f) - 180f
    return (current + diff * factor + 360f) % 360f
}

private val jsonParser = Json { ignoreUnknownKeys = true }

private fun loadStations(context: Context): Map<String, List<Station>> {
    val inputStream: InputStream = context.resources.openRawResource(R.raw.docklocations)
    return jsonParser.decodeFromString(inputStream.bufferedReader().use { it.readText() })
}

@Composable
fun LondonDockCompassApp(
    bearing: Float,
    isAmbient: Boolean
) {
    val context = LocalContext.current

    // Check if permission is already granted so we don't flash the permission screen
    var hasPermission by remember {
        mutableStateOf(
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasPermission = granted }
    )

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Load stations on a background thread so the main thread isn't blocked during first composition
    var stations by remember { mutableStateOf<Map<String, List<Station>>?>(null) }
    LaunchedEffect(Unit) {
        stations = try {
            withContext(Dispatchers.Default) { loadStations(context) }
        } catch (e: Exception) {
            android.util.Log.e("LondonDockCompass", "Failed to load stations", e)
            null
        }
    }

    if (hasPermission) {
        MainScreen(bearing, stations, isAmbient)
    } else {
        PermissionScreen { permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }
    }
}

@Composable
fun MainScreen(
    bearing: Float,
    stations: Map<String, List<Station>>?,
    isAmbient: Boolean
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val repository = remember { BikePointRepository() }
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    var lastLocation by remember { mutableStateOf<Location?>(null) }
    var nearbyStations by remember { mutableStateOf<List<NearbyStationCompass>>(emptyList()) }
    // Keyed by station ID — concurrent-safe snapshot map, each entry filled in as API responds
    val liveStatuses = remember { mutableStateMapOf<Int, BikePointStatus>() }

    // You're on a bike — the screen has to stay lit for the whole ride
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val statusText = when {
        lastLocation == null && stations == null -> "Locating..."
        lastLocation == null -> "Waiting for GPS..."
        stations == null -> "Loading docks..."
        nearbyStations.isEmpty() -> "No docks nearby"
        else -> ""
    }

    // Recompute nearest stations whenever location or stations change
    LaunchedEffect(lastLocation, stations) {
        val location = lastLocation ?: return@LaunchedEffect
        val loadedStations = stations ?: return@LaunchedEffect
        val geoHash = GeoHash.encodeHash(location.latitude, location.longitude, 7)
        val newStations = withContext(Dispatchers.Default) {
            findNearestStations(location, geoHash, loadedStations)
        }
        nearbyStations = newStations
        // Fetch live status for any station not yet fetched (parallel, non-blocking)
        newStations.forEach { nearby ->
            if (nearby.station.id !in liveStatuses) {
                scope.launch {
                    repository.getBikePoint(nearby.station.id.toString())
                        ?.let { liveStatuses[nearby.station.id] = it }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return@DisposableEffect onDispose {}
        }

        // Use last known location immediately for fast startup
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) lastLocation = location
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { lastLocation = it }
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest, locationCallback, Looper.getMainLooper()
        )

        onDispose { fusedLocationClient.removeLocationUpdates(locationCallback) }
    }

    // Nothing to page through until the first fix lands — statusText says why.
    if (nearbyStations.isEmpty()) {
        StatusScreen(statusText)
        return
    }

    // Page 0 is the closest dock, so the pager opens on it with no scrolling needed.
    val pagerState = rememberPagerState { nearbyStations.size }

    Box(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val station = nearbyStations[page]
            StationPage(
                station = station,
                status = liveStatuses[station.station.id],
                bearing = bearing,
                isAmbient = isAmbient
            )
        }
        HorizontalPageIndicator(
            pageIndicatorState = object : PageIndicatorState {
                override val pageCount get() = pagerState.pageCount
                override val pageOffset get() = pagerState.currentPageOffsetFraction
                override val selectedPage get() = pagerState.currentPage
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 4.dp)
        )
    }
}

@Composable
fun StatusScreen(statusText: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
            color = Color.White,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun StationPage(
    station: NearbyStationCompass,
    status: BikePointStatus?,
    bearing: Float,
    isAmbient: Boolean
) {
    // Arrow points LEFT by default, so +90° rotates it to point UP (North = 0°)
    val arrowRotation = station.cycleBearing - bearing + 90f

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.arrow),
            contentDescription = "Direction to nearest dock",
            modifier = Modifier
                .fillMaxSize(0.9f)
                .rotate(arrowRotation),
            colorFilter = if (isAmbient) ColorFilter.tint(Color.White) else null
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // --> Remove the background box entirely in ambient mode
                modifier = if (isAmbient) {
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                } else {
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                }
            ) {
                Text(
                    text = "${station.distanceInMeters}m",
                    style = MaterialTheme.typography.display1.copy(fontWeight = FontWeight.Bold),
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (!isAmbient) {
                    Text(
                        text = station.station.name,
                        style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (status != null) {
                        Text(
                            text = "${status.bikes} bikes  |  ${status.eBikes} e-bikes  |  ${status.emptyDocks} free",
                            style = MaterialTheme.typography.caption1,
                            color = MaterialTheme.colors.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

fun findNearestStations(
    position: Location,
    geoHash: String,
    stations: Map<String, List<Station>>,
    limit: Int = 5
): List<NearbyStationCompass> {
    val candidates = findCandidates(geoHash, stations, minCount = limit)
    return candidates.map { station ->
        val stationLocation = Location("").apply {
            latitude = station.lat
            longitude = station.long
        }
        NearbyStationCompass(
            station = station,
            distanceInMeters = position.distanceTo(stationLocation).roundToInt(),
            cycleBearing = position.bearingTo(stationLocation)
        )
    }.sortedBy { it.distanceInMeters }.take(limit)
}

private fun findCandidates(geoHash: String, stations: Map<String, List<Station>>, minCount: Int = 3): Set<Station> {
    val initial = mutableSetOf(geoHash).also { it.addAll(GeoHash.neighbours(geoHash)) }
    val candidates = initial.flatMap { stations[it] ?: emptyList() }.toMutableSet()

    var frontier = initial
    val visited = initial.toMutableSet()

    repeat(5) {
        if (candidates.size >= minCount) return candidates
        val next = mutableSetOf<String>()
        for (hash in frontier) next.addAll(GeoHash.neighbours(hash))
        next.removeAll(visited)
        visited.addAll(next)
        frontier = next
        for (hash in next) stations[hash]?.let { candidates.addAll(it) }
    }
    return candidates
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Location needed to find nearby docks",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body2
            )
            Button(
                onClick = onRequest,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFD62246)
                )
            ) {
                Text("Allow")
            }
        }
    }
}