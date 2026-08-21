package uk.co.maybeitssoftware.londondockcompass.data

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.maybeitssoftware.londondockcompass.domain.Availability
import uk.co.maybeitssoftware.londondockcompass.domain.Dock
import uk.co.maybeitssoftware.londondockcompass.domain.GeoPoint

/**
 * What survives being written to disk and read back.
 *
 * The tile and the complication are cold-started with no warning and render from this, so a dock
 * that comes back subtly wrong is a wrong answer on the watch face with nothing on screen to
 * suggest it should be doubted.
 */
class SnapshotStoreTest {

    private val dock = Dock(
        id = 341,
        name = "Craven Street, Strand",
        position = GeoPoint(51.50812, -0.12613),
        inService = true,
        availability = Availability(
            bikes = 19,
            eBikes = 3,
            standardBikes = 16,
            emptyDocks = 5,
            totalDocks = 25,
            observedAtMillis = 1_700_000_000_000L
        )
    )

    private fun roundTrip(dock: Dock): Dock {
        val encoded = snapshotJson.encodeToString(
            StoredSnapshot(51.5, -0.12, 1_700_000_000_000L, listOf(dock.toStored()))
        )
        return snapshotJson.decodeFromString<StoredSnapshot>(encoded).docks.single().toDock()
    }

    @Test
    fun `a dock survives the round trip intact`() {
        assertEquals(dock, roundTrip(dock))
    }

    @Test
    fun `unknown availability comes back unknown, not as an empty rack`() {
        // The single most important assertion in this file. A null here means "we do not know";
        // decoding it as zero would tell a rider a full dock is empty.
        val unknown = dock.copy(availability = null)
        assertNull(roundTrip(unknown).availability)
        assertEquals(unknown, roundTrip(unknown))
    }

    @Test
    fun `an out of service dock stays out of service`() {
        assertEquals(false, roundTrip(dock.copy(inService = false)).inService)
    }

    @Test
    fun `coordinates keep full double precision`() {
        // Stored as doubles, so a dock does not drift between sessions.
        val precise = dock.copy(position = GeoPoint(51.508123456789, -0.126134567891))
        assertEquals(precise.position, roundTrip(precise).position)
    }

    @Test
    fun `a snapshot written by an older build still reads`() {
        // totalDocks, standardBikes and observedAtMillis arrived after the first release; older
        // payloads omit them and must still decode rather than throw the whole cache away.
        val legacy = """
            {"lat":51.5,"lon":-0.12,"fetchedAtMillis":1700000000000,
             "docks":[{"id":341,"name":"Craven Street","lat":51.5,"lon":-0.12,
                       "inService":true,"bikes":4,"emptyDocks":6}]}
        """.trimIndent()
        val decoded = snapshotJson.decodeFromString<StoredSnapshot>(legacy).docks.single().toDock()
        val availability = decoded.availability!!

        assertEquals(4, availability.bikes)
        assertEquals(6, availability.emptyDocks)
        assertEquals(10, availability.totalDocks)
        assertEquals(4, availability.standardBikes)
    }
}
