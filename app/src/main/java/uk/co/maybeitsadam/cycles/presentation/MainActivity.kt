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
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.github.davidmoten.geo.GeoHash
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import uk.co.maybeitsadam.cycles.R

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
    val station: Station,
    var distanceInMeters: Int,
    var bearing: Float
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
//            WearApp("Adam")
            Surface(modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background)) {
                LocationPermissionHandler()
            }
        }
    }
}

@Composable
fun LocationPermissionHandler() {
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
        LocationPermissionGiven()
    } else {
        PermissionDeniedScreen(
            onRequestPermission = {
                permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        )
    }
}

@Composable
fun LocationPermissionGiven() {
    val context = LocalContext.current

    val inputStream: InputStream = context.resources.openRawResource(R.raw.docklocations)

    val stations:Map<String, List<Station>> = Json.decodeFromString(
        inputStream.bufferedReader().use {
            it.readText()
        }
    )

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var nearestStation by remember {
        mutableStateOf<NearbyStationCompass?>(null)
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
            val bearing = position.bearingTo(stationLocation)

            NearbyStationCompass(station, distance, bearing)
        }.minBy{ it.distanceInMeters }
    }

    // currently only doing on app launch, should be more frequent
    LaunchedEffect(key1 = Unit) {
        while (true) {
            delay(5000)
            fetchLocation()
        }
    }

    // whenever the geohash changes, find new candidates
    // MAKE THIS REGULAR LOCATION
    LaunchedEffect(currentGeoHash) {
        currentGeoHash?.let {
            nearestStation = findNearestStationTo(position!!, it)
        }
    }

    Column (
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
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
        nearestStation?.let {
            Text(it.station.name)
            Text(it.distanceInMeters.toString())
            Text(it.bearing.toString())
            Spacer( modifier = Modifier.height(5.dp))
        }
    }
}

@Composable
fun PermissionDeniedScreen (onRequestPermission: () -> Unit) {
    Column (
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp),
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


