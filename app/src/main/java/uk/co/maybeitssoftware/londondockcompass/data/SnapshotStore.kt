package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/**
 * Keeps the last dock snapshot on disk.
 *
 * The tile and the complication are cold-started by the system with no warning and are expected to
 * render immediately. Without this they would show a spinner every time; with it they show the last
 * thing we knew and refresh underneath.
 */
class SnapshotStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("dock_snapshot", Context.MODE_PRIVATE)

    fun write(origin: GeoPoint, snapshot: DockSnapshot) {
        try {
            val payload = StoredSnapshot(
                lat = origin.lat,
                lon = origin.lon,
                fetchedAtMillis = snapshot.fetchedAtMillis,
                docks = snapshot.docks.map { it.toStored() }
            )
            prefs.edit { putString(KEY_SNAPSHOT, snapshotJson.encodeToString(payload)) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not persist dock snapshot", e)
        }
    }

    fun read(): Pair<GeoPoint, DockSnapshot>? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return try {
            val stored = snapshotJson.decodeFromString<StoredSnapshot>(raw)
            GeoPoint(stored.lat, stored.lon) to DockSnapshot(
                docks = stored.docks.map { it.toDock() },
                source = DockSource.CACHED,
                fetchedAtMillis = stored.fetchedAtMillis
            )
        } catch (e: Exception) {
            Log.w(TAG, "Discarding unreadable dock snapshot", e)
            prefs.edit { remove(KEY_SNAPSHOT) }
            null
        }
    }

    private companion object {
        const val TAG = "SnapshotStore"
        const val KEY_SNAPSHOT = "last_snapshot"
    }
}

/** File-level so tests round-trip through the same parser the store uses. */
internal val snapshotJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class StoredSnapshot(
    val lat: Double,
    val lon: Double,
    val fetchedAtMillis: Long,
    val docks: List<StoredDock>
)

@Serializable
internal data class StoredDock(
    val id: Int,
    val name: String,
    val lat: Double,
    val lon: Double,
    val inService: Boolean,
    val bikes: Int? = null,
    val eBikes: Int? = null,
    val standardBikes: Int? = null,
    val emptyDocks: Int? = null,
    val totalDocks: Int? = null,
    val observedAtMillis: Long? = null
) {
    fun toDock() = Dock(
        id = id,
        name = name,
        position = GeoPoint(lat, lon),
        inService = inService,
        availability = if (bikes == null || emptyDocks == null) null else Availability(
            bikes = bikes,
            eBikes = eBikes ?: 0,
            standardBikes = standardBikes ?: bikes,
            emptyDocks = emptyDocks,
            totalDocks = totalDocks ?: (bikes + emptyDocks),
            observedAtMillis = observedAtMillis ?: 0L
        )
    )
}

internal fun Dock.toStored() = StoredDock(
    id = id,
    name = name,
    lat = position.lat,
    lon = position.lon,
    inService = inService,
    bikes = availability?.bikes,
    eBikes = availability?.eBikes,
    standardBikes = availability?.standardBikes,
    emptyDocks = availability?.emptyDocks,
    totalDocks = availability?.totalDocks,
    observedAtMillis = availability?.observedAtMillis
)
