package uk.co.maybeitssoftware.londondockcompass.mobile

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.londondockcompass.core.R
import uk.co.maybeitssoftware.londondockcompass.data.BundledDockSource
import uk.co.maybeitssoftware.londondockcompass.data.DestinationWatcher
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.DockSnapshot
import uk.co.maybeitssoftware.londondockcompass.data.DockSource
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.RiderSync
import uk.co.maybeitssoftware.londondockcompass.data.TflBikePointApi
import uk.co.maybeitssoftware.londondockcompass.domain.ArrivalTracker
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.Purpose
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.RideMode
import uk.co.maybeitssoftware.londondockcompass.domain.bearingTo
import uk.co.maybeitssoftware.londondockcompass.domain.distanceTo
import uk.co.maybeitssoftware.londondockcompass.domain.nearestDocks
import uk.co.maybeitssoftware.londondockcompass.domain.rankDocks
import uk.co.maybeitssoftware.londondockcompass.domain.searchDocks
import kotlin.math.roundToInt

data class DockListUiState(
    /** Docks that have this first, nearest first; null for plain distance. */
    val sort: RideMode? = null,
    val docks: List<RankedDock> = emptyList(),
    val savedDocks: List<RankedDock> = emptyList(),
    val destination: Destination? = null,
    /** The pinned dock with its live figures, once fetched. */
    val destinationDock: RankedDock? = null,
    /** Where to go instead, once the destination starts to fail. */
    val alternative: RankedDock? = null,
    val favourites: Set<Int> = emptySet(),
    val source: DockSource = DockSource.BUNDLED,
    val hasPosition: Boolean = false,
    val isRefreshing: Boolean = false,
    /** What the rider has typed into search. Blank means the normal nearest-first list. */
    val query: String = "",
    /**
     * Every dock in the city whose name matches [query], not just the ones nearby — this is how a
     * destination across town gets pinned. Live figures only where we already hold them.
     */
    val searchResults: List<RankedDock> = emptyList()
) {
    val isSearching: Boolean get() = query.isNotBlank()

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
    private val bundled = BundledDockSource(app)
    private val watcher = DestinationWatcher(api)
    private val arrivals = ArrivalTracker()

    /** The whole city's docks, parsed once on first search. Null until then. */
    private var allDocks: List<Dock>? = null

    private val _state = MutableStateFlow(
        DockListUiState(
            sort = prefs.sortBy,
            favourites = prefs.favourites,
            destination = prefs.destination
        )
    )
    val state: StateFlow<DockListUiState> = _state.asStateFlow()

    private var position: GeoPoint? = prefs.lastKnownPosition
    private var snapshot: DockSnapshot = DockSnapshot.EMPTY
    private val savedDocks = mutableMapOf<Int, Dock>()
    private var destinationDock: Dock? = null
    private var alternativeDock: Dock? = null
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
                val moved = remote.destination != _state.value.destination
                _state.update {
                    it.copy(favourites = remote.favourites, destination = remote.destination)
                }
                if (moved) {
                    // Pinned or unpinned on the watch.
                    destinationDock = null
                    alternativeDock = null
                    refresh()
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

    fun onPosition(point: GeoPoint, accuracyMetres: Float? = null) {
        val previous = position
        position = point
        prefs.lastKnownPosition = point
        // Arriving at the pinned dock and then leaving it ends the trip, so the pin goes with it.
        // Approximate location never gets precise enough to count, which is the safe failure.
        _state.value.destination?.let { pinned ->
            val metres = point.distanceTo(pinned.position).roundToInt()
            if (arrivals.update(pinned.dockId, metres, accuracyMetres)) clearDestination()
        }
        recompute()
        if (previous == null || previous.distanceTo(point) > REFETCH_AFTER_METRES) refresh()
    }

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        if (allDocks != null) {
            recompute()
            return
        }
        viewModelScope.launch {
            // A quarter-megabyte of JSON: parse it off the main thread, once.
            allDocks = withContext(Dispatchers.Default) { bundled.all }
            recompute()
        }
    }

    fun setSort(sort: RideMode?) {
        prefs.sortBy = sort
        _state.update { it.copy(sort = sort) }
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

    fun pinDestination(dock: RankedDock, purpose: Purpose) {
        pin(Destination(dock.id, dock.name, dock.dock.position, purpose), dock.dock)
    }

    /** Takes the suggested alternative, for the same purpose as the pin it replaces. */
    fun switchToAlternative() {
        val current = _state.value.destination ?: return
        val alternative = alternativeDock ?: return
        pin(
            Destination(alternative.id, alternative.name, alternative.position, current.purpose),
            alternative
        )
    }

    private fun pin(pinned: Destination, dock: Dock) {
        prefs.destination = pinned
        destinationDock = dock
        alternativeDock = null
        // Clearing the query shows the pin landing; the refresh fetches live figures for a dock
        // pinned from search, which usually has none yet.
        _state.update { it.copy(destination = pinned, query = "", searchResults = emptyList()) }
        publish()
        recompute()
        refresh()
    }

    fun clearDestination() {
        prefs.destination = null
        destinationDock = null
        alternativeDock = null
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
            refreshDestination(here)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed", e)
        } finally {
            _state.update { it.copy(isRefreshing = false) }
            recompute()
        }
    }

    /** The pinned dock, and somewhere else to go once it starts to fail. */
    private suspend fun refreshDestination(here: GeoPoint) {
        val pinned = _state.value.destination ?: return
        val report = watcher.check(pinned, here, snapshot.docks, known = destinationDock)
        // The pin may have changed while the request was out.
        if (_state.value.destination != pinned) return
        destinationDock = report.dock
        alternativeDock = report.alternative
    }

    /** Saved docks are usually outside the swept radius. */
    private suspend fun refreshSavedDocks() {
        val nearbyIds = snapshot.docks.map { it.id }.toSet()
        val wanted = prefs.favourites - nearbyIds
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
        _state.update { it.copy(searchResults = search(here)) }
        if (here == null) {
            _state.update { it.copy(hasPosition = false) }
            return
        }

        val sort = _state.value.sort
        fun rank(dock: Dock) = RankedDock(
            dock = dock,
            distanceMetres = here.distanceTo(dock.position).roundToInt(),
            bearingDegrees = here.bearingTo(dock.position),
            count = sort?.let { dock.availability?.countFor(it) }
        )

        val ranked = if (sort == null) {
            nearestDocks(here, snapshot.docks, limit = DECK_SIZE)
        } else {
            rankDocks(here, snapshot.docks, sort, limit = DECK_SIZE)
        }
        val nearbyIds = ranked.map { it.id }.toSet()
        val saved = savedDocks.values
            .filter { it.id !in nearbyIds }
            .map(::rank)
            .sortedBy { it.distanceMetres }

        val destination = _state.value.destination
        val rankedDestination = destinationDock?.let { dock ->
            rank(dock).copy(count = destination?.let { d -> dock.availability?.let(d.purpose::countIn) })
        }

        _state.update {
            it.copy(
                docks = ranked,
                savedDocks = saved,
                destinationDock = rankedDestination,
                alternative = alternativeDock?.let(::rank),
                source = snapshot.source,
                hasPosition = true
            )
        }
    }

    /**
     * Matches from the bundled list, with live figures swapped in wherever the sweep or a saved
     * dock fetch already has them. Without a position there is no distance, so results sort
     * alphabetically and the screen hides the figure; search still works before the first fix.
     * Locked and removed docks are left out — nobody should be able to pin one.
     */
    private fun search(here: GeoPoint?): List<RankedDock> {
        val query = _state.value.query
        val docks = allDocks ?: return emptyList()
        if (query.isBlank()) return emptyList()

        val live = (snapshot.docks + savedDocks.values + listOfNotNull(destinationDock))
            .associateBy { it.id }
        val sort = _state.value.sort
        return searchDocks(docks.filter { it.inService }, query, here).map { found ->
            val dock = live[found.id] ?: found
            RankedDock(
                dock = dock,
                distanceMetres = here?.distanceTo(dock.position)?.roundToInt() ?: 0,
                bearingDegrees = here?.bearingTo(dock.position) ?: 0f,
                count = sort?.let { dock.availability?.countFor(it) }
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
