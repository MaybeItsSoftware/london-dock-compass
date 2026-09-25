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
import uk.co.maybeitssoftware.londondockcompass.core.R
import uk.co.maybeitssoftware.londondockcompass.data.DestinationWatcher
import uk.co.maybeitssoftware.londondockcompass.data.DockRepository
import uk.co.maybeitssoftware.londondockcompass.data.DockSnapshot
import uk.co.maybeitssoftware.londondockcompass.data.DockSource
import uk.co.maybeitssoftware.londondockcompass.data.RiderPreferences
import uk.co.maybeitssoftware.londondockcompass.data.RiderSync
import uk.co.maybeitssoftware.londondockcompass.data.TflBikePointApi
import uk.co.maybeitssoftware.londondockcompass.domain.ArrivalTracker
import uk.co.maybeitssoftware.londondockcompass.domain.Destination
import uk.co.maybeitssoftware.londondockcompass.domain.DestinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import uk.co.maybeitssoftware.londondockcompass.domain.Purpose
import uk.co.maybeitssoftware.londondockcompass.domain.RankedDock
import uk.co.maybeitssoftware.londondockcompass.domain.bearingTo
import uk.co.maybeitssoftware.londondockcompass.domain.destinationHealth
import uk.co.maybeitssoftware.londondockcompass.domain.distanceTo
import uk.co.maybeitssoftware.londondockcompass.domain.nearestDocks
import kotlin.math.roundToInt

/** Everything the screen needs, recomputed whenever the rider or the docks move. */
data class CompassUiState(
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
    val health: DestinationHealth,
    /** Where to go instead, once the destination starts to fail. */
    val alternative: RankedDock? = null
)

/**
 * Holds the rider's position, the dock snapshot and the two preferences that reorder everything.
 *
 * There is no ride mode here. The watch used to show one count at a time behind a chip you tapped
 * round a ring; every card now carries bikes, e-bikes and spaces together, so the deck is simply
 * nearest first.
 *
 * Kept in a ViewModel so a wrist-flick rotation or an ambient-mode round trip does not restart the
 * polling loop or lose a pinned destination.
 */
class CompassViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DockRepository(app)
    private val prefs = RiderPreferences(app)
    private val sync = RiderSync(app)
    private val api = TflBikePointApi(app.getString(R.string.tfl_app_key))
    private val watcher = DestinationWatcher(api)
    private val arrivals = ArrivalTracker()

    private val _state = MutableStateFlow(
        CompassUiState(favourites = prefs.favourites)
    )
    val state: StateFlow<CompassUiState> = _state.asStateFlow()

    private var position: GeoPoint? = prefs.lastKnownPosition
    private var snapshot: DockSnapshot = DockSnapshot.EMPTY
    private var destinationDock: Dock? = null
    private var alternativeDock: Dock? = null

    /**
     * The two preferences that reorder the deck, held in memory and written through on change.
     *
     * recompute() runs on every GPS fix — up to once a second — and used to re-read both from
     * SharedPreferences each time, rebuilding the favourites Set from strings on every pass.
     */
    private var favourites: Set<Int> = prefs.favourites
    private var destination: Destination? = prefs.destination

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
        destination?.let { pinned ->
            _state.update { it.copy(destination = DestinationState(pinned, null, DestinationHealth.UNKNOWN)) }
        }
        viewModelScope.launch { refreshRequests.collect { runRefresh() } }
        // A dock pinned or saved on the phone lands in these preferences via the listener service,
        // in this same process. Without collecting it here the running deck would not see it until
        // the next time the app was killed and reopened.
        viewModelScope.launch { prefs.changes.collect { adoptPreferences() } }
        // Cold start: the phone may have published while this app was not running.
        viewModelScope.launch { sync.pull()?.let { if (prefs.merge(it)) adoptPreferences() } }
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
    fun onPosition(point: GeoPoint, accuracyMetres: Float? = null) {
        // The seed position from the last session does not count as a fix: the first real one has
        // to be allowed to correct whatever it was we opened with.
        val previous = position.takeIf { hasRealFix }
        hasRealFix = true
        position = point
        rememberPosition(point)
        // Arriving at the pinned dock and then leaving it ends the trip, so the pin goes with it.
        destination?.let { pinned ->
            val metres = point.distanceTo(pinned.position).roundToInt()
            if (arrivals.update(pinned.dockId, metres, accuracyMetres)) clearDestination()
        }
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

    /**
     * Picks up saved docks and the destination after something other than this ViewModel wrote
     * them — the phone, via sync. Our own writes come back through here too, which is harmless: the
     * values already match and nothing is refetched.
     */
    private fun adoptPreferences() {
        val newFavourites = prefs.favourites
        val newDestination = prefs.destination
        if (newFavourites == favourites && newDestination == destination) return

        val destinationMoved = newDestination?.dockId != destination?.dockId
        favourites = newFavourites
        destination = newDestination
        if (destinationMoved) {
            destinationDock = null
            alternativeDock = null
        }
        savedDocks.keys.retainAll(favourites)
        _state.update {
            it.copy(
                favourites = favourites,
                destination = newDestination?.let { pinned ->
                    DestinationState(pinned, null, DestinationHealth.UNKNOWN)
                }
            )
        }
        recompute()
        // A newly synced dock should show a count now, not at the next slow sweep.
        savedDocksRefreshedAt = 0L
        refresh()
    }

    /** Sends saved docks and the destination to the phone. */
    private fun publish() {
        viewModelScope.launch { sync.push(prefs.snapshot()) }
    }

    fun toggleFavourite(dockId: Int) {
        prefs.toggleFavourite(dockId)
        favourites = prefs.favourites
        if (dockId !in favourites) savedDocks.remove(dockId)
        _state.update { it.copy(favourites = favourites) }
        publish()
        recompute()
        // A deliberate save should show a count now, not at the next slow saved-dock sweep.
        savedDocksRefreshedAt = 0L
        refresh()
    }

    fun pinDestination(dock: RankedDock, purpose: Purpose) {
        pin(Destination(dock.id, dock.name, dock.dock.position, purpose), dock.dock)
    }

    /** Takes the suggested alternative, for the same purpose as the pin it replaces. */
    fun switchToAlternative() {
        val current = destination ?: return
        val alternative = alternativeDock ?: return
        pin(
            Destination(alternative.id, alternative.name, alternative.position, current.purpose),
            alternative
        )
    }

    private fun pin(pinned: Destination, dock: Dock) {
        prefs.destination = pinned
        destination = pinned
        destinationDock = dock
        alternativeDock = null
        _state.update {
            it.copy(destination = DestinationState(pinned, null, DestinationHealth.UNKNOWN))
        }
        publish()
        recompute()
        refresh()
    }

    fun clearDestination() {
        prefs.destination = null
        destination = null
        destinationDock = null
        alternativeDock = null
        _state.update { it.copy(destination = null) }
        publish()
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
     * request of its own — unless the sweep happened to cover it. Once it starts to fail, the
     * watcher also finds somewhere to go instead.
     */
    private suspend fun refreshDestinationDock() {
        val pinned = destination ?: return
        val report = watcher.check(pinned, position, snapshot.docks, known = destinationDock)
        // The pin may have changed while the request was out.
        if (destination != pinned) return
        destinationDock = report.dock
        alternativeDock = report.alternative
    }

    /**
     * Keeps counts current for saved docks the sweep did not reach.
     *
     * A commuter has two or three of these, so it is two or three requests — and it is what lets
     * you check your home dock before setting off rather than after arriving at it.
     */
    private suspend fun refreshSavedDocks() {
        val wanted = favourites - snapshot.docks.map { it.id }.toSet()
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
        if (here == null) {
            _state.update { it.copy(hasPosition = false) }
            return
        }

        // count stays null on the deck: cards read all three figures off the availability. The
        // destination is the exception — it is judged on the one figure its purpose depends on,
        // bikes for a pick-up and spaces for a drop-off, and its health follows that.
        fun rank(dock: Dock, count: Int? = null) = RankedDock(
            dock = dock,
            distanceMetres = here.distanceTo(dock.position).roundToInt(),
            bearingDegrees = here.bearingTo(dock.position),
            count = count
        )

        val ranked = nearestDocks(here, snapshot.docks)
        val pinned = destination
        val destinationState = pinned?.let {
            val rankedDestination = destinationDock?.let { dock ->
                rank(dock, count = dock.availability?.let(it.purpose::countIn))
            }
            DestinationState(
                destination = it,
                ranked = rankedDestination,
                health = destinationHealth(rankedDestination),
                alternative = alternativeDock?.let { dock -> rank(dock) }
            )
        }

        // Saved docks already in the deck are left there; only the far-off ones need a page.
        val nearbyIds = ranked.map { it.id }.toSet()
        val saved = savedDocks.values
            .filter { it.id !in nearbyIds && it.id != pinned?.dockId }
            .map { rank(it) }
            .sortedBy { it.distanceMetres }

        _state.update {
            it.copy(
                docks = ranked,
                savedDocks = saved,
                destination = destinationState,
                favourites = favourites,
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
