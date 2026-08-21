package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.co.maybeitssoftware.londondockcompass.R
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/** Where a set of docks came from, so the UI can be honest about how much to trust it. */
enum class DockSource { LIVE, CACHED, BUNDLED }

data class DockSnapshot(
    val docks: List<Dock>,
    val source: DockSource,
    val fetchedAtMillis: Long
) {
    companion object {
        val EMPTY = DockSnapshot(emptyList(), DockSource.BUNDLED, 0L)
    }
}

/**
 * The single way anything in this app asks "what docks are near here?".
 *
 * Shared by the screen, the tile and the complication, which is why the cache lives in a companion
 * object: three surfaces asking the same question within a few seconds should cost one request.
 */
class DockRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = TflBikePointApi(appContext.getString(R.string.tfl_app_key))
    private val bundled by lazy { BundledDockSource(appContext) }
    private val cacheStore by lazy { SnapshotStore(appContext) }

    /**
     * Docks within [radiusMetres], live if we can get them.
     *
     * Falls back through a short-lived cache to the bundled coordinates, so the arrow keeps
     * pointing somewhere sensible in a tunnel, on a dead network, or against a rate limit.
     */
    suspend fun docksNear(
        point: GeoPoint,
        radiusMetres: Int = DEFAULT_RADIUS_METRES
    ): DockSnapshot = withContext(Dispatchers.IO) {
        // Every path through here can touch disk: the cache is SharedPreferences holding a JSON
        // blob, and the bundled fallback parses a quarter-megabyte of dock coordinates. Callers
        // launch from viewModelScope, so without this the whole lot runs on the main thread.
        val now = System.currentTimeMillis()
        cached(point, now)?.let { return@withContext it }

        lock.withLock {
            // Another caller may have refreshed while we waited for the lock.
            cached(point, now)?.let { return@withLock it }
            try {
                val docks = api.docksNear(point, radiusMetres)
                DockSnapshot(docks, DockSource.LIVE, System.currentTimeMillis()).also {
                    memory = CachedAt(point, it)
                    cacheStore.write(point, it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Live dock fetch failed, falling back", e)
                staleFallback(point, now)
                    ?: DockSnapshot(bundled.docksNear(point), DockSource.BUNDLED, now)
            }
        }
    }

    private fun cached(point: GeoPoint, now: Long): DockSnapshot? {
        val held = held() ?: return null
        return held.snapshot.takeIf {
            CachePolicy.isFresh(held.origin, it.fetchedAtMillis, point, now)
        }
    }

    private fun staleFallback(point: GeoPoint, now: Long): DockSnapshot? {
        val held = held() ?: return null
        if (!CachePolicy.isUsableFallback(held.origin, held.snapshot.fetchedAtMillis, point, now)) {
            return null
        }
        return held.snapshot.copy(source = DockSource.CACHED)
    }

    private fun held(): CachedAt? =
        memory ?: cacheStore.read()?.let { CachedAt(it.first, it.second) }

    private data class CachedAt(val origin: GeoPoint, val snapshot: DockSnapshot)

    companion object {
        private const val TAG = "DockRepository"

        /** Comfortably past the far side of a London block, without pulling in half the city. */
        const val DEFAULT_RADIUS_METRES = 800

        private val lock = Mutex()

        @Volatile
        private var memory: CachedAt? = null

        /** Test seam: drops the process-wide cache. */
        fun clearCache() {
            memory = null
        }
    }
}
