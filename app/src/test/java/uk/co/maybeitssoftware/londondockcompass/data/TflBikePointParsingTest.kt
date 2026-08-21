package uk.co.maybeitssoftware.londondockcompass.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The TfL BikePoint response, parsed exactly as the client parses it.
 *
 * This is the layer with an external contract and therefore the one that fails silently: rename a
 * property at TfL's end and the app does not crash, it quietly reports NO LIVE DATA forever. The
 * fixture below is the real shape of a radius query — nested counts in `additionalProperties`,
 * prefixed ids, a `modified` stamp per property — so a change to any of that shows up here.
 */
class TflBikePointParsingTest {

    private val json: Json = tflJson

    private fun property(key: String, value: String, modified: String? = OBSERVED) =
        """{"key":"$key","value":"$value"${modified?.let { ""","modified":"$it"""" } ?: ""}}"""

    private fun place(
        id: String,
        name: String = "Craven Street, Strand",
        properties: List<String>
    ) = """
        {
          "id": "$id",
          "commonName": "$name",
          "lat": 51.50812,
          "lon": -0.12613,
          "placeType": "BikePoint",
          "additionalProperties": [${properties.joinToString(",")}]
        }
    """.trimIndent()

    private fun parse(vararg places: String) =
        json.decodeFromString<PlacesResponse>("""{"places":[${places.joinToString(",")}]}""")
            .places

    @Test
    fun `a healthy dock parses into counts the ranking can use`() {
        val dock = parse(
            place(
                "BikePoints_341",
                properties = listOf(
                    // TerminalName is listed first by TfL and carries an ancient modified stamp;
                    // it must not be mistaken for when the counts were observed.
                    property("TerminalName", "001023", modified = INSTALLED_LONG_AGO),
                    property("Installed", "true", modified = INSTALLED_LONG_AGO),
                    property("Locked", "false"),
                    property("NbBikes", "19"),
                    property("NbEmptyDocks", "5"),
                    property("NbDocks", "25"),
                    property("NbStandardBikes", "16"),
                    property("NbEBikes", "3")
                )
            )
        ).single().toDock()

        assertNotNull(dock)
        assertEquals(341, dock!!.id)
        assertEquals("Craven Street, Strand", dock.name)
        assertTrue(dock.inService)
        val availability = dock.availability!!
        assertEquals(19, availability.bikes)
        assertEquals(3, availability.eBikes)
        assertEquals(16, availability.standardBikes)
        assertEquals(5, availability.emptyDocks)
        // Broken docks count in neither figure, so the total is not 19 + 5.
        assertEquals(25, availability.totalDocks)
    }

    @Test
    fun `the observation time comes from the counts, not from whatever is listed first`() {
        val dock = parse(
            place(
                "BikePoints_1",
                properties = listOf(
                    property("TerminalName", "001023", modified = INSTALLED_LONG_AGO),
                    property("NbBikes", "4"),
                    property("NbEmptyDocks", "8")
                )
            )
        ).single().toDock()!!

        assertEquals(
            Instant.parse(OBSERVED + "Z").toEpochMilli(),
            dock.availability!!.observedAtMillis
        )
    }

    @Test
    fun `missing counts mean unknown, never zero`() {
        // Offline or mid-outage TfL omits the count properties. Reporting that as an empty rack
        // would send a rider to a dock the app has no evidence about.
        val dock = parse(
            place("BikePoints_7", properties = listOf(property("Installed", "true")))
        ).single().toDock()!!

        assertNull(dock.availability)
    }

    @Test
    fun `a locked or uninstalled dock is out of service whatever else it reports`() {
        val locked = parse(
            place(
                "BikePoints_8",
                properties = listOf(
                    property("Installed", "true"),
                    property("Locked", "true"),
                    property("NbBikes", "12"),
                    property("NbEmptyDocks", "3")
                )
            )
        ).single().toDock()!!
        assertEquals(false, locked.inService)

        val uninstalled = parse(
            place(
                "BikePoints_9",
                properties = listOf(
                    property("Installed", "false"),
                    property("NbBikes", "0"),
                    property("NbEmptyDocks", "20")
                )
            )
        ).single().toDock()!!
        assertEquals(false, uninstalled.inService)
    }

    @Test
    fun `total docks falls back to the two counts when TfL omits it`() {
        val dock = parse(
            place(
                "BikePoints_10",
                properties = listOf(property("NbBikes", "6"), property("NbEmptyDocks", "9"))
            )
        ).single().toDock()!!

        val availability = dock.availability!!
        assertEquals(15, availability.totalDocks)
        // Standard bikes default to the whole count rather than to zero.
        assertEquals(6, availability.standardBikes)
    }

    @Test
    fun `an id that is not a BikePoint is dropped rather than guessed at`() {
        assertNull(parse(place("NotABikePoint", properties = emptyList())).single().toDock())
    }

    @Test
    fun `unknown properties and new fields do not break the parse`() {
        // ignoreUnknownKeys is what lets TfL add fields without shipping an app update.
        val response = json.decodeFromString<PlacesResponse>(
            """{"centrePoint":[51.5,-0.12],"places":[{"id":"BikePoints_2","commonName":"X",
               "lat":51.5,"lon":-0.12,"somethingNew":{"a":1},
               "additionalProperties":[{"key":"NbBikes","value":"1","sourceSystemKey":"BikePoints"},
                                       {"key":"NbEmptyDocks","value":"2"}]}]}"""
        )
        assertEquals(1, response.places.size)
        assertEquals(1, response.places.single().toDock()!!.availability!!.bikes)
    }

    @Test
    fun `timestamps parse with or without the trailing Z that Instant insists on`() {
        val withZ = "2026-08-21T09:15:00.000Z".toEpochMillis()
        val without = "2026-08-21T09:15:00.000".toEpochMillis()
        assertEquals(withZ, without)
        assertNotNull(withZ)
        assertNull("not a timestamp".toEpochMillis())
    }

    private companion object {
        const val OBSERVED = "2026-08-21T09:15:00.000"
        const val INSTALLED_LONG_AGO = "2010-07-30T00:00:00.000"
    }
}
