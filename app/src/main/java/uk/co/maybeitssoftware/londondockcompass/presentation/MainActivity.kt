package uk.co.maybeitssoftware.londondockcompass.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.ProximityTracker
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.shouldAlert
import uk.co.maybeitssoftware.londondockcompass.presentation.theme.LondonDockCompassTheme

class MainActivity : ComponentActivity() {

    private lateinit var compass: CompassSensor
    private val isAmbient = mutableStateOf(false)

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            isAmbient.value = true
        }

        override fun onExitAmbient() {
            isAmbient.value = false
        }

        override fun onUpdateAmbient() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        compass = CompassSensor(this)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        setContent {
            LondonDockCompassTheme {
                Scaffold(timeText = { TimeText() }) {
                    LondonDockCompassApp(
                        compass = compass,
                        isAmbient = isAmbient.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        compass.start()
    }

    override fun onPause() {
        super.onPause()
        compass.stop()
    }
}

/**
 * What the platform actually granted, which is not the same question as "did they tap Allow".
 *
 * From Android 12 the system dialog offers Precise or Approximate, and picking Approximate *denies*
 * ACCESS_FINE_LOCATION. An app that only asks about FINE reads that as a flat refusal and sits on
 * its permission screen forever, with a button the system has stopped responding to.
 */
enum class LocationAccess { PRECISE, APPROXIMATE, DENIED }

private fun Context.locationAccess(): LocationAccess = when {
    granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationAccess.PRECISE
    granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationAccess.APPROXIMATE
    else -> LocationAccess.DENIED
}

private fun Context.granted(permission: String): Boolean =
    ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** The way out of a permanent denial: the system settings page for this app. */
private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
fun LondonDockCompassApp(compass: CompassSensor, isAmbient: Boolean) {
    val context = LocalContext.current

    // Checked up front so the permission screen never flashes past an already-granted user.
    var access by remember { mutableStateOf(context.locationAccess()) }
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }

    // Granting in Settings and swiping back should recover the app, not require a cold start.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = context.locationAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            alreadyAsked = true
            access = context.locationAccess()
        }
    )

    // Asking for both is what lets the system offer the precise/approximate choice at all, and
    // what lets us tell an approximate grant apart from a refusal afterwards.
    val requestLocation = {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    LaunchedEffect(Unit) {
        if (access != LocationAccess.PRECISE && !alreadyAsked) requestLocation()
    }

    if (access != LocationAccess.PRECISE) {
        PermissionScreen(
            access = access,
            onRequest = requestLocation,
            onOpenSettings = context::openAppSettings
        )
        return
    }

    val viewModel: CompassViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = remember { Haptics(context) }
    val view = LocalView.current

    // You are on a bike. The screen has to stay lit for the whole ride.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LocationUpdates { location ->
        val point = GeoPoint(location.latitude, location.longitude)
        viewModel.onPosition(point)
        compass.onPosition(point)
    }

    // The dock currently under the rider's eyes, which is the one worth buzzing about.
    var target by remember { mutableStateOf<RankedDock?>(null) }
    val proximity = remember { ProximityTracker() }
    LaunchedEffect(target?.id, target?.distanceMetres) {
        val current = target ?: return@LaunchedEffect
        proximity.update(current.id, current.distanceMetres)?.let(haptics::forBand)
    }

    // Escalating trouble at the pinned destination is the one thing allowed to interrupt a ride.
    var lastHealth by remember { mutableStateOf(state.destination?.health) }
    LaunchedEffect(state.destination?.health) {
        val health = state.destination?.health
        if (health == null) {
            lastHealth = null
            return@LaunchedEffect
        }
        if (shouldAlert(lastHealth, health)) haptics.divert()
        lastHealth = health
    }

    CompassScreen(
        state = state,
        heading = compass.heading.value,
        accuracy = compass.accuracy.value,
        isAmbient = isAmbient,
        onCycleMode = { haptics.confirm(); viewModel.cycleMode() },
        onToggleFavourite = { haptics.confirm(); viewModel.toggleFavourite(it) },
        onPinDestination = { haptics.confirm(); viewModel.pinDestination(it) },
        onClearDestination = { haptics.confirm(); viewModel.clearDestination() },
        onTargetChanged = { target = it }
    )
}

/**
 * Streams fixes while the screen is up.
 *
 * A bike covers five metres a second, so the interval is tight — but gated on actual movement, so
 * standing at a red light costs nothing.
 */
@Composable
private fun LocationUpdates(onLocation: (android.location.Location) -> Unit) {
    val context = LocalContext.current
    val client = remember { LocationServices.getFusedLocationProviderClient(context) }
    val callback = remember {
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(onLocation)
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

        // The last known fix gets an arrow on screen before the first live one lands.
        client.lastLocation.addOnSuccessListener { it?.let(onLocation) }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_500L)
            .setMinUpdateIntervalMillis(1_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }
}
