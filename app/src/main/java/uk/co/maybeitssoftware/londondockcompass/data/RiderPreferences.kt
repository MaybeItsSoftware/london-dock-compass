package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode

/**
 * The handful of things worth remembering between rides: what you were doing, which docks are
 * yours, where you are heading, and roughly where you were.
 *
 * Split across two files on purpose. What you chose — mode, saved docks, destination — is worth
 * restoring onto a new watch. Where you *were* is not: it is a cold-start seed that the first fix
 * overwrites, and the privacy policy promises coordinates never leave the device. Keeping it in its
 * own file is what lets `backup_rules.xml` exclude one and keep the other.
 */
class RiderPreferences(context: Context) {

    private val app = context.applicationContext
    private val prefs: SharedPreferences =
        app.getSharedPreferences("rider_prefs", Context.MODE_PRIVATE)

    /** Excluded from cloud backup. See `res/xml/backup_rules.xml`. */
    private val locationPrefs: SharedPreferences =
        app.getSharedPreferences("rider_location", Context.MODE_PRIVATE)

    init {
        // Older installs kept the last fix in the backed-up file. Drop it rather than migrate it —
        // it is worth less than a second of GPS, and leaving it there would put it in a backup.
        if (prefs.contains(LEGACY_KEY_LAT)) {
            prefs.edit { remove(LEGACY_KEY_LAT); remove(LEGACY_KEY_LON) }
        }
    }

    /** Reopening the app mid-journey should not silently forget you were looking for a space. */
    var mode: RideMode
        get() = runCatching { RideMode.valueOf(prefs.getString(KEY_MODE, null)!!) }
            .getOrDefault(RideMode.HIRE)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    /** Commuters use the same two or three docks every day; those belong at your fingertips. */
    var favourites: Set<Int>
        get() = prefs.getStringSet(KEY_FAVOURITES, emptySet())
            .orEmpty()
            .mapNotNull(String::toIntOrNull)
            .toSet()
        set(value) = prefs.edit { putStringSet(KEY_FAVOURITES, value.map(Int::toString).toSet()) }

    fun toggleFavourite(dockId: Int): Boolean {
        val current = favourites
        val nowFavourite = dockId !in current
        favourites = if (nowFavourite) current + dockId else current - dockId
        return nowFavourite
    }

    fun isFavourite(dockId: Int): Boolean = dockId in favourites

    /** The dock you are riding to, if you have pinned one. */
    var destination: Destination?
        get() {
            val id = prefs.getInt(KEY_DESTINATION_ID, -1).takeIf { it >= 0 } ?: return null
            return Destination(
                dockId = id,
                name = prefs.getString(KEY_DESTINATION_NAME, "").orEmpty(),
                position = prefs.readPoint(KEY_DESTINATION_LAT, KEY_DESTINATION_LON)
                    ?: prefs.readLegacyPoint(LEGACY_KEY_DESTINATION_LAT, LEGACY_KEY_DESTINATION_LON)
                    ?: GeoPoint(0.0, 0.0)
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_DESTINATION_ID)
                remove(KEY_DESTINATION_NAME)
                remove(KEY_DESTINATION_LAT)
                remove(KEY_DESTINATION_LON)
                remove(LEGACY_KEY_DESTINATION_LAT)
                remove(LEGACY_KEY_DESTINATION_LON)
            } else {
                putInt(KEY_DESTINATION_ID, value.dockId)
                putString(KEY_DESTINATION_NAME, value.name)
                putPoint(KEY_DESTINATION_LAT, KEY_DESTINATION_LON, value.position)
                // The float pair is what pre-1.3 builds read; clear it so a downgrade does not
                // resurrect a stale pin.
                remove(LEGACY_KEY_DESTINATION_LAT)
                remove(LEGACY_KEY_DESTINATION_LON)
            }
        }

    /**
     * The last fix the foreground app had.
     *
     * Background surfaces cannot always get a location of their own — a while-in-use permission
     * hands back null once the app is out of sight — so they lean on this instead of showing
     * nothing.
     */
    var lastKnownPosition: GeoPoint?
        get() = locationPrefs.readPoint(KEY_LAT, KEY_LON)
        set(value) {
            if (value == null) return
            locationPrefs.edit { putPoint(KEY_LAT, KEY_LON, value) }
        }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_DESTINATION_ID = "destination_id"
        const val KEY_DESTINATION_NAME = "destination_name"
        const val KEY_DESTINATION_LAT = "destination_lat_bits"
        const val KEY_DESTINATION_LON = "destination_lon_bits"
        const val KEY_LAT = "last_lat_bits"
        const val KEY_LON = "last_lon_bits"

        const val LEGACY_KEY_DESTINATION_LAT = "destination_lat"
        const val LEGACY_KEY_DESTINATION_LON = "destination_lon"
        const val LEGACY_KEY_LAT = "last_lat"
        const val LEGACY_KEY_LON = "last_lon"
    }
}

/**
 * Coordinates as raw [Double] bits.
 *
 * A float carries about seven significant digits, which at London's latitude is a third of a metre
 * of error — small, but it lands on the same 25m arrival band the haptics fire from, and there is
 * no reason to pay it when a long costs the same eight bytes.
 */
private fun SharedPreferences.readPoint(latKey: String, lonKey: String): GeoPoint? {
    if (!contains(latKey) || !contains(lonKey)) return null
    return GeoPoint(
        Double.fromBits(getLong(latKey, 0L)),
        Double.fromBits(getLong(lonKey, 0L))
    ).takeUnless { it.lat.isNaN() || it.lon.isNaN() }
}

private fun SharedPreferences.Editor.putPoint(latKey: String, lonKey: String, point: GeoPoint) {
    putLong(latKey, point.lat.toRawBits())
    putLong(lonKey, point.lon.toRawBits())
}

/** Reads a pin written by a pre-1.3 build, so upgrading does not lose your destination. */
private fun SharedPreferences.readLegacyPoint(latKey: String, lonKey: String): GeoPoint? {
    if (!contains(latKey) || !contains(lonKey)) return null
    val lat = getFloat(latKey, Float.NaN)
    val lon = getFloat(lonKey, Float.NaN)
    return if (lat.isNaN() || lon.isNaN()) null else GeoPoint(lat.toDouble(), lon.toDouble())
}
