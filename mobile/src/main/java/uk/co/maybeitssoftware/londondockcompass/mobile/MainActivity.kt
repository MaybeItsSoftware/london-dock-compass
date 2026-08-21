package uk.co.maybeitssoftware.londondockcompass.mobile

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.awaitCancellation
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.mobile.theme.LondonDockCompassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 16 enforces edge-to-edge at targetSdk 36; safeDrawingPadding does the rest.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LondonDockCompassTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding()
                ) {
                    DockFinderApp()
                }
            }
        }
    }
}

/**
 * Coarse location is enough here.
 *
 * The watch app insists on precise because it draws an arrow and buzzes you at 25 metres; a list
 * ordered by distance is perfectly serviceable on an approximate fix, and asking for less is the
 * right default when less will do.
 */
private enum class LocationAccess { GRANTED, DENIED }

private fun Context.locationAccess(): LocationAccess =
    if (granted(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    ) LocationAccess.GRANTED else LocationAccess.DENIED

private fun Context.granted(permission: String): Boolean =
    ActivityCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }
}

@Composable
private fun DockFinderApp() {
    val context = LocalContext.current
    var access by remember { mutableStateOf(context.locationAccess()) }
    var alreadyAsked by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) access = context.locationAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            alreadyAsked = true
            access = context.locationAccess()
        }
    )
    val request = {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        )
    }

    LaunchedEffect(Unit) {
        if (access == LocationAccess.DENIED && !alreadyAsked) request()
    }

    if (access == LocationAccess.DENIED) {
        PermissionPanel(onRequest = request, onOpenSettings = context::openAppSettings)
        return
    }

    val viewModel: DockListViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onVisibilityChanged(true)
            try {
                awaitCancellation()
            } finally {
                viewModel.onVisibilityChanged(false)
            }
        }
    }

    LocationUpdates { location ->
        viewModel.onPosition(GeoPoint(location.latitude, location.longitude))
    }

    DockListScreen(
        state = state,
        onSelectMode = viewModel::setMode,
        onToggleFavourite = viewModel::toggleFavourite,
        onPinDestination = viewModel::pinDestination,
        onClearDestination = viewModel::clearDestination
    )
}

@Composable
private fun PermissionPanel(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "Location needed to find nearby docks",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Button(onClick = onRequest) { Text("Allow") }
            // Always present: after two refusals the system stops showing the dialog, and a
            // screen whose only button does nothing is worse than no screen at all.
            OutlinedButton(onClick = onOpenSettings) { Text("Open settings") }
        }
    }
}

/**
 * Streams fixes while the app is in front.
 *
 * Balanced power rather than high accuracy: this list is ordered by distance to docks hundreds of
 * metres apart, and a phone in a pocket has no reason to run the GPS hard for that.
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
        // Spelled out rather than routed through locationAccess(), because lint's permission
        // analysis follows checkSelfPermission calls and not helper functions — and a suppression
        // would throw away a check worth keeping.
        val permitted = ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        if (!permitted) return@DisposableEffect onDispose {}

        client.lastLocation.addOnSuccessListener { it?.let(onLocation) }

        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .setMinUpdateDistanceMeters(25f)
            .build()

        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        onDispose { client.removeLocationUpdates(callback) }
    }
}
