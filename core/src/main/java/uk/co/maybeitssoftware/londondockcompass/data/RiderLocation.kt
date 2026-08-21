package uk.co.maybeitssoftware.londondockcompass.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import java.util.concurrent.TimeUnit

/**
 * A best-effort position for surfaces that cannot run a location stream.
 *
 * The tile and the complication are woken by the system for a second or two at a time. A
 * while-in-use location permission hands back null once the app is out of sight, so when the
 * platform declines we fall back to wherever the app last saw the rider — a stale position from
 * ten minutes ago still names the right docks far more often than showing nothing does.
 */
suspend fun riderPosition(context: Context): GeoPoint? = withContext(Dispatchers.IO) {
    val fallback = RiderPreferences(context).lastKnownPosition
    if (ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return@withContext fallback
    }

    runCatching {
        val client = LocationServices.getFusedLocationProviderClient(context)
        Tasks.await(client.lastLocation, LOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }.getOrNull() ?: fallback
}

private const val LOCATION_TIMEOUT_SECONDS = 3L
