package uk.co.maybeitssoftware.londondockcompass.presentation

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.londondockcompass.R
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.DockSnapshot
import uk.co.maybeitssoftware.londondockcompass.data.DockSource
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.TflBikePointApi
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.DestinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.bearingTo
import uk.co.maybeitssoftware.londondockcompass.domain.destinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.distanceTo
import uk.co.maybeitssoftware.londondockcompass.domain.rankDocks
import kotlin.math.roundToInt

/** Everything the screen needs, recomputed whenever the rider or the docks move. */
data class CompassUiState(
    val mode: RideMode = RideMode.HIRE,
    val docks: List<RankedDock> = emptyList(),
    val destination: DestinationState? = null,
    val favourites: Set<Int> = emptySet(),
    val source: DockSource = DockSource.BUNDLED,
    val fetchedAtMillis: Long = 0L,
    val hasPosition: Boolean = false,
    val isRefreshing: Boolean = false
) {
    /** What to say when there is nothing to point at. Null once there is. */
    val statusMessage: String?
        get() = when {
            !hasPosition -> "Finding you…"
            docks.isEmpty() && isRefreshing -> "Finding docks…"
            docks.isEmpty() -> "No docks nearby"
            else -> null
        }

    /** Live figures, or the honest absence of them. */
    val hasLiveData: Boolean get() = source != DockSource.BUNDLED
}

data class DestinationState(
    val destination: Destination,
    val ranked: RankedDock?,
    val health: DestinationHealth
)

/**
 * Holds the rider's position, the dock snapshot and the two preferences that reorder everything.
 *
 * Kept in a ViewModel so a wrist-flick rotation or an ambient-mode round trip does not restart the
 * polling loop or lose a pinned destination.
 */
class CompassViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DockRepository(app)
    private val prefs = RiderPreferences(app)
    private val api = TflBikePointApi(app.getString(R.string.tfl_app_key))

    private val _state = MutableStateFlow(
        CompassUiState(mode = prefs.mode, favourites = prefs.favourites)
    )
    val state: StateFlow<CompassUiState> = _state.asStateFlow()

    private var position: GeoPoint? = prefs.lastKnownPosition
    private var snapshot: DockSnapshot = DockSnapshot.EMPTY
    private var destinationDock: Dock? = null
    private var refreshJob: Job? = null

    /** False while [position] is only the seed from the last session. */
    private var hasRealFix = false

    init {
        prefs.destination?.let { pinned ->
            _state.update { it.copy(destination = DestinationState(pinned, null, DestinationHealth.UNKNOWN)) }
        }
        viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL_MILLIS)
            }
        }
    }

    /** A new fix. Cheap to call at GPS rate — only the ranking runs, not a network request. */
    fun onPosition(point: GeoPoint) {
        // The seed position from the last session does not count as a fix: the first real one has
        // to be allowed to correct whatever it was we opened with.
        val previous = position.takeIf { hasRealFix }
        hasRealFix = true
        position = point
        prefs.lastKnownPosition = point
        recompute()
        // Riding out of the swept radius is the other thing that justifies an off-schedule fetch.
        if (previous == null || previous.distanceTo(point) > REFETCH_AFTER_METRES) refresh()
    }

    fun cycleMode() {
        val next = _state.value.mode.next()
        prefs.mode = next
        _state.update { it.copy(mode = next) }
        recompute()
    }

    fun toggleFavourite(dockId: Int) {
        prefs.toggleFavourite(dockId)
        _state.update { it.copy(favourites = prefs.favourites) }
    }

    fun pinDestination(dock: RankedDock) {
        val destination = Destination(dock.id, dock.name, dock.dock.position)
        prefs.destination = destination
        destinationDock = dock.dock
        _state.update {
            it.copy(destination = DestinationState(destination, null, DestinationHealth.UNKNOWN))
        }
        recompute()
        refresh()
    }

    fun clearDestination() {
        prefs.destination = null
        destinationDock = null
        _state.update { it.copy(destination = null) }
    }

    fun refresh() {
        val here = position ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            try {
                snapshot = repository.docksNear(here)
                refreshDestinationDock()
            } catch (e: Exception) {
                Log.w(TAG, "Refresh failed", e)
            } finally {
                _state.update { it.copy(isRefreshing = false) }
                recompute()
            }
        }
    }

    /**
     * A pinned destination is usually outside the radius we sweep around the rider, so it needs a
     * request of its own — unless the sweep happened to cover it, in which case we already have it.
     */
    private suspend fun refreshDestinationDock() {
        val pinned = prefs.destination ?: return
        val fromSnapshot = snapshot.docks.firstOrNull { it.id == pinned.dockId }
        destinationDock = fromSnapshot
            ?: runCatching { api.dock(pinned.dockId) }
                .onFailure { Log.w(TAG, "Destination refresh failed", it) }
                .getOrNull()
            ?: destinationDock
    }

    private fun recompute() {
        val here = position
        val mode = _state.value.mode
        if (here == null) {
            _state.update { it.copy(hasPosition = false) }
            return
        }

        val ranked = rankDocks(here, snapshot.docks, mode)
        val pinned = prefs.destination
        val destinationState = pinned?.let { destination ->
            val dock = destinationDock
            val rankedDestination = dock?.let {
                RankedDock(
                    dock = it,
                    distanceMetres = here.distanceTo(it.position).roundToInt(),
                    bearingDegrees = here.bearingTo(it.position),
                    count = it.availability?.countFor(mode)
                )
            }
            DestinationState(destination, rankedDestination, destinationHealth(rankedDestination))
        }

        _state.update {
            it.copy(
                docks = ranked,
                destination = destinationState,
                favourites = prefs.favourites,
                source = snapshot.source,
                fetchedAtMillis = snapshot.fetchedAtMillis,
                hasPosition = true
            )
        }
    }

    private companion object {
        const val TAG = "CompassViewModel"

        /** Fast enough that a count is never more than a block old, slow enough to be polite. */
        const val REFRESH_INTERVAL_MILLIS = 20_000L
        const val REFETCH_AFTER_METRES = 250.0
    }
}
