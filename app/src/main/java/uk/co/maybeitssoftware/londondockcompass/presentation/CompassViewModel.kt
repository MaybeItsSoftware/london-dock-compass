package uk.co.maybeitssoftware.londondockcompass.presentation

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    /**
     * Saved docks that are not already in [docks] — the ones you are heading towards rather than
     * standing next to. Without these, a saved dock is only visible once you no longer need to be
     * told where it is.
     */
    val savedDocks: List<RankedDock> = emptyList(),
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

/**
 * How much of the rider's attention the app currently has.
 *
 * Polling used to be unconditional: a `while (isActive)` loop started in the ViewModel's `init`
 * and kept hitting TfL every twenty seconds for as long as the Activity was alive, ambient or
 * not. On a watch that is a battery bill for figures nobody is reading.
 */
enum class Attention {
    /** Not resumed. Nothing to refresh for. */
    AWAY,

    /** Screen dimmed. Worth a slow poll only while a destination is being watched. */
    AMBIENT,

    /** Eyes on the deck. */
    WATCHING
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

    /** Saved docks fetched by id, for the ones too far away to be in the sweep. */
    private val savedDocks = mutableMapOf<Int, Dock>()

    /**
     * Refresh requests, coalesced rather than dropped.
     *
     * Refusing a request while one was in flight meant a deliberate tap — pinning a destination,
     * saving a dock — could wait out the whole twenty-second poll before its count appeared, and
     * pinning a destination is the reason this app exists. A single-slot buffer that drops the
     * *oldest* collapses a burst into one follow-up run while still guaranteeing that the most
     * recent request is the one that gets served.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** False while [position] is only the seed from the last session. */
    private var hasRealFix = false

    private val attention = MutableStateFlow(Attention.AWAY)

    /** The last position actually written to disk, so we do not write one per GPS callback. */
    private var persistedPosition: GeoPoint? = null

    /** Saved docks move slowly and cost a request each; they do not need every cycle. */
    private var savedDocksRefreshedAt = 0L

    init {
        prefs.destination?.let { pinned ->
            _state.update { it.copy(destination = DestinationState(pinned, null, DestinationHealth.UNKNOWN)) }
        }
        viewModelScope.launch { refreshRequests.collect { runRefresh() } }
        viewModelScope.launch {
            val hasDestination = _state
                .map { it.destination != null }
                .distinctUntilChanged()
            combine(attention, hasDestination, ::Pair).collectLatest { (attention, pinned) ->
                val interval = pollInterval(attention, pinned) ?: return@collectLatest
                while (isActive) {
                    refresh()
                    delay(interval)
                }
            }
        }
    }

    fun onAttentionChanged(value: Attention) {
        attention.value = value
    }

    /**
     * How often to poll, or null to stop.
     *
     * Ambient earns a poll only while a destination is pinned: that is the case where the app is
     * watching a dock fill up on the rider's behalf and the answer matters even though nobody is
     * looking. With nothing pinned, a dimmed screen has nothing to say that a request would change.
     */
    private fun pollInterval(attention: Attention, hasDestination: Boolean): Long? = when (attention) {
        Attention.AWAY -> null
        Attention.WATCHING -> REFRESH_INTERVAL_MILLIS
        Attention.AMBIENT -> AMBIENT_REFRESH_INTERVAL_MILLIS.takeIf { hasDestination }
    }

    /** A new fix. Cheap to call at GPS rate — only the ranking runs, not a network request. */
    fun onPosition(point: GeoPoint) {
        // The seed position from the last session does not count as a fix: the first real one has
        // to be allowed to correct whatever it was we opened with.
        val previous = position.takeIf { hasRealFix }
        hasRealFix = true
        position = point
        rememberPosition(point)
        recompute()
        // Riding out of the swept radius is the other thing that justifies an off-schedule fetch.
        if (previous == null || previous.distanceTo(point) > REFETCH_AFTER_METRES) refresh()
    }

