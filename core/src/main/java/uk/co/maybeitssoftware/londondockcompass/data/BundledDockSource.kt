package uk.co.maybeitssoftware.londondockcompass.data

import android.content.Context
import com.github.davidmoten.geo.GeoHash
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.co.maybeitssoftware.londondockcompass.core.R
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/**
 * The dock list baked into the APK, indexed by geohash.
 *
 * This is the offline floor, not the primary source: it knows where every dock was at build time
 * and nothing at all about what is in them, so docks it returns carry a null availability and the
 * UI says "no live data" rather than implying an empty rack.
 */
class BundledDockSource(private val context: Context) {

    private val byGeoHash: Map<String, List<BundledStation>> by lazy {
        context.resources.openRawResource(R.raw.docklocations).use { stream ->
            json.decodeFromString(stream.bufferedReader().readText())
        }
    }

    /** Widens the geohash ring until it has enough candidates or runs out of patience. */
    fun docksNear(point: GeoPoint, minCount: Int = 8): List<Dock> {
        val origin = GeoHash.encodeHash(point.lat, point.lon, GEOHASH_PRECISION)
        val visited = mutableSetOf(origin).apply { addAll(GeoHash.neighbours(origin)) }
        val found = visited.flatMap { byGeoHash[it].orEmpty() }.toMutableSet()

        var frontier: Set<String> = visited.toSet()
        repeat(MAX_RINGS) {
            if (found.size >= minCount) return found.map { it.toDock() }
            val next = frontier.flatMap { GeoHash.neighbours(it) }.toMutableSet()
            next.removeAll(visited)
            visited.addAll(next)
            frontier = next
            next.forEach { hash -> byGeoHash[hash]?.let(found::addAll) }
        }
        return found.map { it.toDock() }
    }

    private companion object {
        const val GEOHASH_PRECISION = 7
        const val MAX_RINGS = 5
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class BundledStation(
    val id: Int,
    val name: String,
    val lat: Double,
    val long: Double,
    val installed: Boolean = true,
    val locked: Boolean = false,
    val temporary: Boolean = false
) {
    fun toDock() = Dock(
        id = id,
        name = name,
        position = GeoPoint(lat, long),
        inService = installed && !locked,
        availability = null
    )
}
