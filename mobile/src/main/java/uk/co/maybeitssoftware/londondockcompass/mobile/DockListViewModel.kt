package uk.co.maybeitssoftware.londondockcompass.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.maybeitssoftware.londondockcompass.core.R
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.DockSnapshot
import uk.co.maybeitssoftware.londondockcompass.data.DockSource
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.RiderSync
import uk.co.maybeitssoftware.londondockcompass.data.TflBikePointApi
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.bearingTo
import uk.co.maybeitssoftware.londondockcompass.domain.distanceTo
import uk.co.maybeitssoftware.londondockcompass.domain.rankDocks
import kotlin.math.roundToInt

data class DockListUiState(
    val mode: RideMode = RideMode.HIRE,
    val docks: List<RankedDock> = emptyList(),
    val savedDocks: List<RankedDock> = emptyList(),
    val destination: Destination? = null,
    val favourites: Set<Int> = emptySet(),
    val source: DockSource = DockSource.BUNDLED,
    val hasPosition: Boolean = false,
    val isRefreshing: Boolean = false
) {
    val statusMessage: String?
        get() = when {
            !hasPosition -> "Finding you…"
            docks.isEmpty() && isRefreshing -> "Finding docks…"
            docks.isEmpty() -> "No docks nearby"
            else -> null
        }
}

/**
 * The phone's view of the same world the watch sees.
 *
 * Every decision here — which docks, in what order, how many of what — comes from the shared
 * domain and data layers untouched. A phone shows more of the deck at once and has no compass to
 * point, so the difference is entirely in the rendering.
 */
class DockListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DockRepository(app)
    private val prefs = RiderPreferences(app)
    private val sync = RiderSync(app)
    private val api = TflBikePointApi(app.getString(R.string.tfl_app_key))

    private val _state = MutableStateFlow(
        DockListUiState(
            mode = prefs.mode,
            favourites = prefs.favourites,
            destination = prefs.destination
        )
    )
    val state: StateFlow<DockListUiState> = _state.asStateFlow()

    private var position: GeoPoint? = prefs.lastKnownPosition
    private var snapshot: DockSnapshot = DockSnapshot.EMPTY
    private val savedDocks = mutableMapOf<Int, Dock>()
    private var savedDocksRefreshedAt = 0L

    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val isVisible = MutableStateFlow(false)

    init {
        viewModelScope.launch { refreshRequests.collect { runRefresh() } }
        viewModelScope.launch {
            isVisible.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (isActive) {
                    refresh()
                    delay(REFRESH_INTERVAL_MILLIS)
                }
            }
        }
        // A dock saved on the watch should appear here without a restart, and vice versa. The
        // listener service writes to the same SharedPreferences this reads, in this same process.
        viewModelScope.launch {
            prefs.changes.collect {
                val remote = prefs.snapshot()
                _state.update {
                    it.copy(favourites = remote.favourites, destination = remote.destination)
                }
                recompute()
            }
        }
        // Cold start: the other device may have published while this app was not running.
        viewModelScope.launch {
            sync.pull()?.let { if (prefs.merge(it)) recompute() }
        }
    }

    fun onVisibilityChanged(visible: Boolean) {
        isVisible.value = visible
    }

    fun onPosition(point: GeoPoint) {
        val previous = position
        position = point
        prefs.lastKnownPosition = point
        recompute()
        if (previous == null || previous.distanceTo(point) > REFETCH_AFTER_METRES) refresh()
    }

    fun cycleMode() {
        val next = _state.value.mode.next()
        prefs.mode = next
        _state.update { it.copy(mode = next) }
        recompute()
    }

    fun setMode(mode: RideMode) {
        prefs.mode = mode
        _state.update { it.copy(mode = mode) }
        recompute()
    }

    fun toggleFavourite(dockId: Int) {
        prefs.toggleFavourite(dockId)
        if (dockId !in prefs.favourites) savedDocks.remove(dockId)
        _state.update { it.copy(favourites = prefs.favourites) }
        savedDocksRefreshedAt = 0L
        publish()
        recompute()
        refresh()
    }

    fun pinDestination(dock: RankedDock) {
        val pinned = Destination(dock.id, dock.name, dock.dock.position)
        prefs.destination = pinned
        savedDocks[dock.id] = dock.dock
        _state.update { it.copy(destination = pinned) }
        publish()
        recompute()
        refresh()
    }

    fun clearDestination() {
        prefs.destination = null
        _state.update { it.copy(destination = null) }
        publish()
        recompute()
    }

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    /** Pushes saved docks and the destination to the watch. */
    private fun publish() {
        viewModelScope.launch { sync.push(prefs.snapshot()) }
    }

    private suspend fun runRefresh() {
        val here = position ?: return
        _state.update { it.copy(isRefreshing = true) }
        try {
            snapshot = repository.docksNear(here)
            refreshSavedDocks()
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed", e)
        } finally {
            _state.update { it.copy(isRefreshing = false) }
            recompute()
        }
    }

    /** Saved docks and the pinned destination are usually outside the swept radius. */
    private suspend fun refreshSavedDocks() {
        val nearbyIds = snapshot.docks.map { it.id }.toSet()
        val wanted = (prefs.favourites + setOfNotNull(prefs.destination?.dockId)) - nearbyIds
        savedDocks.keys.retainAll(wanted)

        val now = System.currentTimeMillis()
        if (now - savedDocksRefreshedAt < SAVED_REFRESH_INTERVAL_MILLIS) return
        savedDocksRefreshedAt = now

        val fetched = coroutineScope {
            wanted.take(MAX_SAVED_FETCHES).map { id ->
                async {
                    id to runCatching { api.dock(id) }
                        .onFailure { Log.w(TAG, "Saved dock $id refresh failed", it) }
                        .getOrNull()
                }
            }.awaitAll()
        }
        fetched.forEach { (id, dock) -> if (dock != null) savedDocks[id] = dock }
    }

    private fun recompute() {
        val here = position
        if (here == null) {
            _state.update { it.copy(hasPosition = false) }
            return
        }

        fun rank(dock: Dock) = RankedDock(
            dock = dock,
            distanceMetres = here.distanceTo(dock.position).roundToInt(),
            bearingDegrees = here.bearingTo(dock.position),
            count = dock.availability?.countFor(_state.value.mode)
        )

        val ranked = rankDocks(here, snapshot.docks, _state.value.mode, limit = DECK_SIZE)
        val nearbyIds = ranked.map { it.id }.toSet()
        val saved = savedDocks.values
            .filter { it.id !in nearbyIds }
            .map(::rank)
            .sortedBy { it.distanceMetres }

        _state.update {
            it.copy(
                docks = ranked,
                savedDocks = saved,
                source = snapshot.source,
                hasPosition = true
            )
        }
    }

    private companion object {
        const val TAG = "DockListViewModel"

        /** A phone is not on a bike, and a phone screen is not a wrist. Slower, and more of it. */
        const val REFRESH_INTERVAL_MILLIS = 30_000L
        const val SAVED_REFRESH_INTERVAL_MILLIS = 120_000L
        const val REFETCH_AFTER_METRES = 250.0
        const val MAX_SAVED_FETCHES = 6
        const val DECK_SIZE = 20
    }
}
