package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import androidx.core.content.edit
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode

/**
 * The handful of things worth remembering between rides: what you were doing, which docks are
 * yours, where you are heading, and roughly where you were.
 */
class RiderPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("rider_prefs", Context.MODE_PRIVATE)

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
                position = GeoPoint(
                    prefs.getFloat(KEY_DESTINATION_LAT, 0f).toDouble(),
                    prefs.getFloat(KEY_DESTINATION_LON, 0f).toDouble()
                )
            )
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_DESTINATION_ID)
                remove(KEY_DESTINATION_NAME)
                remove(KEY_DESTINATION_LAT)
                remove(KEY_DESTINATION_LON)
            } else {
                putInt(KEY_DESTINATION_ID, value.dockId)
                putString(KEY_DESTINATION_NAME, value.name)
                putFloat(KEY_DESTINATION_LAT, value.position.lat.toFloat())
                putFloat(KEY_DESTINATION_LON, value.position.lon.toFloat())
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
        get() {
            val lat = prefs.getFloat(KEY_LAT, Float.NaN)
            val lon = prefs.getFloat(KEY_LON, Float.NaN)
            return if (lat.isNaN() || lon.isNaN()) null
            else GeoPoint(lat.toDouble(), lon.toDouble())
        }
        set(value) {
            if (value == null) return
            prefs.edit {
                putFloat(KEY_LAT, value.lat.toFloat())
                putFloat(KEY_LON, value.lon.toFloat())
            }
        }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_FAVOURITES = "favourites"
        const val KEY_DESTINATION_ID = "destination_id"
        const val KEY_DESTINATION_NAME = "destination_name"
        const val KEY_DESTINATION_LAT = "destination_lat"
        const val KEY_DESTINATION_LON = "destination_lon"
        const val KEY_LAT = "last_lat"
        const val KEY_LON = "last_lon"
    }
}
