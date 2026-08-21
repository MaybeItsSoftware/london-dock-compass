package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/**
 * The parts of a rider's setup that should follow them between devices.
 *
 * Saved docks and the pinned destination, and nothing else. Ride mode is deliberately excluded: it
 * answers "what am I doing in the next ten minutes", which is a property of the device in your hand
 * rather than of you — checking spaces on the phone should not flip the watch out of bike mode
 * while you are still looking for a bike.
 */
data class RiderState(
    val favourites: Set<Int>,
    val destination: Destination?,
    /** Wall clock at the moment of the change. Decides who wins a conflict. */
    val updatedAtMillis: Long
)

/**
 * Mirrors [RiderState] across the Wear Data Layer.
 *
 * Conflicts resolve last-write-wins on wall clock. That is not a vector clock and it cannot be:
 * the two devices may not have been in contact for hours, and there is no meaningful causal order
 * between "saved a dock on the phone" and "pinned a destination on the watch". Phone and watch are
 * both NTP-synced in practice, and the failure mode of getting it wrong is a saved dock reverting —
 * recoverable, and cheap next to the complexity of doing better.
 */
class RiderSync(context: Context) {

    private val app = context.applicationContext
    private val dataClient by lazy { Wearable.getDataClient(app) }

    /**
     * Publishes local state to the other device.
     *
     * setUrgent because the whole point is that pinning a destination on the phone is on the wrist
     * by the time you have picked up the bike; the default delivery window is measured in minutes.
     */
    suspend fun push(state: RiderState) = withContext(Dispatchers.IO) {
        runCatching {
            val request = PutDataMapRequest.create(PATH).apply {
                dataMap.write(state)
            }.asPutDataRequest().setUrgent()
            Tasks.await(dataClient.putDataItem(request))
        }.onFailure { Log.w(TAG, "Could not publish rider state", it) }
        Unit
    }

    /** Pulls whatever the other device last published, for a cold start with no recent event. */
    suspend fun pull(): RiderState? = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(dataClient.dataItems)
                .use { buffer ->
                    buffer.firstOrNull { it.uri.path == PATH }
                        ?.let { DataMapItem.fromDataItem(it).dataMap.readState() }
                }
        }.onFailure { Log.w(TAG, "Could not read rider state", it) }.getOrNull()
    }

    companion object {
        const val PATH = "/rider-state"
        private const val TAG = "RiderSync"

        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_DESTINATION_ID = "destination_id"
        private const val KEY_DESTINATION_NAME = "destination_name"
        private const val KEY_DESTINATION_LAT = "destination_lat"
        private const val KEY_DESTINATION_LON = "destination_lon"
        private const val KEY_UPDATED_AT = "updated_at"

        private fun DataMap.write(state: RiderState) {
            putIntegerArrayList(KEY_FAVOURITES, ArrayList(state.favourites.sorted()))
            putLong(KEY_UPDATED_AT, state.updatedAtMillis)
            val destination = state.destination
            if (destination == null) {
                putInt(KEY_DESTINATION_ID, -1)
                remove(KEY_DESTINATION_NAME)
            } else {
                putInt(KEY_DESTINATION_ID, destination.dockId)
                putString(KEY_DESTINATION_NAME, destination.name)
                putDouble(KEY_DESTINATION_LAT, destination.position.lat)
                putDouble(KEY_DESTINATION_LON, destination.position.lon)
            }
        }

        internal fun DataMap.readState(): RiderState {
            val id = getInt(KEY_DESTINATION_ID, -1)
            return RiderState(
                favourites = getIntegerArrayList(KEY_FAVOURITES).orEmpty().toSet(),
                destination = if (id < 0) null else Destination(
                    dockId = id,
                    name = getString(KEY_DESTINATION_NAME).orEmpty(),
                    position = GeoPoint(
                        getDouble(KEY_DESTINATION_LAT, 0.0),
                        getDouble(KEY_DESTINATION_LON, 0.0)
                    )
                ),
                updatedAtMillis = getLong(KEY_UPDATED_AT, 0L)
            )
        }
    }
}

/**
 * Receives the other device's changes.
 *
 * Declared once in the core library's manifest, so both the watch app and the phone app get it by
 * manifest merge — there is nothing device-specific about accepting a saved dock.
 */
class RiderStateListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val prefs = RiderPreferences(this)
        dataEvents
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == RiderSync.PATH }
            .forEach { event ->
                val remote = with(RiderSync) {
                    DataMapItem.fromDataItem(event.dataItem).dataMap.readState()
                }
                // merge() is a no-op when our copy is newer, so a device that publishes and then
                // hears its own echo does not clobber a change made since.
                if (prefs.merge(remote)) {
                    Log.i(TAG, "Applied rider state from the other device")
                }
            }
    }

    private companion object {
        const val TAG = "RiderStateListener"
    }
}