    /**
     * Persists the fix, but not every fix.
     *
     * This value exists only as a cold-start seed for the tile and the complication. Writing it on
     * every GPS callback queued a disk write a second for a figure nothing reads until the next
     * time the watch wakes a background surface.
     */
    private fun rememberPosition(point: GeoPoint) {
        val last = persistedPosition
        if (last != null && last.distanceTo(point) < PERSIST_AFTER_METRES) return
        persistedPosition = point
        prefs.lastKnownPosition = point
    }

    fun cycleMode() {
        val next = _state.value.mode.next()
        prefs.mode = next
        _state.update { it.copy(mode = next) }
        recompute()
    }

    fun toggleFavourite(dockId: Int) {
        prefs.toggleFavourite(dockId)
        if (dockId !in prefs.favourites) savedDocks.remove(dockId)
        _state.update { it.copy(favourites = prefs.favourites) }
        recompute()
        // A deliberate save should show a count now, not at the next slow saved-dock sweep.
        savedDocksRefreshedAt = 0L
        refresh()
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
        refreshRequests.tryEmit(Unit)
    }

    private suspend fun runRefresh() {
        val here = position ?: return
        _state.update { it.copy(isRefreshing = true) }
        try {
            snapshot = repository.docksNear(here)
            refreshDestinationDock()
            refreshSavedDocks()
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed", e)
        } finally {
            _state.update { it.copy(isRefreshing = false) }
            recompute()
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

    /**
     * Keeps counts current for saved docks the sweep did not reach.
     *
     * A commuter has two or three of these, so it is two or three requests — and it is what lets
     * you check your home dock before setting off rather than after arriving at it.
     */
    private suspend fun refreshSavedDocks() {
        val wanted = prefs.favourites - snapshot.docks.map { it.id }.toSet()
        savedDocks.keys.retainAll(wanted)

        val now = System.currentTimeMillis()
        if (now - savedDocksRefreshedAt < SAVED_REFRESH_INTERVAL_MILLIS) return
        savedDocksRefreshedAt = now

        // Concurrently, and capped. These used to go out one at a time on every twenty-second
        // cycle, so three saved docks plus a destination plus the sweep was five sequential
        // requests a cycle against an API that rate limits per IP.
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
        val mode = _state.value.mode
        if (here == null) {
            _state.update { it.copy(hasPosition = false) }
            return
        }

        fun rank(dock: Dock) = RankedDock(
            dock = dock,
            distanceMetres = here.distanceTo(dock.position).roundToInt(),
            bearingDegrees = here.bearingTo(dock.position),
            count = dock.availability?.countFor(mode)
        )

        val ranked = rankDocks(here, snapshot.docks, mode)
        val pinned = prefs.destination
        val destinationState = pinned?.let { destination ->
            val rankedDestination = destinationDock?.let(::rank)
            DestinationState(destination, rankedDestination, destinationHealth(rankedDestination))
        }

        // Saved docks already in the deck are left there; only the far-off ones need a page.
        val nearbyIds = ranked.map { it.id }.toSet()
        val saved = savedDocks.values
            .filter { it.id !in nearbyIds && it.id != pinned?.dockId }
            .map(::rank)
            .sortedBy { it.distanceMetres }

        _state.update {
            it.copy(
                docks = ranked,
                savedDocks = saved,
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

        /** Dimmed screen, destination pinned: still watching, just not urgently. */
        const val AMBIENT_REFRESH_INTERVAL_MILLIS = 120_000L

        const val REFETCH_AFTER_METRES = 250.0

        /** Far enough that the seed position would actually name different docks. */
        const val PERSIST_AFTER_METRES = 50.0

        /** Saved docks are, by definition, not where you are. */
        const val SAVED_REFRESH_INTERVAL_MILLIS = 120_000L

        /** A commuter has two or three. Twenty must not become twenty requests. */
        const val MAX_SAVED_FETCHES = 6
    }
}
