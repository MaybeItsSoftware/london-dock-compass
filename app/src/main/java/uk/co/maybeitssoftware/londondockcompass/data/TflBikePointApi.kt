package uk.co.maybeitssoftware.londondockcompass.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint
import java.time.Instant

/**
 * The TfL BikePoint API, used through its radius query.
 *
 * One request returns every dock within a radius *together with* its live counts, so this replaces
 * both the bundled coordinate lookup and the five per-dock status calls the app used to fire. It
 * also means new docks appear without shipping an app update.
 */
class TflBikePointApi(private val appKey: String? = null) {

    suspend fun docksNear(point: GeoPoint, radiusMetres: Int): List<Dock> {
        val response: PlacesResponse = client.get(BIKE_POINT_URL) {
            parameter("lat", point.lat)
            parameter("lon", point.lon)
            parameter("radius", radiusMetres)
            appKey?.takeIf { it.isNotBlank() }?.let { parameter("app_key", it) }
        }.body()
        return response.places.mapNotNull { it.toDock() }
    }

    /**
     * One named dock.
     *
     * The only place a per-dock request earns its keep: a pinned destination is usually well
     * outside the radius we sweep around the rider, and its free-space count is the whole reason
     * for pinning it.
     */
    suspend fun dock(id: Int): Dock? {
        val place: PlaceDto = client.get("$BIKE_POINT_URL/BikePoints_$id") {
            appKey?.takeIf { it.isNotBlank() }?.let { parameter("app_key", it) }
        }.body()
        return place.toDock()
    }

    private companion object {
        const val BIKE_POINT_URL = "https://api.tfl.gov.uk/BikePoint"

        // Shared across the app, the tile and the complication — all three hit an API that rate
        // limits per IP, so they had better pool their connections.
        val client by lazy {
            HttpClient(Android) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; coerceInputValues = true })
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = 8_000
                    connectTimeoutMillis = 5_000
                }
            }
        }
    }
}

@Serializable
private data class PlacesResponse(val places: List<PlaceDto> = emptyList())

@Serializable
private data class PlaceDto(
    val id: String,
    val commonName: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val additionalProperties: List<PropertyDto> = emptyList()
)

@Serializable
private data class PropertyDto(
    val key: String,
    val value: String = "",
    val modified: String? = null
)

private fun PlaceDto.toDock(): Dock? {
    // Ids arrive as "BikePoints_341"; everything else in the app keys on the bare integer.
    val numericId = id.substringAfterLast('_').toIntOrNull() ?: return null
    val props = additionalProperties.associateBy { it.key }

    fun int(key: String): Int? = props[key]?.value?.trim()?.toIntOrNull()
    fun bool(key: String, default: Boolean): Boolean =
        props[key]?.value?.trim()?.lowercase()?.toBooleanStrictOrNull() ?: default

    val bikes = int("NbBikes")
    val emptyDocks = int("NbEmptyDocks")

    return Dock(
        id = numericId,
        name = commonName,
        position = GeoPoint(lat, lon),
        // A dock that is uninstalled or locked cannot take or give a bike, whatever else it reports.
        inService = bool("Installed", true) && !bool("Locked", false),
        availability = if (bikes == null || emptyDocks == null) null else Availability(
            bikes = bikes,
            eBikes = int("NbEBikes") ?: 0,
            standardBikes = int("NbStandardBikes") ?: bikes,
            emptyDocks = emptyDocks,
            totalDocks = int("NbDocks") ?: (bikes + emptyDocks),
            // Only the count properties say anything about when the counts were observed. The
            // first property TfL happens to list is usually TerminalName or InstallDate, whose
            // modified stamp can be years old — reading that made live figures look ancient.
            observedAtMillis = COUNT_KEYS
                .mapNotNull { props[it]?.modified?.toEpochMillis() }
                .maxOrNull()
                ?: System.currentTimeMillis()
        )
    )
}

/** The properties whose `modified` stamp actually tracks the availability figures. */
private val COUNT_KEYS = listOf("NbBikes", "NbEmptyDocks", "NbEBikes", "NbStandardBikes")

private fun String.toEpochMillis(): Long? = try {
    // Some TfL fields stamp UTC without the trailing Z that Instant insists on.
    Instant.parse(if (endsWith("Z")) this else this + "Z").toEpochMilli()
} catch (e: Exception) {
    null
}
