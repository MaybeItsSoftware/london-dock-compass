package uk.co.maybeitssoftware.londondockcompass.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class BikePointRepository {

    // Lazy so the HttpClient is not initialised on the main thread at app startup
    private val client by lazy {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }
    }

    suspend fun getBikePoint(id: String): BikePointStatus? {
        // The ID in the JSON file is just an integer (e.g. 1), but the API expects "BikePoints_{id}"
        // However, looking at the doc search, it seems we can get by ID.
        // Let's assume the passed ID is the full ID if possible, otherwise we prepend.
        // Actually, the existing file has "id": 1.
        // TfL API usually uses "BikePoints_1".
        
        val fullId = if (id.startsWith("BikePoints_")) id else "BikePoints_$id"

        return try {
            val bikePoint: BikePoint = client.get("https://api.tfl.gov.uk/BikePoint/$fullId").body()
            
            fun prop(key: String) = bikePoint.additionalProperties.find { it.key == key }?.value?.toIntOrNull() ?: 0
            BikePointStatus(
                id = bikePoint.id,
                name = bikePoint.commonName,
                bikes = prop("NbBikes"),
                eBikes = prop("NbEBikes"),
                standardBikes = prop("NbStandardBikes"),
                emptyDocks = prop("NbEmptyDocks"),
                totalDocks = prop("NbDocks")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
