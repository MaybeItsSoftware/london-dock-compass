package uk.co.maybeitsadam.cycles.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BikePoint(
    val id: String,
    val commonName: String,
    val lat: Double,
    val lon: Double,
    val placeType: String? = null,
    val additionalProperties: List<AdditionalProperty> = emptyList(),
    val children: List<JsonElement> = emptyList(),
    val childrenUrls: List<String> = emptyList()
)

@Serializable
data class AdditionalProperty(
    val category: String,
    val key: String,
    val sourceSystemKey: String,
    val value: String,
    val modified: String
)

data class BikePointStatus(
    val id: String,
    val name: String,
    val bikes: Int,
    val eBikes: Int,
    val standardBikes: Int,
    val emptyDocks: Int,
    val totalDocks: Int
)
